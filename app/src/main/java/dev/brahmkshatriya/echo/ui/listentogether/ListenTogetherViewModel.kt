package dev.brahmkshatriya.echo.ui.listentogether

import android.app.Application
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.math.abs

object ListenTogetherStatus {
    var isGuest: Boolean = false
}

sealed class ListenTogetherState {
    object Idle : ListenTogetherState()
    object Connecting : ListenTogetherState()
    data class Active(val sessionCode: String, val isHost: Boolean, val participants: List<Participant> = emptyList()) : ListenTogetherState()
    data class Error(val message: String) : ListenTogetherState()
}

class ListenTogetherViewModel(application: Application) : AndroidViewModel(application) {
    var playerState: dev.brahmkshatriya.echo.playback.PlayerState? = null
    var browserProvider: (() -> androidx.media3.session.MediaController?)? = null
    var isPlayingProvider: (() -> Boolean)? = null
    var playAction: ((String, Track, Boolean) -> Unit)? = null
    var seekAction: ((Long) -> Unit)? = null
    var setPlayingAction: ((Boolean) -> Unit)? = null

    private val _state = MutableStateFlow<ListenTogetherState>(ListenTogetherState.Idle)
    val state: StateFlow<ListenTogetherState> = _state
    private val _permission = MutableStateFlow(3)
    val permission: StateFlow<Int> = _permission
    private val _event = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val event = _event.asSharedFlow()

    private val firebase = ListenTogetherFirebaseClient()
    private var listenJob: Job? = null
    private var participantsJob: Job? = null
    private var permissionJob: Job? = null
    private var syncManagerJob: Job? = null
    private var lastHostMsg: WsMessage? = null

    private var expectedTrackId: String? = null
    private var expectedIsPlaying: Boolean? = null
    private var lastSeenTrackId: String? = null
    private var lastSeenIsPlaying: Boolean? = null
    private var lastSeenPosition: Long = 0L
    private var lastLocalActionTime = 0L
    private var isApplyingRemoteState = false

    private fun showToast(msg: String) {
        viewModelScope.launch(Dispatchers.Main) { Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show() }
    }

    fun createSession(trackId: String?, extensionId: String?, userName: String, avatarUrl: String? = null) {
        val code = (1..6).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[Random.nextInt(32)] }.joinToString("")
        ListenTogetherStatus.isGuest = false
        _state.value = ListenTogetherState.Active(code, true, listOf(Participant(firebase.clientId, userName, avatarUrl, true)))
        firebase.send(code, WsMessage("JOIN", senderId = firebase.clientId, senderName = userName, senderAvatar = avatarUrl), true)
        firebase.setPermission(code, 3) 
        startListening(code); startSyncManager(); observeParticipants(code); observePermission(code)
    }

    fun joinSession(code: String, userName: String, avatarUrl: String? = null) {
        _state.value = ListenTogetherState.Connecting
        val cleanCode = code.uppercase().trim()
        firebase.checkRoomExists(cleanCode) { exists ->
            if (!exists) { _state.value = ListenTogetherState.Error("Room not found"); return@checkRoomExists }
            ListenTogetherStatus.isGuest = true
            viewModelScope.launch {
                firebase.send(cleanCode, WsMessage("JOIN", senderId = firebase.clientId, senderName = userName, senderAvatar = avatarUrl), false)
                _state.value = ListenTogetherState.Active(cleanCode, false)
                startListening(cleanCode); startSyncManager(); observeParticipants(cleanCode); observePermission(cleanCode)
            }
        }
    }

    private suspend fun applyRemoteState(msg: WsMessage, senderName: String? = null) {
        val extId = msg.extensionId ?: return
        val localTrackId = playerState?.current?.value?.mediaItem?.track?.id
        expectedTrackId = msg.trackId; expectedIsPlaying = msg.isPlaying
        if (localTrackId != msg.trackId) {
            val trackExtras = mutableMapOf<String, String>()
            senderName?.let { trackExtras["addedByName"] = it }
            val track = Track(id = msg.trackId ?: "", title = msg.trackTitle ?: "Sync", extras = trackExtras)
            playAction?.invoke(extId, track, false); delay(1500)
        }
        if ((isPlayingProvider?.invoke() ?: false) != msg.isPlaying) setPlayingAction?.invoke(msg.isPlaying)
        val expected = msg.positionMs + if (msg.isPlaying) System.currentTimeMillis() - msg.timestamp else 0
        if (abs((browserProvider?.invoke()?.currentPosition ?: 0L) - expected) > 2500) seekAction?.invoke(expected)
    }

    private fun startSyncManager() {
        syncManagerJob?.cancel(); syncManagerJob = viewModelScope.launch {
            while (true) {
                delay(500); if (isApplyingRemoteState) continue
                val s = _state.value as? ListenTogetherState.Active ?: break
                val browser = browserProvider?.invoke() ?: continue
                val item = playerState?.current?.value?.mediaItem ?: continue
                if (item.track.id != lastSeenTrackId || abs(browser.currentPosition - lastSeenPosition) > 2500) {
                    firebase.send(s.sessionCode, WsMessage("SYNC", item.track.id, item.extensionId, browser.currentPosition, browser.isPlaying, firebase.clientId, timestamp = System.currentTimeMillis(), trackTitle = item.track.title), s.isHost)
                    lastSeenTrackId = item.track.id; lastSeenPosition = browser.currentPosition
                }
            }
        }
    }

    private fun startListening(code: String) {
        listenJob?.cancel(); listenJob = viewModelScope.launch {
            firebase.connect(code).collect { msg ->
                if (System.currentTimeMillis() - lastLocalActionTime < 3000) return@collect
                val sender = (_state.value as? ListenTogetherState.Active)?.participants?.find { it.id == msg.senderId }
                isApplyingRemoteState = true; applyRemoteState(msg, sender?.name); isApplyingRemoteState = false
            }
        }
    }

    fun updatePermission(level: Int) { 
        val s = _state.value as? ListenTogetherState.Active ?: return
        if (s.isHost) firebase.setPermission(s.sessionCode, level)
    }

    private fun observeParticipants(code: String) {
        participantsJob?.cancel(); participantsJob = viewModelScope.launch {
            firebase.observeParticipants(code).collect { p ->
                val s = _state.value as? ListenTogetherState.Active ?: return@collect
                if (!s.isHost && p.isEmpty()) { leaveSession(); return@collect }
                _state.value = s.copy(participants = p)
            }
        }
    }

    private fun observePermission(code: String) {
        permissionJob?.cancel(); permissionJob = viewModelScope.launch {
            firebase.observePermission(code).collect { _permission.value = it }
        }
    }

    fun leaveSession() {
        val s = _state.value as? ListenTogetherState.Active ?: return
        firebase.send(s.sessionCode, WsMessage("LEAVE", senderId = firebase.clientId))
        ListenTogetherStatus.isGuest = false; _state.value = ListenTogetherState.Idle
    }
}
