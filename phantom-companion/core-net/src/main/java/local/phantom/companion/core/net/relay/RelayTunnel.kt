package local.phantom.companion.core.net.relay

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Локальна адреса, за якою вузол доступний з будь-якої мережі світу.
 *
 * Тунель приймає зʼєднання на 127.0.0.1 і возить їхні байти до точки зустрічі,
 * а звідти — у вузол. Усередину труби йде вже зашифрований TLS вузла, прибитий
 * до відбитка з QR, тож ані точка зустрічі, ані провайдер не бачать нічого,
 * крім шуму. Для решти застосунку це звичайний `https://` — жодне місце
 * виклику не знає, що воно вже не в локальній мережі.
 */
class RelayTunnel(
    private val relayBaseUrl: String,
    private val nodeId: String,
    private val client: HttpClient = defaultClient(),
) {
    private val lock = Any()
    private var server: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private var localUrl: String? = null

    val isRunning: Boolean get() = synchronized(lock) { server != null }

    fun localBaseUrl(): String? = synchronized(lock) { localUrl }

    fun start(): String {
        synchronized(lock) {
            localUrl?.let { return it }
            require(nodeId.isNotBlank()) { "RelayTunnel: node_id порожній" }
            val socket = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK))
            val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            server = socket
            scope = worker
            val url = "https://$LOOPBACK:${socket.localPort}"
            localUrl = url
            worker.launch { accept(socket, worker) }
            Log.i(TAG, "тунель на $url → $relayBaseUrl (вузол $nodeId)")
            return url
        }
    }

    fun stop() {
        val (socket, worker) = synchronized(lock) {
            val pair = server to scope
            server = null
            scope = null
            localUrl = null
            pair
        }
        runCatching { socket?.close() }
        worker?.cancel()
    }

    private suspend fun accept(socket: ServerSocket, worker: CoroutineScope) {
        while (worker.isActive && !socket.isClosed) {
            val accepted = runCatching { socket.accept() }.getOrNull() ?: return
            worker.launch { carry(accepted) }
        }
    }

    private suspend fun carry(socket: Socket) {
        socket.tcpNoDelay = true
        try {
            client.webSocket(clientUrl(relayBaseUrl, nodeId)) {
                val outbound = launch(Dispatchers.IO) {
                    val buffer = ByteArray(CHUNK)
                    val input = socket.getInputStream()
                    while (true) {
                        val read = runCatching { input.read(buffer) }.getOrDefault(-1)
                        if (read <= 0) break
                        // Труба могла закритись просто зараз — це кінець сесії,
                        // а не збій, який має валити батьківську область.
                        val sent = runCatching { send(Frame.Binary(true, buffer.copyOf(read))) }
                        if (sent.isFailure) break
                    }
                }
                try {
                    val output = socket.getOutputStream()
                    for (frame in incoming) {
                        val bytes = when (frame) {
                            is Frame.Binary -> frame.readBytes()
                            is Frame.Text -> frame.readBytes()
                            else -> continue
                        }
                        output.write(bytes)
                        output.flush()
                    }
                } finally {
                    runCatching { socket.close() }
                    outbound.cancel()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "тунельна сесія обірвалась: ${t.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val TAG = "RelayTunnel"
        private const val LOOPBACK = "127.0.0.1"
        private const val BACKLOG = 32
        private const val CHUNK = 16 * 1024

        fun defaultClient(): HttpClient = HttpClient(OkHttp) {
            install(WebSockets) { maxFrameSize = Long.MAX_VALUE }
        }

        /** Точка зустрічі має справжній сертифікат, тож тут звичайна довіра системи. */
        fun clientUrl(relayBaseUrl: String, nodeId: String): String =
            "${socketScheme(relayBaseUrl)}/relay/client?node_id=$nodeId"

        fun socketScheme(relayBaseUrl: String): String {
            val trimmed = relayBaseUrl.trim().trimEnd('/')
            return when {
                trimmed.startsWith("https://", true) -> "wss://" + trimmed.substring(8)
                trimmed.startsWith("http://", true) -> "ws://" + trimmed.substring(7)
                else -> trimmed
            }
        }
    }
}
