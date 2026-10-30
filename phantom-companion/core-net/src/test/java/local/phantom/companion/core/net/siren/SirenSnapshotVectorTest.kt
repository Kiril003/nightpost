package local.phantom.companion.core.net.siren

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import local.phantom.companion.core.net.crypto.bytesToHex
import local.phantom.companion.core.net.crypto.hexToBytes
import local.phantom.companion.core.net.peer.PeerIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Сирена-маяк — на тому самому векторі, який підписує сервер сайту
 * (`platform-site/server/siren_vector.py`, звіряє `tests/test_siren.py`).
 * Розбіжність тут — це маяк, якому телефон ніколи не повірить.
 */
class SirenSnapshotVectorTest {

    private val v: JsonObject = run {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("siren_vector.json"))
        Json.parseToJsonElement(stream.reader(Charsets.UTF_8).readText()).jsonObject
    }
    private val keyId get() = v["key_id"]!!.jsonPrimitive.int
    private val keys get() = mapOf(keyId to hexToBytes(v["public_hex"]!!.jsonPrimitive.content))
    private val cases get() = v["cases"]!!.jsonArray.map { it.jsonObject }
    private fun raw(c: JsonObject) = Base64.getDecoder().decode(c["snapshot_b64"]!!.jsonPrimitive.content)

    @Test fun `версія, домен, межі свіжості й порядок областей — як на сервері`() {
        assertEquals(v["version"]!!.jsonPrimitive.int, SirenSnapshot.VERSION)
        assertEquals(v["domain_hex"]!!.jsonPrimitive.content, bytesToHex(SirenSnapshot.DOMAIN))
        assertEquals(v["fresh_s"]!!.jsonPrimitive.long, SirenSnapshot.FRESH_S)
        assertEquals(v["skew_s"]!!.jsonPrimitive.long, SirenSnapshot.SKEW_S)
        assertEquals(v["oblast_order"]!!.jsonArray.map { it.jsonPrimitive.content }, PeerIntent.OBLAST_ORDER)
    }

    @Test fun `кожен знімок вектора перевіряється й розбирається байт у байт`() {
        for (c in cases) {
            val bytes = raw(c)
            assertEquals(SirenSnapshot.SNAPSHOT_BYTES, bytes.size)
            assertEquals(c["body_hex"]!!.jsonPrimitive.content, bytesToHex(bytes.copyOfRange(0, SirenSnapshot.BODY_BYTES)))
            val snap = SirenSnapshot.verify(bytes, keys)
            assertNotNull(snap)
            val want = c["alerts"]!!.jsonObject.mapValues { it.value.jsonPrimitive.int }
            assertEquals(want, snap!!.alerts)
            assertEquals(c["seq"]!!.jsonPrimitive.long, snap.seq)
            assertEquals(c["observed_at"]!!.jsonPrimitive.long, snap.observedAt)
            assertEquals(keyId, snap.keyId)
        }
    }

    @Test fun `чужий ключ, невідомий id, змінений байт, обрізаний знімок — знімка немає`() {
        val bytes = raw(cases.first())
        assertNull(SirenSnapshot.verify(bytes, mapOf(keyId to ByteArray(32) { 1 })))
        assertNull(SirenSnapshot.verify(bytes, mapOf(keyId + 1 to keys.getValue(keyId))))
        for (i in listOf(0, 3, 11, 20, 50)) {
            val bad = bytes.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull("байт $i змінено, а підпис пройшов", SirenSnapshot.verify(bad, keys))
        }
        assertNull(SirenSnapshot.verify(bytes.copyOf(91), keys))
    }

    @Test fun `старий знімок — правда про тоді, не «зараз», годинник із майбутнього — відмова`() {
        val c = cases.first()
        val observed = c["observed_at"]!!.jsonPrimitive.long
        val snap = SirenSnapshot.verify(raw(c), keys, nowS = observed + 60)!!
        assertTrue(snap.fresh(observed + 60))
        assertFalse(snap.fresh(observed + SirenSnapshot.FRESH_S + 1))
        assertEquals(7, snap.ageMinutes(observed + 7 * 60 + 30))
        assertNull(SirenSnapshot.verify(raw(c), keys, nowS = observed - SirenSnapshot.SKEW_S - 1))
    }

    @Test fun `у збірці не закріплено тестового ключа вектора`() {
        val testKey = keys.getValue(keyId)
        assertFalse(SirenKeys.PINNED.values.any { it.contentEquals(testKey) })
        assertNull(SirenSnapshot.verify(raw(cases.first()), SirenKeys.PINNED))
    }
}
