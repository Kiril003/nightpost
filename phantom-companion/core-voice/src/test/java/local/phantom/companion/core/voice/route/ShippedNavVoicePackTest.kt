package local.phantom.companion.core.voice.route

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ShippedNavVoicePackTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core-voice").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with core-voice/ not found") as Nothing
    }

    private val packDir: File get() = File(repoRoot(), "core-voice/src/main/assets/navvoice")

    @Test
    fun `every clip the manifest names is shipped and playable`() {
        val manifest = File(packDir, "manifest.json")
        assertTrue("no manifest at ${manifest.path}", manifest.isFile)

        val entries = Json { ignoreUnknownKeys = true }
            .parseToJsonElement(manifest.readText()).jsonArray
        assertTrue("manifest is empty", entries.isNotEmpty())

        val missing = mutableListOf<String>()
        val tooSmall = mutableListOf<String>()
        entries.forEach { el ->
            val o = el.jsonObject
            val key = o["key"]?.jsonPrimitive?.content ?: return@forEach
            val name = o["file"]?.jsonPrimitive?.content ?: return@forEach
            val f = File(packDir, name)
            when {
                !f.isFile -> missing += "$key -> $name"
                f.length() <= 500L -> tooSmall += "$key -> $name (${f.length()} B)"
            }
        }
        assertEquals("manifest names clips that are not shipped: $missing", emptyList<String>(), missing)
        assertEquals("clips too small to be speech: $tooSmall", emptyList<String>(), tooSmall)
    }

    @Test
    fun `the turns a driver cannot miss are all baked`() {
        val manifest = File(packDir, "manifest.json")
        val keys = Json { ignoreUnknownKeys = true }
            .parseToJsonElement(manifest.readText()).jsonArray
            .mapNotNull { it.jsonObject["key"]?.jsonPrimitive?.content }
            .map { BakedNavVoice.normalizeKey(it) }
            .toSet()

        val required = listOf(
            "Поверніть ліворуч",
            "Поверніть праворуч",
            "Через 200 метрів поверніть ліворуч",
            "Через 200 метрів поверніть праворуч",
        )
        val absent = required.filterNot { BakedNavVoice.normalizeKey(it) in keys }
        assertEquals("core maneuver cues missing from the pack: $absent", emptyList<String>(), absent)
    }
}
