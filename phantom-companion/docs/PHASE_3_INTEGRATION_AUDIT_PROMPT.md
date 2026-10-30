# Phase 3 Integration Audit + Dead Code Elimination — 6-9 Hour Prompt

> Copy-paste **everything between the two `===` lines** into a new
> Claude Code session. Target run-time: **~6-9 hours Claude Code
> (≈ 2-3 weeks human equivalent)**. ~7 atomic commits. No new
> features. APK looks the same; system works tighter.

```
============================================================
You are joining the PHANTOM Companion project AFTER Phase 3 GodMode
v2 closed (commit `d8cf656`). The project shipped 8 phase blocks
in rapid succession (Phase 1 / 1.5 / 2 / 2-hotfix / 2-quality / 2.5
/ 3-godmode). Each phase moved fast and left scaffolding behind.

Operator's instruction: «проаналізувати і підтягнути все щоб точно
працювало як єдина система і все використовувалось, без зомбі коду».

Translation: integration audit + dead-code elimination. NO new
features. No new screens. No new capabilities. Just tighten what's
shipped: prove every wired component is actually consumed end-to-end,
delete zombie code from v1-era reset, sync drift between docs and
reality, add ONE allowed new dependency (WorkManager) to fix the
critical-path schedulers identified in Phase 3 closing report.

═══ READ FIRST ═══

1. docs/PHASE_3_INTEGRATION_AUDIT_PROMPT.md
   THIS file. 7 phases below.

2. docs/PHASE_3_AUDIT.md
   W4-1's v1 GodMode audit doc (commit 6c60e0d). DA-1 will extend
   it system-wide.

3. docs/COMPANION_CONTRACT.md
   §10.15..§10.18. Verify every channel is consumed end-to-end:
   server → PhantomLink → ViewModel SharedFlow → UI subscriber.

4. docs/VISION_NEXT.md
   §Tier 5 §1-A..§1-H — every item should be ✅ done with a wired
   subscriber at this point. The point of this Wave is to PROVE it.

5. ~/.claude/projects/-home-radxa-programming-my-own-phantom-ai-PHANTOM-OS-BLUEPRINT-phantom-companion/memory/MEMORY.md
   9 entries. Some may be stale (apply-once gotchas now baked into
   tests). DA-4 audits them — do not delete first.

6. git log --oneline 4a2cd44..HEAD
   28+ commits since companion-v2 reset. Some early commits added
   v1-era scaffolding that was never re-wired. DA-2 deletes those.

═══ CURRENT STATE ═══

Branch: `companion-v2-phase-0`
Last commit: `d8cf656` (W4-7 Tasker pattern detector)
Latest APK: `~/Downloads/phantom-companion-phase3-godmode-d8cf656-20260510-1915.apk`
Working tree: clean before you start.
Tests: ~600+ unit tests, all green per CI.

═══ THE FOUR DEBT POOLS ═══

  [DP-1] Zombie code from v1 era (pre-companion-v2 reset 4a2cd44).
         Old Sprint A / Sprint F commits added scaffolding that the
         Phase 1.5/2/2.5/3 work either replaced or made redundant
         but never deleted. Examples found in W4-1 audit:
         - `SecretaryInCallService.kt` — DELETED in W4-6 ✓
         - Old `ScreenAwarenessService` paths — re-implemented in
           W4-2 but the old methods may still exist as unused
         - `TaskerEngine.kt` v1 fields perhaps unused after W4-7
         Audit by grep for `@Deprecated` + unreferenced public
         classes via static analysis.

  [DP-2] Wired infrastructure that's never triggered.
         Services registered in manifest, Composables imported but
         never rendered, EventBus subscribers fire to no listener,
         Room DAO methods that nobody calls.
         Examples to verify:
         - GodModeEventBus — used by W1-3 anti-loop AND W4-4 dispatcher;
           is every command kind actually emitted somewhere?
         - StreamItem.Command — emitted in finalizeAssistant; rendered
           anywhere?
         - All 7 SurfaceTray pills navigate; each destination is a
           real screen, not a stub?
         - All §10.x channels in COMPANION_CONTRACT have a Kotlin
           subscriber and a UI consumer?

  [DP-3] Critical-path schedulers missing WorkManager.
         Phase 3 closing report explicit: «Project has no WorkManager
         dep; both schedulers (W2-5 cleanup + W4-5 digest) use the
         coroutine-loop pattern instead.» Coroutine loops die when
         OEM (Samsung Sleeping Apps, Xiaomi MIUI, Huawei) kills the
         process. 9am digest never fires after device reboot.
         **EXCEPTION TO «no new deps» rule**: this Wave adds
         `androidx.work:work-runtime-ktx` because the cost of skipped
         daily digest is operator trust loss. Document the exception
         in the commit message.

  [DP-4] Doc/code drift.
         ROADMAP entries claim Phase X done, but Phase X has open
         TODOs in code. CONTRACT §10.x has spec, but Kotlin code
         doesn't match all field names. DESIGN_BRIEF says one motion
         spec, MotionTokens says another. Memory entries describe
         gotchas already fixed.

═══ SEVEN ATOMIC COMMITS ═══

──────────────────────────────────────────────────────
DA-1 — System-wide integration audit (1 commit, ~1.5 hours)
──────────────────────────────────────────────────────

Write `docs/INTEGRATION_AUDIT.md`. For each critical path, follow it
end-to-end and pin the breaks:

**Path 1: Server WS event → UI consumer.**
For each §10.x channel in CONTRACT:
| Channel | PhantomLink decoder | ViewModel SharedFlow | Composable subscriber | Status |

Tools: grep for channel name, trace `MutableSharedFlow.tryEmit`
sites, find `.collect {}` sites in `:feature-stream/.../ui/`.

**Path 2: Voice cycle.**
PTT press → AudioRecorder → STT → AIRouter → ttsEngine → AudioTrack.
Verify each hop has live wiring + at least one regression test.

**Path 3: ScreenContext → AIRouter.**
ScreenAwarenessService → ScreenContextStore → AIRouter
enrichedContext builder → AI prompt. Verify with a faked
`AccessibilityNodeInfo` integration test.

**Path 4: app-action → AccessibilityNodeInfo.**
AI emits app-action JSON → finalizeAssistant parses → dispatcher
queue → AccessibilityActionDispatcher executes → ToolTrace UI
updates.

**Path 5: Notification → Stream digest.**
NotificationReceiverService.onNotificationPosted → classifier →
NotificationDigestEntry → daily WorkManager → Stream Notice.

**Path 6: CallScreening → Stream IncomingCallCard.**
CallScreeningService.onScreenCall → CallScreeningPolicy → CallLogEntry
DAO → Stream consumer → IncomingCallCard render.

**Path 7: Stream history persistence.**
StreamUiState.update → StreamPersistenceWriter.observe →
debounced flush → StreamItemDao.upsert. AND restoration: cold launch
→ byThread query → StreamUiState seed.

**Path 8: SurfaceTray → 7 surfaces.**
Long-press header → SurfaceTray → tap pill → NavGraph composable →
real screen (not stub).

**Path 9: Settings → preferences.**
Each toggle in every Settings screen → reactive write → UI feedback
strip → reload after restart shows persisted state.

For each path that breaks: file → commit → fix scope.
For each path that works: ✅.

Audit doc lands as standalone commit. Subsequent DA-N commits
implement fixes one path at a time.

──────────────────────────────────────────────────────
DA-2 — Dead code elimination (1 commit, ~1 hour)
──────────────────────────────────────────────────────

From DA-1's audit, identify zombie code:
- Public classes with zero references outside their own module
- @Deprecated fields beyond their grace window
- v1-era scaffolding superseded by Phase 1.5/2/2.5/3

Tools:
- `./gradlew :module:analyzeUnusedCode` if available, else manual
  grep for class names
- IDE «Find Usages» (you're agent — emulate via grep -rn)
- For each candidate: grep references → if 0 outside its file →
  candidate for delete

DELETE:
- methods that have zero callers
- imports that are unused
- Composable functions that are never rendered
- Room DAO methods nobody invokes
- Services in manifest that nothing binds
- @Deprecated fields past grace

KEEP (don't delete):
- Tests, even orphaned — they may pin invariants
- Public API of `:core-*` modules used by feature modules (cross-
  module greps may miss with kotlin)
- Anything with a TODO(Phase X) marker — those are intentional
  carry-forward

Document deletion list in commit message: «Removed N classes /
M methods / K Composables totaling LoC».

──────────────────────────────────────────────────────
DA-3 — Critical-path wiring fixes (1 commit, ~1.5 hours)
──────────────────────────────────────────────────────

For each broken path from DA-1:
- write a regression test that fails on the break
- fix the wiring (subscriber added, missing call site bridged, etc.)
- test passes

Common breaks expected (from prior session reports):
- §10.15 decision_diary inbound subscriber lacks UI consumer —
  Wave 3-D2 wrote DiaryWebSocketSubscriber but DiaryScreen may not
  refresh on incoming events
- §10.16 run_code outbound — CodeRunnerWidget pushes to
  CodeRunnerActionBus but app-side collector may not exist or
  be unwired
- §10.18 call_screen — W4-6 docs-only spec; if any code was added,
  it's incomplete
- StreamItem.Command — emitted in finalizeAssistant but the
  rendered Composable may not exist or render generically

Each fix is a small inline change. Bundle them into one commit
unless any single fix balloons (in which case split).

──────────────────────────────────────────────────────
DA-4 — Memory + docs sync (1 commit, ~1 hour)
──────────────────────────────────────────────────────

**Memory entries audit.** Each of 9 entries:
- Is the gotcha still possible? (e.g. `nsc_cidr_pitfall` — still
  relevant if production NSC is still loose)
- Is it now baked into a regression test? If yes, add «captured
  by [TestClassName]» line to the entry, keep it
- Is it pure historical? Move to `docs/HISTORICAL_GOTCHAS.md`
  archive, remove from active MEMORY.md

**Doc drift audit:**
- ROADMAP.md vs `git log` — any phase missing from ROADMAP?
- COMPANION_CONTRACT.md — every channel field name matches
  Kotlin data class?
- DESIGN_BRIEF.md vs MotionTokens — values aligned?
- VISION_NEXT.md Tier 5 — every item ✅ done with link to commit?

For each drift found: edit doc to match code, OR file as
TODO(Phase X) if code needs to catch up.

──────────────────────────────────────────────────────
DA-5 — WorkManager dependency (1 commit, ~1.5 hours)
──────────────────────────────────────────────────────

**EXCEPTION TO «no new deps» RULE.** Document why in commit message:
coroutine-loop schedulers die on OEM aggressive battery management;
WorkManager guarantees execution even after process kill.

Add to `gradle/libs.versions.toml`:
```
work = "2.9.1"  # or current stable
work-runtime-ktx = { module = "androidx.work:work-runtime-ktx", version.ref = "work" }
```

Migrate two schedulers:
- **W2-5 stream_items TTL cleanup** (currently coroutine-loop in
  StreamRetentionScheduler.kt) → `PeriodicWorkRequest` daily,
  KEEP unique-name-replace policy
- **W4-5 notification digest** (currently coroutine-loop in
  NotificationDigestScheduler.kt) → `PeriodicWorkRequest` daily
  9am, with `setRequiredNetworkType(NOT_REQUIRED)` (digest is
  local-only)

Tests:
- WorkRequest constraints assertion (interval, network)
- WorkerRunner functional test using WorkManagerTestInitHelper
- Old coroutine-loop classes deleted (DA-2 territory if you want
  to bundle, but cleanest as part of this commit)

Add memory entry `workmanager_for_oem_battery_kill.md`:
«Coroutine-loop schedulers die on Samsung Sleeping Apps / Xiaomi
MIUI / Huawei. Use WorkManager for any periodic work the operator
will rely on. Why: process kill. How to apply: any scheduler that
must survive cold restart.»

──────────────────────────────────────────────────────
DA-6 — Performance + footprint pass (1 commit, ~1 hour)
──────────────────────────────────────────────────────

Cold-launch path measurements via `adb shell am start -W`:
- baseline before this commit
- target: TotalTime < 1500ms on SM-A566B

Heap audit via `adb shell dumpsys meminfo`:
- AppContainer lazy chains — verify nothing instantiates ML models
  at process start
- ScreenAwarenessService binding lazy
- VoiceManager.initStt only if model files exist (already lazy
  per Phase 2 — verify still true)

APK size audit:
- run `:app:bundleDebugCheck` if exists, else `apkanalyzer dex
  packages app/build/outputs/apk/debug/app-debug.apk`
- list any heavy deps that grew unexpectedly

Document findings in commit message + `docs/PERF_BASELINE.md`.

ANY actual perf regression fix is OUT OF SCOPE this Wave — capture
metrics, file follow-ups. Wave focus is audit, not optimization.

──────────────────────────────────────────────────────
DA-7 — Full E2E smoke verify + APK + closing report (1 commit, ~30 min)
──────────────────────────────────────────────────────

Build APK `phantom-companion-integration-<sha>-<TS>.apk`.

Full integration test on simulator (or guidance for SM-A566B):
1. Cold launch — Stream renders with persisted history (W2-3)
2. Long-press «ВАРТА» → all 7 surfaces navigate
3. PTT → STT → AI → TTS (full voice cycle)
4. Open Chrome → ask «про що це?» — AI uses [SCREEN:] context
5. «відкрий Settings → Wi-Fi» — app-action dispatches
6. Receive Telegram → if Important → Stream Notice
7. Wait for 9am next day → digest Notice (proves WorkManager fix)
8. Unknown call → Stream IncomingCallCard

Update ROADMAP.md with Phase 3 closure entry. Pin Phase 3 metrics
in `docs/PHASE_3_CLOSING_REPORT.md`:
- total commits since 4a2cd44
- total LoC
- total test count
- broken paths fixed (from DA-3)
- zombie code deleted (from DA-2)
- doc drift fixed (from DA-4)

═══ NON-NEGOTIABLE CONSTRAINTS ═══

1. **One phase = one atomic commit.** Build green per commit.
2. **Sequential subagent dispatch.** Memory: host crashes on
   parallel Agents. ONE at a time. Direct tools first.
3. **Read before edit.** Edit hook will reject otherwise.
4. **Don't break existing surfaces.** Every test green at every
   commit boundary.
5. **No new features.** No new screens, no new widgets, no new
   AI capabilities.
6. **DA-5 is the ONLY new-dep exception.** Document why in commit
   message. Future Waves still bound by «no new deps».
7. **Don't delete tests.** Even orphaned ones may pin invariants
   that DA-2 deletions break.
8. **Don't touch phantom-os/.** Sibling repo stays read-only.

═══ HOST PROTECTION ═══

After Phase 2 overnight crashed Claude Code via parallel Agents:
- ZERO parallel `Agent(subagent_type=...)` dispatches.
- Direct tools (Read/Bash/Grep/Edit) — fine to parallel calls.
- Long Bash — `run_in_background: true`, NEVER poll.
- If a phase exceeds 2× budget, STOP at next safe commit boundary,
  write `docs/INTEGRATION_AUDIT_HANDOFF.md`.

═══ REPORTING FORMAT ═══

After each commit, 3-5 sentences:
1. DA-N + 1-line scope
2. Files changed (count + 2-3 paths)
3. Test result + build result
4. Anything surprising — especially zombie-code finds and broken
   paths
5. Next phase or pause

After DA-7 final:
1. Total commits + total LoC delta (likely net negative — that's
   the point)
2. Total deleted vs added
3. APK path + md5 + size
4. INTEGRATION_AUDIT.md TL;DR (which paths broken vs OK)
5. STOP. Mega-prompt's Wave 5 (Email) is next session.

═══ POST-APK OPERATOR VERIFICATION ═══

For DA-7 to be «зеленим»:

1. APK installs cleanly over the previous Phase 3 APK (no «schema
   mismatch» Room migration error — proves DA-3 didn't break v6/v7
   migrations).
2. Cold launch < 1.5s on SM-A566B (DA-6 metric).
3. All 7 surfaces still navigate.
4. PTT cycle still works.
5. Full path operator probably most cares about: receive a Telegram
   message → check Stream Notice → wait 24h → 9am Notice digest
   appears. **This is the WorkManager-mediated proof the OS-kill
   issue is gone (DA-5).**
6. INTEGRATION_AUDIT.md reads cleanly + zombie deletions don't
   show up as «missing class» runtime errors.

═══ REFERENCE PATHS QUICK-CARD ═══

```
docs/PHASE_3_INTEGRATION_AUDIT_PROMPT.md  ← THIS file
docs/PHASE_3_GODMODE_OVERNIGHT_PROMPT.md  ← prior session
docs/PHASE_2_5_FOUNDATIONS_PROMPT.md      ← prior
docs/PHASE_3_AUDIT.md                     ← W4-1 GodMode audit
docs/INTEGRATION_AUDIT.md                 ← DA-1 output (NEW)
docs/PHASE_3_CLOSING_REPORT.md            ← DA-7 output (NEW)
docs/PERF_BASELINE.md                     ← DA-6 output (NEW)
docs/COMPANION_CONTRACT.md                ← §10.15..§10.18
docs/VISION_NEXT.md                       ← Tier 5 acceptance refs
~/.claude/projects/.../memory/MEMORY.md   ← 9 entries (DA-4 audits)
git log --oneline 4a2cd44..HEAD           ← 28+ commits since reset
git log --oneline d8cf656..HEAD           ← your commits this session
```

You have ~6-9 hours. ~7 atomic commits. Net LoC delta likely
negative (deletion-heavy). APK looks the same; system works
tighter.

Begin by reading the 6 documents listed above + running
`git status` + `./gradlew test` to confirm green baseline.
Then write `docs/INTEGRATION_AUDIT.md` (DA-1).

When DA-7 APK lands, Phase 3 is fully closed (functionally
shipped + integration-clean). Mega-prompt's Wave 5 (Email triage)
becomes the next session's brief.
============================================================
```

