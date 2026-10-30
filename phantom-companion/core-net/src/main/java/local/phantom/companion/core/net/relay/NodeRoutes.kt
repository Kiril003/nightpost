package local.phantom.companion.core.net.relay

import local.phantom.companion.core.net.pair.PairingQr
import java.util.concurrent.ConcurrentHashMap

/**
 * Перетворює шляхи з QR на адреси, за якими справді можна постукати.
 *
 * `lan` віддається як є, `relay` — як локальний кінець тунелю. Мережа поруч
 * завжди попереду: вона швидша й нікуди не виходить, а зовнішній шлях
 * підіймається лише коли перший мовчить.
 */
class NodeRoutes(
    private val tunnelUrl: ((String, String) -> String?)? = null,
) {
    private val tunnels = ConcurrentHashMap<String, RelayTunnel>()

    fun localUrlFor(relayUrl: String, nodeId: String): String? {
        tunnelUrl?.let { return it(relayUrl, nodeId) }
        return runCatching {
            tunnels.getOrPut("$relayUrl|$nodeId") { RelayTunnel(relayUrl, nodeId) }.start()
        }.getOrNull()
    }

    fun resolve(endpoint: PairingQr.Endpoint): String? {
        if (!endpoint.isRelay) return endpoint.url.trimEnd('/')
        val node = endpoint.nodeId
        if (node.isNullOrBlank()) return null
        return localUrlFor(endpoint.url.trimEnd('/'), node)
    }

    fun resolveAll(qr: PairingQr): List<String> {
        val all = qr.candidateEndpoints()
        return (all.filter { it.isDirect } + all.filter { it.isRelay })
            .mapNotNull(::resolve)
            .distinct()
    }

    fun stopAll() {
        tunnels.values.forEach { it.stop() }
        tunnels.clear()
    }
}
