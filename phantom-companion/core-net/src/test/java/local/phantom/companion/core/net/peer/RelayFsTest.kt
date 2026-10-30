package local.phantom.companion.core.net.peer

import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.X25519
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

/** Конверт v5 і його ключі: властивості з docs/crypto/RELAY_FS.md §8. */
class RelayFsTest {

    private var now = 1_790_000_000_000L

    private class Mem : RelayFsKeys.Slot {
        var v: String? = null
        override fun read() = v
        override fun write(value: String) { v = value }
    }

    private inner class Phone(val id: String) {
        val ed = Ed25519.generateKeypair()
        val dh = X25519.generateKeypair()
        val me = RelayFs.Identity(ed.publicKey, dh.publicKey)
        val slot = Mem()
        var keys = RelayFsKeys(slot, { now })

        fun reboot() { keys = RelayFsKeys(slot, { now }) }

        /** Віддати пачку [other] так, як її повезе звичайний конверт пари. */
        fun handPrekeysTo(other: Phone) {
            val bundle = keys.bundleFor(other.id, ed.privateKey) ?: return
            val body = RelayFs.encodeBundle(bundle)
            val decoded = RelayFs.decodeBundle(body, me.ed)!!
            other.keys.absorb(id, decoded)
        }

        fun letter(to: Phone, text: String) = PeerMessage(id = "m-$text", fromId = id, toId = to.id, body = text, sentAtMs = now)

        fun send(to: Phone, text: String): RelayFsMail.Sealed = RelayFsMail.seal(letter(to, text), me, dh.privateKey, to.me, keys)

        fun open(frame: String, from: Phone): PeerMessage? = RelayFsMail.open(frame, me, dh.privateKey, from.id, from.me, keys)
    }

    private val a = Phone("aaaa")
    private val b = Phone("bbbb")

    @Before fun on() { RelayFs.Flag.enabled = true }

    @After fun off() { RelayFs.Flag.enabled = false }

    private fun v5(s: RelayFsMail.Sealed): RelayFsMail.Sealed.V5 {
        assertTrue("чекали v5, а вийшло $s", s is RelayFsMail.Sealed.V5)
        return s as RelayFsMail.Sealed.V5
    }

    @Test fun `круг — сплячий B відчиняє лист від A`() {
        b.handPrekeysTo(a)
        val sealed = v5(a.send(b, "привіт"))
        assertTrue(sealed.withOpk)
        assertEquals("привіт", b.open(sealed.frame, a)!!.body)
    }

    @Test fun `ключ стерто після вжитку — повтор кадру не відчиняється, і після перезапуску теж`() {
        b.handPrekeysTo(a)
        val frame = v5(a.send(b, "раз")).frame
        assertNotNull(b.open(frame, a))
        assertNull(b.open(frame, a))
        b.reboot()
        assertNull(b.open(frame, a))
    }

    @Test fun `повтор листа під SPK теж не відчиняється`() {
        b.handPrekeysTo(a)
        repeat(RelayFsKeys.OPK_BATCH) { a.keys.nextForSend(b.id) }
        val frame = v5(a.send(b, "spk")).frame
        assertNotNull(b.open(frame, a))
        assertNull(b.open(frame, a))
    }

    @Test fun `вилучений довічний ключ не відчиняє минулого листа v5`() {
        b.handPrekeysTo(a)
        val frame = v5(a.send(b, "таємниця")).frame
        assertNotNull(b.open(frame, a))
        // SPK обернувся двічі й вийшов за вікно — його стерто.
        now += RelayFsKeys.SPK_ROTATE_MS + 1
        b.keys.currentSpk(b.ed.privateKey)
        now += RelayFsKeys.SPK_RETENTION_MS + 1
        b.keys.currentSpk(b.ed.privateKey)
        // Нападник має довічні ключі обох телефонів і весь диск B — але не стерті prekey.
        val seized = RelayFsKeys(Mem().also { it.v = b.slot.v }, { now })
        assertNull(RelayFsMail.open(frame, b.me, b.dh.privateKey, a.id, a.me, seized))
        assertNull(RelayFs.open(frame, b.me, b.dh.privateKey, a.me, { b.dh.privateKey }, { b.dh.privateKey }))
    }

