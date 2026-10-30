package local.phantom.companion.feature.stream.ui.map.indoor

import local.phantom.companion.core.geo.indoor.BuildingEntrance
import local.phantom.companion.core.geo.indoor.EntranceKind
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyph
import local.phantom.companion.feature.stream.ui.map.icons.RecordingGlyphPen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Входи — знаками, не написами (прохання тестувальників 12.09).
 *
 * До цієї зміни `IndoorFloorOverlay` малював кожен вхід ТЕКСТОВОЮ плашкою, і
 * змінна, що її несла, звалась `icon`, хоч у ній лежало слово «Вхід». Вісім
 * плашок на квартал забивали екран рівно там, де людина дивиться на двері.
 */
class EntranceSignTest {

    private fun entrance(
        kind: EntranceKind,
        wheelchair: Boolean = false,
        id: String = "e1",
    ) = BuildingEntrance(
        id = id,
        lat = 50.45,
        lon = 30.52,
        kind = kind,
        isWheelchairAccessible = wheelchair,
    )

    private fun record(glyph: PhantomGlyph) = RecordingGlyphPen().also { glyph.drawOn(it) }

    @Test
    fun `кожен рід входу має намальований знак`() {
        EntranceKind.entries.forEach { kind ->
            val glyph = EntranceSign.glyph(kind)
            assertTrue("$kind без знака — на землі був би порожній диск", record(glyph).marks > 0)
        }
    }

    /**
     * Аварійний вихід НЕ носить ті самі двері, що головний вхід.
     *
     * Це і є вся суть окремого [PhantomGlyph.EXIT]: розрізняти їх самим лише
     * кольором означало б не розрізняти їх для того, хто не бачить кольору, —
     * і саме в ту мить, коли різниця важить найбільше.
     */
    @Test
    fun `аварійний вихід відрізняється від входу силуетом, а не лише кольором`() {
        assertNotEquals(
            EntranceSign.glyph(EntranceKind.MAIN),
            EntranceSign.glyph(EntranceKind.EMERGENCY),
        )
        assertNotEquals(
            EntranceSign.tint(EntranceKind.MAIN),
            EntranceSign.tint(EntranceKind.EMERGENCY),
        )
        assertEquals(PhantomGlyph.EXIT, EntranceSign.glyph(EntranceKind.EMERGENCY))
    }

    /** Метро — місце призначення, а не двері будинку, тож і силует власний. */
    @Test
    fun `вхід у метро носить знак метро`() {
        assertEquals(PhantomGlyph.METRO, EntranceSign.glyph(EntranceKind.SUBWAY))
        assertNotEquals(
            EntranceSign.glyph(EntranceKind.GENERIC),
            EntranceSign.glyph(EntranceKind.SUBWAY),
        )
    }

    @Test
    fun `звичайні двері з пандусом читаються як безбарʼєрні`() {
        assertEquals(
            EntranceKind.ACCESSIBLE,
            EntranceSign.kindOf(entrance(EntranceKind.GENERIC, wheelchair = true)),
        )
        assertEquals(
            EntranceKind.ACCESSIBLE,
            EntranceSign.kindOf(entrance(EntranceKind.SERVICE, wheelchair = true)),
        )
    }

    /**
     * Доступність не зʼїдає роду, який людина шукає першим: «головний вхід» і
     * «вхід у метро» лишаються собою, навіть маючи пандус.
     */
    @Test
    fun `пандус не підміняє головного входу й метро`() {
        assertEquals(
            EntranceKind.MAIN,
            EntranceSign.kindOf(entrance(EntranceKind.MAIN, wheelchair = true)),
        )
        assertEquals(
            EntranceKind.SUBWAY,
            EntranceSign.kindOf(entrance(EntranceKind.SUBWAY, wheelchair = true)),
        )
        assertEquals(
            EntranceKind.EMERGENCY,
            EntranceSign.kindOf(entrance(EntranceKind.EMERGENCY, wheelchair = true)),
        )
    }

    /** Кожен рід має свій тон — інакше диски зливаються в один. */
    @Test
    fun `тони родів не збігаються там, де рід різний`() {
        val distinct = EntranceKind.entries
            .filter { it != EntranceKind.SERVICE }
            .map { EntranceSign.tint(it) }
        assertEquals(
            "два роди входу носять один тон: $distinct",
            distinct.size,
            distinct.toSet().size,
        )
    }

    /**
     * Знак живе в тому ж полі, що й решта родини: диск входу — 26dp, той самий,
     * що в піна місця, і гліф, який виліз за 2..22, ліг би на обідок.
     */
    @Test
    fun `знак виходу вкладається в живе поле сітки`() {
        val pen = record(PhantomGlyph.EXIT)
        assertTrue("x ${pen.minX}..${pen.maxX}", pen.minX >= 2f && pen.maxX <= 22f)
        assertTrue("y ${pen.minY}..${pen.maxY}", pen.minY >= 2f && pen.maxY <= 22f)
    }
}
