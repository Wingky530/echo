package dev.brahmkshatriya.echo.ui.listentogether

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

class ListenTogetherViewModel : ViewModel() {

    private val _state = MutableStateFlow<ListenTogetherState>(ListenTogetherState.Idle)
    val state: StateFlow<ListenTogetherState> = _state

    private val _syncEvent = MutableSharedFlow<SyncEvent>()
    val syncEvent: SharedFlow<SyncEvent> = _syncEvent

    private val firebase = ListenTogetherFirebaseClient()
    private var listenJob: Job? = null

    fun generateCode(): String = Random.nextInt(100000, 999999).toString()

    fun startListening(code: String, isHost: Boolean) {
        listenJob?.cancel()
        listenJob = viewModelScope.launch {
            firebase.connect(code).collect { msg ->
                if (msg.type == "SYNC" && msg.trackId != null) {
                    // Masukkan pesan dari Firebase ke dalam SharedFlow
                    _syncEvent.emit(
                        SyncEvent(
                            trackId = msg.trackId,
                            extensionId = msg.extensionId,
                            positionMs = msg.positionMs,
                            isPlaying = msg.isPlaying
                        )
                    )
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
