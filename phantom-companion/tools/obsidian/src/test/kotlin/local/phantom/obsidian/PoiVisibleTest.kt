package local.phantom.obsidian

import org.junit.Assert.assertTrue
import org.junit.Test

class PoiVisibleTest {
    @Test
    fun poiNamesAppearFromZoom15() {
        for (v in Variant.entries) {
            val label = (StyleCompiler.compile(v).at("layers") as Json.Arr).items.map { it as Json.Obj }
                .first { (it.at("id") as Json.Str).value == ID_LABEL_POI }
            assertTrue("${v.slug}: підпис місць надто пізно", (label.at("minzoom") as Json.Num).value <= 15.0)
        }
    }
}
