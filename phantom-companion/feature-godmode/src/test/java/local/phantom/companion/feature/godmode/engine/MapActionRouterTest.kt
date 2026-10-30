package local.phantom.companion.feature.godmode.engine

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAP-AI-3 — pins the [MapActionRouter] contract:
 *   • emit() converts the 4 map verbs into typed [MapIntent]s and returns true
 *   • non-map verbs route past the router (returns false, no emission)
 *   • isMapAction() is exhaustive over the 4 map sealed variants
 *
 * The router is a process-singleton with a `SharedFlow` that persists
 * across tests; each test launches a collector for exactly the number
 * of events it expects then cancels — no cross-test leakage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapActionRouterTest {

    @Test
    fun `isMapAction returns true for all 4 map verbs`() {
        assertTrue(MapActionRouter.isMapAction(AppAction.NavigateTo(0.0, 0.0)))
        assertTrue(MapActionRouter.isMapAction(AppAction.SearchPlace("x")))
        assertTrue(MapActionRouter.isMapAction(AppAction.ShowOnMap("{}")))
        assertTrue(MapActionRouter.isMapAction(AppAction.SaveOfflineRegion(1.0, 2.0, 0.0, -1.0)))
    }

    @Test
    fun `isMapAction returns false for non-map verbs`() {
        assertFalse(MapActionRouter.isMapAction(AppAction.Back))
        assertFalse(MapActionRouter.isMapAction(AppAction.Home))
        assertFalse(MapActionRouter.isMapAction(AppAction.OpenApp("telegram")))
        assertFalse(MapActionRouter.isMapAction(AppAction.Click(ActionTarget(text = "x"))))
        assertFalse(MapActionRouter.isMapAction(AppAction.Type("hi")))
        assertFalse(MapActionRouter.isMapAction(AppAction.Scroll(ScrollDirection.DOWN)))
    }

    @Test
    fun `emit non-map verb returns false`() {
        assertFalse(MapActionRouter.emit(AppAction.Back))
        assertFalse(MapActionRouter.emit(AppAction.Home))
        assertFalse(MapActionRouter.emit(AppAction.OpenApp("phantom")))
    }

    @Test
    fun `emit NavigateTo surfaces as Navigate intent verbatim`() = runTest {
        val collected = mutableListOf<MapIntent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            MapActionRouter.events.take(1).toList(collected)
        }
        val action = AppAction.NavigateTo(
            latitude = 50.4501,
            longitude = 30.5234,
            mode = TravelMode.DRIVE,
            label = "Майдан",
        )
        assertTrue(MapActionRouter.emit(action))
        job.join()

        val intent = collected.single() as MapIntent.Navigate
        assertEquals(50.4501, intent.latitude, 0.0001)
        assertEquals(30.5234, intent.longitude, 0.0001)
        assertEquals(TravelMode.DRIVE, intent.mode)
        assertEquals("Майдан", intent.label)
    }

    @Test
    fun `emit SearchPlace surfaces as Search intent`() = runTest {
        val collected = mutableListOf<MapIntent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            MapActionRouter.events.take(1).toList(collected)
        }
        assertTrue(MapActionRouter.emit(AppAction.SearchPlace("кавʼярні")))
        job.join()

        val intent = collected.single() as MapIntent.Search
        assertEquals("кавʼярні", intent.query)
    }

    @Test
    fun `emit ShowOnMap surfaces geoJson verbatim`() = runTest {
        val collected = mutableListOf<MapIntent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            MapActionRouter.events.take(1).toList(collected)
        }
        val geo = """{"type":"Point","coordinates":[30,50]}"""
        assertTrue(MapActionRouter.emit(AppAction.ShowOnMap(geo)))
        job.join()

        val intent = collected.single() as MapIntent.Show
        assertEquals(geo, intent.geoJson)
    }

    @Test
    fun `emit SaveOfflineRegion surfaces bbox + name`() = runTest {
        val collected = mutableListOf<MapIntent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            MapActionRouter.events.take(1).toList(collected)
        }
        val action = AppAction.SaveOfflineRegion(
            north = 50.5,
            east = 30.6,
            south = 50.4,
            west = 30.5,
            name = "Київ-центр",
        )
        assertTrue(MapActionRouter.emit(action))
        job.join()

        val intent = collected.single() as MapIntent.SaveRegion
        assertEquals(50.5, intent.north, 0.0001)
        assertEquals(30.6, intent.east, 0.0001)
        assertEquals(50.4, intent.south, 0.0001)
        assertEquals(30.5, intent.west, 0.0001)
        assertEquals("Київ-центр", intent.name)
    }

    @Test
    fun `SaveOfflineRegion allows null name`() = runTest {
        val collected = mutableListOf<MapIntent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            MapActionRouter.events.take(1).toList(collected)
        }
        assertTrue(
            MapActionRouter.emit(
                AppAction.SaveOfflineRegion(1.0, 2.0, 0.0, -1.0, name = null),
            ),
        )
        job.join()

        val intent = collected.single() as MapIntent.SaveRegion
        assertNull(intent.name)
    }
}
