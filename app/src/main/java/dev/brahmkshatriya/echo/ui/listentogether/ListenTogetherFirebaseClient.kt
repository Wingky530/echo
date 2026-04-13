package dev.brahmkshatriya.echo.ui.listentogether

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

data class WsMessage(val type: String = "", val trackId: String? = null, val extensionId: String? = null, val positionMs: Long = 0, val isPlaying: Boolean = false, val senderId: String = "", val senderName: String? = null, val senderAvatar: String? = null, val timestamp: Long = 0, val trackTitle: String? = null)
data class Participant(val id: String, val name: String, val avatarUrl: String? = null, val isHost: Boolean = false)

class ListenTogetherFirebaseClient {
    val clientId: String = UUID.randomUUID().toString().take(8)
    private val db = FirebaseDatabase.getInstance("https://echo-listen-together-default-rtdb.asia-southeast1.firebasedatabase.app")
    
    fun connect(code: String): Flow<WsMessage> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // 🔧 FIX: Jangan kirim ROOM_DESTROYED di sini, biar gak auto-kick pas baru connect
                if (!snapshot.exists()) return 
                val sender = snapshot.child("senderId").value?.toString() ?: ""
                if (sender == clientId) return
                trySend(WsMessage("SYNC", snapshot.child("trackId").value?.toString(), snapshot.child("extensionId").value?.toString(), snapshot.child("positionMs").value?.toString()?.toLongOrNull() ?: 0L, snapshot.child("isPlaying").value?.toString()?.toBoolean() ?: false, sender, null, null, snapshot.child("timestamp").value?.toString()?.toLongOrNull() ?: 0L, snapshot.child("trackTitle").value?.toString()))
            }
            override fun onCancelled(error: DatabaseError) { close(error.toException()) }
        }
        db.getReference("sessions/$code/state").addValueEventListener(listener)
        awaitClose { db.getReference("sessions/$code/state").removeEventListener(listener) }
    }

    fun send(code: String, msg: WsMessage, isHost: Boolean = false) {
        if (msg.type == "SYNC") db.getReference("sessions/$code/state").updateChildren(mapOf("trackId" to msg.trackId, "extensionId" to msg.extensionId, "positionMs" to msg.positionMs, "isPlaying" to msg.isPlaying, "senderId" to msg.senderId, "timestamp" to msg.timestamp, "trackTitle" to msg.trackTitle))
        if (msg.type == "JOIN" || isHost) {
            val nameToSet = msg.senderName?.takeIf { it.isNotBlank() && it != "Guest" } ?: "Guest-${msg.senderId.take(4)}"
            val pRef = db.getReference("sessions/$code/participants/${msg.senderId}")
            pRef.updateChildren(mapOf("id" to msg.senderId, "isHost" to isHost, "lastSeen" to System.currentTimeMillis(), "name" to nameToSet) + (msg.senderAvatar?.takeIf { it.isNotBlank() }?.let { mapOf("avatarUrl" to it) } ?: emptyMap()))
            pRef.onDisconnect().removeValue()
            if (isHost) db.getReference("sessions/$code").onDisconnect().removeValue()
        }
        if (msg.type == "LEAVE") db.getReference("sessions/$code/participants/${msg.senderId}").removeValue()
    }
    
    fun observeParticipants(code: String): Flow<List<Participant>> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // 🔧 FIX: Guest ditendang kalau Host hapus room (data participants hilang)
                if (!snapshot.exists()) {
                    trySend(emptyList()) 
                    return
                }
                trySend(snapshot.children.mapNotNull { Participant(it.child("id").value?.toString() ?: return@mapNotNull null, it.child("name").value?.toString() ?: "Guest", it.child("avatarUrl").value?.toString(), it.child("isHost").value?.toString()?.toBoolean() ?: false) })
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        db.getReference("sessions/$code/participants").addValueEventListener(listener)
        awaitClose { db.getReference("sessions/$code/participants").removeEventListener(listener) }
    }

    fun observePermission(code: String): Flow<Int> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) { trySend(snapshot.value?.toString()?.toIntOrNull() ?: 0) }
            override fun onCancelled(error: DatabaseError) {}
        }
        db.getReference("sessions/$code/permission").addValueEventListener(listener)
        awaitClose { db.getReference("sessions/$code/permission").removeEventListener(listener) }
    }

    fun setPermission(code: String, level: Int) { db.getReference("sessions/$code/permission").setValue(level) }

    fun getCurrentState(code: String, onResult: (WsMessage?) -> Unit) {
        db.getReference("sessions/$code/state").get().addOnSuccessListener { s ->
            if (!s.exists()) onResult(null) else onResult(WsMessage("SYNC", s.child("trackId").value?.toString(), s.child("extensionId").value?.toString(), s.child("positionMs").value?.toString()?.toLongOrNull() ?: 0L, s.child("isPlaying").value?.toString()?.toBoolean() ?: false, s.child("senderId").value?.toString() ?: "", null, null, s.child("timestamp").value?.toString()?.toLongOrNull() ?: 0L, s.child("trackTitle").value?.toString()))
        }.addOnFailureListener { onResult(null) }
    }
}
