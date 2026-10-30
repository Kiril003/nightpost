package local.phantom.companion.core.agent

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCancellationRegistryTest {

    @Test
    fun `register then cancel returns true and cancels the job`() = runTest {
        val registry = AgentCancellationRegistry()
        val job = Job()

        registry.register("agent-1", job)
        val result = registry.cancel("agent-1")

        assertTrue("cancel should return true for known id", result)
        assertTrue("job should be cancelled", job.isCancelled)
    }

    @Test
    fun `cancel unknown id returns false`() {
        val registry = AgentCancellationRegistry()
        val result = registry.cancel("unknown-id")
        assertFalse("cancel should return false for unknown id", result)
    }

    @Test
    fun `isRunning is true while job is active`() = runTest {
        val registry = AgentCancellationRegistry()
        val job = Job()

        registry.register("agent-2", job)
        assertTrue("should be running after register", registry.isRunning("agent-2"))

        job.cancel()
        // After cancellation the job is no longer active.
        assertFalse("should not be running after cancel", registry.isRunning("agent-2"))
    }

    @Test
    fun `isRunning is false for unknown id`() {
        val registry = AgentCancellationRegistry()
        assertFalse(registry.isRunning("nobody"))
    }

    @Test
    fun `size reflects active jobs`() = runTest {
        val registry = AgentCancellationRegistry()
        assertEquals(0, registry.size())

        val job1 = Job()
        val job2 = Job()
        registry.register("a", job1)
        registry.register("b", job2)
        assertEquals(2, registry.size())

        job1.cancel()
        // invokeOnCompletion fires synchronously for already-cancelled jobs on JVM.
        // Give the registry a moment to process by checking after join.
        job1.join()
        assertEquals(1, registry.size())

        job2.cancel()
        job2.join()
        assertEquals(0, registry.size())
    }

    @Test
    fun `completed job is auto-removed from registry`() = runTest {
        val registry = AgentCancellationRegistry()
        val job = Job()
        registry.register("auto-remove", job)
        assertTrue(registry.isRunning("auto-remove"))

        job.complete()
        job.join()

        assertFalse("completed job should be auto-removed", registry.isRunning("auto-remove"))
    }
}
