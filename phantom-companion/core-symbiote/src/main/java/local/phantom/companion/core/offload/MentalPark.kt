package local.phantom.companion.core.offload

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tier 23-A — in-memory park of un-formed thoughts. The vision wants
 * this to feel like a *subconscious*: items quietly mature, surface
 * at the right moment, archive on operator command.
 *
 * Persistence belongs to `:core-data`; this class is the pure
 * computation around the park. `lastTouched` updates on every
 * surface or mutation so the [staleAfterMs] tracker (Tier 23-F) can
 * spot items that have not been engaged for a while.
 */
class MentalPark(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val _items = MutableStateFlow<List<MentalParkItem>>(emptyList())
    val items: StateFlow<List<MentalParkItem>> = _items.asStateFlow()

    fun add(text: String, tags: Set<String> = emptySet(), id: String? = null): MentalParkItem {
        val now = clock()
        val item = MentalParkItem(
            id = id ?: "park-${now}-${_items.value.size}",
            text = text,
            createdAtMs = now,
            lastTouchedMs = now,
            tags = tags,
        )
        _items.value = _items.value + item
        return item
    }

    fun touch(id: String) {
        val now = clock()
        _items.value = _items.value.map { if (it.id == id) it.copy(lastTouchedMs = now) else it }
    }

    fun setRipeness(id: String, newRipeness: Int) {
        val clamped = newRipeness.coerceIn(0, 100)
        _items.value = _items.value.map {
            if (it.id == id) it.copy(ripeness = clamped) else it
        }
    }

    fun archive(id: String) {
        _items.value = _items.value.map {
            if (it.id == id) it.copy(status = MentalParkItem.Status.ARCHIVED) else it
        }
    }

    /**
     * Tier 23-B — active forgetting. The item stays in the park as a
     * tombstone so the operator can still audit "what did I let go".
     * Phantom stops surfacing it. Hard deletion belongs to a Tier
     * 19-E ceremony, not here.
     */
    fun forget(id: String) {
        _items.value = _items.value.map {
            if (it.id == id) it.copy(status = MentalParkItem.Status.FORGOTTEN) else it
        }
    }

    /**
     * Returns the top-N items ranked by ripeness among the still-LIVE
     * entries. Stable tie-breaking on last-touched (older first) so
     * the operator does not see the same shimmer for unchanged park
     * state.
     */
    fun ripestLive(n: Int): List<MentalParkItem> = _items.value
        .filter { it.isActive }
        .sortedWith(compareByDescending<MentalParkItem> { it.ripeness }.thenBy { it.lastTouchedMs })
        .take(n)
}
