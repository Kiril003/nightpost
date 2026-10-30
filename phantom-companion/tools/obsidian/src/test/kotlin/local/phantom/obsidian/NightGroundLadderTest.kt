package local.phantom.obsidian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Нічна драбина ґрунту — вирок власника 01.09, записаний як храповик.
 *
 * «Дороги — яскраво-сині вени на майже чорному, будівлі сірі плями. Це не нічна
 * мапа, це схема.» Виміряно 03.09 проти землі: квартал 1,03:1, парк 1,17, ліс
 * 1,25, вода 1,40; будівля 1,91 проти кварталу; магістраль 15,5. Ґрунт без
 * структури, а дороги — єдина драбина: звідси «схема».
 *
 * Контраст рахується ІЗ ТОКЕНІВ через [CartoInk], не з випеченого JSON: JSON
 * печеться і може відстати, а токени — джерело. Формула — WCAG, та сама, що в
 * `LegibilityTest`. На числах до 03.09 тут червоніють десять перевірок; на
 * нових — жодна.
 *
 * Межа чесна: це вирази, не пікселі. І не пелена `SolarSky` (52 % синьо-чорного
 * поверх усієї мапи вночі), під якою будь-яка драбина стискається до 1,1–1,7:1.
 */
class NightGroundLadderTest {

    private val night = StyleCompiler.ink(Variant.NIGHT)
    private val dusk = StyleCompiler.ink(Variant.DUSK)

    private val moods = listOf("ніч" to night, "сутінки" to dusk)

    private data class Floor(val name: String, val got: Double, val floor: Double)

    /** Підлоги ґрунту — однакові для ночі й сутінків: земля тепла, але не інша. */
    private fun groundFloors(ink: CartoInk) = listOf(
        Floor("земля→квартал", contrast(ink.zoneResidential, ink.ground), 1.25),
        Floor("земля→парк", contrast(ink.landPark, ink.ground), 1.60),
        Floor("земля→ліс", contrast(ink.landForest, ink.ground), 1.60),
        Floor("земля→вода", contrast(ink.water, ink.ground), 2.50),
        Floor("квартал→будівля", contrast(ink.building, ink.zoneResidential), 2.40),
        Floor("будівля→ребро", contrast(ink.buildingEdge, ink.building), 1.40),
    )

    /**
     * Драбина доріг проти кварталу. Сутінки тримають той самий РАНГ на 85 %
     * підлоги: їхня земля світліша (L 0,0105 проти 0,0043), і та сама жила над
     * нею дає менше відношення — це тепло, а не втрачений щабель.
     */
    private fun roadFloors(ink: CartoInk, soft: Double) = listOf(
        Floor("проїзд→квартал", contrast(ink.roadService, ink.zoneResidential), 2.4 * soft),
        Floor("вулиця→квартал", contrast(ink.roadMinor, ink.zoneResidential), 3.1 * soft),
        Floor("tertiary→квартал", contrast(ink.roadTertiary, ink.zoneResidential), 4.3 * soft),
        Floor("secondary→квартал", contrast(ink.roadSecondary, ink.zoneResidential), 5.7 * soft),
        Floor("primary→квартал", contrast(ink.roadPrimary, ink.zoneResidential), 7.5 * soft),
    )

    /** Підкладка підпису — його ореол (земля); назва води лежить ще й НА воді. */
    private fun labelFloors(ink: CartoInk) = listOf(
        Floor("підпис міста→ореол", contrast(ink.labelInk, ink.labelHalo), 4.5),
        Floor("підпис району→ореол", contrast(ink.labelMuted, ink.labelHalo), 4.5),
        Floor("підпис вулиці→ореол", contrast(ink.labelRoad, ink.labelHalo), 4.5),
        Floor("підпис води→ореол", contrast(ink.labelWater, ink.labelHalo), 4.5),
        Floor("номер будинку→ореол", contrast(ink.labelFaint, ink.labelHalo), 4.5),
        Floor("підпис води→вода", contrast(ink.labelWater, ink.water), 3.0),
    )

