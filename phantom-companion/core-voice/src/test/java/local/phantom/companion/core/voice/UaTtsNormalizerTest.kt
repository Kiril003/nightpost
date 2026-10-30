package local.phantom.companion.core.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class UaTtsNormalizerTest {

    /** The live defect: an air-raid alert read out «метрів. Київ». */
    @Test
    fun `city abbreviation is a city, not a unit of distance`() {
        assertEquals(
            "Повітряна тривога — місто Київ",
            UaTtsNormalizer.normalize("Повітряна тривога — м. Київ"),
        )
    }

    @Test
    fun `units and numbers expand into Ukrainian words`() {
        assertEquals("через двісті метрів поверніть", UaTtsNormalizer.normalize("через 200 м поверніть"))
        assertEquals("через пʼять кілометрів", UaTtsNormalizer.normalize("через 5 км"))
        assertEquals("двісті метрів", UaTtsNormalizer.normalize("200м"))
        assertEquals("одна хвилина", UaTtsNormalizer.normalize("1 хв"))
        assertEquals("дві хвилини", UaTtsNormalizer.normalize("2 хв"))
        assertEquals("пʼятнадцять хвилин", UaTtsNormalizer.normalize("15 хв"))
        assertEquals("півтора кілометра", UaTtsNormalizer.normalize("1.5 км"))
    }

    @Test
    fun `a bare unit letter with no number is left alone`() {
        assertEquals("точка м на схемі", UaTtsNormalizer.normalize("точка м на схемі"))
        assertEquals("сектор км", UaTtsNormalizer.normalize("сектор км"))
    }

    @Test
    fun `oblast abbreviation keeps the phrase grammatical`() {
        assertEquals("Київська область", UaTtsNormalizer.normalize("Київська обл."))
    }

    @Test
    fun `street abbreviations still expand`() {
        assertEquals("вулиці Січова чотири", UaTtsNormalizer.normalize("вул. Січова 4"))
        assertEquals("проспекту Науки", UaTtsNormalizer.normalize("просп. Науки"))
    }

    @Test
    fun `markdown and urls are stripped`() {
        assertEquals("важливо", UaTtsNormalizer.normalize("**важливо**"))
        assertEquals("дивись посиланню", UaTtsNormalizer.normalize("дивись https://example.com/x"))
    }

    @Test
    fun `an abbreviation dot is not a regex wildcard`() {
        // Escaping matters: unescaped, «м.» would match «мх», «мь», any char.
        assertEquals("мх Київ", UaTtsNormalizer.normalize("мх Київ"))
    }
}
