package local.phantom.companion.feature.godmode.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppActionParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses click target by text`() {
        // Use a non-alias label so the parser does NOT auto-coerce
        // click → OpenApp (commit 4f750e9). «Зберегти» is not in
        // AppActionAliases.KNOWN so it stays a Click.
        val out = AppActionParser.parseSequence(
            json.parseToJsonElement(
                """{"type":"app-action","actions":[{"action":"click","target":{"text":"Зберегти"}}]}""",
            ).jsonObject,
        )
        assertEquals(1, out.size)
        val click = out.first() as AppAction.Click
        assertEquals("Зберегти", click.target.text)
    }

    @Test
    fun `click on known app alias coerces to OpenApp`() {
        // Auto-coerce introduced 4f750e9 — «click target.text=Налаштування»
        // now resolves to OpenApp(query="налаштування"). The downstream
        // dispatcher resolves the query through AppActionAliases.KNOWN.
        val out = AppActionParser.parseSequence(
            json.parseToJsonElement(
                """{"type":"app-action","actions":[{"action":"click","target":{"text":"Налаштування"}}]}""",
            ).jsonObject,
        )
        assertEquals(1, out.size)
        val open = out.first() as AppAction.OpenApp
        assertEquals("налаштування", open.query.lowercase())
    }

    @Test
    fun `parses click target by id and bounds`() {
        val out = AppActionParser.parseSequence(
            json.parseToJsonElement(
                """{"type":"app-action","actions":[
                       {"action":"click","target":{"id":"com.x:id/btn"}},
                       {"action":"click","target":{"bounds":{"x":10,"y":20,"width":30,"height":40}}}
                ]}""",
            ).jsonObject,
        )
        assertEquals(2, out.size)
        assertEquals("com.x:id/btn", (out[0] as AppAction.Click).target.resourceId)
        assertEquals(
            ActionBounds(10, 20, 30, 40),
            (out[1] as AppAction.Click).target.bounds,
        )
    }

    @Test
    fun `parses scroll with default page amount`() {
        val out = AppActionParser.parseSequence(
            json.parseToJsonElement(
                """{"actions":[{"action":"scroll","direction":"down"}]}""",
            ).jsonObject,
        )
        assertEquals(1, out.size)
        val scroll = out.first() as AppAction.Scroll
        assertEquals(ScrollDirection.DOWN, scroll.direction)
        assertEquals(ScrollAmount.PAGE, scroll.amount)
    }

    @Test
    fun `parses type with optional target`() {
        val out = AppActionParser.parseSequence(
            json.parseToJsonElement(
                """{"actions":[{"action":"type","value":"Wi-Fi"}]}""",
            ).jsonObject,
        )
        assertEquals(1, out.size)
        val type = out.first() as AppAction.Type
        assertEquals("Wi-Fi", type.value)
    }

    @Test
    fun `parses global verbs`() {
        val out = AppActionParser.parseSequence(
            json.parseToJsonElement(
                """{"actions":[
                    {"action":"back"},
                    {"action":"home"},
                    {"action":"recents"},
                    {"action":"notifications"},
                    {"action":"screenshot"}
                ]}""",
            ).jsonObject,
        )
        assertEquals(5, out.size)
        assertTrue(out[0] is AppAction.Back)
        assertTrue(out[1] is AppAction.Home)
        assertTrue(out[2] is AppAction.Recents)
        assertTrue(out[3] is AppAction.Notifications)
        assertTrue(out[4] is AppAction.Screenshot)
    }

    @Test
    fun `caps sequence at MAX_STEPS_PER_SEQUENCE`() {
        val actions = (1..20).joinToString(",") { """{"action":"back"}""" }
        val out = AppActionParser.parseSequence(
            json.parseToJsonElement("""{"actions":[$actions]}""").jsonObject,
        )
        assertEquals(AppAction.MAX_STEPS_PER_SEQUENCE, out.size)
    }

    @Test
    fun `skips malformed entries silently`() {
        val out = AppActionParser.parseSequence(
            json.parseToJsonElement(
                """{"actions":[
                    {"action":"click"},
                    "not-an-object",
                    {"verb":"missing-action"},
                    {"action":"unknown-verb"},
                    {"action":"home"}
                ]}""",
            ).jsonObject,
        )
        // Only "home" survives — click without target dropped, others
        // structurally invalid.
        assertEquals(1, out.size)
        assertTrue(out[0] is AppAction.Home)
    }

    @Test
    fun `bare actions array also parses`() {
        val arr = json.parseToJsonElement(
            """[{"action":"home"},{"action":"back"}]""",
        )
        val out = AppActionParser.parseArray(
            arr as kotlinx.serialization.json.JsonArray,
        )
        assertEquals(2, out.size)
    }

    @Test
    fun `missing actions field returns empty list`() {
        val out = AppActionParser.parseSequence(
            json.parseToJsonElement("""{"type":"app-action"}""").jsonObject,
        )
        assertTrue(out.isEmpty())
    }

    // ── MAP-AI-3 — 4 map verbs ─────────────────────────────────────

    @Test
    fun `parses navigate_to with full payload`() {
        val out = AppActionParser.parseSequence(
            json.parseToJsonElement(
                """{"actions":[{"action":"navigate_to","latitude":50.4501,"longitude":30.5234,"mode":"bike","label":"Майдан"}]}""",
            ).jsonObject,
        )
        assertEquals(1, out.size)
        val nav = out.first() as AppAction.NavigateTo
        assertEquals(50.4501, nav.latitude, 0.0001)
        assertEquals(30.5234, nav.longitude, 0.0001)
        assertEquals(TravelMode.BIKE, nav.mode)
        assertEquals("Майдан", nav.label)
    }

    @Test
    fun `navigate_to defaults mode to WALK when omitted`() {
        val out = AppActionParser.parseSequence(
            json.parseToJsonElement(
                """{"actions":[{"action":"navigate_to","lat":49.84,"lon":24.03}]}""",
            ).jsonObject,
        )
        val nav = out.single() as AppAction.NavigateTo
        assertEquals(TravelMode.WALK, nav.mode)
        assertEquals(null, nav.label)
    }

    @Test
    fun `navigate_to is dropped when coords missing`() {
        val out = AppActionParser.parseSequence(
            json.parseToJsonElement(
                """{"actions":[{"action":"navigate_to","mode":"walk"}]}""",
            ).jsonObject,
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `navigate_to accepts camelCase verb spelling`() {
        val out = AppActionParser.parseSequence(
            json.parseToJsonElement(
                """{"actions":[{"action":"navigateTo","latitude":1.0,"longitude":2.0}]}""",
            ).jsonObject,
        )
        assertEquals(1, out.size)
        assertTrue(out.first() is AppAction.NavigateTo)
    }

    @Test
    fun `parses search_place with query`() {
        val out = AppActionParser.parseSequence(
            json.parseToJsonElement(
                """{"actions":[{"action":"search_place","query":"кавʼярні поряд"}]}""",
            ).jsonObject,
        )
        val s = out.single() as AppAction.SearchPlace
        assertEquals("кавʼярні поряд", s.query)
    }

    @Test
    fun `search_place accepts q alias`() {
        val out = AppActionParser.parseSequence(
            json.parseToJsonElement(
                """{"actions":[{"action":"search","q":"банкомат"}]}""",
            ).jsonObject,
        )
        val s = out.single() as AppAction.SearchPlace
        assertEquals("банкомат", s.query)
    }

    @Test
    fun `search_place trims and drops blank query`() {
        val out = AppActionParser.parseSequence(
            json.parseToJsonElement(
                """{"actions":[{"action":"search_place","query":"   "}]}""",
            ).jsonObject,
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `parses show_on_map with string GeoJSON`() {
        val out = AppActionParser.parseSequence(
            json.parseToJsonElement(
                """{"actions":[{"action":"show_on_map","geometry":"{\"type\":\"Point\",\"coordinates\":[30,50]}"}]}""",
            ).jsonObject,
        )
        val s = out.single() as AppAction.ShowOnMap
        assertTrue(s.geoJson.contains("Point"))
    }

    @Test
    fun `show_on_map accepts nested JSON object as geometry`() {
        val out = AppActionParser.parseSequence(
            json.parseToJsonElement(
                """{"actions":[{"action":"show_on_map","geoJson":{"type":"LineString","coordinates":[[1,2],[3,4]]}}]}""",
            ).jsonObject,
        )
        val s = out.single() as AppAction.ShowOnMap
        assertTrue(s.geoJson.contains("LineString"))
    }

    @Test
    fun `show_on_map dropped when geometry missing`() {
        val out = AppActionParser.parseSequence(
            json.parseToJsonElement(
                """{"actions":[{"action":"show_on_map"}]}""",
            ).jsonObject,
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `parses save_offline_region with bbox`() {
        val out = AppActionParser.parseSequence(
            json.parseToJsonElement(
                """{"actions":[{"action":"save_offline_region","north":50.5,"east":30.6,"south":50.4,"west":30.5,"name":"Київ-центр"}]}""",
            ).jsonObject,
        )
        val r = out.single() as AppAction.SaveOfflineRegion
        assertEquals(50.5, r.north, 0.0001)
        assertEquals(30.6, r.east, 0.0001)
        assertEquals(50.4, r.south, 0.0001)
        assertEquals(30.5, r.west, 0.0001)
        assertEquals("Київ-центр", r.name)
    }

    @Test
    fun `save_offline_region drops when any bbox edge missing`() {
        val out = AppActionParser.parseSequence(
            json.parseToJsonElement(
                """{"actions":[{"action":"save_offline_region","north":50.5,"east":30.6,"south":50.4}]}""",
            ).jsonObject,
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `save_offline_region allows null name`() {
        val out = AppActionParser.parseSequence(
            json.parseToJsonElement(
                """{"actions":[{"action":"save_offline_region","north":1.0,"east":2.0,"south":0.0,"west":-1.0}]}""",
            ).jsonObject,
        )
        val r = out.single() as AppAction.SaveOfflineRegion
        assertEquals(null, r.name)
    }
}
