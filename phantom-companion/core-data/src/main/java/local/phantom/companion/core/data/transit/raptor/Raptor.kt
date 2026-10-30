package local.phantom.companion.core.data.transit.raptor

/**
 * MULTIMODAL CONTINUUM — the seeded RAPTOR solver. Pure: no Android, no
 * network, no clock. Everything it knows arrives as arguments, which is
 * why it can be tested to the second.
 *
 * ── Why "seeded" is the whole trick ──────────────────────────────────
 * Textbook RAPTOR departs from one origin stop at one time. Every transit
 * app then bolts multimodality on afterwards — a park-and-ride database, a
 * separate car router, a fusion layer that stitches the two and prays the
 * seam is optimal. It usually isn't: the seam is chosen by a heuristic
 * ("nearest lot to origin", "biggest lot on the corridor") before the
 * timetable has had any say.
 *
 * We invert it. Round 0 is seeded with a *set* of [Seed]s — "you can be at
 * stop S, at time T, having already paid access leg A". A seed can be a
 * two-minute walk to the tram stop, or a twenty-minute drive to a metro
 * station at the edge of town with the car left behind. The solver does not
 * know, and must not know, what a car is: it sees only stops and times.
 *
 * The consequence is the point. **Where to park stops being an input and
 * becomes an output.** Seed every plausible car-drop, and RAPTOR — which is
 * exact, not a heuristic — reports the one that actually gets the operator
 * there soonest, because it explores them all simultaneously and keeps the
 * best. No park-and-ride database, no seam heuristic, no fusion layer. The
 * transit core stays pure and tiny, and multimodality is a *boundary
 * condition* rather than an algorithm.
 *
 * Symmetrically, egress is a set: every stop carries a walk-to-destination
 * cost, so "get off one stop early because it's a shorter walk from there"
 * falls out for free instead of being special-cased.
 *
 * ── What it returns ──────────────────────────────────────────────────
 * One journey per round that improves on all previous rounds. Round k means
 * "at most k-1 transfers", so the result set is Pareto-optimal in (arrival
 * time, number of transfers) *by construction* — the two axes an operator
 * actually trades off. That is a free gift of the round structure; a
 * Dijkstra would have to be run repeatedly to fake it.
 */
class Raptor(private val tt: Timetable) {

    /** "You can be at [stop] at [readySec], having paid [seedId] to get there." */
    data class Seed(val stop: Int, val readySec: Int, val seedId: Int)

    /** "From [stop], the destination is [seconds] of walking away." */
    data class Egress(val stop: Int, val seconds: Int)

    /** How we reached a stop — enough to rebuild the journey backwards. */
    sealed interface Hop {
        data class Board(
            val route: Int, val trip: Int,
            val boardPos: Int, val alightPos: Int,
        ) : Hop
        data class Walk(val fromStop: Int, val seconds: Int) : Hop
        data class Origin(val seedId: Int) : Hop
    }

    /** One Pareto-optimal answer: fewer [transfers] or an earlier [arrivalSec]. */
    data class Journey(
        val seedId: Int,
        /** Stop the last transit/foot leg drops us at (before egress). */
        val egressStop: Int,
        val egressSeconds: Int,
        /** Arrival at the destination, incl. the egress walk. */
        val arrivalSec: Int,
        val transfers: Int,
        /** Chronological. Walk legs between transit legs are transfers. */
        val hops: List<Hop>,
    )

