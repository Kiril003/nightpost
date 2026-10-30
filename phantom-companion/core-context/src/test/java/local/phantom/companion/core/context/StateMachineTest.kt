package local.phantom.companion.core.context

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Витримка була рівно 30 с при тику рушія 30 с, тож умова
 * `now - lastChange >= minDwell` виконувалась на кожному тику й не стримувала
 * нічого. Разом із тим, що вниз ішов сирий переможець дерева повз цю машину,
 * стан власника перекидався ВАРТА ⇄ ФОКУС кожні пів хвилини.
 */
class StateMachineTest {

    @Test
    fun `a change inside the dwell is refused`() {
        val m = StateMachine(initial = SystemState.FOCUS)
        m.consider(SystemState.SENTINEL, nowMs = 1_000_000L)
        assertEquals(SystemState.SENTINEL, m.state.value)

        m.consider(SystemState.FOCUS, nowMs = 1_030_000L)
        assertEquals(
            "30 секунд — це один тик рушія, не витримка",
            SystemState.SENTINEL,
            m.state.value,
        )
    }

    @Test
    fun `a change after the dwell is taken`() {
        val m = StateMachine(initial = SystemState.FOCUS)
        m.consider(SystemState.SENTINEL, nowMs = 1_000_000L)
        m.consider(SystemState.FOCUS, nowMs = 1_000_000L + 5 * 60_000L)

        assertEquals(SystemState.FOCUS, m.state.value)
    }

    @Test
    fun `the same state never restarts the dwell`() {
        val m = StateMachine(initial = SystemState.FOCUS)
        m.consider(SystemState.SENTINEL, nowMs = 1_000_000L)
        repeat(20) { m.consider(SystemState.SENTINEL, nowMs = 1_000_000L + it * 30_000L) }
        m.consider(SystemState.FOCUS, nowMs = 1_000_000L + 5 * 60_000L)

        assertEquals(SystemState.FOCUS, m.state.value)
    }
}
