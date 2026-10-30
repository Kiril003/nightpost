package local.phantom.companion.core.data.poi

import java.io.BufferedReader

/**
 * HIGHWAY — the bundled street-name corpus: every named road OpenStreetMap
 * carries across Ukraine, baked once from road-mesh-bake by
 * `scripts/bake_streets.py`, read-only, no house numbers (a national bake of
 * those has not been measured — see the script header).
 *
 * Plain tab-separated text, not JSON: ~270 000 rows as `{"id":…,"lat":…}`
 * objects would carry those field names a quarter of a million times over,
 * and parsing them the way [OfflinePoiPack] does would hold every row as a
 * live object for the life of the app — a fit for a corpus [OfflinePoiPack]
 * keeps querying (`inBox`, `nearby`, live map pins), not for one this only
 * ever writes once into Room and then forgets. A line per street lets this
 * stream an [java.io.InputStream] straight into fixed chunks and hold nothing
 * beyond the chunk in flight.
 *
 * A street becomes a plain [PoiGazetteerEntity] with `category = `[CATEGORY]
 * — not a parallel table or a parallel search path. That is what lets
 * «крещатик» (one edit away from «Хрещатик») fall through to the SAME rescue
 * scan [PoiRepository] already runs for a misspelled café: rescue reads
 * whatever sits in `poi_gazetteer` near the operator and does not know or
 * care who put a row there.
 */
object OfflineStreetPack {

    const val CATEGORY = "street"

    /**
     * Synthetic ids start here — clear of every OSM node/way id in use at
     * bake time (order 10^10) by three more orders of magnitude, so a street
     * can never collide with, and silently overwrite, a place the gazetteer
     * saw live.
     */
    private const val ID_BASE = 900_000_000_000_000L

    /** The bake epoch off the header line, `generated=<epoch> count=<n>`. */
    fun parseGenerated(header: String): Long =
        HEADER_GENERATED.find(header)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    /**
     * Chunk the corpus for [PoiGazetteerDao.upsert], reading forward from
     * [reader] — the header line ([parseGenerated]) must already be consumed
     * by the caller, which is what lets a re-seed check cost one `readLine()`
     * instead of a full parse when the bake has not changed.
     *
     * Never throws on a malformed row: a line that does not parse is skipped,
     * not a reason to abandon the ~270 000 that do.
     */
    fun chunkLines(reader: BufferedReader, chunkSize: Int = 500): Sequence<List<PoiGazetteerEntity>> = sequence {
        var buffer = ArrayList<PoiGazetteerEntity>(chunkSize)
        var idx = 0L
        while (true) {
            val line = reader.readLine() ?: break
            parseLine(line, idx)?.let(buffer::add)
            idx++
            if (buffer.size >= chunkSize) {
                yield(buffer)
                buffer = ArrayList(chunkSize)
            }
        }
        if (buffer.isNotEmpty()) yield(buffer)
    }

    private fun parseLine(line: String, idx: Long): PoiGazetteerEntity? {
        val parts = line.split('\t', limit = 4)
        if (parts.size < 4) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        val name = parts[3]
        if (name.isBlank()) return null
        return PoiGazetteerEntity(
            id = ID_BASE + idx,
            lat = lat,
            lon = lon,
            name = name,
            category = CATEGORY,
            subcategory = parts[2].ifEmpty { null },
        )
    }

    private val HEADER_GENERATED = Regex("generated=(\\d+)")
}
