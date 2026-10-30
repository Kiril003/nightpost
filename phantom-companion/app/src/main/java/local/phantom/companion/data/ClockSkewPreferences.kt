package local.phantom.companion.data

import android.content.Context
import android.content.SharedPreferences
import local.phantom.companion.core.geo.sky.ClockSkew
import local.phantom.companion.core.geo.sky.ClockSkewStore

private const val PREFS_NAME = "phantom_clock_skew"
private const val KEY_SKEW_MS = "skew_ms"
private const val KEY_VERIFIED_AT_MS = "verified_at_ms"

/**
 * H-02 — where the last satellite clock-check survives a restart.
 *
 * The condition this guards is a phone that has not seen the sky: a flat
 * battery, a night underground, a jammed corridor. Every one of those ends in a
 * cold start, so a witness kept only in memory would be erased by the very
 * event it exists to detect, and the light countdown would come back up wearing
 * its clean face over a clock that had just been reset by the boot loader.
 *
 * Plain [SharedPreferences], not the Keystore-backed store [MapPreferences]
 * uses: a clock offset is not a secret, and encrypting it would buy nothing
 * while adding a failure mode ([MapPreferences] needs a plaintext fallback
 * precisely because that layer does fail) to a control whose whole value is
 * that it keeps working when things are broken.
 */
class ClockSkewPreferences(context: Context) : ClockSkewStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Absent [KEY_VERIFIED_AT_MS] is the honest «never checked» — read back as
     * null rather than as a zero epoch, which would present 1970 as a
     * verification and let the staleness rule decide by arithmetic on a value
     * nobody ever wrote.
     */
    override fun load(): ClockSkew? {
        if (!prefs.contains(KEY_VERIFIED_AT_MS)) return null
        return ClockSkew(
            skewMs = prefs.getLong(KEY_SKEW_MS, 0L),
            verifiedAtMs = prefs.getLong(KEY_VERIFIED_AT_MS, 0L),
        )
    }

    override fun save(skew: ClockSkew) {
        prefs.edit()
            .putLong(KEY_SKEW_MS, skew.skewMs)
            .putLong(KEY_VERIFIED_AT_MS, skew.verifiedAtMs)
            .apply()
    }
}
