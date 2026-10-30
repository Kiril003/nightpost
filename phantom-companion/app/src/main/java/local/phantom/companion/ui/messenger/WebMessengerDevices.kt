package local.phantom.companion.ui.messenger

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.time.Instant
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.data.AppContainer
import local.phantom.companion.data.BodyLadder

/**
 * `/messenger/devices` на телефоні — вікно «Мій пристрій» тим самим контрактом,
 * що й на ПК (`routes_messenger_devices.py`). Сходинки телефона живуть тут, у
 * [BodyLadder], і міняються лише тут; сходинки ПК ми лише показуємо — їх міняє
 * людина за компʼютером. До ПК ходимо симбіотом із токеном телефона.
 */
class WebMessengerDevices(
    private val ladder: BodyLadder,
    private val link: Link,
    private val onWebSignIn: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {

    private var context: android.content.Context? = null

    interface Link {
        /** null — телефон ні з чим не спарений. */
        fun paired(): Paired?
        suspend fun get(path: String): Reply
        suspend fun post(path: String, body: JsonObject): Reply
    }

    data class Paired(val name: String, val peerId: String?)

    /** [status] 0 — до ПК не докричались. */
    data class Reply(val status: Int, val body: String)

    @Volatile private var seenAtMs: Long? = null

    private val profile = WebPhantomProfile(
        ladder,
        get = { path -> link.get(path).let { it.status to it.body } },
        post = { path, body -> link.post(path, body).let { it.status to it.body } },
    )

    fun claims(seg: List<String>): Boolean = seg.size >= 2 && seg[1] == "devices"

    suspend fun handle(method: String, seg: List<String>, body: String?): String = run {
        val id = seg.getOrNull(2)
        when {
            seg.size == 2 && seg[1] == "devices" && method == "GET" -> ok(list())
            seg.size == 3 && seg[1] == "devices" && seg[2] == "web-signin" && method == "POST" -> {
                onWebSignIn()
                ok(buildJsonObject { put("opened", true) })
            }
            // Профіль › PHANTOM: стрічка й «Стоп усе» — до будь-якого пристрою.
            profile.claims(seg) -> profile.handle(method, seg, body)?.let { ok(it) } ?: fail(404, "not_found")
            id != PC_ID || link.paired() == null -> fail(404, "device_not_found")
            seg.size == 4 && seg[1] == "devices" && seg[3] == "act" && method == "POST" -> act(parse(body))
            seg.size == 4 && seg[1] == "devices" && seg[3] == "ladder" && method == "GET" -> ok(ladders())
            seg.size == 4 && seg[1] == "devices" && seg[3] == "ladder" && method == "PATCH" -> patch(parse(body))
            seg.size == 4 && seg[1] == "devices" && seg[3] == "journal" && method == "GET" -> ok(journal())
            seg.size == 4 && seg[1] == "devices" && seg[3] == "stop" && method == "POST" -> ok(stop(parse(body)))
            else -> fail(404, "not_found")
        }
    }

    private suspend fun list(): JsonObject {
        val paired = link.paired()
        return buildJsonObject {
            put("stopped", ladder.stopped.value)
            put("devices", buildJsonArray { if (paired != null) add(pcRow(paired)) })
        }
    }

    private suspend fun pcRow(paired: Paired): JsonObject {
        val state = link.get("/api/v1/symbiote/state").takeIf { it.status == 200 }?.let { obj(it.body) }
        val feed = link.get("/api/v1/symbiote/feed").takeIf { it.status == 200 }?.let { obj(it.body) }
        if (state != null || feed != null) seenAtMs = now()
        val pc = state?.get("bodies")?.let { it as? JsonArray }
            ?.map { it.jsonObject }
            ?.firstOrNull { it.str("kind") == "desktop" }
        val stopped = feed?.get("stopped")?.jsonPrimitive?.booleanOrNull
            ?: state?.get("stopped")?.jsonPrimitive?.booleanOrNull
        return buildJsonObject {
            put("id", PC_ID)
            put("name", paired.name)
            put("kind", "pc")
            paired.peerId?.let { put("peer_id", it) }
            put("online", state != null || feed != null)
            seenAtMs?.let { put("seen_at", Instant.ofEpochMilli(it).toString()) }
            pc?.get("battery_pct")?.jsonPrimitive?.intOrNull?.let { put("battery_pct", it) }
            pc?.get("charging")?.jsonPrimitive?.booleanOrNull?.let { put("charging", it) }
            stopped?.let { put("stopped", it) }
        }
    }

    private suspend fun act(body: JsonObject?): String {
        val verb = body?.str("verb").orEmpty()
        if (verb !in ACTS) return fail(400, "unknown_act")
        val text = body?.str("text").orEmpty().trim()
        if (verb != "pc.lock" && text.isEmpty()) return ok(said(false, "empty", ""))
        val args = buildJsonObject {
            if (text.isNotEmpty()) put(if (verb == "pc.open") "url" else "text", text)
        }
        val reply = link.post(
            "/api/v1/symbiote/command",
            buildJsonObject {
                put("verb", verb)
                put("args", args)
            },
        )
        val out = obj(reply.body)
        return ok(
            when (reply.status) {
                200 -> {
                    seenAtMs = now()
                    val result = out?.get("result") as? JsonObject
                    val waiting = (result?.get("payload") as? JsonObject)?.str("status")
                    when {
                        waiting == "asked" -> said(true, "asked", "")
                        waiting == "suggested" -> said(true, "suggested", "")
                        result?.get("ok")?.jsonPrimitive?.booleanOrNull == true -> said(true, "done", "")
                        else -> said(false, "failed", "")
                    }
                }
                403 -> said(false, "refused", message(out).orEmpty())
                423 -> said(false, "stopped", "")
                0 -> said(false, "offline", "")
                else -> said(false, "failed", "")
            },
        )
    }

    private suspend fun ladders(): JsonObject {
        val there = link.get("/api/v1/symbiote/ladder").takeIf { it.status == 200 }
            ?.let { obj(it.body) }
            ?.get("devices")?.let { it as? JsonArray }
            ?.firstOrNull()?.jsonObject
            ?.get("ladder")?.let { it as? JsonArray }
            ?.map { it.jsonObject }
            ?.associate { it.str("sphere").orEmpty() to it.str("level").orEmpty() }
        return buildJsonObject {
            put(
                "here",
                buildJsonObject {
                    put("editable", true)
                    put(
                        "spheres",
                        buildJsonArray {
                            PHONE_SPHERES.forEach { s -> add(sphere(s, ladder.level(s).key)) }
                        },
                    )
                },
            )
            if (there == null) {
                put("there", JsonNull)
            } else {
                put(
                    "there",
                    buildJsonObject {
                        put("editable", false)
                        put(
                            "spheres",
                            buildJsonArray {
                                PC_SPHERES.forEach { s -> there[s.key]?.let { add(sphere(s, it)) } }
                            },
                        )
                    },
                )
            }
        }
    }

    private fun sphere(s: BodyLadder.Sphere, level: String): JsonObject {
        val context = this.context
        return buildJsonObject {
            put("sphere", s.key)
            put("title", if (context != null) context.getString(s.label) else s.title)
            put("level", level)
            put("irreversible", s == BodyLadder.Sphere.KEYBOARD)
        }
    }

    private suspend fun patch(body: JsonObject?): String {
        val sphere = PHONE_SPHERES.firstOrNull { it.key == body?.str("sphere") }
        val rung = BodyLadder.Rung.entries.firstOrNull { it.key == body?.str("level") }
        if (sphere == null || rung == null) return fail(400, "bad_rung")
        ladder.set(sphere, rung)
        return ok(ladders())
    }

    private suspend fun journal(): JsonObject {
        val reply = link.get("/api/v1/symbiote/journal?limit=30")
        val entries = obj(reply.body)?.get("entries")?.let { it as? JsonArray }.orEmpty()
        return buildJsonObject {
            put("stopped", ladder.stopped.value)
            put(
                "entries",
                buildJsonArray {
                    entries.map { it.jsonObject }.forEach { e -> add(mapJournalRow(e)) }
                },
            )
        }
    }

    private suspend fun stop(body: JsonObject?): JsonObject {
        val on = body?.get("on")?.jsonPrimitive?.booleanOrNull ?: true
        if (on) {
            ladder.stopAll()
            // Від того, чи ПК почув, зупинка тут не залежить.
            link.post("/api/v1/symbiote/stop", buildJsonObject { put("on", true) })
        } else {
            ladder.resume()
        }
        return buildJsonObject { put("stopped", ladder.stopped.value) }
    }

    private fun said(ok: Boolean, state: String, said: String) = buildJsonObject {
        put("ok", ok)
        put("state", state)
        put("said", said)
    }

    private fun message(o: JsonObject?): String? =
        (o?.get("detail") as? JsonObject)?.str("message")?.takeIf { it.isNotBlank() }

    private fun parse(body: String?): JsonObject? = body?.let { obj(it) }

    private fun obj(s: String): JsonObject? =
        runCatching { Json.parseToJsonElement(s) as? JsonObject }.getOrNull()

    private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

    private fun ok(data: JsonElement): String = buildJsonObject {
        put("ok", true)
        put("status", 200)
        put("data", data)
    }.toString()

    private fun fail(status: Int, code: String): String = buildJsonObject {
        put("ok", false)
        put("status", status)
        put("code", code)
    }.toString()

    companion object {
        const val SEGMENT = "devices"
        const val PC_ID = "pc"

        val ACTS = setOf("pc.clipboard", "pc.open", "pc.notify", "pc.lock")
        // Тепер телефон діє і в екрані, клавіатурі, буфері — тож власник керує
        // всіма шістьма сферами звідси, не лише місцем/звуком/сповіщеннями.
        private val PHONE_SPHERES = listOf(
            BodyLadder.Sphere.PLACE,
            BodyLadder.Sphere.SOUND,
            BodyLadder.Sphere.NOTICES,
            BodyLadder.Sphere.SCREEN,
            BodyLadder.Sphere.KEYBOARD,
            BodyLadder.Sphere.CLIPBOARD,
        )
        private val PC_SPHERES = listOf(
            BodyLadder.Sphere.NOTICES, BodyLadder.Sphere.CLIPBOARD, BodyLadder.Sphere.SCREEN, BodyLadder.Sphere.KEYBOARD,
        )
        private const val REACH_MS = 6_000L
        private val STATUS_ONLY_KEYS = setOf("stop_on", "stop_off", "ladder")

        internal fun mapJournalRow(e: JsonObject): JsonObject {
            val verb = (e["verb"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val status = (e["status"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val hasVerbTitle = verb.isNotEmpty() && !verb.startsWith("symbiote.") && status !in STATUS_ONLY_KEYS
            val whatKey = when {
                hasVerbTitle -> "verb.$verb"
                status.isNotEmpty() -> "status.$status"
                verb.isNotEmpty() -> "verb.$verb"
                else -> ""
            }
            val direction = (e["direction"] as? JsonPrimitive)?.contentOrNull
            val rawWho = (e["who"] as? JsonPrimitive)?.contentOrNull
            val who = if (rawWho == "this_phone" || direction == "to_pc") "this_phone" else "pc"
            return buildJsonObject {
                put("id", e["id"]?.jsonPrimitive?.contentOrNull.orEmpty())
                put("at", (e["at"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                put("verb", verb)
                put("status", status)
                put("what_key", whatKey)
                val what = (e["what"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val statusTitle = (e["status_title"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                put("what", what.ifEmpty { statusTitle })
                put("who", who)
                put("status_title", if (what.isEmpty()) "" else statusTitle)
                put("detail", (e["detail"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                put("can_answer", false)
            }
        }

        fun of(container: AppContainer): WebMessengerDevices =
            WebMessengerDevices(
                ladder = BodyLadder.of(container.applicationContext),
                link = SymbioteLink(container),
                onWebSignIn = {
                    container.events.routeRequest.value = local.phantom.companion.ui.screens.WebSignIn.ROUTE
                },
            ).also { it.context = container.applicationContext }
    }

    private class SymbioteLink(private val c: AppContainer) : Link {
        override fun paired(): Paired? {
            val p = runCatching { c.store.load() }.getOrNull() ?: return null
            val node = p.nodePeerId.takeIf { it.isNotBlank() }
            val name = node?.let { id -> c.peers.peers.value.firstOrNull { it.id == id }?.name }
                ?.takeIf { it.isNotBlank() }
            return Paired(name.orEmpty(), node)
        }

        override suspend fun get(path: String): Reply = reach { jwt ->
            val res = c.api.client.get("${c.api.node()}$path") { bearerAuth(jwt) }
            Reply(res.status.value, res.bodyAsText())
        }

        override suspend fun post(path: String, body: JsonObject): Reply = reach { jwt ->
            val res = c.api.client.post("${c.api.node()}$path") {
                bearerAuth(jwt)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            Reply(res.status.value, res.bodyAsText())
        }

        private suspend fun reach(call: suspend (String) -> Reply): Reply {
            val jwt = runCatching { c.store.load()?.deviceJwt }.getOrNull() ?: return Reply(0, "")
            return runCatching { withTimeoutOrNull(REACH_MS) { call(jwt) } }.getOrNull() ?: Reply(0, "")
        }
    }
}
