package local.phantom.companion.core.net.peer

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * DNS, що памʼятає останню добру відповідь. У підвалі й за captive-порталом
 * резолвер падає частіше, ніж сам сервер: адреса сховка, яку знали годину
 * тому, майже напевно та сама, а «не знайшов імʼя» зупинило б усе.
 *
 * Підміну відповіді (captive повертає свою адресу) це не лікує й не має:
 * TLS там однаково не зійдеться, і це правильна відмова.
 */
class PeerStickyDns(
    private val system: (String) -> List<InetAddress> = Dns.SYSTEM::lookup,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : Dns {

    private class Known(val addresses: List<InetAddress>, val atMs: Long)

    private val known = ConcurrentHashMap<String, Known>()

    override fun lookup(hostname: String): List<InetAddress> {
        val fresh = try {
            system(hostname)
        } catch (e: UnknownHostException) {
            val kept = known[hostname]?.takeIf { nowMs() - it.atMs <= KEEP_MS } ?: throw e
            return kept.addresses
        }
        if (fresh.isNotEmpty()) known[hostname] = Known(fresh, nowMs())
        return fresh
    }

    companion object {
        const val KEEP_MS = 7L * 24 * 3_600_000L

        val shared: PeerStickyDns by lazy { PeerStickyDns() }
    }
}
