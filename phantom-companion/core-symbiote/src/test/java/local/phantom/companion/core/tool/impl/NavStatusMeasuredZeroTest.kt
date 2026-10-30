package local.phantom.companion.core.tool.impl

import android.app.Application
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Друга половина контракту, і без неї перша стала б новим мовчанням.
 *
 * Заборона стосується ВИГАДАНОГО нуля, а не нуля взагалі: якщо скан справді
 * відбувся і не знайшов нічого, це результат, і він мусить доїхати числом.
 *
 * Живе окремим файлом навмисно. Він називає поля, яких у старому коді не
 * існує, тож проти нього не збереться — а тест, що не збирається, не червоніє
 * і нічого не доводить. Сусідній [NavStatusThreatSilenceTest] збудований на
 * значеннях за замовчуванням саме тому, що ЙОГО треба вміти прогнати проти
 * старого коду. Це сторож нового контракту, не доказ старого дефекту.
 */
class NavStatusMeasuredZeroTest {

    private fun ctx() = ToolContext(
        appContext = Application(),
        operatorId = "operator",
        requestId = "req_nav_measured_zero",
        approvedPermissions = emptySet(),
    )

    /**
     * Друга половина контракту, і без неї перша перетворилася б на нове
     * мовчання: справжній вимір мусить лишатись видимим. Заборона стосується
     * вигаданого нуля, а не нуля взагалі.
     */
    @Test
    fun measuredZeroStillReachesTheModelAsANumber() = runTest {
        val tool = NavStatusTool(
            telemetryProvider = {
                NavTelemetrySnapshot(isGuiding = true, activeThreatsCount = 0, curfewChecked = true)
            },
        )

        val ok = tool.execute(buildJsonObject {}, ctx()) {} as ToolResult.Ok

        assertEquals(
            "порахований нуль — це результат, і він мусить доїхати числом",
            0,
            ok.structured?.get("threats_count")?.jsonPrimitive?.content?.toInt(),
        )
        assertNull(
            "коли скан був, статусу «не перевірено» бути не може",
            ok.structured?.get("threats_status"),
        )
        assertTrue(
            "і слова мусять відрізнятись від випадку невідомості. Було: ${ok.output}",
            ok.output.contains("не зафіксовано"),
        )
    }
}
