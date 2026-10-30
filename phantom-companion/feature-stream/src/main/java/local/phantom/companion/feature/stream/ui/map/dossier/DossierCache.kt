package local.phantom.companion.feature.stream.ui.map.dossier

import kotlin.math.roundToLong

/**
 * P1 — LRU-кеш зібраних досьє.
 *
 * Найдорожчий тап у застосунку — ПОВТОРНИЙ: оператор відкриває збережене
 * місце, закриває, відкриває знову — і щоразу платить повним колом
 * Overpass + Nominatim + Вікіпедія. Кеш перетворює друге відкриття на
 * миттєве й ПОВНІСТЮ заповнене: жодного скелетону там, де дані вже були.
 *
 * Кладемо тільки завершені досьє ([PlaceDossier.isComplete]) — інакше
 * напівзібрана картка застрягла б у памʼяті назавжди і жодне повторне
 * відкриття вже ніколи б її не долило.
 *
 * [ttlMs] існує тому, що години роботи й статус «відчинено» протухають: за
 * пів години «Відчинено · до 18:00» стає брехнею. Місткість мала навмисно —
 * це кеш сесії на слабкому телефоні, а не сховище.
 *
 * Синхронізований: знімки кладуть корутини завантажувача, а читає їх
 * композиція — різні потоки.
 */
internal class DossierCache(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val dossier: PlaceDossier, val atMs: Long)

    /** `accessOrder = true` — саме це робить LinkedHashMap власне LRU. */
    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > capacity
    }

    @Synchronized
    fun get(target: DossierTarget): PlaceDossier? {
        val key = dossierKey(target)
        val entry = entries[key] ?: return null
        if (clock() - entry.atMs > ttlMs) {
            entries.remove(key)
            return null
        }
        return entry.dossier
    }

    @Synchronized
    fun put(target: DossierTarget, dossier: PlaceDossier) {
        if (!dossier.isComplete) return
        entries[dossierKey(target)] = Entry(dossier, clock())
    }

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun size(): Int = entries.size

    private companion object {
        const val DEFAULT_CAPACITY = 24
        const val DEFAULT_TTL_MS = 15 * 60 * 1_000L
    }
}

/**
 * Ключ кешу. Ключуємо ЦІЛЬ, а не зіставлений OSM id: у момент пошуку id ще
 * невідомий, тож ключ мусить будуватись однаково і на вході, і на виході.
 *
 * Координата округлюється до ~1 м ([KEY_PRECISION] знаків): та сама
 * бібліотека, відкрита з мапи і зі збережених, приходить із мікроскопічно
 * різними координатами й мусить дати один ключ. Назва в ключі теж потрібна —
 * у ТЦ два POI за метр один від одного це різні місця.
 */
internal fun dossierKey(target: DossierTarget): String {
    val lat = (target.lat * KEY_PRECISION).roundToLong()
    val lon = (target.lon * KEY_PRECISION).roundToLong()
    val name = target.nameHint?.trim()?.lowercase().orEmpty()
    return "$lat:$lon:$name"
}

/** 10^5 ≈ 1 м на широті Києва. */
private const val KEY_PRECISION = 100_000.0
