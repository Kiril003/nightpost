package local.phantom.companion.feature.godmode.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for [ScreenContextExtractor] + [ScreenContextFormatter].
 * No Robolectric, no Android — proves the AccessibilityService walk
 * stays a thin adapter and the ranking / formatting logic can be
 * exercised entirely on the JVM.
 */
class ScreenContextExtractorTest {

    // ---------- ScreenContextExtractor.build ----------

    @Test
    fun `build orders visibleText by descending area`() {
        val ctx = ScreenContextExtractor.build(
            packageName = "com.example.app",
            appLabel = "Example",
            rootClass = "android.widget.FrameLayout",
            nodes = listOf(
                ScreenNode(text = "small", areaPx = 10),
                ScreenNode(text = "huge", areaPx = 1_000),
                ScreenNode(text = "medium", areaPx = 200),
            ),
            nowMs = 1_000L,
        )
        assertEquals(listOf("huge", "medium", "small"), ctx.visibleText)
        assertEquals("com.example.app", ctx.packageName)
        assertEquals("Example", ctx.appLabel)
        assertEquals("android.widget.FrameLayout", ctx.rootClass)
        assertEquals(1_000L, ctx.capturedAtMs)
    }

    @Test
    fun `build trims whitespace and drops blank text`() {
        val ctx = ScreenContextExtractor.build(
            packageName = "p",
            appLabel = null,
            rootClass = null,
            nodes = listOf(
                ScreenNode(text = "  hello  ", areaPx = 100),
                ScreenNode(text = "   ", areaPx = 999),
                ScreenNode(text = "\n\t", areaPx = 50),
                ScreenNode(text = "world", areaPx = 60),
            ),
            nowMs = 0L,
        )
        assertEquals(listOf("hello", "world"), ctx.visibleText)
    }

    @Test
    fun `build de-duplicates same text keeping largest area`() {
        val ctx = ScreenContextExtractor.build(
            packageName = "p",
            appLabel = null,
            rootClass = null,
            nodes = listOf(
                ScreenNode(text = "Send", areaPx = 30),
                ScreenNode(text = "Cancel", areaPx = 40),
                ScreenNode(text = "Send", areaPx = 250),
                ScreenNode(text = "Send", areaPx = 80),
            ),
            nowMs = 0L,
        )
        // Send wins on the 250-area copy; Cancel keeps its 40.
        assertEquals(listOf("Send", "Cancel"), ctx.visibleText)
    }

    @Test
    fun `build respects topN cap`() {
        val nodes = (1..20).map { ScreenNode(text = "n$it", areaPx = it.toLong()) }
        val ctx = ScreenContextExtractor.build(
            packageName = "p",
            appLabel = null,
            rootClass = null,
            nodes = nodes,
            nowMs = 0L,
            topN = 3,
        )
        // Top three by area = n20 / n19 / n18.
        assertEquals(listOf("n20", "n19", "n18"), ctx.visibleText)
    }

    @Test
    fun `build with topN zero produces empty list`() {
        val ctx = ScreenContextExtractor.build(
            packageName = "p",
            appLabel = null,
            rootClass = null,
            nodes = listOf(ScreenNode("a", 10)),
            nowMs = 0L,
            topN = 0,
        )
        assertTrue(ctx.visibleText.isEmpty())
    }

    @Test
    fun `build tolerates empty node list`() {
        val ctx = ScreenContextExtractor.build(
            packageName = "p",
            appLabel = "Phantom",
            rootClass = "Root",
            nodes = emptyList(),
            nowMs = 42L,
        )
        assertTrue(ctx.visibleText.isEmpty())
        assertEquals(42L, ctx.capturedAtMs)
        assertEquals("Phantom", ctx.appLabel)
    }

    // ---------- ScreenContextFormatter.format ----------

    @Test
    fun `format builds expected SCREEN block with quoted text`() {
        val ctx = ScreenContext(
            packageName = "com.android.chrome",
            appLabel = "Chrome",
            rootClass = null,
            visibleText = listOf("Buy tickets", "Eurostar London"),
            capturedAtMs = 0L,
        )
        assertEquals(
            "[SCREEN: app=Chrome, top-text=\"Buy tickets · Eurostar London\"]",
            ScreenContextFormatter.format(ctx),
        )
    }

    @Test
    fun `format falls back to packageName when appLabel is blank or null`() {
        val ctxNull = ScreenContext("com.foo.app", null, null, listOf("hello"), 0L)
        val ctxBlank = ScreenContext("com.foo.app", "  ", null, listOf("hello"), 0L)
        assertEquals(
            "[SCREEN: app=com.foo.app, top-text=\"hello\"]",
            ScreenContextFormatter.format(ctxNull),
        )
        assertEquals(
            "[SCREEN: app=com.foo.app, top-text=\"hello\"]",
            ScreenContextFormatter.format(ctxBlank),
        )
    }

    @Test
    fun `format omits top-text segment when visibleText is empty`() {
        val ctx = ScreenContext("p", "Phantom", null, emptyList(), 0L)
        assertEquals("[SCREEN: app=Phantom]", ScreenContextFormatter.format(ctx))
    }

    @Test
    fun `format clips long single text per-text and joined`() {
        val long = "x".repeat(200)
        val ctx = ScreenContext("p", "P", null, listOf(long, long), 0L)
        val out = ScreenContextFormatter.format(ctx, maxTextChars = 100)
        // The combined slice cannot exceed the max-chars budget.
        val opening = "[SCREEN: app=P, top-text=\""
        val payload = out.removePrefix(opening).removeSuffix("\"]")
        assertTrue("payload was: $payload", payload.length <= 100)
    }

    @Test
    fun `format returns empty string for null context`() {
        assertEquals("", ScreenContextFormatter.format(null))
    }
}
