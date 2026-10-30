package local.phantom.companion.feature.stream.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Рельєф кузні в стилі. Тут вирішується не краса, а одне слово: `terrarium`.
 * Прочитати наш DEM як `mapbox` — це інша формула висоти, тобто гори не на
 * своїх місцях, і жоден кадр цього не скаже вголос.
 */
class OfflineTerrainStyleTest {

    private val style = """{"version":8,"sources":{"openmaptiles":{"type":"vector","url":"https://x/y.json"}},"layers":[]}"""

    @Test
    fun `the dem source lands inside sources with terrarium encoding`() {
        val patched = applyLocalTerrain(style, "/data/user/0/app/files/forge-terrain/terrain.mbtiles", maxZoom = 11)
        assertTrue(patched.contains(""""$LOCAL_DEM_SOURCE""""))
        assertTrue(patched.contains(""""type": "raster-dem""""))
        assertTrue(patched.contains(""""encoding": "terrarium""""))
        assertTrue(patched.contains(""""maxzoom": 11"""))
        assertTrue(
            "локальний файл читає MBTilesFileSource, і тільки за абсолютним шляхом",
            patched.contains(""""url": "mbtiles:///data/user/0/app/files/forge-terrain/terrain.mbtiles""""),
        )
        assertTrue("основа мусить лишитись на місці", patched.contains(""""openmaptiles""""))
    }

    @Test
    fun `the patch is the same shape the offline basemap already uses`() {
        val both = applyLocalTerrain(
            applyOfflineBasemap(style, "/files/offline-basemap/basemap.mbtiles"),
            "/files/forge-terrain/terrain.mbtiles",
            maxZoom = 11,
        )
        assertTrue(both.contains("""mbtiles:///files/offline-basemap/basemap.mbtiles"""))
        assertTrue(both.contains("""mbtiles:///files/forge-terrain/terrain.mbtiles"""))
    }

    @Test
    fun `patching twice adds one source, not two`() {
        val once = applyLocalTerrain(style, "/files/a.mbtiles", maxZoom = 11)
        val twice = applyLocalTerrain(once, "/files/a.mbtiles", maxZoom = 11)
        assertEquals(once, twice)
        assertEquals(1, Regex(LOCAL_DEM_SOURCE).findAll(twice).count())
    }

    @Test
    fun `a style with no sources block is left alone`() {
        val foreign = """{"version":8,"layers":[]}"""
        assertEquals(foreign, applyLocalTerrain(foreign, "/files/a.mbtiles", maxZoom = 11))
    }

    @Test
    fun `a relative path is refused loudly`() {
        val failed = runCatching { applyLocalTerrain(style, "files/a.mbtiles", maxZoom = 11) }
        assertTrue(failed.isFailure)
        assertFalse(applyLocalTerrain(style, "/files/a.mbtiles", maxZoom = 11).contains("\"files/a.mbtiles\""))
    }

    @Test
    fun `the hillshade layer and the style agree on one source name`() {
        assertEquals(DEM_SOURCE, LOCAL_DEM_SOURCE)
    }
}
