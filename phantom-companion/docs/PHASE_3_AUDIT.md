# Phase 3 GodMode v1 → v2 Audit (W4-1)

> Findings collected before Phase 3 GodMode v2 reimplementation begins.
> Single read-only commit. No behavioural change.

## TL;DR

V1 scaffolding from old commits (`b4fe988` phase-3-a-deep-vision,
`4fefad1` phase-3-a-multi-step, `b23ae14` phase-3-c, `17588c4`
phase-3-c-3-d, `3a1c29b` phase-3-b) survived the companion-v2 reset
(`4a2cd44`). All four `AccessibilityService` /
`NotificationListenerService` / `CallScreeningService` /
`InCallService` classes are **declared in the merged manifest** and
will bind on the SM-A566B if the operator grants permissions, but the
v2 surfaces (Stream UI, AIRouter context, Room persistence,
NoticeBar push) are mostly wire-empty. Phase 1's intelligence-gap
pattern recurs: **pipe alive, surface empty.**

`GodModeEventBus` is critical-path now: `ForegroundAppMonitor`
(Phase 1.5-C anti-loop) subscribes to `ScreenChanged` to drive
sensitive-app whisper auto-hide. Anything that breaks the bus breaks
W1-3. Treat the bus as a stable contract; only ADD new event types.

`TaskerEngine` is already richer than expected: AI-powered screen
analysis, NotificationBurst summary, IncomingCall question prompt,
and a hard-coded Waze reasoning demo. Wired into `AppContainer`
(`taskerEngine` lazy) and `StreamViewModel` (`taskerEngine: TaskerEngine?`
ctor param). Used pattern-storage is in-memory only — that becomes
W4-7's job.

`SecretaryInCallService` cannot bind on Android 14+ for non-default
dialers. Confirmed via Android docs (`InCallService` requires
`PhoneAccountHandle.CAPABILITY_CALL_PROVIDER` registration which is
ROLE_DIALER-gated). DELETE in W4-6.

## Per-service findings

