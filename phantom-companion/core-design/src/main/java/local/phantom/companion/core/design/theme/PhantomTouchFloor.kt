package local.phantom.companion.core.design.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The smallest thing a finger is allowed to have to hit.
 *
 * The number was never in the design system, so every surface re-declared it
 * and half of them got it wrong: `PhantomChip`'s private `TAP_MIN`,
 * `feature-stream`'s `BubbleMetrics.MIN_TOUCH`, the since-deleted
 * `PeerChatScreen`'s own `TOUCH` (44dp until 2026-08-15) and the since-deleted
 * `PeopleScreen`'s `TOUCH` (40dp until the same day; the file went 2026-10-04).
 * A constant that lives in one file is a rule that applies to one file.
 *
 * 48dp is the floor for the ordinary case. Destructive controls are held
 * higher — `OfflineTerritoriesPanel`'s delete region is 56dp — but that is a
 * judgement per control, not a second constant to reach for by habit, so it
 * is not offered here.
 *
 * This is a TOUCH floor, not a spacing step. It happens to equal
 * [PhantomSpacings.XXXLarge] today; they are free to diverge, and pointing one
 * at the other would silently retune every 48dp gap the day this moves.
 */
val PhantomTouchFloor: Dp = 48.dp
