package local.phantom.obsidian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чотири вердикти гонтлету, записані як храповики.
 *
 * `StyleStructureTest` доводить, що стиль ЦІЛИЙ. Тут доводиться інше: що його
 * ще й ВИДНО. Кожен тест іменує критику, з якої він виріс, бо число без цієї
 * пам'яті наступний редактор поверне назад за півхвилини.
 *
 * Межа цих перевірок чесна: вони міряють вирази, а не пікселі. Останнє слово
 * лишається за оком на склі — три ті самі кадри, що бачила панель.
 */
class LegibilityTest {

    private fun layersOf(v: Variant) =
        (StyleCompiler.compile(v).at("layers") as Json.Arr).items.map { it as Json.Obj }

    private fun layer(v: Variant, id: String) = layersOf(v).first { (it.at("id") as Json.Str).value == id }

    private fun paintOf(l: Json.Obj) = l.at("paint") as Json.Obj

    private fun layoutOf(l: Json.Obj) = l.at("layout") as Json.Obj

    /* ── 1. Ієрархія доріг на z15 ────────────────────────────────────────── */

    @Test
    fun `the vehicle net keeps a visible rung between every class at z15`() {
        val ladder = listOf(
            "obsidian-road-motorway", "obsidian-road-trunk", "obsidian-road-primary",
            "obsidian-road-secondary", "obsidian-road-tertiary", "obsidian-road-minor",
            "obsidian-road-service",
        ).map { it to at(paintOf(layer(Variant.DAY, it)).at("line-width")!!, 15.0) }

        for ((a, b) in ladder.zipWithNext()) {
            assertTrue(
                "${a.first} (${a.second}) і ${b.first} (${b.second}) на z15 нерозрізненні — ієрархія знову пласка",
                a.second - b.second >= 0.6,
            )
        }
    }

    /**
     * Вердикт далекобійника: «все затоплено однаковим сірим пунктиром стежок».
     * Пішохідна мережа щільніша за проїжджу, тож рівність ширин означає не
     * рівність, а перемогу стежки.
     */
    @Test
    fun `the pedestrian web stays quieter than the residential street at z15`() {
        val path = layer(Variant.DAY, "obsidian-road-path")
        val width = at(paintOf(path).at("line-width")!!, 15.0)
        val alpha = at(paintOf(path).at("line-opacity")!!, 15.0)
        val minor = at(paintOf(layer(Variant.DAY, "obsidian-road-minor")).at("line-width")!!, 15.0)

        assertTrue("стежка на z15 непрозора — вона знову важить як дорога", alpha < 0.55)
        assertTrue("стежка ($width) не тонша за житлову вулицю ($minor)", width < minor * 0.5)
        assertTrue(
            "прозорість стежки не вертається на глибині — на z18 це відповідь на «як пройти»",
            at(paintOf(path).at("line-opacity")!!, 18.5) > 0.75,
        )
    }

    @Test
    fun `the residential street carries its casing well before the block zoom`() {
        val casing = layer(Variant.DAY, "obsidian-road-casing-minor")
        val minzoom = (casing.at("minzoom") as Json.Num).value
        assertTrue("обводка житлової вулиці з'являється аж на z$minzoom", minzoom <= 14.5)
        assertTrue(
            "обводка не ширша за саму заливку — вулиця не отримає краю",
            at(paintOf(casing).at("line-width")!!, 15.0, "minor") >
                at(paintOf(layer(Variant.DAY, "obsidian-road-minor")).at("line-width")!!, 15.0),
        )
    }

    /* ── 2. Зміст на z18 ─────────────────────────────────────────────────── */

    @Test
    fun `the street name repeats harder the closer the map gets`() {
        val spacing = layoutOf(layer(Variant.DAY, "obsidian-label-road")).at("symbol-spacing")!!
        val far = at(spacing, 12.0)
        val near = at(spacing, 19.0)
        assertTrue("крок повтору назви вулиці сталий — на z18 кадр лишиться без імені", near < far * 0.5)
        assertTrue("крок повтору став меншим за розмір самого підпису", near >= 60)
        assertTrue(
            "підпис вулиці не росте до глибокого зуму",
            at(layoutOf(layer(Variant.DAY, "obsidian-label-road")).at("text-size")!!, 18.5) >
                at(layoutOf(layer(Variant.DAY, "obsidian-label-road")).at("text-size")!!, 13.0),
        )
    }

    @Test
    fun `housenumbers are in the style and reach the zoom that asks for them`() {
        val house = layer(Variant.DAY, ID_FIRST_SYMBOL)
        assertEquals(jstr(SL_HOUSENUMBER), house.at("source-layer"))
        assertEquals(get("housenumber"), layoutOf(house).at("text-field"))
        assertTrue("шар номерів обрізано зверху — на z19 будинок знову без номера", house.at("maxzoom") == null)
        val minzoom = (house.at("minzoom") as Json.Num).value
        assertTrue("номери починаються аж на z$minzoom, тобто ніколи", minzoom <= 17.5)
    }

    /* ── 3. Пунктир, що не росте сосисками ───────────────────────────────── */

