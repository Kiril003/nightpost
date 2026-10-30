package local.phantom.companion.core.vision.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Межа зору: що саме означає «нічого не побачив».
 *
 * Модуль `core-vision` жив у збірці **без жодного тесту** — ворота не могли за
 * нього почервоніти за побудовою (вимір 04.09: 22 модулі з тестами, цей єдиний
 * без). Тут стережеться не механіка MLKit, а **продуктове рішення**: порожнеча,
 * збій і знахідка — три різні відповіді, і жодна не сміє видавати себе за іншу.
 *
 * Чому саме [ImageInspector.combine], а не [ImageInspector.inspect]: клієнти
 * MLKit створюються в полях, тож на чистій JVM `inspect` не викликати. Рішення
 * винесено з нього дослівно — і стережеться саме воно, а не зліплені руками
 * обʼєкти [VisionRead], які доводили б лише мої власні уявлення.
 */
class VisionReadBoundaryTest {

    @Test
    fun `коли обидва рушії впали — це збій, і причина не губиться`() {
        val out = ImageInspector.combine(
            ImageInspector.Outcome.Failed("text recognition failed"),
            ImageInspector.Outcome.Failed("barcode scan failed"),
        )
        assertTrue("два падіння мусять дати Failed, а не порожнечу", out is VisionRead.Failed)
        assertEquals("text recognition failed", (out as VisionRead.Failed).reason)
    }

    @Test
    fun `не знайшов — це не зламався`() {
        val out = ImageInspector.combine(
            ImageInspector.Outcome.Done(value = ""),
            ImageInspector.Outcome.Done(list = emptyList()),
        )
        assertTrue(
            "рушій відпрацював і не знайшов нічого — це ВИМІР (Empty), не збій (Failed)",
            out is VisionRead.Empty,
        )
    }

    @Test
    fun `пробіли не є знахідкою`() {
        val out = ImageInspector.combine(
            ImageInspector.Outcome.Done(value = "\n   \t "),
            ImageInspector.Outcome.Done(list = emptyList()),
        )
        assertTrue(
            "текст із самих пробілів мусить бути Empty: інакше модель дістане " +
                "порожню відповідь під виглядом знайденої",
            out is VisionRead.Empty,
        )
    }

    @Test
    fun `самі коди без тексту — це знахідка`() {
        val out = ImageInspector.combine(
            ImageInspector.Outcome.Done(value = "   "),
            ImageInspector.Outcome.Done(list = listOf("https://example.org")),
        )
        assertTrue(out is VisionRead.Found)
        out as VisionRead.Found
        assertEquals("", out.text)
        assertEquals(listOf("https://example.org"), out.codes)
    }

    @Test
    fun `один рушій упав, другий знайшов — знахідка, а не відмова`() {
        val out = ImageInspector.combine(
            ImageInspector.Outcome.Failed("text recognition failed"),
            ImageInspector.Outcome.Done(list = listOf("QR-1")),
        )
        assertTrue(
            "падіння одного рушія не сміє зʼїдати знахідку другого",
            out is VisionRead.Found,
        )
        assertEquals(listOf("QR-1"), (out as VisionRead.Found).codes)
    }
}
