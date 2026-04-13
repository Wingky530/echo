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
    val type: String = "", val trackId: String? = null, val extensionId: String? = null,
    val positionMs: Long = 0, val isPlaying: Boolean = false, val senderId: String = "",
    val senderName: String? = null, val senderAvatar: String? = null,
    val timestamp: Long = 0, val trackTitle: String? = null, val queueContext: String? = null
)

data class Participant(val id: String, val name: String, val avatarUrl: String? = null, val isHost: Boolean = false)

class ListenTogetherFirebaseClient {
    val clientId: String = UUID.randomUUID().toString().take(8)
    private val db = FirebaseDatabase.getInstance("https://echo-listen-together-default-rtdb.asia-southeast1.firebasedatabase.app")

    fun connect(code: String): Flow<WsMessage> = callbackFlow {
        val stateRef = db.getReference("sessions/$code/state")
        val cmdRef = db.getReference("sessions/$code/commands")
        
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return
                val sender = snapshot.child("senderId").value?.toString() ?: ""
                if (sender == clientId) return
                
                // FIXED: Ambil tipe pesan (bisa SYNC atau ADD_QUEUE)
                val type = snapshot.child("type").value?.toString() ?: "SYNC"
                
                trySend(WsMessage(
                    type, 
                    snapshot.child("trackId").value?.toString(), 
                    snapshot.child("extensionId").value?.toString(), 
                    snapshot.child("positionMs").value?.toString()?.toLongOrNull() ?: 0L, 
                    snapshot.child("isPlaying").value?.toString()?.toBoolean() ?: false, 
                    sender, null, null, 
                    snapshot.child("timestamp").value?.toString()?.toLongOrNull() ?: 0L, 
                    snapshot.child("trackTitle").value?.toString(),
                    snapshot.child("queueContext").value?.toString()
                ))
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        
        stateRef.addValueEventListener(listener)
        cmdRef.addValueEventListener(listener)
        awaitClose { 
            stateRef.removeEventListener(listener)
            cmdRef.removeEventListener(listener)
        }
    }

    fun send(code: String, msg: WsMessage, isHost: Boolean = false) {
        // FIXED: ADD_QUEUE dikirim ke jalur 'commands', SYNC ke jalur 'state'
        val ref = if (msg.type == "ADD_QUEUE") {
            db.getReference("sessions/$code/commands")
        } else {
            db.getReference("sessions/$code/state")
        }

        val data = mutableMapOf<String, Any?>(
            "type" to msg.type,
            "trackId" to msg.trackId,
            "extensionId" to msg.extensionId,
            "positionMs" to msg.positionMs,
            "isPlaying" to msg.isPlaying,
            "senderId" to msg.senderId,
            "timestamp" to msg.timestamp,
            "trackTitle" to msg.trackTitle
        )
        if (msg.queueContext != null) data["queueContext"] = msg.queueContext
        ref.setValue(data)

        if (msg.type == "JOIN" || isHost) {
            val nameToSet = msg.senderName?.takeIf { it.isNotBlank() && it != "Guest" } ?: "Guest-${msg.senderId.take(4)}"
            db.getReference("sessions/$code/participants/${msg.senderId}").updateChildren(mapOf<String, Any?>(
                "id" to msg.senderId, "isHost" to isHost, "name" to nameToSet
            ))
        }
    }

    fun checkRoomExists(code: String, callback: (Boolean) -> Unit) {
        db.getReference("sessions/$code").get().addOnSuccessListener { callback(it.exists()) }
    }
    
    fun setPermission(code: String, level: Int) {
        db.getReference("sessions/$code/permission").setValue(level)
    }
}
