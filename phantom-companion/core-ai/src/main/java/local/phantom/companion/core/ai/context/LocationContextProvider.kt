package local.phantom.companion.core.ai.context

import java.util.Locale

/**
 * How much authority a fix carries — the difference between «which city is he
 * in» and «which lane is he in».
 *
 * PHANTOM-EW guards exactly one door: [DefaultLocationContextProvider]'s first
 * rung, the fused fix, which has been spoof-gated, Kalman-fused and stamped
 * with its own radius of doubt. Every rung below it is a *guess* — a last-known
 * fix of unknown age, or a city-level IP lookup up to half an hour stale.
 *
 * Those guesses are perfectly good for telling the AI roughly where the
 * operator is. They are catastrophic for navigation: a guidance session
 * map-matching an IP centroid sees itself kilometres off the polyline, declares
 * the operator off-route, and reroutes him away from a route that was correct.
 *
 * So the grade rides *with* the fix, and the default is [COARSE] — the type
 * fails closed. A fix must be able to prove it was judged before anything is
 * allowed to steer on it.
 */
enum class FixGrade {
    /**
     * Judged by PHANTOM-EW: gated against physics, fused, carrying an honest
     * [LocationFix.accuracyM]. The only grade a drive may be steered by. A
     * dead-reckoned fix is still NAVIGATION — inertial navigation through a
     * tunnel is the engine working, not failing — but it says so via
     * [LocationFix.dead], and its accuracy is growing.
     */
    NAVIGATION,

    /**
     * Last-known or cached. Unknown age, unknown provenance, never gated.
     * Fine for `[LOCATION:…]`; never for a turn.
     */
    COARSE,
}

/**
 * WHERE a position came from, in the project's register grammar (the same four
 * words the map uses for ground truth — see `GroundRegister`).
 *
 * [FixGrade] answers «how precise», which is a different question and cannot
 * substitute for this one: an IP-block centroid and a stale satellite fix are
 * both COARSE, and only one of them is a position of *this device*. Collapsing
 * the two is what let an ISP's registration country reach the map as the
 * operator's location (owner report, 2026-08-16 — Romania).
 */
enum class FixRegister {
    /** A sensor on this device observed this position. */
    MEASURED,

    /** Computed from a measurement — e.g. the gate's hold on the network anchor. */
    DERIVED,

    /** A real earlier measurement of this device, of unknown age. */
    REMEMBERED,

    /** Not a measurement of this device at all — an IP block, a region default. */
    GUESSED,
    ;

    /** May this stand as an answer to «де я»? [GUESSED] never may. */
    val locatesTheDevice: Boolean get() = this != GUESSED
}

/**
 * W1-4 — coordinate pair with optional bearing.
 *
 * R1.1 — grew optional [accuracyM] and [speedMps] so the ambient
 * `[LOCATION:…]` header can carry precision and movement state.
 * Defaults keep every pre-R1 call site and test source-compatible.
 *
 * ZERO-FAIL — grew [grade] and [dead]. Before this, the fused fix's trust,
 * doubt and dead-reckoning flag were all discarded at this boundary, so a
 * consumer could not tell PHANTOM-EW's guarded output from a half-hour-old IP
 * lookup. The guidance loop could not, and rerouted on the difference.
 *
 * H-02 — grew [gpsTimeMs], the one thing on a fix that is not about *where*.
 * A satellite fix carries UTC out of the constellation's own clock, and it was
 * being dropped here, so nothing downstream could tell a correct device clock
 * from one a flat battery reset by three hours. See
 * [ClockCheck][local.phantom.companion.core.geo.sky.ClockCheck].
 */
