package local.phantom.companion.data

import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.X25519
import local.phantom.companion.core.net.crypto.bytesToB64
import local.phantom.companion.core.net.pair.PairingQr
import local.phantom.companion.core.net.pair.SelfHandoverClient
import local.phantom.companion.core.net.peer.OwnSelf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Телефон віддає особу лише тому ПК, з яким спарений, лише за згодою і лише
 * за QR «особа». «Передано» пишеться тільки після доказу ПК.
 */
class OwnSelfAdoptTest {

    private class FakeSlot : PeerLogSlot {
        val cells = HashMap<String, String>()
        override fun read(key: String): String? = cells[key]
        override fun write(key: String, value: String?) {
            if (value == null) cells.remove(key) else cells[key] = value
        }
    }

    private val me: OwnSelf.Keys = run {
        val ed = Ed25519.generateKeypair()
        OwnSelf.Keys(OwnSelf.idOf(ed.publicKey), ed.privateKey, X25519.generateKeypair().privateKey)
    }

    private fun qrJson(purpose: String = "self", nodeId: String = NODE): String {
        val pub = bytesToB64(X25519.generateKeypair().publicKey)
        val extra = if (purpose.isEmpty()) "" else ",\"purpose\":\"$purpose\",\"node_id\":\"$nodeId\""
        return """{"v":1,"host":"phantom.local","ip":"192.168.1.10","port":8000,"pair_id":"self-1",""" +
            """"server_pub":"$pub","server_cert_sha256":"dev-no-pin","exp":${Long.MAX_VALUE},""" +
            """"nonce":"AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcY"$extra}"""
    }

    private class Wire(val answer: SelfHandoverClient.Result) {
        val sent = mutableListOf<Pair<PairingQr, OwnSelf.Keys>>()
        suspend fun handOver(qr: PairingQr, keys: OwnSelf.Keys): SelfHandoverClient.Result {
            sent += qr to keys
            return answer
        }
    }

    private fun adopt(
        wire: Wire,
        store: OwnSelfStore = OwnSelfStore(FakeSlot()),
        paired: String? = NODE,
    ) = OwnSelfAdopt(
        pairedNodeId = { paired },
        identity = { me },
        handOver = wire::handOver,
        store = store,
        now = { 1_790_000_000_000L },
    )

    @Test fun `за згодою й QR мого ПК особа їде, і ПК записано як мою копію`() = runTest {
        val wire = Wire(SelfHandoverClient.Result.Done(me.selfId, NODE))
        val store = OwnSelfStore(FakeSlot())
        val out = adopt(wire, store).adopt(qrJson(), consent = true)
        assertEquals(OwnSelfAdopt.Outcome.Done(NODE), out)
        assertEquals(1, wire.sent.size)
        assertTrue(store.isMine(NODE))
        assertEquals(1_790_000_000_000L, store.copies().single().handedAtMs)
    }

    @Test fun `без згоди — нічого не їде`() = runTest {
        val wire = Wire(SelfHandoverClient.Result.Done(me.selfId, NODE))
        assertEquals(OwnSelfAdopt.Outcome.NoConsent, adopt(wire).adopt(qrJson(), consent = false))
        assertTrue(wire.sent.isEmpty())
    }

    @Test fun `QR спарювання — не передача особи`() = runTest {
        val wire = Wire(SelfHandoverClient.Result.Done(me.selfId, NODE))
        assertEquals(OwnSelfAdopt.Outcome.NotSelfQr, adopt(wire).adopt(qrJson(purpose = ""), consent = true))
        assertEquals(OwnSelfAdopt.Outcome.NotSelfQr, adopt(wire).adopt("не json", consent = true))
        assertTrue(wire.sent.isEmpty())
    }

    @Test fun `чужий ПК або неспарений телефон — нічого не їде`() = runTest {
        val wire = Wire(SelfHandoverClient.Result.Done(me.selfId, NODE))
        assertEquals(
            OwnSelfAdopt.Outcome.NotMyComputer,
            adopt(wire).adopt(qrJson(nodeId = "fedcba9876543210"), consent = true),
        )
        assertEquals(OwnSelfAdopt.Outcome.NotMyComputer, adopt(wire, paired = null).adopt(qrJson(), consent = true))
        assertEquals(OwnSelfAdopt.Outcome.NotMyComputer, adopt(wire, paired = "").adopt(qrJson(nodeId = ""), consent = true))
        assertTrue(wire.sent.isEmpty())
    }

    @Test fun `відмова ПК не записує «передано»`() = runTest {
        val store = OwnSelfStore(FakeSlot())
        val wire = Wire(SelfHandoverClient.Result.Refused("not_my_phone"))
        assertEquals(OwnSelfAdopt.Outcome.Refused("not_my_phone"), adopt(wire, store).adopt(qrJson(), consent = true))
        assertFalse(store.isMine(NODE))
    }

    @Test fun `копії переживають перезапуск і забуваються`() {
        val slot = FakeSlot()
        OwnSelfStore(slot).record(NODE, 5L)
        OwnSelfStore(slot).record(NODE, 9L)
        val again = OwnSelfStore(slot)
        assertEquals(listOf(OwnSelfStore.Copy(NODE, 9L)), again.copies())
        again.forget(NODE)
        assertFalse(OwnSelfStore(slot).isMine(NODE))
    }

    @Test fun `у сховищі копій немає жодного ключа`() {
        val slot = FakeSlot()
        OwnSelfStore(slot).record(NODE, 5L)
        val disk = slot.cells.values.joinToString()
        assertFalse(disk.contains(bytesToB64(me.edSeed).trimEnd('=')))
        assertFalse(disk.contains("seed"))
    }

    private companion object {
        const val NODE = "37e70f24bd956f9f"
    }
}
