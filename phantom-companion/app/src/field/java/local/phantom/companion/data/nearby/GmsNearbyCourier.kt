package local.phantom.companion.data.nearby

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import local.phantom.companion.core.solana.NearbyState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Google Nearby Connections, P2P_CLUSTER: BLE/Bluetooth між двома телефонами,
 * без роутера й без інтернету. Купюра — кілька КБ, ширшої дороги не треба.
 */
internal class GmsNearbyCourier(
    private val context: Context,
    private val scope: CoroutineScope,
    private val selfId: () -> String,
    private val contacts: () -> Collection<String>,
    private val online: () -> Boolean,
    private val inbound: (String) -> String?,
) : NearbyCourier {

    private val services: Boolean by lazy {
        runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        }.getOrDefault(false)
    }
    private val client: ConnectionsClient by lazy { Nearby.getConnectionsClient(context) }

    private val _state = MutableStateFlow(NearbyState.IDLE)
    override val state: StateFlow<NearbyState> = _state.asStateFlow()

    private val awaiting = ConcurrentHashMap<String, Handing>()
    private val handing = Mutex()
    private val status = NearbyStatus()
    // Кадри одного зʼєднання — по черзі: кишеня мусить лягти в розмову раніше за купюру.
    private val inbox = Channel<Pair<String, String>>(Channel.UNLIMITED)
    private var window: Job? = null

    private class Handing(val frames: List<String>) {
        val receipts = CopyOnWriteArrayList<String>()
        val over = CompletableDeferred<Unit>()

        fun got(receipt: String) {
            receipts += receipt
            if (receipts.size >= frames.size) over.complete(Unit)
        }
    }

    init {
        scope.launch(Dispatchers.Default) { ready() && settle() }
        scope.launch(Dispatchers.IO) {
            for ((endpoint, frame) in inbox) {
                val reply = runCatching { inbound(frame) }.getOrNull()?.let { NearbyPacket.encode(NearbyPacket.Receipt(it)) }
                if (reply != null) client.sendPayload(endpoint, Payload.fromBytes(reply)) else client.disconnectFromEndpoint(endpoint)
            }
        }
    }

    override fun missingPermissions(): List<String> =
        if (!services) emptyList()
        else NearbyPlan.permissions(Build.VERSION.SDK_INT).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    override fun wantedNow(): Boolean = services && !online()

    override fun landed() {
        status.landed()
        if (ready()) settle()
    }

    override fun missed() {
        status.missed()
        if (ready()) settle()
    }

    override fun listen() {
        if (!ready()) return
        window?.cancel()
        window = scope.launch {
            delay(LISTEN_MS)
            runCatching { client.stopAdvertising() }
            status.listen(false)
            settle()
        }
        val already = status.listening
        status.listen(true)
        if (already) {
            settle()
            return
        }
        client.startAdvertising(NearbyTag.of(selfId()), SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(STRATEGY).build())
            .addOnFailureListener {
                status.listen(false)
                settle()
            }
        settle()
    }

    override suspend fun handOff(toId: String, frames: List<String>): List<String> {
        if (frames.isEmpty() || !ready()) return emptyList()
        return handing.withLock {
            status.searching()
            settle()
            val got = Handing(frames)
            try {
                withTimeoutOrNull(HANDOFF_MS) { attempt(toId, got) }
                got.receipts.toList()
            } finally {
                runCatching { client.stopDiscovery() }
            }
        }
    }

    private suspend fun attempt(toId: String, got: Handing) {
        val found = CompletableDeferred<String>()
        val discovery = object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                if (info.serviceId == SERVICE_ID && NearbyTag.whose(info.endpointName, listOf(toId)) == toId) {
                    found.complete(endpointId)
                }
            }

            override fun onEndpointLost(endpointId: String) = Unit
        }
        runCatching { client.stopDiscovery() }
        if (!client.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(STRATEGY).build()).done()) return
        val endpoint = found.await()
        runCatching { client.stopDiscovery() }
        // До requestConnection: відповідь може прийти раніше, ніж завершиться Task.
        awaiting[endpoint] = got
        try {
            if (!client.requestConnection(NearbyTag.of(selfId()), endpoint, lifecycle).done()) return
            got.over.await()
        } finally {
            awaiting.remove(endpoint)
            runCatching { client.disconnectFromEndpoint(endpoint) }
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val ours = awaiting.containsKey(endpointId)
            if (ours || NearbyTag.whose(info.endpointName, contacts()) != null) {
                client.acceptConnection(endpointId, payloads)
            } else {
                client.rejectConnection(endpointId)
            }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            val got = awaiting[endpointId] ?: return
            if (!resolution.status.isSuccess) {
                got.over.complete(Unit)
                return
            }
            for (frame in got.frames) {
                val bytes = NearbyPacket.encode(NearbyPacket.Frame(frame))
                if (bytes == null) {
                    got.over.complete(Unit)
                    return
                }
                client.sendPayload(endpointId, Payload.fromBytes(bytes))
            }
        }

        override fun onDisconnected(endpointId: String) {
            awaiting[endpointId]?.over?.complete(Unit)
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (val packet = NearbyPacket.decode(payload.asBytes())) {
                is NearbyPacket.Receipt -> awaiting[endpointId]?.got(packet.receipt)
                is NearbyPacket.Frame -> inbox.trySend(endpointId to packet.frame)
                null -> client.disconnectFromEndpoint(endpointId)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private fun ready(): Boolean {
        if (!services) {
            _state.value = NearbyState.NO_SERVICES
            return false
        }
        if (missingPermissions().isNotEmpty()) {
            _state.value = NearbyState.NEEDS_PERMISSION
            return false
        }
        return selfId().isNotBlank()
    }

    private fun settle(): Boolean {
        _state.value = status.state()
        return true
    }

    private suspend fun Task<Void>.done(): Boolean {
        val result = CompletableDeferred<Boolean>()
        addOnSuccessListener { result.complete(true) }
        addOnFailureListener { result.complete(false) }
        return result.await()
    }

    private companion object {
        const val SERVICE_ID = "local.phantom.companion.cash.v1"
        val STRATEGY: Strategy = Strategy.P2P_CLUSTER
        const val LISTEN_MS = 5 * 60_000L
        const val HANDOFF_MS = 60_000L
    }
}
