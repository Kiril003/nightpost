package local.phantom.companion.core.data.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decay is only worth having if it never eats the thing worth keeping. Every
 * rule here is a promise to the operator about what survives.
 */
class ChatDecayTest {

    private val now = 1_800_000_000_000L
    private val day = 86_400_000L
    private val policy = ChatDecay.Policy(plumbingDays = 21)

    private fun item(
        kind: String,
        ageDays: Int,
        widgetType: String? = null,
        payload: String? = null,
        pinned: Boolean = false,
        text: String? = null,
    ) = StreamItemEntity(
        id = "i", threadId = "t", seqNo = 1, createdAt = now - ageDays * day,
        kind = kind, widgetType = widgetType, widgetPayload = payload,
        textContent = text, pinned = pinned,
    )

    private val heavy = "{\"title\":\"бюджет липня\",\"points\":[" + "1,".repeat(1500) + "1]}"

    @Test
    fun `nothing fresh is touched`() {
        val fresh = item(StreamItemEntity.Kind.WIDGET, ageDays = 3, widgetType = "chart", payload = heavy)
        assertEquals(ChatDecay.Fate.KEEP, ChatDecay.fateOf(fresh, now, policy))
    }

    @Test
    fun `a pinned item never decays, however old and heavy`() {
        val old = item(
            StreamItemEntity.Kind.WIDGET, ageDays = 900, widgetType = "canvas",
            payload = heavy, pinned = true,
        )
        assertEquals(ChatDecay.Fate.KEEP, ChatDecay.fateOf(old, now, policy))
    }

    @Test
    fun `words survive — a year-old sentence is still a sentence`() {
        val text = item(StreamItemEntity.Kind.TEXT, ageDays = 365, text = "де я лишив генератор")
        assertEquals(ChatDecay.Fate.KEEP, ChatDecay.fateOf(text, now, policy))
    }

    /**
     * The regression this file exists for: day 45 used to be the day a canvas
     * became a `{"decayed":true}` stub. It is now an ordinary day.
     */
    @Test
    fun `a heavy canvas at day 45 is whole`() {
        val canvas = item(StreamItemEntity.Kind.WIDGET, ageDays = 45, widgetType = "canvas", payload = heavy)
        assertEquals(ChatDecay.Fate.KEEP, ChatDecay.fateOf(canvas, now, policy))
    }

    @Test
    fun `everything the operator made outlives every old decay stage`() {
        val stages = listOf(22, 45, 60, 89)
        for (type in listOf("canvas", "chart", "artifact", "code-runner", "app-action")) {
            for (age in stages) {
                val card = item(StreamItemEntity.Kind.WIDGET, ageDays = age, widgetType = type, payload = heavy)
                assertEquals(
                    "$type at day $age must still be whole",
                    ChatDecay.Fate.KEEP,
                    ChatDecay.fateOf(card, now, policy),
                )
            }
        }
    }

    /** A notice is on screen and the operator can read it — it is not plumbing. */
    @Test
    fun `a notice the operator received is kept`() {
        val notice = item(StreamItemEntity.Kind.NOTICE, ageDays = 60, text = "Telegram: Богдан — де ти?")
        assertEquals(ChatDecay.Fate.KEEP, ChatDecay.fateOf(notice, now, policy))
    }

    /** The record of PHANTOM acting on the operator's phone is an audit trail. */
    @Test
    fun `an app-action record is not plumbing`() {
        val action = item(StreamItemEntity.Kind.WIDGET, ageDays = 60, widgetType = "app-action", payload = heavy)
        assertEquals(ChatDecay.Fate.KEEP, ChatDecay.fateOf(action, now, policy))
    }

    @Test
    fun `plumbing drops once past its window`() {
        val trace = item(StreamItemEntity.Kind.WIDGET, ageDays = 30, widgetType = "tool-trace", payload = "{}")
        val progress = item(StreamItemEntity.Kind.WIDGET, ageDays = 30, widgetType = "agent-progress", payload = "{}")
        assertEquals(ChatDecay.Fate.DROP, ChatDecay.fateOf(trace, now, policy))
        assertEquals(ChatDecay.Fate.DROP, ChatDecay.fateOf(progress, now, policy))
    }

