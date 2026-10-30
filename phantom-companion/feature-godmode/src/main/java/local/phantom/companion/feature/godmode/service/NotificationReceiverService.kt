package local.phantom.companion.feature.godmode.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import local.phantom.companion.core.data.notifications.NotificationDigestEntity
import local.phantom.companion.core.data.notifications.NotificationDigestRepository
import local.phantom.companion.feature.godmode.engine.GodModeEvent
import local.phantom.companion.feature.godmode.engine.GodModeEventBus
import local.phantom.companion.feature.godmode.engine.NotificationClassifier
import local.phantom.companion.feature.godmode.engine.NotificationImportant
import local.phantom.companion.feature.godmode.engine.NotificationImportantBus
import local.phantom.companion.feature.godmode.engine.NotificationRecentStore
import java.util.UUID

/**
 * Phase 3 W4-5 — re-implemented NotificationListenerService.
 *
 * onNotificationPosted →
 *   1. extract title + text + channelId from the Notification.extras;
 *   2. run [NotificationClassifier.classify] to get a redaction +
 *      Important / Routine / Spam decision (using the per-package
 *      rules supplied via [NotificationReceiverConfig.rules]);
 *   3. dispatch by classification:
 *      • IMPORTANT → push to [NotificationImportantBus] for Stream
 *        Notice surfacing (subject to the operator's quiet-hours
 *        gate at the receive side);
 *      • ROUTINE  → enqueue into [NotificationDigestRepository] for
 *        the daily 9 am digest; what gets persisted is the redacted
 *        title and body ([toDigestRow]), never the raw ones;
 *      • SPAM     → log only, stay silent.
 *
 * The legacy v1 `GodModeEvent.Notification` / `NotificationBurst`
 * events still fire alongside (TaskerEngine + LifeLogArchive depend
 * on them) — strictly additive change.
 *
 * Wiring is process-global through [NotificationReceiverConfig] so
 * AppContainer can supply the repo + rules without the Android-
 * instantiated service needing DI.
 */
