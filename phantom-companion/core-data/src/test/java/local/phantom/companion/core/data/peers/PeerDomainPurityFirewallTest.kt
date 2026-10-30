package local.phantom.companion.core.data.peers

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ЗАКОН ЧИСТОТИ ДОМЕНУ, ЯК ТЕКСТ.
 *
 * `PeerPoint` — гола поверхня обміну. Тієї миті, коли сюди заїде месенджерний
 * тип (кадр, сесія, крипта) або мапний (тайл, стиль, шар), дві сторони
 * склеяться назавжди: месенджер не збереться без мапи, мапа не збереться без
 * месенджера, і жодна з них уже не зможе змінитись окремо.
 *
 * Компілятор цього не бачить — обидва боки в одному застосунку і все чесно
 * лінкується. Тому сторож читає ДЖЕРЕЛО.
 *
 * GATE LAW: файлом, повз up-to-date Gradle — на валі запускати з `--rerun`.
 */
class PeerDomainPurityFirewallTest {

    /**
     * Заборонені сусіди. `core.data.peers` дозволено бачити лише kotlin,
     * kotlinx і себе: усе інше — це або чужий домен, або дорога до нього.
     */
    private val allowedPrefixes = listOf(
        "kotlin.",
        "kotlinx.",
        "java.",
        "local.phantom.companion.core.data.peers.",
    )

    /** Імена, на яких склейка вже одного разу починалась в інших модулях. */
    private val forbiddenMarkers = listOf(
        "messenger", "crypto", "session", "ratchet", "mailbox",
        "tile", "style", "maplibre", "layer", "geojson", "compose",
    )

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core-data").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with core-data/ not found") as Nothing
    }

    private fun peerSources(): List<File> {
        val dir = File(
            repoRoot(),
            "core-data/src/main/java/local/phantom/companion/core/data/peers",
        )
        assertTrue("каталог peers/ не знайдено — сторож нічого не боронить", dir.isDirectory)
        val files = dir.listFiles { f: File -> f.extension == "kt" }?.toList().orEmpty()
        assertTrue("у peers/ немає джерел — сторож осліп", files.isNotEmpty())
        return files
    }

    private fun importsOf(file: File): List<String> = file.readLines()
        .filter { it.startsWith("import ") }
        .map { it.removePrefix("import ").substringBefore(" as ").trim() }

    @Test
    fun `the exchange surface imports nothing from anybody's domain`() {
        val offenders = peerSources().flatMap { file ->
            importsOf(file)
                .filterNot { imp -> allowedPrefixes.any { imp.startsWith(it) } }
                .map { "${file.name}: $it" }
        }
        assertEquals(
            "core-data/peers мусить лишатись голою поверхнею обміну: ні месенджерних " +
                "типів, ні мапних. Заїхало:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `no foreign vocabulary leaks in through a fully qualified name`() {
        // Імпорт — не єдина дорога: повне імʼя в тілі функції обходить його.
        val forbidden = listOf("org.maplibre", "androidx.", "android.") +
            forbiddenMarkers.map { "local.phantom.companion.core.$it" }
        val offenders = peerSources().flatMap { file ->
            val body = file.readText().lowercase()
            forbidden.filter { body.contains(it) }.map { "${file.name}: $it" }
        }
        assertEquals(offenders.joinToString("\n"), emptyList<String>(), offenders)
    }

    @Test
    fun `the surface owns no clock of its own`() {
        // Таймер тут цокав би й тоді, коли мапу ніхто не бачить, і був би
        // єдиним у core-data споживачем батареї, якого ніхто не просив.
        val offenders = peerSources().filter { file ->
            val body = file.readText()
            Regex("""\bdelay\(|Timer\(|fixedRateTimer|CoroutineScope\(""").containsMatchIn(body)
        }.map { it.name }
        assertEquals(
            "підмітання — це suspend fun sweep() на такті споживача, не власний годинник: $offenders",
            emptyList<String>(),
            offenders,
        )
    }
}
