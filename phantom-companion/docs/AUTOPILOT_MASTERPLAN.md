# PHANTOM Autopilot — masterplan

PHANTOM acting on the phone the way a person would: autonomously, for a long
time, independent of the chat turn. Play a game to the end, later triage mail,
chase follow-ups, draft reports. One runtime, one HUD, one safety envelope; each
new capability is a **mission kind** on the same rails.

## Design spine (built in Wave 1)

- **Hybrid mind.** A local decision core does the per-step work for zero tokens;
  the LLM is a rare *supervisor* (start note, anomaly reasoning, ~30 min
  heartbeat, final report). A multi-hour run costs a handful of model calls, not
  thousands — enforced in code by `QuotaGovernor` (hourly/daily caps, min
  interval, 429 back-off).
- **Single mission at a time.** An autonomous agent touching the phone must be
  singular and legible. `MissionEngine` is the one process-global state
  authority; `MissionState` is the single source of truth for the HUD, the
  foreground-service notification and the `mission.status` tool.
- **Safety is layered, never widened by default.** The base
  `AppActionAuthorizer` (vault clamp + sensitive-app deny) always wins first.
  A running mission adds exactly one narrow, audited grant via
  `MissionAuthorizer`: gestures only (never Click/Type), inside the target
  package only, inside the detected surface rect only, only while the surface is
  visible, dies the instant the mission leaves RUNNING. This is what lets the
  2048 pilot play a board that lives inside Monobank without ever being able to
  touch the banking UI around it.
- **Operator is always in command.** Any physical touch → instant pause. The
  floating HUD (`TYPE_ACCESSIBILITY_OVERLAY`, `FLAG_KEEP_SCREEN_ON`) is the
  always-there Pause / Resume / Stop and is what keeps the screen awake for
  hours. Battery floor and screen-off also pause.

## Architecture map

| Concern | Type | Module |
|---|---|---|
| Hands (gestures, screenshot) | `ScreenAwarenessService`, `AppAction.Swipe/Tap/LongPress` | feature-godmode |
| Mission state machine + grant | `MissionEngine`, `Mission`, `MissionAuthorizer` | feature-godmode/mission |
| Resilient host | `AutopilotService` (foreground, specialUse) | feature-godmode/mission |
| Quota ledger | `QuotaGovernor` | feature-godmode/mission |
| HUD | `AutopilotHudOverlay` | feature-godmode/mission/hud |
| 2048 mind | `Board2048`, `Expectimax2048`, `BoardReader`, `Game2048Pilot` | feature-godmode/gamesense |
| Assembly | `AutopilotWiring` | feature-godmode/mission |
| Chat tools | `mission.arm/status/pause/resume/stop` | core-symbiote |

## Waves

### Wave 1 — platform + 2048 (BUILT)
Gestures + in-memory capture; mission runtime; HUD; the 2048 game sense
(bitboard + expectimax + layered board reader with vision calibration cached per
package); arming + chat tools + final report. Acceptance mission: play the
Monobank 2048 to the end, starting when the operator opens it.

> **Board-variant note (from prior 2048 leaderboard work).** Monobank's 2048 is
> the **end-at-2048 leaderboard variant** (the game ends when the 2048 tile is
> made; score is what ranks you), not classic endless play. Empirically an
> expectimax player plateaus around **26–31k average / ~43k best**, and the #1
> board needs a **50k+ tail event**. Relevant knobs are the spawn-4 probability,
> the win/terminal tile, and solver depth. Wave 1 ships classic play-to-loss as
> the default and the deterministic report; **Wave 1.1** below tunes for the
> leaderboard once the live variant is confirmed on-device.

### Wave 1.1 — 2048 leaderboard tuning (next, small)
- Make the solver variant-aware: configurable spawn-4 probability and
  terminal/win tile; a score-maximizing objective for the end-at-2048 variant
  (build the board to squeeze maximum score before the forced 2048), and correct
  detection of the game's "you won / score" end screen vs a natural full-board
  loss.
- Expose depth/prob as mission policy fields; calibrate against the plateau
  numbers above; log per-game score to find the tail-event conditions the #1
  slot needs.

### Wave 2 — mail triage
NotificationReceiver + Gmail UI-driving on mission rails. Read/label/archive with
**per-message operator confirm** for anything outbound; summaries into the
stream. Reuses the pilot loop shape (observe → decide → act → observe) with a
mail "surface" instead of a board.

### Wave 3 — reports & documents
Canvas + research tools driven as a mission: gather → draft → refine → deliver a
document, with heartbeats and a final artifact. Quota budget per mission.

### Wave 4 — scheduled & follow-up missions
WorkManager-scheduled missions (per the OEM battery-kill lesson — coroutine loops
die on Samsung/Xiaomi/Huawei). "Remind/chase X at time T", recurring digests,
deadline follow-ups.

### Wave 5 — generic vision-guided missions
Arbitrary apps with no game solver: `screen.capture` + a supervisor loop that
plans the next action from the screenshot, with a strict per-step quota budget
and the same gesture-grant safety envelope.

## Progress

| Item | Status |
|---|---|
| AP-1 gestures + capture | ✅ built |
| AP-2 mission runtime | ✅ built |
| AP-3 HUD | ✅ built |
| AP-4 2048 game sense | ✅ built |
| AP-5 tools + wiring + report | ✅ built |
| Unit tests (board/solver/reader/authorizer/quota) | ✅ built |
| On-device QA (Monobank 2048) | ⏳ pending operator run |
| Wave 1.1 leaderboard tuning | ⏳ after live-variant confirm |
| Waves 2–5 | ⏳ planned |
