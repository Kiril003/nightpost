package local.phantom.companion.core.geo.link

/**
 * ПОСИЛАННЯ НА МІСЦЕ — НАШЕ, А НЕ ЧУЖОГО САЙТУ.
 *
 * «поділитись місцем» складало три рядки, і третім стояв
 * `https://www.openstreetmap.org/?mlat=…`. Співрозмовник тиснув саме його —
 * бо це єдине, що месенджери роблять клікабельним, — і потрапляв у браузер на
 * сайт OSM. Тобто продукт із власною офлайн-мапою відправляв людину назовні.
 *
 * Тепер першим їде `phantom://map?lat&lon&label`, `geo:` лишається запасним
 * для того, у кого PHANTOM ще не стоїть, а посилання на osm.org — останнім, як
 * останній рубіж і атрибуція.
 *
 * Розбір приймає ОБИДВІ чужі форми: наш власний лист, `geo:` від будь-якої
 * мапи, і посилання osm.org — щоб лист, надісланий учора, відкривався в нас.
 */
object MapLink {

    data class Point(val lat: Double, val lon: Double, val label: String? = null)

    const val SCHEME = "phantom"
    const val HOST = "map"

    fun own(point: Point): String = buildString {
        append("$SCHEME://$HOST?lat=${point.lat}&lon=${point.lon}")
        point.label?.takeIf { it.isNotBlank() }?.let { append("&label=${encode(it)}") }
    }

    fun geo(point: Point): String = buildString {
        append("geo:${point.lat},${point.lon}")
        append("?q=${point.lat},${point.lon}")
        point.label?.takeIf { it.isNotBlank() }?.let { append("(${it})") }
    }

    fun osm(point: Point): String =
        "https://www.openstreetmap.org/?mlat=${point.lat}&mlon=${point.lon}" +
            "#map=$OSM_ZOOM/${point.lat}/${point.lon}"

    /** Текст, який летить у месенджер. Порядок тут і є рішенням. */
    fun shareText(point: Point): String = buildString {
        point.label?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
        appendLine(own(point))
        appendLine(geo(point))
        append(osm(point))
    }

    fun parse(raw: String?): Point? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return parseOwn(text) ?: parseGeo(text) ?: parseOsm(text)
    }

    /** Перший рядок тексту, що виявився посиланням на місце. */
    fun parseFirst(body: String?): Point? =
        body?.lineSequence()?.map { parse(it) }?.firstOrNull { it != null }

    private fun parseOwn(text: String): Point? {
        val lower = text.lowercase()
        // Debug-збірка носить свою схему (`phantom-debug://`), і лист із
        // продакшену мусить відкриватись в обох.
        if (!lower.startsWith("$SCHEME:") && !lower.startsWith("$SCHEME-")) return null
        val query = text.substringAfter("//$HOST", "").removePrefix("?")
        if (query.isEmpty()) return null
        val params = query.split('&').mapNotNull { pair ->
            val k = pair.substringBefore('=', "")
            val v = pair.substringAfter('=', "")
            if (k.isEmpty()) null else k.lowercase() to v
        }.toMap()
        val lat = params["lat"]?.toDoubleOrNull() ?: return null
        val lon = params["lon"]?.toDoubleOrNull() ?: return null
        return point(lat, lon, params["label"]?.let { decode(it) })
    }

    private fun parseGeo(text: String): Point? {
        if (!text.lowercase().startsWith("geo:")) return null
        val body = text.removeRange(0, "geo:".length)
        val label = body.substringAfter('(', "").substringBeforeLast(')', "").ifBlank { null }
        // `geo:0,0?q=lat,lon` — форма, якою мапи шлють ПОЗНАЧКУ, а не камеру:
        // нулі попереду тоді не значать нічого і читати треба `q`.
        val q = body.substringAfter("?q=", "").substringBefore('(').takeIf { it.isNotBlank() }
        val head = body.substringBefore('?')
        val fromQ = q?.let { coords(it) }
        val fromHead = coords(head)
        val chosen = when {
            fromQ != null && fromHead == null -> fromQ
            fromQ != null && fromHead != null && fromHead.first == 0.0 && fromHead.second == 0.0 -> fromQ
            else -> fromHead ?: fromQ
        } ?: return null
        return point(chosen.first, chosen.second, label)
    }

    private fun parseOsm(text: String): Point? {
        val lower = text.lowercase()
        if (!lower.contains("openstreetmap.org")) return null
        val lat = paramOf(lower, "mlat")?.toDoubleOrNull()
        val lon = paramOf(lower, "mlon")?.toDoubleOrNull()
        if (lat != null && lon != null) return point(lat, lon, null)
        // `#map=17/50.45/30.52` — форма без позначки, лише камера.
        val frag = lower.substringAfter("#map=", "").split('/')
        if (frag.size < 3) return null
        return point(frag[1].toDoubleOrNull() ?: return null, frag[2].toDoubleOrNull() ?: return null, null)
    }

    private fun paramOf(lower: String, key: String): String? {
        val i = lower.indexOf("$key=")
        if (i < 0) return null
        return lower.substring(i + key.length + 1).takeWhile { it != '&' && it != '#' }
            .takeIf { it.isNotBlank() }
    }

    private fun coords(raw: String): Pair<Double, Double>? {
        val parts = raw.trim().split(',')
        if (parts.size < 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].substringBefore(',').toDoubleOrNull() ?: return null
        return lat to lon
    }

    /** Координати поза глобусом — це не місце, а зіпсований рядок. */
    private fun point(lat: Double, lon: Double, label: String?): Point? {
        if (lat.isNaN() || lon.isNaN()) return null
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
        return Point(lat, lon, label?.takeIf { it.isNotBlank() })
    }

    private fun encode(s: String): String =
        java.net.URLEncoder.encode(s, Charsets.UTF_8.name())

    private fun decode(s: String): String =
        runCatching { java.net.URLDecoder.decode(s, Charsets.UTF_8.name()) }.getOrDefault(s)

    private const val OSM_ZOOM = 17
}
