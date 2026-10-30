package local.phantom.companion.feature.stream.ui.map

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.data.country.readAsset

/**
 * СИРЕНА-2 — the KEYLESS typed feed: siren.pp.ua, an open mirror of the
 * official ukrainealarm API. What ubilling cannot say, this can, without a
 * token: the alert's TYPE (AIR / ARTILLERY / URBAN_FIGHTS / CHEMICAL /
 * NUCLEAR) and the raion/hromada-level rows with their own start stamps.
 *
 * The feed returns ONLY regions currently under alert, identified by
 * ukrainealarm region ids — so the bundled [RegionIndex] asset (generated
 * from the same API's /regions tree) maps every id to its oblast key in
 * UBILLING's naming, offline. Rows whose id the index does not know are
 * dropped, never guessed (Law 1).
 */

/** One region id resolved: which oblast it belongs to, its display name. */
data class RegionRef(
    val oblast: String,
    val name: String,
    /** True only for real oblast-level entries («м. Київ», «Київська область»…).
     *  The city-hromada oddities (м. Харків, м. Запоріжжя) are deliberately
     *  NOT state-level: their alarm must never paint the whole oblast. */
    val stateLevel: Boolean,
)

/** The active pack's region tree, loaded once from its own asset namespace. */
class RegionIndex private constructor(
    private val regions: Map<String, RegionRef>,
) {
    fun refOf(id: String): RegionRef? = regions[id]

    companion object {
        /** Нема пака — жоден id не резолвиться, і жоден рядок не вигадується. */
        private val NONE = RegionIndex(emptyMap())

        // @Volatile — same law as OblastShapes: the warm-up thread's parse
        // must be visible to the UI thread or it parses again on main. Keyed
        // by asset path for the same reason OblastShapes is.
        @Volatile
        private var cached: Pair<String, RegionIndex>? = null

        /** Double-checked — same law as [OblastShapes.load]: a loser of the
         *  warm-up race waits for that parse rather than repeating it. */
        fun load(context: Context): RegionIndex {
            val pack = local.phantom.companion.core.data.country.CountryPackRegistry.current()
            val asset = pack?.regionIndexAsset ?: return NONE
            cached?.let { (path, index) -> if (path == asset) return index }
            synchronized(this) {
                cached?.let { (path, index) -> if (path == asset) return index }
                // Через пак, не через `context.assets`: у завантаженого пака
                // дерево регіонів лежить у власному каталозі, а не у збірці.
                val raw = pack.readAsset(context, asset) ?: "{}"
                val parsed = runCatching { parse(raw) }.getOrNull() ?: emptyMap()
                return RegionIndex(parsed).also { cached = asset to it }
            }
        }

        private fun parse(raw: String): Map<String, RegionRef> {
            val root = liveJson.parseToJsonElement(raw).jsonObject["regions"]?.jsonObject
                ?: return emptyMap()
            return root.entries.mapNotNull { (id, el) ->
                val o = el.jsonObject
                val oblast = o["o"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = o["n"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                id to RegionRef(oblast, name, stateLevel = o.containsKey("st"))
            }.toMap()
        }
    }
}

/** ukrainealarm alert `type` → [ThreatType]; null = a row not worth an alarm (INFO). */
internal fun sirenThreat(type: String?): ThreatType? = when (type) {
    "AIR" -> ThreatType.UNKNOWN // a generic air-raid siren, honestly untyped
    "ARTILLERY" -> ThreatType.ARTILLERY
    "URBAN_FIGHTS" -> ThreatType.URBAN
    "CHEMICAL" -> ThreatType.CHEMICAL
    "NUCLEAR" -> ThreatType.NUCLEAR
    else -> null
}

/** One oblast's view of the siren feed after id-resolution. */
data class SirenOblast(
    /** A true oblast-level alert row exists (not just raions). */
    val stateActive: Boolean,
    /** Worst typed threat among oblast-level rows; UNKNOWN for plain AIR. */
    val stateThreat: ThreatType,
    /** Earliest oblast-level alert start, epoch ms. */
    val startedEpochMs: Long?,
    val districts: List<DistrictAlert>,
)

/**
 * Pure parse of the /api/v3/alerts payload → oblast key → [SirenOblast].
 * Split from the client so a unit test needs no network.
 */
internal fun parseSirenAlerts(
    body: String,
    refOf: (String) -> RegionRef?,
): Map<String, SirenOblast>? = runCatching {
    data class Acc(
        var stateActive: Boolean = false,
        var stateThreat: ThreatType = ThreatType.UNKNOWN,
        var startedEpochMs: Long? = null,
        val districts: MutableList<DistrictAlert> = mutableListOf(),
    )

    val acc = mutableMapOf<String, Acc>()
    for (el in liveJson.parseToJsonElement(body).jsonArray) {
        val row = el.jsonObject
        val id = row["regionId"]?.jsonPrimitive?.contentOrNull ?: continue
        val ref = refOf(id) ?: continue
        val alerts = row["activeAlerts"]?.jsonArray.orEmpty()

        var worst: ThreatType? = null
        var earliest: Long? = null
        for (alertEl in alerts) {
            val alert = alertEl.jsonObject
            val threat = sirenThreat(alert["type"]?.jsonPrimitive?.contentOrNull) ?: continue
            if (worst == null || threat.meta.severity > worst.meta.severity) worst = threat
            parseIsoEpochMsLenient(alert["lastUpdate"]?.jsonPrimitive?.contentOrNull)?.let { t ->
                if (earliest == null || t < earliest!!) earliest = t
            }
        }
        val threat = worst ?: continue // INFO-only or empty row — not an alarm

        val a = acc.getOrPut(ref.oblast) { Acc() }
        if (ref.stateLevel) {
            a.stateActive = true
            if (threat.meta.severity > a.stateThreat.meta.severity) a.stateThreat = threat
            if (earliest != null && (a.startedEpochMs == null || earliest!! < a.startedEpochMs!!)) {
                a.startedEpochMs = earliest
            }
        } else {
            a.districts += DistrictAlert(ref.name, threat)
        }
    }
    acc.mapValues { (_, a) ->
        SirenOblast(a.stateActive, a.stateThreat, a.startedEpochMs, a.districts.distinct())
    }
}.getOrNull()

/** ISO-8601 with or without offset/fraction («2026-07-18T13:48:21.713182Z»). */
internal fun parseIsoEpochMsLenient(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return runCatching { java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
        .recoverCatching { java.time.Instant.parse(raw).toEpochMilli() }
        .getOrNull()
}

/** The live client. Returns null on network/parse failure (caller keeps last). */
internal class SirenClient(
    private val refOf: (String) -> RegionRef?,
    private val endpoint: String,
    private val userAgent: String,
) {
    suspend fun fetch(): Map<String, SirenOblast>? = withContext(Dispatchers.IO) {
        val body = httpGet(endpoint, userAgent) ?: return@withContext null
        parseSirenAlerts(body, refOf)
    }
}
