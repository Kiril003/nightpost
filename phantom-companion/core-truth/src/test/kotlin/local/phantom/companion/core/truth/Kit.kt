package local.phantom.companion.core.truth

const val MIN = 60_000L
const val HOUR = 60 * MIN
const val DAY = 24 * HOUR
const val NOW = 1_000 * DAY

val HERE = CellId("881e2a1a2bfffff")
val THERE = CellId("881e2a1a3bfffff")

fun key(kind: FactKind, cell: CellId = HERE, obj: String? = "osm:node/1") = FactKey(Place(cell, obj), kind)

fun elder(id: String, net: String = "net-$id", inviter: String? = null) =
    Voter(VoterId(id), NOW - 400 * DAY, net, inviter?.let(::VoterId))

fun fresh(id: String, createdAtMs: Long, net: String = "net-$id", inviter: String? = null) =
    Voter(VoterId(id), createdAtMs, net, inviter?.let(::VoterId))

fun saw(voter: Voter, key: FactKey, reading: Reading, atMs: Long, onSite: Boolean = true) =
    Observation(voter.id, key, reading, atMs, if (onSite) Presence(key.place.cell, atMs - 10 * MIN, atMs + MIN) else null)

fun verdictOf(
    key: FactKey,
    obs: List<Observation>,
    voters: List<Voter>,
    nowMs: Long = NOW,
    reputation: (VoterId) -> Double = { Reputation.NEWCOMER },
) = Truth.verdict(key, obs, voters.associateBy { it.id }, reputation, nowMs)
