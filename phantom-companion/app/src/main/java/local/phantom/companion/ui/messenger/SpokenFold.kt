package local.phantom.companion.ui.messenger

import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.Route
import local.phantom.companion.core.net.peer.PeerMessage

/** Один прохід моделі, складений у слова, виклики й причину поразки. */
internal class SpokenFold {

    private val sb = StringBuilder()
    private var finalText: String? = null
    private val heldCalls = mutableListOf<AIChunk.ToolCall>()

    val calls: List<AIChunk.ToolCall> get() = heldCalls

    var failure: Throwable? = null
        private set

    /** Хто дав цей прохід: сервер Nightpost рахує кожен запит у денний ліміт людини. */
    var route: Route? = null
        private set

    fun feed(chunk: AIChunk) {
        when (chunk) {
            is AIChunk.Token -> {
                sb.append(chunk.text)
                route = chunk.route
            }
            is AIChunk.Final -> {
                finalText = chunk.text
                route = chunk.route
            }
            is AIChunk.ToolCall -> {
                heldCalls += chunk
                route = chunk.route
            }
            is AIChunk.ErrorChunk -> {
                if (chunk.partialText.isNotBlank() && sb.isBlank()) sb.append(chunk.partialText)
                failure = chunk.cause
            }
            else -> Unit
        }
    }

    fun fail(t: Throwable) {
        failure = t
    }

    /** З викликами відповідь — лише Final: виклики з нього вже вийнято, а токени ще несуть їхній JSON. */
    val text: String
        get() = withoutTrailer(
            if (heldCalls.isNotEmpty()) finalText.orEmpty().trim()
            else finalText?.takeIf { it.isNotBlank() } ?: sb.toString().trim(),
        )

    /** Те, що видно, поки слова ростуть: без обʼєкта виклику, що доростає в кінці. */
    val shown: String
        get() {
            val raw = sb.toString()
            return withoutTrailer(raw.substring(0, CALL.find(raw)?.range?.first ?: raw.length)).trimEnd()
        }

    internal companion object {
        /** Службовий рядок підказок, яким SystemPrompts просить закінчити відповідь. */
        private const val TRAILER = "⟦NEXT⟧"

        /**
         * Слова без трейлера ⟦NEXT⟧ — і з тим, що від нього встигло дійти: «⟦», «⟦NE»
         * наприкінці потоку теж не слова. Єдине місце, звідки трейлер не виходить
         * ні в живий рядок, ні в лист, що лягає в журнал.
         */
        fun withoutTrailer(words: String): String {
            val at = words.indexOf(TRAILER)
            if (at >= 0) {
                val head = words.substring(0, at).trimEnd()
                // Рядок підказок кінчається разом із рядком; що ліпить після нього ланцюг (рядок «З памʼяті: …»), лишається.
                val after = words.indexOf('\n', at).takeIf { it >= 0 }?.let { withoutTrailer(words.substring(it)).trim() }.orEmpty()
                return if (after.isEmpty()) head else head + "\n\n" + after
            }
            val tail = words.trimEnd()
            val partial = (TRAILER.length - 1 downTo 1).firstOrNull { tail.endsWith(TRAILER.substring(0, it)) }
            return if (partial == null) words else tail.dropLast(partial).trimEnd()
        }

        /**
         * Рядок журналу таким, яким його бачить сторінка. Лист помічника, записаний до зняття
         * трейлера, несе його в журналі й досі; слова людини не чіпаються, навіть коли вона сама
         * набрала ⟦NEXT⟧.
         */
        fun forPage(m: PeerMessage): PeerMessage {
            if (m.fromId != AgencySessionManager.PHANTOM_AGENT_ID) return m
            val words = withoutTrailer(m.body)
            return if (words == m.body) m else m.copy(body = words)
        }

        /** Обʼєкт виклику чи віджета з будь-якими пробілами, зокрема в ```json. */
        val CALL = Regex("""\{\s*"type"|```json|```\s*\{""")
    }
}
