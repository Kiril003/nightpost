package local.phantom.companion.ui.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Стрічку вмикає екран, який людина справді може відкрити.
 *
 * 04.09 виявилось, що `WhisperSettingsScreen` — повний, покритий тестами екран
 * із вмикачем, дозволом на малювання поверх вікон, частотою і звуком — не мав
 * ЖОДНОГО виклику. Маршрут `Routes.Whisper.path` веде на `WhisperPreviewScreen`
 * (це інший екран, лише показ), а в переліку важелів налаштувань рядка «шепіт»
 * не було взагалі. Наслідок мірявся не виглядом: `setWhisperOverlayEnabled` мав
 * рівно одне місце виклику, і воно було в цьому недосяжному екрані, тож
 * `WhisperLineService` не міг стартувати ніколи. При цьому підвал превʼю казав
 * людині «увімкни в налаштуваннях, розділ «шепіт»» — розділу не існувало.
 *
 * ЧОМУ НЕ ДОСИТЬ ПЕРЕВІРИТИ, ЩО ЕКРАН Є: він увесь час був. Тому тут ланцюг
 * цілком — маршрут, рядок у налаштуваннях, і перехід із того рядка на той
 * маршрут. Кожна ланка окремо: прибери будь-яку — червоніє саме вона.
 *
 * 04.10 (profile-hub): старий перелік важелів (SettingsRealm) розпущено в
 * розділи Профілю. Рядок шепоту живе в «Для розробника» і, як і раніше, стоїть
 * під `Distribution.overlays` — у store малювати поверх застосунків нема чим.
 *
 * ЩО ЦЕЙ СТОРОЖ НЕ ДОВОДИТЬ: він читає джерело, а не рендерить екран —
 * `compose-ui-test` у `gradle/libs.versions.toml` немає. Доводить, що виклик
 * стоїть у тілі потрібної функції, а не що палець його дістав.
 */
class WhisperReachTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "feature-stream").isDirectory }
        ?: error("не знайшла кореня сховища: жоден предок не має теки feature-stream")

    private val appJava = File(root, "app/src/main/java/local/phantom/companion")
    private val navDir = File(appJava, "navigation")
    private val homeDir = File(appJava, "ui/home")

    private fun text(dir: File, name: String): String {
        val f = File(dir, name)
        if (!f.isFile) fail("немає $f — файл перейменували або перенесли")
        return f.readText()
    }

    /** Тіло від `{` після [from] до парної `}` — саме тіло, не файл. */
    private fun balancedBlockAfter(text: String, from: Int): String {
        val open = text.indexOf('{', from)
        if (open < 0) return ""
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return text.substring(open, i + 1) }
            }
            i++
        }
        return text.substring(open)
    }

    /** Кінець списку параметрів: від `(` до парної `)`. */
    private fun matchingParen(text: String, openIdx: Int): Int {
        var depth = 0
        var i = openIdx
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return openIdx
    }

    private fun bodyOf(fileText: String, name: String): String {
        val open = Regex("""fun\s+$name\s*\(""").find(fileText)?.range?.last
            ?: run { fail("функції $name немає — оголошення переїхало або зникло"); 0 }
        // Список параметрів сам містить `= {}` (типовий no-op), тож перший `{`
        // після імені — це той дефолт, а не тіло. Перша версія цього тесту
        // читала саме його, бачила порожньо і червоніла на полагодженому
        // дереві. Спершу закриваємо дужку списку, потім беремо тіло.
        return balancedBlockAfter(fileText, matchingParen(fileText, open))
    }

    /** Імена, які реально реєструє навграф через `composable(...)`. */
    private fun routedNames(): Set<String> {
        val nav = text(navDir, "PhantomNavGraph.kt")
        return Regex("""composable\(([^)]*)\)""").findAll(nav).mapNotNull { m ->
            val block = balancedBlockAfter(nav, m.range.last)
            Regex("""([A-Z][A-Za-z0-9]*)\s*\(""").find(block)?.groupValues?.get(1)
        }.toSet()
    }

    /** Функції, у ТІЛІ яких стоїть виклик — не «файли, де він десь є». */
    private fun holdersOf(call: String): Set<String> =
        appJava.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { f ->
                val t = f.readText()
                Regex("""fun\s+([A-Z][A-Za-z0-9]*)\s*\(""").findAll(t).mapNotNull { m ->
                    val body = balancedBlockAfter(t, m.range.last)
                    if (body.contains(call)) m.groupValues[1] else null
                }
            }.toSet()

    @Test
    fun `маршрут шепоту веде саме на екран налаштувань, а не на показ`() {
        val nav = text(navDir, "PhantomNavGraph.kt")
        val at = nav.indexOf("composable(Routes.SettingsWhisper.path)")
        if (at < 0) fail("у навграфі немає composable(Routes.SettingsWhisper.path) — екран знову недосяжний")
        val block = balancedBlockAfter(nav, at)
        assertTrue(
            "маршрут шепоту не малює WhisperSettingsScreen — у блоці: ${block.take(160)}",
            block.contains("WhisperSettingsScreen("),
        )
    }

    @Test
    fun `розбирач бере тіло функції, а не типовий no-op зі списку параметрів`() {
        // Сторож на сторожа. Перша версія bodyOf брала перший `{` після імені
        // — а в старому settingsEntries це був `= {}` одного з параметрів.
        // Тіло виходило порожнім, і тест червонів на полагодженому дереві.
        // Зразок із дефолтною лямбдою в параметрах тримає розбирач чесним.
        val sample = "fun Sample(onBack: () -> Unit = {}) {\n    Row()\n    Column()\n}"
        assertTrue("розбирач узяв типовий no-op замість тіла", bodyOf(sample, "Sample").contains("Column()"))
        val body = bodyOf(text(homeDir, "ProfileSections.kt"), "DeveloperSection")
        assertTrue(
            "тіло DeveloperSection вийшло завдовжки ${body.length} — розбирач узяв не той блок",
            body.length > 500 && body.contains("SubScreen("),
        )
    }

    @Test
    fun `у розділі для розробника є рядок, що переходить на маршрут шепоту`() {
        val body = bodyOf(text(homeDir, "ProfileSections.kt"), "DeveloperSection")
        assertTrue(
            "у «Для розробника» немає переходу на Routes.SettingsWhisper — пальцю нема за що взятись",
            body.contains("go(Routes.SettingsWhisper.path)"),
        )
        val at = body.indexOf("go(Routes.SettingsWhisper.path)")
        val guard = body.lastIndexOf("Distribution.overlays", at)
        assertTrue(
            "рядок шепоту не стоїть під Distribution.overlays — у store він веде в нікуди",
            guard in 0 until at && at - guard < 300,
        )
    }

    @Test
    fun `єдиний вмикач стрічки живе на екрані, що має маршрут`() {
        val holders = holdersOf("setWhisperOverlayEnabled")
        assertTrue("виклик setWhisperOverlayEnabled не знайдено в жодному тілі", holders.isNotEmpty())
        val routed = routedNames()
        assertTrue(
            "вмикач стрічки кличуть лише $holders, а маршрути ведуть на $routed — стрічку не ввімкнути",
            holders.any { it in routed },
        )
    }

    @Test
    fun `частота і звук доїжджають зі сховища до служби`() {
        // Сховище шифроване і живе тут; служба живе в :feature-ambient і не
        // дістане його сама. Без цього дзеркала обидва важелі писали нікуди.
        val boot = text(File(appJava, "data"), "ContainerBoot.kt")
        val at = Regex("""fun\s+AppContainer\.boot\s*\(""").find(boot)?.range?.first
            ?: run { fail("немає AppContainer.boot() — завантаження контейнера переїхало"); 0 }
        val body = balancedBlockAfter(boot, at)
        assertTrue(
            "boot() не дзеркалить whisperFreqOrdinal — важіль частоти знову пише нікуди",
            body.contains("whisperFreqOrdinal") && body.contains("setFreqOrdinal"),
        )
        assertTrue(
            "boot() не дзеркалить whisperSoundEnabled — перемикач звуку знову пише нікуди",
            body.contains("whisperSoundEnabled") && body.contains("setSoundEnabled"),
        )
    }

    @Test
    fun `службу стрічки стартують з екрана, що має маршрут`() {
        val holders = holdersOf("WhisperLineService::class.java")
        assertTrue("старт WhisperLineService не знайдено в жодному тілі", holders.isNotEmpty())
        val routed = routedNames()
        assertTrue(
            "службу стрічки піднімають лише з $holders, а маршрути ведуть на $routed — служба не підніметься",
            holders.any { it in routed },
        )
    }
}
