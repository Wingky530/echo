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
    private val _permission = MutableStateFlow(0)
    val permission: StateFlow<Int> = _permission
    private val _event = MutableSharedFlow<String>()
    val event = _event.asSharedFlow()

    private val firebase = ListenTogetherFirebaseClient()
    private var listenJob: Job? = null
    private var participantsJob: Job? = null
    private var permissionJob: Job? = null
    private var hostBroadcastJob: Job? = null
    private var lastListenerTrackId: String? = null

    fun createSession(trackId: String?, extensionId: String?, userName: String, avatarUrl: String? = null) {
        val code = (1..6).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[Random.nextInt(32)] }.joinToString("")
        _state.value = ListenTogetherState.Active(code, true, listOf(Participant(firebase.clientId, userName, avatarUrl, true)))
        firebase.send(code, WsMessage("JOIN", senderId = firebase.clientId, senderName = userName, senderAvatar = avatarUrl), true)
        startListening(code, true); startHostBroadcast(code); observeParticipants(code); observePermission(code)
    }

    fun joinSession(code: String, userName: String, avatarUrl: String? = null) {
        _state.value = ListenTogetherState.Connecting
        val cleanCode = code.uppercase().trim()
        viewModelScope.launch {
            try {
                firebase.send(cleanCode, WsMessage("JOIN", senderId = firebase.clientId, senderName = userName, senderAvatar = avatarUrl), false)
                _state.value = ListenTogetherState.Active(cleanCode, false)
                firebase.getCurrentState(cleanCode) { msg -> 
                    if (msg?.trackId != null && msg.extensionId != null) {
                        lastListenerTrackId = msg.trackId
                        playAction?.invoke(msg.extensionId, dev.brahmkshatriya.echo.common.models.Track(id = msg.trackId, title = msg.trackTitle ?: "Listen Together", extras = mapOf(dev.brahmkshatriya.echo.extensions.builtin.unified.UnifiedExtension.EXTENSION_ID to msg.extensionId)), false)
                        if (msg.positionMs > 0) seekAction?.invoke(msg.positionMs)
                        setPlayingAction?.invoke(msg.isPlaying)
                    }
                }
                startListening(cleanCode, false); observeParticipants(cleanCode); observePermission(cleanCode)
            } catch (e: Exception) { _state.value = ListenTogetherState.Error(e.message ?: "Failed") }
        }
    }

    fun updatePermission(level: Int) { val s = _state.value as? ListenTogetherState.Active ?: return; if (s.isHost) firebase.setPermission(s.sessionCode, level) }
    
    fun checkPermission(requiredLevel: Int): Boolean {
        val s = state.value as? ListenTogetherState.Active ?: return false
        if (s.isHost || permission.value >= requiredLevel) return true
        viewModelScope.launch { _event.emit("You do not have permission to perform this action") }
        return false
    }

    private fun startHostBroadcast(code: String) {
        hostBroadcastJob?.cancel(); hostBroadcastJob = viewModelScope.launch {
            while (true) {
                delay(2000)
                val s = _state.value as? ListenTogetherState.Active ?: break
                if (!s.isHost) break
                val current = playerState?.current?.value ?: continue
                broadcastSync(current.mediaItem.track.id, current.mediaItem.extensionId ?: continue, browserProvider?.invoke()?.currentPosition ?: 0L, isPlayingProvider?.invoke() ?: false, current.mediaItem.track.title)
            }
        }
    }

    fun broadcastSync(tId: String, eId: String?, pMs: Long, isP: Boolean, tTitle: String?) {
        val s = _state.value as? ListenTogetherState.Active ?: return
        if (s.isHost) firebase.send(s.sessionCode, WsMessage("SYNC", tId, eId, pMs, isP, firebase.clientId, timestamp = System.currentTimeMillis(), trackTitle = tTitle), true)
    }

    fun leaveSession() {
        val s = _state.value as? ListenTogetherState.Active ?: return
        firebase.send(s.sessionCode, WsMessage("LEAVE", senderId = firebase.clientId))
        listenJob?.cancel(); participantsJob?.cancel(); permissionJob?.cancel(); hostBroadcastJob?.cancel(); lastListenerTrackId = null
        _state.value = ListenTogetherState.Idle
    }

    private fun startListening(code: String, isHost: Boolean) {
        listenJob?.cancel(); listenJob = viewModelScope.launch {
            firebase.connect(code).collect { msg ->
                if (msg.type == "SYNC" && !isHost) {
                    val extId = msg.extensionId ?: return@collect
                    if (lastListenerTrackId != msg.trackId) {
                        lastListenerTrackId = msg.trackId
                        playAction?.invoke(extId, dev.brahmkshatriya.echo.common.models.Track(id = msg.trackId ?: "", title = msg.trackTitle ?: "Listen Together", extras = mapOf(dev.brahmkshatriya.echo.extensions.builtin.unified.UnifiedExtension.EXTENSION_ID to extId)), false)
                        delay(1200)
                    }
                    if ((isPlayingProvider?.invoke() ?: false) != msg.isPlaying) setPlayingAction?.invoke(msg.isPlaying)
                    val expected = msg.positionMs + if (msg.isPlaying) System.currentTimeMillis() - msg.timestamp else 0
                    if (abs((browserProvider?.invoke()?.currentPosition ?: 0L) - expected) > 2500) seekAction?.invoke(expected)
                }
            }
        }
    }

    private fun observeParticipants(code: String) { participantsJob?.cancel(); participantsJob = viewModelScope.launch { firebase.observeParticipants(code).collect { p -> val s = _state.value as? ListenTogetherState.Active ?: return@collect; _state.value = s.copy(participants = p) } } }
    private fun observePermission(code: String) { permissionJob?.cancel(); permissionJob = viewModelScope.launch { firebase.observePermission(code).collect { _permission.value = it } } }
    override fun onCleared() { super.onCleared(); if (_state.value is ListenTogetherState.Active) leaveSession() }
}
