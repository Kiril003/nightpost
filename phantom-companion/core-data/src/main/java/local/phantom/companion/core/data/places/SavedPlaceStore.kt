package local.phantom.companion.core.data.places

import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** The operator's personal saved places. A same-label save updates in place. */
class SavedPlaceStore(private val dao: SavedPlaceDao) {

    suspend fun save(
        label: String,
        lat: Double,
        lon: Double,
        category: String? = null,
        address: String? = null,
        note: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        val clean = label.trim()
        if (clean.isEmpty()) return ""
        val cat = category?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { SavedPlaceCategory.canonical(it) }
        val existing = dao.byLabel(clean)
        if (existing != null) {
            val effective = cat ?: existing.category
            dao.update(
                existing.copy(
                    lat = lat,
                    lon = lon,
                    // Omitted category keeps the existing one — a re-save with just
                    // new coordinates must not demote «home» to «custom».
                    category = effective,
                    address = address?.trim()?.takeIf { it.isNotEmpty() } ?: existing.address,
                    note = note?.trim()?.takeIf { it.isNotEmpty() } ?: existing.note,
                    lastUsedMs = nowMs,
                ),
            )
            dropAnchorSiblings(effective, keepId = existing.id)
            return existing.id
        }
        val id = UUID.randomUUID().toString()
        dao.insert(
            SavedPlaceEntity(
                id = id,
                label = clean,
                category = cat ?: "custom",
                lat = lat,
                lon = lon,
                address = address?.trim()?.takeIf { it.isNotEmpty() },
                note = note?.trim()?.takeIf { it.isNotEmpty() },
                createdAt = nowMs,
                lastUsedMs = nowMs,
            ),
        )
        dropAnchorSiblings(cat ?: "custom", keepId = id)
        return id
    }

    /**
     * Дім і робота — якорі: `transit.home` бере ПЕРШИЙ рядок категорії, тож
     * другий «home» вів би додому в попереднє життя. Інваріант живе тут, у
     * єдиному горлі запису, а не в кожній руці, що зберігає.
     */
    private suspend fun dropAnchorSiblings(category: String, keepId: String) {
        if (category != SavedPlaceCategory.HOME && category != SavedPlaceCategory.WORK) return
        dao.byCategory(category).filter { it.id != keepId }.forEach { dao.deleteById(it.id) }
    }

    sealed interface MarkResult {
        /** [overwrote] — мітка з таким імʼям уже була; аркуш мусить сказати це словом. */
        data class Saved(val id: String, val label: String, val overwrote: Boolean) : MarkResult
        /** Брама імені: без імені мітки не буває — це точка, а не мітка. */
        data object Unnamed : MarkResult
    }

    /**
     * Єдиний вхід у довгу памʼять місць. Відмова тут не косметична: безіменне
     * збереження — це не памʼять, це осад, і саме воно перетворює мапу на
     * звалище однакових зірок.
     */
    suspend fun saveMark(
        label: String?,
        lat: Double,
        lon: Double,
        category: String? = null,
        address: String? = null,
        note: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): MarkResult {
        if (!MarkName.isNamed(label)) return MarkResult.Unnamed
        val clean = MarkName.clean(label)
        val overwrote = dao.byLabel(clean) != null
        val id = save(clean, lat, lon, category, address, note, nowMs)
        if (id.isEmpty()) return MarkResult.Unnamed
        return MarkResult.Saved(id, clean, overwrote)
    }

    /** «Мої мітки» в пошуку: імʼя, адреса, і категорія по цілому слову. */
    suspend fun search(query: String, limit: Int = 4): List<SavedPlaceEntity> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return dao.getAll().filter { SavedPlaceCategory.matchesQuery(q, it) }.take(limit)
    }

    /** Кожен маршрут до мітки піднімає її в списку; єдине, для чого є last_used_ms. */
    suspend fun touch(id: String, nowMs: Long = System.currentTimeMillis()) {
        val existing = dao.byId(id) ?: return
        dao.update(existing.copy(lastUsedMs = nowMs))
    }

    suspend fun removeByLabel(label: String): Boolean {
        val existing = dao.byLabel(label.trim()) ?: return false
        dao.deleteById(existing.id)
        return true
    }

    suspend fun rename(id: String, newLabel: String): Boolean {
        if (!MarkName.isNamed(newLabel)) return false
        val existing = dao.byId(id) ?: return false
        dao.update(existing.copy(label = MarkName.clean(newLabel)))
        return true
    }

    suspend fun recategorize(id: String, category: String): Boolean {
        val canonical = SavedPlaceCategory.canonical(category)
        val existing = dao.byId(id) ?: return false
        dao.update(existing.copy(category = canonical))
        dropAnchorSiblings(canonical, keepId = id)
        return true
    }

    suspend fun list(category: String? = null): List<SavedPlaceEntity> =
        if (category.isNullOrBlank()) dao.getAll() else dao.byCategory(category.trim())

    suspend fun byLabel(label: String): SavedPlaceEntity? = dao.byLabel(label.trim())

    /** Live list for the places card; null [category] = every saved place. */
    fun liveFlow(category: String? = null): Flow<List<SavedPlaceEntity>> =
        if (category.isNullOrBlank()) dao.allFlow() else dao.byCategoryFlow(category.trim())

    suspend fun remove(id: String): Boolean {
        val exists = dao.byId(id) != null
        if (exists) dao.deleteById(id)
        return exists
    }

    suspend fun count(): Int = dao.count()
}
