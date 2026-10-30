package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Позначка на дроті: складається, читається назад і згортається однаково на
 * обох апаратах.
 *
 * Згортання перевіряється саме на ЖУРНАЛІ, а не на «стані»: свого стану в
 * [PeerMark] немає й бути не має — той самий журнал мусить давати той самий
 * результат хоч після перезапуску, хоч після приїзду кадрів навпіл.
 */
class PeerMarkTest {

    private val target = "6e3c1a70-0f51-4a3b-9d2e-71b0c4e5a9f2"

    private fun letter(from: String, at: Long, body: String, id: String = "m$at"): PeerMessage =
        PeerMessage(id = id, fromId = from, toId = "me", body = body, sentAtMs = at)

    private fun cast(
        from: String,
        at: Long,
        what: PeerMark.What,
        value: String,
        on: Boolean,
        targetId: String = target,
    ): PeerMessage = letter(
        from,
        at,
        PeerMark.encode(PeerMark.Mark(what, targetId, on, value))!!,
        id = "$from-$at",
    )

    @Test
    fun `кадр читається назад слово в слово`() {
        PeerMark.What.entries.forEach { what ->
            listOf("❤️", "👍🏽", "🇺🇦", "opt_12", "👩‍👩‍👧") .forEach { value ->
                listOf(true, false).forEach { on ->
                    val mark = PeerMark.Mark(what, target, on, value)
                    val wire = PeerMark.encode(mark)
                    assertNotNull("кадр $what/$value не склався", wire)
                    assertEquals("кадр $what/$value прочитався не тим", mark, PeerMark.decode(wire!!))
                }
            }
        }
    }

    /**
     * Роздільник кадру всередині значення законний рівно так само, як перевід
     * рядка в SDP. Саме через це значення возиться base64 — і саме це тут
     * доводиться, а не припускається.
     */
    @Test
    fun `роздільник усередині значення не ламає кадру`() {
        val mark = PeerMark.Mark(PeerMark.What.VOTE, target, on = true, value = "a;b;c")
        assertEquals(mark, PeerMark.decode(PeerMark.encode(mark)!!))
    }

    @Test
    fun `порожнього й завеликого значення не буває`() {
        assertNull(PeerMark.encode(PeerMark.Mark(PeerMark.What.REACTION, target, true, "")))
        val long = "x".repeat(PeerMark.MAX_VALUE_BYTES + 1)
        assertNull(PeerMark.encode(PeerMark.Mark(PeerMark.What.REACTION, target, true, long)))
    }

    @Test
    fun `позначка без рядка не їде`() {
        listOf("", "не той;рядок", "a".repeat(65)).forEach {
            assertNull(
                "позначка поїхала з непридатним id рядка: «$it»",
                PeerMark.encode(PeerMark.Mark(PeerMark.What.REACTION, it, true, "❤️")),
            )
        }
    }

    @Test
    fun `покалічений кадр не стає позначкою`() {
        listOf(
            "PH-REA:",
            "PH-REA:$target",
            "PH-REA:$target;+",
            "PH-REA:$target;?;4p2k77iP",
            "PH-REA:$target;+;",
            "PH-REA:$target;+;не base64!!!",
            "виходь на звʼязок",
        ).forEach { assertNull("покалічений кадр розібрався: «$it»", PeerMark.decode(it)) }
    }

    /** Двоє поставили один смайл, третій — інший, четвертий передумав. */
    @Test
    fun `згортання читає останнє слово кожного автора`() {
        val log = listOf(
            cast("a", 10, PeerMark.What.REACTION, "❤️", on = true),
            cast("b", 20, PeerMark.What.REACTION, "❤️", on = true),
            cast("c", 30, PeerMark.What.REACTION, "🔥", on = true),
            cast("b", 40, PeerMark.What.REACTION, "❤️", on = false),
            letter("d", 50, "просто слово"),
        )
        assertEquals(
            mapOf("❤️" to listOf("a"), "🔥" to listOf("c")),
            PeerMark.fold(log, PeerMark.What.REACTION, target),
        )
    }

