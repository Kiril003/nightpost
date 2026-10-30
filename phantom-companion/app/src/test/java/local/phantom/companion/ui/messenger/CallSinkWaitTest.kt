package local.phantom.companion.ui.messenger

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Опитування «чи бандл оголосив приймача» — те, що стоїть між кадром дзвінка й першим екраном. */
@OptIn(ExperimentalCoroutinesApi::class)
class CallSinkWaitTest {

    @Test
    fun `приймач уже є — вузол чує «готовий» одразу, без пауз`() = runTest {
        var ready = 0
        val ok = awaitCallSink(declared = { true }, onReady = { ready++ })
        assertTrue(ok)
        assertEquals(1, ready)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `приймач з'явився на четвертому питанні — «готовий» рівно один раз, пауза 200 мс`() = runTest {
        var asked = 0
        var ready = 0
        val ok = awaitCallSink(declared = { ++asked >= 4 }, onReady = { ready++ })
        assertTrue(ok)
        assertEquals(4, asked)
        assertEquals(1, ready)
        assertEquals(600L, currentTime)
    }

    @Test
    fun `приймача не буде — питати перестає, «готовий» не вигадується`() = runTest {
        var ready = 0
        var asked = 0
        val ok = awaitCallSink(declared = { asked++; false }, onReady = { ready++ }, giveUpMs = 15_000L)
        assertFalse(ok)
        assertEquals(0, ready)
        assertTrue("питання не мають спинятись раніше за терпіння", currentTime >= 15_000L)
        assertTrue("питання не мають йти без кінця", asked < 60)
    }
}
