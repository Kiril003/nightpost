package local.phantom.companion.core.sensor.nav

/**
 * PHANTOM-EW — how much of the sky PHANTOM currently believes.
 *
 * Waze and Google Maps have exactly one location state: «here you are».
 * Under electronic warfare that is a lie with a confident face — the
 * operator is shown a position that a jammer chose. PHANTOM does not get to
 * do that. It must be able to say «GPS бреше — тримаю останню певну
 * позицію», and the whole engine exists to make that sentence possible.
 *
 * The ladder is monotone in doubt, and the UI is expected to read it that
 * way: anything past [TRUSTED] means the position is an estimate whose
 * error is GROWING, and [FusedFix.uncertaintyM] says by how much.
 */
enum class LocationTrust {
    /** GPS is landing, plausible, and consistent with the inertial track. */
    TRUSTED,

    /** Fixes are landing but poor (urban canyon, weak sky). Fused, not obeyed. */
    DEGRADED,

    /**
     * Fixes are landing and are PHYSICALLY IMPOSSIBLE — the classic spoofing
     * signature (a 15 km leap, 900 km/h, an altitude that jumped a kilometre
     * while the barometer stayed flat). The sky is talking; PHANTOM stops
     * listening to it and keeps its own reckoning.
     */
    SPOOFED,

    /** No fixes at all for longer than [CustomLocationEngine.JAM_TIMEOUT_MS] — jammed or indoors. */
    JAMMED,

    /**
     * Небо ще не відповіло — і це НЕ звинувачення.
     *
     * Стан, з якого рушій починає. Донедавна початковим було [JAMMED], тож
     * застосунок казав «глушіння» ще до того, як побачив бодай один фікс: у
     * приміщенні, на щойно запущеному телефоні, на емуляторі без фікса — смуга
     * стояла намертво. Це рівно та скарга власника, що система «часто кричить
     * про глушіння, коли його немає».
     *
     * Різниця, яку цей стан тримає: «нічого ще не прийшло» — не те саме, що
     * «прийшло й було відкинуто». Друге — глушіння, і воно потребує доказу:
     * небо, що погасло, поки людина РУХАЛАСЬ. Перше — просто відсутність
     * відповіді, і говорити про неї треба іншим словом.
     */
    SEARCHING,
}

/**
 * One position PHANTOM is willing to stand behind — with, always, the size of
 * its own doubt attached.
 *
 * @param uncertaintyM the 1σ radius of the estimate. Under GPS this is the
 *   reported accuracy; under dead reckoning it GROWS without bound, because
 *   inertial navigation on a phone's MEMS sensors drifts and pretending
 *   otherwise is how people drive into rivers.
 * @param dead true when no satellite contributed to this fix — the position
 *   is inertial, and the operator has a right to know that before he turns.
 */
data class FusedFix(
    val lat: Double,
    val lon: Double,
    val speedMps: Float,
    val bearingDeg: Float,
    val uncertaintyM: Float,
    val trust: LocationTrust,
    val dead: Boolean,
    /** Metres above the session's reference pressure, or null with no barometer. */
    val altitudeM: Float? = null,
    /** PHANTOM-EW — the altimeter's verdict on where the operator's body is. */
    val vertical: VerticalContext = VerticalContext.UNKNOWN,
    val timestampMs: Long = 0L,
)

/**
 * PHANTOM-EW — what the barometer knows that the map cannot: a GPS fix on a
 * road and a GPS fix in the stairwell of the building beside it are the same
 * two numbers. The altimeter is what separates them, and it keeps working
 * when the sky is jammed — pressure cannot be spoofed from a distance.
 */
enum class VerticalContext {
    /** No barometer, or not enough baseline yet. Honest ignorance. */
    UNKNOWN,

    /** Altitude is tracking the terrain: the operator is on the ground/road. */
    GROUND,

    /** A sustained climb or fall with no road gradient to explain it — a building. */
    VERTICAL_TRANSIT,

    /** Settled well above (or below) the entry reference — a floor, or a basement. */
    ELEVATED,
}
