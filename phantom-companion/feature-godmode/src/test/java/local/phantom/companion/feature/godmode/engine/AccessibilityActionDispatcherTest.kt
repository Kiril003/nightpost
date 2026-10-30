package local.phantom.companion.feature.godmode.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccessibilityActionDispatcherTest {

    private class RecordingController(
        private val perStepResult: (AppAction) -> Boolean = { true },
    ) : AccessibilityActionController {
        val executed = mutableListOf<AppAction>()
        override fun execute(action: AppAction): Boolean {
            executed += action
            return perStepResult(action)
        }
    }

    private fun authorizer(
        sensitive: Set<String> = emptySet(),
        vaultLocked: Boolean = false,
    ) = AppActionAuthorizer(
        isSensitiveApp = { sensitive.contains(it) },
        isVaultLocked = { vaultLocked },
    )

    @Test
    fun `runs all steps in order on the controller`() = runTest {
        val controller = RecordingController()
        val progress = mutableListOf<AppActionProgress>()
        val dispatcher = AccessibilityActionDispatcher(
            authorizer = authorizer(),
            controller = controller,
            currentPackage = { "com.example.app" },
            emitProgress = progress::add,
            minStepIntervalMs = 0L,
        )
        dispatcher.dispatch(
            listOf(
                AppAction.Click(ActionTarget(text = "Налаштування")),
                AppAction.Scroll(direction = ScrollDirection.DOWN),
                AppAction.Type(value = "Wi-Fi"),
            ),
        )
        advanceUntilIdle()
        assertEquals(3, controller.executed.size)
        assertTrue(progress.first() is AppActionProgress.Started)
        assertTrue(progress.last() is AppActionProgress.Completed)
        val stepEvents = progress.filterIsInstance<AppActionProgress.Step>()
        assertEquals(3, stepEvents.size)
        assertEquals(1, stepEvents[0].index)
        assertEquals(3, stepEvents[0].total)
    }

    @Test
    fun `caps execution at MAX_STEPS_PER_SEQUENCE`() = runTest {
        val controller = RecordingController()
        val dispatcher = AccessibilityActionDispatcher(
            authorizer = authorizer(),
            controller = controller,
            currentPackage = { "p" },
            emitProgress = {},
            minStepIntervalMs = 0L,
        )
        dispatcher.dispatch(List(20) { AppAction.Back })
        advanceUntilIdle()
        assertEquals(AppAction.MAX_STEPS_PER_SEQUENCE, controller.executed.size)
    }

    @Test
    fun `suspends on sensitive app and reports denial`() = runTest {
        val controller = RecordingController()
        val progress = mutableListOf<AppActionProgress>()
        val dispatcher = AccessibilityActionDispatcher(
            authorizer = authorizer(sensitive = setOf("com.ftband.mono")),
            controller = controller,
            currentPackage = { "com.ftband.mono" },
            emitProgress = progress::add,
            minStepIntervalMs = 0L,
        )
        dispatcher.dispatch(
            listOf(
                AppAction.Click(ActionTarget(text = "Pay")),
                AppAction.Click(ActionTarget(text = "Confirm")),
            ),
        )
        advanceUntilIdle()
        // Controller must NOT have executed any action.
        assertTrue("no clicks reach controller on banking app", controller.executed.isEmpty())
        val suspended = progress.filterIsInstance<AppActionProgress.Suspended>().firstOrNull()
        assertEquals(AppActionAuthorizer.REASON_SENSITIVE_APP, suspended?.reasonKey)
    }

    @Test
    fun `suspends globally when vault is locked`() = runTest {
        val controller = RecordingController()
        val progress = mutableListOf<AppActionProgress>()
        val dispatcher = AccessibilityActionDispatcher(
            authorizer = authorizer(vaultLocked = true),
            controller = controller,
            currentPackage = { "com.example.app" },
            emitProgress = progress::add,
            minStepIntervalMs = 0L,
        )
        dispatcher.dispatch(listOf(AppAction.Home, AppAction.Back))
        advanceUntilIdle()
        assertTrue(controller.executed.isEmpty())
        val suspended = progress.filterIsInstance<AppActionProgress.Suspended>().firstOrNull()
        assertEquals(AppActionAuthorizer.REASON_VAULT_LOCKED, suspended?.reasonKey)
    }

    @Test
    fun `controller false fails the sequence at that step`() = runTest {
        val controller = RecordingController(perStepResult = { it !is AppAction.Type })
        val progress = mutableListOf<AppActionProgress>()
        val dispatcher = AccessibilityActionDispatcher(
            authorizer = authorizer(),
            controller = controller,
            currentPackage = { "p" },
            emitProgress = progress::add,
            minStepIntervalMs = 0L,
        )
        dispatcher.dispatch(
            listOf(
                AppAction.Home,
                AppAction.Type(value = "x"),
                AppAction.Back,                         // never reached
            ),
        )
        advanceUntilIdle()
        assertEquals(2, controller.executed.size)
        val failed = progress.filterIsInstance<AppActionProgress.Failed>().firstOrNull()
        assertEquals(2, failed?.atStep)
    }

    @Test
    fun `re-authorizes per step when current package changes mid-flight`() = runTest {
        // The dispatcher reads currentPackage() before EACH step. We
        // flip the package on the second invocation so the first
        // click lands but the second is denied as a sensitive-app
        // intrusion — proving authorize is not a one-shot upfront
        // check but per-step.
        val callCount = intArrayOf(0)
        val controller = RecordingController()
        val progress = mutableListOf<AppActionProgress>()
        val dispatcher = AccessibilityActionDispatcher(
            authorizer = authorizer(sensitive = setOf("com.ftband.mono")),
            controller = controller,
            currentPackage = {
                callCount[0]++
                if (callCount[0] == 1) "com.benign.app" else "com.ftband.mono"
            },
            emitProgress = progress::add,
            minStepIntervalMs = 0L,
        )
        dispatcher.dispatch(
            listOf(
                AppAction.Click(ActionTarget(text = "Open")),
                AppAction.Click(ActionTarget(text = "Pay")),
            ),
        )
        advanceUntilIdle()

        assertEquals("first click executes; second is denied", 1, controller.executed.size)
        assertTrue(
            "Suspended event names sensitive-app",
            progress.any {
                it is AppActionProgress.Suspended &&
                    it.reasonKey == AppActionAuthorizer.REASON_SENSITIVE_APP
            },
        )
    }
}
