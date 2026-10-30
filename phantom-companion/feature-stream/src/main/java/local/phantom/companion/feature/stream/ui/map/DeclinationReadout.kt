package local.phantom.companion.feature.stream.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import local.phantom.companion.core.ai.context.LocationFix
import androidx.compose.material3.Text
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.geo.sky.ClockCheck
import local.phantom.companion.core.geo.sky.ClockSkew
import local.phantom.companion.core.geo.sky.Declination
import local.phantom.companion.core.geo.sky.DeclinationRefusal
import local.phantom.companion.core.geo.sky.MagneticDeclination
import local.phantom.companion.feature.stream.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * H-05 «магнітне схилення» — the rail's compass correction.
 *
 * **Whole degrees, not tenths.** The model is specified to ±0.5°, so the first
 * decimal place is below its own error bar: «схилення 7,6°» would be a digit of
 * invented precision on a number that is only ever claimed to the degree.
 * Rounding to whole degrees makes the display resolution and the model's
 * accuracy the same thing, which is the only arrangement that cannot mislead.
 *
 * **Present whenever there is a fix, unlike the light countdown next to it.**
 * The countdown hides at midday because its number decays into noise; this one
 * never does — a bearing taken off a needle is wrong by this angle at any hour,
 * and the operator does not get a warning before the moment they need it. It is
 * also the [MapInstrumentPlate] pattern rather than a new one: a static ambient
 * instrument that appears once there is something real to say.
 *
 * **A refusal takes the number's place rather than blanking the line.** When
 * the model is out of its window the slot says so and names the year, because a
 * line that simply vanished would read as "no correction needed" — the one
 * conclusion that is never true. This is the spec's own requirement (L8: refuse
 * silently-stale coefficients rather than serve a five-year-old model as
 * current) rendered where an operator actually meets it.
 */
internal data class DeclinationLine(val text: String, val refused: Boolean, val spoken: String)

@Composable
internal fun rememberDeclinationLine(
    operatorLocation: LocationFix?,
    nowMs: Long,
    /** Shared with the light readout: the window check is a question about the year. */
    clockSkew: ClockSkew?,
): DeclinationLine? {
    val fix = operatorLocation ?: return null

    // Corrected time, for the same reason the countdown uses it — except here a
    // wrong clock does not shift a number, it decides whether there is one at
    // all. A phone that booted into 1970 must refuse rather than answer, and a
    // phone whose skew is known should not refuse over an error already measured.
    val trueNowMs = ClockCheck.correctedNow(nowMs, ClockCheck.assess(nowMs, clockSkew))

    // Keyed at ~1 km and to the day: declination moves by ~0.1° a YEAR, so
    // anything finer would recompute 91 spherical-harmonic terms to produce the
    // identical integer.
    val cellLat = Math.round(fix.lat * 100.0)
    val cellLon = Math.round(fix.lon * 100.0)
    val day = trueNowMs / 86_400_000L
    val declination = remember(cellLat, cellLon, day) {
        MagneticDeclination.at(trueNowMs, fix.lat, fix.lon)
    }

    val face = declinationFace(declination)
    val text = face.value?.let { stringResource(face.textRes, it) } ?: stringResource(face.textRes)
    return DeclinationLine(
        text = text,
        refused = face.refused,
        spoken = stringResource(R.string.map_declination_desc, text),
    )
}

/**
 * The correction's row inside the instrument plate. The glass moved to the
 * plate: three panes for one role read as three separate scraps, and the rule
 * they each obeyed — **нічого не лежить на землі голим** — is obeyed once, by
 * the plate, for all four rows at the same time.
 */
@Composable
internal fun MapDeclinationReadout(line: DeclinationLine) {
    val c = LocalPhantomColors.current
    Text(
        text = line.text,
        style = MapFigureStyle,
        color = if (line.refused) c.text2 else c.text,
        maxLines = 1,
    )
}

/**
 * @property value the single format argument, or null for a line that takes none.
 * @property refused true when this face is the absence of a correction rather
 *   than one — the ink differs, because a stated reason is secondary text while
 *   a number the operator is meant to apply is not.
 */
internal data class DeclinationFace(val textRes: Int, val value: Int?, val refused: Boolean)

/**
 * Every state this instrument can be in, as one exhaustive `when` — a new
 * [DeclinationRefusal] cannot be added without someone deciding what it says.
 *
 * The two "undefined here" cases are deliberately one line of copy. Near the
 * magnetic pole there is too little horizontal field to steer by, and at the
 * geographic pole the east component is genuinely 0/0; they differ in cause but
 * not in what the operator can do about them, and two near-identical sentences
 * would be precision for the reader who is not there.
 */
internal fun declinationFace(declination: Declination): DeclinationFace = when (declination) {
    is Declination.Known -> {
        val degrees = declination.degreesEast
        val rounded = abs(degrees).roundToInt()
        when {
            // Inside half a degree the correction is smaller than the model's
            // own error, and «0° сх.» would name a direction that is not there.
            rounded == 0 -> DeclinationFace(R.string.map_declination_none, null, refused = false)
            degrees > 0.0 -> DeclinationFace(R.string.map_declination_east, rounded, refused = false)
            else -> DeclinationFace(R.string.map_declination_west, rounded, refused = false)
        }
    }

    is Declination.Refused -> when (val reason = declination.reason) {
        is DeclinationRefusal.ModelExpired -> DeclinationFace(
            R.string.map_declination_expired,
            reason.validUntilYear.toInt(),
            refused = true,
        )

        is DeclinationRefusal.ModelNotYetValid ->
            DeclinationFace(R.string.map_declination_clock, null, refused = true)

        is DeclinationRefusal.InMagneticBlackout ->
            DeclinationFace(R.string.map_declination_undefined, null, refused = true)

        DeclinationRefusal.AtGeographicPole ->
            DeclinationFace(R.string.map_declination_undefined, null, refused = true)

        DeclinationRefusal.CoefficientsUnavailable ->
            DeclinationFace(R.string.map_declination_unavailable, null, refused = true)
    }
}
