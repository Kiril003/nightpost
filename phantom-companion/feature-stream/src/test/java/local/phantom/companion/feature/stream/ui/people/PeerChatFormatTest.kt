package local.phantom.companion.feature.stream.ui.people

import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of a conversation. A timestamp under every line, a day nobody can
 * place, a failure hidden because another line followed it — these are what
 * separate a homemade messenger from one people trust.
 */
class PeerChatFormatTest {

    private val day = 86_400_000L
    private val base = 1_800_000_000_000L

    private fun msg(
        id: String,
        from: String,
        at: Long,
        state: PeerMessage.State = PeerMessage.State.DELIVERED,
    ) = PeerMessage(id = id, fromId = from, toId = "x", body = "текст", sentAtMs = at, state = state)

    private fun bubbles(rows: List<PeerChatFormat.Row>) =
        rows.filterIsInstance<PeerChatFormat.Row.Bubble>()

    @Test
    fun `an empty conversation has no rows at all`() {
        assertTrue(PeerChatFormat.rows(emptyList(), "me").isEmpty())
    }

    @Test
    fun `every day opens with its own break`() {
        val rows = PeerChatFormat.rows(
            listOf(msg("a", "me", base), msg("b", "me", base + day)),
            "me",
        )
        assertEquals(2, rows.filterIsInstance<PeerChatFormat.Row.DayBreak>().size)
    }

    @Test
    fun `one day does not repeat its break`() {
        val rows = PeerChatFormat.rows(
            listOf(msg("a", "me", base), msg("b", "me", base + 60_000L)),
            "me",
        )
        assertEquals(1, rows.filterIsInstance<PeerChatFormat.Row.DayBreak>().size)
    }

    @Test
    fun `messages said together are one run`() {
        val rows = bubbles(
            PeerChatFormat.rows(
                listOf(
                    msg("a", "me", base),
                    msg("b", "me", base + 30_000L),
                    msg("c", "me", base + 60_000L),
                ),
                "me",
            ),
        )
        assertTrue("only the first carries the head", rows[0].startsRun)
        assertFalse(rows[1].startsRun)
        assertFalse("only the last carries time and state", rows[0].endsRun)
        assertTrue(rows[2].endsRun)
    }

    @Test
    fun `a long pause breaks the run`() {
        val rows = bubbles(
            PeerChatFormat.rows(
                listOf(msg("a", "me", base), msg("b", "me", base + PeerChatFormat.GROUP_WINDOW_MS + 1)),
                "me",
            ),
        )
        assertTrue(rows[1].startsRun)
        assertTrue(rows[0].endsRun)
    }

    @Test
    fun `the other person always starts their own run`() {
        val rows = bubbles(
            PeerChatFormat.rows(
                listOf(msg("a", "me", base), msg("b", "marta", base + 1_000L)),
                "me",
            ),
        )
        assertTrue(rows[1].startsRun)
        assertFalse(rows[1].mine)
        assertTrue(rows[0].mine)
    }

    @Test
    fun `a failure is never hidden by the line after it`() {
        val rows = bubbles(
            PeerChatFormat.rows(
                listOf(
                    msg("a", "me", base, PeerMessage.State.FAILED),
                    msg("b", "me", base + 10_000L, PeerMessage.State.DELIVERED),
                ),
                "me",
            ),
        )
        assertTrue("the failed line must show its own state", rows[0].endsRun)
    }

    @Test
    fun `messages arriving out of order still read in order`() {
        val rows = bubbles(
            PeerChatFormat.rows(
                listOf(msg("late", "me", base + 5_000L), msg("early", "me", base)),
                "me",
            ),
        )
        assertEquals(listOf("early", "late"), rows.map { it.message.id })
    }

