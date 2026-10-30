package local.phantom.companion.data

import local.phantom.companion.core.data.peers.MyWorldSummary
import local.phantom.companion.core.data.peers.PeerBox
import local.phantom.companion.core.net.peer.PeerWhereGrid
import local.phantom.companion.core.net.peer.SelfMap

/**
 * «Мій світ»: квартали, де я бував, і скільки часу в кожному. Не слід: ні
 * порядку, ні маршруту — лише клітинка, перша й остання мить і перебування.
 * Місто — назва району з офлайн-пакета, коли він є; без пакета міст не лічимо.
 */
object MyWorld {

    /** Довша перерва між вимірами — не перебування, а відсутність. */
    const val GAP_MS = 10 * 60_000L
    const val FREQUENT_MIN_MS = 20 * 60_000L

    data class Book(
        val cells: Map<String, SelfMap.Cell> = emptyMap(),
        val cities: Map<String, String> = emptyMap(),
        val lastKey: String? = null,
        val lastAtMs: Long = 0L,
    )

    fun keyOf(lat: Double, lon: Double): String =
        PeerWhereGrid.cellOf(lat, lon, PeerWhereGrid.Level.QUARTER).let { "q:${it.row}:${it.col}" }

    fun districtOf(lat: Double, lon: Double): String =
        PeerWhereGrid.cellOf(lat, lon, PeerWhereGrid.Level.DISTRICT).let { "d:${it.row}:${it.col}" }

    fun onFix(book: Book, lat: Double, lon: Double, atMs: Long): Book {
        val key = keyOf(lat, lon)
        val gap = atMs - book.lastAtMs
        if (gap < 0L) return book
        val stayed = book.lastKey == key && gap <= GAP_MS
        val old = book.cells[key]
        val cell = when {
            old == null -> SelfMap.Cell(key, atMs, atMs, 0L)
            stayed -> old.copy(lastMs = atMs, dwellMs = old.dwellMs + gap)
            else -> old.copy(lastMs = atMs)
        }
        return book.copy(cells = book.cells + (key to cell), lastKey = key, lastAtMs = atMs)
    }

    fun summary(book: Book, labelOf: (Double, Double) -> String? = { _, _ -> null }, top: Int = 3): MyWorldSummary {
        val boxes = book.cells.keys.mapNotNull(::boundsOf)
        val frequent = book.cells.values.filter { it.dwellMs >= FREQUENT_MIN_MS }
            .sortedByDescending { it.dwellMs }
            .take(top)
            .mapNotNull { c ->
                val cell = cellOf(c.key) ?: return@mapNotNull null
                val (lat, lon) = PeerWhereGrid.centreOf(cell)
                MyWorldSummary.Frequent(labelOf(lat, lon), lat, lon, c.dwellMs)
            }
        return MyWorldSummary(
            quarters = book.cells.size,
            cities = book.cities.values.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet().size,
            frequent = frequent,
            boxes = boxes,
        )
    }

    /** Клітинка з іншого мого пристрою: лише росте, як і в листі собі. */
    fun absorb(book: Book, cell: SelfMap.Cell): Book {
        val merged = SelfMap.apply(SelfMap.Book(world = book.cells), cell).world
        return book.copy(cells = merged)
    }

    private fun cellOf(key: String): PeerWhereGrid.Cell? {
        val parts = key.split(':')
        if (parts.size != 3 || parts[0] != "q") return null
        val cell = PeerWhereGrid.Cell(PeerWhereGrid.Level.QUARTER, parts[1].toIntOrNull() ?: return null, parts[2].toIntOrNull() ?: return null)
        return cell.takeIf(PeerWhereGrid::isValid)
    }

    private fun boundsOf(key: String): PeerBox? = cellOf(key)?.let(PeerWhereGrid::boundsOf)?.let { PeerBox(it.south, it.west, it.north, it.east) }
}
