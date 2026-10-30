package local.phantom.companion.feature.stream.ui.map

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `URLEncoder.encode(рядок, Charsets.UTF_8)` — виклик з **API 33**, а
 * мінімальна версія застосунку 29, і десугарування бібліотек у дереві немає.
 * На Android 10–12 він кидає `NoSuchMethodError`, тобто **пошук місць там
 * просто падає** — на телефоні власника (Android 14) цього не видно ніколи.
 *
 * Знайшов це лінт 30.08 — той самий, якого ворота не кликали жодного разу.
 * Поки лінт не в воротах, ось дешевший сторож на той самий клас.
 *
 * ЧОГО ЦЕЙ СТОРОЖ НЕ БАЧИТЬ: він знає ОДИН виклик і лише в цьому модулі.
 * Інші виклики з новіших API він не помітить — це робота лінту, і його треба
 * поставити у ворота, а не заміняти списком відомих випадків. Він також падає,
 * якщо не знайшов жодного файла: сторож, який нічого не прочитав, мовчить так
 * само, як сторож, у якого все гаразд.
 */
class EncoderApiLevelTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева")
    }

    @Test
    fun `кодування адрес не вживає перевантаження з API 33`() {
        val root = File(repoRoot(), "feature-stream/src/main")
        val kotlin = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("сторож не прочитав жодного файла — він осліп, а не задоволений", kotlin.size > 50)

        val offenders = kotlin.mapNotNull { f ->
            val bad = f.readLines().withIndex().filter { (_, line) ->
                line.contains("URLEncoder.encode(") && line.contains("Charsets.")
            }
            if (bad.isEmpty()) null else "${f.name}: ${bad.joinToString { "рядок ${it.index + 1}" }}"
        }
        assertTrue(
            "виклик з API 33 при minSdk 29 — на Android 10–12 це NoSuchMethodError " +
                "у живому пошуку: ${offenders.joinToString("; ")}",
            offenders.isEmpty(),
        )
    }
}
