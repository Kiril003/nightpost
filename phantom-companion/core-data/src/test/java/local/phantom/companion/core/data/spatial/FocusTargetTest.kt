package local.phantom.companion.core.data.spatial

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Свіжість запиту камери.
 *
 * Потік повторює останній запит, бо той, хто просить, зазвичай ЩОЙНО пішов на
 * мапу — інакше подія губиться в проміжку. Ціна повтору: без позначки часу
 * мапа стрибала б до вчорашньої точки щоразу, коли її відкривають.
 */
class FocusTargetTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `a request made a moment ago is honoured`() {
        assertTrue(FocusTarget(50.0, 30.0, atMs = now - 1_500L).isFresh(now))
        assertTrue(FocusTarget(50.0, 30.0, atMs = now).isFresh(now))
    }

    @Test
    fun `a replayed request from yesterday is not an instruction`() {
        assertFalse(FocusTarget(50.0, 30.0, atMs = now - 86_400_000L).isFresh(now))
    }

    @Test
    fun `the boundary is inclusive so a slow cold map still gets its fly`() {
        val edge = FocusTarget(50.0, 30.0, atMs = now - FocusTarget.FOCUS_FRESH_MS)
        assertTrue(edge.isFresh(now))
        assertFalse(FocusTarget(50.0, 30.0, atMs = now - FocusTarget.FOCUS_FRESH_MS - 1).isFresh(now))
    }
}
