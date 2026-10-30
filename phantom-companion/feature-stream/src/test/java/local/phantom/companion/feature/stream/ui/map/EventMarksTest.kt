package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.core.data.spatial.HAZARD_LAYER_KIND
import local.phantom.companion.core.data.spatial.HAZARD_LAYER_TITLE
import local.phantom.companion.core.data.spatial.HAZARD_SOURCE_COMMUNITY
import local.phantom.companion.core.data.spatial.HazardMarkInput
import local.phantom.companion.core.data.spatial.LayerOrigin
import local.phantom.companion.core.data.spatial.MARK_CLASSES
import local.phantom.companion.core.data.spatial.SpatialLayer
import local.phantom.companion.core.data.spatial.SpatialLayerSpec
import local.phantom.companion.core.data.spatial.SpatialMarker
import local.phantom.companion.core.data.spatial.hazardMarkerOf
import local.phantom.companion.core.geo.cameras.HazardType
import local.phantom.companion.core.geo.cameras.SafetyHazard
import local.phantom.companion.feature.stream.ui.map.atlas.SceneMode
import local.phantom.companion.feature.stream.ui.map.icons.marks.MarkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сцена знаків подій: що доїжджає до шару стилю, а що губиться дорогою.
 * Провенанс губився двічі — у згортанні маркера в трійку чисел і в збірці
 * GeoJSON, — тож обидві точки тут зафіксовані.
 */
class EventMarksTest {

    private val names = MarkKind.entries.associateWith { "клас:${it.id}" }

    private fun hazard(
        id: String = "h",
        type: HazardType = HazardType.ACCIDENT_HOTSPOT,
        title: String = "",
        source: String? = null,
        bearing: Double? = null,
    ) = SafetyHazard(id, 50.45, 30.52, title, type, source = source, bearingDeg = bearing)

    @Test
    fun `community provenance survives all the way to the layer`() {
        val mine = hazard(id = "mine").toEventMark(names)
        val theirs = hazard(id = "theirs", source = HAZARD_SOURCE_COMMUNITY).toEventMark(names)
        assertFalse(mine.community)
        assertTrue(theirs.community)

        val json = eventMarksGeoJson(listOf(mine, theirs))
        assertEquals(2, Regex("\"community\":").findAll(json).count())
        assertTrue(json.contains("\"community\":true"))
        assertTrue(json.contains("\"community\":false"))
    }

    @Test
    fun `a bearing rides along, and its absence is stated, not guessed`() {
        val aimed = hazard(type = HazardType.GATE, bearing = 137.5).toEventMark(names)
        val blind = hazard(type = HazardType.GATE).toEventMark(names)

        assertTrue(eventMarksGeoJson(listOf(aimed)).contains("\"aimed\":true"))
        assertTrue(eventMarksGeoJson(listOf(aimed)).contains("\"bearing\":137.5"))
        // Нуль градусів — теж курс. Брус воріт мусить розрізняти «на північ» і
        // «невідомо», інакше він ляже впоперек уявної дороги.
        assertTrue(eventMarksGeoJson(listOf(blind)).contains("\"aimed\":false"))
    }

    @Test
    fun `the class names itself when the thing has no name of its own`() {
        assertEquals("клас:camera", hazard(type = HazardType.SPEED_CAMERA).toEventMark(names).label)
        assertEquals(
            "Брама Заборовського",
            hazard(type = HazardType.GATE, title = "Брама Заборовського").toEventMark(names).label,
        )
    }

    @Test
    fun `every hazard type has a body to draw`() {
        HazardType.entries.forEach { type ->
            val mark = hazard(type = type).toEventMark(names)
            assertTrue("$type лишився без класу", mark.kind!!.id.isNotEmpty())
        }
    }

    @Test
    fun `a live barrier POI is a gate, a checkpoint POI is not`() {
        val yard = poi(1, category = "barrier", subcategory = "lift_gate", name = "Шлагбаум")
        val kpp = poi(2, category = "barrier", subcategory = "checkpoint", name = "Ворота")
        val out = poiEventHazards(listOf(yard, kpp))
        assertEquals(listOf(HazardType.GATE, HazardType.CHECKPOINT), out.map { it.type })
    }

