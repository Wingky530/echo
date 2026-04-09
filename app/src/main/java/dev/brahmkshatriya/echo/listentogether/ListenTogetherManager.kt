package dev.brahmkshatriya.echo.listentogether

import android.content.Context
import android.content.SharedPreferences
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.ui.listentogether.ListenTogetherFirebaseClient
import dev.brahmkshatriya.echo.ui.listentogether.WsMessage
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ListenTogetherManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("listen_together", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val firebaseClient = ListenTogetherFirebaseClient()
    private val db = FirebaseDatabase.getInstance("https://echo-listen-together-default-rtdb.asia-southeast1.firebasedatabase.app")

    var currentRoomId: String?
        get() = prefs.getString("room_id", null)
        set(value) = prefs.edit().putString("room_id", value).apply()

    var username: String
        get() = prefs.getString("username", "User") ?: "User"
        set(value) = prefs.edit().putString("username", value).apply()

    var isHost: Boolean
        get() = prefs.getBoolean("is_host", false)
        set(value) = prefs.edit().putBoolean("is_host", value).apply()

    // Kita tambahkan parameter extensionId eksplisit agar tidak bingung nyari di objek Track
    fun updateNowPlaying(track: Track, extensionId: String?, position: Long, isPlaying: Boolean) {
        val roomId = currentRoomId ?: return
        if (!isHost) return

        scope.launch {
            val ref = db.getReference("sessions/\$roomId/messages").push()
            val msg = WsMessage(
                type = "SYNC",
                trackId = track.id,
                extensionId = extensionId,
                positionMs = position,
                isPlaying = isPlaying,
                senderId = firebaseClient.clientId,
                senderName = username,
                timestamp = System.currentTimeMillis()
            )
            ref.setValue(msg)
        }
    }
}
