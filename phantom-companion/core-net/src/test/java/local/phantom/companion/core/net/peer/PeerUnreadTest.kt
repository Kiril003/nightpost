package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Дві плашки на одному екрані рахували той самий факт по-різному: куля —
 * тільки розмови на двох, острів — ще й групи. Це не змушує подивитись двічі,
 * а вчить, що жодне з тих чисел нічого не означає.
 *
 * 06.09 число звузилось до розмов на двох НАВМИСНО. Рідні екрани розмови пішли
 * з застосунку, листи живуть у веб-месенджері, а той груп не знає: міст
 * фільтрує ростер до `Kind.PERSON` і кожну розмову позначає «dm». Групу в
 * лічильнику довелось би відчиняти екраном, якого немає.
 *
 * ХВИЛІ, ЯКА НАВЧИТЬ МІСТ ГРУП: поверни `PeerGroup.known(...)` у [PeerUnread.total]
 * і переверни тут `груповий кадр не рахується` назад у «рахується». Доки цього
 * не сталось, груповий лист приходить мовчки, і цей тест каже про це вголос.
 */
class PeerUnreadTest {

    private val group = PeerGroup.Group("abc123abc123", "Північ", listOf("me", "marta"))

    private fun person(id: String, readUpTo: Long = 0L) = PeerIdentity(
        id = id,
        name = id,
        kind = PeerIdentity.Kind.PERSON,
        pubKey = "KEY-$id",
        readUpToMs = readUpTo,
    )

    private fun line(from: String, at: Long, body: String = "тут") = PeerMessage(
        id = "$from-$at",
        fromId = from,
        toId = "me",
        body = body,
        sentAtMs = at,
    )

    @Test
    fun `сказане людиною рахується`() {
        val roster = listOf(person("marta"))
        val outbox = listOf(line("marta", 10L), line("marta", 20L, "виходимо"))
        assertEquals(2, PeerUnread.total(roster, outbox))
    }

    @Test
    fun `груповий кадр не рахується — його нема чим відчинити`() {
        val roster = listOf(person("marta"))
        val outbox = listOf(
            line("marta", 10L),
            line("marta", 20L, PeerGroup.encode(group, "виходимо")),
        )
        assertEquals(
            "груповий кадр приїхав у число, під яким немає екрана: веб-міст груп " +
                "не відчиняє, тож плашка повела б у порожнечу",
            1,
            PeerUnread.total(roster, outbox),
        )
    }

    @Test
    fun `прочитане до позначки виходить із числа`() {
        val roster = listOf(person("marta", readUpTo = 10L))
        val outbox = listOf(line("marta", 10L), line("marta", 20L))
        assertEquals(1, PeerUnread.total(roster, outbox))
    }

    @Test
    fun `the paired PC never lights the People badge`() {
        val roster = listOf(person("marta"), person("laptop").copy(kind = PeerIdentity.Kind.DEVICE))
        val outbox = listOf(line("laptop", 10L))
        assertEquals(0, PeerUnread.total(roster, outbox))
    }

    @Test
    fun `one's own words are never unread`() {
        val roster = listOf(person("marta"))
        val outbox = listOf(
            PeerMessage("x", fromId = "me", toId = "marta", body = "тут", sentAtMs = 10L),
            PeerMessage(
                "y",
                fromId = "me",
                toId = "marta",
                body = PeerGroup.encode(group, "виходимо"),
                sentAtMs = 20L,
            ),
        )
        assertEquals(0, PeerUnread.total(roster, outbox))
    }

    @Test
    fun `no badge is null, never a zero`() {
        assertNull(PeerUnread.badge(0))
        assertNull(PeerUnread.badge(-1))
        assertEquals("7", PeerUnread.badge(7))
    }

    @Test
    fun `past the cap the rail does not widen mid-flight`() {
        assertEquals("99", PeerUnread.badge(PeerUnread.CAP))
        assertEquals("99+", PeerUnread.badge(PeerUnread.CAP + 1))
    }

    /**
     * «Позначити непрочитаним» у месенджері ставило крапку в рядку, а плашка
     * вкладки «Чати» лишалась порожньою (A52, 02.10): двох правд про одне.
     */
    @Test
    fun `позначене непрочитаним рахується в плашці`() {
        val roster = listOf(person("marta"), person("ivan"))
        val outbox = listOf(line("marta", 10L))
        assertEquals(1, PeerUnread.withMarks(roster, outbox, emptySet()))
        // Позначена розмова без справжніх листів — плюс одна.
        assertEquals(2, PeerUnread.withMarks(roster, outbox, setOf("ivan")))
        // Позначка «Збереженого» чи групи теж розмова, яку людина хоче відкрити.
        assertEquals(3, PeerUnread.withMarks(roster, outbox, setOf("ivan", "chat_saved_notes")))
    }

    @Test
    fun `позначена розмова з листами не рахується двічі`() {
        val roster = listOf(person("marta"))
        val outbox = listOf(line("marta", 10L), line("marta", 20L))
        assertEquals(2, PeerUnread.withMarks(roster, outbox, setOf("marta")))
    }
}