    @Test
    fun `plumbing is still kept inside its window`() {
        val trace = item(StreamItemEntity.Kind.WIDGET, ageDays = 16, widgetType = "tool-trace", payload = "{}")
        assertEquals(ChatDecay.Fate.KEEP, ChatDecay.fateOf(trace, now, policy))
    }

    @Test
    fun `pinned plumbing is still the operator's to keep`() {
        val trace = item(
            StreamItemEntity.Kind.WIDGET, ageDays = 300, widgetType = "tool-trace",
            payload = "{}", pinned = true,
        )
        assertEquals(ChatDecay.Fate.KEEP, ChatDecay.fateOf(trace, now, policy))
    }

    /** Only plumbing may ever be destroyed by a clock shorter than retention. */
    @Test
    fun `nothing but plumbing is ever destroyed, at any age`() {
        val types = listOf(
            null, "canvas", "chart", "artifact", "code-runner", "app-action",
            "stat-hero", "reminder", "map", "question",
        )
        for (age in listOf(21, 22, 45, 90, 365, 3_650)) {
            for (type in types) {
                val kind = if (type == null) StreamItemEntity.Kind.TEXT else StreamItemEntity.Kind.WIDGET
                val row = item(kind, ageDays = age, widgetType = type, payload = heavy, text = "текст")
                assertEquals(
                    "${type ?: "text"} at day $age must survive — retention is the only clock",
                    ChatDecay.Fate.KEEP,
                    ChatDecay.fateOf(row, now, policy),
                )
            }
        }
    }

    @Test
    fun `shrink is never handed out any more`() {
        val types = listOf("canvas", "chart", "artifact", "tool-trace", "agent-progress", "app-action")
        for (age in listOf(21, 45, 90, 365)) {
            for (type in types) {
                val row = item(StreamItemEntity.Kind.WIDGET, ageDays = age, widgetType = type, payload = heavy)
                assertTrue(
                    "$type at day $age must not be shrunk",
                    ChatDecay.fateOf(row, now, policy) != ChatDecay.Fate.SHRINK,
                )
            }
        }
    }

    @Test
    fun `the candidate cutoff is the plumbing window, not a second clock`() {
        assertEquals(21, ChatDecay.Policy(plumbingDays = 21).verbatimDays)
        assertEquals(7, ChatDecay.Policy(plumbingDays = 7).verbatimDays)
    }

    @Test
    fun `plumbing is recognised whatever the case`() {
        val shouty = item(StreamItemEntity.Kind.WIDGET, ageDays = 30, widgetType = "Tool-Trace", payload = "{}")
        assertEquals(ChatDecay.Fate.DROP, ChatDecay.fateOf(shouty, now, policy))
    }

    @Test
    fun `the digest keeps the title and admits what it is`() {
        val digest = ChatDecay.digestOf("chart", heavy)
        assertTrue("decay must be self-declaring", "\"decayed\":true" in digest)
        assertTrue("the title is what makes the row readable", "бюджет липня" in digest)
        assertTrue("a digest that is not small is pointless", digest.length < 200)
    }

    @Test
    fun `a title with quotes cannot break the digest json`() {
        val digest = ChatDecay.digestOf("canvas", "{\"title\":\"він сказав \\\"ні\\\"\"}")
        assertTrue(digest.startsWith("{") && digest.endsWith("}"))
        assertTrue("\"decayed\":true" in digest)
    }

    @Test
    fun `no title is not an error`() {
        assertNull(ChatDecay.titleIn("{\"points\":[1,2,3]}"))
        assertTrue("\"decayed\":true" in ChatDecay.digestOf("chart", "{\"points\":[1]}"))
    }

    @Test
    fun `a clock jump backwards does not age anything`() {
        val future = item(StreamItemEntity.Kind.WIDGET, ageDays = -30, widgetType = "tool-trace", payload = "{}")
        assertEquals(ChatDecay.Fate.KEEP, ChatDecay.fateOf(future, now, policy))
    }
}
