package local.phantom.companion.core.sensor.sources

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import local.phantom.companion.core.sensor.GpsSample
import local.phantom.companion.core.sensor.SatelliteTime
import local.phantom.companion.core.sensor.nav.KinematicsEngine

private const val TAG = "GpsSource"

/**
 * Три щаблі темпу GNSS.
 *
 * Було два, і перемикав їх режим супроводу — `guiding || followArmed ||
 * «мапа внизу»`. Жодне з трьох не є фактом руху, і саме звідси діра: людина
 * їде з відкритою мапою без прокладеного маршруту, темп лишається ambient,
 * і точка оживає раз на 5–9 секунд. Запис власника 29.08 (30 хв їзди,
 * 1083 зразки): 165 оновлень позиції, медіанний проміжок 9 с, найдовший 526.
 *
 * Числа тут виведені з дерева, не зі стелі:
 *
 *  - [NAVIGATION] 1_000 мс — те саме, що було. Турн-бай-турн коридор і
 *    детектор глушіння ([nav.CustomLocationEngine.JAM_TIMEOUT_MS] = 6 с)
 *    рахують на секунди.
 *  - [STANDSTILL] 2_500 мс — НЕ «щось повільне». Дві незалежні межі
 *    затискають його з обох боків:
 *      1. [nav.KinematicsEngine.classify] мовчить, доки у вікні
 *         [nav.KinematicsEngine.WINDOW_MS] (12 с) менше за чотири зразки
 *         швидкості (`MIN_SPEED_SAMPLES`). При 2,5 с у вікно влазить 5–6 —
 *         один із запасом на загублений фікс. При 5 с влазить 2–3, і
 *         класифікатор НІКОЛИ не вимовляє вирок: щабель, з якого не видно
 *         руху, — це щабель, з якого не злізти.
 *      2. Два проміжки по 2,5 с = 5 с < 6 с JAM_TIMEOUT_MS: один
 *         загублений фікс на щаблі спокою не має читатись як глушіння.
 *    Точність лишається PRIORITY_HIGH_ACCURACY: людина дивиться на мапу,
 *    просто стоїть. Балансований фікс дав би їй ~25-метрову ляпку.
 *  - [AMBIENT] 5_000 мс — як було: досить, щоб відповісти «де ти?»,
 *    дешево, щоб не гасити.
 *
 * [fastestMs] — APPLE-LOCK: чип має право віддати ШВИДШЕ за інтервал, коли
 * має свіже (більшість чипів дають 2–5 Гц під PRIORITY_HIGH_ACCURACY).
 * Інтервал — обіцянка батареї; ця підлога — дозвіл чипу. Пакетування
 * заборонене окремо нижче: пакетований фікс — це фікс, повз який уже проїхали.
 */
enum class NavCadence(
    val intervalMs: Long,
    val fastestMs: Long,
    val highAccuracy: Boolean,
) {
    AMBIENT(intervalMs = 5_000L, fastestMs = 5_000L, highAccuracy = false),
    STANDSTILL(intervalMs = 2_500L, fastestMs = 1_000L, highAccuracy = true),
    NAVIGATION(intervalMs = 1_000L, fastestMs = 250L, highAccuracy = true),
}

/**
 * Хто на якому щаблі — чиста функція стану плюс витримка.
 *
 * Без Android, без власного годинника: `nowMs` приносить викликач (у
 * `ContinuumHost` це `SystemClock.elapsedRealtime()` — монотонний, переживає
 * зміну стінного часу). Те саме вчення, що й у решті `nav`.
 *
 * ПРАВИЛО ЩАБЛІВ:
 * ```
 * живий супровід            -> NAVIGATION   (екран не має права його гасити)
 * застосунок не на передньому плані -> AMBIENT
 * мапа не на склі           -> AMBIENT
 * доведено, що стоїть       -> STANDSTILL
 * інакше                    -> NAVIGATION   (без торгу)
 * ```
 *
 * Порядок гілок — не косметика. «Доведено, що стоїть» вимагає ПОЗИТИВНОГО
 * свідчення: [nav.KinematicsEngine.MotionState.STILL] з упевненістю не нижче
 * за [nav.KinematicsEngine.AMBIGUOUS_CONFIDENCE] (0.6 — рівно та впевненість,
 * з якою двигун вимовляє STILL без IMU). Усе інше — UNKNOWN, свіжий старт,
 * відсутній свідок — читається як РУХ. Навігатор має право помилятись у бік
 * швидкого темпу, не в бік застиглої точки; а що кожен щабель вище за AMBIENT
 * годує класифікатор, помилка сама себе виправляє за ~12 с.
 */
