package local.phantom.companion.feature.godmode.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Phase 3 W4-4 — single accessibility action emitted by the AI inside
 * an `app-action` widget JSON. The dispatcher executes the sequence
 * step-by-step via the AccessibilityService node-tree APIs.
 *
 * Sequence cap: hard-coded to [MAX_STEPS_PER_SEQUENCE] (8). The AI
 * may emit longer arrays — extra entries are silently dropped by
 * [AppActionParser] so the operator never gets dragged through an
 * infinite chain.
 */
sealed class AppAction {

    /** Tap a node found by text / resource-id / bounds (in that priority). */
    data class Click(val target: ActionTarget) : AppAction()

    /** Scroll the first scrollable ancestor of the active root in [direction]. */
    data class Scroll(
        val direction: ScrollDirection,
        val amount: ScrollAmount = ScrollAmount.PAGE,
    ) : AppAction()

    /** Set text on a node found by [target] (or focus, if null) — for input fields. */
    data class Type(val value: String, val target: ActionTarget? = null) : AppAction()

    /** GLOBAL_ACTION_BACK. */
    object Back : AppAction()

    /** GLOBAL_ACTION_HOME. */
    object Home : AppAction()

    /** GLOBAL_ACTION_RECENTS. */
    object Recents : AppAction()

    /** GLOBAL_ACTION_NOTIFICATIONS. */
    object Notifications : AppAction()

    /** GLOBAL_ACTION_TAKE_SCREENSHOT (Android 11+). */
    object Screenshot : AppAction()

    /** Launch a foreground app by `query` (package name if `query`
     *  contains a dot, otherwise an alias or label resolved at
     *  dispatch time). Uses `getLaunchIntentForPackage` — far more
     *  reliable than locating an icon via accessibility. */
    data class OpenApp(val query: String) : AppAction()

    /**
     * AP-1 — injected touch gesture: finger down at (startX, startY),
     * straight-line drag to (endX, endY) over [durationMs], lift.
     * Dispatched via `AccessibilityService.dispatchGesture`, so unlike
     * [Click] it needs no accessibility node — it works on custom-drawn
     * surfaces (game canvases, WebViews) that expose nothing to the
     * node tree. Coordinates are absolute screen pixels.
     */
    data class Swipe(
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int,
        val durationMs: Long = DEFAULT_SWIPE_MS,
    ) : AppAction()

    /** AP-1 — injected single tap at absolute screen pixel (x, y). */
    data class Tap(val x: Int, val y: Int) : AppAction()

    /** AP-1 — injected long-press at (x, y) held for [durationMs]. */
    data class LongPress(
        val x: Int,
        val y: Int,
        val durationMs: Long = DEFAULT_LONG_PRESS_MS,
    ) : AppAction()

    /**
     * MAP-AI-3 — plot a route or recenter the in-chat MapWidget on
     * (latitude, longitude) with the given travel [mode]. Optional
     * [label] surfaces as a marker tooltip. Dispatched through
     * [MapActionRouter] — never an accessibility node tap.
     */
    data class NavigateTo(
        val latitude: Double,
        val longitude: Double,
        val mode: TravelMode = TravelMode.WALK,
        val label: String? = null,
    ) : AppAction()

    /**
     * MAP-AI-3 — perform a free-text place search and pin every result
     * on the MapWidget. The query is forwarded verbatim to the
     * Nominatim client by the receiving surface.
     */
    data class SearchPlace(val query: String) : AppAction()

    /**
     * MAP-AI-3 — render arbitrary GeoJSON [geoJson] on the MapWidget
     * (points, line strings, polygons). The receiver validates the
     * payload before drawing; malformed JSON is reported as a Notice.
     */
    data class ShowOnMap(val geoJson: String) : AppAction()

