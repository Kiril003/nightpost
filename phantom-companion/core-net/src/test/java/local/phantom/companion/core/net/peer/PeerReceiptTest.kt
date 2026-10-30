package local.phantom.companion.core.net.peer

import local.phantom.companion.core.net.crypto.randomBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Доставлено» — єдиний стан, який ніхто не перечитує.
 *
 * Кожен тест тут про одну підробку: сторонній, який бачив увесь кадр; чужа
 * квитанція, переставлена на інший лист; підслухана відповідь, відтворена над
 * новою спробою. Усі три колись працювали, бо ACK називав id, а id лежав на
 * дроті відкрито.
 */
class PeerReceiptTest {

    private val channel = randomBytes(32)
    private val stranger = randomBytes(32)
    private val nonce = "AQIDBAUGBwgJCgsM"

    private fun tag(
        key: ByteArray = channel,
        id: String = "m-1",
        from: String = "aaaa1111",
        to: String = "bbbb2222",
        n: String = nonce,
    ) = PeerReceipt.of(key, id, from, to, n)

    @Test
    fun `the same delivery always earns the same receipt`() {
        assertEquals(tag(), tag())
        assertEquals(PeerReceipt.LENGTH, tag()!!.length)
    }

    @Test
    fun `only the pair key writes it`() {
        // Спостерігач бачить кадр цілком і все одно не може відзвітувати.
        assertNotEquals(tag(), tag(key = stranger))
    }

    @Test
    fun `a receipt for one message does not confirm another`() {
        assertNotEquals(tag(id = "m-1"), tag(id = "m-2"))
    }

    @Test
    fun `a receipt from one attempt does not confirm the next`() {
        // Кожна спроба має свій nonce, тож підслухана відповідь застаріває
        // рівно тоді, коли лист поїхав удруге.
        assertNotEquals(tag(n = "AQIDBAUGBwgJCgsM"), tag(n = "DAsKCQgHBgUEAwIB"))
    }

    @Test
    fun `re-addressing a receipt changes it`() {
        assertNotEquals(tag(), tag(to = "cccc3333"))
        assertNotEquals(tag(), tag(from = "cccc3333"))
    }

    @Test
    fun `nothing to sign with means no receipt, never an empty one`() {
        assertNull(PeerReceipt.of(ByteArray(0), "m-1", "a", "b", nonce))
        assertNull(tag(id = ""))
        assertNull(tag(n = ""))
        assertNull(tag(from = " "))
    }

    @Test
    fun `separated fields cannot be slid past one another`() {
        // «ab|c» і «a|bc» мусять дати різні квитанції, інакше пара імен
        // складається в чуже посвідчення.
        assertNotEquals(tag(from = "aaaa", to = "1111bbbb2222"), tag(from = "aaaa1111"))
    }

    @Test
    fun `a field carrying the separator is refused, not signed`() {
        // Поля склеюються роздільником без довжин. Поки всі чотири пише той,
        // хто вже має ключ, підсунути роздільник нікому — але рівно це
        // перестає бути правдою, щойно одне з них приходить із чужих рук
        // (нова дія рукостискання, переліт через посередника). Тоді «a|bc» і
        // «ab|c» складаються в одне підписане речення.
        val sep = ''
        assertNull(tag(id = "m-1${sep}aaaa1111"))
        assertNull(tag(from = "aaaa${sep}1111"))
        assertNull(tag(to = "bbbb${sep}2222"))
        assertNull(tag(n = "AQIDBAUG${sep}BwgJCgsM"))

        // Сам зсув: ці двоє склеюються в ОДНЕ й те саме речення, тож без
        // відмови квитанція одного листа зараховувала б інший. Обидва мусять
        // не існувати, а не збігтись.
        assertNull(tag(id = "m-1", from = "aaaa${sep}1111"))
        assertNull(tag(id = "m-1${sep}aaaa", from = "1111"))

        // Поле без роздільника лишається звичайним полем.
        assertNotNull(tag(id = "m-1", from = "aaaa1111"))
    }

    @Test
    fun `matching is exact and survives nonsense`() {
        val mine = tag()!!
        assertTrue(PeerReceipt.matches(mine, mine))
        assertFalse(PeerReceipt.matches(mine, mine.dropLast(1)))
        assertFalse(PeerReceipt.matches(mine, mine.dropLast(1) + "Z"))
        assertFalse(PeerReceipt.matches(mine, null))
        assertFalse(PeerReceipt.matches(mine, ""))
        assertFalse(PeerReceipt.matches("", ""))
        assertFalse(PeerReceipt.matches(null, mine))
    }

    @Test
    fun `an eavesdropper holding the whole frame still cannot answer for it`() {
        val message = PeerMessage(
            id = "m-real", fromId = "aaaa1111", toId = "bbbb2222",
            body = "виходь", sentAtMs = 1_000L, signature = "cGlkcGlz",
        )
        val sealed = PeerEnvelope.seal(message, channel)!!
        val env = PeerEnvelope.parse(sealed.frame)!!

        val honest = PeerReceipt.of(channel, message.id, env.from, env.to, env.n)!!
        // Усе, що є в спостерігача: кадр цілком, адресування, nonce. Немає ані
        // ключа пари, ані — від v3 — id, який лишався останнім, що їх поєднує.
        val best = PeerReceipt.of(stranger, message.id, env.from, env.to, env.n)!!

        assertFalse(PeerReceipt.matches(honest, best))
        assertFalse(sealed.frame.contains("m-real"))
        assertTrue(PeerWire.isAck(PeerWire.ackLine(honest), honest))
        assertFalse(PeerWire.isAck(PeerWire.ackLine(best), honest))
    }
}
