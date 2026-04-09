package dev.brahmkshatriya.echo.listentogether

import android.content.Context
import android.content.SharedPreferences
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

class ListenTogetherManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("listen_together", Context.MODE_PRIVATE)
    private val httpClient = OkHttpClient()
    private val firebaseUrl = "https://echo-listen-together-default-rtdb.asia-southeast1.firebasedatabase.app"
    private val scope = CoroutineScope(Dispatchers.IO)

    var currentRoomId: String?
        get() = prefs.getString("room_id", null)
        set(value) = prefs.edit().putString("room_id", value).apply()

    var username: String
        get() = prefs.getString("username", "User") ?: "User"
        set(value) = prefs.edit().putString("username", value).apply()

    var isHost: Boolean
        get() = prefs.getBoolean("is_host", false)
        set(value) = prefs.edit().putBoolean("is_host", value).apply()

    fun createRoom(onSuccess: (roomId: String) -> Unit) {
        scope.launch {
            val roomId = UUID.randomUUID().toString().take(6).uppercase()
            val data = """{"host":"$username","nowPlaying":"","artist":"","position":0,"isPlaying":false,"updatedAt":${System.currentTimeMillis()}}"""
            val body = data.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$firebaseUrl/rooms/$roomId.json")
                .put(body)
                .build()
            runCatching { httpClient.newCall(request).execute() }
            currentRoomId = roomId
            isHost = true
            onSuccess(roomId)
        }
    }

    fun joinRoom(roomId: String) {
        currentRoomId = roomId.uppercase()
        isHost = false
    }

    fun leaveRoom() {
        if (isHost) {
            currentRoomId?.let { deleteRoom(it) }
        }
        currentRoomId = null
        isHost = false
    }

    fun updateNowPlaying(track: Track, position: Long, isPlaying: Boolean) {
        val roomId = currentRoomId ?: return
        if (!isHost) return
        scope.launch {
            val title = track.title.replace("\"", "'")
            val artist = track.artists.firstOrNull()?.name?.replace("\"", "'") ?: ""
            val data = """{"nowPlaying":"$title","artist":"$artist","position":$position,"isPlaying":$isPlaying,"updatedAt":${System.currentTimeMillis()}}"""
            val body = data.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$firebaseUrl/rooms/$roomId.json")
                .patch(body)
                .build()
            runCatching { httpClient.newCall(request).execute() }
        }
    }

    fun getRoomState(roomId: String, onResult: (String?) -> Unit) {
        scope.launch {
            val request = Request.Builder()
                .url("$firebaseUrl/rooms/$roomId.json")
                .get()
                .build()
            val result = runCatching {
                httpClient.newCall(request).execute().body?.string()
            }.getOrNull()
            onResult(result)
        }
    }

    private fun deleteRoom(roomId: String) {
        scope.launch {
            val request = Request.Builder()
                .url("$firebaseUrl/rooms/$roomId.json")
                .delete()
                .build()
            runCatching { httpClient.newCall(request).execute() }
        }
    }
}