    /**
     * @param boardSlackSec time to get off a vehicle and reach the next
     *   platform *at the same stop*. Not applied after a footpath (that
     *   walk already spent the time) nor to a seed (access already did).
     * @param maxRounds rounds, so at most `maxRounds - 1` transfers. Four
     *   transfers is already more than any operator will accept; beyond
     *   that we are burning cycles to produce answers nobody takes.
     */
    fun plan(
        seeds: List<Seed>,
        egress: List<Egress>,
        boardSlackSec: Int = 45,
        maxRounds: Int = 5,
    ): List<Journey> {
        if (seeds.isEmpty() || egress.isEmpty()) return emptyList()

        val n = tt.stopCount
        val egressAt = IntArray(n) { INF }
        for (e in egress) if (e.seconds < egressAt[e.stop]) egressAt[e.stop] = e.seconds

        // best[s] — earliest arrival at s over *all* rounds so far. Used only
        // for pruning: a label no better than this can never lead anywhere new.
        val best = IntArray(n) { INF }
        // ready[s] — when we could board at s, per round (arrival + slack owed).
        var prevArr = IntArray(n) { INF }
        var prevReady = IntArray(n) { INF }
        val prevHop = arrayOfNulls<Hop>(n)

        for (s in seeds) {
            if (s.readySec < prevArr[s.stop]) {
                prevArr[s.stop] = s.readySec
                prevReady[s.stop] = s.readySec   // access already paid; no slack
                prevHop[s.stop] = Hop.Origin(s.seedId)
                best[s.stop] = s.readySec
            }
        }

        // Journeys are rebuilt from the round they were found in, so each
        // round's parent pointers must survive to the end of the query.
        val hopsByRound = ArrayList<Array<Hop?>>()
        hopsByRound += prevHop.copyOf()

        var marked = HashSet<Int>(seeds.map { it.stop })
        var bestAtTarget = INF
        val out = ArrayList<Journey>()

        // Seeds themselves may already be at the destination (walk-only answer);
        // the caller handles that case — RAPTOR only reports transit journeys.

        for (round in 1..maxRounds) {
            if (marked.isEmpty()) break

            // ── 1. which routes are worth scanning, and from where ──
            // For each route touched by a marked stop, remember the *earliest*
            // position we could board it at — scanning from later is pointless.
            val queue = HashMap<Int, Int>()
            for (s in marked) {
                var i = tt.stopRoutesOffset[s]
                val end = tt.stopRoutesOffset[s + 1]
                while (i < end) {
                    val r = tt.stopRoutes[i]
                    val pos = tt.stopRoutePos[i]
                    val had = queue[r]
                    if (had == null || pos < had) queue[r] = pos
                    i++
                }
            }
            marked = HashSet()

            val curArr = prevArr.copyOf()
            val curReady = prevReady.copyOf()
            val curHop = arrayOfNulls<Hop>(n)

            // ── 2. ride every queued route once, in stop order ──
            for ((route, fromPos) in queue) {
                val count = tt.routeStopCount[route]
                var trip = -1
                var boardPos = -1

                for (pos in fromPos until count) {
                    val stop = tt.stopAt(route, pos)

                    // Riding: can we get off here better than anything known?
                    if (trip >= 0) {
                        val arr = tt.arrival(route, trip, pos)
                        // Target pruning: a label that cannot beat the best known
                        // arrival at the destination is dead — don't propagate it.
                        val bound = minOf(best[stop], bestAtTarget)
                        if (arr < bound) {
                            curArr[stop] = arr
                            curReady[stop] = arr + boardSlackSec
                            best[stop] = arr
                            curHop[stop] = Hop.Board(route, trip, boardPos, pos)
                            marked += stop

                            val eg = egressAt[stop]
                            if (eg != INF && arr + eg < bestAtTarget) bestAtTarget = arr + eg
                        }
                    }

                    // Boarding: if we were here earlier (previous round) than the
                    // trip we're on departs, we can catch an earlier one.
                    val ready = prevReady[stop]
                    if (ready != INF && (trip < 0 || ready <= tt.departure(route, trip, pos))) {
                        val t = tt.earliestTrip(route, pos, ready)
                        if (t >= 0 && t != trip) {
                            trip = t
                            boardPos = pos
                        }
                    }
                }
            }

            // ── 3. footpaths — a transfer on foot costs a round, as it should ──
            // Snapshot: a footpath must extend a *ride* from this round, not
            // chain foot→foot→foot (transfers.txt is already transitively closed
            // by the builder, so chaining would only invent worse duplicates).
            for (stop in marked.toList()) {
                val arr = curArr[stop]
                var i = tt.transferOffset[stop]
                val end = tt.transferOffset[stop + 1]
                while (i < end) {
                    val target = tt.transferTarget[i]
                    val at = arr + tt.transferSeconds[i]
                    if (at < minOf(best[target], bestAtTarget)) {
                        curArr[target] = at
                        curReady[target] = at        // the walk *is* the slack
                        best[target] = at
                        curHop[target] = Hop.Walk(stop, tt.transferSeconds[i])
                        marked += target

                        val eg = egressAt[target]
                        if (eg != INF && at + eg < bestAtTarget) bestAtTarget = at + eg
                    }
                    i++
                }
            }

            hopsByRound += curHop.copyOf()
            prevArr = curArr
            prevReady = curReady

            // ── 4. harvest: did this round produce a better arrival? ──
            var bestStop = -1
            var bestArrival = INF
            for (e in egress) {
                val a = curArr[e.stop]
                if (a == INF) continue
                // Only a label *created this round* is a new journey; an older
                // one would rebuild into a journey we already emitted.
                if (curHop[e.stop] == null) continue
                val total = a + e.seconds
                if (total < bestArrival) {
                    bestArrival = total
                    bestStop = e.stop
                }
            }
            if (bestStop >= 0 && (out.isEmpty() || bestArrival < out.last().arrivalSec)) {
                val hops = rebuild(bestStop, round, hopsByRound)
                val rides = hops?.count { it is Hop.Board } ?: 0
                // A journey with no boarding is not a transit journey — the
                // caller answers "just walk" itself, and far better than we could.
                if (hops != null && rides > 0 && hops.first() is Hop.Origin) {
                    out += Journey(
                        seedId = (hops.first() as Hop.Origin).seedId,
                        egressStop = bestStop,
                        egressSeconds = egressAt[bestStop],
                        arrivalSec = bestArrival,
                        transfers = rides - 1,
                        hops = hops,
                    )
                }
            }
        }
        return out
    }

    /**
     * Walk the parent pointers back from [stop] at [round] to a seed.
     *
     * The round bookkeeping is asymmetric and it matters. A **Board** in
     * round k was built on a label from round k-1, so rebuilding one steps
     * a round *down*. A **Walk** in round k was built on a ride from that
     * same round k (the footpath phase runs after the ride phase), so it
     * stays put. Stepping down on a footpath would skip a round and lose
     * the ride that fed it.
     *
     * A stop whose label survived untouched from an earlier round has a
     * null hop in this one — scan down until the round that actually
     * created it.
     */
    private fun rebuild(stop: Int, round: Int, hopsByRound: List<Array<Hop?>>): List<Hop>? {
        val chain = ArrayList<Hop>()
        var s = stop
        var k = round
        while (true) {
            var hop = hopsByRound[k][s]
            while (hop == null && k > 0) {
                k--
                hop = hopsByRound[k][s]
            }
            when (hop) {
                null -> return null              // never reached a seed
                is Hop.Origin -> { chain += hop; return chain.asReversed() }
                is Hop.Board -> {
                    chain += hop
                    s = tt.stopAt(hop.route, hop.boardPos)
                    if (k == 0) return null      // a board must rest on an earlier round
                    k--
                }
                is Hop.Walk -> {
                    chain += hop
                    s = hop.fromStop             // same round: the ride that fed the walk
                }
            }
        }
    }

    private companion object {
        const val INF = Int.MAX_VALUE / 2
    }
}