    /**
     * MAP-AI-3 — request a pre-download of map tiles for the
     * axis-aligned bounding box. This is a mutation: it consumes
     * megabytes of storage and bandwidth, so the receiving surface
     * MAY surface an explicit confirmation before kicking off the
     * download. [name] is operator-readable; null falls back to a
     * timestamp-derived label inside `OfflineRegionsManager`.
     */
    data class SaveOfflineRegion(
        val north: Double,
        val east: Double,
        val south: Double,
        val west: Double,
        val name: String? = null,
    ) : AppAction()

    companion object {
        /** Max actions per single dispatch call — fixed by safety policy. */
        const val MAX_STEPS_PER_SEQUENCE: Int = 8

        /** AP-1 — default drag duration; fast enough to register as a
         *  fling in 2048-style games, slow enough not to be dropped. */
        const val DEFAULT_SWIPE_MS: Long = 120L

        /** AP-1 — default hold for [LongPress]; past the 500 ms
         *  ViewConfiguration long-press threshold on every OEM. */
        const val DEFAULT_LONG_PRESS_MS: Long = 600L
    }
}

/**
 * MAP-AI-3 — travel mode hint for [AppAction.NavigateTo]. The receiver
 * (typically the in-chat MapWidget or a future routing engine like
 * Valhalla under MAP-AI-5) picks the corresponding profile.
 */
enum class TravelMode { WALK, BIKE, DRIVE, TRANSIT }

/**
 * Shared alias map for `open_app` and the parser-side click→OpenApp
 * coercion. The ScreenAwarenessService resolver also reads this so
 * resolution stays one place. Add new entries in lowercase.
 */
object AppActionAliases {
    val KNOWN: Map<String, String> = mapOf(
        "telegram" to "org.telegram.messenger",
        "viber" to "com.viber.voip",
        "whatsapp" to "com.whatsapp",
        "signal" to "org.thoughtcrime.securesms",
        "messenger" to "com.facebook.orca",
        "instagram" to "com.instagram.android",
        "tiktok" to "com.zhiliaoapp.musically",
        "youtube" to "com.google.android.youtube",
        "ютуб" to "com.google.android.youtube",
        "spotify" to "com.spotify.music",
        "settings" to "com.android.settings",
        "налаштування" to "com.android.settings",
        "gmail" to "com.google.android.gm",
        "пошта" to "com.google.android.gm",
        "mail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "карти" to "com.google.android.apps.maps",
        "chrome" to "com.android.chrome",
        "хром" to "com.android.chrome",
        "браузер" to "com.android.chrome",
        "browser" to "com.android.chrome",
        "google" to "com.google.android.googlequicksearchbox",
        "гугл" to "com.google.android.googlequicksearchbox",
        "пошук" to "com.google.android.googlequicksearchbox",
        "search" to "com.google.android.googlequicksearchbox",
        "assistant" to "com.google.android.googlequicksearchbox",
        "асистент" to "com.google.android.googlequicksearchbox",
        "facebook" to "com.facebook.katana",
        "фейсбук" to "com.facebook.katana",
        "інстаграм" to "com.instagram.android",
        "телеграм" to "org.telegram.messenger",
        "вайбер" to "com.viber.voip",
        "ватсап" to "com.whatsapp",
        "сігнал" to "org.thoughtcrime.securesms",
        "камера" to "com.sec.android.app.camera",
        "camera" to "com.sec.android.app.camera",
        "галерея" to "com.sec.android.gallery3d",
        "gallery" to "com.sec.android.gallery3d",
        "calculator" to "com.sec.android.app.popupcalculator",
        "калькулятор" to "com.sec.android.app.popupcalculator",
        "calendar" to "com.samsung.android.calendar",
        "календар" to "com.samsung.android.calendar",
        "clock" to "com.sec.android.app.clockpackage",
        "годинник" to "com.sec.android.app.clockpackage",
        "phantom" to "local.phantom.companion.debug",
    )

    fun isKnownAlias(s: String): Boolean = KNOWN.containsKey(s.trim().lowercase())
}

