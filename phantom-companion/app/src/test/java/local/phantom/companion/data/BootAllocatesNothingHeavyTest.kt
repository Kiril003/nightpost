package local.phantom.companion.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Старт не піднімає ваг.
 *
 * «Очі-2» зміряли на екрані ПІНу — до мапи, до моделей, до першого дотику —
 * PSS 796 МБ, з них 690 МБ НАТИВНОЇ купи (Java 54 МБ, Graphics 0). На апараті
 * з 2-3 ГБ це виклик lowmemorykiller ще до того, як людина щось зробила.
 *
 * Один винуватець доведений і полікований: `SupertonicRoute.isReady()`
 * відповідало на питання викликом `synth.load()`, тобто чотирма сесіями ONNX
 * над ~398 МБ ваг, а `ContainerBoot` питає готовність голосу на старті.
 * Сторож на це — `ReadinessIsNotLoadingTest` у `:core-voice`.
 *
 * ЦЕЙ сторож — про сам шлях старту: у `boot()` не сміє стояти виклик, який
 * відкриває сесію чи вантажить модель. Перелік нижче — імена, що вже коштували
 * памʼяті; він неповний за побудовою і мусить рости.
 *
 * **Межа, названа вголос:** це вимір ДЖЕРЕЛА і він ловить лише ПРЯМИЙ виклик.
 * Саме тому 398 МБ і проїхали: у `boot()` стояло нешкідливе на вигляд
 * `refreshPremiumStatus()`, а вантажило воно через два шари. Транзитивні
 * випадки ловить тест біля самого завантажувача, не тут. Справжню стелю на
 * нативну купу може поставити лише вимір на апараті.
 */
class BootAllocatesNothingHeavyTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "feature-stream").isDirectory }
        ?: error("не знайшла кореня сховища: жоден предок не має теки feature-stream")

    private val bootSrc: String by lazy {
        val f = File(root, "app/src/main/java/local/phantom/companion/data/ContainerBoot.kt")
        assertTrue("зник ContainerBoot.kt — шлях старту більше ні на чому міряти", f.isFile)
        f.readText()
    }

    /** Тіло `boot()` без коментарів: інакше сторож ловив би власне пояснення. */
    private val bootBody: String by lazy {
        val body = bootSrc.substringAfter("internal fun AppContainer.boot()", "")
        assertTrue("не знайшла boot() — перевимір шляху старту", body.isNotBlank())
        Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
            .replace(Regex("//[^\\n]*").replace(body, ""), "")
    }

    /** Те, що вже коштувало нативної памʼяті або відкриває сесію рушія. */
    private val heavy = listOf(
        "createSession(" to "сесія ONNX — ваги моделі лягають у нативну купу",
        "OrtEnvironment" to "середовище ONNX на шляху старту",
        "synth.load" to "Supertonic: ~398 МБ ваг у чотирьох сесіях",
        "Model(" to "Vosk: акустична модель у нативній памʼяті",
        "ensureLoaded(" to "ліниве завантаження, викликане завчасно",
        "VoskSttEngine(" to "конструктор Vosk вантажить модель одразу",
    )
    // `.load()` НАВМИСНО не в переліку: на старті стоїть `store.load()` —
    // прогрів розпакування Keystore, і він тут доречний. Сторож, що червоніє
    // на правильний рядок, навчає його обходити, а не виправляти.

    @Test
    fun `boot не вантажить моделей прямо`() {
        val offenders = heavy.filter { (needle, _) -> bootBody.contains(needle) }
            .map { (needle, why) -> "  $needle — $why" }
        assertTrue(
            "у boot() стоїть завантаження моделі — старт забере нативну купу до першого екрана:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
