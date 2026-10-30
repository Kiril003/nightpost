package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чим можна купити довічний ключ пари, а чим не можна.
 *
 * Слухач один: MSG і HLO приходять на той самий сокет того самого телефона.
 * Стара збірка на HLO ВІДПОВІДАЄ рядком помилки — тиша це та єдина відповідь,
 * якої вона дати не може. Тож правило: знижку купує лише відповідь, і ніколи
 * мовчання. Мовчазний співрозмовник або недосяжний — тоді б і лист не поїхав,
 * — або це хтось ріже саме домовляння ключів.
 */
class PeerMeetTest {

    private val now = 1_700_000_000_000L

    private fun stranger() = PeerMeet.Known()

    private fun veteran() = PeerMeet.Known(metAtMs = now - 60_000L)

    @Test
    fun `a peer that answered the handshake gets a meeting key`() {
        val decision = PeerMeet.after(stranger(), PeerMeet.Answer.MET, now)
        assertEquals(PeerMeet.Act.MEET, decision.act)
        assertEquals(PeerMeet.Why.MET, decision.why)
    }

    @Test
    fun `a peer that never met and does not know the verb may use the pair key`() {
        val decision = PeerMeet.after(stranger(), PeerMeet.Answer.REFUSED_UNKNOWN_VERB, now)
        assertEquals(PeerMeet.Act.PAIR_KEY, decision.act)
        assertEquals(PeerMeet.Why.OLD_BUILD, decision.why)
    }

    /** Ядро. Тиша не купує слабший ключ НІКОМУ — ні тепер, ні на сотій спробі. */
    @Test
    fun `silence never buys the pair key, however long it lasts`() {
        var known = stranger()
        repeat(50) {
            val decision = PeerMeet.after(known, PeerMeet.Answer.SILENT, now)
            assertEquals(PeerMeet.Act.HOLD, decision.act)
            assertEquals(PeerMeet.Why.SILENT, decision.why)
            known = PeerMeet.noted(known, PeerMeet.Answer.SILENT, now)
        }
    }

    /**
     * Пара, яка вже домовлялась про ключ зустрічі, не опускається назад ніколи:
     * той, хто ріже обмін, може лише затримати лист, а не зробити його слабшим.
     */
    @Test
    fun `a peer that has met before is never downgraded, whatever the answer`() {
        PeerMeet.Answer.entries.filter { it != PeerMeet.Answer.MET }.forEach { answer ->
            val decision = PeerMeet.after(veteran(), answer, now)
            assertEquals("$answer must not weaken a met pair", PeerMeet.Act.HOLD, decision.act)
        }
    }

    /** Збірка не їде назад у часі: «не знаю такого слова» від того, хто вже вмів, — тривога. */
    @Test
    fun `a peer that has met before and now claims not to know the verb is not believed`() {
        val decision = PeerMeet.after(veteran(), PeerMeet.Answer.REFUSED_UNKNOWN_VERB, now)
        assertEquals(PeerMeet.Act.HOLD, decision.act)
        assertEquals(PeerMeet.Why.REFUSED_AFTER_MET, decision.why)
    }

    /** Відмова з іншої причини — не доказ старої збірки, тож і не привід слабшати. */
    @Test
    fun `a refusal that is not the old build's words buys no downgrade`() {
        val decision = PeerMeet.after(stranger(), PeerMeet.Answer.REFUSED_OTHER, now)
        assertEquals(PeerMeet.Act.HOLD, decision.act)
        assertEquals(PeerMeet.Why.SILENT, decision.why)
    }

    @Test
    fun `a build that cannot meet is not asked again every tick`() {
        val known = PeerMeet.noted(stranger(), PeerMeet.Answer.REFUSED_UNKNOWN_VERB, now)
        assertFalse(PeerMeet.shouldOffer(known, now))
        assertFalse(PeerMeet.shouldOffer(known, now + PeerMeet.RETRY_REFUSAL_MS - 1))
        assertTrue(PeerMeet.shouldOffer(known, now + PeerMeet.RETRY_REFUSAL_MS))
    }

