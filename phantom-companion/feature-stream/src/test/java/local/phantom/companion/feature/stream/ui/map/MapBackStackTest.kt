package local.phantom.companion.feature.stream.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Мапа виходила із застосунку замість того, щоб закрити свій же аркуш. Ці
 * випадки — не «покриття функції», а перелік натискань, які власник справді
 * зробив і опинився на робочому столі.
 */
class MapBackStackTest {

    private fun state(
        mount: Boolean = false,
        anchorPick: Boolean = false,
        anchorPlacing: Boolean = false,
        search: Boolean = false,
        answer: Boolean = false,
        alarmSheet: Boolean = false,
        transitHub: Boolean = false,
        maneuverList: Boolean = false,
        offlinePanel: Boolean = false,
        dossier: Boolean = false,
        savedPlace: Boolean = false,
        layerMarker: Boolean = false,
        peerPoint: Boolean = false,
        peerRoute: Boolean = false,
        threatOblast: Boolean = false,
        droppedPoint: Boolean = false,
        tools: Boolean = false,
        searchPin: Boolean = false,
        routeOrigin: Boolean = false,
        drawNaming: Boolean = false,
        lineCard: Boolean = false,
        drawing: Boolean = false,
        route: Boolean = false,
        guiding: Boolean = false,
    ) = MapBackState(
        mount = mount,
        anchorPick = anchorPick,
        anchorPlacing = anchorPlacing,
        search = search,
        answer = answer,
        alarmSheet = alarmSheet,
        transitHub = transitHub,
        maneuverList = maneuverList,
        offlinePanel = offlinePanel,
        dossier = dossier,
        savedPlace = savedPlace,
        layerMarker = layerMarker,
        peerPoint = peerPoint,
        peerRoute = peerRoute,
        threatOblast = threatOblast,
        droppedPoint = droppedPoint,
        tools = tools,
        searchPin = searchPin,
        routeOrigin = routeOrigin,
        drawNaming = drawNaming,
        lineCard = lineCard,
        drawing = drawing,
        route = route,
        guiding = guiding,
    )

    @Test
    fun `порожня мапа віддає натискання системі`() {
        assertNull(mapBackStep(state()))
    }

    @Test
    fun `відкритий пошук закривається, а не застосунок`() {
        assertEquals(MapBackStep.SEARCH, mapBackStep(state(search = true)))
    }

    @Test
    fun `кріплення знімається перше за все`() {
        assertEquals(
            MapBackStep.MOUNT,
            mapBackStep(state(mount = true, search = true, dossier = true, route = true)),
        )
    }

    @Test
    fun `аркуш поверх вибраного знімається першим`() {
        assertEquals(
            MapBackStep.OFFLINE_PANEL,
            mapBackStep(state(offlinePanel = true, dossier = true, tools = true)),
        )
    }

    @Test
    fun `досьє закривається раніше за шпильку, що його породила`() {
        assertEquals(
            MapBackStep.DOSSIER,
            mapBackStep(state(dossier = true, searchPin = true)),
        )
    }

    @Test
    fun `відповідь PHANTOM закривається назад, а не виходом із застосунку`() {
        // Саме та вада, від якої аркуш і зʼявився: питання з мапи не сміє
        // коштувати мапи — ані вперед, ані назад.
        assertEquals(MapBackStep.ANSWER, mapBackStep(state(answer = true)))
        assertEquals(
            MapBackStep.SEARCH,
            mapBackStep(state(search = true, answer = true)),
        )
    }

    @Test
    fun `прокладений маршрут знімається останнім`() {
        assertEquals(MapBackStep.ROUTE, mapBackStep(state(route = true)))
    }

