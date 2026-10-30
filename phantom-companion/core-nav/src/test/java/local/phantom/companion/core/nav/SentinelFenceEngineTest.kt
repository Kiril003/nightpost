package local.phantom.companion.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentinelFenceEngineTest {

    @Test
    fun triggersExitWhenLeavingSafeHaven() {
        val engine = SentinelFenceEngine()
        val safeZone = SentinelFenceEngine.Geofence(
            id = "camp_1",
            label = "Табір",
            centerLat = 50.450,
            centerLon = 30.520,
            radiusM = 500.0,
            kind = SentinelFenceEngine.FenceKind.SAFE_HAVEN,
        )
        engine.addFence(safeZone)

        // Inside safe zone initially: no exit event
        val initialEvents = engine.evaluate(50.450, 30.520)
        assertTrue(initialEvents.isEmpty())

        // Move 2 km away: must trigger EXIT
        val exitEvents = engine.evaluate(50.468, 30.520)
        assertEquals(1, exitEvents.size)
        val event = exitEvents.first()
        assertEquals(SentinelFenceEngine.TransitionType.EXIT, event.transition)
        assertTrue(event.messageUk.contains("вихід за межі безпечної зони"))
    }

    @Test
    fun triggersEnterWhenEnteringHazardZone() {
        val engine = SentinelFenceEngine()
        val hazardZone = SentinelFenceEngine.Geofence(
            id = "minefield_1",
            label = "Замінований сектор",
            centerLat = 50.480,
            centerLon = 30.550,
            radiusM = 300.0,
            kind = SentinelFenceEngine.FenceKind.HAZARD_ZONE,
        )
        engine.addFence(hazardZone)

        // Outside hazard zone initially
        val initialEvents = engine.evaluate(50.450, 30.520)
        assertTrue(initialEvents.isEmpty())

        // Move inside hazard zone: must trigger ENTER
        val enterEvents = engine.evaluate(50.480, 30.550)
        assertEquals(1, enterEvents.size)
        val event = enterEvents.first()
        assertEquals(SentinelFenceEngine.TransitionType.ENTER, event.transition)
        assertTrue(event.messageUk.contains("вхід у небезпечну зону"))
    }

    @Test
    fun countFollowsAddRemoveClear() {
        val engine = SentinelFenceEngine()
        val fence = SentinelFenceEngine.Geofence(
            id = "f1",
            label = "Дім",
            centerLat = 50.45,
            centerLon = 30.52,
            radiusM = 100.0,
            kind = SentinelFenceEngine.FenceKind.SAFE_HAVEN,
        )
        assertEquals(0, engine.count.value)
        engine.addFence(fence)
        engine.addFence(fence.copy(id = "f2"))
        assertEquals(2, engine.count.value)
        engine.removeFence("f1")
        assertEquals(1, engine.count.value)
        engine.clearAll()
        assertEquals(0, engine.count.value)
    }
}
