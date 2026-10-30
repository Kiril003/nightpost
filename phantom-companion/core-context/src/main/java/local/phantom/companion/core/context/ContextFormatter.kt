package local.phantom.companion.core.context

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ContextFormatter {
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /**
     * An unread battery is «невідомо», never 0%. `?: 0` told the model the
     * phone was flat whenever the level had not arrived yet — and a companion
     * that believes that starts saying so.
     */
    fun battery(level: Int?): String = level?.let { "$it%" } ?: "невідомо"

    const val CONTEXT_TEMPLATE = """
ДАНІ СИСТЕМИ (твій поточний контекст):
- Час: %s
- Заряд: %s (%s)
- Активний додаток: %s
- Стан: %s
    """

    fun formatForAi(input: ContextInput, state: SystemState): String {
        val timeStr = timeFormat.format(Date(input.nowMs))
        val chargingStr = if (input.isCharging) "заряджається" else "розряджається"
        val appStr = input.activeAppPackage ?: "робочий стіл / невідомо"

        return CONTEXT_TEMPLATE.format(
            timeStr,
            battery(input.batteryLevel),
            chargingStr,
            appStr,
            state.name
        )
    }

    fun formatForAi(frame: ContextFrame): String {
        val timeStr = timeFormat.format(Date(frame.timestampMs))
        val whoStr = frame.who.profileId?.let { "id=$it" + (frame.who.activeRole?.let { ", роль=$it" } ?: "") } ?: "невідомо"
        val whereStr = frame.where.placeLabel + (if (frame.where.speed != null) " (швидкість: ${frame.where.speed} m/s)" else "")
        val whatStr = (frame.what.activeAppPackage ?: "робочий стіл / невідомо") + 
            (if (frame.what.detectedSounds.isNotEmpty()) ", звуки: ${frame.what.detectedSounds.joinToString()}" else "")
        
        val feelStr = listOfNotNull(
            frame.feel.emotionLabel?.let { "емоція: $it" },
            frame.feel.stressLevel?.let { "стрес: ${(it * 100).toInt()}%" },
            frame.feel.cognitiveLoad?.let { "когнітивне навантаження: ${(it * 100).toInt()}%" },
            frame.feel.breathingRate?.let { "дихання: $it/хв" }
        ).joinToString().ifEmpty { "немає даних" }

        val oweStr = "${frame.owe.commitmentsCount} активних зобовʼязань" + 
            (frame.owe.nextAlarmMs?.let { ", наступний будильник: " + timeFormat.format(Date(it)) } ?: "")

        val deviceStr = battery(frame.device.batteryLevel) + " " + 
            (if (frame.device.isCharging) "(заряджається)" else "(розряджається)") +
            (frame.device.storageAvailableBytes?.let { ", вільно памʼяті: ${it / 1024 / 1024}MB" } ?: "")

        val meshStr = if (frame.mesh.isDesktopConnected) "ПК на звʼязку (пінг: ${frame.mesh.latencyMs}мс)" else "ПК поза мережею"

        return """
            ДАНІ СИСТЕМИ (твій поточний контекст):
            - Час: $timeStr
            - Стан: ${frame.state}
            - Користувач (WHO): $whoStr
            - Локація (WHERE): $whereStr
            - Активність (WHAT): $whatStr
            - Самопочуття (FEEL): $feelStr
            - Зобовʼязання (OWE): $oweStr
            - Пристрій (DEVICE): $deviceStr
            - Звʼязок (MESH): $meshStr
        """.trimIndent()
    }
}
