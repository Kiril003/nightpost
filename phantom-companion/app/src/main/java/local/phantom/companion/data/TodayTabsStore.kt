package local.phantom.companion.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Порядок і сховані сторінки «Сьогодні» (кола й «Дописи»), окремо для кожної своєї особи. */
class TodayTabsStore internal constructor(private val slot: PeerLogSlot) {

    constructor(context: Context) : this(EncryptedSlot(context, FILE_NAME))

    @Serializable
    data class Tabs(val order: List<String>, val hidden: List<String>)

    private val json = Json { ignoreUnknownKeys = true }

    fun get(me: String): Tabs =
        slot.read(KEY_PREFIX + me)
            ?.let { raw -> runCatching { json.decodeFromString<Tabs>(raw) }.getOrNull() }
            ?.let(::clean)
            ?: Tabs(emptyList(), emptyList())

    fun put(me: String, tabs: Tabs): Tabs {
        val kept = clean(tabs)
        slot.write(KEY_PREFIX + me, json.encodeToString(kept))
        return kept
    }

    private fun clean(t: Tabs) = Tabs(
        t.order.map(::migrateKey).filter { it in KEYS }.distinct(),
        t.hidden.map(::migrateKey).filter { it in KEYS }.distinct(),
    )

    private fun migrateKey(key: String): String = when (key) {
        "c:family" -> "c:personal"
        "c:study", "c:travel" -> "c:social"
        else -> key
    }

    companion object {
        val KEYS: Set<String> = PeerCircle.ALL.map { "c:$it" }.toSet() + "posts"
        private const val FILE_NAME = "phantom_today_tabs_v1"
        private const val KEY_PREFIX = "tabs:"
    }
}
