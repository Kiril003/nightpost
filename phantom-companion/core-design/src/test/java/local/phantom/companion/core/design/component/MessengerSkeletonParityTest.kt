package local.phantom.companion.core.design.component

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Ескіз домівки живе в трьох власниках: рідний [MessengerSkeleton] під
 * WebView, `#boot .sk` в index.html вебу і `BootFrame` у App.tsx (запасний кадр
 * React). Холодний старт передає кадр від одного до іншого; розійдуться числа —
 * ескіз стрибне на передачі, і «спокійний перший кадр» стане тремтячим.
 *
 * GATE LAW: читає сусіднє дерево (phantom-os) сирим файловим I/O. Ганяти з
 * `--rerun-tasks`.
 */
class MessengerSkeletonParityTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private fun web(rel: String): String {
        val f = File(repoRoot().parentFile, "phantom-os/src/frontend/$rel")
        if (!f.isFile) fail("немає ${f.path}")
        return f.readText()
    }

    /** Властивості першого правила з цим селектором у `<style>` index.html. */
    private fun rule(css: String, selector: String): Map<String, String> {
        val at = css.indexOf("$selector {")
        if (at < 0) fail("у index.html немає правила «$selector»")
        val body = css.substring(css.indexOf('{', at) + 1, css.indexOf('}', at))
        return body.split(';').mapNotNull { decl ->
            val i = decl.indexOf(':')
            if (i < 0) null else decl.substring(0, i).trim() to decl.substring(i + 1).trim()
        }.toMap()
    }

    private fun px(v: Float) = "${if (v % 1f == 0f) v.toInt().toString() else v.toString()}px"

    @Test
    fun `native skeleton and the web boot draw the same numbers`() {
        val html = web("index.html")
        val s = MessengerSkeletonSpec
        assertEquals(px(s.topPad.value), rule(html, ":root[data-shell='phone'] #boot .sk")["padding-top"])
        rule(html, "#boot .sk-head").let {
            assertEquals(px(s.headHeight.value), it["height"])
            assertEquals("0 ${px(s.headEnd.value)} 0 ${px(s.side.value)}", it["padding"])
            assertEquals(px(s.headGap.value), it["gap"])
        }
        rule(html, "#boot .sk-disc").let {
            assertEquals(px(s.disc.value), it["width"])
            assertEquals(px(s.disc.value), it["height"])
        }
        assertEquals("${(s.discGlyphShare * 100).toInt()}%", rule(html, "#boot .sk-disc svg")["width"])
        rule(html, "#boot .sk i.sk-title").let {
            assertEquals(px(s.titleWidth.value), it["width"])
            assertEquals(px(s.titleHeight.value), it["height"])
        }
        rule(html, "#boot .sk i.sk-pill").let {
            assertEquals(px(s.pillWidth.value), it["width"])
            assertEquals(px(s.pillHeight.value), it["height"])
        }
        rule(html, "#boot .sk-ask").let {
            assertEquals("${px(s.askTop.value)} ${px(s.side.value)} 0", it["margin"])
            assertEquals(px(s.askGap.value), it["gap"])
        }
        assertEquals(px(s.askHeight.value), rule(html, "#boot .sk i.sk-capsule")["height"])
        assertEquals(px(s.askHeight.value), rule(html, "#boot .sk i.sk-mic")["height"])
        rule(html, "#boot .sk-row").let {
            assertEquals(px(s.rowTop.value), it["margin-top"])
            assertEquals(px(s.rowHeight.value), it["height"])
            assertEquals("0 ${px(s.side.value)}", it["padding"])
            assertEquals(px(s.rowGap.value), it["gap"])
        }
        rule(html, "#boot .sk i.sk-line").let {
            assertEquals("${(s.lineShare * 100).toInt()}%", it["width"])
            assertEquals(px(s.lineHeight.value), it["height"])
        }
        rule(html, "#boot .sk i.sk-line.thin").let {
            assertEquals("${(s.thinShare * 100).toInt()}%", it["width"])
            assertEquals(px(s.thinHeight.value), it["height"])
        }
        rule(html, "#boot .sk i.sk-card").let {
            assertEquals("${px(s.cardGap.value)} ${px(s.side.value)} 0", it["margin"])
            assertEquals(px(s.cardHeight.value), it["height"])
            assertEquals(px(s.cardRadius.value), it["border-radius"])
        }
        assertEquals(px(s.firstCardTop.value), rule(html, "#boot .sk i.sk-card.first")["margin-top"])
        assertEquals(local.phantom.companion.core.design.theme.PhantomRadii.Large, s.cardRadius)
    }

    @Test
    fun `the web boot is the skeleton, not a waiting screen, and the react fallback is the same markup`() {
        val html = web("index.html")
        val boot = html.substringAfter("<div id=\"boot\"").substringBefore("<script type=\"module\"")
        assertFalse("«Відкриваю месенджер…» повернулось у заставку", "Відкриваю" in boot)
        val classes = Regex("""class="([^"]+)"""").findAll(boot).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf("glyph", "sk", "sk-head", "sk-disc", "sk-title", "sk-pill", "sk-ask", "sk-capsule", "sk-mic", "sk-row", "sk-line", "sk-line thin", "sk-card first", "sk-card"),
            classes,
        )
        assertTrue("знак у #boot — не той, що в застосунку", boot.contains(PhantomMark.PATH))

        val app = web("src/app/App.tsx")
        val frame = app.substringAfter("export function BootFrame(").substringBefore("function AutoLoginManager")
        assertFalse("запасний кадр React знову говорить «Відкриваю…»", "Відкриваю" in frame)
        val reactClasses = Regex("""className="([^"]+)"""").findAll(frame).map { it.groupValues[1] }.toList()
        assertEquals("BootFrame розійшовся з #boot — на першому комміті React ескіз стрибне", classes, reactClasses)
    }

    @Test
    fun `the web boot starts in night and takes day only from the bridge`() {
        val html = web("index.html")
        assertTrue(html.contains("""<html lang="uk" data-theme="night">"""))
        val js = web("public/boot-theme.js")
        assertTrue("заставка не читає тему з мосту", js.contains("bridge.getTheme()"))
        assertTrue("день не лише з явного «day»", js.contains("theme === 'day' ? 'day' : 'night'"))
        assertTrue(js.contains("setAttribute('data-shell', 'phone')"))
    }
}
