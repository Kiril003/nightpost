package local.phantom.companion.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatHushTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `until is honoured to the millisecond and forever means forever`() {
        val h = ChatHush(mapOf("a" to null, "b" to 5_000L))
        assertTrue(h.hushes("a", Long.MAX_VALUE))
        assertTrue(h.hushes("b", 4_999L))
        assertFalse(h.hushes("b", 5_000L))
        assertFalse(h.hushes("c", 0L))
        assertEquals(setOf("a"), h.live(5_000L).until.keys)
    }

    @Test
    fun `the exceptions list reads soonest end first and forever last`() {
        val h = ChatHush().with("forever", null, "Дім").with("late", 9_000L).with("soon", 2_000L, "Марта")
        assertEquals(
            listOf(Triple("soon", 2_000L, "Марта"), Triple("late", 9_000L, null), Triple("forever", null, "Дім")),
            h.rows(),
        )
        assertEquals(mapOf("forever" to "Дім"), h.live(9_000L).titles)
    }

    @Test
    fun `the store survives a restart and drops expired rows`() {
        val now = System.currentTimeMillis()
        ChatHushStore(app).apply {
            set("keep", null, "Марта")
            set("hour", now + 3_600_000L)
            set("gone", now - 1L)
        }
        val again = ChatHushStore(app)
        assertTrue(again.hushesNow("keep"))
        assertTrue(again.hushesNow("hour"))
        assertFalse(again.hushesNow("gone"))
        assertEquals(setOf("keep", "hour"), again.live().until.keys)
        assertEquals(mapOf("keep" to "Марта"), again.live().titles)
        again.clear("keep")
        again.clear("hour")
        assertTrue(ChatHushStore(app).live().until.isEmpty())
    }

    @Test
    fun `P10 - mentions ring through by default and the switch survives a restart`() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ChatHushStore(ctx)
        org.junit.Assert.assertTrue(store.mentionsThrough.value)
        store.setMentionsThrough(false)
        org.junit.Assert.assertFalse(ChatHushStore(ctx).mentionsThrough.value)
        store.setMentionsThrough(true)
    }

    @Test
    fun `Q1 - a web rule without hours drops, hours across midnight go silent, a question in a one-to-one chat rings`() {
        val night = HushRule.of("22:00", "07:00", ask = true)!!
        assertEquals(HushRule.Verdict.SILENT, night.verdict(23 * 60, oneToOne = false, text = "так"))
        assertEquals(HushRule.Verdict.SILENT, night.verdict(6 * 60 + 59, oneToOne = false, text = "так"))
        assertEquals(HushRule.Verdict.RING, night.verdict(7 * 60, oneToOne = false, text = "так"))
        assertEquals(HushRule.Verdict.RING, night.verdict(23 * 60, oneToOne = true, text = "ти де?"))
        assertEquals(HushRule.Verdict.SILENT, night.verdict(23 * 60, oneToOne = false, text = "ти де?"))
        assertEquals(HushRule.Verdict.DROP, HushRule().verdict(12 * 60, oneToOne = true, text = "ти де?"))
        assertEquals(null, HushRule.of("10:00", "10:00", ask = false))
        assertEquals(null, HushRule.of("25:00", "07:00", ask = false))
    }

    @Test
    fun `Q1 - no rules from the web means plain behaviour, and sent rules survive a restart`() {
        val store = ChatHushStore(app)
        store.setRules(emptyMap())
        assertEquals(HushRule.Verdict.RING, ChatHushStore(app).ruleVerdict("marta", oneToOne = true, text = "привіт"))
        store.setRules(mapOf("marta" to HushRule(), "dim" to HushRule(22 * 60, 7 * 60, ask = true)))
        val again = ChatHushStore(app)
        assertEquals(mapOf("marta" to HushRule(), "dim" to HushRule(22 * 60, 7 * 60, ask = true)), again.rules.value)
        assertEquals(HushRule.Verdict.DROP, again.ruleVerdict("marta", oneToOne = true, text = "привіт"))
        store.setRules(emptyMap())
        assertTrue(ChatHushStore(app).rules.value.isEmpty())
    }
}
