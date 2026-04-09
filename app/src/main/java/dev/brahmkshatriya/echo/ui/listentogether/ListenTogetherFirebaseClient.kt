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

    private var sessionCode: String? = null

    // Stream pesan masuk dari Firebase
    fun connect(code: String): Flow<WsMessage> = callbackFlow {
        sessionCode = code
        val ref = db.getReference("sessions/$code/messages")

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // Hanya ambil pesan terbaru
                val last = snapshot.children.lastOrNull() ?: return
                val msg = last.getValue(WsMessage::class.java) ?: return
                // Jangan proses pesan dari diri sendiri
                if (msg.senderId != clientId) {
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
        val ref = db.getReference("sessions/$code/messages")
        ref.push().setValue(msg)

        // Update participants
        if (msg.type == "JOIN" || msg.type == "SYNC") {
            db.getReference("sessions/$code/participants/${msg.senderId}")
                .setValue(mapOf(
                    "id" to msg.senderId,
                    "name" to (msg.senderName ?: "User"),
                    "isHost" to (msg.type == "SYNC"),
                    "lastSeen" to System.currentTimeMillis()
                ))
        }

        // Hapus participant saat LEAVE
        if (msg.type == "LEAVE") {
            db.getReference("sessions/$code/participants/${msg.senderId}").removeValue()
        }
    }

    fun cleanup(code: String) {
        // Bersihkan pesan lama (opsional, biar DB tidak bengkak)
        val ref = db.getReference("sessions/$code/messages")
        ref.get().addOnSuccessListener { snapshot ->
            if (snapshot.childrenCount > 50) {
                // Hapus semua kecuali 10 terakhir
                snapshot.children.toList()
                    .dropLast(10)
                    .forEach { it.ref.removeValue() }
            }
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
