package local.phantom.companion.feature.precision

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import local.phantom.companion.core.indoor.pdr.ContinuityState
import local.phantom.companion.core.indoor.pdr.PedestrianContinuity
import local.phantom.companion.core.indoor.pdr.PedestrianSensors
import local.phantom.companion.core.indoor.pdr.PedestrianSnapshot

/**
 * Живий потік пішохідної автономії — єдиний дотик цієї підсистеми до заліза.
 *
 * Це третій сенсорний шов модуля (після `RawGnssSource` і [LiveMotionSource]) і
 * той самий підхід: платформа перекладається в прості числа, а вся математика
 * лишається в чистому [PedestrianContinuity] з `:core-indoor`, який повністю
 * перевіряється на JVM. Тут немає жодного рішення — ні порогів, ні фільтрів, ні
 * вердиктів; лише реєстрація, переклад і зняття.
 *
 * **Навіщо це на екрані чесної позиції.** Обіцянка «супутників немає — веду за
 * численням шляху» досі була написана в тексті вердикту, але ніхто її не
 * виконував: `:core-indoor` не був підключений ЖОДНИМ файлом застосунку.
 * Це джерело робить її правдою — і, що важливіше, робить видимою: людина бачить,
 * скільки саме автономії в неї лишилось, ще ДО того, як почнеться глушіння.
 *
 * **Чому без жодного рантайм-дозволу.** Акселерометр, гіроскоп, магнітометр і
 * барометр не належать до «небезпечних» датчиків Android — на відміну від сирих
 * GNSS і від Wi-Fi RTT, який потребує `NEARBY_WIFI_DEVICES`. Тому автономний хід
 * працює навіть тоді, коли людина відмовила застосунку в усьому іншому. Єдина
 * умова — наявність самого заліза, і вона перевіряється чесно: те, чого пристрій
 * не має, не зʼявляється на екрані ні як «0», ні як «—», а називається своїм
 * імʼям.
 *
 * **Чому окремий потік, а не головний.** Тут одночасно живуть пʼять
 * підписок, найшвидша з них — 50 Гц. Обробка одного семпла коштує десятки
 * операцій, але вона йде на кожному семплі й поруч із малюванням карти. Власний
 * `HandlerThread` знімає це з головного циклу і, що важливіше, робить
 * ОДНОПОТОКОВІСТЬ [PedestrianContinuity] явною гарантією, а не збігом обставин:
 * усі колбеки приходять на один і той самий looper.
 *
 * Слухачі знімаються, а потік завершується разом зі скасуванням — датчики не
 * працюють, коли екран не дивляться.
 */
class LivePedestrianSource(context: Context) {

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /**
     * Інвентар заліза, зчитаний один раз. Саме він, а не «чи прийшли семпли»,
     * відрізняє «магнітометра немає» (назавжди) від «магнітометр мовчить» (на
     * секунди) — і для людини це різні підписи.
     */
    val sensors: PedestrianSensors = PedestrianSensors(
        accelerometer = has(Sensor.TYPE_ACCELEROMETER),
        gyroscope = has(Sensor.TYPE_GYROSCOPE),
        rotationVector = has(Sensor.TYPE_ROTATION_VECTOR),
        magnetometer = has(Sensor.TYPE_MAGNETIC_FIELD),
        barometer = has(Sensor.TYPE_PRESSURE),
    )

    /**
     * Довірений супутниковий розвʼязок, який ще не застосовано.
     *
     * Приходить із зовсім іншого колбека (слухач `LocationManager` у
     * [LivePrecisionSource]), тож живе на рівні екземпляра, як і буфер швидкостей
     * у [LiveMotionSource]. `@Volatile` тут не оптимізм, а необхідність:
     * запис і читання відбуваються на різних потоках, і без нього привʼязка
     * могла б не дійти до сенсорного циклу зовсім.
     */
    @Volatile
    private var pendingFix: TrustedFix? = null

    /**
     * Прийняти супутниковий розвʼязок, якому МОЖНА вірити.
     *
     * Рішення про довіру ухвалює той, хто бачить цілісність сигналу
     * ([PrecisionUiState.trustedForAnchor]), а не це джерело: привʼязати числення
     * до підміненої координати було б найгіршим з можливих наслідків — брехня
     * пережила б саму атаку й тягнулася б далі як «власне числення».
     *
     * @param bearingRad курс руху, рад; лише він дає спостерегти, як саме людина
     *   несе телефон
     */
    fun offerTrustedFix(accuracyM: Double, bearingRad: Double?, speedMps: Double?) {
        if (!accuracyM.isFinite() || accuracyM < 0.0) return
        pendingFix = TrustedFix(accuracyM, bearingRad, speedMps)
    }

    /**
     * Потік знімків автономного ходу, приблизно двічі на секунду.
     *
     * Частота публікації навмисне НЕ дорівнює частоті датчиків: числення
     * оновлюється на кожному з 50 семплів за секунду, а перемальовувати екран
     * 50 разів на секунду немає жодного сенсу — крок людини триває пів секунди.
     */
    fun snapshots(): Flow<PedestrianSnapshot> = callbackFlow {
        val manager = sensorManager
        val accelerometer = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (manager == null || accelerometer == null) {
            // Рахувати кроки нічим. Чесно кажемо це один раз і закриваємось —
            // краще порожній екран із причиною, ніж нулі, які виглядають даними.
            trySend(PedestrianContinuity(sensors).snapshot(0L))
            close()
            return@callbackFlow
        }

        // Стан живе всередині підписки: два перегляди екрана — два незалежні
        // числення, жодного спільного стану між ними.
        val continuity = PedestrianContinuity(sensors)
        var lastPublishMs = 0L

        val thread = HandlerThread("phantom-pdr").apply { start() }
        val handler = Handler(thread.looper)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // event.timestamp — наносекунди монотонного годинника, той самий
                // домен для всіх пʼяти датчиків; чисті класи самі переживають
                // розриви й стрибки в ньому.
                val t = event.timestamp / 1_000_000L
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        applyPendingFix(continuity, t)
                        continuity.onAccelerometer(
                            t,
                            event.values[0].toDouble(),
                            event.values[1].toDouble(),
                            event.values[2].toDouble(),
                        )
                        if (t - lastPublishMs >= PUBLISH_INTERVAL_MS) {
                            lastPublishMs = t
                            trySend(continuity.snapshot(t))
                        }
                    }

