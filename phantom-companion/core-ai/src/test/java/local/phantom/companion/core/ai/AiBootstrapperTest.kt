package local.phantom.companion.core.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AiBootstrapperTest {

    private class Router(var routes: Set<Route>) : AIRouter {
        override fun complete(intent: AIIntent): Flow<AIChunk> = emptyFlow()
        override suspend fun availableRoutes(): Set<Route> = routes
    }

    @Test
    fun `без розуму стан не Ready і нічого не качається`() = runTest {
        val boot = AiBootstrapper(Router(emptySet()), TestScope())
        assertEquals(BrainState.Idle, boot.ensureBrainNow(urgent = true))
    }

    @Test
    fun `ПК або ключ, що зʼявились, роблять стан Ready, а зниклі — повертають Idle`() = runTest {
        val router = Router(emptySet())
        val boot = AiBootstrapper(router, TestScope())
        router.routes = setOf(Route.PC_NODE)
        assertEquals(BrainState.Ready, boot.ensureBrainNow())
        router.routes = emptySet()
        assertEquals(BrainState.Idle, boot.ensureBrainNow())
    }
}
