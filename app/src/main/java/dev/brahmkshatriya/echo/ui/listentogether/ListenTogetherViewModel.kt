package dev.brahmkshatriya.echo.ui.listentogether

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    private val wsClient = ListenTogetherWsClient()

    init {
        viewModelScope.launch {
            wsClient.incoming.collect { msg -> handleMessage(msg) }
        }
    }

    fun createSession(trackId: String?, extensionId: String?) {
        val code = generateCode()
        _state.value = ListenTogetherState.Connecting
        viewModelScope.launch {
            wsClient.connect(
                sessionCode = code,
                onOpen = {
                    _state.value = ListenTogetherState.Active(
                        sessionCode = code,
                        isHost = true,
                        participants = listOf(
                            Participant(wsClient.clientId, "You", isHost = true)
                        )
                    )
                },
                onError = { _state.value = ListenTogetherState.Error(it) }
            )
        }
    }

    fun joinSession(code: String) {
        _state.value = ListenTogetherState.Connecting
        viewModelScope.launch {
            wsClient.connect(
                sessionCode = code.uppercase().trim(),
                onOpen = {
                    _state.value = ListenTogetherState.Active(
                        sessionCode = code,
                        isHost = false
                    )
                    wsClient.send(WsMessage(type = "JOIN", senderId = wsClient.clientId))
                },
                onError = { _state.value = ListenTogetherState.Error(it) }
            )
        }
    }

    fun broadcastSync(
        trackId: String,
        extensionId: String?,
        positionMs: Long,
        isPlaying: Boolean
    ) {
        val s = _state.value as? ListenTogetherState.Active ?: return
        if (!s.isHost) return
        wsClient.send(
            WsMessage(
                type = "SYNC",
                trackId = trackId,
                extensionId = extensionId,
                positionMs = positionMs,
                isPlaying = isPlaying,
                senderId = wsClient.clientId
            )
        )
    }

    fun leaveSession() {
        wsClient.send(WsMessage(type = "LEAVE", senderId = wsClient.clientId))
        wsClient.disconnect()
        _state.value = ListenTogetherState.Idle
    }

    private fun handleMessage(msg: WsMessage) {
        when (msg.type) {
            "SYNC" -> {
                val s = _state.value as? ListenTogetherState.Active ?: return
                if (s.isHost) return
                viewModelScope.launch {
                    _syncEvent.emit(
                        SyncEvent(
                            trackId = msg.trackId ?: return@launch,
                            extensionId = msg.extensionId,
                            positionMs = msg.positionMs,
                            isPlaying = msg.isPlaying
                        )
                    )
                }
            }
            "JOIN" -> {
                val s = _state.value as? ListenTogetherState.Active ?: return
                val updated = s.participants.toMutableList()
                if (updated.none { it.id == msg.senderId }) {
                    updated.add(Participant(msg.senderId, msg.senderName ?: "User"))
                }
                _state.value = s.copy(participants = updated)
            }
            "LEAVE" -> {
                val s = _state.value as? ListenTogetherState.Active ?: return
                _state.value = s.copy(
                    participants = s.participants.filter { it.id != msg.senderId }
                )
            }
        }
    }

    private fun generateCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    override fun onCleared() {
        super.onCleared()
        wsClient.disconnect()
    }
}
