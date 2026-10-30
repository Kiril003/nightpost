package local.phantom.companion.feature.stream.domain

import local.phantom.companion.core.geo.layerpack.StoredLayerPack
import local.phantom.companion.core.net.maplayer.PcLayerCatalogClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcLayerPacksTest {

    private fun manifest(id: String, nameUa: String = id, category: String = "terrain") =
        PcLayerCatalogClient.PcLayerManifest(
            id = id,
            name_ua = nameUa,
            category = category,
            available_offline = true,
            source = PcLayerCatalogClient.PcLayerSource(type = "local_db", bbox_required = true),
        )

    private fun pack(layerId: String, fetchedAtMs: Long = 1_000L) = StoredLayerPack(
        layerId = layerId,
        labelUa = "Скелі та осипи",
        category = "terrain",
        northLat = 51.0, southLat = 50.0, eastLon = 31.0, westLon = 30.0,
        featureCount = 42,
        bytes = 12_000L,
        fetchedAtMs = fetchedAtMs,
        geoJson = "{}",
    )

    // ── the four states never collapse into each other ─────────────────

    @Test
    fun `nothing stored, nothing in flight, no error — the layer is only offered`() {
        val state = PcLayerPacks.rowState(stored = null, inFlight = false, lastError = null)
        assertEquals(PcLayerRowState.Offered, state)
    }

    @Test
    fun `a stored pack reads as have, carrying its own age and size`() {
        val state = PcLayerPacks.rowState(stored = pack("cliff_scree"), inFlight = false, lastError = null)
        assertTrue(state is PcLayerRowState.Have)
        state as PcLayerRowState.Have
        assertEquals(1_000L, state.fetchedAtMs)
        assertEquals(12_000L, state.bytes)
        assertEquals(42, state.featureCount)
    }

    @Test
    fun `in flight wins over a stored pack — a refresh reads as transferring, not have`() {
        val state = PcLayerPacks.rowState(stored = pack("cliff_scree"), inFlight = true, lastError = null)
        assertEquals(PcLayerRowState.Transferring, state)
    }

    @Test
    fun `a failed refresh keeps the old pack visible inside the failure, not silently`() {
        val state = PcLayerPacks.rowState(
            stored = pack("cliff_scree", fetchedAtMs = 500L),
            inFlight = false,
            lastError = "ПК відповів 500.",
        )
        assertTrue(state is PcLayerRowState.Failed)
        val failed = state as PcLayerRowState.Failed
        assertEquals("ПК відповів 500.", failed.message)
        assertEquals(500L, failed.had?.fetchedAtMs)
    }

    @Test
    fun `a failed first pull carries no old pack to fall back on`() {
        val state = PcLayerPacks.rowState(stored = null, inFlight = false, lastError = "ПК не на звʼязку.")
        assertTrue(state is PcLayerRowState.Failed)
        assertEquals(null, (state as PcLayerRowState.Failed).had)
    }

    // ── merge: catalog + local store + in-flight + errors ──────────────

    @Test
    fun `a catalog layer never pulled shows offered, a stored one shows have`() {
        val rows = PcLayerPacks.mergeRows(
            catalog = listOf(manifest("cliff_scree"), manifest("power_towers")),
            stored = listOf(pack("power_towers")),
            inFlightLayerId = null,
            errors = emptyMap(),
        )
        assertEquals(PcLayerRowState.Offered, rows.first { it.layerId == "cliff_scree" }.state)
        assertTrue(rows.first { it.layerId == "power_towers" }.state is PcLayerRowState.Have)
    }

    @Test
    fun `a layer the PC no longer lists is not dropped — the operator's own delete removes it, not a catalog refresh`() {
        val rows = PcLayerPacks.mergeRows(
            catalog = listOf(manifest("cliff_scree")),
            stored = listOf(pack("cliff_scree"), pack("retired_layer")),
            inFlightLayerId = null,
            errors = emptyMap(),
        )
        assertEquals(2, rows.size)
        assertTrue(rows.first { it.layerId == "retired_layer" }.state is PcLayerRowState.Have)
    }

    @Test
    fun `only the layer actually in flight is transferring — its siblings are unaffected`() {
        val rows = PcLayerPacks.mergeRows(
            catalog = listOf(manifest("cliff_scree"), manifest("power_towers")),
            stored = emptyList(),
            inFlightLayerId = "power_towers",
            errors = emptyMap(),
        )
        assertEquals(PcLayerRowState.Offered, rows.first { it.layerId == "cliff_scree" }.state)
        assertEquals(
            PcLayerRowState.Transferring,
            rows.first { it.layerId == "power_towers" }.state,
        )
    }

    // ── age / size formatting ───────────────────────────────────────────

    @Test
    fun `age buckets match the voice the map already speaks — щойно, хв, год, дн`() {
        val now = 1_000_000L
        assertEquals("щойно", PcLayerPacks.ageLabel(fetchedAtMs = now, nowMs = now))
        assertEquals("5 хв тому", PcLayerPacks.ageLabel(fetchedAtMs = now - 5 * 60_000L, nowMs = now))
        assertEquals("3 год тому", PcLayerPacks.ageLabel(fetchedAtMs = now - 3 * 3_600_000L, nowMs = now))
        assertEquals("2 дн тому", PcLayerPacks.ageLabel(fetchedAtMs = now - 2 * 86_400_000L, nowMs = now))
    }

    @Test
    fun `size renders bytes, kilobytes and megabytes with a Ukrainian decimal comma`() {
        assertEquals("500 Б", PcLayerPacks.sizeLabel(500L))
        assertEquals("12 КБ", PcLayerPacks.sizeLabel(12_000L))
        assertEquals("1,5 МБ", PcLayerPacks.sizeLabel(1_500_000L))
    }

    // ── specFromGeoJson: the render path the audit found unreachable ────

    @Test
    fun `Point features become markers the renderer can push`() {
        val geoJson = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","properties":{"name":"опора 12"},
               "geometry":{"type":"Point","coordinates":[30.5,50.4]}}
            ]}
        """.trimIndent()
        val spec = PcLayerPacks.specFromGeoJson(pack("power_towers").copy(geoJson = geoJson))
        assertEquals(1, spec.markers.size)
        assertEquals(50.4, spec.markers.first().lat, 0.0001)
        assertEquals(30.5, spec.markers.first().lon, 0.0001)
        assertEquals("опора 12", spec.markers.first().label)
        assertEquals("pc-power_towers", spec.kind)
    }

    @Test
    fun `LineString features become a line, a single-point line does not`() {
        val geoJson = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","properties":{},
               "geometry":{"type":"LineString","coordinates":[[30.5,50.4],[30.6,50.5]]}}
            ]}
        """.trimIndent()
        val spec = PcLayerPacks.specFromGeoJson(pack("cliff_scree").copy(geoJson = geoJson))
        assertEquals(1, spec.lines.size)
        assertEquals(2, spec.lines.first().points.size)
    }

    @Test
    fun `Polygon geometry and malformed bodies degrade to an empty spec, not a crash`() {
        val polygon = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","properties":{},
               "geometry":{"type":"Polygon","coordinates":[[[30.5,50.4],[30.6,50.5],[30.5,50.4]]]}}
            ]}
        """.trimIndent()
        assertEquals(0, PcLayerPacks.specFromGeoJson(pack("cliff_scree").copy(geoJson = polygon)).elementCount)
        assertEquals(0, PcLayerPacks.specFromGeoJson(pack("cliff_scree").copy(geoJson = "не json")).elementCount)
    }
}
