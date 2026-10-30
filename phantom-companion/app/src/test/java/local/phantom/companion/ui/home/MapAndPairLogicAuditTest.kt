package local.phantom.companion.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MapAndPairLogicAuditTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root not found") as Nothing
    }

    private fun code(rel: String): String =
        File(repoRoot(), rel).readLines()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")

    private fun string(dir: String, file: String, name: String): String? {
        val xml = File(repoRoot(), "app/src/main/res/$dir/$file").readText()
        return Regex("""<string name="$name">([^<]*)</string>""").find(xml)?.groupValues?.get(1)
    }

    @Test
    fun `onDeleteSavedPlace branches on removeByLabel result`() {
        val tab = code("app/src/main/java/local/phantom/companion/ui/home/MapTab.kt")
        assertTrue(
            "MapTab must resolve R.string.map_poi_delete_failed",
            tab.contains("val poiDeleteFailedMsg = stringResource(R.string.map_poi_delete_failed)"),
        )
        val deleteBlock = tab.substringAfter("onDeleteSavedPlace = { poi ->")
            .substringBefore("onRenameSavedPlace =")
        assertTrue(
            "onDeleteSavedPlace must capture removeByLabel result",
            deleteBlock.contains("val removed = runCatching") &&
                deleteBlock.contains("container.savedPlaceStore.removeByLabel(poi.name)"),
        )
        assertTrue(
            "onDeleteSavedPlace must branch on removed",
            deleteBlock.contains("if (removed)") &&
                deleteBlock.contains("onPoiConfirm(poiDeletedMsg, ConfirmKind.Success)") &&
                deleteBlock.contains("onPoiConfirm(poiDeleteFailedMsg, ConfirmKind.Refusal)"),
        )

        assertEquals("could not delete", string("values", "strings.xml", "map_poi_delete_failed"))
        assertEquals("Не вдалося видалити", string("values-uk", "strings.xml", "map_poi_delete_failed"))
        assertEquals("Nepodařilo se smazat", string("values-cs", "strings.xml", "map_poi_delete_failed"))
    }

    @Test
    fun `MapTab wires onAddPerson to scanner route`() {
        val tab = code("app/src/main/java/local/phantom/companion/ui/home/MapTab.kt")
        assertTrue(
            "rememberMapViewModel must wire onAddPerson to Routes.Scan.path",
            tab.contains("onAddPerson = { container.events.routeRequest.value = local.phantom.companion.navigation.Routes.Scan.path }"),
        )
    }

    @Test
    fun `look_unpair_body promises a new code, never the same QR`() {
        val en = string("values", "strings_look_profile_hub.xml", "look_unpair_body").orEmpty()
        val uk = string("values-uk", "strings_look_profile_hub.xml", "look_unpair_body").orEmpty()
        val cs = string("values-cs", "strings_look_profile_hub.xml", "look_unpair_body").orEmpty()

        assertEquals("Reconnect with a new code", en)
        assertEquals("Підключити знову — новим кодом", uk)
        assertEquals("Znovu připojit novým kódem", cs)

        assertFalse(en, en.contains("same QR", ignoreCase = true))
        assertFalse(uk, uk.contains("тим самим", ignoreCase = true))
        assertFalse(cs, cs.contains("stejn", ignoreCase = true))
    }
}
