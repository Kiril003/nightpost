package local.phantom.companion.core.voice.stream

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Живий діалог тримається на одному: перший шматок відповіді має піти в
 * синтез, поки модель ще пише решту. Тут перевіряється саме розріз потоку —
 * усе, що вирішує, коли людина почує перше слово.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IncrementalSpeakerTest {

    private class FakeSpan : SpeechSpan {
        val said = mutableListOf<String>()
        var finished = false
        var cancelled = false

        override suspend fun say(segment: String): Boolean {
            said += segment
            return true
        }

        override fun heardSoFar(): String = said.joinToString(" ")
        override fun enqueuedText(): String = said.joinToString(" ")
        override suspend fun finish(timeoutMs: Long) {
            finished = true
        }

        override fun cancel() {
            cancelled = true
        }
    }

    private fun TestScopeSpeaker(
        span: FakeSpan,
        ack: String? = null,
        scope: kotlinx.coroutines.CoroutineScope,
        fallback: MutableList<String> = mutableListOf(),
    ) = IncrementalSpeaker(
        scope = scope,
        openSpan = { span },
        fallbackSpeak = { fallback += it },
        acknowledgement = { ack },
    )

    @Test
    fun `перший шматок іде в синтез задовго до кінця відповіді`() = runTest {
        val span = FakeSpan()
        val speaker = TestScopeSpeaker(span, scope = this)
        speaker.open()

        // Модель ще пише — але перше речення вже закінчилось.
        speaker.append("Перевірив маршрут. ")
        advanceUntilIdle()

        assertEquals(listOf("Перевірив маршрут."), span.said.toList())
        assertTrue("проміжок не мав закриватись посеред ходу", !span.finished)

        speaker.append("Найближче укриття за двісті метрів.")
        speaker.close()
        advanceUntilIdle()

        assertEquals(2, span.said.size)
        assertTrue(span.finished)
    }

    @Test
    fun `короткий відкривач ріжеться по комі, а не по середині слова`() = runTest {
        val span = FakeSpan()
        val speaker = TestScopeSpeaker(span, scope = this)
        speaker.open()
        speaker.append("Гаразд, зараз подивлюсь на це трохи уважніше ніж раніше")
        advanceUntilIdle()
        val opener = span.said.firstOrNull()
        speaker.cancel()

        assertEquals("Гаразд,", opener)
    }

    @Test
    fun `віджет не читається вголос`() = runTest {
        val span = FakeSpan()
        val speaker = TestScopeSpeaker(span, scope = this)
        speaker.open()
        speaker.append("""{"type":"weather","say":"сім градусів, дощ"}""")
        speaker.close()
        advanceUntilIdle()

        assertTrue("JSON не має потрапляти в синтез: ${span.said}", span.said.isEmpty())
    }

    @Test
    fun `прихований трейлер підказок не озвучується`() = runTest {
        val span = FakeSpan()
        val speaker = TestScopeSpeaker(span, scope = this)
        speaker.open()
        speaker.append("Маршрут вільний. ⟦NEXT⟧ показати мапу | інший шлях")
        speaker.close()
        advanceUntilIdle()

        assertEquals(listOf("Маршрут вільний."), span.said)
    }

    @Test
    fun `підтвердження звучить, коли модель мовчить`() = runTest {
        val span = FakeSpan()
        val speaker = TestScopeSpeaker(span, ack = "секунду", scope = this)
        speaker.open()
        advanceUntilIdle()
        val heard = span.said.toList()
        speaker.cancel()

        assertEquals(listOf("секунду"), heard)
    }

    /**
     * Договір «ніколи не мовчати» існує проти мертвої тиші, а не щоб
     * доклеїти склад перед швидкою відповіддю: підтвердження, яке затримує
     * відповідь, робить нас і повільнішими, і балакучішими.
     */
    @Test
    fun `підтвердження мовчить, якщо відповідь уже почалась`() = runTest {
        val span = FakeSpan()
        val speaker = TestScopeSpeaker(span, ack = "секунду", scope = this)
        speaker.open()
        speaker.append("Уже дивлюсь. ")
        advanceUntilIdle()
        val heard = span.said.toList()
        speaker.cancel()

        assertEquals(listOf("Уже дивлюсь."), heard)
    }

    @Test
    fun `перехоплення кидає невимовлене і памʼятає почуте`() = runTest {
        val span = FakeSpan()
        val speaker = TestScopeSpeaker(span, scope = this)
        speaker.open()
        speaker.append("Перше речення. ")
        advanceUntilIdle()

        val heard = speaker.heardSoFar()
        speaker.cancel()
        speaker.append("Друге речення. ")
        advanceUntilIdle()

        assertEquals("Перше речення.", heard)
        assertTrue(span.cancelled)
        assertEquals(1, span.said.size)
    }

    @Test
    fun `коли стрімити нема чим — фраза все одно звучить`() = runTest {
        val fallback = mutableListOf<String>()
        val speaker = IncrementalSpeaker(
            scope = this,
            openSpan = { null },
            fallbackSpeak = { fallback += it },
        )
        speaker.open()
        speaker.append("Маршрут вільний. ")
        speaker.close()
        advanceUntilIdle()

        assertEquals(listOf("Маршрут вільний."), fallback)
    }

    @Test
    fun `місток інструмента звучить у мить виклику, а не наприкінці ходу`() = runTest {
        val span = FakeSpan()
        val speaker = TestScopeSpeaker(span, scope = this)
        speaker.open()
        speaker.say("зараз гляну")
        advanceUntilIdle()
        val heard = span.said.toList()
        speaker.cancel()

        assertEquals(listOf("зараз гляну"), heard)
    }

    @Test
    fun `every piece handed to synthesis is reported, so silence can be timed`() = runTest {
        val span = FakeSpan()
        val seen = mutableListOf<String>()
        val speaker = IncrementalSpeaker(
            scope = this,
            openSpan = { span },
            fallbackSpeak = {},
            onEnqueue = { seen += it },
        )
        speaker.open()
        speaker.append("Перевірив маршрут. ")
        advanceUntilIdle()
        assertEquals(listOf("Перевірив маршрут."), seen.toList())
        speaker.say("Зараз гляну.")
        speaker.append("Укриття поруч.")
        speaker.close()
        advanceUntilIdle()
        assertEquals(3, seen.size)
    }

    @Test
    fun `the answer after a tool call is spoken as it streams, the call is not`() = runTest {
        val span = FakeSpan()
        val speaker = TestScopeSpeaker(span, scope = this)
        speaker.open()
        speaker.append("""{"type":"tool-call","tool":"weather.now","args":{"city":"Київ"}}""")
        speaker.say("Зараз гляну.")
        speaker.restart()
        speaker.append("У Києві сонячно. ")
        advanceUntilIdle()
        val streamed = span.said.toList()
        speaker.append("Парасолька не знадобиться.")
        speaker.close("У Києві сонячно. Парасолька не знадобиться.")
        advanceUntilIdle()

        assertEquals(listOf("Зараз гляну.", "У Києві сонячно."), streamed)
        assertEquals(listOf("Зараз гляну.", "У Києві сонячно.", "Парасолька не знадобиться."), span.said)
    }

    @Test
    fun `a fenced call is never read aloud`() = runTest {
        val span = FakeSpan()
        val speaker = TestScopeSpeaker(span, scope = this)
        speaker.open()
        speaker.append("```json\n{\"type\":\"tool-call\",\"tool\":\"weather.now\",\"args\":{\"city\":\"Київ\"}}\n```")
        advanceUntilIdle()
        val streamed = span.said.toList()
        speaker.close()
        advanceUntilIdle()

        assertTrue("виклик пішов у синтез: $streamed", streamed.isEmpty())
        assertTrue("огорожа пішла в синтез: ${span.said}", span.said.isEmpty())
    }

    @Test
    fun `prose before a call is heard, the call after it is not`() = runTest {
        val span = FakeSpan()
        val speaker = TestScopeSpeaker(span, scope = this)
        speaker.open()
        speaker.append("Зараз подивлюсь погоду. ")
        speaker.append("""{"type":"tool-call","tool":"weather.now","args":{"city":"Київ"}}""")
        advanceUntilIdle()
        val streamed = span.said.toList()
        speaker.close()
        advanceUntilIdle()

        assertEquals(listOf("Зараз подивлюсь погоду."), streamed)
        assertEquals(listOf("Зараз подивлюсь погоду."), span.said)
    }

    @Test
    fun `what replaces a call at the end is heard after the prose`() = runTest {
        val span = FakeSpan()
        val speaker = TestScopeSpeaker(span, scope = this)
        speaker.open()
        speaker.append("Ще гляну аптеки. ")
        speaker.append("""{"type":"tool-call","tool":"geo.nearby","args":{"category":"аптека","radius":"тисяча метрів"}}""")
        speaker.close("Ще гляну аптеки.\n\nНагадування створено.")
        advanceUntilIdle()

        assertEquals(listOf("Ще гляну аптеки.", "Нагадування створено."), span.said)
    }

    @Test
    fun `a widget after the bridge line still says its line`() = runTest {
        val span = FakeSpan()
        val speaker = TestScopeSpeaker(span, scope = this)
        speaker.open()
        speaker.say("Зараз гляну.")
        speaker.append("""{"type":"weather","say":"сім градусів, дощ"}""")
        speaker.close("Сім градусів, дощ.")
        advanceUntilIdle()

        assertEquals(listOf("Зараз гляну.", "Сім градусів, дощ."), span.said)
    }
}
