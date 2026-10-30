package local.phantom.companion.core.voice.stream

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP0, обчислювальна частина: скільки символів відповіді має надійти, перш
 * ніж перший шматок піде в синтез. Це єдина половина часу до першого слова,
 * яку можна чесно проміряти без телефона — друга половина (сам синтез)
 * акустична й міряється лише на залізі.
 *
 * Число має значення тому, що моделі віддають ~15–40 символів за секунду:
 * відкривач на 60 символів — це ще 1.5–4 с чекання ПЕРЕД синтезом, і саме
 * тому ряд починається з 28.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenerLatencyTest {

    private class Recorder : SpeechSpan {
        val said = mutableListOf<String>()
        override suspend fun say(segment: String): Boolean {
            said += segment
            return true
        }
        override fun heardSoFar(): String = ""
        override fun enqueuedText(): String = said.joinToString(" ")
        override suspend fun finish(timeoutMs: Long) = Unit
        override fun cancel() = Unit
    }

    /** Відповіді, які модель справді дає в цьому продукті. */
    private val replies = listOf(
        "Гаразд, дивлюсь. Найближче укриття за двісті метрів, я підсвітив його на мапі.",
        "Маршрут вільний. Виїзд о шостій, дорога займе тридцять сім хвилин.",
        "Так, таймер на десять хвилин поставив.",
        "Зараз перевірю погоду. Сім градусів, дощ до вечора — візьми дощовик.",
        "Не можу сказати напевно: мережі немає, тож дані на мапі можуть бути застарілі.",
        "Три речі на сьогодні: ротація о вісімнадцятій, пакет мап зібрано, рація на четвертому каналі.",
    )

    @Test
    fun `перший шматок відходить у синтез задовго до кінця відповіді`() = runTest {
        val openers = replies.map { reply ->
            val span = Recorder()
            val speaker = IncrementalSpeaker(
                scope = this,
                openSpan = { span },
                fallbackSpeak = {},
            )
            speaker.open()
            // Токен за токеном, як віддає модель.
            var seen = 0
            var firstAt = -1
            for (piece in reply.chunked(4)) {
                speaker.append(piece)
                seen += piece.length
                advanceUntilIdle()
                if (firstAt < 0 && span.said.isNotEmpty()) firstAt = seen
            }
            speaker.close()
            advanceUntilIdle()
            assertTrue("відповідь «${reply.take(20)}…» не дала жодного шматка", firstAt > 0)
            firstAt
        }

        val worst = openers.max()
        val mean = openers.average()
        // WP0: число друкується, а не лише перевіряється — стеля мовчить, поки
        // не зламається, а тренд видно тільки так.
        println("WP0 opener chars: $openers · worst=$worst mean=${"%.1f".format(mean)}")
        assertTrue(
            "найгірший відкривач $worst символів — конвеєр не встигає стартувати",
            worst <= 64,
        )
        assertTrue("середній відкривач $mean символів — задовго до першого звуку", mean <= 40.0)
    }
}
