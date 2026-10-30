package local.phantom.companion.core.voice.live

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Результат руки PHANTOM словами — те, що модель перекаже вголос. */
data class Outcome(val ok: Boolean, val text: String)

/** Вердикт драбини тіла для дієслова (див. `BodyLadder` у `:app`). */
sealed interface BodyVerdict {
    data object Do : BodyVerdict

    /** Можна, але лише з «так» людини. */
    data object Ask : BodyVerdict

    data class Refuse(val why: String) : BodyVerdict
}

sealed interface Person {
    data class One(val id: String, val name: String) : Person

    data class NotFound(val why: String) : Person
}

/**
 * Руки PHANTOM у дзвінку. Реалізація — у `:app` (памʼять, мапа, месенджер,
 * драбина тіла); тут лише контракт, щоб міст і ворота згоди перевірялись без
 * Android.
 */
interface CallHands {
    suspend fun recall(query: String): Outcome
    suspend fun mapLookup(query: String): Outcome
    suspend fun readMessages(who: String?): Outcome
    suspend fun person(who: String): Person
    suspend fun sendMessage(personId: String, text: String): Outcome
    fun bodyVerdict(verb: String): BodyVerdict
    fun bodyTitle(verb: String): String?
    suspend fun bodyAct(verb: String, args: Map<String, String>): Outcome
}

/** Дія, що чекає «так» людини. */
data class PendingAction(
    val key: String,
    val ask: String,
    val askedAtMs: Long,
    val consented: Boolean = false,
)

/**
 * Ворота згоди. Згоду дає ЛИШЕ людина: її власні слова (розпізнані з її мови,
 * `inputTranscription`) або дотик до «Так» на екрані. Модель згоди дати не може —
 * функції «підтвердити» в неї просто немає, а повторний виклик тієї самої дії
 * пропускається лише тоді, коли людина вже сказала «так» саме на неї.
 */
class ConfirmGate(private val now: () -> Long) {
    private val _pending = MutableStateFlow<PendingAction?>(null)
    val pending: StateFlow<PendingAction?> = _pending.asStateFlow()

    /** Репліка людини від останнього питання, наростаюча. */
    private var said = StringBuilder()

    fun ask(key: String, ask: String) {
        said = StringBuilder()
        _pending.value = PendingAction(key, ask, now())
    }

    /** Шматок розпізнаної мови людини. Оцінюється вся репліка, а не шматок. */
    fun heard(delta: String) {
        val p = live() ?: return
        said.append(delta)
        when (Consent.of(said.toString())) {
            Consent.YES -> _pending.value = p.copy(consented = true)
            Consent.NO -> _pending.value = null
            Consent.UNCLEAR -> if (p.consented) _pending.value = p.copy(consented = false)
        }
    }

    /** Нова репліка людини починається з нуля. */
    fun turnEnded() {
        said = StringBuilder()
    }

    fun tapped(yes: Boolean) {
        val p = live() ?: return
        _pending.value = if (yes) p.copy(consented = true) else null
    }

    /** true рівно раз — коли ця сама дія вже має «так» людини. */
    fun pass(key: String): Boolean {
        val p = live() ?: return false
        if (p.key != key || !p.consented) return false
        _pending.value = null
        return true
    }

    fun isAsked(key: String): Boolean = live()?.key == key

    fun clear() {
        _pending.value = null
    }

    private fun live(): PendingAction? {
        val p = _pending.value ?: return null
        if (now() - p.askedAtMs > TTL_MS) {
            _pending.value = null
            return null
        }
        return p
    }

    enum class Consent {
        YES, NO, UNCLEAR;

        companion object {
            private val YES_WORDS = setOf(
                "так", "да", "ага", "угу", "надсилай", "надішли", "відправляй", "відправ", "роби", "зроби",
                "давай", "підтверджую", "можна", "звісно", "звичайно", "yes",
            )
            private val NO_WORDS = setOf("ні", "нє", "не", "стоп", "скасуй", "скасувати", "відміна", "no")
            private val SOFT = setOf("так", "давай", "можна", "ну", "будь", "ласка", "звісно", "звичайно")

            /**
             * Коротка репліка, що ЦІЛА є згодою: «так», «так, надсилай», «давай».
             * «Так, але зміни текст» — не згода; «ні» будь-де — відмова.
             */
            fun of(text: String): Consent {
                val words = text.lowercase()
                    .replace('’', 'ʼ').replace('\'', 'ʼ')
                    .split(Regex("[^\\p{L}ʼ]+")).filter { it.isNotEmpty() }
                if (words.isEmpty()) return UNCLEAR
                if (words.any { it in NO_WORDS }) return NO
                if (words.size > 4) return UNCLEAR
                if (words.first() !in YES_WORDS) return UNCLEAR
                return if (words.all { it in YES_WORDS || it in SOFT }) YES else UNCLEAR
            }
        }
    }

    companion object {
        const val TTL_MS = 120_000L
    }
}

