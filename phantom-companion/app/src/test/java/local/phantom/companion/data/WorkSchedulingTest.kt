package local.phantom.companion.data

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * DA-5 — pin the periodic-work request constraints so an accidental
 * tweak doesn't silently change the operator-relevant invariants
 * (interval, network requirement, unique-name tag). The actual
 * enqueue path is exercised on-device via `WorkScheduling.enqueueAll`;
 * this test only validates the WorkRequest spec because asserting
 * tag + interval + network is enough to catch the kind of regression
 * the audit doc was worried about (a future patch silently bumping
 * digest interval to 24 h would re-introduce the «9 am misses by
 * 23 h» bug).
 */
class WorkSchedulingTest {

    @Test
    fun `cleanup request has 24h interval and no network requirement`() {
        val req = WorkScheduling.cleanupRequest()
        val info = req.workSpec
        assertEquals(
            "daily cleanup must run every 24h",
            TimeUnit.HOURS.toMillis(24L),
            info.intervalDuration,
        )
        assertEquals(
            "cleanup is local DB only — must NOT depend on network",
            NetworkType.NOT_REQUIRED,
            info.constraints.requiredNetworkType,
        )
        assertTrue(
            "request must carry the canonical unique-name tag for forensics",
            req.tags.contains(WorkScheduling.STREAM_CLEANUP_NAME),
        )
    }

    @Test
    fun `digest request has 30min interval and no network requirement`() {
        val req = WorkScheduling.digestRequest()
        val info = req.workSpec
        assertEquals(
            "30 min cadence so 9 am crossing is never missed by more than half-an-hour",
            TimeUnit.MINUTES.toMillis(30L),
            info.intervalDuration,
        )
        assertEquals(
            "digest formatter is local-only — network must NOT be required",
            NetworkType.NOT_REQUIRED,
            info.constraints.requiredNetworkType,
        )
        assertTrue(
            "request must carry the canonical unique-name tag for forensics",
            req.tags.contains(WorkScheduling.NOTIFICATION_DIGEST_NAME),
        )
    }

    /**
     * The licence/update check used to run once per process start and had no
     * schedule at all — a crew that never restarts the app was never offered a
     * build that shipped meanwhile.
     */
    @Test
    fun `license check is scheduled, needs network and spares a low battery`() {
        val req = WorkScheduling.licenseCheckRequest()
        val info = req.workSpec
        assertEquals(
            "12 h cadence — LicenseService's own 20 h gate decides whether it goes out",
            TimeUnit.HOURS.toMillis(12L),
            info.intervalDuration,
        )
        assertEquals(
            "the check talks to the licence server — it must wait for a connection",
            NetworkType.CONNECTED,
            info.constraints.requiredNetworkType,
        )
        assertTrue(
            "a background network wake must not run down a flat battery",
            info.constraints.requiresBatteryNotLow(),
        )
        assertTrue(
            "request must carry the canonical unique-name tag for forensics",
            req.tags.contains(WorkScheduling.LICENSE_CHECK_NAME),
        )
    }

    /**
     * `PeerRelay.POLL_MINUTES` існував і не годував нікого: періодичної роботи
     * не було, тож лист у сховку чекав, поки людина сама відкриє застосунок.
     */
    @Test
    fun `the relay pickup is scheduled, needs network and does not spare a low battery`() {
        val req = WorkScheduling.peerRelayRequest()
        val info = req.workSpec
        assertEquals(
            "крок — мінімум, який дає платформа",
            TimeUnit.MINUTES.toMillis(
                local.phantom.companion.core.net.peer.PeerRelay.POLL_MINUTES,
            ),
            info.intervalDuration,
        )
        assertEquals(
            "забір зі сховка — це запит назовні",
            NetworkType.CONNECTED,
            info.constraints.requiredNetworkType,
        )
        assertTrue(
            "слово мусить доїхати й на семи відсотках",
            !info.constraints.requiresBatteryNotLow(),
        )
        assertTrue(
            "request must carry the canonical unique-name tag for forensics",
            req.tags.contains(WorkScheduling.PEER_RELAY_NAME),
        )
    }
}