    @Test
    fun `the night ground has structure — block, park, forest and water each clear the floor`() {
        for ((mood, ink) in moods) assertClear(groundFloors(ink), mood)
    }

    @Test
    fun `the road ladder stands on the raised block and the motorway no longer blinds`() {
        assertClear(roadFloors(night, 1.0), "ніч")
        assertClear(roadFloors(dusk, 0.85), "сутінки")
        for ((mood, ink) in moods) {
            val motorway = contrast(ink.roadMotorway, ink.zoneResidential)
            assertTrue("$mood: магістраль $motorway:1 проти кварталу — знову сліпуча вена", motorway <= 10.0)
        }
    }

    @Test
    fun `labels keep four and a half to one on their own halo`() {
        for ((mood, ink) in moods) assertClear(labelFloors(ink), mood)
    }

    /** Ранг — це порядок світлот; жоден щабель не переганяє той, що над ним. */
    @Test
    fun `rank is monotone — nothing overtakes the rung above it`() {
        for ((mood, ink) in moods) {
            val roads = listOf(
                "стежка" to ink.roadPath, "проїзд" to ink.roadService, "вулиця" to ink.roadMinor,
                "tertiary" to ink.roadTertiary, "secondary" to ink.roadSecondary,
                "primary" to ink.roadPrimary, "trunk" to ink.roadTrunk, "магістраль" to ink.roadMotorway,
            )
            assertAscending(roads, mood)
            val spine = listOf(
                "земля" to ink.ground, "квартал" to ink.zoneResidential, "парк" to ink.landPark,
                "ліс" to ink.landForest, "вода" to ink.water, "глибока вода" to ink.waterDeep,
            )
            assertAscending(spine, mood)
            // Середні класи стоять між кварталом і парком — кожен окремо, порядок
            // між ними не закон: промзона й лука різняться відтінком, не вагою.
            val middle = listOf(
                "промзона" to ink.zoneIndustrial, "громадська земля" to ink.zoneCivic,
                "лука" to ink.landGrass, "поле" to ink.landFarmland, "болото" to ink.landWetland,
            )
            for ((name, colour) in middle) {
                assertTrue("$mood: $name впав під квартал", luma(colour) > luma(ink.zoneResidential))
                assertTrue("$mood: $name перекрикує парк", luma(colour) < luma(ink.landPark))
            }
        }
    }

    /**
     * Той самий доказ, що й для [CartoInk.paper], лише навпаки: підйом просить
     * тільки темна земля. День і мінімал стоять на нулі рівно — тому їхня
     * випічка не змінюється на жоден байт (це тримає `BakeDeterminismTest`).
     */
    @Test
    fun `only the dark ground asks for the lift — paper keeps its bytes`() {
        assertEquals("ніч не отримала підйому", 1.0, night.night, 0.0)
        assertEquals("сутінки не отримали підйому", 1.0, dusk.night, 0.0)
        assertEquals("денна земля отримала нічний підйом", 0.0, StyleCompiler.ink(Variant.DAY).night, 0.0)
        assertEquals("мінімал отримав нічний підйом", 0.0, StyleCompiler.ink(Variant.MINIMAL).night, 0.0)
    }

    /* ── Арифметика перевірки ────────────────────────────────────────────── */

    private fun assertClear(floors: List<Floor>, mood: String) {
        val red = floors.filter { it.got < it.floor }.map { "${it.name} ${it.got.short()}:1 < ${it.floor.short()}" }
        assertEquals("$mood: підлоги драбини порушено", emptyList<String>(), red)
    }

    private fun assertAscending(ladder: List<Pair<String, String>>, mood: String) {
        for ((low, high) in ladder.zipWithNext()) {
            assertTrue(
                "$mood: ${low.first} (${low.second}) не темніша за ${high.first} (${high.second}) — ранг зламано",
                luma(low.second) < luma(high.second),
            )
        }
    }

    private fun Double.short() = (Math.round(this * 100.0) / 100.0).toString()

    private fun contrast(a: String, b: String): Double {
        val x = luma(a) + 0.05
        val y = luma(b) + 0.05
        return if (x > y) x / y else y / x
    }
}