---

## Why this prompt structure

**Differs from feature-build prompts:**
- **No new capabilities.** Operator's exact instruction: «без зомбі
  коду». This is dead-code elimination + integration verification.
- **DA-5 WorkManager exception.** The «no new deps» rule from Quality
  Hardening is broken once — explicitly justified in the commit
  message. Without WorkManager, daily digest fails silently and
  trust evaporates.
- **Audit-first commit (DA-1).** Never delete code without first
  proving it's zombie. Audit doc lands as standalone evidence.
- **DA-7 final commit ships APK.** Same form factor as Phase
  closures.
- **Net LoC delta likely negative.** That's the point. Project
  shrinks (deletions exceed additions); system tightens.

## When to use this prompt

- Phase 3 GodMode v2 APK has been verified by operator on real
  device.
- Operator wants the project to feel like one tight system before
  starting Wave 5 (Email).
- ~6-9 hours Claude Code budget.

## Companion artifacts

- `docs/PHASE_3_INTEGRATION_AUDIT_PROMPT.md` (this file)
- `docs/PHASE_3_GODMODE_OVERNIGHT_PROMPT.md` (prior, DONE)
- `docs/PHASE_2_REMEDIATION_AND_3_FOUNDATIONS_PROMPT.md` (mega; Wave
  5+6 still pending after this audit)
- 9 memory entries (DA-4 audits)

After this session lands its APK, operator's call: start mega-prompt's
Wave 5 (Gmail OAuth + email triage) or sit on the integration-clean
build for real-world stress testing.
