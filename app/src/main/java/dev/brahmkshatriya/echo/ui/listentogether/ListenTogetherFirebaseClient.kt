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
    val timestamp: Long = 0
)

class ListenTogetherFirebaseClient {

    val clientId: String = UUID.randomUUID().toString().take(8)

    private val db = FirebaseDatabase.getInstance(
        "https://echo-listen-together-default-rtdb.asia-southeast1.firebasedatabase.app"
    )

    fun connect(code: String): Flow<WsMessage> = callbackFlow {
        val ref = db.getReference("sessions/$code/state")

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // Parse manual biar 100% aman dari R8 / Obfuscation
                val type = snapshot.child("type").getValue(String::class.java) ?: ""
                if (type != "SYNC") return
                
                val sender = snapshot.child("senderId").getValue(String::class.java) ?: ""
                if (sender != clientId) {
                    val msg = WsMessage(
                        type = type,
                        trackId = snapshot.child("trackId").getValue(String::class.java),
                        extensionId = snapshot.child("extensionId").getValue(String::class.java),
                        positionMs = snapshot.child("positionMs").getValue(Long::class.java) ?: 0L,
                        isPlaying = snapshot.child("isPlaying").getValue(Boolean::class.java) ?: false,
                        senderId = sender,
                        timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L
                    )
                    trySend(msg)
                }
            }
            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun send(code: String, msg: WsMessage) {
        if (msg.type == "SYNC") {
            db.getReference("sessions/$code/state").setValue(msg)
        } else {
            db.getReference("sessions/$code/messages").push().setValue(msg)
        }

        if (msg.type == "JOIN" || msg.type == "SYNC") {
            db.getReference("sessions/$code/participants/${msg.senderId}")
                .setValue(mapOf(
                    "id" to msg.senderId,
                    "name" to (msg.senderName ?: "User"),
                    "isHost" to (msg.type == "SYNC"),
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
                    val id = child.child("id").getValue(String::class.java) ?: return@mapNotNull null
                    val name = child.child("name").getValue(String::class.java) ?: "User"
                    val isHost = child.child("isHost").getValue(Boolean::class.java) ?: false
                    Participant(id, name, isHost)
                }
                trySend(list)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }
}
