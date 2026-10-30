# Phase 3 GodMode v2 Overnight — 10-14 Hour Prompt

> Copy-paste **everything between the two `===` lines** into a new
> Claude Code session. Target run-time: **~10-14 hours Claude Code
> (≈ 1.5-2 months human equivalent)**. 7-9 atomic commits across
> three sub-tracks (Screen + Notifications + Calls). After this lands,
> Phantom can SEE what's on screen, NAVIGATE apps for the operator,
> SUMMARIZE notifications, and SCREEN incoming calls.

```
============================================================
You are joining the PHANTOM Companion project AFTER Phase 2.5
Foundations closed (commit `587164b`). Phase 1, 1.5, 2, 2 hotfix,
2 Quality Hardening, 2.5 Foundations all shipped — operator now has
production-quality voice loop, Whisper Line overlay, generative
widgets, persistent Stream history, reactive theme + settings,
diagnostic surface, and design fidelity to v4 prototype.

Phase 3 GodMode v2 is the next leap: Phantom stops being «a
pretty chat with widgets» and becomes a real digital symbiont
that watches the screen, navigates apps for the operator,
filters notifications, and intercepts calls.

V1-era scaffolding for these capabilities exists in `:feature-godmode/`
from old commits (b4fe988 phase-3-a-deep-vision, 4fefad1 phase-3-a-multi-step,
b23ae14 phase-3-c, 17588c4 phase-3-c-3-d, 3a1c29b phase-3-b). Those
were shipped before companion-v2 reset (4a2cd44) and never wired
into the v2 AIRouter / Stream UI. Your job is audit + re-implement
to v2 standards.

═══ READ FIRST ═══

1. docs/PHASE_3_GODMODE_OVERNIGHT_PROMPT.md
   THIS file. 7-9 atomic commits with file-level granularity below.

2. docs/PHASE_2_REMEDIATION_AND_3_FOUNDATIONS_PROMPT.md
   Original mega-prompt. Wave 4 verbatim — but THIS prompt's order is
   refined and includes pre-flight from Phase 2.5 carry-forward.

3. docs/VISION_NEXT.md
   §Tier 6 §2-C Screen content + §3-A AccessibilityService chain.
   §Tier 6 §3-B,C,D NotificationListener / TelecomManager / Tasker.

4. docs/COMPANION_CONTRACT.md
   §10.15 / §10.16 already exist. Add §10.18 in this session for the
   `call_screen` channel (mirror to phantom-os so the desktop knows
   when phantom screened a call).

5. ~/.claude/projects/-home-radxa-programming-my-own-phantom-ai-PHANTOM-OS-BLUEPRINT-phantom-companion/memory/MEMORY.md
   9 entries. CRITICAL for tonight:
   - `host_agent_concurrency.md` — sequential Agent dispatch only.
     The host crashed Claude Code with parallel subagents.
   - `vault_lock_one_way_invariant.md` — UI sessionUnlocked, never
     vaultStore.lockNow() from voluntary actions.
   - `kdoc_close_inside_backticks.md` — KSP backtick gotcha.

6. /tmp/phantom-design-4/phantom-app2/project/extras.jsx
   FloatingOrbOverlay design + system-alert-window patterns. Sibling
   pattern for accessibility-driven overlays.

7. feature-godmode/ existing service files (read each):
   - service/ScreenAwarenessService.kt          (v1 AccessibilityService)
   - service/NotificationReceiverService.kt     (v1 NotificationListener)
   - service/SecretaryCallScreeningService.kt   (v1 CallScreeningService)
   - service/SecretaryInCallService.kt          (v1 InCallService — Android 14 deprecated for non-default dialer)
   - engine/TaskerEngine.kt                     (v1 pattern detection)
   - engine/GodModeEventBus.kt                  (v1 — already used by W1-3 anti-loop)

═══ CURRENT STATE (verified) ═══

Branch: `companion-v2-phase-0`
Last commit at session start should be `587164b` or later (Phase 2.5
TODO cleanup).
Working tree: clean before you start (`git status` to verify).
Tests: ~520+ unit tests, all green per CI.
Build: `:app:assembleDebug` ~3:52 on Radxa SBC, 286MB debug APK.
Latest APK in operator's hands:
`~/Downloads/phantom-companion-w2-fe09576-20260510-1713.apk`

═══ PRE-FLIGHT (optional, ~1.5 hr) ═══

If host shows healthy first hour AND you finish W4-1 in <90 min,
spend the margin time clearing Phase 2.5's silent tech-debt before
starting bigger Phase 3 surface area. These are TODO(Phase 3)
markers from commit `587164b`:

  PF-1  per-state UI sweep — feature-side composables import
        `MotionTokens.snapSpring*` / `breathingSpring*` / `fadeQuart`.
        Five composables to audit: OrbView, StreamScreen, RouteWidget,
        WhisperLineRibbon, OnboardingScreen. Replace inline tween
        values with the canonical springs.

  PF-2  ThreadRail consumes ThreadMetaRepository.observeAll() —
        UI in `:feature-stream/.../ui/ThreadRail.kt` switches from
        StreamViewModel.threads to the persistent flow. Default
        «Зараз» thread bootstraps once at AppContainer init.

  PF-3  GHOST hard-suppress at StreamPersistenceWriter — gate
        [observe] short-circuit when GHOST is true. Drop
        post-hoc cleanup. Never enqueue GHOST entries.

These are **opt-in**. If host is at all stressed (build times >
5 min, OOM warnings, slow Bash), SKIP these and go straight to W4-1.
The mega-prompt's later sessions will fold them in.

═══ PHASE 3 GODMODE v2 — 7 ATOMIC COMMITS ═══

──────────────────────────────────────────────────────
W4-1 — Audit v1 GodMode services + map to v2 (1 commit, ~1-1.5 hour)
──────────────────────────────────────────────────────

READ each existing service file in `:feature-godmode/`:
- ScreenAwarenessService.kt — what does it actually capture? Where
  does it dispatch?
- NotificationReceiverService.kt — registered in manifest? receives
  any callbacks? extracts what fields?
- SecretaryCallScreeningService.kt — registered as CallScreening?
  blocks numbers? routes anywhere?
- SecretaryInCallService.kt — Android 14+ blocks non-default
  dialers from binding. Confirm and FLAG FOR DELETION.
- TaskerEngine.kt — what triggers? what stores patterns? wired to
  what UI?
- GodModeEventBus.kt — already used by W1-3 anti-loop in
  StreamViewModel; read carefully so you don't break it.

Write `docs/PHASE_3_AUDIT.md` with findings:
| Service | Status | Decision | Re-implement strategy |

DELETE strategy decisions go in this commit; actual deletes happen in
later phases as they're replaced.

Acceptance:
- audit doc lands
- single commit, no behavioural change to existing surfaces

──────────────────────────────────────────────────────
W4-2 — 3-A v2 ScreenContext → AIRouter (1 commit, ~1.5 hours)
──────────────────────────────────────────────────────

ScreenAwarenessService extracts whatever current AccessibilityNodeInfo
tree gives it → emits structured `ScreenContext`:
```kotlin
data class ScreenContext(
    val packageName: String,         // "com.android.chrome"
    val appLabel: String?,           // resolves via PackageManager
    val rootClass: String?,          // root view class name
    val visibleText: List<String>,   // top-N text nodes by area
    val capturedAtMs: Long,
)
```

Inject into `enrichedContext` for AIRouter alongside `[LOCATION]` from
QH3:
```
[SCREEN: app=Chrome, top-text="Buy tickets · Eurostar London-Paris"]
```

Block ALL of this by `voicePrefs.screenAwarenessEnabled` toggle
(Settings → Permissions → Screen Awareness). Permission flow:
- Per-OEM deeplink to Accessibility settings (Samsung One UI 7
  / Xiaomi MIUI / Google Pixel direct)
- In-app rationale dialog: «фантом дивиться екран щоб допомагати,
  не записує тебе. дозволиш?»
- Settings → Діагностика shows live status: «екран: ✓ читає» / «екран: ·».

Files:
- new `feature-godmode/.../engine/ScreenContextExtractor.kt` (pure)
- modify `feature-godmode/.../service/ScreenAwarenessService.kt`
- new `feature-godmode/.../engine/ScreenContextStore.kt` (façade)
- modify `app/.../data/AppContainer.kt` to inject store
- modify `feature-stream/.../StreamViewModel.kt` `enrichedContext` builder
- new `app/.../ui/settings/PermissionsScreen.kt` — Accessibility
  toggle + per-OEM deeplinks
- tests: `ScreenContextExtractorTest`, `EnrichedContextScreenInjectionTest`

Acceptance: with toggle ON + permission granted, send a prompt while
Chrome is open → `[SCREEN:` block lands in AI context, AI references
visible page text in reply.

──────────────────────────────────────────────────────
W4-3 — 3-A v2 selected-text gesture (1 commit, ~1 hour)
──────────────────────────────────────────────────────

AccessibilityService event listener for ACTION_LONG_CLICK on text
nodes (or 2-finger long-press global gesture detector via the
service's gestureDetector). Captures selected text → injects into AI
context as `[ОПЕРАТОР ВИБРАВ:"$text"]` → opens Stream with auto-prompt
«що це?» if gesture fired from outside Phantom.

Files:
- modify `ScreenAwarenessService` add gesture listener
- new `feature-godmode/.../engine/SelectedTextRouter.kt` — routes
  selection to either current Stream prompt or new query
- modify `StreamViewModel` to accept queued auto-prompt
- new `feature-godmode/.../ui/QuickAskBubble.kt` — floating «що це?»
  pill that appears for 3 sec on selection

Acceptance: long-press a word in any app → quick-ask bubble appears
→ tap → Stream opens with «що це?» pre-filled + selected text in
context → AI answers.

──────────────────────────────────────────────────────
W4-4 — 3-A v2 app-action dispatcher (1 commit, ~2 hours)
──────────────────────────────────────────────────────

AIRouter system prompt extends with a new widget type:
```json
{"type":"app-action",
 "actions":[
   {"action":"click", "target":{"text":"Налаштування"}},
   {"action":"scroll", "direction":"down", "amount":"page"},
   {"action":"type", "value":"Wi-Fi"},
   {"action":"back"}
 ]}
```

`feature-godmode/.../engine/AccessibilityActionDispatcher.kt` parses
the action sequence and executes via AccessibilityNodeInfo APIs:
- click: find by text/id/bounds → performAction(ACTION_CLICK)
- scroll: ACTION_SCROLL_FORWARD on first scrollable ancestor
- type: ACTION_SET_TEXT after focus
- back/home/recents: GLOBAL_ACTION_BACK / HOME / RECENTS

Stream renders progressive ToolTrace updates:
«[крок 1/4] натискаю «Налаштування»» → executed → next step.

Safety guards:
- max 8 steps per dispatch (no infinite chains)
- 200ms minimum between steps (operator can hit cancel)
- ANY ACTION_CLICK on banking apps OR password apps → SUSPENDED,
  Stream Notice «дія заблокована · банкінг»
- VAULT-locked → all actions suspended until unlock

Files:
- new `feature-godmode/.../engine/AccessibilityActionDispatcher.kt`
- new `feature-godmode/.../engine/AppActionAuthorizer.kt` (denylist)
- modify `core-ai/.../SystemPrompts.kt` add `app-action` description
- modify `feature-stream/.../widget/AppActionWidget.kt` (new) — render
  progressive ToolTrace; receives state from EventBus
- tests: dispatcher unit tests with mock AccessibilityNodeInfo,
  authorizer denylist tests, max-step guard test

Acceptance: «відкрий Telegram і знайди чат з Олегом» → AI emits
4-step app-action JSON → dispatcher executes step-by-step → Stream
shows progress updates → Telegram opens to the right chat.

──────────────────────────────────────────────────────
W4-5 — 3-B v2 Notification triage (1 commit, ~1.5 hours)
──────────────────────────────────────────────────────

Re-implement NotificationReceiverService:
- onNotificationPosted → strip sensitive content (banking codes /
  OTP via regex denylist patterns) → classify as Important / Routine
  / Spam (per-channel ML or AI-assisted)
- Important → push as Stream NoticeBar IMMEDIATELY
- Routine → queue for daily digest
- Spam → silent, log only

Daily digest at 9am (WorkManager):
«ранок · вчорашня доба: 3 нові від Олі · 2 нагадування · 1 email
від адвоката про контракт. Розгорнути?»

Settings → Notifications surface:
- Per-app rules (Important / Routine / Spam by default + override)
- Quiet hours (no Important pushes between 22:00 — 8:00)
- Sensitive-app denylist (extends Whisper auto-hide list)

Files:
- modify `feature-godmode/.../service/NotificationReceiverService.kt`
- new `feature-godmode/.../engine/NotificationClassifier.kt`
- new `core-data/.../notifications/NotificationDigestEntry.kt`
  + DAO + Repository — Room v8 migration adds `notification_digest`
  table
- new `app/.../ui/settings/NotificationSettingsScreen.kt`
- modify `feature-stream/.../StreamViewModel.kt` to consume Important
  → Notice
- tests: classifier unit tests, sensitive-content stripping,
  daily digest WorkManager test

Acceptance: Telegram message arrives → if Important per rules →
Stream Notice immediately. Banking SMS — never reaches Stream
(stripped + classified spam). 9am next day → digest Notice with
yesterday's Routine items.

──────────────────────────────────────────────────────
W4-6 — 3-C v2 Call Screening API (1 commit, ~1.5 hours)
──────────────────────────────────────────────────────

Android 14+ blocks InCallService for non-default dialers. Use
CallScreeningService instead. Phantom can:
- intercept incoming calls before they ring
- decide allow/silence/reject based on caller ID + behavior history
- show incoming-call card in Stream with «прийняти за тебе» option
- log all screened calls to Room

DELETE `SecretaryInCallService.kt` (v1 deprecated approach).

Outgoing calls: just `Intent.ACTION_CALL` with permission. Operator
voice command «зателефонуй Олегу» → AI emits `app-action` with
phone-number resolved via Contacts → ACTION_CALL fires.

Real AI Secretary speak-to-caller (phantom answers in operator's
voice) is OUT OF SCOPE this Wave — defer to Phase 4 when StyleTTS2
inference + phantom-os Telecom integration ship.

CONTRACT §10.18 — `call_screen` channel:
```
channel: call_screen
verbs:
  - incoming { caller_id, label?, decision }   // mobile → desktop
  - decision_recorded { id, action }            // mobile → desktop
  - request_screen { caller_id }                // desktop → mobile (future)
direction: bidirectional
```

Files:
- modify `feature-godmode/.../service/SecretaryCallScreeningService.kt`
- new `feature-godmode/.../engine/CallScreeningPolicy.kt` — denylist /
  trusted contacts / unknown handling
- new `core-data/.../calls/CallLogEntry.kt` + DAO + Room v9 migration
  `call_screen_log` table
- DELETE `SecretaryInCallService.kt` + manifest entry
- modify `docs/COMPANION_CONTRACT.md` — append §10.18
- new `feature-stream/.../ui/IncomingCallCard.kt`
- tests: policy classification, log persistence

Acceptance: incoming call from unknown number → if policy says
"screen" → call silenced + Stream card «дзвінок · невідомий номер
· прийняти / відхилити / спочатку послухати». Operator decides.
Decision recorded in call_screen_log.

──────────────────────────────────────────────────────
W4-7 — 3-D Tasker pattern detector light (1 commit, ~1.5 hours)
──────────────────────────────────────────────────────

Promote v1 TaskerEngine to v2:
- Persist patterns to Room v10 `patterns` table
  ```sql
  CREATE TABLE patterns(
    id TEXT PRIMARY KEY,
    trigger_kind TEXT NOT NULL,     -- 'geo','time','app','intent'
    trigger_value TEXT NOT NULL,    -- json depending on kind
    suggested_action TEXT NOT NULL,
    confidence_score REAL NOT NULL,
    occurrence_count INTEGER NOT NULL DEFAULT 0,
    last_observed_at INTEGER NOT NULL
  );
  ```
- Detector observes:
  - Time-of-day patterns: «щовівторка о 9:00 ти готуєш каву»
    (TimerWidget creation + 'каву' keyword + DOW/HH match)
  - App-launch patterns: «після Slack ти завжди читаєш Linear 5хв»
  - Geo patterns: «коли вдома о 19:00 — TikTok 20хв» (depends on
    sensorAdapter.gps)
- Threshold: 3+ occurrences within 7 days → surface as InsightCard
  in Stream:
  «помітив: щовівторка о 9:00 ти готуєш каву → нагадати?»
  [Так, нагадай] [Не зараз] [Не пиши про це більше]

Files:
- modify `feature-godmode/.../engine/TaskerEngine.kt`
- new `core-data/.../patterns/PatternEntity.kt` + DAO + Room v10
- new `feature-godmode/.../engine/PatternDetector.kt`
- modify `feature-stream/.../StreamViewModel.kt` to consume
  insight events
- tests: detector with synthetic event sequences, threshold logic,
  «не пиши більше» suppression

Acceptance: simulate 4× «таймер на каву» events at 9am Tuesdays →
on 5th week, Stream gets InsightCard suggestion.

═══ NON-NEGOTIABLE CONSTRAINTS ═══

1. **One phase = one atomic commit.** Build green per commit.
2. **Sequential subagent dispatch.** Memory: host crashes on
   parallel Agents. ONE at a time. Direct tools first.
3. **Read before edit.** Edit hook will reject otherwise.
4. **Don't break Phase 1/1.5/2/2.5.** All 7 surfaces, all widgets,
   all reactive settings, Stream history persistence — all green
   after each commit.
5. **No new dependencies.** AccessibilityService, NotificationListener,
   CallScreeningService — all platform APIs, no libs.
6. **No phantom-os sibling-repo touches.** §10.18 backend route is
   docs-only.
7. **Operator's exact words verbatim** in user-facing copy. Ukrainian,
   ти-form, lowercase, intimate.
8. **Permission flows are sensitive.** Accessibility + NotificationListener
   + CallScreening = operator-trust-defining permissions. Each must
   have rationale dialog + per-OEM deeplink. NEVER auto-grant; NEVER
   request without rationale.
9. **Banking + OTP denylist is sacred.** AppActionAuthorizer (W4-4) +
   NotificationClassifier sensitive-strip (W4-5) + Whisper auto-hide
   (Phase 1.5-C) — all share the same denylist. Centralize the list
   in `core-data/.../sensitive/SensitiveAppDenylist.kt` if not already
   centralized.
10. **InCallService is dead.** Do not try to register the v1
    SecretaryInCallService — Android 14 blocks it. CallScreening
    only.

═══ HOST PROTECTION ═══

After Phase 2 overnight crashed Claude Code via parallel Agents:
- ZERO parallel `Agent(subagent_type=...)` dispatches.
- Direct tools (Read/Bash/Grep/Edit) — fine to parallel.
- Long Bash — `run_in_background: true`, NEVER poll, wait for
  notification.
- If a phase is taking >2× budgeted hours, STOP at next commit
  boundary, write `docs/PHASE_3_HANDOFF.md`.

═══ REPORTING FORMAT ═══

After each commit, 3-5 sentences:
1. W4-N + 1-line scope
2. Files changed (count + 2-3 paths)
3. Test result + build result
4. Anything surprising
5. Next phase or pause

After W4-7 final:
1. Total commits + LoC delta + new tests
2. APK path + md5 + size
3. 7-item operator verification checklist (one per W4-1..W4-7)
4. STOP. No mega-prompt Wave 5/6 this session.

═══ POST-APK OPERATOR VERIFICATION ═══

For W4-7 to be «зеленим»:

1. Settings → Permissions → Screen Awareness → grant Accessibility
   per per-OEM deeplink → see «екран: ✓ читає» in Diagnostics.
2. Open Chrome with a news article → ask phantom «про що ця стаття?»
   → AI answers using `[SCREEN:` context (verifying W4-2).
3. Long-press a word in any app → quick-ask bubble appears →
   tap → Stream opens, AI explains (W4-3).
4. Say «відкрий Telegram і знайди чат з Олегом» → step-by-step
   ToolTrace updates → Telegram opens to Олег's chat (W4-4).
5. Receive Telegram message → if «important» per rules → Stream
   Notice immediately (W4-5).
6. Tomorrow 9am → Stream digest Notice with yesterday's routine
   notifications (W4-5 part 2).
7. Receive call from unknown number → call silenced + Stream
   incoming-call card with 3 actions (W4-6).
8. Simulate 4× timer on Tuesdays at 9am → on 5th week, InsightCard
   appears (W4-7).

═══ REFERENCE PATHS QUICK-CARD ═══

```
docs/PHASE_3_GODMODE_OVERNIGHT_PROMPT.md  ← THIS file
docs/PHASE_2_REMEDIATION_AND_3_FOUNDATIONS_PROMPT.md ← mega
docs/PHASE_2_5_FOUNDATIONS_PROMPT.md      ← prior session DONE
docs/COMPANION_CONTRACT.md                ← extend §10.18
docs/VISION_NEXT.md                       ← Tier 6 §3-A,B,C,D
docs/PHASE_3_AUDIT.md                     ← W4-1 output
~/.claude/projects/.../memory/            ← 9 entries
git log --oneline 587164b..HEAD           ← your commits this session
phantom-os/src/backend/                   ← READ-ONLY sibling
```

You have ~10-14 hours. ~7-9 atomic commits. One green APK at the
end. Operator runs the 8-item verification on SM-A566B.

Begin by reading the 7 documents listed above + each existing
service file in `:feature-godmode/`. Run `git status` +
`./gradlew test` to confirm green baseline. Then write
`docs/PHASE_3_AUDIT.md` (W4-1).

When W4-7 APK lands, Phase 3 GodMode v2 is closed. Mega-prompt's
Waves 5 (Email triage) and 6 (Cloud STT/Vision) become the next
session's brief.
============================================================
```

---

## Why this prompt structure

**Differs from `PHASE_2_REMEDIATION_AND_3_FOUNDATIONS_PROMPT.md`
(mega):**
- **Trim to Wave 4 only** — operator's «план Б» scope.
- **Pre-flight added** — opt-in clearing of Phase 2.5's TODO(Phase 3)
  carry-forward debt, but only if host healthy.
- **Audit-first commit (W4-1)** — never re-implement v1 code without
  knowing what's actually there. Saves rewriting working code.
- **Specific Android 14 InCallService kill** — v1 scaffolding tried
  to register InCallService; new sessions could repeat the mistake
  without explicit "don't" call-out.
- **Operator-verification 8-item list** — each commit has a named
  device action operator can perform.

## When to use this prompt

- Phase 2.5 APK has been verified by operator on real device.
- Operator wants Phase 3 GodMode capabilities (screen vision, app
  control, notification triage, call screening) before Email +
  Cloud STT.
- ~10-14 hours Claude Code budget.

## Companion artifacts

- `docs/PHASE_3_GODMODE_OVERNIGHT_PROMPT.md` (this file)
- `docs/PHASE_2_REMEDIATION_AND_3_FOUNDATIONS_PROMPT.md` (mega, will
  reference Wave 5+6 as next-up)
- `docs/PHASE_2_5_FOUNDATIONS_PROMPT.md` (prior, DONE)
- 9 memory entries

After this session lands its APK, operator's call: start mega-prompt's
Wave 5 (Gmail OAuth + email triage) or sit on the GodMode build
to gather real-world feedback before adding more layers.
