package local.phantom.companion.net

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [PhantomLink]'s testable helpers. Opening a real socket
 * still needs a live Ktor [io.ktor.client.HttpClient] session that MockEngine
 * cannot fake — but the reconnect decisions the loop makes around it are
 * seams here. We lock down:
 *
 *   - [buildWebSocketUrl] — REST-base → `/ws?token=` translation, the
 *     regression that broke pairings before COMPANION_CONTRACT.md §10.1
 *     was tracked.
 *   - The `/ws` path is bare (NOT `/api/v1/ws`) — the contract gap.
 *   - Subscribe message JSON is byte-equivalent to the server's
 *     `websocket_hub.py` parser shape.
 *   - [pumpEndpoints] / [sessionWasUseful] / [nextBackoffMs] — a session
 *     that only completed the handshake must not reset the retry ladder.
 */
class PhantomLinkTest {

    // ── buildWebSocketUrl ────────────────────────────────────────────────

    @Test fun `http base URL produces ws scheme on the same port`() {
        assertEquals(
            "ws://phantom.local:8000/ws?token=jwt",
            buildWebSocketUrl("http://phantom.local:8000", "jwt"),
        )
    }

    @Test fun `https base URL produces wss scheme`() {
        assertEquals(
            "wss://phantom.local:8443/ws?token=tok",
            buildWebSocketUrl("https://phantom.local:8443", "tok"),
        )
    }

    @Test fun `ws path is bare slash-ws not slash-api-v1-ws (CONTRACT 10_1)`() {
        // Backend mounts at literal `/ws` (main.py:930). Prepending /api/v1
        // returns 404 from the FastAPI router. Lock the regression in.
        val url = buildWebSocketUrl("http://phantom.local:8000", "jwt")
        assertTrue("URL must contain /ws not /api/v1/ws — got: $url", "/ws?token=" in url)
        assertTrue("URL must NOT contain /api/v1 — got: $url", "/api/v1" !in url)
    }

    @Test fun `IPv4 host is preserved`() {
        assertEquals(
            "ws://192.168.1.42:8000/ws?token=t",
            buildWebSocketUrl("http://192.168.1.42:8000", "t"),
        )
    }

    @Test fun `default http port 80 is used when none is given`() {
        assertEquals(
            "ws://phantom.local:80/ws?token=t",
            buildWebSocketUrl("http://phantom.local", "t"),
        )
    }

    @Test fun `default https port 443 is used when none is given`() {
        assertEquals(
            "wss://phantom.local:443/ws?token=t",
            buildWebSocketUrl("https://phantom.local", "t"),
        )
    }

    @Test fun `token is appended verbatim to the query string`() {
        // Token format is opaque to PhantomLink — server does the JWT parse.
        // We only assert the value is preserved as-is.
        val url = buildWebSocketUrl("http://h:8000", "header.payload.sig")
        assertTrue(
            "token must be appended verbatim — got: $url",
            url.endsWith("?token=header.payload.sig"),
        )
    }

    // ── SubscribeMessage shape — byte-compatible with the backend hub ────

    @Test fun `SubscribeMessage serializes to control plus channels`() {
        // websocket_hub.py:226-262 reads {"control": "subscribe", "channels": [...]}.
        // If either field name drifts, the server silently ignores the frame
        // and the phone receives only the default-channel subset, missing
        // agent.stream / profile_sync.
        val json = Json.encodeToString(
            SubscribeMessage.serializer(),
            SubscribeMessage("subscribe", listOf("agent.stream", "profile_sync")),
        )
        assertTrue("JSON must contain control field — got: $json", "\"control\"" in json)
        assertTrue("JSON must contain channels field — got: $json", "\"channels\"" in json)
        assertTrue("must carry agent.stream — got: $json", "\"agent.stream\"" in json)
        assertTrue("must carry profile_sync — got: $json", "\"profile_sync\"" in json)
    }

    // ── parseHubFrame — server→client envelope decoding ──────────────────

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test fun `parseHubFrame extracts channel and type from valid envelope`() {
        val text = """{"channel":"sensor","type":"snapshot","data":{"x":1},"ts":1700000000000}"""
        val ev = parseHubFrame(text, json)!!
        assertEquals("sensor", ev.channel)
        assertEquals("snapshot", ev.type)
        assertEquals("ts must round-trip via raw", "1700000000000", ev.raw["ts"]?.toString())
    }

    @Test fun `parseHubFrame returns null for malformed JSON`() {
        // The connect loop must NOT abort on a bad frame — it skips and
        // keeps reading the next one.
        assertEquals(null, parseHubFrame("not-json", json))
        assertEquals(null, parseHubFrame("", json))
        assertEquals(null, parseHubFrame("{[malformed", json))
    }

    @Test fun `parseHubFrame returns null when channel field is missing`() {
        val text = """{"type":"x","data":{}}"""
        assertEquals(null, parseHubFrame(text, json))
    }

    @Test fun `parseHubFrame returns null when channel is blank or wrong type`() {
        // Regression: the previous implementation did obj.toString().trim('"')
        // which silently accepted nested objects/arrays and emitted "[1,2,3]"
        // as a "channel name". parseHubFrame now requires a JsonPrimitive
        // string before accepting the value.
        assertEquals(null, parseHubFrame("""{"channel":"","type":"x"}""", json))
        assertEquals(null, parseHubFrame("""{"channel":[1,2],"type":"x"}""", json))
        assertEquals(null, parseHubFrame("""{"channel":{"a":1},"type":"x"}""", json))
    }

