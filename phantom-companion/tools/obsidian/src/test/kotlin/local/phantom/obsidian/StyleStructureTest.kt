package local.phantom.obsidian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Структура випеченого стилю — те, за що на пристрої тримаються контрибуції.
 *
 * Ці перевірки дешеві на JVM і безцінні на телефоні: зламаний якір не падає
 * збіркою і не кидає виняток — він просто малює маршрут під землею.
 */
class StyleStructureTest {

    private fun style(v: Variant) = StyleCompiler.compile(v)

    private fun layersOf(v: Variant) = (style(v).at("layers") as Json.Arr)
        .items.map { it as Json.Obj }

    private fun id(l: Json.Obj) = (l.at("id") as Json.Str).value

    private fun type(l: Json.Obj) = (l.at("type") as Json.Str).value

    @Test
    fun `layer ids are unique, prefixed and shared by every variant`() {
        val night = layersOf(Variant.NIGHT).map(::id)

        for (v in Variant.entries) {
            assertEquals("${v.slug} розійшовся з ніччю складом шарів", night, layersOf(v).map(::id))
        }
        assertEquals("айді шару повторюється", night.size, night.toSet().size)
        assertEquals(
            "шар поза префіксом контракту",
            emptyList<String>(),
            night.filterNot { it.startsWith(ID_PREFIX) },
        )
        assertTrue("стиль підозріло малий: ${night.size} шарів", night.size >= 45)
    }

    @Test
    fun `the ground is the first layer and the only background`() {
        for (v in Variant.entries) {
            val layers = layersOf(v)
            assertEquals("перший шар більше не тло — якір addLayerAbove(first) зламано", ID_GROUND, id(layers.first()))
            assertEquals("background має бути рівно один", 1, layers.count { type(it) == "background" })
        }
    }

    @Test
    fun `symbols form one unbroken tail and start at the documented anchor`() {
        for (v in Variant.entries) {
            val types = layersOf(v).map(::type)
            val first = types.indexOf("symbol")
            assertTrue("у стилі немає жодного символьного шару", first >= 0)
            assertTrue(
                "символьний шар опинився серед заливок — якір «під перший символ» більше не значить «під усі підписи»",
                types.drop(first).all { it == "symbol" },
            )
            assertEquals(ID_FIRST_SYMBOL, id(layersOf(v)[first]))
        }
    }

    @Test
    fun `every data layer hangs off the one vector source`() {
        val sources = style(Variant.NIGHT).at("sources") as Json.Obj
        assertEquals("джерело мусить бути рівно одне", setOf(SOURCE_ID), sources.entries.keys)
        val src = sources.at(SOURCE_ID) as Json.Obj
        assertEquals(jstr("vector"), src.at("type"))
        assertEquals(jstr(TILEJSON_URL), src.at("url"))
        assertTrue(
            "з асета зникла атрибуція OSM/OpenMapTiles",
            (src.at("attribution") as Json.Str).value.contains("OpenStreetMap"),
        )
        assertEquals(jstr(GLYPHS_URL), style(Variant.NIGHT).at("glyphs"))

        for (l in layersOf(Variant.NIGHT)) {
            if (type(l) == "background") continue
            assertEquals("шар ${id(l)} висить не на нашому джерелі", jstr(SOURCE_ID), l.at("source"))
            assertTrue("шар ${id(l)} без source-layer", l.at("source-layer") is Json.Str)
        }
    }

    @Test
    fun `text layers only ever ask for the font the glyph server hosts`() {
        for (v in Variant.entries) {
            for (l in layersOf(v)) {
                val layout = l.at("layout") as? Json.Obj ?: continue
                if (layout.at("text-field") == null) continue
                assertEquals(
                    "шар ${id(l)} просить інший стек шрифту — гліфи 404, підпис мовчки без тексту",
                    FONT_STACK,
                    layout.at("text-font"),
                )
            }
        }
    }