/**
 * Locator for a node the dispatcher should act on. The matchers run
 * in priority order: [text] (substring, case-insensitive) →
 * [resourceId] (exact) → [bounds] (closest centre). The AI is
 * encouraged to emit `text` for human-named buttons; `resourceId`
 * for stable Android system widgets; `bounds` only as last resort.
 */
data class ActionTarget(
    val text: String? = null,
    val resourceId: String? = null,
    val bounds: ActionBounds? = null,
)

/** Pixel-rectangle target. Bounds are screen-space (root-window origin). */
data class ActionBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }
enum class ScrollAmount { LINE, PAGE }

/**
 * Pure parser for the AI's `app-action` widget JSON payload. Lossy
 * by design: any malformed entry inside the array is skipped (not
 * fatal); the sequence is capped at [AppAction.MAX_STEPS_PER_SEQUENCE].
 *
 * Expected JSON shape:
 * ```json
 * {
 *   "type": "app-action",
 *   "actions": [
 *     {"action": "click",   "target": {"text": "Налаштування"}},
 *     {"action": "scroll",  "direction": "down", "amount": "page"},
 *     {"action": "type",    "value": "Wi-Fi"},
 *     {"action": "back"}
 *   ]
 * }
 * ```
 *
 * The parser accepts either the full envelope or a bare actions
 * array — convenient for both AI-emitted widget payloads and
 * unit-test fixtures.
 */
object AppActionParser {

    fun parseSequence(envelope: JsonObject): List<AppAction> {
        val arr = envelope["actions"] as? JsonArray ?: return emptyList()
        return parseArray(arr)
    }

    fun parseArray(arr: JsonArray): List<AppAction> {
        val out = mutableListOf<AppAction>()
        for (el in arr) {
            val obj = (el as? JsonObject) ?: continue
            val parsed = parseSingle(obj) ?: continue
            out += parsed
            if (out.size >= AppAction.MAX_STEPS_PER_SEQUENCE) break
        }
        return out
    }

