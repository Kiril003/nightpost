package local.phantom.companion.core.voice.stream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import local.phantom.companion.core.voice.UaTtsNormalizer

/**
 * ЖИВА РОЗМОВА — одна репліка, текст якої ще пишеться.
 *
 * Реалізує [SupertonicRoute.Stream] і будь-що інше, що вміє говорити
 * шматками в один безперервний сеанс відтворення.
 */
interface SpeechSpan {
    /** Промовити наступний шматок. false — синтез не впорався. */
    suspend fun say(segment: String): Boolean

    /** Текст, який людина СПРАВДІ почула (за головкою відтворення). */
    fun heardSoFar(): String

    /** Текст, відданий синтезу — почутий чи ні. */
    fun enqueuedText(): String

    /** Більше тексту не буде: договорити чергу. */
    suspend fun finish(timeoutMs: Long = 60_000L)

    /** Обірвати мид-слово. */
    fun cancel()
}

/**
 * ЖИВА РОЗМОВА — PHANTOM говорить, поки думає.
 *
 * Досі голос вмикався тільки після того, як уся відповідь моделі осіла
 * (`StreamViewModel.finalizeAssistant`), і людина слухала 4–8 секунд
 * тиші за кожну репліку. Цей клас розрізає потік токенів на промовні
 * шматки й віддає їх синтезу ще під час генерації.
 *
 * ## Що тут неочевидне
 *
 * **Один сеанс на всю репліку.** Говорити реченнями через звичайний
 * `RoutedTts.speak` було б простіше, але кожен виклик відкриває й закриває
 * `SpeechActivityHub`, а кожне таке перемикання відкриває воротар
 * транскрипції в наш власний хвіст ехо. Тому проміжок мовлення
 * відкривається один раз і закривається один раз.
 *
 * **Мʼякий і жорсткий ліміт.** Перший шматок хочеться віддати якнайшвидше,
 * але різати фразу посеред думки — чутно. Тому: якщо в межах вікна є кінець
 * речення чи кома — ріжемо там (навіть коротко); якщо ні — чекаємо до
 * жорсткого ліміту й ріжемо по пробілу.
 *
 * **JSON не озвучується.** Відповідь-віджет починається з `{`; поки перший
 * значущий символ не доведе, що це проза, не промовляється нічого —
 * озвучення `say` лишається за шляхом завершення ходу, як і було.
 */
