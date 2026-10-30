package local.phantom.companion.core.geo.metro

import android.app.Application
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.constitution.AuditVerdict
import local.phantom.companion.core.constitution.AxiomAuditor
import local.phantom.companion.core.data.spatial.FocusTarget
import local.phantom.companion.core.data.spatial.LayerOrigin
import local.phantom.companion.core.data.spatial.SpatialLayer
import local.phantom.companion.core.data.spatial.SpatialLayerSink
import local.phantom.companion.core.data.spatial.SpatialLayerSpec
import local.phantom.companion.core.net.map.OverpassCenter
import local.phantom.companion.core.net.map.OverpassElement
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `metro.station` — адаптер, яким схема станції потрапляє в діалог.
 *
 * Перевіряється рівно те, що властиве адаптеру, а не логіці шару:
 *
 *  1. **Він узагалі зареєструється.** Реєстр інструментів мовчки викидає ті,
 *     чий маніфест не проходить конституційний аудит — інструмент із
 *     непройденим маніфестом не «зламався б», він просто ніколи не існував би,
 *     і жоден тест логіки цього не показав би.
 *  2. **Розрізнення відмов.** «Мережа мовчить» мусить бути `Err`, а «даних
 *     немає» — `Ok`: інакше модель піде перепитувати мережу замість того, щоб
 *     сказати оператору правду.
 *  3. **Координати.** Без них інструмент чесно просить координати, а не бере
 *     навмання центр міста.
 */
class MetroStationToolTest {

    private class FakeSink : SpatialLayerSink {
        val pushed = mutableListOf<SpatialLayerSpec>()
        override suspend fun push(spec: SpatialLayerSpec): String {
            require(spec.elementCount > 0) { "порожній шар класти не можна" }
            pushed += spec
            return "layer-1"
        }

        override suspend fun setVisible(layerId: String, visible: Boolean) = Unit
        override suspend fun clear(layerId: String): Boolean = false
        override suspend fun clearThread(threadId: String?, origin: LayerOrigin?): Int = 0
        override suspend fun list(threadId: String?): List<SpatialLayer> = emptyList()
        override suspend fun requestFocus(target: FocusTarget) = Unit
    }

    private val context = ToolContext(
        appContext = Application(),
        operatorId = "operator",
        requestId = "test",
        approvedPermissions = setOf(ToolPermission.NETWORK, ToolPermission.LOCATION),
    )

    /** Мінімальна станція: один вхід і платформа — досить, щоб шар був чим наповнити. */
    private val station: List<OverpassElement> = listOf(
        OverpassElement(
            id = 1, type = "node", lat = 50.4500, lon = 30.5230,
            tags = mapOf("railway" to "subway_entrance", "ref" to "1", "name" to "Хрещатик"),
        ),
        OverpassElement(
            id = 2, type = "way",
            geometry = listOf(
                OverpassCenter(50.4504, 30.5234),
                OverpassCenter(50.4505, 30.5234),
                OverpassCenter(50.4505, 30.5235),
                OverpassCenter(50.4504, 30.5235),
                OverpassCenter(50.4504, 30.5234),
            ),
            tags = mapOf("railway" to "platform", "level" to "-1", "name" to "Хрещатик"),
        ),
    )

    private fun tool(
        sink: SpatialLayerSink,
        elements: List<OverpassElement>?,
        location: (suspend () -> Pair<Double, Double>?)? = null,
    ) = MetroStationTool(
        layer = MetroStationLayer(sink, StationIndoorSource { elements }, { null }),
        currentLocation = location,
    )

    @Test
    fun `маніфест проходить конституційний аудит — інакше інструмента просто не буде`() {
        val verdict = AxiomAuditor().audit(MetroStationTool(FakeSink()).manifest)
        assertTrue(
            "інструмент із непройденим маніфестом реєстр викидає МОВЧКИ: $verdict",
            verdict is AuditVerdict.Passed,
        )
    }

