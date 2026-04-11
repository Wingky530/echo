package dev.brahmkshatriya.echo.ui.listentogether

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.math.abs

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
    // Lu tinggal lanjutin sisa fungsinya (createSession, dll) di bawah sini
}
