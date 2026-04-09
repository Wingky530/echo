package dev.brahmkshatriya.echo.ui.listentogether

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
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
    val isPlaying: Boolean,
    val trackTitle: String? = null,
    val trackArtist: String? = null,
    val timestamp: Long 
)

class ListenTogetherViewModel : ViewModel() {

    private val _state = MutableStateFlow<ListenTogetherState>(ListenTogetherState.Idle)
    val state: StateFlow<ListenTogetherState> = _state

    // ✅ FIX: Kasih memori (replay=1) biar event gak ilang pas transisi UI
    private val _syncEvent = MutableSharedFlow<SyncEvent>(
        replay = 1,
        extraBufferCapacity = 5,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val syncEvent: SharedFlow<SyncEvent> = _syncEvent

    private val firebase = ListenTogetherFirebaseClient()
    private var listenJob: Job? = null
    private var participantsJob: Job? = null

    fun createSession(trackId: String?, extensionId: String?) {
        val code = generateCode()
        val joinedAt = System.currentTimeMillis()
        _state.value = ListenTogetherState.Active(
            sessionCode = code,
            isHost = true,
            participants = listOf(Participant(firebase.clientId, "Host", isHost = true))
        )
        firebase.send(code, WsMessage(
            type = "JOIN",
            senderId = firebase.clientId,
            senderName = "Host",
            timestamp = joinedAt
        ))
        startListening(code, isHost = true)
        observeParticipants(code)
    }

    fun joinSession(code: String) {
        _state.value = ListenTogetherState.Connecting
        val cleanCode = code.uppercase().trim()
        val joinedAt = System.currentTimeMillis()

        viewModelScope.launch {
            try {
                firebase.send(cleanCode, WsMessage(
                    type = "JOIN",
                    senderId = firebase.clientId,
                    senderName = "User",
                    timestamp = joinedAt
                ))
                _state.value = ListenTogetherState.Active(
                    sessionCode = cleanCode,
                    isHost = false
                )
                fetchAndApplyCurrentState(cleanCode)
                startListening(cleanCode, isHost = false)
                observeParticipants(cleanCode)
            } catch (e: Exception) {
                _state.value = ListenTogetherState.Error(e.message ?: "Gagal bergabung")
            }
        }
    }

    fun broadcastSync(trackId: String, extensionId: String?, positionMs: Long, isPlaying: Boolean, trackTitle: String? = null, trackArtist: String? = null) {
        val s = _state.value as? ListenTogetherState.Active ?: return
        if (!s.isHost) return
        firebase.send(s.sessionCode, WsMessage(
            type = "SYNC",
            trackId = trackId,
            extensionId = extensionId,
            positionMs = positionMs,
            isPlaying = isPlaying,
            trackTitle = trackTitle,
            trackArtist = trackArtist,
            senderId = firebase.clientId,
            timestamp = System.currentTimeMillis()
        ))
    }

    fun leaveSession() {
        val s = _state.value as? ListenTogetherState.Active ?: return
        firebase.send(s.sessionCode, WsMessage(type = "LEAVE", senderId = firebase.clientId))
        listenJob?.cancel()
        participantsJob?.cancel()
        _state.value = ListenTogetherState.Idle
    }

    private fun startListening(code: String, isHost: Boolean) {
        listenJob?.cancel()
        listenJob = viewModelScope.launch {
            firebase.connect(code).collect { msg ->
                if (msg.type == "SYNC" && !isHost) {
                    _syncEvent.emit(SyncEvent(
                        trackId = msg.trackId ?: return@collect,
                        extensionId = msg.extensionId,
                        positionMs = msg.positionMs,
                        isPlaying = msg.isPlaying,
                        trackTitle = msg.trackTitle,
                        trackArtist = msg.trackArtist,
                        timestamp = msg.timestamp 
                    ))
                }
            }
        }
    }

    private fun observeParticipants(code: String) {
        participantsJob?.cancel()
        participantsJob = viewModelScope.launch {
            firebase.observeParticipants(code).collect { participants ->
                val s = _state.value as? ListenTogetherState.Active ?: return@collect
                _state.value = s.copy(participants = participants)
            }
        }
    }

    private fun fetchAndApplyCurrentState(code: String) {
        viewModelScope.launch {
            firebase.getCurrentState(code) { msg ->
                if (msg != null) {
                    viewModelScope.launch {
                        _syncEvent.emit(SyncEvent(
                            trackId = msg.trackId ?: return@launch,
                            extensionId = msg.extensionId,
                            positionMs = msg.positionMs,
                            isPlaying = msg.isPlaying,
                            timestamp = msg.timestamp
                        ))
                    }
                }
            }
        }
    }

    private fun generateCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    override fun onCleared() {
        super.onCleared()
        if (_state.value is ListenTogetherState.Active) leaveSession()
    }
}
