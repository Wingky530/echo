package dev.brahmkshatriya.echo.ui.listentogether

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
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
    var playerState: dev.brahmkshatriya.echo.playback.PlayerState? = null
    var browserProvider: (() -> androidx.media3.session.MediaController?)? = null
    var isPlayingProvider: (() -> Boolean)? = null
    var playAction: ((String, Track, Boolean) -> Unit)? = null
    var addToQueueAction: ((String, Track) -> Unit)? = null
    var seekAction: ((Long) -> Unit)? = null
    var setPlayingAction: ((Boolean) -> Unit)? = null

    private val firebase = ListenTogetherFirebaseClient()
    private val _state = MutableStateFlow<ListenTogetherState>(ListenTogetherState.Idle)
    val state: StateFlow<ListenTogetherState> = _state
    
    private val _permission = MutableStateFlow(3)
    val permission: StateFlow<Int> = _permission

    private val _event = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val event = _event.asSharedFlow()

    // FIXED: Pake List buat ngelacak ID lagu satu-satu
    private var lastSeenQueueIds = listOf<String>()
    private var lastSeenTrackId: String? = null
    private var lastSeenPosition: Long = 0L
    private var isApplyingRemoteState = false

    fun createSession(trackId: String?, extId: String?, name: String, avatar: String?) {
        val code = (1..6).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[(0..31).random()] }.joinToString("")
        ListenTogetherStatus.isGuest = false
        playerState?.radio?.value = dev.brahmkshatriya.echo.playback.PlayerState.Radio.Empty
        _state.value = ListenTogetherState.Active(code, true)
        firebase.send(code, WsMessage("JOIN", senderId = firebase.clientId, senderName = name, senderAvatar = avatar), true)
        firebase.setPermission(code, 3)
        startObserving(code)
    }

    fun joinSession(code: String, name: String, avatar: String?) {
        val cleanCode = code.uppercase().trim()
        _state.value = ListenTogetherState.Connecting
        firebase.checkRoomExists(cleanCode) { exists ->
            if (exists) {
                ListenTogetherStatus.isGuest = true
                playerState?.radio?.value = dev.brahmkshatriya.echo.playback.PlayerState.Radio.Empty
                _state.value = ListenTogetherState.Active(cleanCode, false)
                firebase.send(cleanCode, WsMessage("JOIN", senderId = firebase.clientId, senderName = name, senderAvatar = avatar), false)
                startObserving(cleanCode)
            } else { _state.value = ListenTogetherState.Error("Room not found") }
        }
    }

    private fun startObserving(code: String) {
        viewModelScope.launch {
            firebase.connect(code).collect { msg ->
                val s = _state.value as? ListenTogetherState.Active ?: return@collect
                
                if (msg.type == "ADD_QUEUE" && s.isHost && msg.senderId != firebase.clientId) {
                    val extId = msg.extensionId ?: ""
                    val trackToAdd = Track(
                        id = msg.trackId ?: "",
                        title = msg.trackTitle ?: "Added Song",
                        extras = mapOf(
                            "addedByName" to (msg.senderName ?: "Guest"),
                            "addedByAvatar" to (msg.senderAvatar ?: "")
                        )
                    )
                    addToQueueAction?.invoke(extId, trackToAdd)
                } else if (msg.type == "ADD_QUEUE" && !s.isHost) {
                    val extId = msg.extensionId ?: ""
                    val trackToAdd = Track(
                        id = msg.trackId ?: "",
                        title = msg.trackTitle ?: "Added Song",
                        extras = mapOf("addedByName" to (msg.senderName ?: "Guest"), "addedByAvatar" to (msg.senderAvatar ?: ""))
                    )
                    addToQueueAction?.invoke(extId, trackToAdd)
                } else if (msg.type == "SYNC") {
                    isApplyingRemoteState = true
                    applyRemoteState(msg)
                    isApplyingRemoteState = false
                }
            }
        }
        
        viewModelScope.launch {
            while (true) {
                delay(1000)
                val s = _state.value as? ListenTogetherState.Active ?: continue
                val browser = browserProvider?.invoke() ?: continue
                val item = playerState?.current?.value?.mediaItem ?: continue
                
                // FIXED: Deteksi lagu masuk secara lebih detail (Play Next/Add to Queue)
                val currentQueueIds = (0 until browser.mediaItemCount).mapNotNull { browser.getMediaItemAt(it).track?.id }
                if (currentQueueIds.size > lastSeenQueueIds.size && lastSeenQueueIds.isNotEmpty()) {
                    val addedIds = currentQueueIds.filter { it !in lastSeenQueueIds }
                    addedIds.forEach { newId ->
                        val newTrack = (0 until browser.mediaItemCount).map { browser.getMediaItemAt(it) }.find { it.track?.id == newId }?.track
                        if (newTrack != null) {
                            firebase.send(s.sessionCode, WsMessage("ADD_QUEUE", newId, item.extensionId, senderId = firebase.clientId, senderName = ((_state.value as? ListenTogetherState.Active)?.participants?.find { it.id == firebase.clientId }?.name ?: "Host"), senderAvatar = ((_state.value as? ListenTogetherState.Active)?.participants?.find { it.id == firebase.clientId }?.avatarUrl), trackTitle = newTrack.title))
                        }
                    }
                }
                lastSeenQueueIds = currentQueueIds
                
                if (item.track.id != lastSeenTrackId || abs(browser.currentPosition - lastSeenPosition) > 3000) {
                    firebase.send(s.sessionCode, WsMessage("SYNC", item.track.id, item.extensionId, browser.currentPosition, browser.isPlaying, firebase.clientId, timestamp = System.currentTimeMillis(), trackTitle = item.track.title), s.isHost)
                    lastSeenTrackId = item.track.id
                    lastSeenPosition = browser.currentPosition
                }
            }
        }

        viewModelScope.launch {
            firebase.observeParticipants(code).collect { p ->
                val s = _state.value as? ListenTogetherState.Active ?: return@collect
                if (!s.isHost && p.isEmpty()) { leaveSession(); return@collect }
                _state.value = s.copy(participants = p)
            }
        }

        viewModelScope.launch {
            firebase.observePermission(code).collect { _permission.value = it }
        }
    }

    private suspend fun applyRemoteState(msg: WsMessage) {
        val extId = msg.extensionId ?: return
        if (playerState?.current?.value?.mediaItem?.track?.id != msg.trackId) {
            playAction?.invoke(extId, Track(id = msg.trackId ?: "", title = msg.trackTitle ?: "Sync", extras = emptyMap()), false)
            delay(1500)
        }
        if ((isPlayingProvider?.invoke() ?: false) != msg.isPlaying) setPlayingAction?.invoke(msg.isPlaying)
        val expected = msg.positionMs + if (msg.isPlaying) System.currentTimeMillis() - msg.timestamp else 0
        if (abs((browserProvider?.invoke()?.currentPosition ?: 0L) - expected) > 3000) seekAction?.invoke(expected)
    }

    fun updatePermission(level: Int) { 
        val s = _state.value as? ListenTogetherState.Active ?: return
        if (s.isHost) firebase.setPermission(s.sessionCode, level)
    }

    fun leaveSession() {
        val s = _state.value as? ListenTogetherState.Active ?: return
        firebase.send(s.sessionCode, WsMessage("LEAVE", senderId = firebase.clientId))
        ListenTogetherStatus.isGuest = false
        _state.value = ListenTogetherState.Idle
    }
}
