package dev.brahmkshatriya.echo.ui.listentogether

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.brahmkshatriya.echo.playback.PlaybackConnection
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

data class Participant(
    val id: String,
    val name: String,
    val isHost: Boolean = false
)

sealed class ListenTogetherState {
    object Idle : ListenTogetherState()
    object Connecting : ListenTogetherState()
    data class Active(
        val sessionCode: String,
        val isHost: Boolean,
        val participants: List<Participant> = emptyList()
    ) : ListenTogetherState()
    data class Error(val message: String) : ListenTogetherState()
}

data class SyncEvent(
    val trackId: String,
    val extensionId: String?,
    val positionMs: Long,
    val isPlaying: Boolean
)

class ListenTogetherViewModel(
    private val playbackConnection: PlaybackConnection
) : ViewModel() {

    private val _state = MutableStateFlow<ListenTogetherState>(ListenTogetherState.Idle)
    val state: StateFlow<ListenTogetherState> = _state

    private val _syncEvent = MutableSharedFlow<SyncEvent>()
    val syncEvent: SharedFlow<SyncEvent> = _syncEvent

    private val firebase = ListenTogetherFirebaseClient()
    private var listenJob: Job? = null

    private fun generateCode(): String = Random.nextInt(100000, 999999).toString()

    fun createSession(trackId: String?, extensionId: String?) {
        val code = generateCode()
        _state.value = ListenTogetherState.Active(code, true, listOf(Participant(firebase.clientId, "You", true)))
        startListening(code, true)
    }

    fun startListening(code: String, isHost: Boolean) {
        listenJob?.cancel()
        listenJob = viewModelScope.launch {
            firebase.connect(code).collect { msg ->
                if (msg.type == "SYNC" && msg.trackId != null) {
                    if (!isHost) {
                        val currentId = playbackConnection.currentMetadata.value?.id
                        if (currentId != msg.trackId && msg.extensionId != null) {
                            playbackConnection.playTrack(msg.trackId, msg.extensionId)
                        }
                        val pos = playbackConnection.playbackState.value?.position ?: 0L
                        if (Math.abs(pos - msg.positionMs) > 3000) {
                            playbackConnection.seekTo(msg.positionMs)
                        }
                        if (msg.isPlaying) playbackConnection.play() else playbackConnection.pause()
                    }
                    _syncEvent.emit(SyncEvent(msg.trackId, msg.extensionId, msg.positionMs, msg.isPlaying))
                }
            }
        }
    }

    fun joinSession(code: String) {
        _state.value = ListenTogetherState.Active(code, false)
        startListening(code, false)
    }

    fun leaveSession() {
        listenJob?.cancel()
        _state.value = ListenTogetherState.Idle
    }
}
