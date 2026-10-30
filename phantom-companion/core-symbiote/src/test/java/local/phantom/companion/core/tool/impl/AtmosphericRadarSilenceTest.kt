package local.phantom.companion.core.tool.impl

import android.app.Application
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Погоду питає людина, а відповідає модель. Усе, що інструмент віддасть,
 * вийде з вуст ШІ з виглядом виміру — тож порожнеча мусить лишитись
 * порожнечею аж до слів, а не стати правдоподібним числом.
 */
class AtmosphericRadarSilenceTest {

    private fun ctx() = ToolContext(
        appContext = Application(),
        operatorId = "operator",
        requestId = "req_radar_silence",
        approvedPermissions = emptySet(),
    )

    private fun args() = buildJsonObject {
        put("lat", 50.450)
        put("lon", 30.520)
    }

    /**
     * Точний зліпок підстановки, яка стояла у ToolRegistryFactory на місці
     * мовчання Open-Meteo: 18°C, 0 мм/год, 12 км/год, 270°. Рушій робив із
     * них готовий бриф «небо ясне», і він доїжджав до людини як вимір.
     * Константи лишаються в тесті навіть якщо функцію перепишуть.
     */
    @Test
    fun standInReadingNeverReachesTheModel() = runTest {
        val tool = AtmosphericRadarTool(
            getRadar = { _, _ ->
                RadarResult(
                    conditionType = "CLEAR",
                    reflectivityDbz = 0,
                    temperatureC = 18.0,
                    windKmh = 12.0,
                    windDir = "Західний",
                    activeLayer = null,
                    brief = "Небо ясне/хмарність 0%, температура 18°C, вітер Західний 12 км/год.",
                )
            }
        )

        val result = tool.execute(args(), ctx()) {}

        val leaked = (result as? ToolResult.Ok)?.output
        assertNull("вигадане показання дійшло до моделі як факт: $leaked", leaked)
        assertTrue(result is ToolResult.Err)
        val err = result as ToolResult.Err
        assertEquals("radar_unsourced", err.code)
        assertTrue(err.message.contains("без часу виміру"))
    }

    /**
     * Напрямку не міряли — поля немає зовсім.
     *
     * До 13.09 сюди завжди приходив «Західний»: ToolRegistryFactory ставив на
     * місце напрямку сталу 270°, бо клієнт Open-Meteo не питав
     * `wind_direction_10m`. Румб зі стелі неможливо відрізнити від виміряного,
     * тож мовчання джерела мусить лишитись порожнім полем, а не рядком.
     */
    @Test
    fun unmeasuredWindDirectionIsAbsentNotGuessed() = runTest {
        val tool = AtmosphericRadarTool(
            getRadar = { _, _ ->
                RadarResult(
                    conditionType = "CLEAR",
                    reflectivityDbz = 0,
                    temperatureC = 18.0,
                    windKmh = 12.0,
                    windDir = null,
                    activeLayer = null,
                    brief = "Небо ясне/хмарність 0%, температура 18°C, вітер 12 км/год.",
                    observedAtMs = System.currentTimeMillis(),
                )
            }
        )

        val result = tool.execute(args(), ctx()) {}

        assertTrue(result is ToolResult.Ok)
        val ok = result as ToolResult.Ok
        assertNull(
            "невиміряний напрямок вітру доїхав до моделі полем",
            ok.structured?.get("wind_direction"),
        )
        assertTrue(
            "швидкість вітру мусить лишитись — мовчить лише напрямок",
            ok.structured?.get("wind_speed_kmh") != null,
        )
    }

    /** Джерело мовчить — відмова мусить сказати ЧОМУ, а не «тимчасово недоступні». */
    @Test
    fun silentSourceSaysWhyInsteadOfNothing() = runTest {
        val tool = AtmosphericRadarTool(getRadar = { _, _ -> null })

        val result = tool.execute(args(), ctx()) {}

        assertTrue(result is ToolResult.Err)
        val err = result as ToolResult.Err
        assertEquals("radar_unavailable", err.code)
        assertTrue("відмова не називає причини: ${err.message}", err.message.contains("не прийшло жодного виміру"))
        assertTrue("відмова не каже, що це означає: ${err.message}", err.message.contains("не називай чисел"))
    }
}
