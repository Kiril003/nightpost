package local.phantom.companion.core.sensor.nav.replay

import kotlin.random.Random

internal data class Score(
    val scenario: String,
    val chain: String,
    val errEventMaxM: Double,
    val errEventP95M: Double,
    val errEventEndM: Double,
    val crossEventMaxM: Double,
    val onRoadPct: Double,
    val overclaimPct: Double,
    val mergeJumpM: Double,
    val detectS: Double?,
    val falseSpoof: Int,
    val falseJam: Int,
    val clearErrP95M: Double,
)

internal class ReplayRun(private val sc: Scenario, private val chains: List<Chain>) {
    private val rng = Random(sc.seed)
    private val mover = TruthMover(sc.path, sc.legs, if (sc.body == Body.CAR) 2.5 else 1.0, if (sc.body == Body.CAR) 4.0 else 1.5)
    private val imu = ImuSynth(sc.body, Math.toRadians(sc.mountYawDeg), rng)
    private val flp = FlpSynth(rng)
    private val net = NetworkSynth(rng)

    private class Acc {
        val eventErr = ArrayList<Double>()
        val clearErr = ArrayList<Double>()
        var crossMax = 0.0
        var onRoad = 0
        var overclaim = 0
        var endErr = 0.0
        var mergeJump = 0.0
        var detect: Double? = null
        var falseSpoof = 0
        var falseJam = 0
        var lastAlarm = Alarm.NONE
        var lastShown: En? = null
    }

    fun run(): List<Score> {
        val acc = chains.associateWith { Acc() }
        val stepMs = 20L
        val endMs = (sc.durationS * 1000).toLong()
        var t = 0L
        var truthAtLastTick: Truth? = null
        while (t <= endMs) {
            val truth = mover.step(t, stepMs / 1000.0)
            val tS = t / 1000.0
            val sample = imu.sample(truth, stepMs / 1000.0)
            chains.forEach { it.imu(sample, t) }
            if (t % 200L == 0L) {
                val hpa = pressureOf(truth.altitudeM) + (0.01 * rng.gauss()).toFloat()
                chains.forEach { it.pressure(hpa, t) }
            }
            if (t % 1000L == 0L) {
                val sky = skyAt(tS)
                flp.fix(truth, sky, tS, 1.0)?.let { f -> chains.forEach { it.gps(f, t) } }
                net.fix(truth, Scenarios.networkAccFor(sky), tS)?.let { f -> chains.forEach { it.network(f, t) } }
                chains.forEach { it.tick(t) }
                chains.forEach { c -> score(c, acc.getValue(c), truth, truthAtLastTick, tS, t) }
                truthAtLastTick = truth
            }
            t += stepMs
        }
        return chains.map { c ->
            val a = acc.getValue(c)
            Score(
                scenario = sc.name,
                chain = c.name,
                errEventMaxM = a.eventErr.maxOrNull() ?: 0.0,
                errEventP95M = p95(a.eventErr),
                errEventEndM = a.endErr,
                crossEventMaxM = a.crossMax,
                onRoadPct = if (a.eventErr.isEmpty()) 0.0 else 100.0 * a.onRoad / a.eventErr.size,
                overclaimPct = if (a.eventErr.isEmpty()) 0.0 else 100.0 * a.overclaim / a.eventErr.size,
                mergeJumpM = a.mergeJump,
                detectS = a.detect,
                falseSpoof = a.falseSpoof,
                falseJam = a.falseJam,
                clearErrP95M = p95(a.clearErr),
            )
        }
    }

    private fun skyAt(tS: Double): Sky = sc.sky.firstOrNull { tS >= it.fromS && tS < it.toS }?.sky ?: Sky.Clear()

    private fun score(c: Chain, a: Acc, truth: Truth, prev: Truth?, tS: Double, tMs: Long) {
        val inEvent = tS >= sc.eventFromS && tS < sc.eventToS
        val afterEvent = tS >= sc.eventToS && tS < sc.eventToS + 30
        val alarm = c.alarm(tMs)
        val spoofWindow = sc.event == EventKind.SPOOF && tS >= sc.eventFromS && tS < sc.eventToS + 30
        if (alarm == Alarm.SPOOF && a.lastAlarm != Alarm.SPOOF && !spoofWindow) a.falseSpoof++
        if (alarm == Alarm.JAM && a.lastAlarm != Alarm.JAM && !sc.jamming) a.falseJam++
        if (inEvent && a.detect == null && sc.event != EventKind.BENIGN) {
            val caught = if (sc.event == EventKind.SPOOF) alarm == Alarm.SPOOF else alarm != Alarm.NONE
            if (caught) a.detect = tS - sc.eventFromS
        }
        a.lastAlarm = alarm
        val shown = c.shown(tMs) ?: return
        val err = (shown.p - truth.p).norm()
        if (inEvent) {
            a.eventErr += err
            val cross = sc.path.crossTrackM(shown.p)
            if (cross > a.crossMax) a.crossMax = cross
            if (cross <= 10.0) a.onRoad++
            if (err > shown.claimedM) a.overclaim++
            a.endErr = err
        } else if (tS > 20 && !afterEvent) {
            a.clearErr += err
        }
        val last = a.lastShown
        if (afterEvent && last != null && prev != null) {
            val jump = (shown.p - last).norm() - (truth.p - prev.p).norm()
            if (jump > a.mergeJump) a.mergeJump = jump
        }
        a.lastShown = shown.p
    }

    private fun p95(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        val s = xs.sorted()
        return s[((s.size - 1) * 0.95).toInt()]
    }
}

internal fun formatScores(scores: List<Score>): String = buildString {
    appendLine("сценарій | ланцюг | макс похибка під час, м | p95, м | в кінці, м | поперек макс, м | на дорозі % | недооцінка % | стрибок злиття, м | виявлено за, с | хибна підміна | хибне глушіння | чисте небо p95, м")
    for (s in scores) {
        appendLine(
            listOf(
                s.scenario, s.chain, f(s.errEventMaxM), f(s.errEventP95M), f(s.errEventEndM), f(s.crossEventMaxM),
                f(s.onRoadPct), f(s.overclaimPct), f(s.mergeJumpM), s.detectS?.let { f(it) } ?: "—",
                s.falseSpoof.toString(), s.falseJam.toString(), f(s.clearErrP95M),
            ).joinToString(" | "),
        )
    }
}

private fun f(x: Double) = String.format(java.util.Locale.US, "%.0f", x)