    @Test
    fun `a plain place is never mistaken for an event`() {
        val cafe = poi(3, category = "amenity", subcategory = "cafe", name = "Кава з собою")
        assertTrue(poiEventHazards(listOf(cafe)).isEmpty())
    }

    @Test
    fun `one point is one event, whoever reported it`() {
        val live = hazard(id = "live", type = HazardType.GATE)
        val cached = poi(4, category = "barrier", subcategory = "gate", name = "Ворота")
            .copy(lat = live.lat, lon = live.lon)
        assertEquals(1, mergeEventHazards(listOf(live), listOf(cached)).size)
    }

    @Test
    fun `an escaped label cannot break the collection`() {
        val marked = hazard(title = "Двір \"Сонячний\"\n").toEventMark(names)
        val json = eventMarksGeoJson(listOf(marked))
        assertFalse(json.contains("\"Сонячний\""))
        assertTrue(json.contains("\\\"Сонячний\\\""))
        assertEquals(json.count { it == '{' }, json.count { it == '}' })
    }

    // ── мітки шару «позначки на мапі» → знаки ────────────────────────────

    private fun markerLayer(
        vararg markers: SpatialMarker,
        kind: String = HAZARD_LAYER_KIND,
        visible: Boolean = true,
    ) = SpatialLayer(
        id = "layer-$kind",
        spec = SpatialLayerSpec(
            title = HAZARD_LAYER_TITLE,
            origin = LayerOrigin.WORLD,
            kind = kind,
            markers = markers.toList(),
        ),
        createdAt = 0L,
        visible = visible,
        expiresAt = null,
    )

    private fun marker(
        label: String = "ДТП",
        source: String? = null,
        detail: String? = null,
        lat: Double = 50.4722,
        lon: Double = 30.3985,
    ) = SpatialMarker(lat = lat, lon = lon, label = label, detail = detail, source = source)

    @Test
    fun `a layer mark becomes a figurine of its class, with provenance and card`() {
        val out = hazardLayerEventMarks(
            listOf(markerLayer(marker(source = HAZARD_SOURCE_COMMUNITY, detail = "спільнота"))),
            names,
        )
        assertEquals(1, out.size)
        val mark = out.single()
        assertEquals(MarkKind.CRASH, mark.kind)
        assertTrue(mark.community)
        assertTrue(mark.mark)
        assertEquals("ДТП", mark.label)
        assertEquals("спільнота", mark.detail)

        val json = eventMarksGeoJson(out)
        assertTrue(json.contains("\"kind\":\"crash\""))
        assertTrue(json.contains("\"community\":true"))
        assertTrue(json.contains("\"mark\":true"))
        assertTrue(json.contains("\"detail\":\"спільнота\""))
    }

    @Test
    fun `a mark from the live plane keeps its point, in GeoJSON order`() {
        val marker = hazardMarkerOf(
            HazardMarkInput(
                kind = "ДТП",
                lat = 50.4722,
                lon = 30.3985,
                source = HAZARD_SOURCE_COMMUNITY,
            ),
        )
        val json = eventMarksGeoJson(hazardLayerEventMarks(listOf(markerLayer(marker)), names))
        // GeoJSON — це [довгота, широта]. Переставлені місцями числа кладуть
        // київську мітку в Казахстан: сцена та сама, лічильник каже «є», а
        // екрана знак не торкається.
        assertTrue(json, json.contains("\"coordinates\":[30.3985,50.4722]"))
        assertTrue(json.contains("\"kind\":\"crash\""))
        assertTrue(json.contains("\"community\":true"))
    }

    @Test
    fun `whatever the three hands write, the layer gets a class the pen can draw`() {
        // Палець, world.report і жива площина шлють вільний рядок. Клас у
        // GeoJSON мусить бути id, який figureImage має чим намалювати, —
        // інакше знак є в сцені й ніколи не зʼявляється на склі.
        val hands = listOf(
            "ДТП", "gate", "Шлагбаум", "укриття", "камера", "ремонт", "щось невідоме",
        )
        hands.forEach { kind ->
            val marker = hazardMarkerOf(HazardMarkInput(kind = kind, lat = 50.45, lon = 30.52))
            val mark = hazardLayerEventMarks(listOf(markerLayer(marker)), names).single()
            assertTrue("«$kind» → ${mark.kind!!.id}", mark.kind!!.id in MARK_CLASSES)
            assertTrue(eventMarksGeoJson(listOf(mark)).contains("\"kind\":\"${mark.kind!!.id}\""))
        }
    }

