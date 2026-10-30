package local.phantom.companion.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import local.phantom.companion.core.nav.NavCue
import local.phantom.companion.core.voice.UaTtsNormalizer
import local.phantom.companion.feature.stream.ui.map.AirAlertNotice
import local.phantom.companion.feature.stream.ui.map.AirAlertNoticeKind

/**
 * Голос тривоги, що не залежить від екрана. Раніше фразу промовляв
 * MapViewModel — і в кишені, з мертвою MainActivity, PHANTOM мовчав.
 * Живе у службі; чистий Kotlin, щоб доводитись без Android.
 */
class AirAlertVoice(
    private val prewarm: suspend (String) -> Unit,
    private val submit: (NavCue) -> Unit,
    private val normalize: (String) -> String = UaTtsNormalizer::normalize,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val prewarmBudgetMs: Long = PREWARM_BUDGET_MS,
) {
    suspend fun announce(notice: AirAlertNotice) {
        // Синтез ДО черги: терміновий маршрутизатор віддає непрогріту фразу
        // системному голосу, а прогріту — живому, з кешу.
        withTimeoutOrNull(prewarmBudgetMs) {
            try {
                prewarm(normalize(notice.phrase))
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
            }
        }
        submit(
            NavCue(
                text = notice.phrase,
                urgency = if (notice.kind == AirAlertNoticeKind.RAISED) NavCue.Urgency.NOW else NavCue.Urgency.SOON,
                key = KEY,
                bornMs = clockMs(),
                ttlMs = TTL_MS,
            ),
        )
    }

    companion object {
        const val KEY = "air-alert"
        const val TTL_MS = 120_000L
        const val PREWARM_BUDGET_MS = 12_000L
    }
}
