package dev.brahmkshatriya.echo.ui.listentogether

import kotlinx.serialization.Serializable

@Serializable
data class WsMessage(
    val type: String,
    val trackId: String? = null,
    val extensionId: String? = null,
    val positionMs: Long = 0,
    val isPlaying: Boolean = false,
    val senderId: String = "",
    val senderName: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
