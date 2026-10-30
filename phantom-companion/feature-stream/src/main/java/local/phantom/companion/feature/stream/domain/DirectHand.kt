package local.phantom.companion.feature.stream.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * Chat-upgrade-2 — the Hands Deck: device commands the operator fires
 * DIRECTLY, with zero LLM turns. Same philosophy that fixed the map
 * (WorldMenu): PHANTOM-driven is not enough — the operator must be able to
 * reach the phone's hands with one tap.
 *
 * Each kind maps to an EXISTING registered tool (R5 Device Hands /
 * `settings`) inside StreamViewModel.handleDirectHand — the tap reuses the
 * full runToolCall path (permission gate, decision diary, tool-trace line
 * in the stream), so a direct action is exactly as audited and exactly as
 * visible as a model-initiated one (Law 2).
 *
 * The deck lives in the island's Chest sheet, not in the composer. This
 * KDoc said "from the composer" for a year while the composer was mounted
 * only when `composerExpanded` is true (StreamScreen :928) — which means
 * behind a keyboard, on one route, gone the moment the trough folds.
 * Nobody who wants a torch is writing a message.
 */
enum class DirectHandKind(val label: String) {
    /** Torch toggle over `settings.flashlight`; on/off state comes from the
     *  system via [DirectHandsWiring.torchOn], not from a local guess. */
    FLASHLIGHT("ліхтарик"),

    /** Media volume −15% over `settings.volume` delta. */
    VOLUME_DOWN("тихіше"),

    /** Media volume +15% over `settings.volume` delta. */
    VOLUME_UP("гучніше"),

    /** 5-minute system countdown over `phone.timer`. */
    TIMER_5MIN("таймер 5 хв"),

    /** Wi-Fi system panel over `phone.panel`. */
    WIFI_PANEL("Wi-Fi панель"),
}

/**
 * What `:app` must answer before the deck may be drawn — only a caller
 * holding an Android Context can answer either question honestly.
 *
 * Null wiring means no deck at all (Law 1, unwired = absent), which is why
 * this is a nullable field on StreamTooling rather than a set of defaults.
 */
class DirectHandsWiring(
    /**
     * Which hands this device actually has. Run once at VM start. A phone
     * with no flash unit and no clock app must not be offered a torch or a
     * timer: the tool would refuse, and an offer that always refuses is a
     * promise, not a control.
     */
    val probe: suspend () -> Set<DirectHandKind>,
    /**
     * System torch state, as the camera service reports it — so the chip
     * also follows a torch switched from the notification shade, and a
     * failed run cannot leave the label lying.
     */
    val torchOn: StateFlow<Boolean>,
)
