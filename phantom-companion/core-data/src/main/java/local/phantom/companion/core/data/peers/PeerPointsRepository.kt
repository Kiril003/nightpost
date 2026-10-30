package local.phantom.companion.core.data.peers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Месенджер пише батчем, мапа читає. Ключ — [PeerPoint.nodeId]. */
class PeerPointsRepository(private val now: () -> Long = System::currentTimeMillis) {

    private val _points = MutableStateFlow<List<PeerPoint>>(emptyList())
    val points: StateFlow<List<PeerPoint>> = _points.asStateFlow()

    /** Уся картина: кого в батчі немає — того немає. */
    fun replaceAll(batch: List<PeerPoint>) = write(batch, keepAbsent = false)

    fun upsertBatch(batch: List<PeerPoint>) {
        if (batch.isEmpty()) return
        write(batch, keepAbsent = true)
    }

    fun drop(nodeId: String) {
        _points.value = _points.value.filterNot { it.nodeId == nodeId }
    }

    fun clear() {
        _points.value = emptyList()
    }

    /** Такт дає споживач: власний таймер тут цокав би й при згорнутій мапі. */
    @Suppress("RedundantSuspendModifier")
    suspend fun sweep() {
        val nowMs = now()
        val kept = _points.value.filterNot { it.expiredAt(nowMs) }
        if (kept.size != _points.value.size) _points.value = kept
    }

    private fun write(batch: List<PeerPoint>, keepAbsent: Boolean) {
        val nowMs = now()
        val held = _points.value.associateBy { it.nodeId }
        val out = LinkedHashMap<String, PeerPoint>()
        if (keepAbsent) _points.value.forEach { out[it.nodeId] = it }
        batch.forEach { raw ->
            val incoming = raw.sealed(nowMs, held[raw.nodeId])
            // Скринька віддає точки не в тому порядку, у якому їх міряли.
            // Старіша поверх новішої — це стрибок людини назад, тобто рух,
            // якого не було.
            val standing = out[raw.nodeId]
            if (standing == null || incoming.atMs >= standing.atMs) out[raw.nodeId] = incoming
        }
        _points.value = out.values.filterNot { it.expiredAt(nowMs) }
    }

    /** Спад seq — не діра: лічильник живе в межах однієї сесії шерінгу. */
    private fun PeerPoint.sealed(nowMs: Long, previous: PeerPoint?): PeerPoint {
        val hole = seq != null && previous?.seq != null && seq > previous.seq + 1
        return if (!stale && !hole && freshAt(nowMs)) this else copy(stale = true)
    }
}
