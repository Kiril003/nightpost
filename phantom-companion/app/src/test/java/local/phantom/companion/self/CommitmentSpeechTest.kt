package local.phantom.companion.self

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitmentSpeechTest {

    private val zone: ZoneId = ZoneId.of("Europe/Kyiv")
    // Sunday 2026-09-27 10:00 Europe/Kyiv
    private val nowMs: Long = LocalDateTime.of(2026, 9, 27, 10, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `parses Ukrainian commitment with dative name and tomorrow`() {
        val parsed = CommitmentSpeech.parse("Надішлю Олі фото завтра", nowMs, zone)
        assertNotNull(parsed)
        assertEquals("Оля", parsed!!.person)
        assertEquals("Олі", parsed.personRaw)
        assertEquals("фото", parsed.what)
        val due = Instant.ofEpochMilli(parsed.dueMs!!).atZone(zone)
        assertEquals(LocalDateTime.of(2026, 9, 28, 9, 0).toLocalDate(), due.toLocalDate())
    }

    @Test
    fun `parses English commitment with tomorrow`() {
        val parsed = CommitmentSpeech.parse("I'll send Olia the photo tomorrow", nowMs, zone)
        assertNotNull(parsed)
        assertEquals("Olia", parsed!!.person)
        assertEquals("photo", parsed.what)
        val due = Instant.ofEpochMilli(parsed.dueMs!!).atZone(zone)
        assertEquals(LocalDateTime.of(2026, 9, 28, 9, 0).toLocalDate(), due.toLocalDate())
    }

    @Test
    fun `resolves Ukrainian name cases to nominative`() {
        for (variant in listOf("Олі", "Ользі", "Олю", "Оля")) {
            assertEquals("for $variant", "Оля", CommitmentSpeech.nominativeName(variant))
        }
        assertEquals("Сашко", CommitmentSpeech.nominativeName("Сашкові"))
        assertEquals("Петро", CommitmentSpeech.nominativeName("Петрові"))
        assertEquals("Андрій", CommitmentSpeech.nominativeName("Андрієві"))
        assertEquals("Марта", CommitmentSpeech.nominativeName("Марті"))
    }

    @Test
    fun `declension-tolerant prefix match of at least 3 letters in both scripts`() {
        assertTrue(CommitmentSpeech.matchesFirstName("Олі", "Оля Коваль"))
        assertTrue(CommitmentSpeech.matchesFirstName("Ользі", "Оля Мельник"))
        assertTrue(CommitmentSpeech.matchesFirstName("Олю", "Оля"))
        assertTrue(CommitmentSpeech.matchesFirstName("Сашкові", "Сашко Петренко"))
        assertTrue(CommitmentSpeech.matchesFirstName("Олені", "Олена Бондар"))
        assertTrue(CommitmentSpeech.matchesFirstName("Olia", "Olia Koval"))
        assertTrue(CommitmentSpeech.matchesFirstName("Petro", "Petro Ivanenko"))
        assertFalse("Олі must not match Олег", CommitmentSpeech.matchesFirstName("Олі", "Олег"))
        assertFalse("Олені must not match Олег", CommitmentSpeech.matchesFirstName("Олені", "Олег"))
        assertTrue(CommitmentSpeech.matchesFirstName("Марії", "Марія Коваль"))
        assertFalse("Марії must not match Марта", CommitmentSpeech.matchesFirstName("Марії", "Марта"))
        assertFalse("Марії must not match Марина", CommitmentSpeech.matchesFirstName("Марії", "Марина"))
        assertFalse("Марії must not match Марк", CommitmentSpeech.matchesFirstName("Марії", "Марк"))
        assertFalse("Марії must not match Марат", CommitmentSpeech.matchesFirstName("Марії", "Марат"))
        assertTrue(CommitmentSpeech.matchesFirstName("Вікторії", "Вікторія"))
        assertFalse("Вікторії must not match Віктор", CommitmentSpeech.matchesFirstName("Вікторії", "Віктор"))
        assertTrue(CommitmentSpeech.matchesFirstName("Олі", "Ольга"))
        assertTrue(CommitmentSpeech.matchesFirstName("Ользі", "Ольга"))
        assertTrue(CommitmentSpeech.matchesFirstName("Олю", "Ольга"))
    }

    @Test
    fun `parses relative days, weekdays and clock times against injected clock and zone`() {
        val today = CommitmentSpeech.parse("Передам Ользі ключі сьогодні о 18:30", nowMs, zone)!!
        assertEquals("Оля", today.person)
        assertEquals("ключі", today.what)
        val todayDue = Instant.ofEpochMilli(today.dueMs!!).atZone(zone)
        assertEquals(LocalDateTime.of(2026, 9, 27, 18, 30), todayDue.toLocalDateTime())

        val afterTomorrow = CommitmentSpeech.parse("Скину Сашкові звіт післязавтра", nowMs, zone)!!
        assertEquals("Сашко", afterTomorrow.person)
        assertEquals("звіт", afterTomorrow.what)
        val afterDue = Instant.ofEpochMilli(afterTomorrow.dueMs!!).atZone(zone)
        assertEquals(LocalDateTime.of(2026, 9, 29, 9, 0).toLocalDate(), afterDue.toLocalDate())

        val tuesday = CommitmentSpeech.parse("Поверну Марті книгу у вівторок о 14:15", nowMs, zone)!!
        assertEquals("Марта", tuesday.person)
        assertEquals("книгу", tuesday.what)
        val tueDue = Instant.ofEpochMilli(tuesday.dueMs!!).atZone(zone)
        assertEquals(DayOfWeek.TUESDAY, tueDue.dayOfWeek)
        assertEquals(LocalTime.of(14, 15), tueDue.toLocalTime())

        val enWeekday = CommitmentSpeech.parse("I will send the report to Olia on Friday at 16:45", nowMs, zone)!!
        assertEquals("Olia", enWeekday.person)
        assertEquals("report", enWeekday.what)
        val friDue = Instant.ofEpochMilli(enWeekday.dueMs!!).atZone(zone)
        assertEquals(DayOfWeek.FRIDAY, friDue.dayOfWeek)
        assertEquals(LocalTime.of(16, 45), friDue.toLocalTime())
    }

    @Test
    fun `today without explicit clock time schedules in the future after 9am`() {
        val ukToday = CommitmentSpeech.parse("Надішлю Олі фото сьогодні", nowMs, zone)
        assertNotNull(ukToday)
        assertTrue("today dueMs must be in the future", ukToday!!.dueMs!! > nowMs)
        val ukDue = Instant.ofEpochMilli(ukToday.dueMs!!).atZone(zone)
        assertEquals(LocalDateTime.of(2026, 9, 27, 11, 0), ukDue.toLocalDateTime())

        val enToday = CommitmentSpeech.parse("I'll send Olia the photo today", nowMs, zone)
        assertNotNull(enToday)
        assertTrue("today dueMs must be in the future", enToday!!.dueMs!! > nowMs)
    }

    @Test
    fun `SelfSpeech routes commitments and promise queries without stealing Remember or Forget`() {
        assertTrue(SelfSpeech.read("Запамʼятай, що я люблю каву") is SelfSpeech.Heard.Remember)
        assertTrue(SelfSpeech.read("Запамʼятай, що я надішлю Олі фото завтра") is SelfSpeech.Heard.Remember)
        assertTrue(SelfSpeech.read("Remember that I'll send Olia the photo") is SelfSpeech.Heard.Remember)
        assertNull(CommitmentSpeech.parse("Запамʼятай, що я надішлю Олі фото завтра", nowMs, zone))
        assertNull(CommitmentSpeech.parse("Remember that I'll send Olia the photo", nowMs, zone))
        assertTrue(SelfSpeech.read("Забудь про каву") is SelfSpeech.Heard.Forget)
        assertEquals(SelfSpeech.Heard.WhatDidIPromise, SelfSpeech.read("Що я обіцяв?"))
        assertEquals(SelfSpeech.Heard.WhatDidIPromise, SelfSpeech.read("What did I promise?"))
        val heard = SelfSpeech.read("Надішлю Олі фото завтра")
        assertTrue("expected Heard.Commitment but got $heard", heard is SelfSpeech.Heard.Commitment)
        assertNull(CommitmentSpeech.parse("Яка завтра погода?", nowMs, zone))
    }

    @Test
    fun `rejects non-promise verbs, capitalized places or tech terms, reminders and money phrases`() {
        val negatives = listOf(
            "Показати укриття в Києві",
            "Покажу укриття в Києві",
            "Написати тест на Kotlin",
            "Напишу тест на Kotlin",
            "I'll write a script for Linux",
            "Нагадай купити молоко",
            "Перекажи Олені 5 USDC",
            "Скинь Олі 200 грн",
            "Скину 200 грн на Monobank",
            "Розділи рахунок з Олею",
        )
        for (phrase in negatives) {
            assertNull("must not parse as commitment: $phrase", CommitmentSpeech.parse(phrase, nowMs, zone))
            val read = SelfSpeech.read(phrase, nowMs, zone)
            assertFalse("SelfSpeech must not classify as Commitment: $phrase -> $read", read is SelfSpeech.Heard.Commitment)
        }

        // Custom name not in static dictionary is rejected unless present in the owner's peer roster
        assertNull(CommitmentSpeech.parse("Надішлю Світозару фото завтра", nowMs, zone))
        val fromRoster = CommitmentSpeech.parse(
            "Надішлю Світозару фото завтра",
            nowMs,
            zone,
            rosterNames = listOf("Світозар Коваль"),
        )
        assertNotNull(fromRoster)
        assertEquals("фото", fromRoster!!.what)
    }

    @Test
    fun `parses bare hour clock and month date in commitments`() {
        val octNow = LocalDateTime.of(2026, 10, 15, 10, 0).atZone(zone).toInstant().toEpochMilli()
        val expNine = LocalDateTime.of(2026, 10, 16, 9, 0).atZone(zone).toInstant().toEpochMilli()
        val expOct20 = LocalDateTime.of(2026, 10, 20, 9, 0).atZone(zone).toInstant().toEpochMilli()

        val ukNine = CommitmentSpeech.parse("Надішлю Олі фото о 9", octNow, zone)
        assertNotNull(ukNine)
        assertEquals("Оля", ukNine!!.person)
        assertEquals("фото", ukNine.what)
        assertEquals(expNine, ukNine.dueMs)

        val enNine = CommitmentSpeech.parse("I will send Olia the photo at 9", octNow, zone)
        assertNotNull(enNine)
        assertEquals("Olia", enNine!!.person)
        assertEquals("photo", enNine.what)
        assertEquals(expNine, enNine.dueMs)

        val ukOct20 = CommitmentSpeech.parse("Надішлю Олі фото 20 жовтня", octNow, zone)
        assertNotNull(ukOct20)
        assertEquals("Оля", ukOct20!!.person)
        assertEquals("фото", ukOct20.what)
        assertEquals(expOct20, ukOct20.dueMs)
    }

    @Test
    fun `an offer under an old message counts from when it was sent and expires`() {
        val sent = LocalDateTime.of(2026, 9, 27, 12, 0).atZone(zone).toInstant().toEpochMilli()
        val nextMorning = LocalDateTime.of(2026, 9, 28, 10, 0).atZone(zone).toInstant().toEpochMilli()
        val offer = CommitmentSpeech.offerFor("Скину звіт завтра о 18:00", "Оля Коваль", sent, nextMorning, zone)
        val due = Instant.ofEpochMilli(offer!!.dueMs!!).atZone(zone)
        assertEquals(LocalDateTime.of(2026, 9, 28, 18, 0), due.toLocalDateTime())

        val dayAfter = LocalDateTime.of(2026, 9, 29, 10, 0).atZone(zone).toInstant().toEpochMilli()
        assertNull(CommitmentSpeech.offerFor("Скину звіт завтра о 18:00", "Оля Коваль", sent, dayAfter, zone))
    }

    @Test
    fun `parses commitment directed at peer when peer name is not in text`() {
        val parsed = CommitmentSpeech.parseForPeer("Скину звіт завтра о 18:00", "Оля Коваль", nowMs, zone)
        assertNotNull(parsed)
        assertEquals("Оля Коваль", parsed!!.person)
        assertEquals("звіт", parsed.what)
        val due = Instant.ofEpochMilli(parsed.dueMs!!).atZone(zone)
        assertEquals(LocalDateTime.of(2026, 9, 28, 18, 0), due.toLocalDateTime())
    }
}
