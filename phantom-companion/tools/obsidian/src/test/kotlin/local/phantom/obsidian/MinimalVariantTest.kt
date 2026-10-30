package local.phantom.obsidian

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Мінімал мусить бути ЧЕСНИМ мінімумом.
 *
 * Рік поспіль рейка «МІНІМАЛ» показувала денний стиль — оператор натискав і
 * нічого не змінювалось. Зробити навпаки так само легко: назвати мінімалом
 * стиль, з якого вимели половину змісту, і продати втрату як спокій. Тут
 * прибито обидві межі — що саме відступає, і що не сміє.
 */
class MinimalVariantTest {

    private val day = StyleCompiler.ink(Variant.DAY)
    private val min = StyleCompiler.ink(Variant.MINIMAL)

    /** Ролі, які мінімал стишує. */
    private fun hushed(i: CartoInk) = mapOf(
        "ліс" to i.landForest,
        "лука" to i.landGrass,
        "парк" to i.landPark,
        "поле" to i.landFarmland,
        "скеля" to i.landRock,
        "пісок" to i.landSand,
        "лід" to i.landIce,
        "болото" to i.landWetland,
        "житлова зона" to i.zoneResidential,
        "промзона" to i.zoneIndustrial,
        "громадська зона" to i.zoneCivic,
        "будівля" to i.building,
        "ребро будівлі" to i.buildingEdge,
    )

    /** Ролі, заради яких мінімал і беруть — вони стоять на повну. */
    private fun kept(i: CartoInk) = mapOf(
        "магістраль" to i.roadMotorway,
        "житлова вулиця" to i.roadMinor,
        "стежка" to i.roadPath,
        "обводка" to i.roadCasing,
        "обводка магістралі" to i.roadCasingMajor,
        "вода" to i.water,
        "глибока вода" to i.waterDeep,
        "річка" to i.waterway,
        "залізниця" to i.rail,
        "кордон" to i.boundaryCountry,
        "підпис" to i.labelInk,
        "підпис вулиці" to i.labelRoad,
        "підпис води" to i.labelWater,
        "ореол" to i.labelHalo,
    )

    @Test
    fun `minimal stands on the day ground, not a fourth one`() {
        assertEquals(Polarity.DAY, Variant.MINIMAL.polarity)
        assertEquals("мінімал завів власну землю — це вже не той самий стиль", day.ground, min.ground)
    }

    @Test
    fun `the net, the water and the labels are untouched to the byte`() {
        for ((name, colour) in kept(day)) {
            assertEquals("$name у мінімалі змінився — стишили те, заради чого його вмикають", colour, kept(min)[name])
        }
    }

    @Test
    fun `the land steps back — every muted role loses contrast against the ground`() {
        for ((name, colour) in hushed(day)) {
            val before = contrast(colour, day.ground)
            val after = contrast(hushed(min)[name]!!, min.ground)
            assertTrue("$name не стишився: $before:1 → $after:1", after < before)
        }
    }

    /** Стишити — не стерти: ліс, який зрівнявся з землею, це не мінімал, це
     *  порожня мапа. Поріг береться від сходинки каналу, а не від смаку. */
    @Test
    fun `nothing is muted all the way into the ground`() {
        for ((name, colour) in hushed(min)) {
            val ratio = contrast(colour, min.ground)
            assertTrue("$name зник у землю", colour != min.ground)
            assertTrue("$name на $ratio:1 — його вже нема", ratio > 1.02)
        }
    }

    /**
     * Найгучніше на мінімалі — мережа й вода, і це вимірюване твердження, а не
     * настрій. Поки воно стоїть, око читає маршрут першим.
     */
    @Test
    fun `the road casing and the water are the loudest things left`() {
        val floor = minOf(contrast(min.roadCasing, min.ground), contrast(min.water, min.ground))
        val loudest = hushed(min).maxByOrNull { contrast(it.value, min.ground) }!!
        assertTrue(
            "«${loudest.key}» (${contrast(loudest.value, min.ground)}:1) перекрикує мережу ($floor:1)",
            contrast(loudest.value, min.ground) < floor,
        )
    }

    /**
     * Тінь будівлі несе альфу як частину значення. Суміш із непрозорою землею
     * без цієї осторги зробила б її щільнішою — мінімал додав би затемнення.
     */
    @Test
    fun `muting never touches opacity`() {
        for (pair in listOf(day.buildingShadow to min.buildingShadow, day.wetlandHatch to min.wetlandHatch)) {
            val (d, m) = pair
            assertEquals("мінімал змінив щільність, а не гучність: $d → $m", alphaOf(d), alphaOf(m), 0.001)
            assertTrue("роль не стишено взагалі: $d", d != m)
        }
    }

    /** Дві ролі, що злилися в один колір, — це шар, який мовчки зник. */
    @Test
    fun `no two roles collapse into one colour that the day kept apart`() {
        assertEquals(
            "мінімал загубив колір — дві ролі стали одним шаром",
            day.all.size,
            min.all.size,
        )
    }

    /** Назви місць із тайла — лише текст: крапка на мапі одна, і це наш пін. */
    @Test
    fun `no variant ever draws its own POI dot`() {
        for (v in Variant.entries) {
            val dots = (StyleCompiler.compile(v).at("layers") as Json.Arr).items
                .map { it as Json.Obj }
                .filter { (it.at("source-layer") as? Json.Str)?.value?.startsWith("poi") == true }
                .filter { (it.at("type") as Json.Str).value != "symbol" || (it.at("layout") as Json.Obj).at("icon-image") != null }
                .map { (it.at("id") as Json.Str).value }
            assertEquals("${v.slug} малює власні POI-крапки — оверлеї застосунку тепер друга крапка", emptyList<String>(), dots)
        }
    }

    /* ── Арифметика перевірки ────────────────────────────────────────────── */

    private fun channel(c: String, i: Int): Int {
        require(c.startsWith("#")) { "очікується #RRGGBB, отримано $c" }
        return c.substring(1 + i * 2, 3 + i * 2).toInt(16)
    }

    private fun luminance(c: String): Double {
        val lin = (0..2).map { i ->
            val v = channel(c, i) / 255.0
            if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2]
    }

    private fun contrast(a: String, b: String): Double {
        val x = luminance(a) + 0.05
        val y = luminance(b) + 0.05
        return if (x > y) x / y else y / x
    }
}
