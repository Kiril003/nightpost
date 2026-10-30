package local.phantom.companion.core.tool.diary

import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory [DecisionDiary] implementation.
 *
 * Capped at [MAX_ENTRIES] entries (FIFO — oldest entry is evicted when the
 * cap is exceeded). Survives neither process restart nor configuration change,
 * which is intentional for Phase 1 — full Room-backed persistence is Phase 4.
 *
 * Thread-safe: backed by [CopyOnWriteArrayList] so concurrent writes from
 * multiple coroutines are safe without an explicit mutex.
 */
class InMemoryDecisionDiary : DecisionDiary {

    private val entries = CopyOnWriteArrayList<DecisionEntry>()

    override suspend fun write(entry: DecisionEntry) {
        // Evict oldest first when cap is reached.
        while (entries.size >= MAX_ENTRIES) {
            if (entries.isNotEmpty()) entries.removeAt(0)
        }
        entries.add(entry)
    }

    override suspend fun recent(limit: Int): List<DecisionEntry> {
        // Snapshot, reverse (newest first), then take up to limit.
        return entries.toList().asReversed().take(limit)
    }

    companion object {
        /** Maximum number of in-memory entries before FIFO eviction kicks in. */
        const val MAX_ENTRIES = 500
    }
}
