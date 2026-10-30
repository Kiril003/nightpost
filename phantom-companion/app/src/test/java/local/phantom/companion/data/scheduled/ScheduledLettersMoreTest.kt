package local.phantom.companion.data.scheduled

import kotlinx.coroutines.runBlocking
import local.phantom.companion.service.ScheduledLetterOnline
import local.phantom.companion.ui.messenger.WebMessengerScheduled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** msg-x 2 (28.10): перенести, змінити, повтор і «коли буде в мережі» — на апараті, а не лише на екрані. */
class ScheduledLettersMoreTest {

    private val kyiv = ZoneId.of("Europe/Kyiv")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(kyiv).toInstant().toEpochMilli()

    private var disk: String? = null
    private var clock = 0L
    private fun book() = ScheduledLetters(read = { disk }, write = { disk = it }, zone = { kyiv }, clock = { clock })

    @Test
    fun `reschedule moves the alarm, clears an old refusal and refuses the past`() = runBlocking {
        val b = book()
        val l = b.add("peer_1", "раз", 5_000, false, 1_000)!!
        b.fireDue(6_000) { "ключ змінився" }
        assertEquals("ключ змінився", b.get(l.id)!!.failure)
        val moved = b.update(l.id, nowMs = 7_000, dueAtMs = 20_000) as ScheduledLetters.Edit.Done
        assertEquals(20_000, moved.letter.dueAtMs)
        assertEquals("", moved.letter.failure)
        assertEquals(l.id, b.next()!!.id)
        assertEquals(ScheduledLetters.Edit.Past, b.update(l.id, nowMs = 7_000, dueAtMs = 6_500))
        assertEquals(ScheduledLetters.Edit.Empty, b.update(l.id, nowMs = 7_000, text = "  "))
        assertEquals(ScheduledLetters.Edit.Gone, b.update("nope", nowMs = 7_000, text = "x"))
        val edited = b.update(l.id, nowMs = 7_000, text = " два ") as ScheduledLetters.Edit.Done
        assertEquals("два", edited.letter.text)
        assertEquals(20_000, edited.letter.dueAtMs)
    }

    @Test
    fun `a repeating letter stays with the next time at the same local hour`() = runBlocking {
        val b = book()
        val thu = ms(2026, 10, 29, 9, 0)
        clock = thu - 1
        val l = b.add("peer_1", "доброго ранку", thu, false, ms(2026, 10, 28, 18, 40), repeat = "weekly")!!
        clock = thu + 1
        assertEquals(1, b.fireDue(thu + 1) { null })
        assertEquals(ms(2026, 11, 5, 9, 0), b.get(l.id)!!.dueAtMs)
        val fri = ms(2026, 10, 30, 9, 0)
        assertEquals(ms(2026, 11, 2, 9, 0), b.nextDue(fri, "weekdays", fri))
        // Перехід на зимовий час 25.10: та сама 09:00, а не 08:00.
        val sat = ms(2026, 10, 24, 9, 0)
        assertEquals(ms(2026, 10, 25, 9, 0), b.nextDue(sat, "daily", sat))
        assertNull(b.add("peer_1", "x", thu, false, 0, repeat = "hourly")!!.repeat.ifEmpty { null })
    }

    @Test
    fun `when-online letters skip the alarm and go when that person speaks`() = runBlocking {
        val b = book()
        val l = b.add("peer_1", "фото надішлю", dueAtMs = 0, quiet = false, nowMs = 1_000, whenOnline = true)!!
        assertNull(b.next())
        assertTrue(b.due(Long.MAX_VALUE).isEmpty())
        assertTrue(b.waitsForOnline("peer_1"))
        assertFalse(b.waitsForOnline("peer_2"))
        val sent = mutableListOf<String>()
        assertEquals(0, b.fireOnline("peer_2") { sent += it.id; null })
        assertEquals(1, b.fireOnline("peer_1") { sent += it.id; null })
        assertEquals(listOf(l.id), sent)
        assertTrue(b.all().isEmpty())
    }

    @Test
    fun `presence diff names only people whose last-seen moved, never on the first snapshot`() {
        assertEquals(emptyList<String>(), ScheduledLetterOnline.cameBack(null, mapOf("a" to 5L)))
        assertEquals(listOf("a"), ScheduledLetterOnline.cameBack(mapOf("a" to 5L, "b" to 3L), mapOf("a" to 9L, "b" to 3L)))
        assertEquals(emptyList<String>(), ScheduledLetterOnline.cameBack(emptyMap(), mapOf("new" to 9L)))
    }

    @Test
    fun `the door PATCH re-arms and maps refusals`() {
        val b = book()
        var rearms = 0
        val door = WebMessengerScheduled(b, { true }, { null }, { rearms++ }, { 1_000 })
        val one = door.add("peer_1", "раз", 5_000, false, repeat = "daily") as WebMessengerScheduled.Out.One
        assertEquals("daily", one.letter.repeat)
        val r0 = rearms
        val moved = door.edit(one.letter.id, null, 9_000, null, null) as WebMessengerScheduled.Out.One
        assertEquals(9_000, moved.letter.dueAtMs)
        assertEquals(r0 + 1, rearms)
        assertEquals(WebMessengerScheduled.Out.Refused(422, "past"), door.edit(one.letter.id, null, 500, null, null))
        assertEquals(WebMessengerScheduled.Out.Refused(404, "no_letter"), door.edit("x", "a", null, null, null))
        val online = door.edit(one.letter.id, null, null, null, true) as WebMessengerScheduled.Out.One
        assertTrue(online.letter.whenOnline)
        assertEquals("", online.letter.repeat)
    }
}