    @Test
    fun `today and yesterday are named, older is a date`() {
        val now = base + day * 5
        assertEquals(PeerChatFormat.DayLabel.TODAY, PeerChatFormat.dayLabel(now, now))
        assertEquals(PeerChatFormat.DayLabel.YESTERDAY, PeerChatFormat.dayLabel(now - day, now))
        assertEquals(PeerChatFormat.DayLabel.OLDER, PeerChatFormat.dayLabel(now - day * 3, now))
    }

    @Test
    fun `a monogram is at most two letters and never empty`() {
        assertEquals("МК", PeerChatFormat.monogram("Марта Ковальчук"))
        assertEquals("МА", PeerChatFormat.monogram("Марта"))
        assertEquals("?", PeerChatFormat.monogram("   "))
        assertEquals("МК", PeerChatFormat.monogram("  Марта   Ковальчук  "))
    }

    @Test
    fun `історія починається з рядка про те, коли людину додано`() {
        val rows = PeerChatFormat.rows(
            messages = listOf(msg("a", "me", base)),
            meId = "me",
            notes = listOf(PeerChatFormat.ChatNote.Added(base - day, 0L)),
        )
        val first = rows.first()
        assertTrue("рядок землі стоїть вище навіть за день", first is PeerChatFormat.Row.Note)
        assertEquals(
            PeerChatFormat.ChatNote.Added(base - day, 0L),
            (first as PeerChatFormat.Row.Note).note,
        )
    }

    @Test
    fun `порожня розмова все одно має свою землю`() {
        val rows = PeerChatFormat.rows(
            messages = emptyList(),
            meId = "me",
            notes = listOf(PeerChatFormat.ChatNote.Added(base, base)),
        )
        assertEquals(1, rows.size)
        assertTrue(rows.single() is PeerChatFormat.Row.Note)
    }

    @Test
    fun `зміна ключа стає рядком у тому місці, де вона сталась`() {
        val rows = PeerChatFormat.rows(
            messages = listOf(msg("a", "me", base), msg("b", "me", base + 60_000L)),
            meId = "me",
            notes = listOf(PeerChatFormat.ChatNote.KeyChanged(base + 30_000L)),
        )
        val at = rows.indexOfFirst { it is PeerChatFormat.Row.Note }
        assertEquals("між двома листами, а не в кінці", 2, at)
    }

    @Test
    fun `рядок про ключ розриває пробіг, а не малюється крізь нього`() {
        val rows = PeerChatFormat.rows(
            messages = listOf(msg("a", "me", base), msg("b", "me", base + 60_000L)),
            meId = "me",
            notes = listOf(PeerChatFormat.ChatNote.KeyChanged(base + 30_000L)),
        )
        val said = bubbles(rows)
        assertTrue("перший мусить показати свій час", said[0].endsRun)
        assertTrue("другий починає новий блок", said[1].startsRun)
    }

    @Test
    fun `подія, пізніша за все сказане, лягає в кінець`() {
        val rows = PeerChatFormat.rows(
            messages = listOf(msg("a", "me", base)),
            meId = "me",
            notes = listOf(PeerChatFormat.ChatNote.KeyChanged(base + day)),
        )
        assertTrue(rows.last() is PeerChatFormat.Row.Note)
    }

    @Test
    fun `цитата бере імʼя автора і його рядок`() {
        val target = msg("m1", "olia", base).copy(body = "Я  на\nпівнічному  вході")
        val quote = PeerChatFormat.quoteOf(
            messages = listOf(target),
            replyToId = "m1",
            meId = "me",
            mineLabel = "Ти",
            nameOf = { "Оля" },
        )
        assertEquals("Оля", quote?.author)
        assertEquals("Я на північному вході", quote?.line)
    }

    @Test
    fun `власний лист у цитаті зветься займенником, а не іменем`() {
        val quote = PeerChatFormat.quoteOf(
            listOf(msg("m1", "me", base)), "m1", "me", "Ти", { "Оля" },
        )
        assertEquals("Ти", quote?.author)
    }