                    Sensor.TYPE_GYROSCOPE -> continuity.onGyroscope(
                        t,
                        event.values[0].toDouble(),
                        event.values[1].toDouble(),
                        event.values[2].toDouble(),
                    )

                    Sensor.TYPE_ROTATION_VECTOR -> azimuthOf(event)?.let { continuity.onAzimuth(t, it) }

                    Sensor.TYPE_MAGNETIC_FIELD -> continuity.onMagneticField(
                        t,
                        event.values[0].toDouble(),
                        event.values[1].toDouble(),
                        event.values[2].toDouble(),
                    )

                    Sensor.TYPE_PRESSURE -> continuity.onPressure(t, event.values[0].toDouble())
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        manager.registerListener(listener, accelerometer, ACCEL_PERIOD_US, handler)
        register(manager, listener, handler, Sensor.TYPE_GYROSCOPE, GYRO_PERIOD_US)
        register(manager, listener, handler, Sensor.TYPE_ROTATION_VECTOR, HEADING_PERIOD_US)
        register(manager, listener, handler, Sensor.TYPE_MAGNETIC_FIELD, HEADING_PERIOD_US)
        register(manager, listener, handler, Sensor.TYPE_PRESSURE, PRESSURE_PERIOD_US)

        // Початковий знімок, щоб картка не блимала порожнечею до першого семпла.
        trySend(continuity.snapshot(0L))

        awaitClose {
            manager.unregisterListener(listener)
            thread.quitSafely()
        }
    }

    /**
     * Застосувати привʼязку саме тут, на сенсорному потоці, а не там, де вона
     * прийшла: [PedestrianContinuity] має рівно одного власника, і всі його
     * зміни зобовʼязані відбуватись у одному потоці.
     */
    private fun applyPendingFix(continuity: PedestrianContinuity, elapsedMs: Long) {
        val fix = pendingFix ?: return
        pendingFix = null
        continuity.onTrustedFix(
            elapsedMs = elapsedMs,
            accuracyM = fix.accuracyM,
            bearingRad = fix.bearingRad,
            speedMps = fix.speedMps,
        )
    }

    private fun register(
        manager: SensorManager,
        listener: SensorEventListener,
        handler: Handler,
        type: Int,
        periodUs: Int,
    ) {
        val sensor = manager.getDefaultSensor(type) ?: return
        manager.registerListener(listener, sensor, periodUs, handler)
    }

    private fun has(type: Int): Boolean = sensorManager?.getDefaultSensor(type) != null

    private class TrustedFix(
        val accuracyM: Double,
        val bearingRad: Double?,
        val speedMps: Double?,
    )

    private companion object {

        /**
         * Азимут пристрою з вектора обертання, рад від півночі за годинниковою.
         *
         * @return `null`, якщо платформа віддала вектор, який сама ж не приймає.
         *   Частина прошивок Samsung кладе в `TYPE_ROTATION_VECTOR` пʼять
         *   компонент замість чотирьох, і `getRotationMatrixFromVector` на такому
         *   векторі кидає виняток — тому беремо рівно перші чотири.
         */
        fun azimuthOf(event: SensorEvent): Double? {
            val values = event.values
            if (values.size < 4) return null
            val quaternion = FloatArray(4) { values[it] }
            val matrix = FloatArray(9)
            val orientation = FloatArray(3)
            return try {
                SensorManager.getRotationMatrixFromVector(matrix, quaternion)
                SensorManager.getOrientation(matrix, orientation)
                val azimuth = orientation[0].toDouble()
                if (azimuth.isFinite()) azimuth else null
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        /**
         * 50 Гц. Це не «про всяк випадок»: детектор кроків оцінює довжину кроку
         * з каденсу, каденс — з інтервалу між піками прискорення, а роздільність
         * цього інтервалу дорівнює періоду вибірки. На 20 Гц пів кроку
         * розмиваються на ±50 мс, що вже дає кілька відсотків помилки шляху на
         * кожній сотні метрів.
         */
        const val ACCEL_PERIOD_US = 20_000

        /** 20 Гц: людина не повертається швидше, ніж кілька радіан за секунду. */
        const val GYRO_PERIOD_US = 50_000

        /** 5 Гц: курс — повільна величина, а магнітометр із компасом гріють помітно. */
        const val HEADING_PERIOD_US = 200_000

        /** 2 Гц: барометр згладжується сталою в 1.5 с, частіше просто нічого не дає. */
        const val PRESSURE_PERIOD_US = 500_000

        /** Крок людини триває пів секунди — частіше оновлювати екран немає сенсу. */
        const val PUBLISH_INTERVAL_MS = 500L
    }
}

/** Хід зараз реально несе позицію, а не просто рахує кроки. */
internal val PedestrianSnapshot.carriesPosition: Boolean
    get() = anchoredToWorld && (state == ContinuityState.HOLDING || state == ContinuityState.STILL)