data class LocationFix(
    val lat: Double,
    val lon: Double,
    val bearing: Float? = null,
    val accuracyM: Float? = null,
    val speedMps: Float? = null,
    /** Fails closed: an unmarked fix is a guess until it proves otherwise. */
    val grade: FixGrade = FixGrade.COARSE,
    /** True when no satellite contributed — the position is inertial. */
    val dead: Boolean = false,
    /**
     * Satellite-derived UTC of this fix, independent of the device wall clock.
     *
     * Non-null **only** when the fix provably came from satellites — the
     * platform's `gps` provider, whose `Location.getTime()` is decoded from the
     * navigation message rather than read off the phone. A fused, network,
     * cached or IP-derived fix leaves this null: those carry a time too, but it
     * is the device's own clock echoed back, which would turn this field into a
     * measurement of nothing that reads exactly like a measurement of something.
     */
    val gpsTimeMs: Long? = null,
    /**
     * Provenance. Last in the list so no positional call site shifts.
     *
     * Defaults to [FixRegister.REMEMBERED] — the honest reading of an unmarked
     * fix from a last-known lookup. [FixRegister.GUESSED] is deliberately not
     * reachable by default: a caller must state outright that it is not
     * measuring this device.
     */
    val register: FixRegister = FixRegister.REMEMBERED,
) {
    /** May a drive be steered by this? */
    val isNavigationGrade: Boolean get() = grade == FixGrade.NAVIGATION

    /** May this be shown or spoken as where the operator is? */
    val locatesTheDevice: Boolean get() = register.locatesTheDevice
}

/**
 * Phase 2 hotfix #2 — pure-Kotlin contract for "where is the operator
 * right now?" injected at the front of the AI's enrichedContext block.
 *
 * Implementation lives in `:app` (combines MobileSensorAdapter's GPS
 * SharedFlow with a LocationManager.PASSIVE_PROVIDER fallback). This
 * module stays Android-Location-free so the contract + the formatter
 * can be unit-tested without Robolectric.
 */
fun interface LocationContextProvider {
    /** @return latest known fix or null when no source has a fix. */
    fun current(): LocationFix?
}

/**
 * RETIRED, 2026-08-16 — an IP address is not a position.
 *
 * This was the fifth rung of the chain: when GPS / passive / network had
 * nothing, `current()` returned the coordinates of the caller's egress IP
 * block. On the owner's phone that block is registered in **Romania**, so the
 * map drew him in Romania while the gate was correctly refusing a captured
 * sky — the alarm came from one source and the marker from another, and the
 * marker was not a measurement of anything on the device.
 *
 * It was also a third-party HTTP call made precisely when the operator is
 * jammed or captured, in a tool whose users must not be locatable by
 * infrastructure. GHOST-gating that was never sufficient; the default posture
 * had to be.
 *
 * The type is kept only so the `:app` wiring still compiles while it is
 * removed there. Nothing consumes it: [DefaultLocationContextProvider] no
 * longer has an IP rung and [DefaultLocationContextProvider.refreshIfStale]
 * no longer performs a fetch. Delete both together.
 */
@Deprecated("An IP address is not a position. Never reinstate on the fix path.")
fun interface WebGeoIpFallback {
    suspend fun fetch(): Pair<Double, Double>?
}

/**
 * Formats a coords pair as `[LOCATION:lat,lon]` for the AI prompt
 * preamble. Always uses [Locale.US] so the decimal separator stays `.`
 * regardless of the device locale (`,` would tokenise as a list
 * separator inside the bracket and break the parser the model sees).
 *
 * Returns `[LOCATION:unknown]` when no fix is available — the AI is
 * instructed to ask the operator for a city name in that case rather
 * than copy the example coordinates from the system prompt.
 */
