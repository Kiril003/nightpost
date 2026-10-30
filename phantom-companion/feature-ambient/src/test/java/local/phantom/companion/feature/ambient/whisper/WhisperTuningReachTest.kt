package local.phantom.companion.feature.ambient.whisper

import java.io.File
import local.phantom.companion.core.design.component.WhisperFreq
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * «Частота» і «звук» доходять до служби, а не лише до сховища.
 *
 * 04.09: обидва важелі писали у `VoicePreferences` і не мали жодного читача.
 * `WhisperLineService` брав зашитий `WhisperFreq.Medium.cycleMs`, тож три чипи
 * давали 38 секунд на цикл, хоч кожен обіцяв своє число — «60 секунд»,
 * «38 секунд», «22 секунди». Перемикач звуку обіцяв «крихкий тон при появі
 * нового рядка», а в модулі не було жодного програвання звуку взагалі.
 *
 * Сховище шифроване і живе в :app, тому служба не дістане його сама — звідси
 * [WhisperTuning]. Ці тести тримають обидва кінці шва: що обʼєкт перекладає
 * порядковий номер у темп, і що служба справді читає його замість константи.
 *
 * ЩО ЦЕЙ СТОРОЖ НЕ ДОВОДИТЬ: тон він не чує. Доводить, що виклик стоїть у тілі
 * `RibbonScaffold` і що темп більше не зашитий.
 */
class WhisperTuningReachTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "feature-stream").isDirectory }
        ?: error("не знайшла кореня сховища: жоден предок не має теки feature-stream")

    private val serviceFile = File(
        root,
        "feature-ambient/src/main/java/local/phantom/companion/feature/ambient/" +
            "service/WhisperLineService.kt",
    )

    private fun serviceText(): String {
        if (!serviceFile.isFile) fail("немає $serviceFile — службу перейменували або перенесли")
        return serviceFile.readText()
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

    private fun ribbonBody(): String {
        val t = serviceText()
        // Список параметрів може нести `= {}`; беремо тіло після парної `)`,
        // а не перший `{` після імені.
        val open = Regex("""fun\s+RibbonScaffold\s*\(""").find(t)?.range?.last
            ?: run { fail("у службі немає RibbonScaffold — стрічку переписали"); 0 }
        return balancedBlockAfter(t, matchingParen(t, open))
    }

    @Test
    fun `розбирач бере саме тіло стрічки`() {
        val body = ribbonBody()
        assertTrue(
            "тіло RibbonScaffold вийшло завдовжки ${body.length} — розбирач узяв не той блок",
            body.length > 500,
        )
        assertTrue("у тілі немає WhisperLineRibbon( — це не воно", body.contains("WhisperLineRibbon("))
    }

    /* ---- переклад номера в темп ---- */

    @Test
    fun `кожен порядковий номер дає свій темп`() {
        WhisperTuning.setFreqOrdinal(0)
        assertEquals(WhisperFreq.Slow, WhisperTuning.freq.value)
        WhisperTuning.setFreqOrdinal(1)
        assertEquals(WhisperFreq.Medium, WhisperTuning.freq.value)
        WhisperTuning.setFreqOrdinal(2)
        assertEquals(WhisperFreq.Fast, WhisperTuning.freq.value)
    }

    @Test
    fun `три чипи дають три РІЗНІ цикли`() {
        // Суть вади була не в назві, а в числі: усі три вели до 38 секунд.
        val cycles = (0..2).map {
            WhisperTuning.setFreqOrdinal(it)
            WhisperTuning.freq.value.cycleMs
        }
        assertEquals("три чипи мусять дати три різні цикли, а дали $cycles", 3, cycles.toSet().size)
    }

    @Test
    fun `номер поза діапазоном не валить службу`() {
        WhisperTuning.setFreqOrdinal(99)
        assertEquals(WhisperFreq.Medium, WhisperTuning.freq.value)
        WhisperTuning.setFreqOrdinal(-1)
        assertEquals(WhisperFreq.Medium, WhisperTuning.freq.value)
    }

    @Test
    fun `звук перемикається обома напрямками`() {
        WhisperTuning.setSoundEnabled(true)
        assertTrue(WhisperTuning.soundEnabled.value)
        WhisperTuning.setSoundEnabled(false)
        assertTrue("вимкнення звуку не доходить", !WhisperTuning.soundEnabled.value)
    }

    /* ---- служба справді читає шов ---- */

    @Test
    fun `служба бере темп із налаштувань, а не з константи`() {
        val body = ribbonBody()
        assertTrue(
            "RibbonScaffold не читає WhisperTuning.freq — важіль частоти знову нікуди не веде",
            body.contains("WhisperTuning.freq"),
        )
        assertTrue(
            "у RibbonScaffold повернувся зашитий темп — три чипи знову дадуть один цикл",
            !body.contains("WhisperFreq.Medium.cycleMs"),
        )
    }

    @Test
    fun `служба гасить тон, коли звук вимкнено`() {
        val body = ribbonBody()
        assertTrue(
            "RibbonScaffold не читає WhisperTuning.soundEnabled — перемикач звуку знову мертвий",
            body.contains("WhisperTuning.soundEnabled"),
        )
        assertTrue(
            "тон не програється взагалі — перемикач обіцяв би звук, якого нема",
            body.contains("startTone("),
        )
        assertTrue(
            "тон не звірений із перемикачем — грав би й з вимкненим звуком",
            body.contains("soundEnabled"),
        )
    }

    @Test
    fun `тон звільняє генератор разом із стрічкою`() {
        // ToneGenerator тримає канал AudioTrack. Служба живе весь час роботи
        // телефона, тож не звільнений генератор — це витік на весь сеанс.
        val body = ribbonBody()
        assertTrue(
            "генератор тону не звільняється — витік на весь час життя служби",
            body.contains("DisposableEffect") && body.contains("release()"),
        )
    }
}
