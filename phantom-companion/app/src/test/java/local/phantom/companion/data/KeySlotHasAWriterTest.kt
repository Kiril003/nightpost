package local.phantom.companion.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * СЛОТ КЛЮЧА, ЯКИЙ МОЖНА ЛИШЕ СПОРОЖНИТИ.
 *
 * Виміряно 06.09. `AiKeyStore.KEY_HF_TOKEN` існував від початку; `hfToken`
 * читався; `AppContainer` подавав його в `ModelManager.hfTokenProvider`;
 * `fetchTo` додавав `Authorization: Bearer …` на кожен запит до
 * huggingface.co. Усе, крім одного: ЄДИНИМ викликом `saveHfToken` у всьому
 * бойовому коді був `saveHfToken(null)` у `SettingsViewModel.clearKeys()`.
 *
 * Тобто слот можна було лише спорожнити — ніколи не наповнити. Токен був
 * порожній назавжди за побудовою, а «Асети» показували на Gemma стрілку
 * завантаження, яка щоразу верталася з 401 (репозиторій `gated: auto`,
 * анонімний GET = 401 GatedRepo, зміряно). Жоден тест цього не бачив: усі
 * ланки, крім писача, були на місці й покриті.
 *
 * Це той самий клас вади, що [[phantom-written-but-never-called]] — і він не
 * ловиться перевіркою реалізації, лише перевіркою ДОСЯЖНОСТІ. Тому тут скан
 * дерева, а не мок: мок писача не доводить, що писача хтось викликає.
 */
class KeySlotHasAWriterTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    private fun mainSources(): List<File> {
        val files = File(repoRoot(), "app/src/main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        // Порожній обхід дав би зелене на порожньому місці.
        check(files.size > 50) { "підозріло мало .kt у app/src/main (${files.size})" }
        return files
    }

    /**
     * Кожен сетер ключа — і скільки разів його КЛИЧУТЬ не для очищення.
     *
     * Форма з крапкою (`.saveHfToken(`) навмисна, і вона тут головна. Перший
     * підхід рахував просто `saveHfToken(` — і оголошення `fun saveHfToken(…)`
     * саме собою давало другу згадку, через що тест був би зелений на дереві
     * ДО цієї хвилі, де писача не існувало. Перевірено на 2fbaf5f6: з крапкою
     * лишається один виклик, і він же очищення, тож ворота червоніють як слід.
     */
    private fun writerCounts(setter: String): Pair<Int, Int> {
        val sources = mainSources().map { it.readText() }
        val calls = Regex("""\.""" + Regex.escape(setter) + """\(""")
        val clears = Regex("""\.""" + Regex.escape(setter) + """\(\s*null\s*\)""")
        return sources.sumOf { calls.findAll(it).count() } to
            sources.sumOf { clears.findAll(it).count() }
    }

    @Test
    fun `кожен слот ключа має в бойовому коді писача, а не лише стирача`() {
        listOf("saveGeminiKey", "saveHfToken").forEach { setter ->
            val (all, clearing) = writerCounts(setter)
            assertTrue(
                "$setter викликається $all раз(и), з них $clearing — очищення null. " +
                    "Слот, у який нема звідки внести значення, порожній назавжди: читач, " +
                    "заголовок і кнопка звантаження при цьому виглядають справними, і " +
                    "жоден тест реалізації цього не побачить. Додай поле вводу, а не " +
                    "прибирай цю перевірку.",
                all > clearing,
            )
        }
    }
}
