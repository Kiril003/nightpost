package local.phantom.companion.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QH1 — pin the destructive-action gating logic so a future refactor
 * doesn't accidentally reintroduce single-tap «Видалити все».
 *
 * Operator's complaint surfaced because that button used to fire on
 * the first tap from the Settings footer, alongside the «Зберегти»
 * button — easy to mistap when both have the same TextButton style.
 * The controller is the JVM-pure half of the fix; Compose UI mirrors
 * the same phase machine.
 */
class DangerZoneControllerTest {

    private class FakeClock(var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
    }

    @Test
    fun `starts in Idle`() {
        val ctrl = DangerZoneController(nowMs = FakeClock())
        assertEquals(DangerZoneController.Phase.Idle, ctrl.currentPhase())
        assertFalse(ctrl.canCommit())
    }

    @Test
    fun `openFirstConfirm moves to FirstConfirm phase`() {
        val ctrl = DangerZoneController(nowMs = FakeClock())
        ctrl.openFirstConfirm()
        assertEquals(DangerZoneController.Phase.FirstConfirm, ctrl.currentPhase())
        assertFalse("FirstConfirm cannot commit yet", ctrl.canCommit())
    }

    @Test
    fun `acceptFirstConfirm starts cooldown`() {
        val clock = FakeClock(now = 1000)
        val ctrl = DangerZoneController(nowMs = clock, cooldownMs = 3000)
        ctrl.openFirstConfirm()
        ctrl.acceptFirstConfirm()
        assertEquals(DangerZoneController.Phase.Cooldown, ctrl.currentPhase())
        assertFalse("commit blocked at t=0 of cooldown", ctrl.canCommit())
    }

    @Test
    fun `commit blocked while cooldown elapsing`() {
        val clock = FakeClock(now = 1000)
        val ctrl = DangerZoneController(nowMs = clock, cooldownMs = 3000)
        ctrl.openFirstConfirm()
        ctrl.acceptFirstConfirm()

        clock.now = 1000 + 1500     // half of cooldown
        assertFalse(ctrl.canCommit())

        clock.now = 1000 + 2999     // 1 ms before cooldown ends
        assertFalse(ctrl.canCommit())
    }

    @Test
    fun `commit allowed once cooldown elapses`() {
        val clock = FakeClock(now = 1000)
        val ctrl = DangerZoneController(nowMs = clock, cooldownMs = 3000)
        ctrl.openFirstConfirm()
        ctrl.acceptFirstConfirm()

        clock.now = 1000 + 3000
        assertTrue(ctrl.canCommit())

        clock.now = 1000 + 5000     // any time after cooldown is fine
        assertTrue(ctrl.canCommit())
    }

    @Test
    fun `commit fires action and resets to Idle`() {
        val clock = FakeClock(now = 1000)
        val ctrl = DangerZoneController(nowMs = clock, cooldownMs = 3000)
        ctrl.openFirstConfirm()
        ctrl.acceptFirstConfirm()
        clock.now = 4500

        var fired = false
        ctrl.commit { fired = true }

        assertTrue("action invoked exactly once", fired)
        assertEquals(DangerZoneController.Phase.Idle, ctrl.currentPhase())
        assertFalse(ctrl.canCommit())
    }

    @Test(expected = IllegalStateException::class)
    fun `commit before cooldown elapses throws`() {
        val clock = FakeClock(now = 1000)
        val ctrl = DangerZoneController(nowMs = clock, cooldownMs = 3000)
        ctrl.openFirstConfirm()
        ctrl.acceptFirstConfirm()
        clock.now = 1500     // half-cooldown
        ctrl.commit { /* must not fire */ }
    }

    @Test
    fun `cancel from any phase returns to Idle`() {
        val ctrl = DangerZoneController(nowMs = FakeClock())

        ctrl.openFirstConfirm()
        ctrl.cancel()
        assertEquals(DangerZoneController.Phase.Idle, ctrl.currentPhase())

        ctrl.openFirstConfirm()
        ctrl.acceptFirstConfirm()
        ctrl.cancel()
        assertEquals(DangerZoneController.Phase.Idle, ctrl.currentPhase())
    }

    @Test
    fun `cooldown countdown rounds up to the next whole second`() {
        val clock = FakeClock(now = 0)
        val ctrl = DangerZoneController(nowMs = clock, cooldownMs = 3000)
        ctrl.openFirstConfirm()
        ctrl.acceptFirstConfirm()

        // At t=0 of cooldown, 3 seconds remain.
        assertEquals(3, ctrl.remainingCooldownSeconds())
        clock.now = 1
        assertEquals(3, ctrl.remainingCooldownSeconds())
        clock.now = 1500
        assertEquals(2, ctrl.remainingCooldownSeconds())
        clock.now = 2500
        assertEquals(1, ctrl.remainingCooldownSeconds())
        clock.now = 2999
        assertEquals(1, ctrl.remainingCooldownSeconds())
        clock.now = 3000
        assertEquals(0, ctrl.remainingCooldownSeconds())
    }

    @Test
    fun `remainingCooldownSeconds is zero outside cooldown phase`() {
        val ctrl = DangerZoneController(nowMs = FakeClock())
        assertEquals(0, ctrl.remainingCooldownSeconds())
        ctrl.openFirstConfirm()
        assertEquals(0, ctrl.remainingCooldownSeconds())
    }

    @Test(expected = IllegalStateException::class)
    fun `acceptFirstConfirm outside FirstConfirm throws`() {
        val ctrl = DangerZoneController(nowMs = FakeClock())
        ctrl.acceptFirstConfirm()
    }
}
