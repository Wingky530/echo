package dev.brahmkshatriya.echo.ui.listentogether

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Data class representing a participant in a shared listening session.
 */
data class Participant(
    val name: String,
    val isHost: Boolean,
    val avatarUrl: String? = null
)

/**
 * Represents the various states of a "Listen Together" session.
 */
sealed class ListenTogetherState {
    /** Default state when no session is active. */
    object Idle : ListenTogetherState()
    
    /** State active while establishing a connection. */
    object Connecting : ListenTogetherState()
    
    /** * The state when a session is live.
     * @property extensionId ID used to verify compatibility between host and guests.
     */
    data class Active(
        val sessionCode: String,
        val isHost: Boolean,
        val extensionId: String? = null,
        val participants: List<Participant> = emptyList()
    ) : ListenTogetherState()
    
    /** Represents a failure state with a specific [message]. */
    data class Error(val message: String) : ListenTogetherState()
}

class ListenTogetherViewModel : ViewModel() {
    private val _state = MutableStateFlow<ListenTogetherState>(ListenTogetherState.Idle)
    val state: StateFlow<ListenTogetherState> = _state

    // Note: You can re-add your function logic (createSession, etc.) in Acode later
}
