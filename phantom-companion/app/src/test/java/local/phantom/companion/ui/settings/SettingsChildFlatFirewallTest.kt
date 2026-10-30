package local.phantom.companion.ui.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Стиль D для дочірніх екранів Профілю, до яких веде звичайний рядок
 * («Голос», «Звідки дані на мапі»): той самий каркас [SubScreen] і групи,
 * токени `LocalPhantomColors`/`PhantomText`, без старої теми, моноширинного
 * шрифту, бурштинових кікерів, сирих sp і напівпрозорих підкладок.
 */
class SettingsChildFlatFirewallTest {

    private val screens = listOf("VoiceSettingsScreen.kt", "DataLicensesScreen.kt")

    private fun root(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "app/src/main").isDirectory) dir = dir.parentFile
        return requireNotNull(dir) { "app/src/main not found" }
    }

    private fun src(name: String) =
        File(root(), "app/src/main/java/local/phantom/companion/ui/settings/$name").readText()

    @Test fun childScreensUseTheProfileFrameAndDTokens() {
        val banned = Regex("""LocalPhantomTheme|LocalStateAccent|PhantomMono|PhantomKicker|GlassCard|PhantomBackPill|\d\.sp\b|\.copy\(alpha""")
        for (name in screens) {
            val code = src(name).lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            val hits = code.withIndex().filter { banned.containsMatchIn(it.value) }.map { "$name:${it.index + 1}: ${it.value.trim()}" }
            assertTrue("legacy look:\n" + hits.joinToString("\n"), hits.isEmpty())
            assertTrue("$name: not on SubScreen", code.any { "SubScreen(" in it })
        }
    }

    /** Власник 26.09: жодної техніки перед людиною — шляхів файлів, імен рушіїв, RAM, прикрас. */
    @Test fun noTechOnScreen() {
        val voice = src("VoiceSettingsScreen.kt")
        val sources = src("DataLicensesScreen.kt")
        val modelRow = src("AssetsPane.kt").substringAfter("internal fun ModelRow(")
        assertTrue("voice shows engine route names", !Regex("""(?<!fun )(tts|stt)RouteLabel\(""").containsMatchIn(voice))
        assertTrue("voice shows a RAM recommendation", "voice_stt_choice" !in voice && "hearingModelForRam" !in voice)
        assertTrue("model row shows the engine name", "displayName" !in modelRow)
        assertTrue("model row actions are amber", "accentSharp" !in modelRow && "LocalPhantomTheme" !in modelRow)
        assertTrue("data sources list asset paths", "Asset?.let" !in sources)
        assertTrue("data sources name a file", ".COF" !in sources)

        val groups = bundledAttributions(listOf(local.phantom.companion.core.data.country.UkrainePack))
        assertTrue("no bundled sources named", groups.isNotEmpty())
        assertTrue("OpenStreetMap not credited", groups.any { "OpenStreetMap" in it.first })
        for ((name, kinds) in groups) {
            assertTrue("path on screen: $name", '/' !in name && ".json" !in name && ".zip" !in name)
            assertTrue("$name covers nothing", kinds.isNotEmpty())
        }

        val labels = listOf(
            "voice_test_button", "voice_test_button_live", "settings_voice_fetch", "voice_preview_button",
            "lic_feed_alerts", "lic_feed_threats", "lic_feed_details", "lic_feed_radar", "lic_feed_weather",
        )
        for (dir in listOf("values", "values-uk", "values-cs")) {
            val xml = File(root(), "app/src/main/res/$dir/strings.xml").readText()
            for (key in labels) {
                val value = Regex("""<string name="$key">([^<]*)</string>""").find(xml)!!.groupValues[1]
                assertTrue("$dir/$key decorated: $value", '·' !in value && value.first().isUpperCase())
            }
        }
    }

    @Test fun groupCaptionsAreSentenceCase() {
        val keys = listOf(
            "voice_engines", "voice_prosody", "voice_test_title", "voice_stt_models",
            "voice_clone_models", "voice_character_title",
            "lic_map", "lic_bundled", "lic_feeds", "lic_fonts", "lic_model", "lic_engines",
        )
        for (dir in listOf("values", "values-uk", "values-cs")) {
            val xml = File(root(), "app/src/main/res/$dir/strings.xml").readText()
            for (key in keys) {
                val value = Regex("""<string name="$key">([^<]*)</string>""").find(xml)?.groupValues?.get(1)
                    ?: error("$dir/$key missing")
                assertTrue("$dir/$key shouts: $value", value != value.uppercase() && value.first().isUpperCase())
            }
        }
    }
}
