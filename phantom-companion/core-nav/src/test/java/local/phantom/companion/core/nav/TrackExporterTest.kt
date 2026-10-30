package local.phantom.companion.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackExporterTest {

    @Test
    fun exportsGpxCorrectly() {
        val points = listOf(
            TrackExporter.TrackPoint(lat = 50.450, lon = 30.520, elevationM = 150.0, timeMs = 1700000000000L),
            TrackExporter.TrackPoint(lat = 50.455, lon = 30.525, elevationM = 155.5, timeMs = 1700000060000L),
        )

        val gpx = TrackExporter.exportGpx("Похід Поділ", points)
        assertTrue(gpx.contains("<name>Похід Поділ</name>"))
        assertTrue(gpx.contains("""<trkpt lat="50.45" lon="30.52">"""))
        assertTrue(gpx.contains("<ele>150.0</ele>"))

    }

    @Test
    fun exportsGeoJsonCorrectly() {
        val points = listOf(
            TrackExporter.TrackPoint(lat = 50.450, lon = 30.520, elevationM = 150.0),
            TrackExporter.TrackPoint(lat = 50.455, lon = 30.525, elevationM = 155.0),
        )

        val geoJson = TrackExporter.exportGeoJson("Маршрут 1", points)
        assertTrue(geoJson.contains(""""name": "Маршрут 1""""))
        assertTrue(geoJson.contains(""""type": "LineString""""))
        assertTrue(geoJson.contains("[30.52,50.45,150.0]"))
    }
}
