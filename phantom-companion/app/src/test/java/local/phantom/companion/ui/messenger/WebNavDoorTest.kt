package local.phantom.companion.ui.messenger

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Двері веба, які шелл відчиняє після вкладки «Я»: `__phantomOpenTab` і `__phantomComposeMoment`. */
class WebNavDoorTest {

    @After fun clear() {
        WebNav.pending.value = null
    }

    @Test fun `a request waits in the queue until the host takes it, newest wins`() {
        WebNav.request(WebNav.Door.OpenTab("c:close"), nowMs = 1_000L)
        assertEquals(WebNav.Ask(WebNav.Door.OpenTab("c:close"), 1_000L + WebNav.TTL_MS), WebNav.pending.value)
        WebNav.request(WebNav.Door.ComposeMoment, nowMs = 2_000L)
        assertEquals(WebNav.Door.ComposeMoment, WebNav.pending.value!!.door)
    }

    @Test fun `the script calls the exact door name with the key quoted`() {
        val open = WebNav.script(WebNav.Door.OpenTab("c:close"))
        assertTrue(open, "window.__phantomOpenTab" in open && "\"c:close\"" in open && "'missing'" in open)
        val tricky = WebNav.script(WebNav.Door.OpenTab("c:\"x\");alert(1);//"))
        assertTrue("слово людини не стає кодом: $tricky", "\"c:\\\"x\\\");alert(1);//\"" in tricky)
        val compose = WebNav.script(WebNav.Door.ComposeMoment)
        assertTrue(compose, "window.__phantomComposeMoment" in compose && "'missing'" in compose)
        val share = WebNav.script(WebNav.Door.ShareIn)
        assertTrue(share, "window.__phantomShareIn" in share && "'missing'" in share)
        val profiles = WebNav.script(WebNav.Door.OpenProfiles)
        assertTrue(profiles, "__phantomProfiles" in profiles && "open()" in profiles && "'missing'" in profiles)
    }

    @Test fun `the answer is one of three words and anything else means not there yet`() {
        assertEquals(WebNav.Outcome.OPENED, WebNav.outcome("\"opened\""))
        assertEquals(WebNav.Outcome.REFUSED, WebNav.outcome("\"refused\""))
        assertEquals(WebNav.Outcome.MISSING, WebNav.outcome("\"missing\""))
        assertEquals(WebNav.Outcome.MISSING, WebNav.outcome("null"))
        assertEquals(WebNav.Outcome.MISSING, WebNav.outcome(null))
    }

    @Test fun `the page is still coming up - the host retries until the door opens`() = runTest {
        WebNav.request(WebNav.Door.OpenTab("c:work"))
        val ask = WebNav.pending.value!!
        var calls = 0
        val out = WebNav.deliver(ask, run = { calls++; if (calls < 4) "\"missing\"" else "\"opened\"" }, pause = {})
        assertEquals(WebNav.Outcome.OPENED, out)
        assertEquals(4, calls)
        assertNull("відкрите знімається з черги", WebNav.pending.value)
    }

    @Test fun `a refusal is one call and leaves the queue`() = runTest {
        WebNav.request(WebNav.Door.OpenTab("c:nobody"))
        val ask = WebNav.pending.value!!
        var calls = 0
        assertEquals(WebNav.Outcome.REFUSED, WebNav.deliver(ask, run = { calls++; "\"refused\"" }, pause = {}))
        assertEquals(1, calls)
        assertNull(WebNav.pending.value)
    }

    @Test fun `no door at all - exactly TRIES attempts and the request stays for the next page`() = runTest {
        WebNav.request(WebNav.Door.ComposeMoment)
        val ask = WebNav.pending.value!!
        var calls = 0
        assertEquals(WebNav.Outcome.MISSING, WebNav.deliver(ask, run = { calls++; "\"missing\"" }, pause = {}))
        assertEquals(WebNav.TRIES, calls)
        assertEquals(ask, WebNav.pending.value)
    }

    @Test fun `a stale request is dropped without opening anything`() = runTest {
        WebNav.request(WebNav.Door.OpenTab("c:close"), nowMs = 0L)
        val stale = WebNav.pending.value!!
        var asked = 0
        val out = WebNav.deliver(stale, run = { asked++; "\"opened\"" }, pause = {}, now = { 3 * 3_600_000L })
        assertEquals(WebNav.Outcome.EXPIRED, out)
        assertEquals(0, asked)
        assertNull(WebNav.pending.value)
    }

    @Test fun `a newer request is not cleared by an older answer`() = runTest {
        WebNav.request(WebNav.Door.OpenTab("c:close"))
        val first = WebNav.pending.value!!
        WebNav.deliver(first, run = {
            WebNav.request(WebNav.Door.ComposeMoment)
            "\"opened\""
        }, pause = {})
        assertEquals(WebNav.Door.ComposeMoment, WebNav.pending.value!!.door)
    }

    @Test
    fun `двері вкладки напряму кличуть window phantomOpenDirection з id, порожній id знімає шар`() {
        val open = WebNav.script(WebNav.Door.OpenDirection("tasks"))
        assertTrue(open.contains("window.__phantomOpenDirection"))
        assertTrue(open.contains("f(\"tasks\")"))
        assertTrue(WebNav.script(WebNav.Door.OpenDirection("")).contains("f(\"\")"))
        assertEquals(WebNav.Outcome.OPENED, WebNav.outcome("opened"))
    }
}
