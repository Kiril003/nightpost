package local.phantom.companion.core.nav

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * M-N08 — Sovereign GPX & GeoJSON Track Export & Import Engine.
 *
 * Enables exporting footsteps/routes to standard GPX XML and GeoJSON LineStrings,
 * and parsing imported GPX data back into spatial points.
 */
object TrackExporter {

    data class TrackPoint(
        val lat: Double,
        val lon: Double,
        val elevationM: Double? = null,
        val timeMs: Long? = null,
    )

    private val isoFormat: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    /**
     * Exports track points to standard GPX 1.1 XML.
     */
    fun exportGpx(trackName: String, points: List<TrackPoint>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append("\n")
        sb.append("""<gpx version="1.1" creator="Nightpost" xmlns="http://www.topografix.com/GPX/1/1">""").append("\n")
        sb.append("  <trk>\n")
        sb.append("    <name>").append(escapeXml(trackName)).append("</name>\n")
        sb.append("    <trkseg>\n")

        for (pt in points) {
            sb.append("""      <trkpt lat="${pt.lat}" lon="${pt.lon}">""")
            if (pt.elevationM != null) {
                sb.append("<ele>${pt.elevationM}</ele>")
            }
            if (pt.timeMs != null) {
                sb.append("<time>${isoFormat.format(Date(pt.timeMs))}</time>")
            }
            sb.append("</trkpt>\n")
        }

        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>")
        return sb.toString()
    }

    /**
     * Exports track points to GeoJSON Feature LineString.
     */
    fun exportGeoJson(trackName: String, points: List<TrackPoint>): String {
        val coordinates = points.joinToString(",") { pt ->
            if (pt.elevationM != null) {
                "[${pt.lon},${pt.lat},${pt.elevationM}]"
            } else {
                "[${pt.lon},${pt.lat}]"
            }
        }
        return """
        {
          "type": "FeatureCollection",
          "features": [
            {
              "type": "Feature",
              "properties": {
                "name": "${escapeJson(trackName)}",
                "point_count": ${points.size}
              },
              "geometry": {
                "type": "LineString",
                "coordinates": [$coordinates]
              }
            }
          ]
        }
        """.trimIndent()
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}
