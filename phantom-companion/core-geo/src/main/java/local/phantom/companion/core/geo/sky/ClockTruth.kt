package local.phantom.companion.core.geo.sky

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * H-02, clause two — «наскільки точний годинник пристрою», moved out of the
 * TalkBack description and into something that can actually be false.
 *
 * [UsableLight] converts an instant into minutes of workable light. Every one
 * of those minutes is measured from the device's idea of *now*, and after a
 * long blackout with a flat battery that idea is a guess the phone made when it
 * booted — hours off, with no outward sign. A countdown that says «світла ~40
 * хв» off a clock three hours fast is not approximately right; it is confidently
 * wrong at exactly the moment the number is worth reading.
 *
 * The correction is free and already on the device. A GNSS fix carries UTC out
 * of the satellite's own caesium standard: `Location.getTime()` on a fix that
 * came from satellites is independent of the wall clock, and the difference
 * between the two is the phone's error, measured rather than assumed.
 *
 * So this holds one number and one timestamp — [ClockSkew] — and answers one
 * question, [assess]. It **fails closed** in both directions: no satellite
 * witness inside [VERIFY_TTL_MS] is [ClockTruth.Unverified], and so is a record
 * whose own age comes out negative, which is what a second clock change since
 * the last check looks like from here. Silence is never read as agreement.
 */
object ClockCheck {

    /**
     * How far the device may be from the satellites before the countdown has to
     * say so out loud.
     *
     * Two minutes, because that is the granularity the countdown actually
     * claims: [UsableLight.rounded] snaps to five minutes above twenty, so a
     * skew under two cannot move a displayed number by more than one step, and
     * announcing it would be chrome for an error the operator cannot see. Above
     * it, the number moves, and silence would be a lie.
     */
    const val TOLERANCE_MS = 120_000L

    /**
     * How long a satellite check stays good for.
     *
     * A day. Phone clocks drift by seconds a month, not hours — the failure
     * this guards is not drift, it is a cold boot with no network, and that
     * announces itself as a jump, not a creep. Twenty-four hours is long enough
     * that a crew working underground for a shift keeps a green line, and short
     * enough that a phone which has not seen the sky since the last battery
     * death cannot keep claiming it knows what time it is.
     */
    const val VERIFY_TTL_MS = 24L * 60L * 60L * 1000L

    /** Device clock minus satellite UTC. Positive = the phone runs fast. */
    fun skewOf(deviceNowMs: Long, gpsTimeMs: Long): Long = deviceNowMs - gpsTimeMs

    /**
     * The record [assess] will be asked about later, built from one satellite
     * witness. [ClockSkew.verifiedAtMs] is stamped in **satellite** time, not
     * device time, on purpose: the device clock is the thing under suspicion,
     * and an age measured with the suspect's own ruler is not a measurement.
     */
    fun witness(deviceNowMs: Long, gpsTimeMs: Long): ClockSkew =
        ClockSkew(skewMs = skewOf(deviceNowMs, gpsTimeMs), verifiedAtMs = gpsTimeMs)

    /**
     * What may be claimed right now, given the last satellite witness.
     *
     * The age of the record is measured in corrected time — `deviceNow − skew`
     * against the satellite stamp — so a clock that moved *again* since the
     * check shows up as an impossible age rather than as a fresh verification.
     * Both impossible directions land on [ClockTruth.Unverified]: a negative age
     * (the clock went backwards) and an age past [VERIFY_TTL_MS] (it went
     * forwards, or the check simply expired). Neither can be told from real
     * elapsed time without a new fix, and guessing in favour of the phone is the
     * one answer that is never allowed here.
     */
    fun assess(deviceNowMs: Long, record: ClockSkew?): ClockTruth {
        if (record == null) return ClockTruth.Unverified
        val age = (deviceNowMs - record.skewMs) - record.verifiedAtMs
        if (age < 0L || age > VERIFY_TTL_MS) return ClockTruth.Unverified
        return if (kotlin.math.abs(record.skewMs) <= TOLERANCE_MS) {
            ClockTruth.Verified(record.skewMs)
        } else {
            ClockTruth.Corrected(record.skewMs)
        }
    }

