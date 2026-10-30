package local.phantom.companion.data

import local.phantom.companion.PhantomApp
import local.phantom.companion.core.net.pair.PairingQr
import local.phantom.companion.core.net.pair.SelfHandoverClient
import local.phantom.companion.core.net.peer.OwnSelf

/**
 * QR «особа» з екрана ПК → згода людини → особа їде (docs/ONE_SELF.md, крок 1).
 *
 * Лише тому ПК, з яким телефон спарений за QR ([PairedDeviceStore]), а не
 * будь-якому рядку «мій пристрій». «Передано» пишеться тільки після доказу ПК.
 * Екрана тут немає — його дасть крок 5; згоду передає той, хто питав людину.
 */
class OwnSelfAdopt internal constructor(
    private val pairedNodeId: () -> String?,
    private val identity: () -> OwnSelf.Keys?,
    private val handOver: suspend (PairingQr, OwnSelf.Keys) -> SelfHandoverClient.Result,
    private val store: OwnSelfStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed interface Outcome {
        data class Done(val nodeId: String) : Outcome
        data object NoConsent : Outcome
        data object NotSelfQr : Outcome
        data object NotMyComputer : Outcome
        data object NoIdentity : Outcome
        data class Refused(val code: String) : Outcome
    }

    suspend fun adopt(rawQr: String, consent: Boolean): Outcome {
        val qr = runCatching { PairingQr.fromJson(rawQr) }.getOrNull()
            ?.takeIf { it.purpose == SelfHandoverClient.PURPOSE_SELF }
            ?: return Outcome.NotSelfQr
        val mine = pairedNodeId()?.takeIf { it.isNotBlank() }
        if (mine == null || qr.nodeId != mine) return Outcome.NotMyComputer
        if (!consent) return Outcome.NoConsent
        val keys = identity() ?: return Outcome.NoIdentity
        return when (val result = handOver(qr, keys)) {
            is SelfHandoverClient.Result.Done -> {
                store.record(result.nodeId, now())
                Outcome.Done(result.nodeId)
            }
            is SelfHandoverClient.Result.Refused -> Outcome.Refused(result.code)
        }
    }

    companion object {
        /** Насіння читаються з [DeviceIdentityStore] лише тут і лише на мить передачі. */
        fun of(container: AppContainer): OwnSelfAdopt = OwnSelfAdopt(
            pairedNodeId = { runCatching { container.store.load()?.nodePeerId }.getOrNull() },
            identity = {
                runCatching {
                    val me = container.identity.get()
                    OwnSelf.Keys(me.id, me.privateKey, me.dhPrivateKey)
                }.getOrNull()
            },
            handOver = { qr, keys ->
                val client = SelfHandoverClient()
                try {
                    client.handOver(qr, keys)
                } finally {
                    client.close()
                }
            },
            store = OwnSelfStore(container.applicationContext),
        )

        fun of(): OwnSelfAdopt? = runCatching { of(PhantomApp.container()) }.getOrNull()
    }
}
