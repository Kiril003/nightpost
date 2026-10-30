package local.phantom.companion.core.indoor.rtt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import local.phantom.companion.core.indoor.LocalPoint
import local.phantom.companion.core.indoor.RttRange
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Єдиний дотик модуля до платформи Android: `WifiRttManager` → [RttRange].
 *
 * Усе, що нижче цього файлу, — чистий Kotlin над простими типами, і саме тому
 * математику модуля можна перевірити JVM-тестом за секунду, а не поїздкою в
 * метро з ноутбуком. Тут навмисно немає жодного рішення: ні фільтрації
 * перевідбиттів, ні згладжування, ні вибору якорів. Тільки переклад.
 *
 * **Чому RTT, а не рівень сигналу.** RSSI під землею безнадійний: потужність
 * падає від тіла людини, від натовпу, від зачинених дверей вагона — і жодна
 * калібрувальна карта цього не враховує. RTT (802.11mc FTM) вимірює ЧАС
 * проходження пакета туди й назад, тобто фізичну відстань; перевідбиття псують
 * її на десятки сантиметрів, а не на десятки метрів.
 *
 * **Чому обмеження частоти обовʼязкове.** Один сеанс RTT до восьми точок — це
 * десятки радіообмінів і помітний імпульс струму. Опитування раз на секунду
 * зʼїдає батарею швидше за екран. Під землею телефон — єдине джерело звʼязку,
 * тож [RttThrottle] тут не оптимізація, а вимога безпеки.
 */
@RequiresApi(Build.VERSION_CODES.P)
class WifiRttRanger(
    private val context: Context,
    private val throttle: RttThrottle = RttThrottle(),
    private val executor: Executor = Executor { it.run() },
    private val clockMs: () -> Long = SystemClock::elapsedRealtime,
) {

    private val manager: WifiRttManager? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)) {
            context.getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as? WifiRttManager
        } else {
            null
        }

    /**
     * Чому саме зараз RTT (не)доступний. Перевіряється щоразу перед запитом:
     * користувач може вимкнути Wi-Fi або відкликати дозвіл між сеансами.
     */
    fun availability(): RttAvailability = when {
        manager == null -> RttAvailability.NO_HARDWARE
        !manager.isAvailable -> RttAvailability.DISABLED
        !hasPermission() -> RttAvailability.PERMISSION_MISSING
        else -> RttAvailability.SUPPORTED
    }

    /**
     * Один сеанс вимірювання.
     *
     * @param accessPoints результати сканування Wi-Fi; сканування — не справа
     *   цього модуля, його власник `:core-sensor`
     * @param anchors координати точок доступу в локальній системі за BSSID;
     *   якорі без координати все одно повертаються — вони знадобляться, коли
     *   план поверху приїде пізніше
     */
    suspend fun range(
        accessPoints: List<ScanResult>,
        anchors: Map<String, LocalPoint> = emptyMap(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): RttOutcome {
        val availability = availability()
        if (availability != RttAvailability.SUPPORTED) return RttOutcome.Unavailable(availability)
        if (accessPoints.isEmpty()) return RttOutcome.Success(emptyList())

        val now = clockMs()
        val waitMs = throttle.remainingMs(now)
        if (waitMs > 0L) return RttOutcome.Throttled(waitMs)
        throttle.markStarted(now)

        val peers = accessPoints.take(maxPeers())
        val request = RangingRequest.Builder().apply { addAccessPoints(peers) }.build()

        val outcome = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val callback = object : RangingResultCallback() {
                    override fun onRangingFailure(code: Int) {
                        if (cont.isActive) cont.resume(RttOutcome.Failed(code))
                    }

                    override fun onRangingResults(results: List<RangingResult>) {
                        if (!cont.isActive) return
                        val at = clockMs()
                        cont.resume(RttOutcome.Success(results.mapNotNull { translate(it, anchors, at) }))
                    }
                }
                try {
                    @Suppress("MissingPermission")
                    manager!!.startRanging(request, executor, callback)
                } catch (e: SecurityException) {
                    // Дозвіл відкликали між перевіркою і запитом — гонка, яку
                    // не усунути перевіркою, тільки обробкою.
                    if (cont.isActive) cont.resume(RttOutcome.Unavailable(RttAvailability.PERMISSION_MISSING))
                } catch (e: IllegalArgumentException) {
                    if (cont.isActive) cont.resume(RttOutcome.Failed(RangingResultCallback.STATUS_CODE_FAIL))
                }
            }
        }
        return outcome ?: RttOutcome.TimedOut
    }

    /**
     * Дозвіл, потрібний прямо цій ОС. До 33 — геолокація (Wi-Fi-сканування
     * історично прирівняне до визначення місця), від 33 — окремий
     * `NEARBY_WIFI_DEVICES`.
     */
    private fun hasPermission(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
    }

    private fun maxPeers(): Int = runCatching { RangingRequest.getMaxPeers() }
        .getOrDefault(FALLBACK_MAX_PEERS)
        .coerceIn(1, FALLBACK_MAX_PEERS)

    private fun translate(
        result: RangingResult,
        anchors: Map<String, LocalPoint>,
        atMs: Long,
    ): RttRange? {
        if (result.status != RangingResult.STATUS_SUCCESS) return null
        val bssid = result.macAddress?.toString() ?: return null
        return RttTranslation.toRange(
            bssid = bssid,
            elapsedMs = atMs,
            distanceMm = result.distanceMm,
            stdDevMm = result.distanceStdDevMm,
            rssiDbm = result.rssi,
            anchor = anchors[bssid],
        )
    }

    companion object {
        /** Сеанс до восьми точок укладається в ~1 с; далі це вже збій. */
        const val DEFAULT_TIMEOUT_MS = 3_000L

        /** `RangingRequest.getMaxPeers()` на всіх відомих пристроях ≤ 8. */
        const val FALLBACK_MAX_PEERS = 8
    }
}