    /**
     * The instant the countdown should actually be computed from.
     *
     * A live record is applied whether or not it exceeded [TOLERANCE_MS] — a
     * known error is worth removing even when it was too small to mention.
     * [ClockTruth.Unverified] returns the device clock unchanged, because there
     * is nothing better to return; that state's whole job is to say so on
     * screen rather than to quietly invent a correction.
     */
    fun correctedNow(deviceNowMs: Long, truth: ClockTruth): Long = when (truth) {
        is ClockTruth.Verified -> deviceNowMs - truth.skewMs
        is ClockTruth.Corrected -> deviceNowMs - truth.skewMs
        ClockTruth.Unverified -> deviceNowMs
    }

    /**
     * The skew as the copy has to state it: whole minutes under an hour, whole
     * hours above. Rounds to nearest — this number is an explanation of a
     * correction already applied, not a countdown, so there is no direction that
     * shorts the operator by rounding the wrong way.
     */
    fun magnitudeOf(skewMs: Long): SkewMagnitude {
        val abs = kotlin.math.abs(skewMs)
        return if (abs < 60L * 60L * 1000L) {
            SkewMagnitude(((abs + 30_000L) / 60_000L).toInt(), SkewUnit.MINUTES, ahead = skewMs > 0L)
        } else {
            SkewMagnitude(((abs + 1_800_000L) / 3_600_000L).toInt(), SkewUnit.HOURS, ahead = skewMs > 0L)
        }
    }
}

/** Whole minutes or whole hours — the two units the readout has words for. */
enum class SkewUnit { MINUTES, HOURS }

/** @property ahead true when the device clock runs *fast* against the satellites. */
data class SkewMagnitude(val amount: Int, val unit: SkewUnit, val ahead: Boolean)

/**
 * One satellite witness, kept.
 *
 * @property skewMs device clock minus satellite UTC at the moment of the check.
 * @property verifiedAtMs satellite UTC of that moment — see [ClockCheck.witness]
 *   for why this is not stamped with the device's own clock.
 */
data class ClockSkew(val skewMs: Long, val verifiedAtMs: Long)

/**
 * Where a [ClockSkew] survives a restart.
 *
 * The state this guards is the *absence* of a satellite for a day, and a phone
 * that reboots loses in-memory state without losing the condition — so a
 * process-lifetime witness would silently re-arm the clean face every cold
 * start, which is precisely the wrong direction to fail. Kept as an interface
 * so the rule above stays pure Kotlin and unit-testable; the Android
 * implementation lives in `:app` beside the other preference stores.
 */
interface ClockSkewStore {
    fun load(): ClockSkew?
    fun save(skew: ClockSkew)
}

/** What the light readout is allowed to claim about its own clock. */
sealed interface ClockTruth {

    /** Checked inside the day, and inside [ClockCheck.TOLERANCE_MS]. Say nothing. */
    data class Verified(val skewMs: Long) : ClockTruth

    /** Checked, and wrong by more than the tolerance: correct it, and say so. */
    data class Corrected(val skewMs: Long) : ClockTruth

    /** No satellite has confirmed this clock inside [ClockCheck.VERIFY_TTL_MS]. */
    data object Unverified : ClockTruth
}

/**
 * Keeps the newest witness, and hands it to a [ClockSkewStore] so tomorrow's
 * cold start inherits it.
 *
 * Deliberately dumb about *which* fixes it is fed: the caller is the one that
 * knows a `gpsTimeMs` is non-null only when satellites set it, and re-deciding
 * that here would mean a second, drifting definition of «came from the sky».
 * Every fix that carries one is a fresh check — GNSS delivers at 1 Hz under
 * guidance, so this settles within a second of the first fix and then simply
 * keeps confirming itself.
 */
class ClockWitness(private val store: ClockSkewStore? = null) {

    private val _record = MutableStateFlow(runCatching { store?.load() }.getOrNull())

    /** The last satellite witness, or null if none has ever been recorded. */
    val record: StateFlow<ClockSkew?> = _record.asStateFlow()

    /**
     * Feed one fix. `gpsTimeMs == null` means the fix could not prove it came
     * from satellites, which is not evidence of anything and is ignored — the
     * old record keeps ageing out on its own.
     */
    fun observe(gpsTimeMs: Long?, deviceNowMs: Long) {
        val gps = gpsTimeMs ?: return
        val next = ClockCheck.witness(deviceNowMs, gps)
        if (_record.value == next) return
        _record.value = next
        runCatching { store?.save(next) }
    }
}