    /**
     * Стримування — не вирок. Старий код памʼятав «стара збірка» до перезапуску
     * застосунку, і людина, яка щойно додала тебе у відповідь, лишалась на
     * довічному ключі назавжди.
     */
    @Test
    fun `the suppression never becomes a verdict`() {
        val known = PeerMeet.noted(stranger(), PeerMeet.Answer.REFUSED_UNKNOWN_VERB, now)
        val later = now + PeerMeet.RETRY_REFUSAL_MS
        assertTrue(PeerMeet.shouldOffer(known, later))
        assertEquals(PeerMeet.Act.MEET, PeerMeet.after(known, PeerMeet.Answer.MET, later).act)
    }

    /** Доведена зустріч гасить підозру одразу, не чекаючи вигасання. */
    @Test
    fun `meeting once clears the suppression and guards the pair from then on`() {
        var known = PeerMeet.noted(stranger(), PeerMeet.Answer.REFUSED_UNKNOWN_VERB, now)
        known = PeerMeet.noted(known, PeerMeet.Answer.MET, now)
        assertTrue(known.metAtMs > 0L)
        assertTrue(PeerMeet.shouldOffer(known, now))
        assertEquals(
            PeerMeet.Why.REFUSED_AFTER_MET,
            PeerMeet.after(known, PeerMeet.Answer.REFUSED_UNKNOWN_VERB, now).why,
        )
    }

    /** Стримування гасить тільки час або засвідчена зустріч — більше ніщо. */
    @Test
    fun `only time or a proved meeting lifts the suppression`() {
        val refused = PeerMeet.noted(stranger(), PeerMeet.Answer.REFUSED_UNKNOWN_VERB, now)
        PeerMeet.Answer.entries.filter { it != PeerMeet.Answer.MET }.forEach { answer ->
            assertFalse(
                "$answer must not lift the suppression",
                PeerMeet.shouldOffer(PeerMeet.noted(refused, answer, now), now),
            )
        }
        assertTrue(PeerMeet.shouldOffer(PeerMeet.noted(refused, PeerMeet.Answer.MET, now), now))
    }

    private fun row(id: String, pub: String, dh: String, met: Long) = PeerIdentity(
        id = id,
        name = "Марта",
        kind = PeerIdentity.Kind.PERSON,
        pubKey = pub,
        dhPubKey = dh,
        metAtMs = met,
    )

    /** Підмінений ключ не успадковує зустріч, якої під ним не було. */
    @Test
    fun `a substituted key forgets the meeting it never proved`() {
        val roster = listOf(row("marta", "pub-a", "dh-a", now))
        val after = PeerRoster.upsert(roster, row("marta", "pub-b", "dh-a", 0L), now)
        assertEquals(0L, after.single().metAtMs)
        assertFalse(PeerMeet.Known(metAtMs = after.single().metAtMs).everMet)
    }

    /** Те саме, коли підмінили лише ключ шифрування. */
    @Test
    fun `a substituted encryption key also forgets the meeting`() {
        val roster = listOf(row("marta", "pub-a", "dh-a", now))
        val after = PeerRoster.upsert(roster, row("marta", "pub-a", "dh-b", 0L), now)
        assertEquals(0L, after.single().metAtMs)
    }

    /** Той самий ключ, доданий заново, зустріч не втрачає — інакше кожне повторне сканування знімало б захист. */
    @Test
    fun `re-adding the same key keeps the meeting`() {
        val roster = listOf(row("marta", "pub-a", "dh-a", now))
        val after = PeerRoster.upsert(roster, row("marta", "pub-a", "dh-a", 0L), now)
        assertEquals(now, after.single().metAtMs)
        assertTrue(PeerMeet.Known(metAtMs = after.single().metAtMs).everMet)
    }

    @Test
    fun `the meeting is written down where a restart can read it back`() {
        val roster = PeerRoster.markMet(listOf(row("marta", "pub-a", "dh-a", 0L)), "marta", now)
        assertEquals(now, roster.single().metAtMs)
    }

    /** Мовчання поруч — сигнал для екрана, а не привід слабшати. */
    @Test
    fun `silence only becomes something to show after it repeats`() {
        var known = stranger()
        repeat(PeerMeet.SILENCE_TO_SHOW - 1) {
            known = PeerMeet.noted(known, PeerMeet.Answer.SILENT, now)
            assertFalse(PeerMeet.worthShowing(known))
        }
        known = PeerMeet.noted(known, PeerMeet.Answer.SILENT, now)
        assertTrue(PeerMeet.worthShowing(known))
    }
}
