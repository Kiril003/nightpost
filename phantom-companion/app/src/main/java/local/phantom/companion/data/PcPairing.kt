package local.phantom.companion.data

import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.phantom.companion.R
import local.phantom.companion.core.net.pair.DeviceInfo
import local.phantom.companion.core.net.pair.PairProofs
import local.phantom.companion.core.net.pair.PairingQr
import local.phantom.companion.core.net.peer.PeerIdentity

/**
 * Спарювання з моїм ПК з одного скану — спільне для рідного екрана й веб-месенджера.
 *
 * Досі ця дорога жила лише у [local.phantom.companion.vm.PhantomViewModel], і
 * веб, де людина натискає «Додати свій пристрій», не мав як нею піти. Спарений
 * ПК одразу стає «моїм пристроєм» ([PeerRosterStore.adoptOwnDevice]), а не
 * людиною в списку.
 */
class PcPairing(private val container: AppContainer) {

    sealed interface Input {
        data class Qr(val qr: PairingQr) : Input
        data class Digits(val digits: String) : Input
        data object Bad : Input
    }

    enum class Failure { BAD_INPUT, EXPIRED, NOT_FOUND, UNREACHABLE, NOT_PROVEN }

    sealed interface Outcome {
        data class Paired(val device: PairedDevice) : Outcome
        data class Failed(val why: Failure, val detail: String = "") : Outcome
    }

    /** Стан для веба: він питає, доки не побачить кінця. */
    sealed interface State {
        data object Idle : State
        data object Working : State
        data object Done : State
        data class Failed(val why: Failure) : State
    }

    @Volatile var state: State = State.Idle
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Почати у фоні; веб дізнається кінець через [state]. */
    fun start(input: String) {
        if (state == State.Working) return
        state = State.Working
        scope.launch {
            state = when (val out = claim(input)) {
                is Outcome.Paired -> State.Done
                is Outcome.Failed -> State.Failed(out.why)
            }
        }
    }

    suspend fun claim(input: String): Outcome {
        val qr = when (val parsed = parse(input)) {
            Input.Bad -> return Outcome.Failed(Failure.BAD_INPUT)
            is Input.Digits -> container.pairDiscovery.resolve(parsed.digits)
                .getOrElse { return Outcome.Failed(Failure.NOT_FOUND, it.message.orEmpty()) }
            is Input.Qr -> parsed.qr
        }
        if (qr.isExpired()) return Outcome.Failed(Failure.EXPIRED)
        return try {
            Outcome.Paired(claimQr(qr))
        } catch (e: NotProven) {
            Outcome.Failed(Failure.NOT_PROVEN)
        } catch (t: Throwable) {
            Outcome.Failed(Failure.UNREACHABLE, t.message.orEmpty())
        }
    }

    private class NotProven : Exception()

    private suspend fun claimQr(qr: PairingQr): PairedDevice {
        val deviceInfo = DeviceInfo(
            name = Build.MODEL,
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            osVersion = "Android ${Build.VERSION.RELEASE}",
        )
        // Довічна сітьова особа телефона їде в паринг: без неї ПК не має чим
        // скласти спільний ключ каналу.
        val me = withContext(Dispatchers.IO) { container.identity.get() }
        val outcome = container.pairClient.claim(
            qr,
            deviceInfo,
            routes = container.api.address.routesForPairing(),
            mesh = PairProofs.MeshBlock(
                peerId = me.id,
                pubKeyB64 = me.publicKeyB64,
                dhPubKeyB64 = me.dhPublicKeyB64,
            ),
        )
        val response = outcome.response
        val node = response.nodeMesh()
        val verified = PairProofs.verifyServerProof(
            sharedKey = outcome.sharedKey,
            deviceJwt = response.deviceJwt,
            serverProofB64 = response.serverProof,
            node = node,
        )
        if (!verified) throw NotProven()

        // Адреса, що щойно відповіла на /pair/claim, — «мережа поруч»; обхідний
        // шлях — перший кінець точки зустрічі з QR.
        val workedUrl = outcome.successfulBaseUrl
        val relay = qr.candidateEndpoints().firstOrNull { it.isRelay }
        val lanUrl = qr.candidateUrls().firstOrNull { it == workedUrl }
            ?: qr.candidateUrls().firstOrNull()
            ?: workedUrl

        val paired = PairedDevice(
            lanUrl = lanUrl,
            relayUrl = relay?.url,
            relayNodeId = relay?.nodeId,
            devNoPin = qr.isDevNoPin,
            deviceId = response.deviceId,
            userId = response.user.id,
            username = response.user.username,
            computerName = response.computerName.trim(),
            deviceJwt = response.deviceJwt,
            expiresAt = response.expiresAt,
            deviceEd25519Priv = outcome.deviceEd25519PrivateKeyB64,
            deviceEd25519Pub = outcome.deviceEd25519PublicKeyB64,
            serverCertSha256 = qr.serverCertSha256,
            nodePeerId = node?.peerId.orEmpty(),
            nodePubKey = node?.pubKeyB64.orEmpty(),
            nodeDhPubKey = node?.dhPubKeyB64.orEmpty(),
        )
        container.store.save(paired)
        // Спарений ПК — мій пристрій, а не людина: одразу в «Мої пристрої».
        if (node != null) {
            val now = System.currentTimeMillis()
            container.peers.adoptOwnDevice(
                PeerIdentity(
                    id = node.peerId,
                    name = container.applicationContext.getString(R.string.paired_pc_name),
                    kind = PeerIdentity.Kind.DEVICE,
                    pubKey = node.pubKeyB64,
                    dhPubKey = node.dhPubKeyB64,
                    transport = lanUrl,
                    addedAtMs = now,
                    lastSeenMs = now,
                    // Ключ приїхав у доказі сервера під ключем із просканованого QR:
                    // сильніше за SHARED, слабше за CAMERA.
                    keySource = PeerIdentity.KeySource.REMOTE,
                ),
            )
        }
        local.phantom.companion.service.PhantomLinkService.ensureRunning(container.applicationContext)
        return paired
    }

    companion object {
        /** Скан QR або (запасне) цифри, продиктовані з екрана ПК. */
        fun parse(raw: String): Input {
            val input = raw.trim()
            if (input.isEmpty()) return Input.Bad
            if (input.all { it.isDigit() }) {
                return if (input.length in 4..10) Input.Digits(input) else Input.Bad
            }
            val qr = runCatching { PairingQr.fromJson(input) }.getOrNull() ?: return Input.Bad
            // QR «особа» (docs/ONE_SELF.md) іде іншою дорогою, не в /pair/claim.
            return if (qr.purpose.isEmpty()) Input.Qr(qr) else Input.Bad
        }
    }
}
