// Вектори дзеркала: входи й числа, пораховані САМИМ `:core-truth`.
// Запуск — scripts/truth-vectors.sh; результат — tests/vectors/truth_vectors.json,
// який читає tests/test_truth_mirror.py.
package local.phantom.companion.core.truth.vectors

import local.phantom.companion.core.truth.*
import kotlin.random.Random

private const val MIN = 60_000L
private const val HOUR = 60 * MIN
private const val DAY = 24 * HOUR
private const val NOW = 1_000 * DAY

private fun q(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) when {
        c == '"' -> sb.append("\\\"")
        c == '\\' -> sb.append("\\\\")
        c == '\n' -> sb.append("\\n")
        c < ' ' -> sb.append(String.format("\\u%04x", c.code))
        else -> sb.append(c)
    }
    return sb.append('"').toString()
}

private fun obj(vararg kv: Pair<String, Any?>): String =
    kv.joinToString(",", "{", "}") { (k, v) -> q(k) + ":" + enc(v) }

private fun enc(v: Any?): String = when (v) {
    null -> "null"
    is String -> q(v)
    is Boolean, is Int, is Long -> v.toString()
    is Double -> { require(v.isFinite()); v.toString() }
    is List<*> -> v.joinToString(",", "[", "]") { enc(it) }
    is Raw -> v.json
    else -> error("no encoder for ${v::class}")
}

private class Raw(val json: String)

private fun voterJson(v: Voter) = Raw(obj("id" to v.id.value, "created_at_ms" to v.createdAtMs, "network_id" to v.networkId, "invited_by" to v.invitedBy?.value))

private fun obsJson(o: Observation) = Raw(
    obj(
        "voter" to o.voter.value, "cell" to o.key.place.cell.value, "object" to o.key.place.objectId,
        "kind" to o.key.kind.name, "reading" to o.reading.wire, "at_ms" to o.atMs,
        "presence" to o.presence?.let { Raw(obj("cell" to it.cell.value, "from_ms" to it.fromMs, "to_ms" to it.toMs)) },
    ),
)

private fun verdictJson(v: Verdict) = Raw(
    obj(
        "state" to v.state.name, "reading" to v.reading?.wire, "probability" to v.probability, "mass" to v.mass,
        "supporters" to v.supporters, "last_support_ms" to v.lastSupportAtMs, "newest_ms" to v.newestAtMs,
        "dissent" to v.dissent, "contested" to v.contested,
    ),
)

private val CELLS = listOf(CellId("881e2a1a2bfffff"), CellId("881e2a1a3bfffff"))

private fun randomWorld(rnd: Random, kind: FactKind, span: Long): Triple<List<Voter>, Map<VoterId, Double>, List<Observation>> {
    val voters = (0 until rnd.nextInt(1, 14)).map { i ->
        Voter(
            VoterId("v$i"),
            NOW - rnd.nextLong(0, 60 * DAY),
            if (rnd.nextInt(5) == 0) null else "net-${rnd.nextInt(4)}",
            if (rnd.nextBoolean()) VoterId(if (rnd.nextInt(4) == 0) "v${rnd.nextInt(3)}" else "inv-${rnd.nextInt(3)}") else null,
        )
    }
    val rep = voters.associate { it.id to rnd.nextDouble(0.05, Reputation.CEILING) }
    val key = FactKey(Place(CELLS[0], "osm:node/1"), kind)
    val obs = voters.flatMap { v ->
        (0 until rnd.nextInt(1, 4)).map {
            val at = NOW - rnd.nextLong(0, span)
            val presence = when (rnd.nextInt(4)) {
                0 -> null
                1 -> Presence(CELLS[1], at - 10 * MIN, at + MIN)
                2 -> Presence(CELLS[0], at - 40 * MIN, at - 20 * MIN)
                else -> Presence(CELLS[0], at - 10 * MIN, at + MIN)
            }
            Observation(v.id, key, kind.readings.random(rnd), at, presence)
        }
    }.shuffled(rnd)
    return Triple(voters, rep, obs)
}

