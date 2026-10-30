package local.phantom.companion.core.voice.live

/**
 * Стенограма дзвінка: шматки розпізнаної мови людини і сказаного PHANTOM
 * складаються в репліки. Після дзвінка вона лягає в розмову з PHANTOM — і
 * текстовий розум наступного разу бачить, про що говорили голосом.
 */
class CallTranscript(private val now: () -> Long) {

    enum class Who { OWNER, PHANTOM }

    data class Line(val who: Who, val text: String, val atMs: Long, val cut: Boolean = false)

    private val closed = mutableListOf<Line>()
    private var openWho: Who? = null
    private val open = StringBuilder()
    private var openAt = 0L
    private var openCut = false

    @Synchronized
    fun owner(delta: String) = append(Who.OWNER, delta)

    @Synchronized
    fun phantom(delta: String) = append(Who.PHANTOM, delta)

    /** Людина перебила PHANTOM — його репліка в стенограмі обірвана, а не повна. */
    @Synchronized
    fun interrupted() {
        if (openWho == Who.PHANTOM) openCut = true
        close()
    }

    @Synchronized
    fun closeTurn() = close()

    /** Останнє, що сказав [who], — для субтитрів. */
    @Synchronized
    fun last(who: Who): String =
        if (openWho == who) tidy(open.toString()) else closed.lastOrNull { it.who == who }?.text.orEmpty()

    @Synchronized
    fun lines(): List<Line> {
        val all = closed.toMutableList()
        if (openWho != null) {
            val t = tidy(open.toString())
            if (t.isNotEmpty()) all += Line(openWho!!, t, openAt, openCut)
        }
        return all
    }

    private fun append(who: Who, delta: String) {
        if (delta.isEmpty()) return
        if (openWho != who) {
            close()
            openWho = who
            openAt = now()
        }
        open.append(delta)
    }

    private fun close() {
        val who = openWho ?: return
        val t = tidy(open.toString())
        if (t.isNotEmpty()) closed += Line(who, t, openAt, openCut)
        openWho = null
        open.setLength(0)
        openCut = false
    }

    private fun tidy(s: String) = s.replace(Regex("\\s+"), " ").replace(Regex(" ([,.!?:;])"), "$1").trim()
}

/** Куди лягає стенограма після дзвінка (у `:app` — розмова з PHANTOM у месенджері). */
fun interface TranscriptSink {
    fun save(lines: List<CallTranscript.Line>)
}
