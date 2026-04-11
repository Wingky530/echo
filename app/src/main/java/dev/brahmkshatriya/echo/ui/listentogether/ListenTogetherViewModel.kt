package dev.brahmkshatriya.echo.ui.listentogether

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Data class representing a participant in a session.
 */
data class Participant(
    val name: String,
    val isHost: Boolean,
    val avatarUrl: String? = null
)

/**
 * Sealed class defining all possible states for the Listen Together feature.
 */
sealed class ListenTogetherState {
    object Idle : ListenTogetherState()
    object Connecting : ListenTogetherState()
    data class Active(
        val sessionCode: String,
        val isHost: Boolean,
        val extensionId: String? = null,
        val participants: List<Participant> = emptyList()
    ) : ListenTogetherState()
    data class Error(val message: String) : ListenTogetherState()
}

/**
 * ViewModel to manage playback synchronization and session states.
 */
class ListenTogetherViewModel : ViewModel() {
    private val _state = MutableStateFlow<ListenTogetherState>(ListenTogetherState.Idle)
    val state: StateFlow<ListenTogetherState> = _state

    // Placeholder properties to satisfy ListenTogetherBottomSheet references
    var playerState: Any? = null
    var browserProvider: Any? = null
    var isPlayingProvider: (() -> Boolean)? = null
    var playAction: Any? = null
    var seekAction: Any? = null
    var setPlayingAction: Any? = null

    /** Logic for creating a new session */
    fun createSession() {}
    
    /** Logic for joining an existing session */
    fun joinSession(code: String) {}
    
    /** Logic for leaving the current session */
    fun leaveSession() {}
}
