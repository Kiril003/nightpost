package local.phantom.companion.core.sensor.nav

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ПОКАЗАТИ СВІТОВІ ІНШУ ТОЧКУ — і при цьому самому знати правду.
 *
 * Замовлення власника 31.08. Сенс не в тому, щоб обдурити застосунок, а в
 * тому, щоб **позиція перестала бути даниною, яку пристрій платить кожному,
 * хто попросить**. Тут це можливо саме тому, що ФАНТОМ має власний рушій
 * позиції: система віддає стороннім те, що ми поклали, а наша навігація і
 * далі рахує справжній фікс — вони не поділяють джерела.
 *
 * **Механізм штатний, не злам.** Android має «застосунок фіктивної локації»:
 * людина вмикає розробницькі налаштування й обирає там ФАНТОМ. Без цього
 * кроку жоден виклик тут не спрацює — і це правильно: підміна позиції не
 * може вмикатися застосунком за людину.
 *
 * **Чого воно НЕ робить, і це має бути сказано вголос:**
 *  - Android ставить на такий фікс ознаку «фіктивний», і застосунок може її
 *    прочитати; банки й частина сервісів відмовлять. Ховати цю ознаку ми не
 *    будемо: підміняти СВОЮ локацію — право людини, ламати чужу перевірку —
 *    вже інше, і бʼє воно по ній же;
 *  - геолокація за IP не змінюється взагалі — сайт бачитиме справжню країну;
 *  - вежі й Wi-Fi навколо можуть суперечити поставленій точці.
 *
 * **Чому воно голосне.** Забути, що світ бачить неправду, небезпечніше, ніж
 * не мати цієї можливості: людина може повірити чужому застосунку, який
 * веде її з фальшивої точки. Тому стан живе в [state], а служба поверх
 * тримає сповіщення, яке не змахнути.
 */
object MockPosition {

    sealed interface State {
        /** Ніхто нічого не підміняє: світ бачить справжній фікс. */
        data object Off : State

        /** Світ бачить цю точку. [sinceMs] — коли ми почали її віддавати. */
        data class Showing(val lat: Double, val lon: Double, val sinceMs: Long) : State

        /**
         * Система не дозволила. Майже завжди причина одна: ФАНТОМ не обраний
         * у розробницьких налаштуваннях як застосунок фіктивної локації.
         */
        data class Refused(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Провайдери, які підміняємо. Одного `gps` мало: сучасні застосунки
     * питають злитий провайдер через Play Services, і той на API 31+ читає
     * саме `fused`. Мережевий — бо ним користуються ті, кому досить міста.
     */
    private val providers: List<String>
        get() = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        }

    /** Точність, яку показуємо світові. Не нуль: фікс без похибки сам собою підозрілий. */
    private const val SHOWN_ACCURACY_M = 8f

    @Volatile
    private var armed: Boolean = false

    /**
     * Почати віддавати світові [lat]/[lon].
     *
     * Ідемпотентне: повторний виклик з іншою точкою просто пересуває її, не
     * знімаючи й не перереєстровуючи провайдерів — інакше застосунки навколо
     * побачили б розрив і частина з них узагалі перестала б слухати.
     */
    fun show(context: Context, lat: Double, lon: Double): State {
        val lm = context.getSystemService(LocationManager::class.java)
            ?: return State.Refused("система не дала доступу до розташування").also { _state.value = it }

        try {
            if (!armed) {
                providers.forEach { register(lm, it) }
                armed = true
            }
            val now = System.currentTimeMillis()
            providers.forEach { push(lm, it, lat, lon, now) }
            val prev = _state.value
            _state.value = State.Showing(
                lat = lat,
                lon = lon,
                sinceMs = (prev as? State.Showing)?.sinceMs ?: now,
            )
        } catch (e: SecurityException) {
            // Найчастіша причина — ФАНТОМ не обраний застосунком фіктивної
            // локації. Кажемо це словами: «SecurityException» людині нічого
            // не пояснює, а один перемикач у розробницьких — пояснює все.
            armed = false
            _state.value = State.Refused(
                "система не дозволила: у розробницьких налаштуваннях телефона треба " +
                    "обрати Nightpost як застосунок фіктивної локації",
            )
            Log.w(TAG, "mock provider refused", e)
        } catch (e: IllegalArgumentException) {
            armed = false
            _state.value = State.Refused("цей телефон не приймає фіктивну локацію")
            Log.w(TAG, "mock provider rejected", e)
        }
        return _state.value
    }

    /** Повернути світові правду. Безпечно кликати, коли нічого не ввімкнено. */
    fun stop(context: Context) {
        val lm = context.getSystemService(LocationManager::class.java)
        providers.forEach { name ->
            runCatching { lm?.setTestProviderEnabled(name, false) }
            runCatching { lm?.removeTestProvider(name) }
        }
        armed = false
        _state.value = State.Off
    }

    /**
     * Чи бачить світ неправду ПРЯМО ЗАРАЗ. Питати треба саме це, а не «чи
     * вмикали»: система знімає тестові провайдери при перезавантаженні й
     * коли людина забирає дозвіл, і тоді наш прапорець збрехав би.
     */
    fun isShowingFalsePosition(): Boolean = _state.value is State.Showing

    private fun register(lm: LocationManager, name: String) {
        // Спершу прибрати залишок від попереднього сеансу: після падіння
        // процесу провайдер може лишитись зареєстрованим, і повторний
        // addTestProvider кине IllegalArgumentException на рівному місці.
        runCatching { lm.removeTestProvider(name) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            lm.addTestProvider(
                name,
                /* requiresNetwork = */ false,
                /* requiresSatellite = */ false,
                /* requiresCell = */ false,
                /* hasMonetaryCost = */ false,
                /* supportsAltitude = */ true,
                /* supportsSpeed = */ true,
                /* supportsBearing = */ true,
                ProviderProperties.POWER_USAGE_LOW,
                ProviderProperties.ACCURACY_FINE,
            )
        } else {
            @Suppress("DEPRECATION")
            lm.addTestProvider(
                name,
                false, false, false, false, true, true, true,
                /* powerRequirement = */ 1,
                /* accuracy = */ 1,
            )
        }
        lm.setTestProviderEnabled(name, true)
    }

    private fun push(lm: LocationManager, name: String, lat: Double, lon: Double, nowMs: Long) {
        val loc = Location(name).apply {
            latitude = lat
            longitude = lon
            accuracy = SHOWN_ACCURACY_M
            altitude = 0.0
            speed = 0f
            bearing = 0f
            time = nowMs
            // Без цього поля Android відкидає фікс мовчки, і підміна виглядає
            // як «не працює». Годинник саме монотонний, не стінний.
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                verticalAccuracyMeters = 3f
                speedAccuracyMetersPerSecond = 0.5f
                bearingAccuracyDegrees = 5f
            }
        }
        lm.setTestProviderLocation(name, loc)
    }

    private const val TAG = "MockPosition"
}
