package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Підлога API: метод, якого на Android 10 не існує.
 *
 * **Знайдено 30.08, чотири виклики.** `URLEncoder.encode(String, Charset)` —
 * перевантаження **з API 33**. У цьому дереві `minSdk = 29`, а десугарування
 * бібліотек ядра вимкнено (`coreLibraryDesugaring` не згадується в жодному
 * `.kts` чи `.toml`). Отже на Android 10, 11 і 12 цей рядок дає
 * `NoSuchMethodError` у момент виклику.
 *
 * Два з чотирьох викликів стояли просто на шляху **пошуку місць**
 * (`NominatimClient`, `PhotonClient` — це і є два різновиди геокодера за
 * `GeocodeClient`). Тобто вирок власника «пошук місць не спроможний ні на що»
 * для третини підтримуваних версій Android означав не слабкий пошук, а
 * **падіння з першої літери**.
 *
 * **Чому цього ніхто не бачив:** емулятор дому — API 34, телефон власника —
 * Android 16. Жодна поверхня, на якій ми дивимось, цей поверх не зачіпає.
 * Мовчання інструментів тут теж повне: `tsc`-еквіваленту немає, компілятор
 * Kotlin про рівні API не знає, а Android Lint (який ловить це правилом
 * `NewApi`) **у ворота не підключений**.
 *
 * **Правильні ліки ширші за цей тест** — увімкнути десугарування або завести
 * `:app:lintFieldDebug` у ворота, і тоді весь клас закривається одразу. Доки
 * цього немає, тут стоїть вузький сторож на конкретний метод, який уже коштував
 * нам робочої функції.
 *
 * **Сліпі плями:**
 *  - стереже ОДИН метод, не клас: будь-який інший виклик API 33+ пройде повз;
 *  - читає джерело регексом — виклик, розбитий на кілька рядків, не побачить;
 *  - не перевіряє, чи десугарування досі вимкнено: якщо його увімкнуть, цей
 *    сторож стане зайвим, але не шкідливим.
 */
class MinSdkApiFloorTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь репозиторію з feature-stream/ не знайдено") as Nothing
    }

    /** Чиста функція — тому її можна довести на завідомо поганому рядку. */
    private fun offends(line: String): Boolean =
        Regex("""URLEncoder\.encode\([^)]*,\s*Charsets\.""").containsMatchIn(line)

    @Test
    fun `URLEncoder не бере Charset при minSdk 29`() {
        val root = File(repoRoot(), "feature-stream/src/main")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    if (offends(line)) "${f.name}:${i + 1}" else null
                }
            }
            .toList()

        assertEquals(
            "URLEncoder.encode(x, Charsets.UTF_8) — перевантаження з API 33, а minSdk тут 29 " +
                "і десугарування вимкнено: на Android 10-12 це NoSuchMethodError у момент " +
                "виклику. Пиши \"UTF-8\" рядком (так уже роблять DossierWikiLookup і " +
                "DossierEntity), або вмикай coreLibraryDesugaring і прибирай цей сторож.",
            emptyList<String>(),
            offenders,
        )
    }

    /** ДОКАЗ ЧЕРВОНОГО: сторож мусить ловити саме ту форму, через яку падало. */
    @Test
    fun `сторож ловить форму з Charset`() {
        assertTrue(offends("""val q = URLEncoder.encode(query, Charsets.UTF_8)"""))
        assertTrue(offends("""it.write("data=" + URLEncoder.encode(query, Charsets.UTF_8))"""))
    }

    /** І не ловить безпечну — інакше він просто заборонив би кодування взагалі. */
    @Test
    fun `сторож пропускає форму з рядком`() {
        assertTrue(!offends("""val q = URLEncoder.encode(query, "UTF-8")"""))
        assertTrue(!offends("""URLEncoder.encode(title.replace(' ', '_'), "UTF-8")"""))
    }
}