/**
 * Виклики функцій Gemini Live → руки PHANTOM.
 *
 * Читати (памʼять, мапа, листи) — одразу. Надіслати лист — завжди через «так»
 * людини. Дієслова тіла — через драбину: що вона дозволяє мовчки, те робиться,
 * що просить згоди — іде у ворота, що забороняє — відмова словами драбини.
 */
class LiveToolBridge(
    private val hands: CallHands,
    val gate: ConfirmGate,
) {
    val declarations: JsonArray = buildJsonArray {
        add(fn(RECALL, "Згадати, що Nightpost знає про людину-власника: факти, імена, домовленості.", "query" to "що згадати"))
        add(fn(MAP, "Знайти на мапі місце, обʼєкт або категорію поруч (укриття, аптека, вода, адреса).", "query" to "що шукати"))
        add(
            fn(
                READ,
                "Прочитати останні листи в месенджері Nightpost. Без who — останні вхідні від усіх.",
                "who" to "імʼя людини?",
            ),
        )
        add(
            fn(
                SEND,
                "Надіслати лист людині в месенджері Nightpost. Іде ЛИШЕ після «так» власника: " +
                    "спершу прийде awaiting_confirmation — прочитай питання вголос і чекай відповіді, " +
                    "потім виклич ще раз з тими самими аргументами.",
                "who" to "імʼя людини", "text" to "текст листа",
            ),
        )
        add(
            fn(
                BODY,
                "Дія телефона через драбину дозволів: phone.ring, phone.notify, phone.speak, phone.locate, " +
                    "phone.stop. Що драбина просить погодити — прийде awaiting_confirmation.",
                "verb" to "дієслово", "text" to "текст для notify/speak?",
            ),
        )
    }

    suspend fun handle(call: ToolCall): ToolReply {
        val out = try {
            dispatch(call)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            err("рука зламалась: ${e.javaClass.simpleName}")
        }
        return ToolReply(call.id, call.name, out)
    }

    private suspend fun dispatch(call: ToolCall): JsonObject {
        val a = call.args
        return when (call.name) {
            RECALL -> outcome(hands.recall(a.s("query")))
            MAP -> outcome(hands.mapLookup(a.s("query")))
            READ -> outcome(hands.readMessages(a.s("who").ifBlank { null }))
            SEND -> send(a.s("who"), a.s("text"))
            BODY -> body(a.s("verb"), a.s("text"))
            else -> err("такої руки немає: ${call.name}")
        }
    }

    private suspend fun send(who: String, text: String): JsonObject {
        if (who.isBlank() || text.isBlank()) return err("бракує адресата або тексту листа")
        val person = when (val p = hands.person(who)) {
            is Person.NotFound -> return err(p.why)
            is Person.One -> p
        }
        val key = "$SEND|${person.id}|${text.trim()}"
        if (gate.pass(key)) return outcome(hands.sendMessage(person.id, text.trim()))
        if (!gate.isAsked(key)) gate.ask(key, "Кому: ${person.name}. Текст: «${text.trim()}». Надсилати?")
        return awaiting(gate.pending.value?.ask.orEmpty())
    }

    private suspend fun body(verb: String, text: String): JsonObject {
        val args = if (text.isBlank()) emptyMap() else mapOf("text" to text)
        return when (val v = hands.bodyVerdict(verb)) {
            is BodyVerdict.Refuse -> err(v.why)
            BodyVerdict.Do -> outcome(hands.bodyAct(verb, args))
            BodyVerdict.Ask -> {
                val key = "$BODY|$verb|$text"
                if (gate.pass(key)) return outcome(hands.bodyAct(verb, args))
                if (!gate.isAsked(key)) gate.ask(key, "Телефон: ${hands.bodyTitle(verb) ?: verb}?")
                awaiting(gate.pending.value?.ask.orEmpty())
            }
        }
    }

    companion object {
        const val RECALL = "memory_recall"
        const val MAP = "map_lookup"
        const val READ = "messages_read"
        const val SEND = "message_send"
        const val BODY = "phone_act"

        private fun fn(name: String, description: String, vararg params: Pair<String, String>) = buildJsonObject {
            put("name", name)
            put("description", description)
            putJsonObject("parameters") {
                put("type", "OBJECT")
                putJsonObject("properties") {
                    params.forEach { (k, d) ->
                        putJsonObject(k) {
                            put("type", "STRING")
                            put("description", d.removeSuffix("?"))
                        }
                    }
                }
                putJsonArray("required") {
                    params.filterNot { it.second.endsWith("?") }.forEach { add(JsonPrimitive(it.first)) }
                }
            }
        }

        private fun JsonObject.s(k: String): String = (this[k] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

        private fun outcome(o: Outcome) = buildJsonObject {
            put("ok", o.ok)
            put(if (o.ok) "result" else "error", o.text)
        }

        private fun err(why: String) = buildJsonObject {
            put("ok", false)
            put("error", why)
        }

        private fun awaiting(ask: String) = buildJsonObject {
            put("ok", false)
            put("status", "awaiting_confirmation")
            put("ask", ask)
            put("note", "Нічого ще не зроблено. Спитай власника вголос і чекай його «так».")
        }
    }
}
