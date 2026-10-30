package local.phantom.companion.feature.stream

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Жоден екран не сміє обіцяти шифрування безумовно.
 *
 * ІСТОРІЯ, і вона важливіша за правило. 07.09 порожній груповий чат малював
 * «X25519 + ChaCha20-Poly1305 шифрування кожного пакету» складу, чиїх ключів на
 * телефоні не було. Сторож проти цієї самої брехні вже існував — і був обійдений
 * ОДНІЄЮ БУКВОЮ: параметр `canSeal` мав замовчування `true`, тож виклик, який
 * промовчав, діставав обіцянку задарма.
 *
 * Того ж дня екрани, які він стеріг, прибрали разом зі старим месенджером — і
 * сторож почав чесно казати «дивлюсь у порожнє». Це правильна поведінка, але
 * мета пережила файл: заява про шифрування не сміє стояти там, де її ніхто не
 * перевіряє, у ЖОДНОМУ екрані.
 *
 * Тому він більше не привʼязаний до імені файла — саме привʼязка й зробила
 * попередника декорацією.
 *
 * 12.09 — ТРЕТЄ коло тієї самої вади, і воно варте запису. Виняток для «запиту
 * до асистента» був записаний МІСЦЕМ, а не природою: сторож звузили до `res/`,
 * бо запити жили в коді. Потім `d02fb18e` переніс швидкі запити чату в ресурси
 * — і сторож почервонів на `stream_quick_crypto_prompt`, тобто рівно на тому,
 * що його автор свідомо виніс за межі. Червоне на ЗАКОННОМУ стані вчить
 * сторожа обходити.
 *
 * Тепер виняток записаний природою й доводиться кодом: рядок не є заявою
 * людині лише тоді, коли його значення нікуди не малюється, а йде в
 * `onPrompt(...)` — тобто це те, що людина ПИТАЄ, а не те, що їй КАЖУТЬ.
 * Перейменування не рятує: щоб вийти з-під сторожа, треба справді бути запитом.
 */
class SealedClaimFirewallTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return requireNotNull(dir) { "не знайдено кореня репозиторію" }
    }

    /**
     * Лише те, що БАЧИТЬ ЛЮДИНА. Перша версія цього сторожа шукала слова в
     * коді — і ловила `KEY_DH_PRIV = "x25519_priv"` (імʼя ключа сховища) та
     * запит до асистента. Обидва не є заявою людині, і сторож, що їх лічить,
     * привчає обходити себе.
     */
    private fun shownText(): List<File> {
        val roots = listOf("feature-stream/src/main/res", "app/src/main/res")
        return roots.flatMap { r ->
            File(repoRoot(), r).walkTopDown()
                .filter { it.isFile && it.name.startsWith("strings") && it.extension == "xml" }
        }
    }

    /** Слова, які стверджують стійкість каналу, а не описують механізм у коментарі. */
    private val claims = listOf(
        "ChaCha20", "X25519", "E2EE", "Post-Quantum", "постквантов",
        "наскрізне шифрування", "шифрування кожного",
    )

    @Test
    fun `сторож має що стерегти`() {
        val files = shownText()
        assertTrue(
            "файлів рядків не знайдено — сторож дивиться в порожнє, і його зелене нічого не варте",
            files.size >= 4,
        )
    }

    /** Код, у якому ключ може бути намальований або відправлений. */
    private fun sources(): List<String> =
        listOf("feature-stream/src/main/java", "app/src/main/java").flatMap { r ->
            File(repoRoot(), r).walkTopDown().filter { it.isFile && it.extension == "kt" }
                .map { it.readText() }.toList()
        }

    /**
     * Чи є цей ключ ЗАПИТОМ людини, доведеним кодом: значення звʼязується
     * локальною змінною і йде в `onPrompt(...)`. Не намальовано — не заява.
     */
    private fun isAskedNotTold(key: String, sources: List<String>): Boolean =
        sources.any { t ->
            val v = Regex("val\\s+(\\w+)\\s*=\\s*stringResource\\(R\\.string\\.$key\\)")
                .find(t)?.groupValues?.get(1) ?: return@any false
            t.contains("onPrompt($v)")
        }

    @Test
    fun `жоден рядок не обіцяє шифрування без умови`() {
        val src = sources()
        val offenders = shownText().flatMap { f ->
            f.readText().lineSequence().withIndex()
                .filter { (_, line) -> claims.any { c -> line.contains(c, ignoreCase = true) } }
                .filterNot { (_, line) ->
                    val key = Regex("name=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                    key != null && isAskedNotTold(key, src)
                }
                .map { (i, line) -> "${f.name}:${i + 1}  ${line.trim().take(90)}" }
        }
        assertTrue(
            "екран стверджує шифрування рядком, а не станом: $offenders.\n" +
                "Заява про стійкість каналу мусить читатися зі СТАНУ (чи є ключ), " +
                "а не стояти в тексті. Порожній чат із такою обіцянкою — це брехня " +
                "тому, кому композер навіть не дасть написати.",
            offenders.isEmpty(),
        )
    }

    /**
     * Негативний контроль: виняток «це запит, не заява» мусить бути ВУЗЬКИМ.
     * Ключ, якого ніхто не шле в `onPrompt`, лишається заявою — інакше
     * сторож із винятком тихо перетворився б на завжди-зелений.
     */
    @Test
    fun `сторож червоніє на заяві, а не мовчить на всьому`() {
        // 02.10 швидкі запити пішли разом зі стрічкою, тож запит звіряється на зразку.
        val asked = """
            val prompt = stringResource(R.string.quick_crypto_prompt)
            Chip(onClick = { onPrompt(prompt) })
        """.trimIndent()
        val told = """
            val line = stringResource(R.string.chat_header_sealed_synthetic)
            Text(line)
        """.trimIndent()
        assertTrue(
            "ключ, якого не шлють у onPrompt, НЕ сміє бути звільнений",
            !isAskedNotTold("chat_header_sealed_synthetic", listOf(told) + sources()),
        )
        assertTrue(
            "запит, що йде в onPrompt, мусить бути звільнений — інакше виняток не працює",
            isAskedNotTold("quick_crypto_prompt", listOf(asked)),
        )
    }

    @Test
    fun `перелік заборонених слів не порожній`() {
        assertTrue(
            "перелік слів порожній — тоді сторож зелений завжди, і його зелене нічого не значить",
            claims.size >= 5,
        )
    }

    @Test
    fun `обидві мови під оглядом`() {
        val paths = shownText().map { it.absolutePath }
        assertTrue(
            "українських рядків під оглядом немає — сторож дивиться повз продукт",
            paths.any { it.contains("/values-uk/") },
        )
        assertTrue(
            "англійських рядків під оглядом немає: обіцянка могла б лишитись у перекладі. " +
                "Саме так у цьому дереві вже жила брехня — виправлена в одній мові й ціла в другій.",
            paths.any { it.contains("/values/") },
        )
    }
}