    @Test
    fun `every dash in the style is recomputed per zoom band`() {
        for (l in layersOf(Variant.NIGHT)) {
            val dash = (l.at("paint") as? Json.Obj)?.at("line-dasharray") ?: continue
            val id = (l.at("id") as Json.Str).value
            val items = (dash as Json.Arr).items
            assertEquals("$id: пунктир не за step() — на z18 він піде сосисками", jstr("step"), items.first())
            assertEquals("$id: step() пунктиру питає не зум", zoom(), items[1])

            val bands = items.drop(2).filterIsInstance<Json.Arr>().map { band ->
                // Смуга приїжджає в ["literal", […]]: голий масив у виході
                // step() рушій читає як вираз і викидає весь пунктир.
                assertEquals("$id: смуга пунктиру не обгорнута", jstr("literal"), band.items.first())
                (band.items[1] as Json.Arr).items.map { (it as Json.Num).value }
            }
            assertTrue("$id: у пунктирі менше двох смуг", bands.size >= 2)
            for ((wide, tight) in bands.zipWithNext()) {
                assertTrue(
                    "$id: смуга $tight не тісніша за $wide — штрих росте разом з лінією",
                    tight.sum() < wide.sum(),
                )
            }
        }
    }

    /* ── 4. Денна земля, яку видно ───────────────────────────────────────── */

    @Test
    fun `daylight land clears the paper it is printed on`() {
        val ink = CartoInk(Palette(Polarity.DAY))
        val floors = listOf(
            "ліс" to (ink.landForest to 1.60),
            "парк" to (ink.landPark to 1.45),
            "лука" to (ink.landGrass to 1.25),
            "поле" to (ink.landFarmland to 1.55),
            "болото" to (ink.landWetland to 1.40),
            "вода" to (ink.water to 1.60),
            "глибока вода" to (ink.waterDeep to 2.00),
            "обводка дороги" to (ink.roadCasing to 1.60),
            "обводка магістралі" to (ink.roadCasingMajor to 2.40),
        )
        for ((name, pair) in floors) {
            val (colour, floor) = pair
            val got = contrast(colour, ink.ground)
            assertTrue("$name на денній землі $got:1 — велосипедистка не відрізнить його від асфальту", got >= floor)
        }
        // Поле й ліс тепер однаково важкі — розрізняє їх тепла вісь, а не
        // світлота: країна полів не має виглядати країною лісів.
        assertTrue("поле перестало бути теплішим за ліс", warm(ink.landFarmland) - warm(ink.landForest) >= 12)
    }

    /**
     * Поріг не «майже нуль», а «менше однієї сходинки каналу»: найбільший крок
     * глибини — 1,15 × 255, тож нижче 3e-3 жоден канал не зрушить після
     * округлення, і нічна випічка лишається побайтово тією самою.
     */
    @Test
    fun `only paper asks for depth — the night keeps the ink it was praised for`() {
        assertTrue("нічна земля отримала денну поправку", CartoInk(Palette(Polarity.NIGHT)).paper < 3e-3)
        assertTrue("сутінки отримали денну поправку", CartoInk(Palette(Polarity.DUSK)).paper < 3e-3)
        assertTrue("денна земля перестала вимагати глибини", CartoInk(Palette(Polarity.DAY)).paper > 0.5)
    }

    private fun warm(c: String) = channel(c, 0) - channel(c, 2)

    private fun channel(c: String, i: Int) = c.substring(1 + i * 2, 3 + i * 2).toInt(16)

    /* ── Читання виразів ─────────────────────────────────────────────────── */

    private fun contrast(a: String, b: String): Double {
        val x = luma(a) + 0.05
        val y = luma(b) + 0.05
        return if (x > y) x / y else y / x
    }

    /** Значення `interpolate` по зуму в точці; [roadClass] розкриває `match`,
     *  який стоїть у стопах смугових обводок. */
    private fun at(node: Json, z: Double, roadClass: String? = null): Double {
        val items = (node as Json.Arr).items
        assertEquals("очікувався interpolate по зуму", jstr("interpolate"), items.first())
        assertEquals(zoom(), items[2])
        val base = ((items[1] as Json.Arr).items.getOrNull(1) as? Json.Num)?.value
        val stops = items.drop(3).chunked(2).map { (stop, v) -> (stop as Json.Num).value to resolve(v, roadClass) }

        if (z <= stops.first().first) return stops.first().second
        if (z >= stops.last().first) return stops.last().second
        val hi = stops.indexOfFirst { it.first >= z }
        val (z0, v0) = stops[hi - 1]
        val (z1, v1) = stops[hi]
        val span = z1 - z0
        val t = if (base == null) (z - z0) / span else
            (Math.pow(base, z - z0) - 1) / (Math.pow(base, span) - 1)
        return v0 + (v1 - v0) * t
    }

    private fun resolve(node: Json, roadClass: String?): Double {
        if (node is Json.Num) return node.value
        val items = (node as Json.Arr).items
        assertEquals("у стопі не число і не match", jstr("match"), items.first())
        val hit = roadClass?.let { cls ->
            items.drop(2).chunked(2).firstOrNull { pair ->
                pair.size == 2 && (pair[0] as? Json.Arr)?.items?.contains(jstr(cls)) == true
            }
        }
        return ((hit?.get(1) ?: items.last()) as Json.Num).value
    }
}
