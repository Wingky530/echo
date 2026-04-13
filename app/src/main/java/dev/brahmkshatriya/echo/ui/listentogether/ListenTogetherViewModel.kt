package dev.brahmkshatriya.echo.ui.listentogether

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.math.abs

sealed class ListenTogetherState {
    object Idle : ListenTogetherState()
    object Connecting : ListenTogetherState()
    data class Active(val sessionCode: String, val isHost: Boolean, val participants: List<Participant> = emptyList()) : ListenTogetherState()
    data class Error(val message: String) : ListenTogetherState()
}

class ListenTogetherViewModel : ViewModel() {
    var playerState: dev.brahmkshatriya.echo.playback.PlayerState? = null
    var browserProvider: (() -> androidx.media3.session.MediaController?)? = null
    var isPlayingProvider: (() -> Boolean)? = null
    var playAction: ((String, dev.brahmkshatriya.echo.common.models.Track, Boolean) -> Unit)? = null
    var seekAction: ((Long) -> Unit)? = null
    var setPlayingAction: ((Boolean) -> Unit)? = null

    private val _state = MutableStateFlow<ListenTogetherState>(ListenTogetherState.Idle)
    val state: StateFlow<ListenTogetherState> = _state
    private val _permission = MutableStateFlow(3) // Default to Full Control (Bitmask 3)
    val permission: StateFlow<Int> = _permission
    private val _event = MutableSharedFlow<String>()
    val event = _event.asSharedFlow()

    private val firebase = ListenTogetherFirebaseClient()
    private var listenJob: Job? = null
    private var participantsJob: Job? = null
    private var permissionJob: Job? = null
    private var hostBroadcastJob: Job? = null
    private var lastListenerTrackId: String? = null
    private var lastHostMsg: WsMessage? = null

    fun createSession(trackId: String?, extensionId: String?, userName: String, avatarUrl: String? = null) {
        val code = (1..6).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[Random.nextInt(32)] }.joinToString("")
        _state.value = ListenTogetherState.Active(code, true, listOf(Participant(firebase.clientId, userName, avatarUrl, true)))
        firebase.send(code, WsMessage("JOIN", senderId = firebase.clientId, senderName = userName, senderAvatar = avatarUrl), true)
        firebase.setPermission(code, 3) // Push default Full Control to Firebase
        startListening(code, true); startHostBroadcast(code); observeParticipants(code); observePermission(code)
    }

    fun joinSession(code: String, userName: String, avatarUrl: String? = null) {
        _state.value = ListenTogetherState.Connecting
        val cleanCode = code.uppercase().trim()
        
        firebase.checkRoomExists(cleanCode) { exists ->
            if (!exists) {
                // If room doesn't exist, set error state to trigger Toast
                _state.value = ListenTogetherState.Error("Room does not exist or has ended")
                return@checkRoomExists
            }
            viewModelScope.launch {
                try {
                    firebase.send(cleanCode, WsMessage("JOIN", senderId = firebase.clientId, senderName = userName, senderAvatar = avatarUrl), false)
                    _state.value = ListenTogetherState.Active(cleanCode, false)
                    
                    firebase.getCurrentState(cleanCode) { msg ->
                        if (msg?.trackId != null && msg.extensionId != null) {
                            viewModelScope.launch { applyRemoteState(msg) }
                        }
                    }
                    startListening(cleanCode, false); observeParticipants(cleanCode); observePermission(cleanCode)
                } catch (e: Exception) { _state.value = ListenTogetherState.Error(e.message ?: "Failed to join") }
            }
        }
    }

    private suspend fun applyRemoteState(msg: WsMessage) {
        val extId = msg.extensionId ?: return
        if (lastListenerTrackId != msg.trackId) {
            lastListenerTrackId = msg.trackId
            val track = dev.brahmkshatriya.echo.common.models.Track(id = msg.trackId ?: "", title = msg.trackTitle ?: "Listen Together", extras = mapOf(dev.brahmkshatriya.echo.extensions.builtin.unified.UnifiedExtension.EXTENSION_ID to extId))
            playAction?.invoke(extId, track, false)
            delay(1200)
        }
        if ((isPlayingProvider?.invoke() ?: false) != msg.isPlaying) setPlayingAction?.invoke(msg.isPlaying)
        val expected = msg.positionMs + if (msg.isPlaying) System.currentTimeMillis() - msg.timestamp else 0
        val localPos = browserProvider?.invoke()?.currentPosition ?: 0L
        if (abs(localPos - expected) > 2500) seekAction?.invoke(expected)
    }

    fun updatePermission(level: Int) { 
        val s = _state.value as? ListenTogetherState.Active ?: return
        if (s.isHost) firebase.setPermission(s.sessionCode, level)
    }

    fun leaveSession() {
        val s = _state.value as? ListenTogetherState.Active ?: return
        if (s.isHost) {
            com.google.firebase.database.FirebaseDatabase.getInstance("https://echo-listen-together-default-rtdb.asia-southeast1.firebasedatabase.app").getReference("sessions/${s.sessionCode}").removeValue()
        } else {
            firebase.send(s.sessionCode, WsMessage("LEAVE", senderId = firebase.clientId))
        }
        listenJob?.cancel(); participantsJob?.cancel(); permissionJob?.cancel(); hostBroadcastJob?.cancel(); _state.value = ListenTogetherState.Idle
    }

    private fun startHostBroadcast(code: String) {
        hostBroadcastJob?.cancel(); hostBroadcastJob = viewModelScope.launch {
            while (true) {
                delay(2000)
                val s = _state.value as? ListenTogetherState.Active ?: break
                if (!s.isHost) break
                val current = playerState?.current?.value ?: continue
                val track = current.mediaItem.track
                firebase.send(code, WsMessage("SYNC", track.id, current.mediaItem.extensionId ?: "", browserProvider?.invoke()?.currentPosition ?: 0L, isPlayingProvider?.invoke() ?: false, firebase.clientId, timestamp = System.currentTimeMillis(), trackTitle = track.title), true)
            }
        }
    }

    private fun startListening(code: String, isHost: Boolean) {
        listenJob?.cancel(); listenJob = viewModelScope.launch {
            firebase.connect(code).collect { msg ->
                if (msg.type == "SYNC" && !isHost) {
                    lastHostMsg = msg
                    applyRemoteState(msg)
                }
            }
        }
    }

    private fun observeParticipants(code: String) {
        participantsJob?.cancel(); participantsJob = viewModelScope.launch {
            firebase.observeParticipants(code).collect { p ->
                val s = _state.value as? ListenTogetherState.Active ?: return@collect
                if (!s.isHost && p.isEmpty()) { 
                    _event.emit("Host has ended the session"); leaveSession(); return@collect 
                }
                _state.value = s.copy(participants = p)
            }
        }
    }

    private fun observePermission(code: String) {
        permissionJob?.cancel(); permissionJob = viewModelScope.launch {
            firebase.observePermission(code).collect { _permission.value = it }
        }
    }
}
