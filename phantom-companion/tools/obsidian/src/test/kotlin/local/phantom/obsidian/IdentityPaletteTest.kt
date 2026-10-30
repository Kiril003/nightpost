package local.phantom.obsidian

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * Мапа говорить тією ж мовою, що й решта продукту (`docs/design/system/tokens.json`).
 *
 * До 02.10 земля й чорнило мапи жили власними значеннями чернетки V0, а вода й
 * парк підіймались до сигнальних `info`/`success` — неонова синь і зелень під
 * спокійним інтерфейсом. Тут прибито три речі: земляна родина — це рівно токени
 * дизайн-системи; на землі немає неону; бурштин на дорогах — лише тінь.
 */
class IdentityPaletteTest {

    private val system: String by lazy {
        val file = File(System.getProperty("phantom.design.tokens") ?: "../../../docs/design/system/tokens.json")
        assertTrue("не знайдено tokens.json дизайн-системи: ${file.absolutePath}", file.isFile)
        file.readText()
    }

    private fun systemColour(polarity: String, name: String): String {
        val block = system.substringAfter("\"$polarity\": {").substringBefore("}\n    }")
        val hit = Regex("\"${Regex.escape(name)}\":\\s*\\{\\s*\"value\":\\s*\"(#[0-9A-Fa-f]{6})").find(block)
        return hit?.groupValues?.get(1) ?: error("у tokens.json немає color.$polarity.$name")
    }

    @Ignore("Палітра np (b19) тепла, а DuskPolarity і драбини мапи вимагають холодної ночі: рішення дизайну після 12.10")
    @Test
    fun `the map earth is the design system, both polarities`() {
        val pairs = listOf(
            "ph.color.ground" to "canvas",
            "ph.color.surface" to "surface",
            "ph.color.surface-raised" to "raised",
            "ph.color.border" to "line-strong",
            "ph.color.ink" to "text",
            "ph.color.ink-muted" to "text-2",
            "ph.color.ink-faint" to "text-3",
        )
        val drift = pairs.flatMap { (map, sys) ->
            listOf(Polarity.NIGHT to "night", Polarity.DAY to "day").mapNotNull { (pol, key) ->
                val want = systemColour(key, sys)
                val got = Tokens.color(map, pol)
                if (got.equals(want, ignoreCase = true)) null else "$map ($key): мапа $got, система $sys $want"
            }
        }
        assertEquals("земля мапи розійшлась із дизайн-системою", emptyList<String>(), drift)
    }

    /** Розкид каналів — груба, але чесна міра «наскільки це колір, а не сірий».
     *  Стара нічна вода #2F5F8B мала 92, парк #1E4532 — 39 на майже чорному. */
    private fun chroma(c: String): Int {
        val ch = (0..2).map { c.substring(1 + it * 2, 3 + it * 2).toInt(16) }
        return ch.max() - ch.min()
    }

    @Test
    fun `nothing on the ground glows — water, green and civic land stay muted`() {
        for (v in Variant.entries) {
            val ink = StyleCompiler.ink(v)
            val family = mapOf(
                "вода" to ink.water, "глибока вода" to ink.waterDeep, "річка" to ink.waterway,
                "берег" to ink.waterShore, "ліс" to ink.landForest, "парк" to ink.landPark,
                "лука" to ink.landGrass, "громадська земля" to ink.zoneCivic, "болото" to ink.landWetland,
            )
            for ((name, colour) in family) {
                assertTrue("${v.slug}: $name $colour — неон на землі (розкид ${chroma(colour)})", chroma(colour) <= 56)
            }
        }
    }

    @Test
    fun `amber never paints a road — at most a warm shade on the motorway`() {
        for (v in Variant.entries) {
            val ink = StyleCompiler.ink(v)
            val ranks = listOf(
                ink.roadMotorway, ink.roadTrunk, ink.roadPrimary, ink.roadSecondary,
                ink.roadTertiary, ink.roadMinor, ink.roadService, ink.roadPath,
            )
            for (colour in ranks) {
                assertTrue("${v.slug}: дорога $colour забарвлена (розкид ${chroma(colour)})", chroma(colour) <= 32)
            }
        }
    }

    private fun symbols(v: Variant) = (StyleCompiler.compile(v).at("layers") as Json.Arr).items
        .map { it as Json.Obj }
        .filter { (it.at("type") as Json.Str).value == "symbol" }

    @Test
    fun `every halo is the canvas, soft-edged, and between 1_2 and 1_5 px`() {
        for (v in Variant.entries) {
            val ground = StyleCompiler.ink(v).ground
            for (l in symbols(v)) {
                val id = (l.at("id") as Json.Str).value
                val paint = l.at("paint") as Json.Obj
                assertEquals("${v.slug} → $id: ореол не колір землі", jstr(ground), paint.at("text-halo-color"))
                assertEquals("${v.slug} → $id: ореол без розмиття читається контуром", jnum(0.5), paint.at("text-halo-blur"))
                val width = (paint.at("text-halo-width") as Json.Num).value
                assertTrue("${v.slug} → $id: ореол $width px", width in 1.2..1.5)
            }
        }
    }

    @Test
    fun `only place names are tracked, and only a little`() {
        val places = setOf(
            "obsidian-label-suburb", "obsidian-label-village", "obsidian-label-town",
            "obsidian-label-city", "obsidian-label-country",
        )
        for (l in symbols(Variant.NIGHT)) {
            val id = (l.at("id") as Json.Str).value
            val tracking = ((l.at("layout") as Json.Obj).at("text-letter-spacing") as? Json.Num)?.value
            if (id in places) {
                assertTrue("$id: розрядка $tracking поза 0,02–0,1", tracking != null && tracking in 0.02..0.1)
            } else {
                assertTrue("$id: розрядка на не-місці ($tracking)", tracking == null)
            }
        }
    }
}
