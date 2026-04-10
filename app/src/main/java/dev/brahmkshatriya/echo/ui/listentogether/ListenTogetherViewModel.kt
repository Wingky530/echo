package dev.brahmkshatriya.echo.ui.listentogether

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import kotlin.random.Random
import kotlin.math.abs

data class Participant(val id: String, val name: String, val isHost: Boolean = false)

sealed class ListenTogetherState {
    object Idle : ListenTogetherState()
    object Connecting : ListenTogetherState()
    data class Active(val sessionCode: String, val isHost: Boolean, val participants: List<Participant> = emptyList()) : ListenTogetherState()
    data class Error(val message: String) : ListenTogetherState()
}

data class SyncEvent(val trackId: String, val extensionId: String?, val positionMs: Long, val isPlaying: Boolean, val trackTitle: String? = null, val trackArtist: String? = null, val timestamp: Long)

class ListenTogetherViewModel : ViewModel() {

    // ✅ Akses ke PlayerViewModel (di-inject dari BottomSheet)
    var playerState: dev.brahmkshatriya.echo.playback.PlayerState? = null
    var browserProvider: (() -> androidx.media3.session.MediaController?)? = null
    var isPlayingProvider: (() -> Boolean)? = null
    var playAction: ((String, dev.brahmkshatriya.echo.common.models.Track, Boolean) -> Unit)? = null
    var seekAction: ((Long) -> Unit)? = null
    var setPlayingAction: ((Boolean) -> Unit)? = null

    private val _state = MutableStateFlow<ListenTogetherState>(ListenTogetherState.Idle)
    val state: StateFlow<ListenTogetherState> = _state

    private val _syncEvent = MutableSharedFlow<SyncEvent>(replay = 1, extraBufferCapacity = 5, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val syncEvent: SharedFlow<SyncEvent> = _syncEvent

    private val firebase = ListenTogetherFirebaseClient()
    private var listenJob: Job? = null
    private var participantsJob: Job? = null
    private var hostBroadcastJob: Job? = null
    
    private var lastListenerTrackId: String? = null

    fun createSession(trackId: String?, extensionId: String?) {
        val code = generateCode()
        val joinedAt = System.currentTimeMillis()
        _state.value = ListenTogetherState.Active(sessionCode = code, isHost = true, participants = listOf(Participant(firebase.clientId, "Host", isHost = true)))
        firebase.send(code, WsMessage(type = "JOIN", senderId = firebase.clientId, senderName = "Host", timestamp = joinedAt))
        startHostBroadcast(code)
        observeParticipants(code)
    }

    fun joinSession(code: String) {
        _state.value = ListenTogetherState.Connecting
        val cleanCode = code.uppercase().trim()
        val joinedAt = System.currentTimeMillis()

        viewModelScope.launch {
            try {
                firebase.send(cleanCode, WsMessage(type = "JOIN", senderId = firebase.clientId, senderName = "User", timestamp = joinedAt))
                _state.value = ListenTogetherState.Active(sessionCode = cleanCode, isHost = false)
                fetchAndApplyCurrentState(cleanCode)
                startListening(cleanCode)
                observeParticipants(cleanCode)
            } catch (e: Exception) {
                _state.value = ListenTogetherState.Error(e.message ?: "Gagal bergabung")
            }
        }
    }

    // ✅ Pindah sini biar jalan terus di background walau menu ditutup
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
                
                android.util.Log.d("LT_HOST", "Broadcasting: track=${track.id} ext=${extId} pos=${positionMs}")
                
                broadcastSync(track.id, extId, positionMs, isPlaying, track.title, track.artists?.firstOrNull()?.name)
            }
        }
    }

    // ✅ Pindah sini juga
    private fun startListening(code: String) {
        listenJob?.cancel()
        listenJob = viewModelScope.launch {
            firebase.connect(code).collect { msg ->
                val s = _state.value as? ListenTogetherState.Active ?: return@collect
                if (s.isHost) return@collect
                
                if (msg.type == "SYNC") {
                    val extId = msg.extensionId ?: return@collect
                    
                    android.util.Log.d("LT_DEBUG", "SYNC received: track=${msg.trackId} title=${msg.trackTitle} pos=${msg.positionMs}")

                    // 1. Ganti Lagu
                    if (lastListenerTrackId != msg.trackId) {
                        lastListenerTrackId = msg.trackId
                        val track = dev.brahmkshatriya.echo.common.models.Track(id = msg.trackId ?: "", title = msg.trackTitle ?: "Listen Together", extras = mapOf(dev.brahmkshatriya.echo.extensions.builtin.unified.UnifiedExtension.EXTENSION_ID to extId))
                        playAction?.invoke(extId, track, false)
                        delay(1200) // Tunggu muat
                    }

                    // 2. Play/Pause
                    val localIsPlaying = isPlayingProvider?.invoke() ?: false
                    if (localIsPlaying != msg.isPlaying) {
                        setPlayingAction?.invoke(msg.isPlaying)
                    }

                    // 3. Kompensasi Posisi
                    val networkLatency = System.currentTimeMillis() - msg.timestamp
                    var expectedPosition = msg.positionMs
                    if (msg.isPlaying) expectedPosition += networkLatency

                    val localPos = browserProvider?.invoke()?.currentPosition ?: 0L
                    if (abs(localPos - expectedPosition) > 2500) {
                        seekAction?.invoke(expectedPosition)
                    }
                }
            }
        }
    }

    fun broadcastSync(trackId: String, extensionId: String?, positionMs: Long, isPlaying: Boolean, trackTitle: String? = null, trackArtist: String? = null) {
        val s = _state.value as? ListenTogetherState.Active ?: return
        if (!s.isHost) return
        firebase.send(s.sessionCode, WsMessage(type = "SYNC", trackId = trackId, extensionId = extensionId, positionMs = positionMs, isPlaying = isPlaying, trackTitle = trackTitle, trackArtist = trackArtist, senderId = firebase.clientId, timestamp = System.currentTimeMillis()))
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
                if (msg != null && msg.trackId != null) {
                    val extId = msg.extensionId ?: return@launch
                    if (lastListenerTrackId != msg.trackId) {
                        lastListenerTrackId = msg.trackId
                        val track = dev.brahmkshatriya.echo.common.models.Track(id = msg.trackId ?: "", title = "Listen Together", extras = mapOf(dev.brahmkshatriya.echo.extensions.builtin.unified.UnifiedExtension.EXTENSION_ID to extId))
                        playAction?.invoke(extId, track, false)
                    }
                    if (msg.positionMs > 0) seekAction?.invoke(msg.positionMs)
                    setPlayingAction?.invoke(msg.isPlaying)
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
