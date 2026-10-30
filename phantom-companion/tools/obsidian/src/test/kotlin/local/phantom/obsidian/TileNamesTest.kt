package local.phantom.obsidian

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Свіжа установка поза пакунком України, або коли Overpass лежить, мусить
 * назвати місця й зупинки з того тайла, що вже малює будівлі (вердикт власника
 * 23.10: «на мапі лише 3D-будинки»).
 */
class TileNamesTest {

    private val assets = File(System.getProperty("obsidian.assets")).parentFile

    private fun layersOf(v: Variant) = (StyleCompiler.compile(v).at("layers") as Json.Arr).items.map { it as Json.Obj }

    private fun onPoi(v: Variant) = layersOf(v).filter {
        (it.at("type") as Json.Str).value == "symbol" && (it.at("source-layer") as? Json.Str)?.value == SL_POI
    }

    private fun mentions(node: Json?, word: String): Boolean = when (node) {
        is Json.Str -> node.value == word
        is Json.Arr -> node.items.any { mentions(it, word) }
        is Json.Obj -> node.entries.values.any { mentions(it, word) }
        else -> false
    }

    @Test
    fun `every variant names transit stops and places from the tile`() {
        for (v in Variant.entries) {
            val byId = onPoi(v).associateBy { (it.at("id") as Json.Str).value }
            val transit = byId[ID_LABEL_TRANSIT]
            assertTrue("${v.slug}: зупинки транспорту без підпису", transit != null)
            for (cls in listOf("railway", "bus")) {
                assertTrue("${v.slug}: фільтр зупинок пропускає «$cls»", mentions(transit!!.at("filter"), cls))
            }
            assertTrue("${v.slug}: зупинки з'являються пізніше z15", (transit!!.at("minzoom") as Json.Num).value <= 15.0)
            val places = byId[ID_LABEL_POI]
            assertTrue("${v.slug}: місця з тайла без підпису", places != null)
            assertTrue("${v.slug}: місця з'являються пізніше z16", (places!!.at("minzoom") as Json.Num).value <= 16.0)
        }
    }

    @Test
    fun `the baked asset carries the same names the compiler writes`() {
        for (v in Variant.entries) {
            val text = File(System.getProperty("obsidian.assets"), "${v.slug}.json").readText()
            for (id in listOf(ID_LABEL_POI, ID_LABEL_TRANSIT)) {
                assertTrue("${v.slug}.json без $id — телефон отримає старий стиль", text.contains("\"$id\""))
            }
        }
    }

    @Test
    fun `every symbol layer resolves its glyphs inside the package and asks for no sprite`() {
        val packaged = setOf("0-255.pbf", "1024-1279.pbf")
        for (v in Variant.entries) {
            val style = StyleCompiler.compile(v)
            assertEquals("${v.slug}: спрайт тягнувся б із мережі", null, style.at("sprite"))
            val glyphs = (style.at("glyphs") as Json.Str).value
            assertTrue("${v.slug}: гліфи не з пакунка — $glyphs", glyphs.startsWith("asset://fonts/"))
            for (l in layersOf(v)) {
                val layout = l.at("layout") as? Json.Obj ?: continue
                assertEquals("${l.at("id")}: картинка без спрайта", null, layout.at("icon-image"))
                val font = layout.at("text-font") as? Json.Arr ?: continue
                for (stack in font.items.map { (it as Json.Str).value }) {
                    val dir = File(assets, "fonts/$stack")
                    for (range in packaged) {
                        assertTrue("${l.at("id")}: нема ${dir.name}/$range у пакунку", File(dir, range).isFile)
                    }
                }
            }
        }
    }
}
