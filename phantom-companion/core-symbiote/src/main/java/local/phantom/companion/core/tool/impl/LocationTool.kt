package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk
import java.util.Locale

/**
 * R1.1 — rich single-shot location snapshot for [LocationTool].
 *
 * Deliberately NOT `core-ai`'s `LocationFix`: the tool answer carries
 * accuracy/speed/bearing/age/source which the ambient prompt header
 * doesn't need in full. `:app` builds this from MobileSensorAdapter's
 * live GPS sample first, LocationManager last-known second, and the
 * IP-geo chain last (with `source` naming which rung answered).
 */
data class LocationSnapshot(
    /**
     * Null exactly when [refusedUa] is set. A refused position carries no
     * coordinates at all — not 0,0, which is a real place in the Gulf of
     * Guinea and would be spoken as fact by the first consumer that forgot
     * to look at the refusal.
     */
    val lat: Double?,
    val lon: Double?,
    val accuracyM: Float? = null,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val ageMs: Long? = null,
    val source: String,
    /**
     * Set when the consistency gate refused the sky: the position is known to
     * be false (spoof) or absent. The tool must state this instead of a place.
     */
    val refusedUa: String? = null,
    val refusedEn: String? = null,
    /**
     * Set when a position may be stated but not as a certainty — «позиція
     * приблизна», «остання перевірена точка». Travels into the answer so the
     * assistant repeats the doubt instead of dropping it.
     */
    val advisoryUa: String? = null,
)

/**
 * R1.1 — `location.current`: the AI's answer to "де оператор зараз?".
 *
 * Returns coordinates, accuracy, speed, bearing, fix age and (unless
 * `address:false`) a reverse-geocoded coarse address. Backed by lambdas
 * so this module stays free of Android Location/Geocoder types and the
 * tool is unit-testable with plain fakes.
 *
 * Requires [ToolPermission.LOCATION] — gated per-call by
 * ToolPermissionGate like every other tool.
 *
 * Honest degradation (Law 1): no fix ⇒ `ToolResult.Err("no_fix", …)`,
 * a position the consistency gate refused ⇒ `ToolResult.Err("position_refused", …)`
 * carrying the gate's sentence — never a fabricated or a known-false
 * position; a failed reverse-geocode just omits the address field.
 */
class LocationTool(
    private val snapshotProvider: () -> LocationSnapshot?,
    private val reverseGeocode: suspend (lat: Double, lon: Double) -> String? = { _, _ -> null },
) : Tool {

    override val id = "location.current"

    override val description =
        "Точна поточна геолокація оператора: координати, точність (м), швидкість, напрямок, вік фіксу і зворотно-геокодована адреса."

    override val argsSchema: JsonObject = buildJsonObject {
        put("address", JsonPrimitive("boolean?"))
    }

    override val permissions: Set<ToolPermission> = setOf(ToolPermission.LOCATION)

    override val risk: ToolRisk = ToolRisk.READ

    override val streaming: Boolean = false

    override val manifest = FeatureManifest(
        id = "tool.location",
        tier = 1,
        displayName = "Локація",
        erodesOperatorAgency = false,
        exfiltratesPrivate = false,
        actsWithoutConsent = false,
        abusableAgainstWeak = false,
        sunsetAfterDaysIdle = 90,
        surfaces = setOf(FeatureManifest.Surface.STREAM),
    )

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val startMs = System.currentTimeMillis()

        val snapshot = runCatching { snapshotProvider() }.getOrNull()
            ?: return ToolResult.Err(
                code = "no_fix",
                message = "Де ти — поки не знаю. Можливо, телефону не дозволено знати місце або " +
                    "визначення місця вимкнене. Надворі чи біля вікна телефон знайде тебе за хвилину.",
                durationMs = System.currentTimeMillis() - startMs,
            )

        // PRECISION ENGINE §4 — the gate said this position must not be stated.
        // Answering with an Err is the point: a caught spoof that still hands
        // the assistant coordinates gets read out as fact.
        snapshot.refusedUa?.let { why ->
            return ToolResult.Err(
                code = "position_refused",
                message = if (snapshot.refusedEn != null) "$why / ${snapshot.refusedEn}" else why,
                durationMs = System.currentTimeMillis() - startMs,
            )
        }
        val lat = snapshot.lat
        val lon = snapshot.lon
        if (lat == null || lon == null) {
            return ToolResult.Err(
                code = "no_fix",
                message = "Де ти — поки не знаю. Перевір, чи телефону дозволено знати місце і чи " +
                    "визначення місця увімкнене.",
                durationMs = System.currentTimeMillis() - startMs,
            )
        }

        val wantAddress = args["address"]?.jsonPrimitive?.booleanOrNull ?: true
        val address = if (wantAddress) {
            runCatching { reverseGeocode(lat, lon) }.getOrNull()
        } else null

        val ageS = snapshot.ageMs?.let { it / 1000 }
        val stale = ageS != null && ageS > STALE_AFTER_S
        val digits = decimalsFor(snapshot.accuracyM)

        val structured = buildJsonObject {
            put("lat", lat)
            put("lon", lon)
            snapshot.accuracyM?.let { put("accuracy_m", it) }
            snapshot.speedMps?.let { put("speed_mps", it) }
            snapshot.bearingDeg?.let { put("bearing_deg", it) }
            ageS?.let { put("age_s", it) }
            put("stale", stale)
            put("source", snapshot.source)
            // How many decimals the fix earns — a consumer that reprints the
            // pair must not add digits the accuracy never supported.
            put("printable_decimals", digits)
            snapshot.advisoryUa?.let { put("advisory", it) }
            address?.let { put("address", it) }
            put("address_requested", wantAddress)
            if (wantAddress && address == null) put("address_note", "зворотний геокодер не відповів")
        }

        val human = buildString {
            snapshot.advisoryUa?.let { append(it).append(". ") }
            append(String.format(Locale.US, "%.${digits}f, %.${digits}f", lat, lon))
            address?.let { append(" — ").append(it) }
            val acc = snapshot.accuracyM
            if (snapshot.advisoryUa == null && (acc == null || acc > ROUGH_AFTER_M)) {
                append(". Місце приблизне")
            }
            snapshot.speedMps?.let { append(String.format(Locale.US, "; швидкість %.0f км/год", it * 3.6)) }
            if (stale) append("; це було ${ageS!! / 60} хв тому — ти міг зрушити з місця")
        }

        return ToolResult.Ok(
            output = human,
            structured = structured,
            durationMs = System.currentTimeMillis() - startMs,
        )
    }

    private companion object {
        const val STALE_AFTER_S = 120L
        const val ROUGH_AFTER_M = 100f

        /**
         * Five decimals is ~1 m. Printing them from a ±2 km IP-geo fix is a lie
         * told in exact digits — the same rule the handover card applies to
         * MGRS, applied to the decimal pair.
         */
        fun decimalsFor(accuracyM: Float?): Int = when {
            accuracyM == null -> 3
            accuracyM <= 5f -> 5
            accuracyM <= 50f -> 4
            accuracyM <= 500f -> 3
            else -> 2
        }
    }
}
