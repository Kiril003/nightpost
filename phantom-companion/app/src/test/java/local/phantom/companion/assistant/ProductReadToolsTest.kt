package local.phantom.companion.assistant

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.Route
import local.phantom.companion.core.ai.context.MapViewportSnapshot
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.ui.messenger.ToolRounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductReadToolsTest {

    private val ctx = ToolContext(
        appContext = ContextWrapper(null),
        operatorId = "op",
        requestId = "req",
        approvedPermissions = emptySet(),
    )

    @Test
    fun `null sources return honest unavailable Err`() = runBlocking {
        val toolset = ProductToolset(emptyList(), ProductSources())
        for (id in listOf("day.today", "circles.list", "where.people", "wallet.balance", "map.viewport")) {
            val res = toolset.get(id)!!.execute(JsonObject(emptyMap()), ctx) {}
            assertTrue("$id expected Err, got $res", res is ToolResult.Err)
            assertEquals("unavailable", (res as ToolResult.Err).code)
        }
    }

    @Test
    fun `each A6 read tool stays within 2048 UTF-8 bytes even with 500 rows`() = runBlocking {
        val dayJson = buildJsonObject {
            put("now_ms", 1700000000000L)
            put("calendar", "granted")
            put(
                "items",
                buildJsonArray {
                    repeat(500) { idx ->
                        add(
                            buildJsonObject {
                                put("id", "item-$idx")
                                put("kind", "reminder")
                                put("title", "Task number $idx with extra text to grow the payload size")
                                put("at_ms", 1700000000000L + idx * 60_000L)
                                put("end_ms", 1700000060000L + idx * 60_000L)
                                put("all_day", false)
                                put("peer_id", "peer-$idx")
                                put("overdue", idx % 2 == 0)
                            },
                        )
                    }
                },
            )
        }
        val circles = (1..500).map { CircleRow(name = "Person $it", circle = "close") }
        val where = (1..500).map { WhereRow(name = "Friend $it", level = "district", ageMin = 4L) }
        val wallet = WalletRow(
            shortAddress = "7xKX…9bQp",
            balances = (1..500).map { "COIN$it" to "$it.123456" },
            cluster = "mainnet",
        )
        val viewport = MapViewportSnapshot(
            centerLat = 50.4501,
            centerLon = 30.5234,
            zoom = 14.0,
            locality = "Center ".repeat(200),
            visiblePoiCount = 42,
            activeLayerTitles = listOf("cafes", "transit"),
        )
        val sources = ProductSources(
            day = { dayJson },
            circles = { circles },
            where = { where },
            wallet = { wallet },
            viewport = { viewport },
            english = { true },
        )
        val toolset = ProductToolset(emptyList(), sources)

        for (id in listOf("day.today", "circles.list", "where.people", "wallet.balance", "map.viewport")) {
            val res = toolset.get(id)!!.execute(JsonObject(emptyMap()), ctx) {} as ToolResult.Ok
            val bytes = res.output.toByteArray(Charsets.UTF_8).size
            assertTrue("$id output is $bytes bytes (> 2048)", bytes <= 2048)
            val parsed = Json.parseToJsonElement(res.output).jsonObject
            assertEquals(parsed, res.structured)
        }

        val dayOut = (toolset.get("day.today")!!.execute(JsonObject(emptyMap()), ctx) {} as ToolResult.Ok).structured!!
        assertTrue(dayOut["items"]!!.jsonArray.size <= 20)
        assertTrue(dayOut["more"]!!.jsonPrimitive.int >= 480)
    }

    @Test
    fun `where people exposes names and level only without coordinates`() = runBlocking {
        val sources = ProductSources(
            where = {
                listOf(
                    WhereRow(name = "Olena", level = "exact", ageMin = 2L),
                    WhereRow(name = "Taras", level = "district", ageMin = null),
                )
            },
        )
        val tool = ProductToolset(emptyList(), sources).get("where.people")!!
        val res = tool.execute(JsonObject(emptyMap()), ctx) {} as ToolResult.Ok

        assertFalse("lat in output: ${res.output}", "lat" in res.output)
        assertFalse("lon in output: ${res.output}", "lon" in res.output)
        assertFalse(Regex("""\d+\.\d+""").containsMatchIn(res.output))
        val first = res.structured!!["people"]!!.jsonArray.first().jsonObject
        assertEquals("Olena", first["name"]!!.jsonPrimitive.content)
        assertEquals("exact", first["level"]!!.jsonPrimitive.content)
    }

    @Test
    fun `wallet balance never contains a full-length address`() = runBlocking {
        val fullAddress = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        val sources = ProductSources(
            wallet = {
                WalletRow(
                    shortAddress = fullAddress,
                    balances = listOf("USDC" to "12.5", "SOL" to "0.8"),
                    cluster = "mainnet",
                )
            },
        )
        val tool = ProductToolset(emptyList(), sources).get("wallet.balance")!!
        val res = tool.execute(JsonObject(emptyMap()), ctx) {} as ToolResult.Ok

        assertFalse("Full address leaked in ${res.output}", fullAddress in res.output)
        assertFalse(Regex("""[1-9A-HJ-NP-Za-km-z]{32,44}""").containsMatchIn(res.output))
        assertEquals("EPjF…Dt1v", res.structured!!["address"]!!.jsonPrimitive.content)
    }

    @Test
    fun `what is on today calls day today exactly once via ToolRounds`() = runBlocking {
        var dayCalls = 0
        val sources = ProductSources(
            day = {
                dayCalls++
                buildJsonObject {
                    put("now_ms", 1700000000000L)
                    put(
                        "items",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("kind", "reminder")
                                    put("title", "Call Anna")
                                    put("at_ms", 1700003600000L)
                                    put("overdue", false)
                                },
                            )
                        },
                    )
                }
            },
        )
        val toolset = ProductToolset(emptyList(), sources)
        var passCount = 0
        val rounds = ToolRounds(
            pass = { _ ->
                passCount++
                if (passCount == 1) {
                    ToolRounds.Pass(
                        text = "",
                        calls = listOf(
                            AIChunk.ToolCall(
                                name = "day.today",
                                arguments = "{}",
                                callId = "c1",
                                route = Route.CLOUD_GEMINI,
                            ),
                        ),
                        error = null,
                        route = Route.CLOUD_GEMINI,
                    )
                } else {
                    ToolRounds.Pass(
                        text = "You have: Call Anna.",
                        calls = emptyList(),
                        error = null,
                        route = Route.CLOUD_GEMINI,
                    )
                }
            },
            run = { _, calls ->
                calls.map { call ->
                    val tool = toolset.get(call.name)!!
                    when (val r = tool.execute(JsonObject(emptyMap()), ctx) {}) {
                        is ToolResult.Ok -> r.output
                        is ToolResult.Err -> r.message
                        else -> "err"
                    }
                }
            },
            voice = null,
        )

        val outcome = rounds.answer(AIIntent.Text(prompt = "what's on today"))
        assertEquals(1, dayCalls)
        assertEquals(ToolRounds.Outcome.Answer("You have: Call Anna."), outcome)
    }
}
