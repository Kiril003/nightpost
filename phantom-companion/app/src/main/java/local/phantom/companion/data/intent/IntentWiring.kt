package local.phantom.companion.data.intent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import local.phantom.companion.core.net.intent.IntentClient
import local.phantom.companion.core.net.intent.IntentGateway
import local.phantom.companion.core.net.peer.PeerIntent
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.data.AppContainer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Клієнт Намірів, підключений до справжнього світу телефона:
 *  - мій ПК — той, з яким спарено цей телефон (`PairedDevice.nodePeerId`),
 *    ключ пари — той самий, яким їздять листи ([local.phantom.companion.data.PeerMesh.pairKeyFor]);
 *  - сховок — звичайний лист сітки; «ПК отримав» — квитанція (DELIVERED);
 *  - SMS — лише збірка `field` ([IntentSmsFlavor]);
 *  - відповіді — листи `PH-ANS:` у журналі сітки і SMS від мого шлюзу.
 */
class IntentWiring(private val container: AppContainer) {

    val settings = IntentSettings(container.applicationContext)

    val client = IntentClient(
        pc = IntentClient.PcLink {
            val pcId = runCatching { container.store.load()?.nodePeerId }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: return@PcLink null
            val me = container.mesh.selfIdFlow.value.takeIf { it.isNotBlank() } ?: return@PcLink null
            val key = container.mesh.pairKeyFor(pcId) ?: return@PcLink null
            IntentClient.Pc(phoneId = me, pcId = pcId, pairKey = key)
        },
        relay = object : IntentClient.RelayPort {
            override fun send(pcId: String, body: String): String? = container.mesh.send(pcId, body)?.id

            override fun delivered(letterId: String): Boolean =
                container.mesh.outbox.value.any { it.id == letterId && it.state == PeerMessage.State.DELIVERED }

            override fun ready(): Boolean = container.mesh.relayReady()
        },
        sms = IntentSmsFlavor.port(container.applicationContext, settings),
        slot = IntentJournalSlot(container.applicationContext),
    )

    private fun pcId(): String? =
        runCatching { container.store.load()?.nodePeerId }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Домашній шлюз — лише у збірці field (він шле SMS); у store його немає. */
    val gateway: IntentGateway? = IntentSmsFlavor.sender(container.applicationContext)?.let { out ->
        IntentGateway(
            allowed = { settings.allowed.toSet() },
            lan = GatewayLan { container.api.address.current() ?: runCatching { container.store.load()?.lanUrl }.getOrNull() },
            relay = IntentGateway.RelayPort { body -> pcId()?.let { container.mesh.send(it, body)?.id } },
            sms = out,
            ledger = GatewayLedgerSlot(container.applicationContext),
            zoneOffsetMs = { java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong() },
        )
    }

    private val started = AtomicBoolean(false)

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        IntentInbox.sink = { text -> client.onSms(text) }
        GatewayInbox.sink = { from, text -> gateway?.onSms(from, text) }
        scope.launch {
            while (isActive) {
                runCatching { client.step() }
                delay(STEP_MS)
            }
        }
        // Знімок «Сирени-маяка», що приїхав відповіддю на Намір (сховком чи SMS), —
        // у той самий сховок, що й прямий: перевірка підпису там.
        scope.launch {
            client.journal.collect { journal ->
                for (r in journal) {
                    val a = r.answer ?: continue
                    if (r.op != PeerIntent.OP_SIREN || a.status != PeerIntent.Status.OK) continue
                    val raw = runCatching { java.util.Base64.getDecoder().decode(a.bodyB64) }.getOrNull() ?: continue
                    runCatching { container.backpack.siren.offer(raw) }
                }
            }
        }
        scope.launch {
            val seen = HashSet<String>()
            container.mesh.outbox.collect { log ->
                for (m in log) {
                    if (!PeerIntent.isAnswer(m.body) || !seen.add(m.id)) continue
                    // Моє прохання — клієнтові; інакше це відповідь, яку шлюз чекає для SMS.
                    val mine = runCatching { client.onLetter(m.fromId, m.body) }.getOrDefault(false)
                    if (!mine && m.fromId == pcId()) runCatching { gateway?.onLetter(m.body) }
                }
                runCatching { client.step() }
            }
        }
    }

    private companion object {
        /** Ескалація рахується від 30 с — хід раз на 5 с її не проґавить. */
        const val STEP_MS = 5_000L
    }
}
