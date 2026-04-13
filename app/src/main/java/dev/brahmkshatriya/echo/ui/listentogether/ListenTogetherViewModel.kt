package dev.brahmkshatriya.echo.ui.listentogether

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
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
    private val _permission = MutableStateFlow(3) // Default to Full Control
    val permission: StateFlow<Int> = _permission
    
    // Extra buffer to ensure toasts survive rapid state changes
    private val _event = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val event = _event.asSharedFlow()

    private val firebase = ListenTogetherFirebaseClient()
    private var listenJob: Job? = null
    private var participantsJob: Job? = null
    private var permissionJob: Job? = null
    private var syncManagerJob: Job? = null
    private var lastListenerTrackId: String? = null
    private var lastHostMsg: WsMessage? = null

    fun createSession(trackId: String?, extensionId: String?, userName: String, avatarUrl: String? = null) {
        val code = (1..6).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[Random.nextInt(32)] }.joinToString("")
        _state.value = ListenTogetherState.Active(code, true, listOf(Participant(firebase.clientId, userName, avatarUrl, true)))
        firebase.send(code, WsMessage("JOIN", senderId = firebase.clientId, senderName = userName, senderAvatar = avatarUrl), true)
        firebase.setPermission(code, 3) 
        startListening(code); startSyncManager(); observeParticipants(code); observePermission(code)
    }

    fun joinSession(code: String, userName: String, avatarUrl: String? = null) {
        _state.value = ListenTogetherState.Connecting
        val cleanCode = code.uppercase().trim()
        
        firebase.checkRoomExists(cleanCode) { exists ->
            if (!exists) {
                _state.value = ListenTogetherState.Error("Room does not exist or has ended")
                return@checkRoomExists
            }
            viewModelScope.launch {
                try {
                    firebase.send(cleanCode, WsMessage("JOIN", senderId = firebase.clientId, senderName = userName, senderAvatar = avatarUrl), false)
                    _state.value = ListenTogetherState.Active(cleanCode, false)
                    
                    // Fetch initial state for instant sync upon re-joining
                    firebase.getCurrentState(cleanCode) { msg ->
                        if (msg?.trackId != null && msg.extensionId != null) {
                            lastHostMsg = msg
                            viewModelScope.launch { applyRemoteState(msg) }
                        }
                    }
                    startListening(cleanCode); startSyncManager(); observeParticipants(cleanCode); observePermission(cleanCode)
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
            delay(1200) // Allow player time to buffer
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
        listenJob?.cancel(); participantsJob?.cancel(); permissionJob?.cancel(); syncManagerJob?.cancel(); lastListenerTrackId = null
        _state.value = ListenTogetherState.Idle
    }

    private fun startSyncManager() {
        syncManagerJob?.cancel(); syncManagerJob = viewModelScope.launch {
            while (true) {
                delay(1500)
                val s = _state.value as? ListenTogetherState.Active ?: break
                val localBrowser = browserProvider?.invoke() ?: continue
                val localIsPlaying = isPlayingProvider?.invoke() ?: false
                val localTrackId = playerState?.current?.value?.mediaItem?.track?.id ?: continue
                val localTitle = playerState?.current?.value?.mediaItem?.track?.title
                val extId = playerState?.current?.value?.mediaItem?.extensionId ?: continue

                if (s.isHost) {
                    // Host is the absolute truth, broadcasts unconditionally
                    firebase.send(s.sessionCode, WsMessage("SYNC", localTrackId, extId, localBrowser.currentPosition, localIsPlaying, firebase.clientId, timestamp = System.currentTimeMillis(), trackTitle = localTitle), true)
                } else {
                    // Guest logic: Compare local state vs host state
                    val hostMsg = lastHostMsg ?: continue
                    val perm = _permission.value
                    val hasAddRemove = (perm and 1) != 0
                    val hasPlayback = (perm and 2) != 0

                    val expectedPos = hostMsg.positionMs + if (hostMsg.isPlaying) System.currentTimeMillis() - hostMsg.timestamp else 0
                    val trackChanged = hostMsg.trackId != null && localTrackId != hostMsg.trackId
                    val playbackChanged = localIsPlaying != hostMsg.isPlaying || abs(localBrowser.currentPosition - expectedPos) > 3500

                    if (trackChanged) {
                        if (hasAddRemove) {
                            // Guest has permission -> Command the host!
                            val newMsg = WsMessage("SYNC", localTrackId, extId, localBrowser.currentPosition, localIsPlaying, firebase.clientId, timestamp = System.currentTimeMillis(), trackTitle = localTitle)
                            firebase.send(s.sessionCode, newMsg, false)
                            lastHostMsg = newMsg 
                        } else {
                            // No permission -> Rubber-band back to Host
                            _event.tryEmit("No permission to change track")
                            applyRemoteState(hostMsg)
                        }
                    } else if (playbackChanged) {
                        if (hasPlayback) {
                            // Guest has permission -> Command the host!
                            val newMsg = WsMessage("SYNC", localTrackId, extId, localBrowser.currentPosition, localIsPlaying, firebase.clientId, timestamp = System.currentTimeMillis(), trackTitle = localTitle)
                            firebase.send(s.sessionCode, newMsg, false)
                            lastHostMsg = newMsg
                        } else {
                            // No permission -> Rubber-band back to Host
                            _event.tryEmit("No permission to control playback")
                            applyRemoteState(hostMsg)
                        }
                    }
                }
            }
        }
    }

    private fun startListening(code: String) {
        listenJob?.cancel(); listenJob = viewModelScope.launch {
            firebase.connect(code).collect { msg ->
                if (msg.type == "SYNC") {
                    // Both Host and Guest accept SYNC messages from Firebase
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
                    _event.tryEmit("Host has ended the session") 
                    leaveSession(); return@collect 
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