    @Test
    fun `цитата на лист, якого вже нема, не малює порожньої рамки`() {
        assertEquals(
            null,
            PeerChatFormat.quoteOf(listOf(msg("m1", "olia", base)), "згас", "me", "Ти", { "Оля" }),
        )
        assertEquals(
            null,
            PeerChatFormat.quoteOf(listOf(msg("m1", "olia", base)), "", "me", "Ти", { "Оля" }),
        )
    }
    @Test
    fun `розмова з непрочитаним відкривається на межі, а не в самому низу`() {
        val rows = PeerChatFormat.rows(
            messages = listOf(
                msg("a", "me", base),
                msg("b", "olia", base + 60_000L),
                msg("c", "olia", base + 120_000L),
            ),
            meId = "me",
            readUpToMs = base + 30_000L,
        )
        val mark = rows.indexOfFirst { it is PeerChatFormat.Row.NewMark }
        assertTrue("межа «нове» має бути в списку", mark >= 0)
        assertEquals(mark, PeerChatFormat.landing(rows))
        assertTrue("посадка не в кінці", PeerChatFormat.landing(rows) < rows.lastIndex)
    }

    @Test
    fun `прочитана розмова відкривається на останньому рядку`() {
        val rows = PeerChatFormat.rows(
            listOf(msg("a", "me", base), msg("b", "olia", base + 60_000L)),
            "me",
        )
        assertEquals(rows.lastIndex, PeerChatFormat.landing(rows))
        assertEquals(0, PeerChatFormat.landing(emptyList()))
    }

    @Test
    fun `пігулка рахує лише чужі рядки під краєм екрана`() {
        // Власний лист лежить МІЖ чужими під краєм: інакше правило «лише чужі»
        // не перевіряється жодним рядком цього списку.
        val rows = PeerChatFormat.rows(
            listOf(
                msg("a", "olia", base),
                msg("b", "olia", base + 10 * 60_000L),
                msg("c", "me", base + 20 * 60_000L),
                msg("d", "olia", base + 30 * 60_000L),
            ),
            "me",
        )
        val edge = rows.indexOfFirst {
            it is PeerChatFormat.Row.Bubble && it.message.id == "a"
        }
        assertEquals(2, PeerChatFormat.newBelow(rows, edge))
        assertEquals(0, PeerChatFormat.newBelow(rows, rows.lastIndex))
    }

    @Test
    fun `копіюється сказане, а не конверт групи`() {
        val group = PeerGroup.Group(id = "g1", name = "Ланка", memberIds = listOf("me", "olia"))
        val wire = PeerGroup.encode(group, "Виходимо о 6")
        assertTrue("тіло має бути конвертом", PeerGroup.isGroup(wire))
        assertEquals(
            "Виходимо о 6",
            PeerChatFormat.copyText(msg("a", "me", base).copy(body = wire)),
        )
    }

    @Test
    fun `місце копіюється парою координат, а не своїм дротяним виглядом`() {
        val body = PeerPayload.encodePlace(lat = 50.4502, lon = 30.52341, accuracyM = 25)
        assertEquals(
            "50.45020, 30.52341",
            PeerChatFormat.copyText(msg("a", "olia", base).copy(body = body)),
        )
    }

    @Test
    fun `only a letter that really went without a meeting key says so`() {
        // Рядок про НАШ лист, а не про чужу збірку. Мовчання для UNKNOWN —
        // це кожен рядок, записаний до появи ключів зустрічі: вигадувати їм
        // минуле не можна.
        val plain = PeerMessage(
            id = "m-1", fromId = "a", toId = "b", body = "текст", sentAtMs = 1L,
        )
        assertFalse(PeerChatFormat.withoutMeetingKey(plain))
        assertFalse(
            PeerChatFormat.withoutMeetingKey(
                plain.copy(sealing = PeerMessage.Sealing.MEETING_KEY),
            ),
        )
        assertTrue(
            PeerChatFormat.withoutMeetingKey(
                plain.copy(sealing = PeerMessage.Sealing.PAIR_KEY),
            ),
        )
    }

}