    @Test fun `parseHubFrame defaults type to empty string when missing`() {
        // The server-side envelope guarantees a type, but a partial frame
        // shouldn't kill the link — the caller's `when` over channel name
        // can still do something useful.
        val ev = parseHubFrame("""{"channel":"_meta"}""", json)!!
        assertEquals("_meta", ev.channel)
        assertEquals("", ev.type)
    }

    @Test fun `parseHubFrame preserves nested data via raw`() {
        // PhantomViewModel handlers cast event.raw["data"] as JsonObject —
        // verify the round-trip through the parser.
        val text = """{"channel":"pair","type":"claimed","data":{"device_id":"abc"},"ts":1}"""
        val ev = parseHubFrame(text, json)!!
        val data = ev.raw["data"] as kotlinx.serialization.json.JsonObject
        assertEquals(
            "\"abc\"",
            data["device_id"]?.toString(),
        )
    }

    // ── Reconnect backoff — the session must be USEFUL, not merely opened ──

    @Test fun `an upgrade that carries no frame is not a useful session`() {
        // The defect: success was latched at the 101 upgrade. A node that
        // accepts and closes — a PC that is off behind the relay — then reset
        // the ladder to 1 s forever: TLS + WS handshake once per second.
        assertTrue(!sessionWasUseful(frames = 0, durationMs = 30_000))
    }

    @Test fun `a frame inside a sub-second session is still not useful`() {
        // A device JWT rejected INSIDE the ws handler answers with one error
        // frame and hangs up. Counting that frame would re-open the same hole.
        assertTrue(!sessionWasUseful(frames = 1, durationMs = 200))
    }

    @Test fun `a session that carried frames and lasted is useful`() {
        assertTrue(sessionWasUseful(frames = 1, durationMs = MIN_USEFUL_SESSION_MS))
        assertTrue(sessionWasUseful(frames = 400, durationMs = 600_000))
    }

    @Test fun `backoff walks the documented ladder and stops at sixty seconds`() {
        var ms = BACKOFF_LADDER_MS.first()
        val walked = mutableListOf(ms)
        repeat(5) {
            ms = nextBackoffMs(ms, useful = false)
            walked += ms
        }
        assertEquals(listOf(1_000L, 2_000L, 5_000L, 15_000L, 60_000L, 60_000L), walked)
    }

    @Test fun `a useful session returns the ladder to its first rung`() {
        assertEquals(1_000L, nextBackoffMs(60_000L, useful = true))
    }

    @Test fun `an out-of-ladder value cannot fall back to one second`() {
        // Guards the index arithmetic: a value past the last rung must clamp
        // to the ceiling, never wrap to the fastest retry.
        assertEquals(60_000L, nextBackoffMs(120_000L, useful = false))
    }

    // ── pumpEndpoints — the connectLoop seam ─────────────────────────────

    @Test fun `a live session that carried nothing reports the round as useless`() = runBlocking {
        // Pre-fix this was `success = true` the moment webSocketSession
        // returned. The round must now be judged by what the session carried.
        val tried = mutableListOf<String>()
        val useful = pumpEndpoints(listOf("http://a:8000")) { url ->
            tried += url
            false
        }
        assertEquals(listOf("http://a:8000"), tried)
        assertTrue("an upgrade alone must not reset the backoff", !useful)
    }

    @Test fun `a useful session reports the round as useful`() = runBlocking {
        assertTrue(pumpEndpoints(listOf("http://a:8000")) { true })
    }

    @Test fun `a refusing address hands over to the next one`() = runBlocking {
        val tried = mutableListOf<String>()
        val useful = pumpEndpoints(listOf("http://dead:8000", "http://live:8000")) { url ->
            tried += url
            if (url.contains("dead")) throw java.io.IOException("connection refused")
            true
        }
        assertEquals(listOf("http://dead:8000", "http://live:8000"), tried)
        assertTrue(useful)
    }

    @Test fun `an address that answered is not followed by the others`() = runBlocking {
        val tried = mutableListOf<String>()
        pumpEndpoints(listOf("http://a:8000", "http://b:8000")) { url ->
            tried += url
            false
        }
        assertEquals("a node that answered ends the round", listOf("http://a:8000"), tried)
    }

    @Test fun `every address refusing leaves the round useless`() = runBlocking {
        val seen = mutableListOf<Throwable>()
        val useful = pumpEndpoints(
            endpoints = listOf("http://a:8000", "http://b:8000"),
            onFailure = { _, t -> seen += t },
            session = { throw java.io.IOException("nope") },
        )
        assertTrue(!useful)
        assertEquals(2, seen.size)
    }

    @Test fun `a peer that accepts and hangs up reaches the ceiling in five rounds`() = runBlocking {
        // The whole defect in one test: an always-answering, never-speaking
        // node must not be retried at 1 Hz inside a boot-started service.
        var backoffMs = BACKOFF_LADDER_MS.first()
        repeat(5) {
            val useful = pumpEndpoints(listOf("http://relay:443")) {
                sessionWasUseful(frames = 0, durationMs = 40)
            }
            backoffMs = nextBackoffMs(backoffMs, useful)
        }
        assertEquals(60_000L, backoffMs)
    }

    // ── Compile-time invariant — channel set covers contract priority list ─

    @Test fun `channels set names every contract priority channel`() {
        // CONTRACT §3 channel inventory: the phone must subscribe to every
        // server-only channel that has a typed handler in PhantomViewModel.
        // Default mobile filter on the server already covers sensor / state
        // / pair / familiar / context / chat — those don't need to be listed
        // here, but listing them is harmless (UNION) and self-documenting.
        listOf("sensor", "context", "state", "familiar", "pair", "agent.stream", "profile_sync")
            .forEach { ch ->
                assertTrue(
                    "PhantomLink must subscribe to '$ch'",
                    ch in PHANTOM_LINK_CHANNELS,
                )
            }
    }
}