class NotificationReceiverService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Legacy 30-second burst window — kept so TaskerEngine v1 logic still ticks. */
    private val burstBuffer = mutableMapOf<String, MutableList<Pair<Long, String>>>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName.orEmpty()
        if (packageName.isBlank()) return

        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE).orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val channelId = sbn.notification.channelId

        if (title.isBlank() && text.isBlank()) return

        val now = System.currentTimeMillis()

        // Classify + redact off-thread so onNotificationPosted returns fast.
        scope.launch {
            val rules = NotificationReceiverConfig.rules
            val result = NotificationClassifier.classify(
                packageName = packageName,
                channelId = channelId,
                title = title,
                text = text,
                rules = rules,
            )
            dispatchByClassification(result, now)
        }

        // Legacy bus paths — kept exactly as v1 so TaskerEngine /
        // ForegroundAppMonitor continue to receive the events.
        val list = burstBuffer.getOrPut(packageName) { mutableListOf() }
        list.add(now to "$title: $text")
        list.removeAll { now - it.first > BURST_WINDOW_MS }
        if (list.size >= BURST_THRESHOLD) {
            val combined = list.joinToString("\n") { it.second }
            GodModeEventBus.push(GodModeEvent.NotificationBurst(packageName, combined))
            list.clear()
        } else {
            GodModeEventBus.push(GodModeEvent.Notification(packageName, title, text))
        }
    }

    private fun resolveLabel(pkg: String): String = runCatching {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private suspend fun dispatchByClassification(
        result: NotificationClassifier.ClassificationResult,
        now: Long,
    ) {
        // Око симбіота бачить недавнє — у РЕДАГОВАНІЙ формі, без спаму.
        if (result.classification != NotificationClassifier.Classification.SPAM) {
            NotificationRecentStore.push(
                NotificationRecentStore.Recent(
                    packageName = result.packageName,
                    appLabel = resolveLabel(result.packageName),
                    title = result.redactedTitle,
                    text = result.redactedText,
                    atMs = now,
                ),
            )
        }
        when (result.classification) {
            NotificationClassifier.Classification.IMPORTANT -> {
                if (!NotificationReceiverConfig.isInQuietHours(now)) {
                    NotificationImportantBus.push(result.toImportant(now))
                } else {
                    Log.i(TAG, "quiet-hours suppress important from ${result.packageName}")
                }
            }
            NotificationClassifier.Classification.ROUTINE -> {
                val repo = NotificationReceiverConfig.repository
                if (repo != null) {
                    runCatching {
                        repo.enqueue(result.toDigestRow(UUID.randomUUID().toString(), now))
                    }.onFailure { t ->
                        Log.w(TAG, "digest enqueue failed for ${result.packageName}", t)
                    }
                }
            }
            NotificationClassifier.Classification.SPAM -> {
                Log.d(TAG, "spam ${result.packageName}: ${result.title}")
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Phase 4 — surface a corresponding "removed" hint to Stream
        // when an Important notification is dismissed externally
        // (operator opened Telegram), so the Notice can self-clear
        // without an explicit Notice cleanup gesture.
    }

    private companion object {
        const val TAG = "GodModeNotif"
        const val BURST_WINDOW_MS: Long = 30_000L
        const val BURST_THRESHOLD: Int = 3
    }
}

/** Що з класифікації лягає на шину: заголовок — редагований, як і тіло. */
internal fun NotificationClassifier.ClassificationResult.toImportant(
    atMs: Long,
): NotificationImportant = NotificationImportant(
    packageName = packageName,
    redactedTitle = redactedTitle,
    redactedText = redactedText,
    deliveredAtMs = atMs,
)

/** Що з класифікації лягає на диск: заголовок — редагований, як і тіло. */
internal fun NotificationClassifier.ClassificationResult.toDigestRow(
    id: String,
    atMs: Long,
): NotificationDigestEntity = NotificationDigestEntity(
    id = id,
    packageName = packageName,
    channelId = channelId,
    title = redactedTitle,
    redactedText = redactedText,
    deliveredAt = atMs,
)

/**
 * Phase 3 W4-5 — process-global config the AccessibilityService /
 * NotificationListenerService can reach for. AppContainer arms it
 * once at init; the service reads on every callback. Mirrors the
 * [AccessibilityServiceActionRegistry] pattern.
 */
object NotificationReceiverConfig {

    @Volatile
    var repository: NotificationDigestRepository? = null

    @Volatile
    var rules: List<NotificationClassifier.PackageRule> = emptyList()

    /**
     * Quiet hours — Important pushes are suppressed during this
     * window so a 3 am Telegram doesn't wake the operator. Default
     * 22:00 → 08:00 in the device's local time.
     *
     * Stored as minute-of-day (0..1439) so DST is a non-issue —
     * the OS clock already absorbs the shift.
     */
    @Volatile
    var quietHoursStartMin: Int = DEFAULT_QUIET_START_MIN
    @Volatile
    var quietHoursEndMin: Int = DEFAULT_QUIET_END_MIN

    fun isInQuietHours(epochMs: Long): Boolean {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
        val mod = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
            cal.get(java.util.Calendar.MINUTE)
        // Window may wrap midnight (start > end).
        return if (quietHoursStartMin <= quietHoursEndMin) {
            mod in quietHoursStartMin..quietHoursEndMin
        } else {
            mod >= quietHoursStartMin || mod <= quietHoursEndMin
        }
    }

    internal fun resetForTest() {
        repository = null
        rules = emptyList()
        quietHoursStartMin = DEFAULT_QUIET_START_MIN
        quietHoursEndMin = DEFAULT_QUIET_END_MIN
    }

    private const val DEFAULT_QUIET_START_MIN: Int = 22 * 60   // 22:00
    private const val DEFAULT_QUIET_END_MIN: Int = 8 * 60      // 08:00
}