object LocationContextFormatter {
    /**
     * R1.1 — [address] and the fix's accuracy/speed extend the header:
     * `[LOCATION:50.4501,30.5234 ±12m рух:іде пішки адреса:"Хрещатик, Київ"]`.
     * With all extras absent the output is byte-identical to the pre-R1
     * `[LOCATION:lat,lon]` form, so existing prompt-pinning tests hold.
     */
    fun format(
        fix: LocationFix?,
        address: String? = null,
        /**
         * Whether the app currently holds a location permission at all.
         *
         * Without this, `[LOCATION:unknown]` carried **two opposite causes**:
         * «дозволу немає» and «дозвіл є, фіксу ще нема». The model cannot tell
         * them apart, so it cannot tell the operator — it can only say «не
         * знаю, де ти» and leave him guessing whether that is a settings
         * problem or a sky problem. One is fixed in two taps; the other is
         * fixed by walking to a window.
         *
         * This is the same defect as `nav.status` publishing a hard
         * `threats_count = 0`: **an unknown wearing the clothes of a
         * measurement**. Here it is milder — `unknown` is at least honest
         * about the value — but it is the same loss: the *reason* is thrown
         * away at the one place that still knows it.
         *
         * Defaults to true so every existing caller and test keeps its exact
         * byte output; only a caller that actually checked may say otherwise.
         */
        permitted: Boolean = true,
    ): String {
        // Order matters: a denied permission explains the missing fix, so it
        // must be reported instead of it, never after it.
        if (!permitted) return "[LOCATION:дозволу немає]"
        if (fix == null) return "[LOCATION:unknown]"
        return buildString {
            append(String.format(Locale.US, "[LOCATION:%.4f,%.4f", fix.lat, fix.lon))
            fix.accuracyM?.let { append(String.format(Locale.US, " ±%.0fm", it)) }
            movementLabel(fix.speedMps)?.let { append(" рух:").append(it) }
            address?.takeIf { it.isNotBlank() }?.let { append(" адреса:\"").append(it).append('"') }
            append(']')
        }
    }

    /** Coarse movement state from GPS speed; null when speed is unknown. */
    fun movementLabel(speedMps: Float?): String? = when {
        speedMps == null -> null
        speedMps < 0.5f -> "стоїть"
        speedMps < 2.5f -> "іде пішки"
        speedMps < 8.0f -> "їде повільно"
        else -> "їде"
    }
}

/**
 * The location chain — three rungs, all of them measurements of this device.
 *
 *   1. live MobileSensorAdapter GPS sample (spoof-gated upstream)
 *   2. LocationManager PASSIVE_PROVIDER last-known
 *   3. LocationManager NETWORK_PROVIDER last-known
 *
 * There is no fourth rung, and adding one is the mistake this class exists to
 * prevent. It used to end in an IP-geo lookup, which put the registration
 * country of the operator's ISP on the map as his position (owner report,
 * 2026-08-16 — Romania, while the sky was being correctly refused). Every rung
 * here answers «where is this device»; an IP block answers «whose network is
 * this», and the two are not degrees of the same measurement.
 *
 * When all three are silent the answer is null, and null means «я не знаю, де
 * ти» — the caller must say so rather than substitute a guess.
 *
 * Each source is a callable so the implementation stays Android-free (lookups
 * live in `:app`), and every branch is a unit test.
 */
@Suppress("UNUSED_PARAMETER", "DEPRECATION")
class DefaultLocationContextProvider(
    private val sensorProvider: () -> LocationFix?,
    private val passiveProvider: () -> LocationFix?,
    private val networkProvider: () -> LocationFix?,
    // Retired. Accepted and ignored so the `:app` wiring compiles while it is
    // removed there; see [WebGeoIpFallback].
    webGeoIpFallback: WebGeoIpFallback? = null,
    isGhostMode: () -> Boolean = { false },
    clockMs: () -> Long = System::currentTimeMillis,
    cacheTtlMs: Long = 30L * 60L * 1000L,
) : LocationContextProvider {

    /**
     * Three rungs, then null. A guess never rides at the bottom of this
     * function — see the class doc for what happened when one did.
     */
    override fun current(): LocationFix? {
        runCatching { sensorProvider() }.getOrNull()?.takeIf { it.locatesTheDevice }?.let { return it }
        runCatching { passiveProvider() }.getOrNull()?.takeIf { it.locatesTheDevice }?.let { return it }
        runCatching { networkProvider() }.getOrNull()?.takeIf { it.locatesTheDevice }?.let { return it }
        return null
    }

    /**
     * Retired no-op, kept so the `:app` boot path compiles until its call is
     * removed. It used to fetch ipapi.co; it now performs no network call at
     * all, which is the whole point — the old one fired on every app start.
     */
    @Deprecated("No-op. The IP-geo cache is gone; remove the call site.")
    @Suppress("RedundantSuspendModifier")
    suspend fun refreshIfStale() = Unit
}