    /** Кадри приїхали не в тому порядку, у якому їх поклали: годинник автора вирішує. */
    @Test
    fun `порядок згортання бере з часу, а не з черги`() {
        val put = cast("a", 10, PeerMark.What.REACTION, "❤️", on = true)
        val took = cast("a", 20, PeerMark.What.REACTION, "❤️", on = false)
        assertEquals(
            "кадр, що приїхав пізніше, але складений раніше, зняв свіжішу позначку",
            emptyMap<String, List<String>>(),
            PeerMark.fold(listOf(took, put), PeerMark.What.REACTION, target),
        )
    }

    /** Смайл однієї людини не змішується з голосом тієї самої людини. */
    @Test
    fun `роди не змішуються між собою і між рядками`() {
        val other = "11111111-2222-3333-4444-555555555555"
        val log = listOf(
            cast("a", 10, PeerMark.What.REACTION, "❤️", on = true),
            cast("a", 20, PeerMark.What.VOTE, "opt_0", on = true),
            cast("a", 30, PeerMark.What.REACTION, "🔥", on = true, targetId = other),
        )
        assertEquals(
            mapOf("❤️" to listOf("a")),
            PeerMark.fold(log, PeerMark.What.REACTION, target),
        )
        assertEquals(
            mapOf("opt_0" to listOf("a")),
            PeerMark.fold(log, PeerMark.What.VOTE, target),
        )
        assertEquals(
            mapOf("🔥" to listOf("a")),
            PeerMark.fold(log, PeerMark.What.REACTION, other),
        )
    }

    /**
     * Опитування з одним вибором: людина, що передумала, рахується ОДИН раз.
     * Без цього сума голосів перевищила б кількість тих, хто голосував, — число
     * на екрані, за яким немає людей.
     */
    @Test
    fun `новий голос знімає попередній, коли вибір один`() {
        val log = listOf(
            cast("a", 10, PeerMark.What.VOTE, "opt_0", on = true),
            cast("a", 20, PeerMark.What.VOTE, "opt_1", on = true),
            cast("b", 30, PeerMark.What.VOTE, "opt_0", on = true),
        )
        assertEquals(
            mapOf("opt_1" to listOf("a"), "opt_0" to listOf("b")),
            PeerMark.fold(log, PeerMark.What.VOTE, target, single = true),
        )
        // Той самий журнал без правила — два голоси однієї людини.
        assertEquals(
            listOf("a"),
            PeerMark.fold(log, PeerMark.What.VOTE, target, single = false)["opt_0"]?.take(1),
        )
    }

    @Test
    fun `позначка не доходить до розмови і не рахується листом`() {
        val log = listOf(cast("them", 10, PeerMark.What.REACTION, "❤️", on = true))
        assertFalse("смайл став рядком розмови", PeerOutbox.isSpoken(log.single()))
        assertEquals(
            "смайл порахувався непрочитаним листом",
            0,
            PeerOutbox.unread(log, peerId = "them", readUpToMs = 0L),
        )
        assertEquals("смайл порахувався листом, який чекає", 0, PeerOutbox.waiting(log))
        assertNull(
            "смайл загримів у шторці сирим дротом",
            PeerAnnounce.notice(log.single(), visibleChat = null, visibleGroup = null),
        )
    }

    /** Реєстр мусить упізнавати обидва роди — інакше вони поїхали б повз заслони. */
    @Test
    fun `реєстр упізнає обидва роди`() {
        assertEquals(
            PeerFrames.Kind.REACTION,
            PeerFrames.kindOf(
                PeerMark.encode(PeerMark.Mark(PeerMark.What.REACTION, target, true, "❤️"))!!,
            ),
        )
        assertEquals(
            PeerFrames.Kind.VOTE,
            PeerFrames.kindOf(
                PeerMark.encode(PeerMark.Mark(PeerMark.What.VOTE, target, true, "opt_0"))!!,
            ),
        )
    }

    /** Наступний дотик знімає те, що вже стоїть, — і питає про це той самий згортач. */
    @Test
    fun `holds каже, чи дотик зніматиме`() {
        val log = listOf(cast("a", 10, PeerMark.What.REACTION, "❤️", on = true))
        assertTrue(PeerMark.holds(log, PeerMark.What.REACTION, target, "❤️", "a"))
        assertFalse(PeerMark.holds(log, PeerMark.What.REACTION, target, "❤️", "b"))
        assertFalse(PeerMark.holds(log, PeerMark.What.REACTION, target, "🔥", "a"))
    }

