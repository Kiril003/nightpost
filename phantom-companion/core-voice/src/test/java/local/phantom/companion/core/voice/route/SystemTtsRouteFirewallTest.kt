package local.phantom.companion.core.voice.route

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Системний голос має один вхід — [RoutedTts].
 *
 * 03.09 власник почув повітряну тривогу системним роботом. Дроту повз
 * маршрутизатор у дереві не знайшлось — робот прийшов ізсередини, через
 * запасний хід без сліду. Але й ніщо не боронило такому дроту зʼявитись
 * завтра: `android.speech.tts.TextToSpeech` — публічний клас, а
 * `AndroidSystemTts` — публічний міст. Тепер боронить цей сторож.
 *
 * Дозволено: усе всередині `core-voice/.../route/`, і в корені композиції
 * (AppContainer) — збудувати міст і віддати його в `RoutedTts(systemFallback = …)`.
 * Будь-який виклик на збудованому мості, будь-яка згадка поза цими двома
 * місцями — червоне.
 *
 * GATE LAW: читає вихідники як файли, повз перевірку актуальності Gradle.
 * Ганяти з `--rerun`.
 */
class SystemTtsRouteFirewallTest {

    private val routeDir = "core-voice/src/main/java/local/phantom/companion/core/voice/route/"
    private val compositionRoot = "app/src/main/java/local/phantom/companion/data/AppContainer.kt"

    private val frameworkTts = Regex("""android\.speech\.tts\.|\bTextToSpeech\s*\(""")
    private val bridge = Regex("""\bAndroidSystemTts\b""")
    private val bridgeBuilt = Regex("""\bval\s+(\w+)\s*=\s*AndroidSystemTts\s*\(""")
    private val comment = Regex("""^\s*(//|\*|/\*)""")

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core-voice").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь дерева з core-voice/ не знайдено") as Nothing
    }

    internal fun offences(path: String, source: String): List<String> {
        if (path.startsWith(routeDir)) return emptyList()
        val out = mutableListOf<String>()
        val lines = source.lines()
        lines.forEachIndexed { i, line ->
            if (comment.containsMatchIn(line)) return@forEachIndexed
            val at = "$path:${i + 1}"
            if (frameworkTts.containsMatchIn(line)) out += "$at: android.speech.tts поза route/"
            if (!bridge.containsMatchIn(line)) return@forEachIndexed
            when {
                path != compositionRoot -> out += "$at: AndroidSystemTts поза route/ і поза коренем композиції"
                line.trimStart().startsWith("import ") -> Unit
                bridgeBuilt.containsMatchIn(line) -> Unit
                else -> out += "$at: AndroidSystemTts у корені композиції не як конструкція"
            }
        }
        if (path == compositionRoot) {
            bridgeBuilt.findAll(source).map { it.groupValues[1] }.forEach { name ->
                val called = Regex("""\b$name\s*\.\s*\w+\s*\(""")
                lines.forEachIndexed { i, line ->
                    if (!comment.containsMatchIn(line) && called.containsMatchIn(line)) {
                        out += "$path:${i + 1}: $name викликано напряму, повз RoutedTts"
                    }
                }
                if (!Regex("""systemFallback\s*=\s*$name\b""").containsMatchIn(source)) {
                    out += "$path: $name збудовано, але не віддано в RoutedTts(systemFallback = …)"
                }
            }
        }
        return out
    }

    @Test
    fun `сторож червоніє на дріт повз маршрутизатор`() {
        val rawFramework = """
            class AirAlertVoice(ctx: Context) {
                private val tts = TextToSpeech(ctx) { }
            }
        """.trimIndent()
        assertEquals(1, offences("app/src/main/java/x/AirAlertVoice.kt", rawFramework).size)

        val bridgeElsewhere = """
            import local.phantom.companion.core.voice.route.AndroidSystemTts
            class Siren(ctx: Context) { val voice = AndroidSystemTts(ctx) }
        """.trimIndent()
        assertEquals(2, offences("app/src/main/java/x/Siren.kt", bridgeElsewhere).size)

        val rootCalling = """
            import local.phantom.companion.core.voice.route.AndroidSystemTts
            val systemTts = AndroidSystemTts(applicationContext)
            systemTts.speak(text, "uk-UA")
            RoutedTts(premium = supertonic, systemFallback = systemTts)
        """.trimIndent()
        assertEquals(1, offences(compositionRoot, rootCalling).size)

        val rootHonest = rootCalling.replace("systemTts.speak(text, \"uk-UA\")\n", "")
        assertEquals(emptyList<String>(), offences(compositionRoot, rootHonest))

        val rootHoarding = rootHonest.replace("RoutedTts(premium = supertonic, systemFallback = systemTts)", "")
        assertEquals(1, offences(compositionRoot, rootHoarding).size)
    }

    @Test
    fun `системний голос ніде не викликається повз RoutedTts`() {
        val root = repoRoot()
        val files = root.walkTopDown()
            .onEnter { it.name != "build" && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension == "kt" && it.path.contains("/src/main/") }
            .toList()
        assertTrue("вихідників не знайдено — сторож нічого не читає", files.size > 100)
        val rel = { f: File -> f.relativeTo(root).path }
        assertTrue(
            "корінь композиції зник — сторож боронить порожнечу",
            files.any { rel(it) == compositionRoot },
        )

        val offenders = files.flatMap { offences(rel(it), it.readText()) }
        assertEquals(
            "системний голос покликано повз RoutedTts. Запасний голос живе " +
                "ВСЕРЕДИНІ маршрутизатора, де кожна підміна лишає причину " +
                "(RoutedTts.fallbacks); поруч із ним він підмінює мовчки.\n    " +
                offenders.joinToString("\n    "),
            emptyList<String>(),
            offenders,
        )
    }
}