/** Чому RTT недоступний. Показується користувачу як є — без вигаданих позицій. */
enum class RttAvailability {
    /** Можна міряти. */
    SUPPORTED,

    /** Пристрій не має `FEATURE_WIFI_RTT` — більшість бюджетних телефонів. */
    NO_HARDWARE,

    /** Обладнання є, але Wi-Fi або сама служба вимкнені. */
    DISABLED,

    /** Немає `NEARBY_WIFI_DEVICES` (API 33+) чи `ACCESS_FINE_LOCATION`. */
    PERMISSION_MISSING,
}

/** Підсумок одного сеансу вимірювання. */
sealed interface RttOutcome {
    data class Success(val ranges: List<RttRange>) : RttOutcome
    data class Unavailable(val reason: RttAvailability) : RttOutcome

    /** Запит відхилено обмежувачем частоти; повторити не раніше ніж через [retryInMs]. */
    data class Throttled(val retryInMs: Long) : RttOutcome
    data class Failed(val code: Int) : RttOutcome
    data object TimedOut : RttOutcome
}

/**
 * Обмежувач частоти сеансів RTT — чистий, тестований, без платформи.
 *
 * Мінімальний інтервал заданий у конструкторі, бо він різний для різних
 * режимів: людина стоїть на платформі — раз на 10 с вистачає; людина йде
 * переходом — раз на 2 с.
 */
class RttThrottle(private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS) {
    private var lastStartMs: Long = Long.MIN_VALUE

    /** Скільки ще мілісекунд чекати; 0 — можна міряти. */
    fun remainingMs(nowMs: Long): Long {
        if (lastStartMs == Long.MIN_VALUE) return 0L
        // Час назад означає перезавантаження лічильника — не блокуємо назавжди.
        if (nowMs < lastStartMs) return 0L
        val elapsed = nowMs - lastStartMs
        return (minIntervalMs - elapsed).coerceAtLeast(0L)
    }

    fun markStarted(nowMs: Long) {
        lastStartMs = nowMs
    }

    fun reset() {
        lastStartMs = Long.MIN_VALUE
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 2_000L
    }
}

/**
 * Переклад сирих чисел `RangingResult` у [RttRange] — окремо від платформи,
 * щоб перевірку меж можна було протестувати без пристрою.
 */
object RttTranslation {

    /** Менше — фізично неможливо: телефон не всередині точки доступу. */
    const val MIN_DISTANCE_M = 0.0

    /** Далі за це Wi-Fi під землею не чутно; таке значення — відлуння. */
    const val MAX_DISTANCE_M = 150.0

    /** 802.11mc дає 0.1–0.5 м; нуль означає «пристрій не повідомив». */
    const val MIN_STD_DEV_M = 0.3

    /**
     * @return `null`, якщо вимір не має фізичного сенсу. Відʼємна дальність —
     *   не рідкість: у сеансі з сильним прямим шляхом оцінка часу може вийти
     *   меншою за нуль, і драйвер віддає її як є.
     */
    fun toRange(
        bssid: String,
        elapsedMs: Long,
        distanceMm: Int,
        stdDevMm: Int,
        rssiDbm: Int?,
        anchor: LocalPoint?,
    ): RttRange? {
        val distanceM = distanceMm / 1000.0
        if (!distanceM.isFinite() || distanceM <= MIN_DISTANCE_M || distanceM > MAX_DISTANCE_M) return null
        val stdDevM = (stdDevMm / 1000.0).coerceAtLeast(MIN_STD_DEV_M)
        return RttRange(
            bssid = bssid,
            elapsedMs = elapsedMs,
            distanceM = distanceM,
            stdDevM = stdDevM,
            rssiDbm = rssiDbm,
            anchor = anchor,
        )
    }
}
