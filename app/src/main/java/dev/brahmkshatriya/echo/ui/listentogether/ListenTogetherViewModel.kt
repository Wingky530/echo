package dev.brahmkshatriya.echo.ui.listentogether

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

class ListenTogetherViewModel : ViewModel() {
    private val _state = MutableStateFlow<ListenTogetherState>(ListenTogetherState.Idle)
    val state: StateFlow<ListenTogetherState> = _state

    var playerState: Any? = null
    var browserProvider: Any? = null
    var isPlayingProvider: (() -> Boolean)? = null
    var playAction: Any? = null
    var seekAction: Any? = null
    var setPlayingAction: Any? = null

    fun createSession() {}
    fun joinSession(code: String) {}
    fun leaveSession() {}
}
