package local.phantom.companion.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import local.phantom.companion.core.net.peer.PeerRelay
import java.util.concurrent.TimeUnit

/**
 * DA-5 — pure helpers that build the [PeriodicWorkRequest] for both
 * Phase 3 schedulers + the AppContainer-side enqueue glue.
 *
 * Pulled out as a separate object so the unit test can assert
 * constraints (interval, network, unique-name policy) on the built
 * `PeriodicWorkRequest` without spinning up an actual WorkManager
 * runtime. The schedulers themselves keep their pure-Kotlin
 * `runOnceIfDue` API; the Worker classes are thin shims that
 * delegate to the same code path the «force-fire from Settings»
 * button uses.
 *
 * The «no new dependencies» rule of Phase 2.5 / Phase 3 has one
 * documented exception, and this file is its blast radius. See
 * `docs/INTEGRATION_AUDIT.md` § B-4 / B-5 for the rationale.
 */
object WorkScheduling {

    /** Unique-work name for the daily stream_items TTL cleanup. */
    const val STREAM_CLEANUP_NAME: String = "phantom.stream_cleanup"

    /** Unique-work name for the daily 9 am notification digest. */
    const val NOTIFICATION_DIGEST_NAME: String = "phantom.notification_digest"

    /** Unique-work name for the daily 03:00 symbiote constitution audit. */
    const val SYMBIOTE_AUDIT_NAME: String = "phantom.symbiote_audit"

    /** Unique-work name for the licence + update check. */
    const val LICENSE_CHECK_NAME: String = "phantom.license_check"

    /** Unique-work name for the blind-relay pickup. */
    const val PEER_RELAY_NAME: String = "phantom.peer_relay"

    /**
     * Daily TTL cleanup. Repeat interval is 24 h with a 1 h flex
     * window — the gating logic inside
     * [StreamCleanupScheduler.runOnceIfDue] further enforces «not
     * more than once per 24h since last run», so a same-day
     * re-fire after a process restart is a no-op. No network
     * required (the cleanup is pure local DB).
     */
    fun cleanupRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<StreamCleanupWorker>(
            repeatInterval = 24L,
            repeatIntervalTimeUnit = TimeUnit.HOURS,
            flexTimeInterval = 1L,
            flexTimeIntervalUnit = TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build(),
            )
            .addTag(STREAM_CLEANUP_NAME)
            .build()

    /**
     * Notification digest. Wakes every 30 min so a 9 am crossing
     * is never missed by more than half-an-hour even after a
     * device reboot. The gating logic inside
     * [NotificationDigestScheduler.runOnceIfDue] enforces «only
     * once per local day, only after `emitHour` has passed». No
     * network required (digest is local-only).
     */
    fun digestRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<NotificationDigestWorker>(
            repeatInterval = 30L,
            repeatIntervalTimeUnit = TimeUnit.MINUTES,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build(),
            )
            .addTag(NOTIFICATION_DIGEST_NAME)
            .build()

    fun symbioteAuditRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<SymbioteAuditWorker>(
            repeatInterval = 24L,
            repeatIntervalTimeUnit = TimeUnit.HOURS,
            flexTimeInterval = 2L,
            flexTimeIntervalUnit = TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build(),
            )
            .addTag(SYMBIOTE_AUDIT_NAME)
            .build()

    /**
     * Licence + update check. The only one of the four that talks to the
     * network, so it is also the only one that waits for a connection and
     * refuses a flat battery. The 12 h cadence only WAKES the check —
     * `LicenseService.CHECK_IN_INTERVAL_MS` decides whether it goes out at
     * all, so a licensed device still speaks about once a day, and a device
     * with no signal spends nothing.
     */
    fun licenseCheckRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<LicenseCheckWorker>(
            repeatInterval = 12L,
            repeatIntervalTimeUnit = TimeUnit.HOURS,
            flexTimeInterval = 2L,
            flexTimeIntervalUnit = TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .addTag(LICENSE_CHECK_NAME)
            .build()

    /**
     * Забір зі сховка. Крок — [PeerRelay.POLL_MINUTES]: це не смак, а мінімум,
     * який платформа взагалі дає періодичній роботі.
     *
     * Єдина з пʼяти без `setRequiresBatteryNotLow`, і саме тому вона тут окремо
     * названа: слово, покладене в сховок, мусить доїхати й на семи відсотках.
     * Телефон без жодного контакту не робить із цього прокидання нічого —
     * рішення в [local.phantom.companion.core.net.peer.PeerRelayGate].
     */
    fun peerRelayRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<PeerRelayWorker>(
            repeatInterval = PeerRelay.POLL_MINUTES,
            repeatIntervalTimeUnit = TimeUnit.MINUTES,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .addTag(PEER_RELAY_NAME)
            .build()

    /**
     * AppContainer-side glue: enqueue every periodic schedule with
     * KEEP semantics so an existing schedule (carried across cold
     * launches by WorkManager's WorkDb) is not replaced. KEEP also
     * means the operator's first install enqueues the schedule
     * once and every subsequent launch is a cheap no-op.
     */
    fun enqueueAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            STREAM_CLEANUP_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            cleanupRequest(),
        )
        wm.enqueueUniquePeriodicWork(
            NOTIFICATION_DIGEST_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            digestRequest(),
        )
        wm.enqueueUniquePeriodicWork(
            SYMBIOTE_AUDIT_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            symbioteAuditRequest(),
        )
        wm.enqueueUniquePeriodicWork(
            LICENSE_CHECK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            licenseCheckRequest(),
        )
        wm.enqueueUniquePeriodicWork(
            PEER_RELAY_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            peerRelayRequest(),
        )
    }
}
