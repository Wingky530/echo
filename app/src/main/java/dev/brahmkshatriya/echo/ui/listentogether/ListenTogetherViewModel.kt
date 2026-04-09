package dev.brahmkshatriya.echo.ui.listentogether

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

data class Participant(val id: String, val name: String, val isHost: Boolean = false)

sealed class ListenTogetherState {
    object Idle : ListenTogetherState()
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
    private val playerViewModel: PlayerViewModel
) : ViewModel() {

    private val _state = MutableStateFlow<ListenTogetherState>(ListenTogetherState.Idle)
    val state: StateFlow<ListenTogetherState> = _state

    private val _syncEvent = MutableSharedFlow<SyncEvent>()
    val syncEvent: SharedFlow<SyncEvent> = _syncEvent

    private val _participants = MutableStateFlow<List<Participant>>(emptyList())
    val participants: StateFlow<List<Participant>> = _participants

    private val firebase = ListenTogetherFirebaseClient()
    private var listenJob: Job? = null
    private var participantsJob: Job? = null

    private fun generateCode(): String = Random.nextInt(100000, 999999).toString()

    fun createSession(trackId: String?, extensionId: String?) {
        val code = generateCode()
        _state.value = ListenTogetherState.Active(code, true,
            listOf(Participant(firebase.clientId, "You", true)))
        startListening(code, true)
        observeParticipants(code)
    }

    fun startListening(code: String, isHost: Boolean) {
        listenJob?.cancel()
        listenJob = viewModelScope.launch {
            firebase.connect(code).collect { msg ->
                if (msg.type == "SYNC" && msg.trackId != null) {
                    if (!isHost) {
                        val browser = playerViewModel.browser.value
                        if (browser != null) {
                            val currentId = playerViewModel.playerState.current.value
                                ?.mediaItem?.mediaId
                            if (currentId != msg.trackId) {
                                val idx = playerViewModel.queue.indexOfFirst {
                                    it.mediaId == msg.trackId
                                }
                                if (idx >= 0) playerViewModel.play(idx)
                            }
                            browser.seekTo(msg.positionMs)
                            if (msg.isPlaying) browser.play() else browser.pause()
                        }
                    }
                    _syncEvent.emit(SyncEvent(
                        msg.trackId, msg.extensionId, msg.positionMs, msg.isPlaying
                    ))
                }
            }
        }
    }

    private fun observeParticipants(code: String) {
        participantsJob?.cancel()
        participantsJob = viewModelScope.launch {
            firebase.observeParticipants(code).collect { list ->
                _participants.value = list
                val current = _state.value
                if (current is ListenTogetherState.Active) {
                    _state.value = current.copy(participants = list)
                }
            }
        }
    }

    fun joinSession(code: String) {
        _state.value = ListenTogetherState.Active(code, false)
        startListening(code, false)
        observeParticipants(code)
    }

    fun leaveSession() {
        listenJob?.cancel()
        participantsJob?.cancel()
        _participants.value = emptyList()
        _state.value = ListenTogetherState.Idle
    }
}
