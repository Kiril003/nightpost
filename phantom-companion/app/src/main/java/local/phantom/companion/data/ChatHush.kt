package local.phantom.companion.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.Calendar

/**
 * Muted chats: id → until (ms), null = until the person unmutes. The web writes it over
 * the bridge, with the chat's name so Settings › Notifications can list it natively.
 */
data class ChatHush(val until: Map<String, Long?> = emptyMap(), val titles: Map<String, String> = emptyMap()) {

    fun hushes(chatId: String, nowMs: Long): Boolean {
        if (chatId !in until) return false
        val end = until[chatId] ?: return true
        return nowMs < end
    }

    fun live(nowMs: Long): ChatHush {
        val kept = until.filter { (_, end) -> end == null || nowMs < end }
        return ChatHush(kept, titles.filterKeys { it in kept })
    }

    fun with(chatId: String, untilMs: Long?, title: String? = null): ChatHush =
        ChatHush(until + (chatId to untilMs), if (title.isNullOrBlank()) titles else titles + (chatId to title))

    fun without(chatId: String): ChatHush = ChatHush(until - chatId, titles - chatId)

    /** Soonest end first, «until I unmute» last — the order the exceptions list reads. */
    fun rows(): List<Triple<String, Long?, String?>> =
        until.entries.sortedWith(compareBy({ it.value == null }, { it.value ?: 0L }))
            .map { (id, end) -> Triple(id, end, titles[id]) }
}

/**
 * Rule from the web's `phantom_hush_rules.v1`, already resolved per chat by the web
 * (chat hours over circle hours). No hours = full mute. [ask] lets a question in a
 * one-to-one chat ring. The web's «plan soon» exception is not evaluated here.
 */
data class HushRule(val fromMin: Int? = null, val toMin: Int? = null, val ask: Boolean = false) {

    enum class Verdict { RING, SILENT, DROP }

    fun verdict(minuteOfDay: Int, oneToOne: Boolean, text: String): Verdict {
        val from = fromMin
        val to = toMin
        val hours = from != null && to != null
        val quiet = when {
            from == null || to == null -> true
            from < to -> minuteOfDay in from until to
            else -> minuteOfDay >= from || minuteOfDay < to
        }
        if (!quiet) return Verdict.RING
        if (ask && oneToOne && '?' in text) return Verdict.RING
        return if (hours) Verdict.SILENT else Verdict.DROP
    }

    companion object {
        private val TIME = Regex("^(\\d{1,2}):(\\d{2})$")

        fun minutes(hhmm: String?): Int? {
            val m = TIME.matchEntire(hhmm ?: return null) ?: return null
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            return if (h in 0..23 && min in 0..59) h * 60 + min else null
        }

        fun of(from: String?, to: String?, ask: Boolean): HushRule? {
            if (from == null && to == null) return HushRule(ask = ask)
            val f = minutes(from) ?: return null
            val t = minutes(to) ?: return null
            return if (f == t) null else HushRule(f, t, ask)
        }
    }
}

class ChatHushStore(context: Context) {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<ChatHush> = _state.asStateFlow()

    fun hushesNow(chatId: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        _state.value.hushes(chatId, nowMs)

    fun live(nowMs: Long = System.currentTimeMillis()): ChatHush = _state.value.live(nowMs)

    fun set(chatId: String, untilMs: Long?, title: String? = null) = save(_state.value.with(chatId, untilMs, title))

    fun clear(chatId: String) = save(_state.value.without(chatId))

    private val _mentionsThrough = MutableStateFlow(prefs.getBoolean(MENTIONS, true))
    /** @ від учасника групи дзвонить і в розмові без звуку, і в тихі години профілю. */
    val mentionsThrough: StateFlow<Boolean> = _mentionsThrough.asStateFlow()

    fun setMentionsThrough(on: Boolean) {
        _mentionsThrough.value = on
        prefs.edit().putBoolean(MENTIONS, on).apply()
    }

    private val _rules = MutableStateFlow(readRules())
    /** Empty until the web sends its rules: then nothing changes against plain mute. */
    val rules: StateFlow<Map<String, HushRule>> = _rules.asStateFlow()

    fun setRules(next: Map<String, HushRule>) {
        _rules.value = next
        val o = JSONObject()
        next.forEach { (id, r) ->
            o.put(id, JSONObject().apply {
                r.fromMin?.let { put("f", it) }
                r.toMin?.let { put("t", it) }
                if (r.ask) put("a", true)
            })
        }
        prefs.edit().putString(RULES, o.toString()).apply()
    }

    fun ruleVerdict(chatId: String, oneToOne: Boolean, text: String, nowMs: Long = System.currentTimeMillis()): HushRule.Verdict {
        val rule = _rules.value[chatId] ?: return HushRule.Verdict.RING
        val c = Calendar.getInstance().apply { timeInMillis = nowMs }
        return rule.verdict(c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE), oneToOne, text)
    }

    private fun readRules(): Map<String, HushRule> {
        val o = runCatching { JSONObject(prefs.getString(RULES, null) ?: return emptyMap()) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, HushRule>()
        o.keys().forEach { id ->
            val r = o.optJSONObject(id) ?: return@forEach
            val f = if (r.has("f")) r.optInt("f", -1).takeIf { it in 0 until 1440 } else null
            val t = if (r.has("t")) r.optInt("t", -1).takeIf { it in 0 until 1440 } else null
            if ((f == null) != (t == null)) return@forEach
            out[id] = HushRule(f, t, r.optBoolean("a", false))
        }
        return out
    }

    private fun save(next: ChatHush) {
        val live = next.live(System.currentTimeMillis())
        _state.value = live
        val o = JSONObject()
        live.until.forEach { (id, end) -> o.put(id, end ?: JSONObject.NULL) }
        prefs.edit().putString(KEY, o.toString()).putString(TITLES, JSONObject(live.titles).toString()).apply()
    }

    private fun read(): ChatHush {
        val raw = prefs.getString(KEY, null) ?: return ChatHush()
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return ChatHush()
        val out = LinkedHashMap<String, Long?>()
        o.keys().forEach { id ->
            if (id.isBlank()) return@forEach
            out[id] = if (o.isNull(id)) null else o.optLong(id, -1L).takeIf { it > 0 } ?: return@forEach
        }
        val names = runCatching { JSONObject(prefs.getString(TITLES, null) ?: "{}") }.getOrNull()
        val titles = names?.keys()?.asSequence()?.filter { it in out }?.associateWith { names.optString(it) }
            ?.filterValues { it.isNotBlank() }.orEmpty()
        return ChatHush(out, titles)
    }

    companion object {
        const val FILE_NAME = "phantom_chat_hush_v1"
        private const val KEY = "chats"
        private const val TITLES = "titles"
        private const val MENTIONS = "mentions_through"
        private const val RULES = "rules"
    }
}
