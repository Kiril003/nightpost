package local.phantom.companion.net

import local.phantom.companion.core.net.relay.NodeRoutes
import local.phantom.companion.data.PairedDeviceStore

/**
 * Одна відповідь на питання «куди зараз стукати».
 *
 * Мережа поруч завжди перша: вона швидша й нікуди не виходить. Коли звʼязок
 * успішно став на якийсь шлях, [remember] прибиває туди і решту запитів, щоб
 * половина застосунку не лишилась говорити в мертву адресу. Кожне
 * перепідключення знову починає з мережі поруч, тож повернення додому
 * не потребує ані перезапуску, ані окремого опитувача.
 */
class NodeAddress(
    private val store: PairedDeviceStore,
    private val routes: NodeRoutes = NodeRoutes(),
) {
    @Volatile
    private var preferred: String? = null

    fun candidates(): List<String> {
        val paired = store.load() ?: return emptyList()
        val out = LinkedHashSet<String>()
        paired.lanUrl.trimEnd('/').takeIf { it.isNotBlank() }?.let { out += it }
        val relay = paired.relayUrl?.trimEnd('/')
        val node = paired.relayNodeId
        if (!relay.isNullOrBlank() && !node.isNullOrBlank()) {
            routes.localUrlFor(relay, node)?.let { out += it }
        }
        return out.toList()
    }

    fun current(): String? {
        val known = candidates()
        preferred?.let { if (it in known) return it }
        return known.firstOrNull()
    }

    fun remember(url: String) {
        preferred = url
    }

    fun lanUrl(): String? =
        store.load()?.lanUrl?.trimEnd('/')?.takeIf { it.isNotBlank() }

    /** Обхідний шлях коштує мобільного трафіку — треба знати, коли з нього злазити. */
    fun isDetour(url: String): Boolean = url.trimEnd('/') != lanUrl()

    fun lanReachable(timeoutMs: Int = LAN_PROBE_MS): Boolean {
        val url = lanUrl() ?: return false
        return runCatching {
            val uri = java.net.URI(url)
            val port = when {
                uri.port > 0 -> uri.port
                uri.scheme.equals("https", ignoreCase = true) -> 443
                else -> 80
            }
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(uri.host, port), timeoutMs)
                true
            }
        }.getOrDefault(false)
    }

    fun forget() {
        preferred = null
    }

    fun stop() {
        preferred = null
        routes.stopAll()
    }

    /** Паринг ще не збережений, тож шляхи беруться просто з QR. */
    fun routesForPairing(): NodeRoutes = routes

    companion object {
        const val LAN_PROBE_MS: Int = 700
    }
}