    @Test
    fun `назад скасовує обраний початок маршруту раніше за сам маршрут`() {
        assertEquals(MapBackStep.ROUTE_ORIGIN, mapBackStep(state(routeOrigin = true)))
        assertEquals(
            MapBackStep.ROUTE_ORIGIN,
            mapBackStep(state(routeOrigin = true, route = true)),
        )
        // Аркуш поверх вибору початку закривається першим.
        assertEquals(
            MapBackStep.DROPPED_POINT,
            mapBackStep(state(droppedPoint = true, routeOrigin = true)),
        )
    }

    @Test
    fun `питання «як ставити дім» знімається раніше за пошук, з якого відкрилось`() {
        assertEquals(
            MapBackStep.ANCHOR_PICK,
            mapBackStep(state(anchorPick = true, search = true)),
        )
    }

    @Test
    fun `якір, що чекає дотику, не перехоплює назад в аркуша`() {
        // Поки чекає ЛИШЕ пілюля, видиме зверху — аркуш точки; зняти замість
        // нього невидимий стан означало б, що «назад» не робить нічого.
        assertEquals(
            MapBackStep.DROPPED_POINT,
            mapBackStep(state(anchorPlacing = true, droppedPoint = true)),
        )
        assertEquals(MapBackStep.ANCHOR_PLACING, mapBackStep(state(anchorPlacing = true)))
    }

    @Test
    fun `аркуш поворотів закривається сам, а не скидає маршрут`() {
        // Він мав аркуш і не мав кроку: «назад» долітав до ROUTE і стирав
        // прокладений маршрут, а під час ведення — виходив із застосунку.
        assertEquals(
            MapBackStep.MANEUVER_LIST,
            mapBackStep(state(maneuverList = true, route = true)),
        )
        assertEquals(
            MapBackStep.MANEUVER_LIST,
            mapBackStep(state(maneuverList = true, route = true, guiding = true)),
        )
    }

    @Test
    fun `живе ведення не скасовується жестом назад`() {
        assertNull(mapBackStep(state(route = true, guiding = true)))
    }

    @Test
    fun `під час ведення аркуші все одно закриваються`() {
        assertEquals(
            MapBackStep.TRANSIT_HUB,
            mapBackStep(state(transitHub = true, route = true, guiding = true)),
        )
    }

    @Test
    fun `кожен стан має свій крок — жоден не лишається без виходу`() {
        val each = listOf(
            MapBackStep.MOUNT to state(mount = true),
            MapBackStep.ANCHOR_PICK to state(anchorPick = true),
            MapBackStep.SEARCH to state(search = true),
            MapBackStep.ANSWER to state(answer = true),
            MapBackStep.ALARM_SHEET to state(alarmSheet = true),
            MapBackStep.TRANSIT_HUB to state(transitHub = true),
            MapBackStep.MANEUVER_LIST to state(maneuverList = true),
            MapBackStep.OFFLINE_PANEL to state(offlinePanel = true),
            MapBackStep.DOSSIER to state(dossier = true),
            MapBackStep.SAVED_PLACE to state(savedPlace = true),
            MapBackStep.DRAW_NAMING to state(drawNaming = true),
            MapBackStep.LINE_CARD to state(lineCard = true),
            MapBackStep.LAYER_MARKER to state(layerMarker = true),
            MapBackStep.PEER_POINT to state(peerPoint = true),
            MapBackStep.PEER_ROUTE to state(peerRoute = true),
            MapBackStep.THREAT_OBLAST to state(threatOblast = true),
            MapBackStep.DROPPED_POINT to state(droppedPoint = true),
            MapBackStep.TOOLS to state(tools = true),
            MapBackStep.SEARCH_PIN to state(searchPin = true),
            MapBackStep.ROUTE_ORIGIN to state(routeOrigin = true),
            MapBackStep.ANCHOR_PLACING to state(anchorPlacing = true),
            MapBackStep.DRAW to state(drawing = true),
            MapBackStep.ROUTE to state(route = true),
        )
        each.forEach { (expected, s) -> assertEquals(expected, mapBackStep(s)) }
        assertEquals(MapBackStep.entries.size, each.size)
    }
}
