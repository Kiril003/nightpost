package local.phantom.companion.core.solana

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentWatchTest {

    private class Net {
        var online = false
        var probes = 0
        val probe: suspend () -> PaymentStatus = {
            probes++
            if (online) PaymentStatus.CONFIRMED else PaymentStatus.UNKNOWN
        }
    }

    private fun TestScope.watch(changes: IntArray = IntArray(1)) =
        PaymentWatch(backgroundScope, now = { testScheduler.currentTime }) { changes[0]++ }

    @Test
    fun cashThatArrivedOfflineIsConfirmedWhenTheNetworkComesBackHoursLater() = runTest {
        val net = Net()
        val changes = IntArray(1)
        val w = watch(changes)
        w.watch("sig", PaymentWatch.CASH_MAX_AGE_MS, net.probe)
        advanceTimeBy(3 * 3600_000L)
        assertEquals(null, w.status("sig"))
        net.online = true
        w.kick()
        runCurrent()
        assertEquals(PaymentStatus.CONFIRMED, w.status("sig"))
        assertEquals(1, changes[0])
        assertEquals(0, w.waitingCount)
    }

    @Test
    fun withoutANetworkSignalTheRareRetryStillGetsThere() = runTest {
        val net = Net()
        val w = watch()
        w.watch("sig", PaymentWatch.CASH_MAX_AGE_MS, net.probe)
        advanceTimeBy(5 * 3600_000L)
        net.online = true
        advanceTimeBy(PaymentWatch.MAX_PAUSE_MS + 1)
        assertEquals(PaymentStatus.CONFIRMED, w.status("sig"))
    }

    @Test
    fun retriesThinOutInsteadOfPollingEveryFewSeconds() = runTest {
        val net = Net()
        val w = watch()
        w.watch("sig", PaymentWatch.CASH_MAX_AGE_MS, net.probe)
        advanceTimeBy(24 * 3600_000L)
        assertTrue("${net.probes} probes in a day", net.probes in PaymentWatch.BURST..PaymentWatch.BURST + 60)
    }

    @Test
    fun pastTheAgeCeilingItStopsButOpeningTheCardStillLooksOnce() = runTest {
        val net = Net()
        val w = watch()
        w.watch("sig", PaymentWatch.PAY_MAX_AGE_MS, net.probe)
        advanceTimeBy(PaymentWatch.PAY_MAX_AGE_MS + PaymentWatch.MAX_PAUSE_MS * 2)
        val atCeiling = net.probes
        advanceTimeBy(24 * 3600_000L)
        w.kick()
        runCurrent()
        assertEquals(atCeiling, net.probes)
        net.online = true
        w.watch("sig", PaymentWatch.PAY_MAX_AGE_MS, net.probe)
        runCurrent()
        assertEquals(PaymentStatus.CONFIRMED, w.status("sig"))
    }

    @Test
    fun aSettledAnswerIsNotAskedAgain() = runTest {
        val net = Net().apply { online = true }
        val w = watch()
        w.watch("sig", PaymentWatch.PAY_MAX_AGE_MS, net.probe)
        runCurrent()
        w.watch("sig", PaymentWatch.PAY_MAX_AGE_MS, net.probe)
        w.kick()
        advanceTimeBy(3600_000L)
        assertEquals(1, net.probes)
    }
}