fun main(args: Array<String>) {
    val out = ArrayList<Pair<String, Any?>>()
    val rnd = Random(2409)

    out += "kinds" to FactKind.entries.map {
        Raw(obj("name" to it.name, "wire" to it.wire, "half_life_ms" to it.halfLifeMs, "readings" to it.readings.map { r -> r.wire },
            "positive" to it.positive?.wire, "remote_factor" to it.remoteFactor, "importance" to it.importance,
            "embargo_during_alert" to it.embargoDuringAlert))
    }
    out += "refusals" to Refusal.entries.map { Raw(obj("name" to it.name, "uk" to it.uk, "en" to it.en)) }
    out += "max_observations_per_hour" to TruthWire.MAX_OBSERVATIONS_PER_HOUR

    out += "weights" to (0 until 300).map {
        val s = Standing(rnd.nextDouble(0.0, 40.0).let { if (rnd.nextInt(5) == 0) 0.0 else it }, rnd.nextDouble(0.0, 40.0).let { if (rnd.nextInt(5) == 0) 0.0 else it }, NOW - rnd.nextLong(0, 400 * DAY))
        val now = NOW
        Raw(obj("confirmed" to s.confirmed, "refuted" to s.refuted, "as_of_ms" to s.asOfMs, "now_ms" to now, "weight" to Reputation.weight(s.at(now))))
    }

    out += "age" to (0 until 100).map {
        val v = if (it == 0) null else Voter(VoterId("a"), NOW - rnd.nextLong(-DAY, 40 * DAY))
        Raw(obj("created_at_ms" to v?.createdAtMs, "at_ms" to NOW, "factor" to Reputation.ageFactor(v, NOW)))
    }

    out += "circle_mass" to (0 until 100).map {
        val ws = (0 until rnd.nextInt(0, 12)).map { rnd.nextDouble(0.0, 3.0) }
        Raw(obj("weights" to ws, "mass" to Independence.circleMass(ws)))
    }

    out += "circles" to (0 until 100).map {
        val (voters, _, _) = randomWorld(rnd, FactKind.SHOP, HOUR)
        val c = Independence.circles(voters, NOW)
        Raw(obj("voters" to voters.map(::voterJson), "as_of_ms" to NOW, "roots" to voters.map { c.getValue(it.id).value }))
    }

    out += "verdicts" to (0 until 150).map {
        val kind = FactKind.entries.random(rnd)
        val span = (kind.halfLifeMs * rnd.nextDouble(0.1, 3.0)).toLong()
        val (voters, rep, obs) = randomWorld(rnd, kind, span)
        val key = obs.first().key
        val now = NOW + rnd.nextLong(-kind.halfLifeMs / 4, kind.halfLifeMs)
        val v = Truth.verdict(key, obs, voters.associateBy { it.id }, { rep.getValue(it) }, now)
        Raw(obj(
            "voters" to voters.map(::voterJson),
            "reputation" to voters.map { Raw(obj("id" to it.id.value, "w" to rep.getValue(it.id))) },
            "observations" to obs.map(::obsJson),
            "cell" to key.place.cell.value, "object" to key.place.objectId, "kind" to kind.name,
            "now_ms" to now, "verdict" to verdictJson(v),
            "claims" to listOf("confirm", "deny").map { c ->
                val claim = if (c == "confirm") Claim.Confirm else Claim.Deny
                Raw(obj("claim" to c, "reading" to claim.toReading(kind, v)?.wire))
            },
        ))
    }

    out += "settle" to (0 until 50).map {
        val kind = FactKind.entries.random(rnd)
        val (voters, _, obs) = randomWorld(rnd, kind, kind.halfLifeMs * 2)
        val ledger = ReputationLedger()
        val now = NOW + kind.halfLifeMs
        val outcomes = ledger.settle(obs, voters.associateBy { it.id }, now)
        Raw(obj(
            "voters" to voters.map(::voterJson),
            "observations" to obs.map(::obsJson),
            "now_ms" to now,
            "outcomes" to outcomes.map { (o, r) -> Raw(obj("index" to obs.indexOf(o), "outcome" to r.name)) },
            "standings" to voters.map { v ->
                val s = ledger.standing(v.id, now)
                Raw(obj("id" to v.id.value, "confirmed" to s.confirmed, "refuted" to s.refuted, "weight" to ledger.weight(v.id, now)))
            },
        ))
    }

    val phrases = listOf(
        "біля школи працює ППО", "приліт за магазином", "тут стоїть танк", "колона бтр іде на схід",
        "військові в дворі", "позиції за парком", "уламки ракети на дорозі", "прильот в соседний дом",
        "здесь пво", "П П О працює", "п.п.о", "air defense near the mall", "air defence", "troops on the bridge",
        "shahed hit the depot", "вибух на заводі", "блокпост на виїзді", "тцк на зупинці", "бпла над районом", "ппo",
        "всупереч графіку відкрили раніше", "магазин Зеніт працює до 20:00", "позавчора теж було зачинено",
        "вхід з двору, ключ у консьєржа", "генератор гуде, але світло є", "черга до каси", "ёлка у входа",
        "ТАНК", "т а н к", "рапорт", "   ", "", "x".repeat(280), "x".repeat(281), "😀".repeat(140), "😀".repeat(141),
    )
    val kinds = FactKind.entries.map { it.wire } + listOf(
        "military.position", "air_defense", "air-defense.active", "strike.site", "troops", "drone.sighting",
        "ппо", "приліт", "позиції_військ", "tcc.patrol", "cafe.vibe", " SHOP.WORKING ", "shop", "", "impactful",
    )
    val readings = Reading.entries.map { it.wire } + listOf("OPEN", " on ", "maybe", "")
    out += "intake" to (0 until 400).map {
        val kind = kinds.random(rnd)
        val reading = if (rnd.nextInt(3) == 0) readings.random(rnd) else (FactKind.byWire(kind.trim().lowercase())?.readings?.random(rnd)?.wire ?: readings.random(rnd))
        val cell = if (rnd.nextInt(12) == 0) "  " else CELLS[0].value
        val objectId = when (rnd.nextInt(6)) { 0 -> null; 1 -> "osm:node/танк"; else -> "osm:node/${rnd.nextInt(1000)}" }
        val note = if (rnd.nextBoolean()) null else phrases.random(rnd)
        val r = IntakeGate.check(Submission(kind, reading, Place(CellId(cell), objectId), note))
        Raw(obj(
            "kind" to kind, "reading" to reading, "cell" to cell, "object" to objectId, "note" to note,
            "refused" to (r as? Intake.Refused)?.reason?.name,
            "accepted_kind" to (r as? Intake.Accepted)?.kind?.name,
            "accepted_reading" to (r as? Intake.Accepted)?.reading?.wire,
            "note_for_moderation" to (r as? Intake.Accepted)?.noteForModeration,
        ))
    } + phrases.map { p -> Raw(obj("text" to p, "forbidden" to IntakeGate.forbiddenText(p))) }

    out += "band" to (listOf(-3, 0, 1, 4, 5, 6, 9, 10, 99, 100, 101, 109, 110, 1234) + (0 until 50).map { rnd.nextInt(0, 3000) })
        .map { Raw(obj("n" to it, "band" to ImpactCounter.band(it))) }

    out += "embargo" to (0 until 200).map {
        val kind = FactKind.entries.random(rnd)
        val at = NOW - rnd.nextLong(0, 6 * HOUR)
        val alerts = (0 until rnd.nextInt(0, 3)).map {
            val s = NOW - rnd.nextLong(0, 8 * HOUR)
            AlertWindow(s, if (rnd.nextInt(4) == 0) null else s + rnd.nextLong(0, 3 * HOUR))
        }
        val o = Observation(VoterId("x"), FactKey(Place(CELLS[0]), kind), kind.readings.first(), at)
        Raw(obj(
            "kind" to kind.name, "at_ms" to at,
            "alerts" to alerts.map { a -> Raw(obj("start_ms" to a.startMs, "end_ms" to a.endMs)) },
            "release_ms" to Embargo.releaseAtMs(o, alerts),
        ))
    }

    val json = out.joinToString(",\n", "{\n", "\n}\n") { (k, v) -> q(k) + ":" + enc(v) }
    java.io.File(args[0]).writeText(json)
}
