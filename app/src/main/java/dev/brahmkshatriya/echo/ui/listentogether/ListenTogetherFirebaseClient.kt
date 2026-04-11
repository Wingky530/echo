/**
 * Package dev.brahmkshatriya.echo.ui.listentogether
 * 
 * Purpose: Custom Firebase client acting as a real-time bridge for the "Listen Together" feature
 * It handles the WebSocket-like communication for syncing playback states and managing session participants
 *
 * Key Components:
 *  - WsMessage: Data class for syncing playback position, play/pause state, and track info
 *  - Participant: Data class representing users in the session
 *  - connect(), send(), observeParticipants(): Methods handling Firebase Realtime Database interactions via flows
 *
 * Dependencies:
 *  - com.google.firebase.database: For low-latency real-time state synchronization
 *  - kotlinx.coroutines.flow: For converting callback-based Firebase listeners into cold flows
 */
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
    // Bridges Firebase's realtime callback into a cold Flow that automatically unregisters the listener when cancelled
    fun connect(code: String): Flow<WsMessage> = callbackFlow {
        val ref = db.getReference("sessions/$code/state")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return
                val sender = snapshot.child("senderId").value?.toString() ?: ""
                // Ignore messages sent by this client to prevent an infinite feedback loop
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
            val updateData = mutableMapOf<String, Any>(
                "id" to msg.senderId,
                "isHost" to isHost,
                "lastSeen" to System.currentTimeMillis()
            )
            val nameToSet = msg.senderName?.takeIf { it.isNotBlank() && it != "Guest" } ?: "Guest-${msg.senderId.take(4)}"
            updateData["name"] = nameToSet
            updateData["name"] = nameToSet
            msg.senderAvatar?.takeIf { it.isNotBlank() }?.let { updateData["avatarUrl"] = it }
            db.getReference("sessions/$code/participants/${msg.senderId}").updateChildren(updateData)
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
