package dev.brahmkshatriya.echo.ui.listentogether

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

data class WsMessage(
    val type: String = "",
    val trackId: String? = null,
    val extensionId: String? = null,
    val positionMs: Long = 0,
    val isPlaying: Boolean = false,
    val senderId: String = "",
    val senderName: String? = null,
    val senderAvatar: String? = null,
    val timestamp: Long = 0,
    val trackTitle: String? = null,
    val trackArtist: String? = null
)

data class Participant(
    val id: String,
    val name: String,
    val avatarUrl: String? = null,
    val isHost: Boolean = false
)

class ListenTogetherFirebaseClient {
    val clientId: String = UUID.randomUUID().toString().take(8)
    private val db = FirebaseDatabase.getInstance("https://echo-listen-together-default-rtdb.asia-southeast1.firebasedatabase.app")

    fun connect(code: String): Flow<WsMessage> = callbackFlow {
        val ref = db.getReference("sessions/$code/state")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return
                val sender = snapshot.child("senderId").value?.toString() ?: ""
                if (sender == clientId) return
                val msg = WsMessage(
                    type = "SYNC",
                    trackId = snapshot.child("trackId").value?.toString(),
                    extensionId = snapshot.child("extensionId").value?.toString(),
                    positionMs = snapshot.child("positionMs").value?.toString()?.toLongOrNull() ?: 0L,
                    isPlaying = snapshot.child("isPlaying").value?.toString()?.toBoolean() ?: false,
                    senderId = sender,
                    timestamp = snapshot.child("timestamp").value?.toString()?.toLongOrNull() ?: 0L,
                    trackTitle = snapshot.child("trackTitle").value?.toString()
                )
                trySend(msg)
            }
            override fun onCancelled(error: DatabaseError) { close(error.toException()) }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun send(code: String, msg: WsMessage, isHost: Boolean = false) {
        if (msg.type == "SYNC") {
            val stateMap = mapOf(
                "trackId" to msg.trackId,
                "extensionId" to msg.extensionId,
                "positionMs" to msg.positionMs,
                "isPlaying" to msg.isPlaying,
                "senderId" to msg.senderId,
                "timestamp" to msg.timestamp,
                "trackTitle" to msg.trackTitle
            )
            db.getReference("sessions/$code/state").updateChildren(stateMap)
        }
        
        if (msg.type == "JOIN" || isHost) {
            db.getReference("sessions/$code/participants/${msg.senderId}")
                .updateChildren(mapOf(
                    "id" to msg.senderId,
                    "name" to (msg.senderName ?: "Guest"),
                    "avatarUrl" to msg.senderAvatar,
                    "isHost" to isHost,
                    "lastSeen" to System.currentTimeMillis()
                ))
        }
        
        if (msg.type == "LEAVE") {
            db.getReference("sessions/$code/participants/${msg.senderId}").removeValue()
        }
    }

    fun observeParticipants(code: String): Flow<List<Participant>> = callbackFlow {
        val ref = db.getReference("sessions/$code/participants")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = snapshot.children.mapNotNull { child ->
                    val id = child.child("id").value?.toString() ?: return@mapNotNull null
                    val name = child.child("name").value?.toString() ?: "Guest"
                    val avatar = child.child("avatarUrl").value?.toString()
                    val isHost = child.child("isHost").value?.toString()?.toBoolean() ?: false
                    Participant(id, name, avatar, isHost)
                }
                trySend(list)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun getCurrentState(code: String, onResult: (WsMessage?) -> Unit) {
        db.getReference("sessions/$code/state").get().addOnSuccessListener { snapshot ->
            if (!snapshot.exists()) { onResult(null); return@addOnSuccessListener }
            onResult(WsMessage(
                type = "SYNC",
                trackId = snapshot.child("trackId").value?.toString(),
                extensionId = snapshot.child("extensionId").value?.toString(),
                positionMs = snapshot.child("positionMs").value?.toString()?.toLongOrNull() ?: 0L,
                isPlaying = snapshot.child("isPlaying").value?.toString()?.toBoolean() ?: false,
                senderId = snapshot.child("senderId").value?.toString() ?: "",
                timestamp = snapshot.child("timestamp").value?.toString()?.toLongOrNull() ?: 0L,
                trackTitle = snapshot.child("trackTitle").value?.toString()
            ))
        }.addOnFailureListener { onResult(null) }
    }
}
