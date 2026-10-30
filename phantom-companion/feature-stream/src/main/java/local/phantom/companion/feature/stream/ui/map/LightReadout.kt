package local.phantom.companion.feature.stream.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.geo.sky.ClockCheck
import local.phantom.companion.core.geo.sky.ClockSkew
import local.phantom.companion.core.geo.sky.ClockTruth
import local.phantom.companion.core.geo.sky.LightPhase
import local.phantom.companion.core.geo.sky.SkewUnit
import local.phantom.companion.core.geo.sky.UsableLight
import local.phantom.companion.feature.stream.R

/**
 * @property doubted the clock behind the number was never confirmed — the one
 *   state that colours the NUMBER, because it is the only one where the number
 *   itself is in doubt. A corrected countdown is true (its caveat explains a
 *   repair, not a doubt) and keeps the ink of every other instrument.
 */
internal data class LightLine(
    val headline: String,
    val note: String?,
    val doubted: Boolean,
    val spoken: String,
)

/**
 * H-02 «~40 хв придатного світла» — what the plate may say about the light, or
 * null when it may say nothing. Honest in two ways.
 *
 * **Absent when there is no fix.** Not a default city's sunset — no position,
 * no line. The only coordinates that reach this are the operator's own, never
 * `ui.viewportCenter` and never the KYIV_LAT/KYIV_LON fallback that other
 * verbs in the map use: scrubbing the camera to Tromsø must not change what
 * time it gets dark where the operator is standing.
 *
 * **Absent when the number says nothing.** «8 годин світла» at midday is noise
 * that teaches the eye to skip the line; the instrument appears only inside
 * the ~90-minute window where the number is an input to a decision.
 *
 * The horizon line is not decoration. Every time behind this comes from a flat
 * open horizon, and a balka, a treeline or a street wall goes dark well before
 * it does — so the limit travels with the number rather than sitting one tap
 * away in a sheet the operator at dusk will not open.
 *
 * The clock line is the second limit, and it used to be a dodge. Every minute
 * here is measured from the device's idea of «now», and after a long blackout
 * with a flat battery that idea can be hours wrong — a fact the readout stated
 * only in its TalkBack description, where a sighted operator at dusk never met
 * it. Now the clock is actually checked against the satellites
 * ([ClockCheck]), and there are three faces: silence when the check is fresh
 * and clean, an out-loud correction when the phone is wrong by more than two
 * minutes, and the accent over the number when nothing has confirmed the clock
 * for a day.
 */
@Composable
internal fun rememberLightLine(
    operatorLocation: LocationFix?,
    nowMs: Long,
    /** H-02 — the last satellite check; null = never checked. See [ClockCheck]. */
    clockSkew: ClockSkew?,
): LightLine? {
    val fix = operatorLocation ?: return null

    // Judged here, per redraw, rather than cached upstream: this state goes
    // stale by the passage of time alone, so a verdict computed once would keep
    // showing the clean face right through the day it expired on.
    val clock = ClockCheck.assess(nowMs, clockSkew)
    // The countdown is computed from CORRECTED time whenever a live check
    // exists — a known offset is worth removing even when it was too small to
    // be worth mentioning. Unverified falls back to the device clock, and says
    // so on screen rather than quietly inventing a correction.
    val trueNowMs = ClockCheck.correctedNow(nowMs, clock)

    // Keyed on ~1km, not on the raw fix: a metre of GPS jitter arrives every
    // second and moves civil dusk by microseconds, so keying on it would
    // re-scan the sun's whole curve 1800 times per tick of the clock that
    // actually matters.
    val cellLat = Math.round(fix.lat * 100.0)
    val cellLon = Math.round(fix.lon * 100.0)
    val face = remember(cellLat, cellLon, trueNowMs) {
        lightFace(UsableLight.phase(trueNowMs, fix.lat, fix.lon))
    } ?: return null

    val headline = face.minutes
        ?.let { stringResource(face.textRes, it) }
        ?: stringResource(face.textRes)
    val noteText = clockNote(clock)?.let { n ->
        n.amount?.let { stringResource(n.textRes, it) } ?: stringResource(n.textRes)
    }
    return LightLine(
        headline = headline,
        note = noteText,
        doubted = clock == ClockTruth.Unverified,
        spoken = stringResource(R.string.map_light_desc, headline) +
            (noteText?.let { " $it." } ?: ""),
    )
}

internal data class LightFace(val textRes: Int, val minutes: Int?)

/**
 * Null is a face too — the three silent phases are silent on purpose, not by
 * omission. The `when` is exhaustive so a new [LightPhase] cannot slip in and
 * render nothing without someone choosing that.
 */
internal fun lightFace(phase: LightPhase): LightFace? = when (phase) {
    is LightPhase.UsableLight ->
        LightFace(R.string.map_light_remaining, UsableLight.rounded(phase.minutes))
    is LightPhase.FallingDark ->
        LightFace(R.string.map_light_until_dark, UsableLight.rounded(phase.minutes))
    is LightPhase.FirstLight ->
        LightFace(R.string.map_light_until_first, UsableLight.rounded(phase.minutes))
    LightPhase.LightAllNight -> LightFace(R.string.map_light_all_night, null)
    LightPhase.NoDaylight -> LightFace(R.string.map_light_no_day, null)
    LightPhase.NoFullDark -> LightFace(R.string.map_light_no_dark, null)
    is LightPhase.Ample -> null
    LightPhase.Dawning -> null
    LightPhase.Night -> null
}

/** @property amount the skew's size, in the unit [textRes] names; null when the line takes no number. */
internal data class ClockNote(val textRes: Int, val amount: Int?)

/**
 * What the readout says about its own clock — and, for the clean case, the
 * decision to say nothing.
 *
 * Silence is the right face for [ClockTruth.Verified]: a line reading «годинник
 * звірено» on every ordinary dusk is chrome, and chrome shown constantly is
 * chrome the eye stops reading — which would cost exactly the two states that
 * need to be noticed. The caveat's absence is the good news, the same way the
 * instrument's own absence at midday is.
 *
 * The correction states name a DIRECTION, not just a size. «годинник спішить на
 * 3 год» tells the operator something he can check against a wristwatch and act
 * on; a bare «±3 год» would leave him unable to tell a repair from a fault.
 */
internal fun clockNote(clock: ClockTruth): ClockNote? = when (clock) {
    is ClockTruth.Verified -> null
    is ClockTruth.Corrected -> {
        val skew = ClockCheck.magnitudeOf(clock.skewMs)
        val res = when {
            skew.ahead && skew.unit == SkewUnit.MINUTES -> R.string.map_light_clock_fast_min
            skew.ahead -> R.string.map_light_clock_fast_hr
            skew.unit == SkewUnit.MINUTES -> R.string.map_light_clock_slow_min
            else -> R.string.map_light_clock_slow_hr
        }
        ClockNote(res, skew.amount)
    }
    ClockTruth.Unverified -> ClockNote(R.string.map_light_clock_unverified, null)
}
