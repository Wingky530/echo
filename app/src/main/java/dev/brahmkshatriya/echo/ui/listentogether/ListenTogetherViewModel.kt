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
    
    // UPDATE: playAction now supports a List of Tracks for Queue Context injection
    var playAction: ((String, List<Track>, Boolean) -> Unit)? = null
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
    private var lastSeenQueueContext: String? = null
    private var lastSeenIsPlaying: Boolean? = null
    private var lastSeenPosition: Long = 0L
    private var lastLocalActionTime = 0L
    
    private var isApplyingRemoteState = false

    private fun showToast(msg: String) {
        viewModelScope.launch(Dispatchers.Main) { 
            Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show() 
        }
    }

    private fun saveLocalUserToPrefs(userName: String, avatarUrl: String?) {
        val prefs = getApplication<Application>().getSharedPreferences("listen_together_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("localUserName", userName).putString("localAvatarUrl", avatarUrl).apply()
    }

    private fun clearLocalUserFromPrefs() {
        getApplication<Application>().getSharedPreferences("listen_together_prefs", Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun createSession(trackId: String?, extensionId: String?, userName: String, avatarUrl: String? = null) {
        saveLocalUserToPrefs(userName, avatarUrl)
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
            saveLocalUserToPrefs(userName, avatarUrl)
            viewModelScope.launch {
                try {
                    firebase.send(cleanCode, WsMessage("JOIN", senderId = firebase.clientId, senderName = userName, senderAvatar = avatarUrl), false)
                    _state.value = ListenTogetherState.Active(cleanCode, false)
                    
                    firebase.getCurrentState(cleanCode) { msg ->
                        if (msg?.trackId != null && msg.extensionId != null) {
                            lastHostMsg = msg
                            viewModelScope.launch { 
                                val sender = (_state.value as? ListenTogetherState.Active)?.participants?.find { it.id == msg.senderId }
                                isApplyingRemoteState = true
                                applyRemoteState(msg, sender?.name, sender?.avatarUrl) 
                                isApplyingRemoteState = false
                            }
                        }
                    }
                    startListening(cleanCode); startSyncManager(); observeParticipants(cleanCode); observePermission(cleanCode)
                } catch (e: Exception) { _state.value = ListenTogetherState.Error(e.message ?: "Failed to join") }
            }
        }
    }

    // UPDATE: Inject multiple tracks instead of one
    private suspend fun applyRemoteState(msg: WsMessage, senderName: String? = null, senderAvatar: String? = null) {
        val extId = msg.extensionId ?: return
        val localTrackId = playerState?.current?.value?.mediaItem?.track?.id
        
        expectedTrackId = msg.trackId
        expectedIsPlaying = msg.isPlaying
        
        // Track ID or Queue Context changed -> Force full queue reload
        if (localTrackId != msg.trackId || lastSeenQueueContext != msg.queueContext) {
            val trackExtras = mutableMapOf<String, String>()
            trackExtras[dev.brahmkshatriya.echo.extensions.builtin.unified.UnifiedExtension.EXTENSION_ID] = extId
            senderName?.let { trackExtras["addedByName"] = it }
            senderAvatar?.let { trackExtras["addedByAvatar"] = it }

            val trackListToLoad = mutableListOf<Track>()
            
            // First add the currently playing track
            trackListToLoad.add(
                Track(id = msg.trackId ?: "", title = msg.trackTitle ?: "Listen Together", extras = trackExtras)
            )

            // Parse Queue Context and append upcoming tracks to eliminate buffer delays
            if (!msg.queueContext.isNullOrEmpty()) {
                val upNextIds = msg.queueContext.split(",")
                for (id in upNextIds) {
                    if (id.isNotBlank() && id != msg.trackId) {
                        trackListToLoad.add(Track(id = id, title = "Upcoming Track", extras = trackExtras))
                    }
                }
            }

            // Fire the updated playAction which now accepts List<Track>
            playAction?.invoke(extId, trackListToLoad, false)
            lastSeenQueueContext = msg.queueContext
            delay(1500) // Buffer wait
        }

        if ((isPlayingProvider?.invoke() ?: false) != msg.isPlaying) {
            setPlayingAction?.invoke(msg.isPlaying)
        }
        
        val expected = msg.positionMs + if (msg.isPlaying) System.currentTimeMillis() - msg.timestamp else 0
        val localPos = browserProvider?.invoke()?.currentPosition ?: 0L
        if (abs(localPos - expected) > 2500) {
            seekAction?.invoke(expected)
            lastSeenPosition = expected 
        }
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
        clearLocalUserFromPrefs()
        listenJob?.cancel(); participantsJob?.cancel(); permissionJob?.cancel(); syncManagerJob?.cancel()
        _state.value = ListenTogetherState.Idle
    }

    private fun startSyncManager() {
        syncManagerJob?.cancel(); syncManagerJob = viewModelScope.launch {
            while (true) {
                delay(500) 
                if (isApplyingRemoteState) continue 

                val s = _state.value as? ListenTogetherState.Active ?: break
                val localBrowser = browserProvider?.invoke() ?: continue
                val localIsPlaying = isPlayingProvider?.invoke() ?: false
                
                // Track Extraction
                val currentMediaItem = playerState?.current?.value?.mediaItem ?: continue
                val localTrackId = currentMediaItem.track.id
                val localTitle = currentMediaItem.track.title
                val extId = currentMediaItem.extensionId
                val localPos = localBrowser.currentPosition

                // Extract Upcoming Queue (Next 10 tracks to prevent payload bloat)
                var currentQueueContext = ""
                try {
                    val queueSize = localBrowser.mediaItemCount
                    val currentIndex = localBrowser.currentMediaItemIndex
                    if (queueSize > 0 && currentIndex != -1) {
                        val upcomingIds = mutableListOf<String>()
                        val maxNextTracks = minOf(currentIndex + 10, queueSize)
                        for (i in currentIndex + 1 until maxNextTracks) {
                            val nextItem = localBrowser.getMediaItemAt(i)
                            // Using extension ID fallback check just in case
                            val nextId = nextItem.mediaId
                            if(nextId.isNotEmpty()) upcomingIds.add(nextId)
                        }
                        if (upcomingIds.isNotEmpty()) {
                            currentQueueContext = upcomingIds.joinToString(",")
                        }
                    }
                } catch (e: Exception) {
                    // Fail silently if ExoPlayer isn't ready
                }

                var genuineTrackChange = false
                var genuineQueueChange = false
                
                if (localTrackId != lastSeenTrackId) {
                    if (lastSeenTrackId != null && localTrackId != expectedTrackId) genuineTrackChange = true
                    lastSeenTrackId = localTrackId
                }

                if (currentQueueContext != lastSeenQueueContext) {
                    genuineQueueChange = true
                    lastSeenQueueContext = currentQueueContext
                }

                var genuinePlayChange = false
                if (localIsPlaying != lastSeenIsPlaying) {
                    if (lastSeenIsPlaying != null && localIsPlaying != expectedIsPlaying) genuinePlayChange = true
                    lastSeenIsPlaying = localIsPlaying
                }

                var genuineSeek = false
                if (abs(localPos - lastSeenPosition) > 2500) genuineSeek = true
                lastSeenPosition = localPos

                val localMadeChange = genuineTrackChange || genuinePlayChange || genuineSeek || genuineQueueChange

                if (s.isHost) {
                    if (localMadeChange || System.currentTimeMillis() - lastLocalActionTime > 3000) {
                        val newMsg = WsMessage(
                            "SYNC", localTrackId, extId, localPos, localIsPlaying, 
                            firebase.clientId, timestamp = System.currentTimeMillis(), 
                            trackTitle = localTitle, queueContext = currentQueueContext
                        )
                        firebase.send(s.sessionCode, newMsg, true)
                        lastLocalActionTime = System.currentTimeMillis()
                        expectedTrackId = localTrackId
                        expectedIsPlaying = localIsPlaying
                    }
                } else {
                    val hostMsg = lastHostMsg ?: continue
                    val perm = _permission.value
                    val hasAddRemove = (perm and 1) != 0
                    val hasPlayback = (perm and 2) != 0

                    if (localMadeChange) {
                        var allowed = true
                        if ((genuineTrackChange || genuineQueueChange) && !hasAddRemove) { allowed = false; showToast("No permission to change queue") }
                        if ((genuinePlayChange || genuineSeek) && !hasPlayback) { allowed = false; showToast("No permission to control playback") }

                        if (allowed) {
                            val newMsg = WsMessage(
                                "SYNC", localTrackId, extId, localPos, localIsPlaying, 
                                firebase.clientId, timestamp = System.currentTimeMillis(), 
                                trackTitle = localTitle, queueContext = currentQueueContext
                            )
                            firebase.send(s.sessionCode, newMsg, false)
                            lastLocalActionTime = System.currentTimeMillis()
                            expectedTrackId = localTrackId
                            expectedIsPlaying = localIsPlaying
                            lastHostMsg = newMsg 
                        } else {
                            val sender = s.participants.find { it.id == hostMsg.senderId }
                            isApplyingRemoteState = true
                            applyRemoteState(hostMsg, sender?.name, sender?.avatarUrl) 
                            isApplyingRemoteState = false
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
                    if (System.currentTimeMillis() - lastLocalActionTime < 3000) return@collect
                    lastLocalActionTime = System.currentTimeMillis() 
                    lastHostMsg = msg
                    val sender = (_state.value as? ListenTogetherState.Active)?.participants?.find { it.id == msg.senderId }
                    isApplyingRemoteState = true
                    applyRemoteState(msg, sender?.name, sender?.avatarUrl)
                    isApplyingRemoteState = false
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