    @Test
    fun `patrol and checkpoint marks left in a layer reach no scene`() {
        listOf("патруль", "Патруль", "patrol", "поліція", "блокпост", "КПП").forEach { kind ->
            val marker = hazardMarkerOf(HazardMarkInput(kind = kind, lat = 50.45, lon = 30.52))
            assertTrue(kind, hazardLayerEventMarks(listOf(markerLayer(marker)), names).isEmpty())
        }
    }

    @Test
    fun `an invisible layer and a foreign kind stay off the mark scene`() {
        assertTrue(hazardLayerEventMarks(listOf(markerLayer(marker(), visible = false)), names).isEmpty())
        assertTrue(hazardLayerEventMarks(listOf(markerLayer(marker(), kind = "poi")), names).isEmpty())
    }

    @Test
    fun `a layer mark outranks its own radar echo on the same cell`() {
        val echo = SafetyHazard(
            id = "haz_0",
            lat = 50.4722,
            lon = 30.3985,
            title = "ДТП",
            type = HazardType.ACCIDENT_HOTSPOT,
            source = HAZARD_SOURCE_COMMUNITY,
        )
        val scene = eventMarksScene(
            layers = listOf(markerLayer(marker(source = HAZARD_SOURCE_COMMUNITY))),
            events = listOf(echo),
            zoom = 17.0,
            corridor = emptyList(),
            focus = null,
            names = names,
        )
        assertEquals(1, scene.size)
        assertTrue(scene.single().mark)
    }

    @Test
    fun `radar and POI events keep flowing when no layer mark claims the spot`() {
        val scene = eventMarksScene(
            layers = emptyList(),
            events = listOf(hazard(type = HazardType.SPEED_CAMERA)),
            zoom = 17.0,
            corridor = emptyList(),
            focus = null,
            names = names,
        )
        assertEquals(1, scene.size)
        assertFalse(scene.single().mark)
    }

    @Test
    fun `behind the wheel a courtyard gate is not a road fact, a crash is`() {
        val scene = eventMarksScene(
            layers = listOf(markerLayer(marker(label = "ворота"), marker(label = "ДТП", lat = 50.48))),
            events = emptyList(),
            zoom = 17.0,
            corridor = emptyList(),
            focus = null,
            names = names,
            scene = SceneMode.DRIVE,
        )
        assertEquals(listOf(MarkKind.CRASH), scene.map { it.kind })
    }

    @Test
    fun `under a siren the shelter leads the scene, elsewhere order is untouched`() {
        val layers = listOf(markerLayer(marker(label = "ДТП"), marker(label = "укриття", lat = 50.48)))
        val threat = eventMarksScene(
            layers = layers, events = emptyList(), zoom = 17.0,
            corridor = emptyList(), focus = null, names = names,
            scene = SceneMode.THREAT,
        )
        assertEquals(MarkKind.SHELTER, threat.first().kind)
        val explore = eventMarksScene(
            layers = layers, events = emptyList(), zoom = 17.0,
            corridor = emptyList(), focus = null, names = names,
        )
        assertEquals(listOf(MarkKind.CRASH, MarkKind.SHELTER), explore.map { it.kind })
    }

    @Test
    fun `hazard marks leave the phantom dot path, other layers keep it`() {
        val hazardLayer = markerLayer(marker())
        val poiLayer = markerLayer(
            SpatialMarker(lat = 50.5, lon = 30.5, label = "аптека"),
            kind = "poi",
        )
        val json = buildLayerMarkersGeoJson(listOf(hazardLayer, poiLayer))
        assertFalse(json.contains("ДТП"))
        assertTrue(json.contains("аптека"))
    }

    private fun poi(
        id: Long,
        category: String,
        subcategory: String?,
        name: String,
    ) = PoiEntity(
        id = id,
        lat = 50.45 + id / 1000.0,
        lon = 30.52,
        name = name,
        category = category,
        subcategory = subcategory,
        tags = emptyMap(),
    )
}