    @Test
    fun `every colour in the baked style comes from the tokens`() {
        for (v in Variant.entries) {
            val allowed = StyleCompiler.ink(v).all
            val used = colours(style(v))
            assertTrue("у стилі не знайдено кольорів", used.isNotEmpty())
            assertEquals(
                "колір у стилі ${v.slug} не виводиться з токенів",
                emptySet<String>(),
                used - allowed,
            )
        }
    }

    /**
     * Вираз зуму дозволений тільки на верхньому рівні властивості. `match`, що
     * віддає `interpolate` по зуму, style-spec відкидає — і MapLibre викидає
     * ВЕСЬ шар мовчки, лишаючи дірку в мережі доріг. Саме на цьому спіткнувся
     * перший підхід до дорожніх смуг.
     */
    @Test
    fun `zoom never hides inside another expression`() {
        for (v in Variant.entries) {
            for (l in layersOf(v)) {
                for (bag in listOfNotNull(l.at("layout") as? Json.Obj, l.at("paint") as? Json.Obj)) {
                    for ((key, value) in bag.entries) {
                        assertTrue("${id(l)} → $key: вираз зуму вкладений", zoomIsTopLevel(value))
                    }
                }
                (l.at("filter"))?.let {
                    assertTrue("${id(l)} → filter: фільтр не має права питати зум", !mentionsZoom(it))
                }
            }
        }
    }

    @Test
    fun `the anchors the app reaches for are all present`() {
        val ids = layersOf(Variant.NIGHT).map(::id)
        assertTrue(ID_GROUND in ids)
        assertTrue(ID_FIRST_SYMBOL in ids)
        assertTrue("плаский слід будівлі зник — «показати будівлі» нічого не покаже", ID_BUILDING in ids)
        // Тап по землі відкидає шари з «boundary» в айді: кордон — не місце.
        val admin = layersOf(Variant.NIGHT).filter {
            (it.at("source-layer") as? Json.Str)?.value == SL_BOUNDARY
        }
        assertTrue("кордонів у стилі нема", admin.isNotEmpty())
        assertEquals(
            "адмінкордон без «boundary» в айді почне ловити палець",
            emptyList<String>(),
            admin.map(::id).filterNot { it.contains("boundary") },
        )
    }

    @Test
    fun `the paid volume stays out of the free style`() {
        for (v in Variant.entries) {
            assertEquals(
                "екструзія в стилі — об'єм лишається платною контрибуцією",
                emptyList<String>(),
                layersOf(v).filter { type(it) == "fill-extrusion" }.map(::id),
            )
        }
    }

    /* ── Обхід дерева ────────────────────────────────────────────────────── */

    private fun colours(node: Json): Set<String> = when (node) {
        is Json.Str -> if (node.value.startsWith("#") || node.value.startsWith("rgba(")) setOf(node.value) else emptySet()
        is Json.Arr -> node.items.flatMap(::colours).toSet()
        is Json.Obj -> node.entries.values.flatMap(::colours).toSet()
        else -> emptySet()
    }

    private fun head(node: Json): String? =
        ((node as? Json.Arr)?.items?.firstOrNull() as? Json.Str)?.value

    private fun mentionsZoom(node: Json): Boolean = when (node) {
        is Json.Arr -> head(node) == "zoom" || node.items.any(::mentionsZoom)
        is Json.Obj -> node.entries.values.any(::mentionsZoom)
        else -> false
    }

    private fun zoomIsTopLevel(node: Json): Boolean {
        val op = head(node)
        if (op == "interpolate" || op == "step") {
            // `interpolate` тримає вхід третім аргументом (після кривої), `step` — другим.
            val at = if (op == "interpolate") 2 else 1
            val args = (node as Json.Arr).items
            if (head(args.getOrNull(at) ?: jnum(0)) == "zoom") {
                return args.filterIndexed { i, _ -> i != at }.none(::mentionsZoom)
            }
        }
        return !mentionsZoom(node)
    }
}