class NavCadencePlanner(
    private val settleMs: Long = LADDER_SETTLE_MS,
) {

    /** Щабель, який зараз ЗАСТОСОВАНО (не той, якого просить стан). */
    var cadence: NavCadence = NavCadence.AMBIENT
        private set

    private var pending: NavCadence? = null
    private var pendingSinceMs = 0L

    /**
     * Підйом щабля — миттєвий. Спуск — тільки після [settleMs] незмінного
     * приводу. Повертає щабель, який належить застосувати зараз.
     */
    fun update(
        leading: Boolean,
        foreground: Boolean,
        mapOnGlass: Boolean,
        motion: KinematicsEngine.MotionProfile?,
        nowMs: Long,
    ): NavCadence {
        val target = target(leading, foreground, mapOnGlass, motion)
        if (target == cadence) {
            pending = null
            return cadence
        }
        // Менший інтервал = вищий щабель. Порівняння по інтервалу, а не по
        // ordinal: щабель, доданий у середину переліку, не має тихо
        // перевернути гістерезис.
        if (target.intervalMs < cadence.intervalMs) {
            pending = null
            cadence = target
            return cadence
        }
        if (target != pending) {
            // Інший привід перезапускає витримку — те саме вчення, що й у
            // [nav.KinematicsEngine]: миготливе свідчення не накопичується.
            pending = target
            pendingSinceMs = nowMs
        }
        if (nowMs - pendingSinceMs >= settleMs) {
            cadence = target
            pending = null
        }
        return cadence
    }

    /**
     * Скільки ще мілісекунд притримано спуск, або 0, якщо нічого не висить.
     *
     * Викликачеві це потрібно, бо спуск мусить долетіти навіть тоді, коли
     * більше НІЧОГО не станеться: людина заглушила мотор — і жодної нової
     * події, яка б розбудила [update], може не бути.
     */
    fun demotionDueInMs(nowMs: Long): Long {
        if (pending == null) return 0L
        return (settleMs - (nowMs - pendingSinceMs)).coerceAtLeast(1L)
    }

    private fun target(
        leading: Boolean,
        foreground: Boolean,
        mapOnGlass: Boolean,
        motion: KinematicsEngine.MotionProfile?,
    ): NavCadence = when {
        // Живий супровід не має форми екрана: Android Auto веде без жодної
        // живої Activity, а детектор глушіння рахує тишу в секундах.
        leading -> NavCadence.NAVIGATION
        // THERMAL 11.08 — палата не міняється від кнопки «Додому».
        !foreground -> NavCadence.AMBIENT
        !mapOnGlass -> NavCadence.AMBIENT
        motion.readsAsStanding() -> NavCadence.STANDSTILL
        else -> NavCadence.NAVIGATION
    }

    private fun KinematicsEngine.MotionProfile?.readsAsStanding(): Boolean {
        val p = this ?: return false
        if (p.state != KinematicsEngine.MotionState.STILL) return false
        return p.confidence >= KinematicsEngine.AMBIGUOUS_CONFIDENCE
    }

    companion object {
        /**
         * ВИТРИМКА СПОКОЮ — скільки привід на спуск мусить протриматись,
         * перш ніж чип справді сповільнять.
         *
         * 12_000 мс, і обидві половини числа з міряного:
         *
         *  - запис власника: медіанний проміжок між оновленнями позиції —
         *    9 с. Будь-яка витримка ≤ 9 с означає, що звичайна тиша старого
         *    темпу сама по собі виглядає як привід сповільнитись;
         *  - вирок STILL порахований медіаною по вікну
         *    [nav.KinematicsEngine.WINDOW_MS] = 12 с. Спускатись швидше, ніж
         *    за одне повне вікно, — це спускатись по свідченню, у якому ще
         *    лежить їзда.
         *
         * Зверху на це лягає власна витримка двигуна: машина стає STILL лише
         * через [nav.KinematicsEngine.VEHICLE_TO_STILL_DWELL_MS] = 25 с
         * («на світлофорі машина все ще машина»). Разом 25 + 12 = 37 с — саме
         * стільки треба простояти, щоб чип сповільнили. Світлофор не встигає.
         */
        const val LADDER_SETTLE_MS = 12_000L
    }
}

class GpsSource(private val context: Context) {

    private val _state = MutableStateFlow<GpsSample?>(null)
    val state: StateFlow<GpsSample?> = _state.asStateFlow()

    /**
     * PRECISION ENGINE, Section 4 — the INDEPENDENT network anchor.
     *
     * Wi-Fi/cell positioning cannot be spoofed the way GNSS RF can; in the Kyiv
     * capture it held true in Kyiv while the fused provider was walked to Peru.
     * This is deliberately a separate `LocationManager.NETWORK_PROVIDER` sample,
     * NOT another read of the fused client — the whole value is that it is a
     * second, GNSS-independent witness the [nav.ConsistencyGate] can hold the
     * fused fix against. Coarse (tens–hundred metres) and slow, which is all a
     * spoof reference needs to be.
     */
    private val _networkState = MutableStateFlow<GpsSample?>(null)
    val networkState: StateFlow<GpsSample?> = _networkState.asStateFlow()

