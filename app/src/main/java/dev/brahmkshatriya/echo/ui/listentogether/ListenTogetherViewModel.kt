/**
 * Package dev.brahmkshatriya.echo.ui.listentogether
 * 
 * Purpose: Manages the core business logic and state for a synchronized listening session
 * It connects the local media player state with the remote Firebase state
 *
 * Key Components:
 *  - ListenTogetherState: Represents the current session state (Idle, Connecting, Active, Error)
 *  - createSession(), joinSession(): Entry points for starting or joining a room
 *  - broadcastSync(): Pushes the host's playback state to the database on a timer
 *  - startListening(): Reacts to remote sync messages and adjusts local playback to match
 *
 * Dependencies:
 *  - ListenTogetherFirebaseClient: For networking and real-time data sync
 *  - kotlinx.coroutines.flow: State flow management for UI observation
 */
package dev.brahmkshatriya.echo.ui.listentogether

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private val firebase = ListenTogetherFirebaseClient()
    private var listenJob: Job? = null
    private var participantsJob: Job? = null
    private var hostBroadcastJob: Job? = null

    private var lastListenerTrackId: String? = null

    fun createSession(trackId: String?, extensionId: String?, userName: String, avatarUrl: String? = null) {
        val code = generateCode()
        val joinedAt = System.currentTimeMillis()
        _state.value = ListenTogetherState.Active(sessionCode = code, isHost = true, participants = listOf(Participant(firebase.clientId, userName, avatarUrl, isHost = true)))
        
        firebase.send(code, WsMessage(type = "JOIN", senderId = firebase.clientId, senderName = userName, senderAvatar = avatarUrl, timestamp = joinedAt), isHost = true)
        startListening(code, isHost = true)
        startHostBroadcast(code)
        observeParticipants(code)
    }

    fun joinSession(code: String, userName: String, avatarUrl: String? = null) {
        _state.value = ListenTogetherState.Connecting
        val cleanCode = code.uppercase().trim()
        val joinedAt = System.currentTimeMillis()

        viewModelScope.launch {
            try {
                firebase.send(cleanCode, WsMessage(type = "JOIN", senderId = firebase.clientId, senderName = userName, senderAvatar = avatarUrl, timestamp = joinedAt), isHost = false)
                _state.value = ListenTogetherState.Active(sessionCode = cleanCode, isHost = false)
                fetchAndApplyCurrentState(cleanCode)
                startListening(cleanCode, isHost = false)
                observeParticipants(cleanCode)
            } catch (e: Exception) {
                _state.value = ListenTogetherState.Error(e.message ?: "Gagal bergabung")
            }
        }
    }

    private fun startHostBroadcast(code: String) {
        hostBroadcastJob?.cancel()
        hostBroadcastJob = viewModelScope.launch {
            while (true) {
                delay(2000)
                val s = _state.value as? ListenTogetherState.Active ?: break
                if (!s.isHost) break
                val current = playerState?.current?.value ?: continue
                val track = current.mediaItem.track
                val extId = current.mediaItem.extensionId ?: continue
                val positionMs = browserProvider?.invoke()?.currentPosition ?: 0L
                val isPlaying = isPlayingProvider?.invoke() ?: false
                
                broadcastSync(track.id, extId, positionMs, isPlaying, track.title)
            }
        }
    }

    fun broadcastSync(trackId: String, extensionId: String?, positionMs: Long, isPlaying: Boolean, trackTitle: String? = null) {
        val s = _state.value as? ListenTogetherState.Active ?: return
        if (!s.isHost) return
        firebase.send(s.sessionCode, WsMessage(type = "SYNC", trackId = trackId, extensionId = extensionId, positionMs = positionMs, isPlaying = isPlaying, trackTitle = trackTitle, senderId = firebase.clientId, timestamp = System.currentTimeMillis()), isHost = true)
    }

    fun leaveSession() {
        val s = _state.value as? ListenTogetherState.Active ?: return
        firebase.send(s.sessionCode, WsMessage(type = "LEAVE", senderId = firebase.clientId))
        listenJob?.cancel()
        participantsJob?.cancel()
        hostBroadcastJob?.cancel()
        lastListenerTrackId = null
        _state.value = ListenTogetherState.Idle
    }

    private fun startListening(code: String, isHost: Boolean) {
        listenJob?.cancel()
        listenJob = viewModelScope.launch {
            firebase.connect(code).collect { msg ->
                if (msg.type == "SYNC" && !isHost) {
                    val extId = msg.extensionId ?: return@collect
                    
                    // Only fetch and play the track if it differs from what is currently playing
                    // This prevents constant reloading of the track on every sync event
                    if (lastListenerTrackId != msg.trackId) {
                        lastListenerTrackId = msg.trackId
                        val track = dev.brahmkshatriya.echo.common.models.Track(id = msg.trackId ?: "", title = msg.trackTitle ?: "Listen Together", extras = mapOf(dev.brahmkshatriya.echo.extensions.builtin.unified.UnifiedExtension.EXTENSION_ID to extId))
                        playAction?.invoke(extId, track, false)
                        delay(1200)
                    }

                    val localIsPlaying = isPlayingProvider?.invoke() ?: false
                    if (localIsPlaying != msg.isPlaying) {
                        setPlayingAction?.invoke(msg.isPlaying)
                    }

                    // Apply basic latency compensation
                    // If the remote track is playing, real position is further along than the timestamp indicates
                    val networkLatency = System.currentTimeMillis() - msg.timestamp
                    var expectedPosition = msg.positionMs
                    if (msg.isPlaying) expectedPosition += networkLatency

                    // Only seek if the difference between local and expected position exceeds 2.5 seconds
                    // Strict syncing causes audio stutter, so a buffer threshold is necessary
                    val localPos = browserProvider?.invoke()?.currentPosition ?: 0L
                    if (abs(localPos - expectedPosition) > 2500) {
                        seekAction?.invoke(expectedPosition)
                    }
                }
            }
        }
    }

    private fun fetchAndApplyCurrentState(code: String) {
        viewModelScope.launch {
            firebase.getCurrentState(code) { msg ->
                if (msg != null && msg.trackId != null && msg.extensionId != null) {
                    val extId = msg.extensionId
                    if (lastListenerTrackId != msg.trackId) {
                        lastListenerTrackId = msg.trackId
                        val track = dev.brahmkshatriya.echo.common.models.Track(id = msg.trackId, title = msg.trackTitle ?: "Listen Together", extras = mapOf(dev.brahmkshatriya.echo.extensions.builtin.unified.UnifiedExtension.EXTENSION_ID to extId))
                        playAction?.invoke(extId, track, false)
                    }
                    if (msg.positionMs > 0) seekAction?.invoke(msg.positionMs)
                    setPlayingAction?.invoke(msg.isPlaying)
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

    private fun generateCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    override fun onCleared() {
        super.onCleared()
        if (_state.value is ListenTogetherState.Active) leaveSession()
    }
}