| Service | Status | Decision | Re-implement strategy |
|---|---|---|---|
| `ScreenAwarenessService` | **Live**. Manifest declares it; `accessibility_service_config.xml` wired; runs on permission grant. Captures `TYPE_WINDOW_STATE_CHANGED` + `TYPE_WINDOW_CONTENT_CHANGED`, recursively walks node tree, dumps `[x,y,w,h] text \| ...` to `GodModeEventBus.ScreenSnapshot` (≤5000 chars). Also exposes `GlobalAction` / `ClickText` / `TypeText` command path consumed via `GodModeEventBus.commands`. Settings → Permissions has an `isAccessibilityServiceEnabled(...)` check (`SettingsDialog.kt:442`). | **Keep + expand.** Bus contract is stable. | W4-2 introduces a pure `ScreenContextExtractor` (replaces in-service walk) + `ScreenContextStore` cache; service emits structured `ScreenContext` (packageName, appLabel via PackageManager, rootClass, top-N visibleText by area, ts). `StreamViewModel.enrichedContext` reads store and prepends `[SCREEN: app=…, top-text=…]` block alongside `[LOCATION:…]`. Permission flow: rationale dialog + per-OEM Accessibility deeplink + Diagnostics live status. Gate by `voicePrefs.screenAwarenessEnabled`. W4-3 adds long-press / 2-finger gesture listener for `[ОПЕРАТОР ВИБРАВ:"…"]`. W4-4 adds `AccessibilityActionDispatcher` driven by AIRouter's new `app-action` widget. |
| `NotificationReceiverService` | **Stub.** Manifest declares it (`BIND_NOTIFICATION_LISTENER_SERVICE`); receives callbacks once user grants Notification Access. Captures `android.title` + `android.text`, naive 30s burst window (≥3 in 30s emits `NotificationBurst`), single notifications emit raw `Notification`. **No Stream consumer** — events feed `TaskerEngine.processEvent` for hard-coded "bank+Payment" notice and AI-summarized burst. No sensitive-content stripping, no per-channel classifier, no daily digest, no Settings UI. | **Replace.** | W4-5 splits into `NotificationClassifier` (sensitive denylist via `core-data/.../sensitive/SensitiveAppDenylist.kt` regex + per-channel rules → Important / Routine / Spam), Room v8 `notification_digest` table + DAO + repository, daily 9am `WorkManager` digest job, Settings → Notifications screen (per-app rules, quiet hours, denylist). Important pushes go directly to `StreamViewModel` Notice; Routine queues; Spam logged-only. |
| `SecretaryCallScreeningService` | **Stub.** Manifest declares it (`BIND_SCREENING_SERVICE`); on incoming call respondsToCall with everything-allowed (`setDisallowCall(false)` etc.) and pushes `IncomingCall(phoneNumber)` to bus. `TaskerEngine.processEvent` surfaces a `Question(...)` action with three Ukrainian options but Stream has **no `IncomingCallCard`** consumer of that, so it visibly does nothing today. | **Keep service shell, fill body.** | W4-6 adds `CallScreeningPolicy` (denylist / trusted contacts / unknown handling / vault-locked behaviour), `core-data/.../calls/CallLogEntry` Room v9 table, `IncomingCallCard` Stream UI rendering the policy decision, and CONTRACT §10.18 docs-only `call_screen` channel spec (mobile↔desktop). Outgoing-call uplift is `Intent.ACTION_CALL` driven from W4-4 `app-action`. |
| `SecretaryInCallService` | **Manifest-declared, will not bind.** Android 14+ requires the app hold `RoleManager.ROLE_DIALER` for `InCallService` to bind for any inbound/outbound call. Phantom is not the default dialer (and shouldn't be — operator already has Samsung Phone). Service contains call-state callback wiring + bus push, but the framework will never invoke it. Dead code masquerading as a feature. | **DELETE** in W4-6 commit. | Remove file + manifest entry + any stale references. CallScreening-only model from W4-6 carries the load. Real "phantom answers in operator's voice" path defers to Phase 4 when StyleTTS2 inference + phantom-os Telecom integration ship together. |
| `TaskerEngine` | **Live and consumed.** Wired in `AppContainer.kt:406` (lazy + `aiRouter` injection) and `StreamViewModel` ctor (`taskerEngine: TaskerEngine? = null`). `init` block subscribes to `GodModeEventBus.events` for screen-snapshot AI analysis with `should-analyze` heuristic (length-delta > 500 OR > 2 min since last analysis). Emits `TaskerAction.{Question,Notice,Reasoning,Chart}` via internal `MutableSharedFlow`. **No persistence**, **no occurrence-counting**, hard-coded Waze branch. | **Keep as base + extend.** | W4-7 introduces Room v10 `patterns` table (`id, trigger_kind, trigger_value, suggested_action, confidence_score, occurrence_count, last_observed_at`), `PatternEntity` + DAO + repository, `PatternDetector` engine that observes time-of-day / app-launch / geo signals from `MobileSensorAdapter`, threshold 3+ within 7 days → `InsightCard` Stream surfacing with Так / Не зараз / Не пиши більше outcome. Existing AI-screen-analysis / NotificationBurst-summary paths stay; the Waze hard-coded branch is dropped in favour of generic detector. |
| `GodModeEventBus` | **Critical infrastructure.** Used by `AppContainer.init` for `lifeLog.archiveEvent` + `ForegroundAppMonitor.update` (Phase 1.5-C anti-loop sensitive-app hide). Used by `StreamViewModel:559` to push `GodModeCommand` for AI-driven app actions. `TaskerEngine.init` collects from it. Anything that breaks `ScreenChanged` event flow breaks W1-3 anti-loop and the operator's vault-protection invariant. | **DO NOT MODIFY existing event types.** Only ADD. | W4-2 adds `ScreenContext` event (or moves to a separate cold flow exposed by `ScreenContextStore`); W4-3 adds `SelectedText`; W4-4 adds `AppAction*` request/result events; W4-5 adds `NotificationDecision` (already-classified). Existing `ScreenChanged`, `ScreenSnapshot`, `Notification`, `NotificationBurst`, `IncomingCall` event classes remain wire-compatible. |

## Manifest reality check

`feature-godmode/src/main/AndroidManifest.xml` declares all four
services. Manifest merger pulls them into the app APK (`grep`-ed
`build/intermediates/merged_manifest/.../AndroidManifest.xml` — all
four present). On a fresh install:

* Notification Listener access — operator grants in
  Settings → Apps → Special access → Notification access.
* Accessibility — operator grants in
  Settings → Accessibility → Installed services →
  «Familiar Screen Awareness».
* CallScreening — system auto-binds for any installed app declaring
  the action (no operator grant needed; Android picks among installed
  CallScreening services in display priority order, with default
  dialer winning ties).
* InCallService — never binds for Phantom; will be removed.

`app/src/main/AndroidManifest.xml` already includes
`SYSTEM_ALERT_WINDOW` (Phase 3-E orb), `FOREGROUND_SERVICE_HEALTH`,
`FOREGROUND_SERVICE_LOCATION`, `BODY_SENSORS`. The `READ_PHONE_STATE`
+ `READ_CALL_LOG` permissions that CallScreeningService benefits
from are NOT yet declared — W4-6 must add them.

## Open questions for v2

1. **ScreenContextStore broadcast cadence.** v1 service tries to
   emit on every `TYPE_WINDOW_CONTENT_CHANGED`, which on a busy
   screen (e.g. Telegram chat scrolling) means dozens per second.
   v2 must throttle in the extractor — propose 500ms debounce
   matching the existing 100ms `notificationTimeout` config × 5×.
2. **PackageManager appLabel resolution cost.** Cheap once cached;
   propose lazy `LruCache<String, String>` keyed by packageName in
   `ScreenContextStore`.
3. **`GodModeEventBus` vs new `ScreenContextStore` flow.** Two
   possible designs:
   1. extend bus with `ScreenContext` event (uniform consumer
      pattern, but couples store cadence to bus throughput);
   2. expose dedicated `StateFlow<ScreenContext?>` on the store
      (decoupled cadence, easier to reason about for AIRouter
      enrichedContext which only ever needs latest).
   Pick (2). AIRouter consumes a snapshot at request-build time;
   bus stays untouched for back-compat with `ForegroundAppMonitor`.
4. **§10.18 `call_screen` channel scope.** Mirror to phantom-os so
   the desktop knows when phantom screened a call. Spec docs-only
   in W4-6; no backend implementation this session.

## Non-decisions deferred

* StyleTTS2 voice playback into a live call — Phase 4.
* ML-based `NotificationClassifier` (currently rule-based with
  optional AI assist) — Phase 4 or later when we have on-device
  Gemma reliably running.
* CallScreening "decline + AI answers in your voice" — Phase 4.
* Attack-tree review of `app-action` against banking and password
  managers — covered partially by `AppActionAuthorizer` denylist in
  W4-4; deeper review (rooted device, intent-spoofing) deferred.

## Wire diagram (after Phase 3 GodMode v2 lands)

```
                  ┌───────────────────────────────────────────────┐
                  │                AccessibilityService            │
                  │   ScreenAwarenessService (W4-2/3/4)            │
                  ├──────────────┬────────────────────────────────┤
        events    │              │ commands (AppAction)            │
        ScreenCtx │              │                                 │
                  ▼              ▼                                 │
   ScreenContextStore   AccessibilityActionDispatcher              │
        │  StateFlow                │                              │
        ▼                            ▼                             │
   StreamViewModel.enrichedContext   GodModeEventBus               │
        │                            │                             │
        ▼                            ▼                             │
       AIRouter                 AppActionWidget (Stream)           │
                                                                   │
   NotificationListenerService ──► NotificationClassifier ────►    │
       (W4-5)                      Stream Notice / DigestDao       │
                                                                   │
   CallScreeningService ──────────► CallScreeningPolicy ─────►     │
       (W4-6)                      IncomingCallCard / call_log     │
                                                                   │
   GodModeEventBus.events ────────► PatternDetector (W4-7) ──►     │
                                    InsightCard (Stream)           │
```

## Acceptance for W4-1

* This file lands.
* No Kotlin / resource changes.
* Single commit.
* Build green (`./gradlew :feature-godmode:test :app:assembleDebug`
  reproduces baseline at HEAD).

Outcome map (which W4-N commit handles each v1 file):

| v1 file | W4 commit |
|---|---|
| `service/ScreenAwarenessService.kt` | W4-2 (extractor pull-out) + W4-3 (gesture) + W4-4 (action dispatch listener hook) |
| `service/NotificationReceiverService.kt` | W4-5 (classifier rewrite) |
| `service/SecretaryCallScreeningService.kt` | W4-6 (policy fill-in) |
| `service/SecretaryInCallService.kt` | **W4-6 — DELETED + manifest entry removed** |
| `engine/TaskerEngine.kt` | W4-7 (Room v10 persistence + detector promotion) |
| `engine/GodModeEventBus.kt` | unchanged (bus is contract, extended only) |
| `Module.kt` KDoc | W4-7 (refresh KDoc to v2 reality) |

— Phase 3 GodMode v2, audit closed.
