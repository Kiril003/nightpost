package local.phantom.companion.feature.godmode.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SelectedTextRouterTest {

    private var fakeNow: Long = 0L

    @Before
    fun setUp() {
        SelectedTextRouter.resetForTest()
        fakeNow = 1_000L
        SelectedTextRouter.clockMs = { fakeNow }
    }

    @After
    fun tearDown() {
        SelectedTextRouter.resetForTest()
    }

    @Test
    fun `update stores trimmed selection`() {
        SelectedTextRouter.update(
            SelectedText(text = "  manhole cover  ", sourcePackage = "com.x", capturedAtMs = 1_000L),
        )
        val current = SelectedTextRouter.current()
        assertNotNull(current)
        assertEquals("manhole cover", current!!.text)
    }

    @Test
    fun `update rejects blank text`() {
        SelectedTextRouter.update(
            SelectedText(text = "   ", sourcePackage = "com.x", capturedAtMs = 1_000L),
        )
        assertNull(SelectedTextRouter.current())
    }

    @Test
    fun `current returns null past the staleness window`() {
        SelectedTextRouter.staleAfterMs = 5_000L
        SelectedTextRouter.update(
            SelectedText(text = "fresh", sourcePackage = "p", capturedAtMs = 1_000L),
        )
        fakeNow = 2_000L
        assertNotNull(SelectedTextRouter.current())
        fakeNow = 10_000L
        assertNull("must drop stale capture", SelectedTextRouter.current())
    }

    @Test
    fun `clear drops the snapshot`() {
        SelectedTextRouter.update(
            SelectedText(text = "x", sourcePackage = "p", capturedAtMs = 1_000L),
        )
        assertNotNull(SelectedTextRouter.current())
        SelectedTextRouter.clear()
        assertNull(SelectedTextRouter.current())
    }

    @Test
    fun `formatForPrompt builds the operator-selected block`() {
        val sel = SelectedText(text = "Eurostar London-Paris", sourcePackage = "p", capturedAtMs = 0L)
        assertEquals(
            "[ОПЕРАТОР ВИБРАВ:\"Eurostar London-Paris\"]",
            SelectedTextRouter.formatForPrompt(sel),
        )
    }

    @Test
    fun `formatForPrompt clips long selection`() {
        val long = "a".repeat(2_000)
        val out = SelectedTextRouter.formatForPrompt(
            SelectedText(long, "p", 0L),
            maxChars = 50,
        )
        // 50 chars + the wrapping `[ОПЕРАТОР ВИБРАВ:"..."]` boilerplate.
        assertTrue("payload limited: $out", out.length < 100)
        assertTrue(out.startsWith("[ОПЕРАТОР ВИБРАВ:\""))
        assertTrue(out.endsWith("\"]"))
    }

    @Test
    fun `formatForPrompt returns empty string for null`() {
        assertEquals("", SelectedTextRouter.formatForPrompt(null))
    }
}