    /**
     * H-02 — UTC as the satellites tell it, for [satelliteNowMs].
     *
     * A separate witness because the fused feed cannot be one. `Location.getTime()`
     * is satellite-decoded only on a fix that actually came down from the sky,
     * and the fused provider reports `provider == "fused"` whatever it blended,
     * so reading a time off [state] would give the device's own clock back with
     * a satellite's authority attached to it.
     *
     * PASSIVE_PROVIDER costs nothing: it never powers the GNSS chip, it only
     * copies fixes other subscribers already caused — including the ones this
     * class's own fused request causes. And unlike the fused location, a passive
     * delivery keeps the ORIGINATING provider's name, which is the proof this
     * needs and the only place it is available for free.
     */
    private val _satelliteTime = MutableStateFlow<SatelliteTime?>(null)
    val satelliteTime: StateFlow<SatelliteTime?> = _satelliteTime.asStateFlow()

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private var running = false
    private var cadence = NavCadence.AMBIENT

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            _state.value = GpsSample(
                lat = loc.latitude,
                lon = loc.longitude,
                accuracyM = loc.accuracy,
                speedMps = if (loc.hasSpeed()) loc.speed else null,
                bearingDeg = if (loc.hasBearing()) loc.bearing else null,
                altitudeM = if (loc.hasAltitude()) loc.altitude.toFloat() else null,
                elapsedMs = loc.elapsedRealtimeNanos / 1_000_000L,
            )
        }
    }

    // All four methods implemented on purpose: the status callbacks only became
    // interface default methods in API 30, and this ships to minSdk 29, where an
    // unimplemented onStatusChanged would AbstractMethodError the moment the
    // platform reported a provider status change.
    private val networkListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            _networkState.value = GpsSample(
                lat = loc.latitude,
                lon = loc.longitude,
                accuracyM = if (loc.hasAccuracy()) loc.accuracy else NETWORK_ASSUMED_ACC_M,
                speedMps = null,
                bearingDeg = null,
                altitudeM = null,
                elapsedMs = loc.elapsedRealtimeNanos / 1_000_000L,
            )
        }

        @Deprecated("Required for API 29; no-op — the gate reads position, not status.")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    /**
     * H-02 — the satellite clock witness. Accepts a delivery ONLY when the
     * originating provider is the platform's `gps`: everything else on the
     * passive stream (network, fused, another app's mock) carries a `time` that
     * is ultimately the device's own, and passing one through here would make
     * the clock check confirm the very clock it exists to doubt.
     */
    private val passiveListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            if (loc.provider != LocationManager.GPS_PROVIDER) return
            val elapsedMs = loc.elapsedRealtimeNanos / 1_000_000L
            if (elapsedMs <= 0L) return
            _satelliteTime.value = SatelliteTime(utcMs = loc.time, elapsedMs = elapsedMs)
        }

        @Deprecated("Required for API 29; no-op — this listener reads time, not status.")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    /**
     * H-02 — satellite UTC advanced to this instant, or null when no GNSS fix
     * has been seen recently enough to speak for now.
     *
     * The advance is by [SystemClock.elapsedRealtime], which counts through deep
     * sleep and is immune to every wall-clock change — so this stays satellite
     * truth rather than decaying back into the device's opinion. It is still
     * capped at [SAT_TIME_FRESH_MS]: monotonic projection would in principle
     * hold for the whole boot, but «the sky confirmed this clock» is a claim
     * about a recent observation, and a phone that has not seen a satellite
     * since morning must be allowed to age out of it rather than coast on one
     * fix forever.
     */
    fun satelliteNowMs(): Long? {
        val t = _satelliteTime.value ?: return null
        val age = SystemClock.elapsedRealtime() - t.elapsedMs
        if (age < 0L || age > SAT_TIME_FRESH_MS) return null
        return t.utcMs + age
    }

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        running = true
        request()
        requestNetworkAnchor()
        requestSatelliteClock()
    }

    /** Запит без дозволу падає один раз і не повторюється; дозвіл прийшов — повторити. */
    fun rearm() {
        if (!running) return
        request()
        requestNetworkAnchor()
        requestSatelliteClock()
    }

    /**
     * Subscribe to the passive stream purely to overhear satellite time. Costs
     * no power and cannot summon a fix on its own — on a device where nothing
     * else is using GNSS this simply stays silent, and the clock check honestly
     * reports that it has no witness.
     */
    @SuppressLint("MissingPermission")
    private fun requestSatelliteClock() {
        val lm = locationManager ?: return
        runCatching {
            lm.requestLocationUpdates(
                LocationManager.PASSIVE_PROVIDER,
                SAT_TIME_INTERVAL_MS,
                0f,
                passiveListener,
                context.mainLooper,
            )
        }.onFailure { t ->
            Log.w(TAG, "passive clock witness unavailable — light countdown runs unverified", t)
        }
    }

    /**
     * Subscribe to the independent network provider as the spoof reference. A
     * best-effort subscription: on a device with the network provider disabled
     * or Play-Services-only location, this stays silent and the gate simply runs
     * without an anchor (still catching the hAcc-explosion and kinematic cases).
     */
    @SuppressLint("MissingPermission")
    private fun requestNetworkAnchor() {
        val lm = locationManager ?: return
        runCatching {
            if (!lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) return
            lm.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                NET_ANCHOR_INTERVAL_MS,
                0f,
                networkListener,
                context.mainLooper,
            )
        }.onFailure { t ->
            Log.w(TAG, "network anchor unavailable — gate runs without a network reference", t)
        }
    }

    /**
     * Поставити чип на щабель темпу — див. [NavCadence]. Хто саме обирає
     * щабель: [NavCadencePlanner], з місця, де видно і рух, і екран.
     *
     * Телефон, який цілий день тримає PRIORITY_HIGH_ACCURACY, до вечора
     * розряджений; телефон, чия точка застигає на 7 секунд під час їзди, не є
     * навігатором. Тому щаблів три, а не два.
     */
    fun setCadence(next: NavCadence) {
        if (cadence == next) return
        cadence = next
        if (running) request()
    }

    /**
     * Старий двопозиційний вхід. Живий: ним ще ходить труба
     * `AppContainer.setNavigationGrade` → `MobileSensorAdapter` (обидва — не
     * наші файли), і та труба несе лише Boolean.
     *
     * Доки [setCadence] не проведено крізь неї, [NavCadence.STANDSTILL]
     * недосяжний ззовні цього модуля. Тут навмисно НЕМАЄ відображення
     * STANDSTILL → false: ambient-щабель сліпить класифікатор руху (2–3
     * зразки на вікно замість чотирьох), і телефон, який зупинився з
     * відкритою мапою, більше не зміг би помітити, що поїхав, — рівно той
     * баг, який ми лікуємо.
     */
    fun setNavigationGrade(enabled: Boolean) {
        setCadence(if (enabled) NavCadence.NAVIGATION else NavCadence.AMBIENT)
    }

    @SuppressLint("MissingPermission")
    private fun request() {
        val priority = if (cadence.highAccuracy) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val intervalMs = cadence.intervalMs
        val request = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis(cadence.fastestMs)
            // Never batch: a fix held back to save a wakeup arrives describing
            // a place the car has already left. 0 = deliver on arrival.
            .setMaxUpdateDelayMillis(0)
            .build()
        runCatching {
            fusedClient.removeLocationUpdates(callback)
            fusedClient.requestLocationUpdates(request, callback, context.mainLooper)
        }.onFailure { t ->
            Log.e(TAG, "requestLocationUpdates failed — permission missing or Play Services unavailable", t)
            _state.value = null
        }
    }

    fun stop() {
        running = false
        runCatching { fusedClient.removeLocationUpdates(callback) }
            .onFailure { Log.w(TAG, "removeLocationUpdates failed", it) }
        runCatching { locationManager?.removeUpdates(networkListener) }
            .onFailure { Log.w(TAG, "removeUpdates(network) failed", it) }
        runCatching { locationManager?.removeUpdates(passiveListener) }
            .onFailure { Log.w(TAG, "removeUpdates(passive) failed", it) }
        _state.value = null
        _networkState.value = null
        _satelliteTime.value = null
    }

    /** Elapsed-realtime now, in the same clock [GpsSample.elapsedMs] is stamped with. */
    fun nowElapsedMs(): Long = SystemClock.elapsedRealtime()

    private companion object {
        /** The network anchor is a spoof reference, not a navigation feed — slow is fine. */
        const val NET_ANCHOR_INTERVAL_MS = 4_000L

        /**
         * A clock only has to be checked as often as it can plausibly go wrong,
         * and this one goes wrong in a jump at boot, never in a creep. A minute
         * between passive deliveries is already far more often than needed.
         */
        const val SAT_TIME_INTERVAL_MS = 60_000L

        /** How long one satellite reading may still speak for «now». See [satelliteNowMs]. */
        const val SAT_TIME_FRESH_MS = 5L * 60L * 1000L

        /** Some cell-only network fixes report no accuracy; assume a coarse value
         *  rather than 0, so the divergence gate does not treat them as pinpoint. */
        const val NETWORK_ASSUMED_ACC_M = 500f
    }
}