class IncrementalSpeaker(
    private val scope: CoroutineScope,
    private val openSpan: suspend () -> SpeechSpan?,
    /** Коли стрімити нема чим — сказати все одним махом наприкінці ходу. */
    private val fallbackSpeak: suspend (String) -> Unit,
    private val onSpanBegin: () -> Unit = {},
    private val onSpanEnd: () -> Unit = {},
    /**
     * Коротке «чую» голосом PHANTOM, якщо модель мовчить довше за
     * [ackAfterMs]. Формулювання — голос персони, не інженерне рішення.
     */
    private val acknowledgement: () -> String? = { null },
    private val ackAfterMs: Long = 900L,
    /** Пільга для відповіді, яка майже встигла: краще мовчати, ніж заважати. */
    private val ackGraceMs: Long = 250L,
    private val onFirstClause: () -> Unit = {},
    private val onFirstAudio: () -> Unit = {},
    private val ramp: List<Pair<Int, Int>> = DEFAULT_RAMP,
    /** Кожен шматок, відданий синтезу, — для годинника тиші ([QuietGuard]). */
    private val onEnqueue: (String) -> Unit = {},
) {

    private val raw = StringBuilder()
    private var consumed = 0

    // Читається таймером підтвердження з іншої корутини.
    @Volatile
    private var emitted = 0

    private val chunks = Channel<String>(Channel.UNLIMITED)
    private var pump: Job? = null
    private var ackJob: Job? = null
    private var span: SpeechSpan? = null

    @Volatile
    private var spoke = false

    @Volatile
    private var closed = false

    @Volatile
    private var cancelled = false

    @Volatile
    private var proseProven: Boolean? = null

    /** Чи прозвучало хоч слово цієї репліки. */
    val hasSpoken: Boolean get() = spoke

    /** Те, що людина почула, — для позначки перехоплення в історії. */
    fun heardSoFar(): String = span?.heardSoFar().orEmpty()

    fun open() {
        if (pump != null) return
        onSpanBegin()
        pump = scope.launch {
            for (piece in chunks) {
                if (cancelled) break
                val live = span ?: openSpan()?.also { span = it }
                if (live == null) {
                    // Стрімити нема чим — усе одно не мовчимо: віддаємо
                    // шматок звичайним двигуном.
                    runCatching { fallbackSpeak(piece) }
                    markSpoken()
                    continue
                }
                if (!live.say(piece)) {
                    runCatching { fallbackSpeak(piece) }
                }
                markSpoken()
            }
        }
        val ack = acknowledgement()
        if (!ack.isNullOrBlank()) {
            ackJob = scope.launch {
                delay(ackAfterMs)
                // Договір «ніколи не мовчати» існує, щоб убити мертву тишу, а
                // не щоб приліпити склад перед швидкою відповіддю. Якщо
                // перший шматок уже в черзі — мовчимо й даємо відповіді бути
                // відповіддю; коротка пільга ловить тих, хто майже встиг.
                if (emitted > 0) return@launch
                delay(ackGraceMs)
                if (emitted == 0 && !cancelled && !closed) enqueue(ack)
            }
        }
    }

    /** Наростити відповідь на черговий шматок токенів. */
    fun append(delta: String) {
        if (cancelled || closed || delta.isEmpty()) return
        synchronized(raw) { raw.append(delta) }
        drain(force = false)
    }

    /**
     * Промовити фразу поза чергою тексту моделі — місток інструмента
     * («зараз гляну»), сказаний у момент виклику, а не наприкінці ходу.
     */
    fun say(line: String) {
        if (cancelled || closed) return
        enqueue(line)
    }

    /**
     * Новий прохід моделі в тій самій репліці (після інструментів): неозвучене
     * з попереднього було викликом, а не відповіддю, і прозу доводимо наново.
     */
    fun restart() {
        if (cancelled || closed) return
        synchronized(raw) {
            raw.setLength(0)
            consumed = 0
            proseProven = null
        }
    }

    /** Відповідь дописано: договорити хвіст і закрити проміжок. */
    fun close(finalText: String? = null) {
        if (closed) return
        closed = true
        ackJob?.cancel()
        if (finalText != null) {
            synchronized(raw) {
                if (consumed == 0) {
                    // З тексту моделі ще нічого не прозвучало (місток і «чую» —
                    // не відповідь): підсумковий текст і Є реплікою.
                    // Сюди потрапляє віджет — потік був JSON і мовчав, а
                    // говорити треба його рядок `say`.
                    raw.setLength(0)
                    raw.append(finalText)
                    consumed = 0
                    proseProven = null
                } else if (finalText.length > speakable().length) {
                    // Потік і підсумок розійшлись (склейка, виправлення, результати
                    // інструментів замість виклику) — добираємо лише неозвучений хвіст.
                    raw.setLength(0)
                    raw.append(finalText)
                }
            }
        }
        drain(force = true)
        chunks.close()
        scope.launch {
            // Стеля, а не природна умова: якщо синтез завис, приєднання
            // ніколи б не повернулось, [onSpanEnd] не спрацював би — і
            // SpeechActivityHub назавжди лишився б у стані «говорить», через
            // що канал більше не транскрибує жодного слова. Краще закрити
            // проміжок тихо і глухою тишею, ніж оглухнути назовсім.
            withTimeoutOrNull(SPAN_CLOSE_MAX_MS) {
                pump?.join()
                runCatching { span?.finish() }
            } ?: run {
                pump?.cancel()
                span?.cancel()
            }
            onSpanEnd()
        }
    }

    /** Людина перебила: кинути все, що не прозвучало. */
    fun cancel() {
        if (cancelled) return
        cancelled = true
        closed = true
        ackJob?.cancel()
        pump?.cancel()
        chunks.close()
        span?.cancel()
        onSpanEnd()
    }

    private fun markSpoken() {
        if (!spoke) {
            spoke = true
            onFirstAudio()
        }
    }

    private fun enqueue(piece: String) {
        val speakable = UaTtsNormalizer.normalize(piece).trim()
        if (speakable.isBlank()) return
        if (emitted == 0) onFirstClause()
        emitted++
        onEnqueue(speakable)
        chunks.trySend(speakable)
    }

    private fun drain(force: Boolean) {
        while (true) {
            val piece = synchronized(raw) { takeChunk(force) } ?: return
            enqueue(piece)
        }
    }

    /** Викликається під `synchronized(raw)`. */
    private fun takeChunk(force: Boolean): String? {
        val full = speakable()
        if (consumed >= full.length) return null
        val pending = full.substring(consumed)

        if (proseProven == null) {
            val firstReal = pending.firstOrNull { !it.isWhitespace() } ?: return null
            proseProven = firstReal != '{'
        }
        if (proseProven == false) return null

        val (soft, hard) = ramp.getOrElse(emitted) { ramp.last() }
        if (force) {
            val tail = pending.trim()
            consumed = full.length
            return tail.ifBlank { null }
        }

        val window = pending.take(hard)
        // Перший розріз вирішує час до першого слова, всі наступні — щільність
        // конвеєра. Тому відкривач бере НАЙРАНІШУ придатну межу, а далі —
        // найпізнішу, щоб сегмент був повним.
        val opener = emitted == 0

        boundaryIn(window, SENTENCE_ENDERS, opener)?.let { return cut(pending, it + 1) }
        if (pending.length >= soft) {
            boundaryIn(window, CLAUSE_BREAKS, opener)?.let { return cut(pending, it + 1) }
        }
        if (pending.length >= hard) {
            val space = window.lastIndexOf(' ')
            return cut(pending, if (space >= MIN_CHARS) space + 1 else hard)
        }
        return null
    }

    /** Прихований трейлер ⟦NEXT⟧ — телеметрія; обʼєкт виклику чи віджета, зокрема в ```json, — теж не мовлення. Під `synchronized(raw)`. */
    private fun speakable(): String {
        val text = raw.toString().substringBefore('⟦')
        return UNSPOKEN.find(text)?.let { text.substring(0, it.range.first) } ?: text
    }

    private fun boundaryIn(window: String, marks: CharArray, earliest: Boolean): Int? {
        val at = if (earliest) {
            (MIN_CHARS until window.length).firstOrNull { window[it] in marks } ?: -1
        } else {
            window.indexOfLast { it in marks }
        }
        return at.takeIf { it >= MIN_CHARS }
    }

    private fun cut(pending: String, at: Int): String? {
        consumed += at
        return pending.substring(0, at).trim().ifBlank { null }
    }

    companion object {
        /** «Гаразд,» — сім символів, і це вже добрий відкривач. */
        private const val MIN_CHARS = 5

        /** Стеля закриття проміжку: довша за будь-яку правдоподібну репліку. */
        private const val SPAN_CLOSE_MAX_MS = 90_000L
        private val SENTENCE_ENDERS = charArrayOf('.', '!', '?', '…')
        private val CLAUSE_BREAKS = charArrayOf(',', ';', ':', '—')
        private val UNSPOKEN = Regex("""\{\s*"type"|```json|```\s*\{""")

        /**
         * (мʼякий, жорсткий) ліміт на шматок.
         *
         * Сходинки не довільні: за польовим виміром в коді (A56) синтез коштує
         * ~33 мс на символ, а звучить символ ~94 мс, тобто шматок прикриває
         * синтез наступного, доки той не більший за нього приблизно втричі.
         * Ряд 28 → 78 → 200 тримається під цим стелею з запасом; ширші
         * сходинки чутно як шов у місці склейки на слабшому чи гарячому
         * апараті.
         */
        val DEFAULT_RAMP = listOf(28 to 64, 78 to 150, 200 to 300)
    }
}
