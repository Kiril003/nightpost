package local.phantom.companion.core.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-scoped registry that maps an [agentId] to its running [Job] so
 * callers (e.g. StreamViewModel via WidgetEventBus.Cancel) can cancel a
 * specific sub-agent by id without holding a direct reference to its job.
 *
 * Thread-safety: backed by [ConcurrentHashMap]; safe to call from any thread.
 *
 * Lifecycle contract:
 * 1. Caller launches the supervisor coroutine and obtains its [Job].
 * 2. Caller calls [register] before the job is awaited.
 * 3. The registry auto-removes the entry via [Job.invokeOnCompletion].
 * 4. [cancel] finds the job and cancels it; the supervisor catches
 *    [CancellationException] and returns [AgentResult.Cancelled].
 */
class AgentCancellationRegistry {

    private val jobs = ConcurrentHashMap<String, Job>()

    /**
     * Binds [job] to [agentId] for the duration of its execution.
     * The binding is removed automatically when the job completes.
     */
    fun register(agentId: String, job: Job) {
        jobs[agentId] = job
        job.invokeOnCompletion { jobs.remove(agentId) }
    }

    /**
     * Signals cancellation to the job bound to [agentId].
     *
     * @param reason Human-readable cause forwarded as the [CancellationException] message.
     * @return `true` if a live job was found and signalled; `false` if [agentId] is unknown.
     */
    fun cancel(agentId: String, reason: String = "cancelled_by_operator"): Boolean {
        val job = jobs[agentId] ?: return false
        job.cancel(CancellationException(reason))
        return true
    }

    /** Returns `true` if a job for [agentId] exists and is still [Job.isActive]. */
    fun isRunning(agentId: String): Boolean = jobs[agentId]?.isActive == true

    /** Number of currently tracked (active) jobs. */
    fun size(): Int = jobs.size
}