    @Test
    fun `інструмент оголошує рівно ті дозволи, які справді потрібні`() {
        val t = MetroStationTool(FakeSink())
        assertEquals("ідентифікатор стабільний — за ним його кличе модель", "metro.station", t.id)
        assertEquals(
            "мережа для Overpass і локація для «найближчого входу» — і нічого зайвого",
            setOf(ToolPermission.NETWORK, ToolPermission.LOCATION), t.permissions,
        )
        assertTrue(
            "опис мусить прямо забороняти вигадувати входи — це те, що читає модель",
            t.description.contains("не вигадуй"),
        )
    }

    @Test
    fun `виклик із координатами кладе шар і повертає розібраний результат`() = runTest {
        val sink = FakeSink()
        val res = tool(sink, station).execute(
            buildJsonObject {
                put("lat", JsonPrimitive(50.4500))
                put("lon", JsonPrimitive(30.5230))
            },
            context,
        ) {}

        assertTrue("станція знайшлась — це Ok", res is ToolResult.Ok)
        res as ToolResult.Ok
        assertEquals("шар покладено рівно один", 1, sink.pushed.size)
        assertEquals("шар метро", "metro", sink.pushed.single().kind)

        val s = res.structured!!
        assertEquals("ідентифікатор шару повертається моделі", "layer-1", s["layer_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("один вхід у фікстурі", 1, s["entrances"]?.jsonPrimitive?.intOrNull)
        assertTrue("атрибуція джерела їде разом із результатом", s.containsKey("attribution"))
    }

    @Test
    fun `без координат і без геолокації інструмент просить координати`() = runTest {
        val res = tool(FakeSink(), station).execute(buildJsonObject {}, context) {}

        assertTrue("немає з чого рахувати — це Err", res is ToolResult.Err)
        assertEquals("код відмови машинно-читаний", "no_location", (res as ToolResult.Err).code)
    }

    @Test
    fun `без координат бере позицію оператора`() = runTest {
        val sink = FakeSink()
        val res = tool(sink, station, location = { 50.4500 to 30.5230 })
            .execute(buildJsonObject {}, context) {}

        assertTrue("позиції оператора достатньо", res is ToolResult.Ok)
        assertEquals("шар усе одно кладеться", 1, sink.pushed.size)
    }

    @Test
    fun `мовчання мережі — це Err, а порожні дані — Ok`() = runTest {
        val args = buildJsonObject {
            put("lat", JsonPrimitive(50.4500))
            put("lon", JsonPrimitive(30.5230))
        }

        val offline = tool(FakeSink(), elements = null).execute(args, context) {}
        assertTrue("мережа мовчить — це збій", offline is ToolResult.Err)
        assertEquals("geo_unavailable", (offline as ToolResult.Err).code)

        val empty = tool(FakeSink(), elements = emptyList()).execute(args, context) {}
        assertTrue(
            "порожній OSM — це відповідь, а не збій: інакше модель піде довбати мережу",
            empty is ToolResult.Ok,
        )
        assertEquals("і вона чесно каже, що входів нуль", 0, (empty as ToolResult.Ok).structured!!["entrances"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun `безбарʼєрний режим передається у шар`() = runTest {
        val sink = FakeSink()
        val res = tool(sink, station).execute(
            buildJsonObject {
                put("lat", JsonPrimitive(50.4500))
                put("lon", JsonPrimitive(30.5230))
                put("wheelchair", JsonPrimitive(true))
            },
            context,
        ) {} as ToolResult.Ok

        // На цій станції немає жодного вертикального звʼязку, тож безбарʼєрного
        // маршруту не існує — і результат мусить це визнати, а не промовчати.
        assertEquals(
            "безбарʼєрного маршруту тут немає, і про це сказано",
            false,
            res.structured!!["wheelchair_route"]?.jsonPrimitive?.booleanOrNull,
        )
    }
}
