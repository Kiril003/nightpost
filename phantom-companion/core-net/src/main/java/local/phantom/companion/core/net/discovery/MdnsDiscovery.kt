package local.phantom.companion.core.net.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class MdnsResult(
    val host: String,       // resolved hostname or IP
    val port: Int,
    val txtRecords: Map<String, String>,
)

/**
 * One-shot LAN scan for `_phantom._tcp.` services. Returns the first resolved
 * service or null on timeout. Caller is responsible for stopping the scan
 * (we do it automatically once the first result resolves).
 *
 * Why a single-result API: pairing only needs ONE Phantom-OS instance on the
 * LAN. If multiple are present, the first to respond wins; the user can
 * re-scan if they want a different one.
 *
 * TXT records advertised by PHANTOM OS: `version=1`, `app=PHANTOM OS`
 * (discovery/mdns_publisher.py:91-94). These do not carry crypto material, so
 * a resolved LAN host is used to query /api/v1/pair/resolve/<pin> directly
 * rather than going through the meeting point.
 */
open class MdnsDiscovery(private val context: Context?) {

    private val serviceType = "_phantom._tcp."

    open suspend fun discoverFirst(timeoutMs: Long = 3000L): MdnsResult? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine<MdnsResult?> { cont ->
            val nsd = context!!.getSystemService(Context.NSD_SERVICE) as NsdManager
            var listener: NsdManager.DiscoveryListener? = null

            listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onDiscoveryStarted(serviceType: String?) {}
                override fun onDiscoveryStopped(serviceType: String?) {}
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    val resolveListener = object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                            // Try the next service — do nothing, let timeout handle it
                        }
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress ?: return
                            val txt = info.attributes
                                ?.mapValues { String(it.value ?: ByteArray(0)) }
                                ?: emptyMap()
                            val result = MdnsResult(host, info.port, txt)
                            try { nsd.stopServiceDiscovery(listener!!) } catch (_: Throwable) {}
                            if (cont.isActive) cont.resume(result)
                        }
                    }
                    nsd.resolveService(serviceInfo, resolveListener)
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
            }

            cont.invokeOnCancellation {
                try { nsd.stopServiceDiscovery(listener!!) } catch (_: Throwable) {}
            }

            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }
}
