package local.phantom.companion.core.sensor.sources

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * PHANTOM-EW — the barometer, the one sensor an adversary cannot reach.
 *
 * Emits raw pressure in hPa; the interpretation (altitude, «operator is three
 * floors up», the cross-examination of a forged GPS altitude) belongs to
 * [local.phantom.companion.core.sensor.nav.Altimeter].
 *
 * [available] is false on devices with no pressure sensor — a large share of
 * budget phones. The engine degrades honestly: no barometer means no vertical
 * context and one fewer witness against a spoof, never a fabricated altitude.
 */
class BarometerSource(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    /** False where the hardware simply does not exist. Say so; never invent a number. */
    val available: Boolean get() = sensor != null

    private val _hpa = MutableStateFlow<Float?>(null)
    val hpa: StateFlow<Float?> = _hpa.asStateFlow()

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            _hpa.value = event.values.firstOrNull()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        val s = sensor ?: return
        sensorManager.registerListener(listener, s, SAMPLING_PERIOD_US)
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
    }

    private companion object {
        /** ~5 Hz. A stairwell takes seconds; a lift takes tens. Nothing needs faster. */
        const val SAMPLING_PERIOD_US = 200_000
    }
}
