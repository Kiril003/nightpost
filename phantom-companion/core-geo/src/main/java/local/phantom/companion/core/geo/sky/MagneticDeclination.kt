package local.phantom.companion.core.geo.sky

import java.time.Instant
import java.time.Year
import java.time.ZoneOffset

/**
 * Магнітне схилення — кут між магнітною і справжньою північчю. H-05 / spec L8.
 *
 * A compass points at the magnetic pole, a map is drawn to the geographic one,
 * and across Ukraine the gap runs from roughly 6° to 9° east — a kilometre of
 * error for every ten walked on a bearing taken straight off the needle. It is
 * also, unlike everything else in this package, a *slow* fact: it drifts by
 * about 0.1° a year, which is why the model behind it expires.
 *
 * **Why this ships a coefficient file instead of calling the platform.**
 * `android.hardware.GeomagneticField` is offline, free, and genuinely WMM-based
 * — and its model epoch is baked into the OS image with no way to read it back
 * at runtime. On a phone that stopped taking system updates in 2021 it answers
 * from a decade-old model, in exactly the same tone of voice as a current one,
 * and nothing in the API lets the caller tell the two apart. The spec's own
 * requirement — refuse silently-stale coefficients rather than serve a
 * five-year-old model as current — is not merely unimplemented through that
 * API, it is unimplementable: the fact the check needs is not exposed. So the
 * coefficients ship here, where their epoch is a value this code can read and
 * therefore a window this code can refuse to leave.
 *
 * **Provenance.** `core-geo/src/main/resources/geomag/WMM2025.COF`, byte-identical
 * to `WMM.COF` from NOAA/NCEI's `WMM2025COF.zip`
 * (`ncei.noaa.gov/sites/default/files/2025-01/WMM2025COF.zip`), SHA-256
 * `06791cd95faba7bdf4a709808f2715a53fe689b29c23b9886bc2196fa9b3eb13`, epoch
 * 2025.0, released 2024-11-13. A work of the US Government: public domain under
 * 17 U.S.C. §105, no attribution obligation — credited anyway on the licences
 * screen, because a bundled data file nobody can trace is its own kind of
 * unverifiable claim. `WorldMagneticModelTest` re-checks that hash on every run,
 * so the bytes this doc describes and the bytes that ship cannot drift apart
 * silently.
 *
 * **What the number is worth.** NOAA specifies declination to ±0.5° over the
 * model's five-year window, for the main field only. It does not include local
 * magnetic anomalies — ore bodies, rebar, a vehicle, the phone's own speaker
 * magnet — which routinely dwarf it, and no model can. This value corrects a
 * *map bearing*; it does not make a particular compass in a particular hand
 * correct.
 *
 * **When it refuses.** Outside the window, near either magnetic pole, or with
 * no readable coefficients — see [DeclinationRefusal]. Every refusal names its
 * reason, and none of them is a number: the entire point of the exercise is
 * that a stale model produces silence with an explanation rather than an angle
 * that still looks like an angle.
 */
object MagneticDeclination {

    /**
     * WMM is published on a five-year cadence and its error budget is quoted
     * over exactly that span. The epoch comes from the file; this is the only
     * part of the window that is convention rather than data, which is why it
     * is one named constant instead of an arithmetic literal.
     */
    const val VALIDITY_YEARS = 5.0

    /**
     * NOAA's blackout-zone threshold. Where the horizontal field falls under
     * 2000 nT the needle has almost nothing to align to, and declination stops
     * being a correction anyone can apply — the model still returns an angle
     * there, and that angle is not a usable bearing. Ukraine is nowhere near
     * it; the check exists because this API is generic and a caller standing in
     * the Arctic deserves the same honesty as one standing in Kyiv.
     */
    const val BLACKOUT_HORIZONTAL_NT = 2000.0

    private val coefficients: WmmCoefficients? by lazy { WorldMagneticModel.loadBundled() }

    /** First instant the shipped model may be evaluated for, as a decimal year. */
    val validFromYear: Double? get() = coefficients?.epochYear

    /** First instant it may no longer be evaluated for. */
    val validUntilYear: Double? get() = coefficients?.epochYear?.plus(VALIDITY_YEARS)

    /** e.g. `WMM-2025`, read from the coefficient file's own header. */
    val modelName: String? get() = coefficients?.modelName?.takeIf { it.isNotBlank() }

