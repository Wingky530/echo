package dev.brahmkshatriya.echo.ui.listentogether

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID

class ListenTogetherWsClient {

    // 🔧 Ganti dengan URL server kamu setelah deploy
    private val serverUrl = "wss://your-server.up.railway.app/ws"

    val clientId: String = UUID.randomUUID().toString().take(8)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val httpClient = OkHttpClient()
    private var ws: WebSocket? = null

    private val _incoming = MutableSharedFlow<WsMessage>(extraBufferCapacity = 16)
    val incoming: SharedFlow<WsMessage> = _incoming

    fun connect(sessionCode: String, onOpen: () -> Unit, onError: (String) -> Unit) {
        val request = Request.Builder()
            .url("$serverUrl/$sessionCode")
            .build()

        ws = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = onOpen()

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val msg = json.decodeFromString<WsMessage>(text)
                    _incoming.tryEmit(msg)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onError(t.message ?: "Connection failed")
            }
        })
    }

    fun send(msg: WsMessage) {
        ws?.send(json.encodeToString(WsMessage.serializer(), msg))
    }

    fun disconnect() {
        ws?.close(1000, "User left")
        ws = null
    }
}
