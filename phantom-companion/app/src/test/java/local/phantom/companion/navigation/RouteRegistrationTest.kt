package local.phantom.companion.navigation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Кожен оголошений маршрут має призначення в графі.
 *
 * Клас вади, який тут повторювався найчастіше: екран написаний, скомпільований,
 * покритий тестами — і жоден `composable(...)` на нього не веде. Так було з
 * `WhisperSettingsScreen` (04.09): єдине місце, де вмикається стрічка поверх
 * інших застосунків, не мало маршруту, тож службу не міг підняти ніхто, а
 * підвал превʼю посилав людину в неіснуючий розділ налаштувань.
 *
 * Цей сторож ловить половину вади дешево: оголошений і не змонтований маршрут.
 * Другу половину — «маршрут є, але з UI на нього ніхто не переходить» — він НЕ
 * ловить: для цього потрібен тест на конкретний жест, як у [WhisperReachTest]
 * чи `MeshGateReachTest`. Тому додавати маршрут, аби заспокоїти цей тест, —
 * значить нічого не полагодити.
 */
class RouteRegistrationTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "feature-stream").isDirectory }
        ?: error("не знайшла кореня сховища: жоден предок не має теки feature-stream")

    private val navDir = File(root, "app/src/main/java/local/phantom/companion/navigation")

    private fun read(name: String): String {
        val f = File(navDir, name)
        if (!f.isFile) fail("немає $f — файл навігації перейменували або перенесли")
        return f.readText()
    }

    /** `data object X : Routes("…")` — саме оголошені маршрути. */
    private fun declared(): Set<String> =
        Regex("""data object\s+([A-Za-z0-9]+)\s*:\s*Routes\(""")
            .findAll(read("Routes.kt"))
            .map { it.groupValues[1] }
            .toSet()

    /**
     * `composable(Routes.X.path)` і `composable(route = Routes.X.path, …)` —
     * обидві форми в графі живі, тож обидві тут.
     */
    private val registration = Regex("""composable\(\s*(?:route\s*=\s*)?Routes\.([A-Za-z0-9]+)\.path""")

    private fun registeredIn(src: String): Set<String> =
        registration.findAll(src).map { it.groupValues[1] }.toSet()

    private fun registered(): Set<String> = registeredIn(read("PhantomNavGraph.kt"))

    @Test
    fun `жоден оголошений маршрут не лишився без призначення`() {
        val orphans = (declared() - registered()).sorted()
        assertTrue(
            "маршрути оголошені, але жоден composable( на них не веде: $orphans — " +
                "екран за ними недосяжний, скільки б тестів його не покривало",
            orphans.isEmpty(),
        )
    }

    @Test
    fun `граф не монтує маршрут, якого немає в Routes`() {
        // Зворотний бік: друкарська помилка в імені дала б мертвий запис у
        // графі, і компілятор її б спіймав — але лише поки імʼя з Routes.
        val ghosts = (registered() - declared()).sorted()
        assertTrue("граф монтує невідомі маршрути: $ghosts", ghosts.isEmpty())
    }

    @Test
    fun `розбирач бачить обидві форми виклику composable`() {
        // Сторож, що не вміє прочитати багаторядкову форму, був би зелений на
        // будь-якому дереві. 02.10 граф лишився без неї, тож форма звіряється на зразку.
        val multiline = "composable(\n    route = Routes.Sample.path,\n    arguments = emptyList(),\n) { }"
        assertTrue("багаторядкова форма не читається", "Sample" in registeredIn(multiline))
        val found = registered()
        assertTrue("розбирач не побачив SettingsLicense — однорядкова форма не читається", "SettingsLicense" in found)
        // 04.10 (profile-hub): маршрутів 18 — Settings, Memory і Messenger зняли.
        assertTrue("розбирач не знайшов жодного маршруту — регулярка згнила", found.size >= 18)
    }
}