    /**
     * Declination at a place and an instant, or a named refusal.
     *
     * [epochMs] is UTC, and is trusted exactly as far as the caller's clock is:
     * the window check is a comparison against the device's idea of the year, so
     * a phone that booted flat into 1970 will be told the model is not yet valid
     * rather than handed a 2025 answer. Callers with a satellite witness should
     * pass [ClockCheck.correctedNow]'s output, the way the map's light readout
     * already does.
     *
     * @param heightKm height above the WGS-84 ellipsoid; the default of 0 is
     *   correct to well under the model's own error bar for any ground use.
     */
    fun at(
        epochMs: Long,
        latDeg: Double,
        lonDeg: Double,
        heightKm: Double = 0.0,
    ): Declination {
        val model = coefficients
            ?: return Declination.Refused(DeclinationRefusal.CoefficientsUnavailable)

        val year = decimalYear(epochMs)
        val from = model.epochYear
        val until = model.epochYear + VALIDITY_YEARS
        if (year < from) {
            return Declination.Refused(DeclinationRefusal.ModelNotYetValid(from, year))
        }
        if (year >= until) {
            return Declination.Refused(DeclinationRefusal.ModelExpired(until, year))
        }
        if (WorldMagneticModel.isAtGeographicPole(latDeg)) {
            return Declination.Refused(DeclinationRefusal.AtGeographicPole)
        }

        val sample = WorldMagneticModel.evaluate(model, year, latDeg, lonDeg, heightKm)
        if (sample.horizontalNt < BLACKOUT_HORIZONTAL_NT) {
            return Declination.Refused(DeclinationRefusal.InMagneticBlackout(sample.horizontalNt))
        }

        return Declination.Known(
            degreesEast = sample.declinationDeg,
            driftDegPerYear = sample.declinationRateDegPerYear,
            modelName = model.modelName,
            validUntilYear = until,
        )
    }

    /**
     * Calendar date as a decimal year, the unit the model's time argument is in.
     *
     * Leap years divide by 366, so 1 July lands on .4986 in one year and .5 in
     * another rather than both being called "half past". At 0.1°/yr that
     * distinction is far below the noise — it is here because the alternative is
     * a constant 365.25 that quietly disagrees with NOAA's own test dates.
     */
    internal fun decimalYear(epochMs: Long): Double {
        val at = Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC)
        val daysInYear = if (Year.isLeap(at.year.toLong())) 366.0 else 365.0
        val fractionOfDay = at.toLocalTime().toSecondOfDay() / 86_400.0
        return at.year + (at.dayOfYear - 1 + fractionOfDay) / daysInYear
    }
}

/** Either an angle that may be applied to a bearing, or a reason there is none. */
sealed interface Declination {

    /**
     * @property degreesEast positive east of true north — the sign convention
     *   every chart legend and every compass bezel uses. Add it to a magnetic
     *   bearing to get a true one.
     * @property driftDegPerYear the model's own secular-variation rate at this
     *   place. Surfaced rather than hidden because it is the evidence for the
     *   expiry rule: a number moving 0.1° a year is a number with a shelf life.
     * @property validUntilYear when [MagneticDeclination] will start refusing.
     */
    data class Known(
        val degreesEast: Double,
        val driftDegPerYear: Double,
        val modelName: String,
        val validUntilYear: Double,
    ) : Declination

    data class Refused(val reason: DeclinationRefusal) : Declination
}

/**
 * Why no angle was returned.
 *
 * Each case carries the numbers behind it so a surface can say «модель діяла до
 * 2030» instead of «немає даних» — a refusal the operator cannot interrogate
 * teaches the same helplessness as a wrong answer, just more slowly.
 */
sealed interface DeclinationRefusal {

    /**
     * The date is past the model's window. The coefficients would still evaluate
     * — that is exactly the danger, and why this is a refusal and not a warning
     * attached to a number.
     */
    data class ModelExpired(val validUntilYear: Double, val askedYear: Double) : DeclinationRefusal

    /**
     * The date is before the epoch. In the field this is not a historical query,
     * it is a phone whose clock died and came back in 1970.
     */
    data class ModelNotYetValid(val validFromYear: Double, val askedYear: Double) :
        DeclinationRefusal

    /** Horizontal field under [MagneticDeclination.BLACKOUT_HORIZONTAL_NT]. */
    data class InMagneticBlackout(val horizontalNt: Double) : DeclinationRefusal

    /** Within a hair of 90°N/S, where the east component is 0/0. */
    data object AtGeographicPole : DeclinationRefusal

    /** The bundled coefficient file was missing or unreadable. */
    data object CoefficientsUnavailable : DeclinationRefusal
}
