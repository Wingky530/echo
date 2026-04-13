package dev.brahmkshatriya.echo.ui.listentogether

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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
    private val firebase = ListenTogetherFirebaseClient()
    private val _state = MutableStateFlow<ListenTogetherState>(ListenTogetherState.Idle)
    val state: StateFlow<ListenTogetherState> = _state
    private val _permission = MutableStateFlow(3)
    val permission: StateFlow<Int> = _permission

    var playerState: dev.brahmkshatriya.echo.playback.PlayerState? = null
    var browserProvider: (() -> androidx.media3.session.MediaController?)? = null
    var isPlayingProvider: (() -> Boolean)? = null
    var playAction: ((String, Track, Boolean) -> Unit)? = null
    var addToQueueAction: ((String, Track) -> Unit)? = null // FIXED: Handler baru
    var seekAction: ((Long) -> Unit)? = null
    var setPlayingAction: ((Boolean) -> Unit)? = null

    private var lastSeenQueueSize = 0
    private var lastSeenTrackId: String? = null
    private var lastSeenPosition: Long = 0L
    private var isApplyingRemoteState = false

    fun createSession(trackId: String?, extId: String?, name: String, avatar: String?) {
        val code = (1..6).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[(0..31).random()] }.joinToString("")
        ListenTogetherStatus.isGuest = false
        _state.value = ListenTogetherState.Active(code, true)
        firebase.send(code, WsMessage("JOIN", senderId = firebase.clientId, senderName = name, senderAvatar = avatar), true)
        startObserving(code)
    }

    fun joinSession(code: String, name: String, avatar: String?) {
        val cleanCode = code.uppercase().trim()
        _state.value = ListenTogetherState.Connecting
        firebase.checkRoomExists(cleanCode) { exists ->
            if (exists) {
                ListenTogetherStatus.isGuest = true
                _state.value = ListenTogetherState.Active(cleanCode, false)
                firebase.send(cleanCode, WsMessage("JOIN", senderId = firebase.clientId, senderName = name, senderAvatar = avatar), false)
                startObserving(cleanCode)
            } else { _state.value = ListenTogetherState.Error("Room not found") }
        }
    }

    private fun startObserving(code: String) {
        // 1. Listen Commands & State
        viewModelScope.launch {
            firebase.connect(code).collect { msg ->
                val s = _state.value as? ListenTogetherState.Active ?: return@collect
                
                if (msg.type == "ADD_QUEUE" && s.isHost) {
                    // Host terima perintah dari Guest
                    val trackToAdd = Track(id = msg.trackId ?: "", title = msg.trackTitle ?: "Added Song", extras = emptyMap())
                    addToQueueAction?.invoke(msg.extensionId ?: "", trackToAdd)
                } else if (msg.type == "SYNC") {
                    // Sync posisi playback
                    isApplyingRemoteState = true
                    applyRemoteState(msg)
                    isApplyingRemoteState = false
                }
            }
        }
        
        // 2. Sync Manager (Pantau perubahan lokal)
        viewModelScope.launch {
            while (true) {
                delay(1000)
                val s = _state.value as? ListenTogetherState.Active ?: continue
                val browser = browserProvider?.invoke() ?: continue
                val item = playerState?.current?.value?.mediaItem ?: continue
                
                // DETEKSI ADD NEXT: Kalau antrean bertambah
                if (browser.mediaItemCount > lastSeenQueueSize && lastSeenQueueSize != 0) {
                    val newTrack = browser.getMediaItemAt(browser.mediaItemCount - 1).track
                    if (newTrack != null) {
                        // Kirim perintah ke Host
                        firebase.send(s.sessionCode, WsMessage("ADD_QUEUE", newTrack.id, item.extensionId, senderId = firebase.clientId, trackTitle = newTrack.title))
                    }
                }
                lastSeenQueueSize = browser.mediaItemCount
                
                // Sync State Reguler
                if (item.track.id != lastSeenTrackId || abs(browser.currentPosition - lastSeenPosition) > 3000) {
                    firebase.send(s.sessionCode, WsMessage("SYNC", item.track.id, item.extensionId, browser.currentPosition, browser.isPlaying, firebase.clientId, timestamp = System.currentTimeMillis(), trackTitle = item.track.title), s.isHost)
                    lastSeenTrackId = item.track.id
                    lastSeenPosition = browser.currentPosition
                }
            }
        }
    }

    private suspend fun applyRemoteState(msg: WsMessage) {
        val extId = msg.extensionId ?: return
        if (playerState?.current?.value?.mediaItem?.track?.id != msg.trackId) {
            playAction?.invoke(extId, Track(msg.trackId ?: "", msg.trackTitle ?: "Sync", emptyMap()), false)
            delay(1500)
        }
        if ((isPlayingProvider?.invoke() ?: false) != msg.isPlaying) setPlayingAction?.invoke(msg.isPlaying)
        val expected = msg.positionMs + if (msg.isPlaying) System.currentTimeMillis() - msg.timestamp else 0
        if (abs((browserProvider?.invoke()?.currentPosition ?: 0L) - expected) > 3000) seekAction?.invoke(expected)
    }

    fun leaveSession() {
        val s = _state.value as? ListenTogetherState.Active ?: return
        firebase.send(s.sessionCode, WsMessage("LEAVE", senderId = firebase.clientId))
        ListenTogetherStatus.isGuest = false
        _state.value = ListenTogetherState.Idle
    }
}
