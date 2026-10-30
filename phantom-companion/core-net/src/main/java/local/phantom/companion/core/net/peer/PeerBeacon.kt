package local.phantom.companion.core.net.peer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * How two phones find each other with no server and no internet.
 *
 * Pairing already scans for the operator's own node; this is the other half —
 * announcing THIS device so somebody else's phone can find it, and listening
 * for peers rather than for the first thing that answers.
 *
 * The announcement carries a key fingerprint, never a name: names are chosen,
 * keys are proved, and a discovery layer that trusts a chosen string is a
 * discovery layer that hands the operator whoever asks.
 */
class PeerBeacon(private val context: Context) {

    private val nsd: NsdManager?
        get() = runCatching {
            context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        }.getOrNull()

    private var registration: NsdManager.RegistrationListener? = null

    /**
     * [lost] — сервіс зник з мережі. Тоді [peerId] — лише префікс з імені
     * екземпляра (адресу зниклого не розвʼязати), тож зіставляти через [names].
     */
    data class Found(val peerId: String, val host: String, val port: Int, val lost: Boolean = false) {
        fun names(id: String): Boolean = if (lost) id.startsWith(peerId) else id == peerId
    }

    /**
     * Announces this device on the LAN. [peerId] is the fingerprint of this
     * device's long-term key — the same identity the roster stores, so a peer
     * that finds this service can tell whether it already knows us.
     */
    fun advertise(peerId: String, port: Int = PeerWire.PORT) {
        val manager = nsd ?: return
        stopAdvertising()
        val info = NsdServiceInfo().apply {
            // Instance names are visible on the network; the fingerprint is
            // public by nature, an operator's name is not.
            serviceName = "$NAME_PREFIX${peerId.take(16)}"
            serviceType = SERVICE_TYPE
            setPort(port)
            runCatching { setAttribute(TXT_PEER_ID, peerId) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo?) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo?, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo?) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo?, errorCode: Int) = Unit
        }
        registration = listener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    fun stopAdvertising() {
        val manager = nsd ?: return
        registration?.let { runCatching { manager.unregisterService(it) } }
        registration = null
    }

    /**
     * Every PHANTOM peer on this network, as they appear. A flow rather than a
     * one-shot scan: peers walk into range and out of it, and a switcher built
     * on a single snapshot is wrong the moment somebody moves.
     */
    fun discover(): Flow<Found> = callbackFlow {
        val manager = nsd ?: run { close(); return@callbackFlow }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String?, errorCode: Int) {
                close()
            }

            override fun onStopDiscoveryFailed(type: String?, errorCode: Int) = Unit
            override fun onDiscoveryStarted(type: String?) = Unit
            override fun onDiscoveryStopped(type: String?) = Unit

            override fun onServiceFound(info: NsdServiceInfo?) {
                val found = info ?: return
                if (found.serviceName?.startsWith(NAME_PREFIX) != true) return
                runCatching {
                    manager.resolveService(
                        found,
                        object : NsdManager.ResolveListener {
                            override fun onResolveFailed(i: NsdServiceInfo?, code: Int) = Unit
                            override fun onServiceResolved(resolved: NsdServiceInfo?) {
                                val r = resolved ?: return
                                val host = r.host?.hostAddress ?: return
                                val id = peerIdOf(r) ?: return
                                // Discovery is the one part of the mesh with
                                // nothing on screen to show for it: a peer is
                                // simply there or it is not. Without a line
                                // here, «the beacon does not work» and «nobody
                                // is around» are the same observation.
                                android.util.Log.i(
                                    TAG_DISCOVERY,
                                    "found $id at $host:${r.port}",
                                )
                                trySend(Found(peerId = id, host = host, port = r.port))
                            }
                        },
                    )
                }
            }

            override fun onServiceLost(info: NsdServiceInfo?) {
                lostOf(info?.serviceName)?.let { trySend(it) }
            }
        }
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
        awaitClose { runCatching { manager.stopServiceDiscovery(listener) } }
    }

    /** Prefers the TXT record; falls back to the instance name's suffix. */
    private fun peerIdOf(info: NsdServiceInfo): String? {
        val fromTxt = runCatching {
            info.attributes[TXT_PEER_ID]?.toString(Charsets.UTF_8)
        }.getOrNull()
        if (!fromTxt.isNullOrBlank()) return fromTxt
        return info.serviceName?.removePrefix(NAME_PREFIX)?.takeIf { it.isNotBlank() }
    }

    companion object {
        const val SERVICE_TYPE = "_phantomlink._tcp."
        const val NAME_PREFIX = "phantom-"
        const val TXT_PEER_ID = "pid"

        /** Log tag for the one part of the mesh that has no surface. */
        const val TAG_DISCOVERY = "PhantomPeers"

        /**
         * Which of the found services are people this operator has already
         * proved a key with. Everything else on the network is a stranger and
         * is NOT offered as a contact — discovery finds addresses, the roster
         * decides who is anybody.
         */
        fun lostOf(serviceName: String?): Found? {
            val prefix = serviceName?.takeIf { it.startsWith(NAME_PREFIX) }?.removePrefix(NAME_PREFIX)
            if (prefix.isNullOrBlank()) return null
            return Found(peerId = prefix, host = "", port = 0, lost = true)
        }

        fun known(found: List<Found>, roster: List<PeerIdentity>): List<Found> {
            val ids = roster.map { it.id }.toSet()
            return found.filter { it.peerId in ids }
        }
    }
}
