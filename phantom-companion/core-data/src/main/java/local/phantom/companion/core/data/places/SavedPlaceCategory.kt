package local.phantom.companion.core.data.places

/**
 * Закритий словник категорій мітки. Категорія — це прикметник мітки, не тека:
 * створити свою не можна, а порожньої категорії не існує як обʼєкта, тому нема
 * чого прибирати.
 */
object SavedPlaceCategory {

    const val HOME = "home"
    const val WORK = "work"
    const val SHELTER = "shelter"
    const val FAVOURITE = "favourite"
    const val CUSTOM = "custom"

    val ALL: List<String> = listOf(HOME, WORK, SHELTER, FAVOURITE, CUSTOM)

    /** Дім, робота, укриття видимі здалеку — решта тільки зблизька (§зум-драбина). */
    val VITAL: Set<String> = setOf(HOME, WORK, SHELTER)

    private val SYNONYMS: Map<String, String> = buildMap {
        listOf("home", "дім", "дом", "домівка", "додому", "хата").forEach { put(it, HOME) }
        listOf("work", "робота", "роботу", "офіс", "служба", "база").forEach { put(it, WORK) }
        listOf("shelter", "укриття", "схрон", "сховище", "підвал", "бомбосховище")
            .forEach { put(it, SHELTER) }
        listOf("favourite", "favorite", "улюблене", "улюблені", "подобається", "зірка")
            .forEach { put(it, FAVOURITE) }
        listOf("custom", "місце", "місця", "інше").forEach { put(it, CUSTOM) }
    }

    fun canonical(raw: String?): String {
        val key = raw?.trim()?.lowercase().orEmpty()
        if (key.isEmpty()) return CUSTOM
        return SYNONYMS[key] ?: if (key in ALL) key else CUSTOM
    }

    fun isVital(category: String?): Boolean = canonical(category) in VITAL

    private val WORD = Regex("[\\p{L}\\p{Nd}]+")

    /**
     * «додому» знаходить категорію «дім», але «домофон» — ні: збіг категорії
     * лише по ЦІЛОМУ слову, інакше синонімний словник перехопить світовий запит.
     * Імʼя й адреса шукаються підрядком — там людина друкує початок назви.
     */
    fun matchesQuery(query: String, place: SavedPlaceEntity): Boolean =
        matchesQuery(query, place.label, place.address, place.category)

    /**
     * Та сама брама для тих, хто тримає мітку не як рядок таблиці: мапа несе
     * її як PoiEntity. Правило мусить бути ОДНЕ — доти екран пошуку мав власне,
     * вужче (тільки імʼя), тож «додому» не знаходило дім, названий «Хата».
     */
    fun matchesQuery(
        query: String,
        label: String,
        address: String?,
        category: String?,
    ): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return false
        if (label.lowercase().contains(q)) return true
        if (address?.lowercase()?.contains(q) == true) return true
        val canonical = canonical(category)
        return WORD.findAll(q).any { SYNONYMS[it.value] == canonical }
    }
}
