package local.phantom.companion.ui.messenger

import java.io.File
import local.phantom.companion.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Сторожі сцени зустрічі, які не залежать від жодного класу мосту.
 *
 *  1. Серверні двері відчинено рішенням власника №6.4 (10.10) — і лише тому, що
 *     заяви підписує окремий ключ заяв. Червоніє, якщо підпис знову веде ключ
 *     месенджера або клієнт сцени обходить прапорець.
 *  2. Слова відмов існують у ОБОХ мовах і не містять заборонених у інтерфейсі слів.
 */
class SceneGuardsTest {

    @Test
    fun `двері закладів і балів відчинено рішенням 6-4 і лише з ключем заяв`() {
        assertTrue("SCENE_SERVER вимкнено попри рішення №6.4", BuildConfig.SCENE_SERVER)
        val container = source("data/AppContainer.kt")
        assertFalse("заяви знову підписує ключ месенджера", container.contains("proveClaim"))
        assertEquals(
            "кожен клієнт сцени мусить підписувати ключем заяв",
            listOf("claims::prove", "claims::prove"),
            Regex("""VenueClient\.deviceSigner\(([^)]*)\)""").findAll(container).map { it.groupValues[1] }.toList(),
        )
        assertTrue(
            "підписувач заяв узяв не ключ заяв",
            container.contains("ClaimSigner(key = { runCatching { claimKeys.get() }.getOrNull() })"),
        )
        assertFalse("PeerMesh знову підписує заяви сцени", source("data/PeerMesh.kt").contains("DeviceClaim"))
    }

    @Test
    fun `ключ заяв живе окремо від ключа месенджера`() {
        val store = source("data/ClaimKeyStore.kt")
        assertTrue("ключ заяв без власного майстер-ключа Keystore", store.contains("MasterKey.Builder(context, MASTER_ALIAS)"))
        assertTrue(store.contains("const val FILE_NAME = \"phantom_claims_v1\""))
        for (shared in listOf("phantom_identity_v1", "DeviceIdentityStore", "\"ed25519_priv\"", "Log.")) {
            assertFalse("ключ заяв торкається «$shared»", store.contains(shared))
        }
    }

    @Test
    fun `клієнти сцени складаються лише в контейнері і лише через прапорець`() {
        val container = source("data/AppContainer.kt")
        assertEquals(
            2,
            Regex("""VenueClient\.sceneBase\(\s*local\.phantom\.companion\.BuildConfig\.SCENE_SERVER,""").findAll(container).count(),
        )
        val root = File(srcRoot(), "app/src/main/java")
        val makers = root.walkTopDown().filter { it.extension == "kt" }
            .filter { Regex("""(?<!class )\b(Venue|Points)Client\(""").containsMatchIn(it.readText()) }
            .map { it.name }.toSet()
        assertEquals("клієнт сцени складено в обхід прапорця", setOf("AppContainer.kt"), makers)
    }

    private fun srcRoot(): File {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "feature-stream").isDirectory) d = d.parentFile
        return d ?: fail("корінь дерева не знайдено") as Nothing
    }

    private fun source(path: String): String {
        val f = File(srcRoot(), "app/src/main/java/local/phantom/companion/$path")
        if (!f.isFile) fail("немає $f")
        return f.readText()
    }

    private fun res(dir: String): String {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "feature-stream").isDirectory) d = d.parentFile
        val f = File(d ?: fail("корінь дерева не знайдено") as Nothing, "app/src/main/res/$dir/strings.xml")
        if (!f.isFile) fail("немає $f")
        return f.readText()
    }

    private fun strings(dir: String): Map<String, String> =
        Regex("""<string name="(msgr_scene_[a-z_]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(res(dir)).associate { it.groupValues[1] to it.groupValues[2] }

    private val needed = listOf(
        "msgr_scene_no_fix", "msgr_scene_checkin_no_fix", "msgr_scene_near_stale", "msgr_scene_place_coarse",
        "msgr_scene_peer_old", "msgr_scene_mark_failed", "msgr_scene_mark_bad", "msgr_scene_not_card",
        "msgr_scene_not_enabled", "msgr_scene_unreachable", "msgr_scene_refused",
    )

    @Test
    fun `слова відмов сцени є в обох мовах і не збігаються між собою`() {
        val uk = strings("values-uk")
        val en = strings("values")
        for (key in needed) {
            assertTrue("$key немає в українських рядках", !uk[key].isNullOrBlank())
            assertTrue("$key немає в англійських рядках", !en[key].isNullOrBlank())
        }
        // Ті, що раніше ділили один рядок і брехали: «поруч» ≠ «Я на місці» ≠ «приблизне».
        val distinct = listOf("msgr_scene_no_fix", "msgr_scene_checkin_no_fix", "msgr_scene_near_stale", "msgr_scene_place_coarse")
        assertTrue(distinct.map { uk[it] }.toSet().size == distinct.size)
        assertTrue(distinct.map { en[it] }.toSet().size == distinct.size)
    }

    @Test
    fun `в англійських словах сцени немає заборонених слів інтерфейсу`() {
        val banned = listOf("light", "blackout", "darkness", "bluetooth", "mesh")
        for ((key, text) in strings("values")) {
            val low = text.lowercase()
            for (b in banned) {
                assertFalse("$key містить заборонене «$b»: $text", Regex("\\b$b\\b").containsMatchIn(low))
            }
        }
    }
}
