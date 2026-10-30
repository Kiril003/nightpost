package local.phantom.companion.core.data.places

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import local.phantom.companion.core.data.spatial.GeoPoint

/** Намальована ламана, прочитана з диска. */
data class DrawnLine(
    val id: String,
    val label: String,
    val points: List<GeoPoint>,
    val createdAt: Long,
    val updatedAt: Long,
    /**
     * `false` — рядок вершин на диску пошкоджений. Такий запис не викидається
     * мовчки й не показується порожньою лінією: аркуш мусить сказати про нього
     * словом, інакше зникнення намальованого виглядає як «нічого не було».
     */
    val readable: Boolean = true,
)

/**
 * Довга памʼять намальованих ліній. Брама імені та стеля вершин — на вході, у
 * єдиному горлі запису: безіменна або однокрапкова лінія не існує як обʼєкт,
 * і відмовити треба там, де людина ще бачить, що саме вона малювала.
 */
class DrawnLineStore(private val dao: DrawnLineDao) {

    sealed interface SaveResult {
        data class Saved(val id: String, val label: String) : SaveResult
        /** Брама імені: лінія живе роками, тому мусить мати імʼя. */
        data object Unnamed : SaveResult
        /** Одна вершина — це точка; ламаної ще немає. */
        data class TooFew(val have: Int) : SaveResult
    }

    suspend fun save(
        label: String?,
        points: List<GeoPoint>,
        nowMs: Long = System.currentTimeMillis(),
    ): SaveResult {
        if (points.size < MIN_VERTICES) return SaveResult.TooFew(points.size)
        if (!MarkName.isNamed(label)) return SaveResult.Unnamed
        val taken = dao.labels().map { it.lowercase() }.toSet()
        val clean = MarkName.deduplicate(MarkName.clean(label), taken)
        val id = UUID.randomUUID().toString()
        dao.upsert(
            DrawnLineEntity(
                id = id,
                label = clean,
                vertices = DrawnLineCodec.encode(points),
                createdAt = nowMs,
                updatedAt = nowMs,
            ),
        )
        return SaveResult.Saved(id, clean)
    }

    suspend fun list(): List<DrawnLine> = dao.getAll().map { it.toDomain() }

    fun liveFlow(): Flow<List<DrawnLine>> = dao.allFlow().map { rows -> rows.map { it.toDomain() } }

    suspend fun byId(id: String): DrawnLine? = dao.byId(id)?.toDomain()

    suspend fun count(): Int = dao.count()

    suspend fun rename(id: String, label: String?, nowMs: Long = System.currentTimeMillis()): SaveResult {
        val existing = dao.byId(id) ?: return SaveResult.TooFew(0)
        if (!MarkName.isNamed(label)) return SaveResult.Unnamed
        val taken = dao.labels().filterNot { it == existing.label }.map { it.lowercase() }.toSet()
        val clean = MarkName.deduplicate(MarkName.clean(label), taken)
        dao.upsert(existing.copy(label = clean, updatedAt = nowMs))
        return SaveResult.Saved(id, clean)
    }

    /** Правка вершин: додати, зсунути, прибрати — лінія переписується цілою. */
    suspend fun replaceVertices(
        id: String,
        points: List<GeoPoint>,
        nowMs: Long = System.currentTimeMillis(),
    ): SaveResult {
        val existing = dao.byId(id) ?: return SaveResult.TooFew(0)
        if (points.size < MIN_VERTICES) return SaveResult.TooFew(points.size)
        dao.upsert(
            existing.copy(vertices = DrawnLineCodec.encode(points), updatedAt = nowMs),
        )
        return SaveResult.Saved(id, existing.label)
    }

    suspend fun remove(id: String): Boolean = dao.deleteById(id) > 0

    private fun DrawnLineEntity.toDomain(): DrawnLine {
        val decoded = DrawnLineCodec.decode(vertices)
        return DrawnLine(
            id = id,
            label = label,
            points = decoded.orEmpty(),
            createdAt = createdAt,
            updatedAt = updatedAt,
            readable = decoded != null,
        )
    }

    companion object {
        const val MIN_VERTICES = 2
    }
}