    private fun parseSingle(obj: JsonObject): AppAction? {
        val verb = (obj["action"] as? JsonPrimitive)?.contentOrNull()?.lowercase()
            ?: return null
        return when (verb) {
            "click" -> {
                val t = obj.parseTarget() ?: return null
                // Auto-coerce «click on target.text=<known app alias>»
                // into OpenApp. Gemini frequently emits
                //   {"action":"click","target":{"text":"Spotify"}}
                // when the operator says «відкрий Spotify», expecting
                // the dispatcher to find an icon — but at that moment
                // the active window is Phantom (or another app), not
                // the launcher, so the find returns null. Coerce to
                // OpenApp so the launch intent fires reliably.
                val textKey = t.text?.trim()?.lowercase()
                if (textKey != null && AppActionAliases.isKnownAlias(textKey)) {
                    AppAction.OpenApp(query = textKey)
                } else {
                    AppAction.Click(t)
                }
            }
            "scroll" -> {
                val dir = obj.string("direction")?.lowercase()?.let { d ->
                    runCatching { ScrollDirection.valueOf(d.uppercase()) }.getOrNull()
                } ?: return null
                val amount = obj.string("amount")?.let { a ->
                    runCatching { ScrollAmount.valueOf(a.uppercase()) }.getOrNull()
                } ?: ScrollAmount.PAGE
                AppAction.Scroll(direction = dir, amount = amount)
            }
            "type" -> {
                val value = obj.string("value") ?: return null
                AppAction.Type(value = value, target = obj.parseTarget())
            }
            "swipe" -> {
                val sx = obj.int("startX") ?: obj.int("start_x") ?: return null
                val sy = obj.int("startY") ?: obj.int("start_y") ?: return null
                val ex = obj.int("endX") ?: obj.int("end_x") ?: return null
                val ey = obj.int("endY") ?: obj.int("end_y") ?: return null
                val dur = obj.int("durationMs")?.toLong() ?: AppAction.DEFAULT_SWIPE_MS
                AppAction.Swipe(startX = sx, startY = sy, endX = ex, endY = ey, durationMs = dur)
            }
            "tap" -> {
                val x = obj.int("x") ?: return null
                val y = obj.int("y") ?: return null
                AppAction.Tap(x = x, y = y)
            }
            "long_press", "longpress", "long-press" -> {
                val x = obj.int("x") ?: return null
                val y = obj.int("y") ?: return null
                val dur = obj.int("durationMs")?.toLong() ?: AppAction.DEFAULT_LONG_PRESS_MS
                AppAction.LongPress(x = x, y = y, durationMs = dur)
            }
            "back" -> AppAction.Back
            "home" -> AppAction.Home
            "recents" -> AppAction.Recents
            "notifications" -> AppAction.Notifications
            "screenshot" -> AppAction.Screenshot
            "open_app", "open", "launch", "open-app" -> {
                val v = obj.string("value")
                    ?: obj.string("package")
                    ?: obj.string("packageName")
                    ?: obj.string("app")
                    ?: return null
                AppAction.OpenApp(query = v.trim())
            }
            "navigate_to", "navigateto", "navigate" -> {
                val lat = obj.double("latitude") ?: obj.double("lat") ?: return null
                val lon = obj.double("longitude") ?: obj.double("lon") ?: return null
                val mode = obj.string("mode")?.let { m ->
                    runCatching { TravelMode.valueOf(m.uppercase()) }.getOrNull()
                } ?: TravelMode.WALK
                val label = obj.string("label")
                AppAction.NavigateTo(latitude = lat, longitude = lon, mode = mode, label = label)
            }
            "search_place", "searchplace", "search" -> {
                val q = (obj.string("query") ?: obj.string("q") ?: return null).trim()
                if (q.isEmpty()) return null
                AppAction.SearchPlace(query = q)
            }
            "show_on_map", "showonmap", "show_map" -> {
                // Accept either a string-shaped GeoJSON or a nested JSON
                // object that we stringify so the receiver sees a single
                // canonical text payload.
                val raw = obj["geometry"] ?: obj["geoJson"] ?: obj["geojson"] ?: return null
                val text = when (raw) {
                    is JsonPrimitive -> raw.contentOrNull()
                    is JsonObject -> raw.toString()
                    is JsonArray -> raw.toString()
                    else -> null
                } ?: return null
                if (text.isBlank()) return null
                AppAction.ShowOnMap(geoJson = text)
            }
            "save_offline_region", "saveofflineregion", "save_region" -> {
                val n = obj.double("north") ?: return null
                val e = obj.double("east") ?: return null
                val s = obj.double("south") ?: return null
                val w = obj.double("west") ?: return null
                val name = obj.string("name")
                AppAction.SaveOfflineRegion(north = n, east = e, south = s, west = w, name = name)
            }
            else -> null
        }
    }

    private fun JsonObject.parseTarget(): ActionTarget? {
        val targetEl = this["target"] ?: return null
        val obj = (targetEl as? JsonObject) ?: return null
        val text = obj.string("text")
        val resourceId = obj.string("id") ?: obj.string("resourceId")
        val boundsObj = obj["bounds"] as? JsonObject
        val bounds = boundsObj?.parseBounds()
        if (text == null && resourceId == null && bounds == null) return null
        return ActionTarget(text = text, resourceId = resourceId, bounds = bounds)
    }

    private fun JsonObject.parseBounds(): ActionBounds? {
        val x = this.int("x") ?: return null
        val y = this.int("y") ?: return null
        val w = this.int("width") ?: this.int("w") ?: return null
        val h = this.int("height") ?: this.int("h") ?: return null
        return ActionBounds(x = x, y = y, width = w, height = h)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.let { if (it.isString) it.content else null }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull()?.toIntOrNull()

    private fun JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.contentOrNull()?.toDoubleOrNull()

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
}
