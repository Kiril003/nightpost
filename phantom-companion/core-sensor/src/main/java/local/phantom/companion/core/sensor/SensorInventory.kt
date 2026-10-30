package local.phantom.companion.core.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.util.Log

/**
 * HORIZON — the sensor roll call. One structured logcat block that settles,
 * permanently and per-device, the question «датчика немає чи ми його не
 * зареєстрували?»: a type listed as `ABSENT (hardware)` next to a full
 * inventory of everything the device DOES carry is proof of absence, not a
 * shrug. (The barometer case that prompted this: Samsung ships pressure
 * sensors on flagships only — an A-series phone reporting ABSENT is telling
 * the truth, and no manifest permission would change it; TYPE_PRESSURE at
 * navigation rates needs none.)
 *
 * Read it on-device with `adb logcat -s PhantomSensors`.
 */
object SensorInventory {

    private const val TAG = "PhantomSensors"

    /** The witnesses navigation actually calls on, by Android type constant. */
    private val NAV_SENSORS = linkedMapOf(
        Sensor.TYPE_PRESSURE to "PRESSURE (barometer)",
        Sensor.TYPE_ACCELEROMETER to "ACCELEROMETER",
        Sensor.TYPE_GYROSCOPE to "GYROSCOPE",
        Sensor.TYPE_ROTATION_VECTOR to "ROTATION_VECTOR",
        Sensor.TYPE_MAGNETIC_FIELD to "MAGNETIC_FIELD (compass)",
        Sensor.TYPE_STEP_COUNTER to "STEP_COUNTER",
    )

    /**
     * Log the inventory once and return type → present for the caller
     * (BlackBox note, telemetry). Cheap: one system-service list read.
     */
    fun log(context: Context): Map<Int, Boolean> {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        if (sm == null) {
            Log.e(TAG, "SensorManager unavailable — no inventory possible")
            return emptyMap()
        }
        val present = HashMap<Int, Boolean>(NAV_SENSORS.size)
        for ((type, label) in NAV_SENSORS) {
            val s = sm.getDefaultSensor(type)
            present[type] = s != null
            if (s != null) {
                Log.i(
                    TAG,
                    "$label: present — ${s.name} / ${s.vendor}, " +
                        "range=${s.maximumRange}, power=${s.power} mA",
                )
            } else {
                Log.i(TAG, "$label: ABSENT (hardware — the device does not carry one)")
            }
        }
        val all = sm.getSensorList(Sensor.TYPE_ALL)
        Log.i(TAG, "inventory: ${all.size} sensors total on this device")
        for (s in all) {
            Log.d(TAG, "  [type=${s.type}] ${s.name} / ${s.vendor}")
        }
        return present
    }

    /** Compact one-line summary for the flight recorder. */
    fun summarize(present: Map<Int, Boolean>): String =
        NAV_SENSORS.entries.joinToString(" ") { (type, label) ->
            val short = label.substringBefore(" ")
            if (present[type] == true) "$short=ok" else "$short=ABSENT"
        }
}
