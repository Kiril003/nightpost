package local.phantom.companion.core.net.peer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.net.crypto.Ed25519
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayFsStatusTest {

    private class Mem : RelayFsKeys.Slot {
        var v: String? = null
        override fun read() = v
        override fun write(value: String) { v = value }
    }

    private var now = 1_790_000_000_000L

    @After fun off() { RelayFs.Flag.enabled = false }

    @Test fun `порожній стан — нулі й null, а не вигадка, і погляд нічого не змінює`() {
        val slot = Mem()
        val keys = RelayFsKeys(slot, { now })
        val view = RelayFsStatus.of(keys, listOf(RelayFsStatus.Person("b", "Оксана", "")), enabled = false, nowMs = now)
        assertNull(view.mySpk)
        val c = view.contacts.single()
        assertFalse(c.v5Capable || c.mySpkSent || c.theirKeys)
        assertEquals(0, c.myOpksIssued)
        assertNull(c.lastOut)
        assertNull("стан не записано — SPK не народився від погляду", slot.v)
    }

    @Test fun `лічильники ключів і печатки — з самих ключів, у формі вебу`() {
        RelayFs.Flag.enabled = true
        val me = Ed25519.generateKeypair()
        val them = Ed25519.generateKeypair()
        val mine = RelayFsKeys(Mem(), { now })
        val theirs = RelayFsKeys(Mem(), { now })
        mine.bundleFor("b", me.privateKey)
        mine.absorb("b", theirs.bundleFor("a", them.privateKey)!!)
        mine.nextForSend("b")
        mine.noteOut("b", PeerMessage.Sealing.PAIR_KEY, "лист завеликий")
        now += 3 * 3_600_000L

        val view = RelayFsStatus.of(mine, listOf(RelayFsStatus.Person("b", "Оксана", "AAAA")), enabled = true, nowMs = now)
        assertEquals(3L, view.mySpk!!.ageH)
        val c = view.contacts.single()
        assertTrue(c.v5Capable && c.mySpkSent && c.theirKeys)
        assertEquals(RelayFsKeys.OPK_BATCH, c.myOpksIssued)
        assertEquals(RelayFsKeys.OPK_BATCH - 1, c.theirOpksHeld)
        assertEquals(180L, c.theirBundleAgeMin)

        val json = Json.parseToJsonElement(RelayFsStatus.json(view)).jsonObject
        val row = json["contacts"]!!.jsonArray.single().jsonObject
        assertEquals("PAIR_KEY", row["last_out"]!!.jsonObject["sealing"]!!.jsonPrimitive.content)
        assertEquals("лист завеликий", row["last_out"]!!.jsonObject["why"]!!.jsonPrimitive.content)
        assertEquals("null", row["last_in"].toString())
        assertEquals("true", json["enabled"].toString())
    }
}
