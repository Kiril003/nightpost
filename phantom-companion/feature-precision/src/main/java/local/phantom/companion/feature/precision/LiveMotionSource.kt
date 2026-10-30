package local.phantom.companion.feature.precision

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import local.phantom.companion.core.mobility.HeuristicModeClassifier
import local.phantom.companion.core.mobility.ModeVerdict
import local.phantom.companion.core.mobility.MotionFeatures
import local.phantom.companion.core.mobility.MotionSample
import local.phantom.companion.core.mobility.MotionWindower

/**
 * Живий потік режиму пересування з акселерометра телефона.
 *
 * Це другий — після [RawGnssSource] — і останній дотик модуля до заліза: він читає
 * `TYPE_ACCELEROMETER`, зшиває події у [MotionSample], а далі вся робота йде в
 * чистому `:core-mobility` ([MotionWindower] → [MotionFeatures] →
 * [HeuristicModeClassifier]), який повністю перевіряється на JVM.
 *
 * **Чому лише акселерометр.** Евристичний класифікатор не звертається до жодної
 * ознаки гіроскопа — режим руху цілком читається з модуля прискорення, його
 * спектра й вертикальної складової. Тому джерело не реєструє гіроскоп: менше
 * сенсорів — менше витрати батареї і жодної проблеми синхронізації двох потоків.
 *
 * **Чому без дозволу.** Акселерометр не є «небезпечним» сенсором Android і не
 * потребує рантайм-дозволу — на відміну від сирих GNSS. Тому єдина умова роботи
 * — наявність сенсора на пристрої ([hasAccelerometer]).
 *
 * Тримає рівно одну реєстрацію слухача, знятого разом зі скасуванням потоку:
 * сенсор не працює, коли екран не дивляться.
 */
class LiveMotionSource(context: Context) {

    private val appContext = context.applicationContext
    private val sensors = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /** Чи є на пристрої акселерометр — без нього режим руху визначити нічим. */
    val hasAccelerometer: Boolean
        get() = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null

    // Останні кілька секунд швидкостей із зовнішнього джерела (GNSS). Класифікатор
    // без швидкості свідомо занижує впевненість і не розрізняє «авто / громадський
    // / нерухомо в плавному вагоні»; із нею — розрізняє. Буфер живе на РІВНІ
    // ЕКЗЕМПЛЯРА (не всередині потоку), бо швидкості надходять з іншого колбека
    // (GPS-слухач у LivePrecisionSource), ніж сенсорні події.
    private val speedLock = Any()
    private val speedBuffer = ArrayDeque<TimedSpeed>()

    /**
     * Прийняти одну швидкість, м/с (звичайно з `Location.speed`). Час береться за
     * стінним годинником самого джерела, а не з сенсорних міток — так вікно
     * швидкостей не залежить від того, у якому годинниковому домені працює
     * акселерометр конкретного пристрою. Відʼємні й нескінченні значення відкидаються.
     */
    fun offerSpeed(speedMps: Double) {
        if (!speedMps.isFinite() || speedMps < 0.0) return
        val now = System.currentTimeMillis()
        synchronized(speedLock) {
            speedBuffer.addLast(TimedSpeed(now, speedMps))
            val cutoff = now - SPEED_RETENTION_MS
            while (speedBuffer.isNotEmpty() && speedBuffer.first().atMs < cutoff) speedBuffer.removeFirst()
        }
    }

    /** Швидкості останнього вікна (≈ретенція буфера) як масив для ознак. */
    private fun recentSpeeds(): DoubleArray = synchronized(speedLock) {
        val now = System.currentTimeMillis()
        val cutoff = now - SPEED_RETENTION_MS
        speedBuffer.asSequence().filter { it.atMs >= cutoff }.map { it.speedMps }.toList().toDoubleArray()
    }

    private class TimedSpeed(val atMs: Long, val speedMps: Double)

    /**
     * Потік вердиктів режиму — по одному на кожне завершене 18-секундне вікно
     * (новий приблизно кожні 9 с завдяки 50 % перекриттю). Перший зʼявиться не
     * раніше, ніж набереться повне вікно: режим руху принципово не можна чесно
     * оголосити за півсекунди спостереження.
     */
    fun verdicts(): Flow<ModeVerdict> = callbackFlow {
        val manager = sensors
        val accel = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (manager == null || accel == null) {
            // Немає сенсора — чесно закриваємо потік, режим лишиться UNKNOWN.
            close()
            return@callbackFlow
        }

        // Стан класифікації живе всередині підписки: два перегляди екрана — два
        // незалежні вікна, жодного спільного стану між ними.
        val windower = MotionWindower()
        val classifier = HeuristicModeClassifier()

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
                val sample = MotionSample(
                    // event.timestamp — наносекунди монотонного годинника; вікнувач
                    // сам скине сітку, якщо годинник десь піде назад.
                    elapsedMs = event.timestamp / 1_000_000L,
                    ax = event.values[0].toDouble(),
                    ay = event.values[1].toDouble(),
                    az = event.values[2].toDouble(),
                )
                for (window in windower.offer(sample)) {
                    val features = MotionFeatures.extract(window, recentSpeeds())
                    trySend(classifier.classify(features, window.endElapsedMs))
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        manager.registerListener(listener, accel, SAMPLING_PERIOD_US)
        awaitClose { manager.unregisterListener(listener) }
    }

    private companion object {
        /**
         * Період вибірки, мкс. 50 000 ≈ 20 Гц — з запасом годує 10-Гц сітку
         * [MotionWindower] і не гріє батарею, як 100 Гц. Це лише побажання для
         * Android; нерівність кроку прибирає інтерполяція у вікнувачі.
         */
        const val SAMPLING_PERIOD_US = 50_000

        /**
         * Скільки останніх секунд швидкості тримати, мс. Трохи більше за вікно
         * (18 с), щоб на момент завершення вікна в буфері був повний його період.
         */
        const val SPEED_RETENTION_MS = 20_000L
    }
}
