package local.phantom.companion.core.context.nodes

import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.context.ContextInput
import local.phantom.companion.core.context.SystemState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for [ActiveAppNode]. The node feeds the [DecisionTree]
 * weight inputs that drive the 6-state FSM, so:
 *
 *   - the priority categories (GHOST / FOCUS / media-DREAM) need to fire
 *     for the documented packages,
 *   - and the unconfigured fallback must abstain (weight=0) so this
 *     node doesn't accidentally outvote a stronger signal from another
 *     decision node (TimeOfDay, Motion, etc.).
 */
class ActiveAppNodeTest {

    private val node = ActiveAppNode()

    private fun input(pkg: String?) = ContextInput(
        nowMs = 0L,
        localHour = 12,
        localDayOfWeek = 3,
        activeAppPackage = pkg,
    )

    @Test fun `null active app abstains with weight 0`() = runTest {
        val v = node.evaluate(input(null))
        assertEquals(SystemState.FOCUS, v.state)
        assertEquals(0f, v.weight, 0.0001f)
        assertEquals("", v.rationale)
    }

    @Test fun `Signal triggers GHOST with strong weight`() = runTest {
        val v = node.evaluate(input("org.thoughtcrime.securesms"))
        assertEquals(SystemState.GHOST, v.state)
        assertEquals(0.8f, v.weight, 0.0001f)
        // Rationale must be human-readable Ukrainian — feeds the System
        // Pulse narrative in SystemPulseRepository.composeNarrative.
        assertEquals("відкрито конфіденційний додаток", v.rationale)
    }

    @Test fun `system Settings is treated as a privacy surface`() = runTest {
        val v = node.evaluate(input("com.android.settings"))
        assertEquals(SystemState.GHOST, v.state)
    }

    @Test fun `Google Docs triggers FOCUS productivity weight`() = runTest {
        val v = node.evaluate(input("com.google.android.apps.docs"))
        assertEquals(SystemState.FOCUS, v.state)
        assertEquals(0.7f, v.weight, 0.0001f)
        assertEquals("робота з документами/кодом", v.rationale)
    }

    @Test fun `YouTube package triggers DREAM media weight`() = runTest {
        val v = node.evaluate(input("com.google.android.youtube"))
        assertEquals(SystemState.DREAM, v.state)
        assertEquals(0.5f, v.weight, 0.0001f)
        assertEquals("перегляд медіа", v.rationale)
    }

    @Test fun `Netflix package matches via substring contains`() = runTest {
        val v = node.evaluate(input("com.netflix.mediaclient"))
        assertEquals(SystemState.DREAM, v.state)
    }

    @Test fun `unknown package abstains with weight 0`() = runTest {
        // Unconfigured app: don't influence the FSM — let other nodes win.
        val v = node.evaluate(input("com.example.someapp"))
        assertEquals(SystemState.FOCUS, v.state)
        assertEquals(0f, v.weight, 0.0001f)
        assertEquals("", v.rationale)
    }
}