    @Test fun `той самий сценарій для v3 — довічний ключ відчиняє минуле (дірка, яку закриваємо)`() {
        val ka = PeerChannel.derive(a.dh.privateKey, b64(b.dh.publicKey), a.id, b.id)!!
        val sealed = PeerEnvelope.seal(a.letter(b, "таємниця"), ka)!!
        val stolen = PeerChannel.derive(b.dh.privateKey, b64(a.dh.publicKey), b.id, a.id)!!
        assertEquals("таємниця", PeerEnvelope.open(sealed.frame) { stolen }!!.body)
    }

    @Test fun `поза порядком — листи 3, 1, 2 відчиняються всі`() {
        b.handPrekeysTo(a)
        val frames = (1..3).map { v5(a.send(b, "№$it")).frame }
        for (i in listOf(2, 0, 1)) assertEquals("№${i + 1}", b.open(frames[i], a)!!.body)
    }

    @Test fun `вичерпання — OPK, далі SPK, застарілі ключі або прапорець вимкнено — чесний відкат на v3`() {
        b.handPrekeysTo(a)
        val withOpk = (1..RelayFsKeys.OPK_BATCH).map { v5(a.send(b, "o$it")) }
        assertTrue(withOpk.all { it.withOpk })
        val spkOnly = v5(a.send(b, "spk"))
        assertFalse(spkOnly.withOpk)
        assertEquals("spk", b.open(spkOnly.frame, a)!!.body)
        now += RelayFsKeys.SPK_MAX_AGE_MS + 1
        assertTrue(a.send(b, "старе") is RelayFsMail.Sealed.Fallback)
        RelayFs.Flag.enabled = false
        assertTrue(Phone("cccc").also { b.handPrekeysTo(it) }.send(b, "x") is RelayFsMail.Sealed.Fallback)
    }

    @Test fun `поповнення — нова пачка, коли в контакта лишилось мало OPK`() {
        b.handPrekeysTo(a)
        assertNull("пачка не потрібна, доки OPK вистачає", b.keys.bundleFor(a.id, b.ed.privateKey))
        val frames = (1..RelayFsKeys.OPK_BATCH - RelayFsKeys.OPK_LOW + 1).map { v5(a.send(b, "$it")).frame }
        frames.forEach { assertNotNull(b.open(it, a)) }
        val refill = b.keys.bundleFor(a.id, b.ed.privateKey)
        assertNotNull(refill)
        assertEquals(RelayFsKeys.OPK_BATCH, refill!!.opks.size)
    }

    @Test fun `контакт втратив стан v5 — його нова пачка з тими самими id замінює стару чергу`() {
        b.handPrekeysTo(a)
        // B переустановлено або відновлено без файла v5: лічильники знову з 1, ключі інші.
        b.slot.v = null
        b.reboot()
        b.handPrekeysTo(a)
        assertEquals("стара черга викинута, а не доклеєна", RelayFsKeys.OPK_BATCH, a.keys.opksLeftFor(b.id))
        assertNotNull("лист іде на ключ, який у B справді є", b.open(v5(a.send(b, "після відновлення")).frame, a))
    }

    @Test fun `підроблена пачка і OPK, виданий іншому контакту, не працюють`() {
        val bundle = b.keys.bundleFor(a.id, b.ed.privateKey)!!
        val forged = bundle.copy(spk = bundle.spk.copy(pub = b64(X25519.generateKeypair().publicKey)))
        assertNull(RelayFs.decodeBundle(RelayFs.encodeBundle(forged), b.me.ed))
        assertNull("чужий Ed25519", RelayFs.decodeBundle(RelayFs.encodeBundle(bundle), a.me.ed))
        // C отримує OPK, видані A, — і пише ними B: B не відчинить.
        val c = Phone("cccc")
        c.keys.absorb(b.id, bundle)
        val frame = v5(c.send(b, "чужий opk")).frame
        assertNull(b.open(frame, c))
    }

    @Test fun `змінений будь-який байт кадру або інший відправник — не відчиняється`() {
        b.handPrekeysTo(a)
        val frame = v5(a.send(b, "ціле")).frame
        for (bad in listOf(frame.replace("\"aaaa\"", "\"zzzz\""), frame.replace("\"spk\":", "\"spk\":9"), frame.dropLast(3) + "AA\"}")) {
            assertNull(b.open(bad, a))
        }
        assertNull(RelayFsMail.open(frame, b.me, b.dh.privateKey, "cccc", Phone("cccc").me, b.keys))
    }

    private fun b64(raw: ByteArray) = Base64.getEncoder().encodeToString(raw)
}
