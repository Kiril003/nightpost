package local.phantom.companion.core.net.peer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.X25519
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * Наскрізний v5 «телефон ↔ ПК» на векторі `relay_fs_e2e.json`
 * (`phantom-os/src/backend/scripts/relay_fs_e2e_vector.py`).
 *
 * Тут — половина телефона з ТИМИ САМИМИ зернами: пачка й лист виходять байт у
 * байт як у файлі (а pytest `test_relay_fs_e2e.py` веде саме ці байти крізь
 * справжній `PhoneCourier` ПК). Лист ПК телефон відчиняє ключами, які видав
 * сам; повтор не відчиняється; вилучений телефон прочитаного не відчиняє.
 */
class RelayFsE2eTest {

    private val v: JsonObject = run {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("relay_fs_e2e.json"))
        Json.parseToJsonElement(stream.reader(Charsets.UTF_8).readText()).jsonObject
    }

    private fun s(vararg path: String): String {
        var node = v
        for (i in 0 until path.size - 1) node = node[path[i]]!!.jsonObject
        return node[path.last()]!!.jsonPrimitive.content
    }

    private fun b(vararg path: String): ByteArray = Base64.getDecoder().decode(s(*path))
    private fun seed(label: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(label.toByteArray(Charsets.UTF_8))

    private class Mem(var v: String? = null) : RelayFsKeys.Slot {
        override fun read() = v
        override fun write(value: String) { v = value }
    }

    private val clock get() = v["clock_ms"]!!.jsonPrimitive.long
    private val pcId get() = s("pc", "id")
    private val phoneId get() = s("phone", "id")
    private val pc get() = RelayFs.Identity(b("pc", "ed"), b("pc", "dh"))
    private val phoneEd get() = seed(s("seeds", "phone_ed"))
    private val phoneDh get() = seed(s("seeds", "phone_dh"))
    private val me get() = RelayFs.Identity(Ed25519.publicKeyFromPrivate(phoneEd), X25519.publicKeyFromPrivate(phoneDh))

    /** Генератор ключів як у вектора: i-й ключ — sha256(префікс + i). */
    private fun keys(slot: Mem = Mem()): RelayFsKeys {
        var i = 0
        val prefix = s("seeds", "phone_fs_prefix")
        return RelayFsKeys(slot, { clock }) {
            val priv = seed(prefix + i++)
            X25519.Keypair(priv, X25519.publicKeyFromPrivate(priv))
        }
    }

    @Before fun on() { RelayFs.Flag.enabled = true }

    @After fun off() { RelayFs.Flag.enabled = false }

    @Test fun `особа телефона — та, що у векторі`() {
        assertEquals(s("phone", "ed"), Base64.getEncoder().encodeToString(me.ed))
        assertEquals(s("phone", "dh"), Base64.getEncoder().encodeToString(me.dh))
    }

    @Test fun `пачка й лист телефона — байт у байт як у векторі, лист ПК відчиняється`() {
        val slot = Mem()
        val keys = keys(slot)
        assertEquals(s("phone_bundle"), RelayFs.encodeBundle(keys.bundleFor(pcId, phoneEd)!!))

        val pcBundle = RelayFs.decodeBundle(s("pc_bundle"), pc.ed)
        assertNotNull("пачку ПК підписано ключем ПК", pcBundle)
        keys.absorb(pcId, pcBundle!!)

        val letter = PeerSealing.seal(
            PeerMessage(id = "e2e-phone-1", fromId = phoneId, toId = pcId, body = "Телефон → ПК: лист під v5", sentAtMs = clock + 123),
            phoneEd,
        )
        val expectedJson = Json.parseToJsonElement(s("phone_letter", "json")).jsonObject
        assertEquals(expectedJson["signature"]!!.jsonPrimitive.content, letter.signature)
        val sealed = RelayFsMail.seal(letter, me, phoneDh, pc, keys, seed(s("seeds", "phone_ek")))
        assertTrue(sealed is RelayFsMail.Sealed.V5)
        assertEquals(s("phone_letter", "frame"), (sealed as RelayFsMail.Sealed.V5).frame)
        assertEquals(PeerMessage.Sealing.ONE_TIME_KEY, keys.lastOut(pcId)?.sealing)

        val opksBefore = keys.issuedTo(pcId)
        val opened = RelayFsMail.open(s("pc_letter", "frame"), me, phoneDh, pcId, pc, keys)
        assertNotNull("телефон відчиняє лист ПК", opened)
        assertEquals("e2e-pc-1", opened!!.id)
        assertEquals("ПК → телефон: лист під v5", opened.body)
        assertTrue("підпис ПК", PeerSealing.verify(opened, pc.ed, expectedFromId = pcId))
        assertEquals("OPK стерто", opksBefore - 1, keys.issuedTo(pcId))
        assertEquals(PeerMessage.Sealing.ONE_TIME_KEY, keys.lastIn(pcId)?.sealing)

        // Повтор тієї самої копії: тексту немає, але копію впізнано для квитанції.
        assertNull(RelayFsMail.open(s("pc_letter", "frame"), me, phoneDh, pcId, pc, keys))
        assertEquals("e2e-pc-1", RelayFsMail.landed(s("pc_letter", "frame"), pcId, keys))

        // Вилучення телефона: довічні ключі + зашифрований файл стану — і все одно ні.
        val seized = RelayFsKeys(Mem(slot.v), { clock })
        assertNull(RelayFs.open(s("pc_letter", "frame"), me, phoneDh, pc, seized::spkPrivate) { seized.opkPrivate(pcId, it) })
        assertTrue("у стані немає тексту листа", "лист під v5" !in (slot.v ?: ""))
    }
}
