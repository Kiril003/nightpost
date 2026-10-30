package local.phantom.companion.core.gnss

/**
 * Цінне в атаці — те, що було ДО неї: з якої C/N0 стартував генератор, чи
 * передувало підміні глушіння. Тому епохи пишуться в кільце постійно, а на
 * диск лягають лише при спрацюванні — разом із хвостом попередніх.
 */
class GnssBlackBox(
    private val preTriggerEpochs: Int = PRE_TRIGGER,
    private val holdOffEpochs: Int = HOLD_OFF,
    private val maxRecords: Int = MAX_RECORDS,
    private val sink: (String) -> Unit,
) {

    private val ring = ArrayDeque<Record>()
    private var armed = false
    private var cleanStreak = 0
    private var written = 0
    private var sessionId: String? = null

    val isArmed: Boolean get() = armed
    val recordsWritten: Int get() = written

    fun offer(epoch: GnssEpoch, integrity: GnssIntegrity, networkDivergenceKm: Double? = null): Boolean {
        val rec = Record(epoch, integrity, networkDivergenceKm)

        if (!armed) {
            if (!integrity.compromised) {
                push(rec)
                return false
            }

            // Тригер свідомо не йде в кільце: він витіснив би найстарішу епоху
            // вікна, і preTriggerEpochs означало б на одну менше, ніж обіцяє.
            armed = true
            cleanStreak = 0
            sessionId = "bb-${epoch.elapsedRealtimeNanos}"
            emit(header(rec))
            ring.forEach { emit(line(it)) }
            ring.clear()
            emit(line(rec))
            return true
        }

        // Не з першої чистої епохи: підміна миготить, і серія start/stop
        // розрізала б один інцидент на десяток недоказових уламків.
        cleanStreak = if (integrity.compromised) 0 else cleanStreak + 1
        emit(line(rec))
        if (cleanStreak >= holdOffEpochs || written >= maxRecords) {
            armed = false
            cleanStreak = 0
            sessionId = null
        }
        return true
    }

    fun forceArm(epoch: GnssEpoch) {
        if (armed) return
        armed = true
        cleanStreak = 0
        sessionId = "manual-${epoch.elapsedRealtimeNanos}"
        emit(header(Record(epoch, CLEAN, null)))
        ring.forEach { emit(line(it)) }
        ring.clear()
    }

    fun reset() {
        ring.clear()
        armed = false
        cleanStreak = 0
        written = 0
        sessionId = null
    }

    private fun push(rec: Record) {
        ring.addLast(rec)
        while (ring.size > preTriggerEpochs) ring.removeFirst()
    }

    private fun emit(s: String) {
        if (written >= maxRecords) return
        sink(s)
        written++
    }

    private fun header(rec: Record): String = buildString {
        append("""{"t":"header","session":"""")
        append(sessionId)
        append("""","schema":""").append(SCHEMA)
        append(""","preTrigger":""").append(ring.size)
        append(""","trigger":{"jam":""").append(num(rec.integrity.jammingScore))
        append(""","spoof":""").append(num(rec.integrity.spoofingScore))
        append("}}")
    }

    private fun line(r: Record): String = buildString {
        val e = r.epoch
        val g = r.integrity
        append("""{"t":"epoch","ns":""").append(e.elapsedRealtimeNanos)
        append(""","sats":""").append(e.satCount)
        append(""","dual":""").append(e.dualFrequency)
        e.clockBiasNanos?.let { append(""","clockBiasNs":""").append(num(it)) }
        append(""","hwDisc":""").append(e.hardwareClockDiscontinuityCount)
        append(""","jam":""").append(num(g.jammingScore))
        append(""","spoof":""").append(num(g.spoofingScore))
        append(""","usable":""").append(g.usableSats)
        append(""","src":"""").append(g.source.name).append('"')
        r.networkDivergenceKm?.let { append(""","divKm":""").append(num(it)) }
        if (g.excluded.isNotEmpty()) {
            append(""","excl":{""")
            g.excluded.entries.forEachIndexed { i, (k, v) ->
                if (i > 0) append(',')
                append('"').append(esc(k)).append("""":"""").append(esc(v)).append('"')
            }
            append('}')
        }
        append(""","obs":[""")
        e.observations.forEachIndexed { i, o ->
            if (i > 0) append(',')
            append("""{"k":"""").append(esc(o.key))
            append("""","cn0":""").append(num(o.cn0DbHz))
            append(""","prr":""").append(num(o.pseudorangeRateMps))
            o.elevationDeg?.let { append(""","el":""").append(num(it)) }
            if (o.cycleSlip) append(""","slip":true""")
            if (o.multipathSuspect) append(""","mp":true""")
            if (o.usedInFix) append(""","fix":true""")
            append('}')
        }
        append("]}")
    }

    private data class Record(
        val epoch: GnssEpoch,
        val integrity: GnssIntegrity,
        val networkDivergenceKm: Double?,
    )

    private companion object {
        const val SCHEMA = 1
        const val PRE_TRIGGER = 120
        const val HOLD_OFF = 30
        const val MAX_RECORDS = 20_000

        val CLEAN = GnssIntegrity(jammingScore = 0.0, spoofingScore = 0.0, usableSats = 0)

        fun num(v: Double): String {
            if (v.isNaN() || v.isInfinite()) return "null"
            val r = Math.round(v * 1000.0) / 1000.0
            return if (r == Math.floor(r) && Math.abs(r) < 1e15) r.toLong().toString() else r.toString()
        }

        fun esc(s: String): String {
            if (s.none { it == '"' || it == '\\' || it < ' ' }) return s
            return buildString(s.length + 8) {
                for (c in s) when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c == '\n' -> append("\\n")
                    c == '\r' -> append("\\r")
                    c == '\t' -> append("\\t")
                    c < ' ' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
        }
    }
}
