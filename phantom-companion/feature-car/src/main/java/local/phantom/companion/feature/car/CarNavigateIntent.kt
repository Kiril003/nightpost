package local.phantom.companion.feature.car

/**
 * Reads the host's «navigate to X».
 *
 * A certified navigation app is asked to drive by other things than its own
 * screens — the car's assistant, a shared address, a voice command handled by
 * the host. That arrives as a `geo:` URI, and it comes in three shapes that
 * look alike and are not: a real coordinate, a coordinate with a label, and
 * `geo:0,0?q=<words>` which carries no coordinate at all and must be searched.
 */
internal object CarNavigateIntent {

    sealed interface Target {
        data class Point(val lat: Double, val lon: Double, val label: String?) : Target
        data class Query(val text: String) : Target
    }

    fun parse(uri: String?): Target? {
        val raw = uri?.trim().orEmpty()
        if (!raw.startsWith("geo:", ignoreCase = true)) return null
        val body = raw.substring(4)
        val path = body.substringBefore('?')
        val query = body.substringAfter('?', "")
        val q = paramOf(query, "q")

        val coords = path.split(',').mapNotNull { it.trim().toDoubleOrNull() }
        val hasCoords = coords.size >= 2 && !(coords[0] == 0.0 && coords[1] == 0.0)
        if (hasCoords && valid(coords[0], coords[1])) {
            return Target.Point(coords[0], coords[1], labelIn(q))
        }
        // `q` may itself be «lat,lon(label)» — the form most senders use.
        val qCoords = q?.substringBefore('(')?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }
        if (qCoords != null && qCoords.size >= 2 && valid(qCoords[0], qCoords[1])) {
            return Target.Point(qCoords[0], qCoords[1], labelIn(q))
        }
        val words = q?.let { decode(it) }?.trim()
        return if (words.isNullOrBlank()) null else Target.Query(words)
    }

    private fun valid(lat: Double, lon: Double) =
        lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)

    /** «50.45,30.52(Софія)» → «Софія». */
    private fun labelIn(q: String?): String? {
        val open = q?.indexOf('(') ?: return null
        if (open < 0) return null
        val close = q.indexOf(')', open + 1)
        val inner = if (close < 0) q.substring(open + 1) else q.substring(open + 1, close)
        return decode(inner).takeIf { it.isNotBlank() }
    }

    private fun paramOf(query: String, key: String): String? = query
        .split('&')
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter('=')
        ?.takeIf { it.isNotBlank() }

    private fun decode(s: String): String =
        runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