    // ── дорога власної позначки ──────────────────────────────────────────
    //
    // Згортач навмисно сліпий до [PeerMessage.State]: він відповідає на
    // питання «хто що тримає», і відповідь мусить бути однаковою на обох
    // апаратах. Наслідок, який власник називає «начебто поставилось»: смайл у
    // черзі згортався в рядок точно так само, як доставлений. [PeerMark
    // .latestCast] відповідає на ДРУГЕ питання — «яким саме кадром я це
    // поставив», — і саме він дає мосту слово про дорогу.

    private fun castWith(
        from: String,
        at: Long,
        value: String,
        on: Boolean,
        state: PeerMessage.State,
    ): PeerMessage = cast(from, at, PeerMark.What.REACTION, value, on).copy(state = state)

    @Test
    fun `latestCast віддає кадр, яким позначку поставили`() {
        val log = listOf(
            castWith("a", 10, "❤️", on = true, state = PeerMessage.State.DELIVERED),
            castWith("a", 20, "🔥", on = true, state = PeerMessage.State.QUEUED),
        )
        assertEquals(
            PeerMessage.State.DELIVERED,
            PeerMark.latestCast(log, PeerMark.What.REACTION, target, "❤️", "a")?.state,
        )
        // Той самий автор, той самий рядок — інший смайл, інша доля кадру.
        assertEquals(
            PeerMessage.State.QUEUED,
            PeerMark.latestCast(log, PeerMark.What.REACTION, target, "🔥", "a")?.state,
        )
    }

    /**
     * «Поставив — зняв — поставив»: вирішує НАЙСВІЖІШИЙ кадр автора, а не
     * перший. Інакше екран показував би долю кадру, якого вже не існує.
     */
    @Test
    fun `вирішує найсвіжіший кадр автора, а не перший`() {
        val log = listOf(
            castWith("a", 10, "❤️", on = true, state = PeerMessage.State.DELIVERED),
            castWith("a", 20, "❤️", on = false, state = PeerMessage.State.DELIVERED),
            castWith("a", 30, "❤️", on = true, state = PeerMessage.State.FAILED),
        )
        assertEquals(
            PeerMessage.State.FAILED,
            PeerMark.latestCast(log, PeerMark.What.REACTION, target, "❤️", "a")?.state,
        )
    }

    /** Чужа позначка — не моя дорога: питати про неї нічим і нема сенсу. */
    @Test
    fun `чужий кадр не стає моєю дорогою`() {
        val log = listOf(castWith("b", 10, "❤️", on = true, state = PeerMessage.State.QUEUED))
        assertNull(PeerMark.latestCast(log, PeerMark.What.REACTION, target, "❤️", "a"))
        assertNotNull(PeerMark.latestCast(log, PeerMark.What.REACTION, target, "❤️", "b"))
    }

    /** Рід позначки не плутається: голос за варіант — не смайл. */
    @Test
    fun `рід позначки не плутається в дорозі`() {
        val log = listOf(
            cast("a", 10, PeerMark.What.VOTE, "opt_0", on = true)
                .copy(state = PeerMessage.State.FAILED),
        )
        assertNull(PeerMark.latestCast(log, PeerMark.What.REACTION, target, "opt_0", "a"))
        assertEquals(
            PeerMessage.State.FAILED,
            PeerMark.latestCast(log, PeerMark.What.VOTE, target, "opt_0", "a")?.state,
        )
    }

    /** Позначка на ІНШИЙ рядок не стає дорогою цього рядка. */
    @Test
    fun `позначка на інший рядок не рахується`() {
        val other = "11111111-2222-3333-4444-555555555555"
        val log = listOf(
            cast("a", 10, PeerMark.What.REACTION, "❤️", on = true, targetId = other)
                .copy(state = PeerMessage.State.FAILED),
        )
        assertNull(PeerMark.latestCast(log, PeerMark.What.REACTION, target, "❤️", "a"))
    }
}
