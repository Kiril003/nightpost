# Phase 2.5 Foundations Overnight — 11-16 Hour Prompt

> Copy-paste **everything between the two `===` lines** into a new
> Claude Code session. Target run-time: **~11-16 hours Claude Code
> (≈ 2-3 months human equivalent)**. Three Waves: widget remediation,
> visual design polish to v4 prototype fidelity, stream history
> persistence. After this lands, Phase 2 is genuinely production
> quality both functionally and visually.

```
============================================================
You are joining the PHANTOM Companion project AFTER Phase 2 ship +
3 hotfixes + Quality Hardening session. Settings are reactive,
theme switches live, models are consumed after download, errors
surface to Stream + Diagnostics, AI auto-recovers. The functional
foundation is solid.

But operator says the visual layer is still not at parity with the
Claude Design v4 prototype that drove all Phase 1/1.5/2 work, AND
two functional regressions remain: Phase 2 widgets are «гівнокод»
(timer broken, code not highlighted, no copy, widget self-loop), and
Stream history disappears on cold launch.

Three Waves tonight, executed in order:

  Wave 1   Phase 2 widget remediation                ~4-6 hr
  Wave 1.5 Visual design polish to v4 prototype       ~3-4 hr   [NEW]
  Wave 2   Stream history persistence (Room v6)      ~4-6 hr

After Wave 2 APK lands, Phase 2.5 is closed and Phase 3 (GodMode v2,
Email triage, Cloud STT/Vision) becomes the next session's brief.
This session does NOT touch Phase 3 scope.

═══ READ FIRST ═══

1. docs/PHASE_2_5_FOUNDATIONS_PROMPT.md
   THIS file. The three Waves below are pre-pinned with file-level
   granularity.

2. docs/PHASE_2_REMEDIATION_AND_3_FOUNDATIONS_PROMPT.md
   The original mega-prompt. Wave 1 + Wave 2 verbatim. (Wave 1.5
   is new in this prompt.)

3. docs/DESIGN_BRIEF.md
   705-line atmospheric spec. CRITICAL FOR WAVE 1.5:
   - §III Glass Stack tokens (alpha values, blur stand-ins)
   - §IV per-state visual matrix (FOCUS/SENTINEL/GHOST/DREAM/SHADOW/DIALOGUE)
   - §V sound catalog (chimeBell/crystalNotice/spikeAlert mapping)
   - §VI motion catalog (spring(180,0.75) snap, spring(120,0.85)
     breathing, easeInOutQuart 600ms fades)
   - §VII typography (32-36sp ExtraBold display, -0.4..-0.5sp
     letter-spacing; 12sp Medium UPPERCASE +4sp letter-spacing
     for labels)

4. /tmp/phantom-design-4/phantom-app2/project/tokens.css
   Design tokens — color values are TRUTH. Compare every PhantomTheme
   color against this in Wave 1.5.

5. /tmp/phantom-design-4/phantom-app2/project/orb.jsx
   WebGL shader Orb. The Compose port is simplified — that's
   acceptable. But the spring physics in `useSmoothedLook` (lines
   ~20-60) MUST match Compose `Animatable<Offset>` with spring
   stiffness=180, dampingRatio=0.75, threshold=0.001f.

6. /tmp/phantom-design-4/phantom-app2/project/{stream,phase2,surfaces}.jsx
   Reference for each Phase 1/1.5/2 ported screen. Wave 1.5 audits
   each.

7. ~/.claude/projects/-home-radxa-programming-my-own-phantom-ai-PHANTOM-OS-BLUEPRINT-phantom-companion/memory/MEMORY.md
   9 entries. CRITICAL:
   - `host_agent_concurrency.md` — sequential Agent only. Direct
     tools first.
   - `vault_lock_one_way_invariant.md` — UI sessionUnlocked, never
     vaultStore.lockNow() from voluntary actions.
   - `kdoc_close_inside_backticks.md` — KSP backtick gotcha.

═══ CURRENT STATE (verified) ═══

Branch: `companion-v2-phase-0`
Last commit at session start should be `dc90d02` or later (QH5 from
Quality Hardening session).
Working tree: clean before you start (`git status` to verify).
Tests: ~480 unit tests, all green per CI.
Build: `:app:assembleDebug` ~3:52 on Radxa SBC, 286MB debug APK.

Latest APK in operator's hands:
`~/Downloads/phantom-companion-quality-dc90d02-20260510-1535.apk`

══════════════════════════════════════════════════════
WAVE 1 — Phase 2 widget remediation (5-7 commits, ~4-6 hours)
══════════════════════════════════════════════════════

This is the functional remediation Wave. Operator called Phase 2
widget code «гівнокод». Restore them to production quality.

────────────────────────────────────
W1-1 — TimerWidget actually counts down (~1 hour)
────────────────────────────────────

Read `feature-stream/.../widget/TimerWidget.kt` line by line.
Verify ALL three potential bugs:
- `started_at + duration_seconds*1000` math correct
- `LaunchedEffect` ticks via `kotlinx.coroutines.delay(1000)` loop
- Timer state survives Compose recomposition (`rememberSaveable`)
- Pause / cancel actions wire to a real local state mutation
- Finish cue: `HapticEngine.fire(VIBRATE_FINISH)` + chimeBell
  SoundPool play

Add `TimerWidgetCountdownTest` that uses `kotlinx-coroutines-test`
`runTest` + `advanceTimeBy(60_000)` to assert tick → finish path.
Test for pause/resume timing math (often THIS is the bug).

────────────────────────────────────
W1-2 — CodeRunnerWidget syntax highlighting + copy (~45 min)
────────────────────────────────────

Replace plain `Text(text = data.code)` with `CodeBlock`-style syntax
highlighting from Phase 1-A's `dev.snipme:highlights:0.7.1` library
(already on classpath). Map `data.lang` to highlight language:
python/kotlin/js/bash/javascript/typescript/sql/yaml.

Add a copy-to-clipboard icon button (top-right of code box):
```kotlin
val clipboard = LocalClipboardManager.current
IconButton(onClick = { clipboard.setText(AnnotatedString(data.code)) }) {
    Icon(Icons.Outlined.ContentCopy, "копіювати")
}
```
Haptic feedback on copy + 1-second toast «скопійовано». Theme-aware
(use `LocalPhantomTheme.current.semanticPositive` for the success
tint).

────────────────────────────────────
W1-3 — Widget self-loop fix (anti-loop) (~1 hour)
────────────────────────────────────

Two-part fix:

(a) `core-ai/.../SystemPrompts.kt` — add explicit rule:
    «ПРАВИЛО АНТИ-ПЕТЛЯ: повертай JSON-віджет ЛИШЕ якщо поточний
    запит користувача явно вимагає його (таймер/погода/мапа/код).
    НЕ повторюй віджет на наступні повідомлення. Якщо користувач
    задає звичайне питання — відповідай текстом.»

(b) `feature-stream/.../StreamViewModel.kt` history serialization
    for AI context — when serializing past `StreamItem.Widget` items
    as `AIIntent.Text.Turn` history, replace the JSON payload with a
    short ASSISTANT-summary like «[віджет: timer 5хв, активний]» so
    the AI doesn't see raw JSON it can mimic.

Add `WidgetSelfLoopTest` — feed AI history with one prior Widget
item, then plain text user prompt; assert the new response is text,
not JSON-widget mimicry.

────────────────────────────────────
W1-4 — Location chain: GPS → IP-geo fallback (~1 hour)
────────────────────────────────────

QH3 wired the active engine indicator. Hotfix #2 wired GPS into
`enrichedContext`. This Wave adds the IP-geo fallback so weather
works even without GPS.

Chain order (priority):
  1. `MobileSensorAdapter.batches.replayCache.lastOrNull()?.gps`
  2. `LocationManager.getLastKnownLocation` PASSIVE_PROVIDER
  3. `LocationManager.getLastKnownLocation` NETWORK_PROVIDER
  4. **Web fallback:** `https://ipapi.co/json/` returns
     `{lat, lon, city}`. Cache 30 min. **GHOST mode hard-disable
     cloud fallback.**
  5. If all fail: `[LOCATION:unknown]`, AI asks «у якому місті ти?»

Files:
- extend `core-ai/.../context/LocationContextProvider.kt` with
  `WebGeoIpFallback` injected dependency.
- `core-net/.../webgeo/IpApiClient.kt` (NEW) — single-method HTTP
  client, 5-second timeout, retry once.
- test all 5 chain branches + GHOST gate.

────────────────────────────────────
W1-5 — Real Weather data (OpenMeteo client) (~45 min)
────────────────────────────────────

WeatherWidget receives data IN payload from AI. AI must fetch real
weather. No backend yet → call OpenMeteo directly.

Files:
- `core-ai/.../external/OpenMeteoClient.kt` (NEW) — pure HTTP,
  no API key needed:
  `https://api.open-meteo.com/v1/forecast?latitude=X&longitude=Y
   &hourly=temperature_2m,wind_speed_10m,precipitation`
  Cache 1 hour.
- `core-ai/.../tool/WeatherTool.kt` (NEW) — function-calling style:
  AI emits `{"type":"weather", "request":"current"}` →
  phone fetches → re-prompts AI with data → AI emits final
  `weather` widget JSON with real values.
- tests for both clients with mocked HTTP.

After Wave 1 last commit: build APK
`phantom-companion-w1-<sha>-<TS>.apk`. STOP. Operator runs widget
checklist:
  «таймер на 1 хвилину»     → countdown ticks, finish-cues fire
  «що з погодою?»            → real values for operator's location
  «куди до магазину АТБ?»    → mini-map renders OR specific failure
  «hello world python»       → syntax highlighted, copy works,
                               disabled if not paired

══════════════════════════════════════════════════════
WAVE 1.5 — Visual design polish to v4 prototype (4-6 commits, ~3-4 hours)
══════════════════════════════════════════════════════

This Wave is NEW (not in mega-prompt). It addresses operator's
question «дизайн не пророблений як Claude Design робив, чому?»
The answer: each Phase 1/1.5/2 ported by feel, no line-by-line
fidelity audit. This Wave does the audit + corrections.

────────────────────────────────────
W1.5-1 — Color tokens audit (~30 min)
────────────────────────────────────

Diff every value in `core-design/.../theme/PhantomTheme.kt` against
`/tmp/phantom-design-4/phantom-app2/project/tokens.css`:

For each token in tokens.css, check the Compose equivalent. Write
findings to `docs/DESIGN_AUDIT.md` table:
  | Token | tokens.css value | PhantomTheme value | Match? | Action |

Fix mismatches in PhantomTheme. SPECIFIC values to verify against
the prototype (these are the ones operator will visually notice):
- `--ink-strong` (primary text)
- `--ink` (secondary text)
- `--ink-secondary` (tertiary text)
- `--accent-gold` (gold accent across all states)
- `--accent-amber` (GHOST state)
- `--accent-violet` (DREAM state)
- `--accent-cyan` (DIALOGUE state)
- `--glass-panel` (alpha values for each glass level)
- `--glass-border`
- `--surface-base`

Test: `PhantomThemeAuditTest` — each token in audit table has unit
case asserting the value. Regression guard.

────────────────────────────────────
W1.5-2 — Typography audit + fix (~30 min)
────────────────────────────────────

DESIGN_BRIEF §VII says:
- Display headlines: 32-36sp ExtraBold, letterSpacing -0.4..-0.5sp
- Section headings: 18-20sp Bold
- Body: 14-16sp Regular
- Labels: 12sp Medium UPPERCASE letterSpacing 4sp
- Whisper line: 11sp Light letterSpacing 1.5sp

Audit `core-design/.../theme/Typography.kt` AND every Composable's
inline `Text(fontSize = ...)` for compliance. Replace inline sizes
with theme references.

Files most likely off-spec (audit them all):
- `feature-stream/.../ui/StreamScreen.kt`
- `feature-stream/.../ui/SystemPulseHeader.kt`
- `feature-stream/.../ui/widget/*Widget.kt` (all 11 widgets)
- `feature-onboarding/.../ui/*Step.kt`

Test: `TypographyComplianceTest` — sample 6 representative composables
+ snapshot the TextStyle they receive, assert against design spec.

────────────────────────────────────
W1.5-3 — Spring physics audit (~45 min)
────────────────────────────────────

DESIGN_BRIEF §VI specifies exact spring values:
- Snap-to-position: `spring(stiffness=180, dampingRatio=0.75,
  visibilityThreshold=0.001f)` — 600ms typical settle
- Breathing: `spring(stiffness=120, dampingRatio=0.85)` — slower,
  more organic
- Fades: `easeInOutQuart` over 600ms

Audit every `Animatable.animateTo(...)` and `tween(...)` call:
- `core-design/.../component/OrbView.kt` (spring physics MUST match
  prototype `useSmoothedLook` exactly)
- `feature-stream/.../ui/StreamScreen.kt` (item enter/exit)
- `feature-stream/.../ui/widget/RouteWidget.kt` (snap-to-corner)
- `feature-stream/.../ui/whisper/WhisperLineRibbon.kt` (drift)
- `feature-onboarding/.../ui/OnboardingScreen.kt` (step transitions)

Replace ad-hoc tween values with the canonical springs. Add
`core-design/.../motion/MotionTokens.kt` (NEW):
```kotlin
object MotionTokens {
    val snapSpring = spring<Offset>(stiffness = 180f, dampingRatio = 0.75f, visibilityThreshold = Offset(0.001f, 0.001f))
    val breathingSpring = spring<Float>(stiffness = 120f, dampingRatio = 0.85f)
    val fadeQuart = tween<Float>(durationMillis = 600, easing = CubicBezierEasing(0.77f, 0f, 0.175f, 1f))
}
```

Test: `MotionTokensTest` — assert tokens equal design-brief values.

────────────────────────────────────
W1.5-4 — Sound catalog wiring audit (~30 min)
────────────────────────────────────

DESIGN_BRIEF §V maps sounds to events:
- `chimeBell` — Timer finish, AI completion of long task
- `crystalNotice` — Insight card appears, proactive nudge
- `spikeAlert` — Error, lock_vault remote command, banking-app
  detected by Whisper auto-hide

Audit `core-design/.../sound/SoundEngine.kt` (or equivalent). For
each event listed in design brief, find the trigger in code and
verify the sound is fired. Add missing wiring.

Files to check trigger calls:
- TimerWidget finish — should fire chimeBell ✓ (verify)
- InsightCard appear (`StreamViewModel.proactive.cycle` subscriber)
  — should fire crystalNotice
- StreamViewModel reportError — should fire spikeAlert (Critical only)
- VaultStore.lockNow() side effect — should fire spikeAlert
- WhisperLineService sensitive-app detected — should fire spikeAlert

Test: per-event wiring snapshot tests.

────────────────────────────────────
W1.5-5 — Per-state visual matrix audit (~30 min)
────────────────────────────────────

DESIGN_BRIEF §IV specifies six states with exact visual character:
| State | Accent | Glass | Animation | Sound |
| FOCUS | gold | normal | breathing 4s | crystalNotice |
| SENTINEL | amber | tighter | breathing 2s | spikeAlert |
| GHOST | dim/cyan | minimal | freeze | none |
| DREAM | violet | softest | floating 6s | chimeBell |
| SHADOW | black | masked | none | none |
| DIALOGUE | cyan | bright | pulsing 1.5s | crystalNotice |

Audit `core-design/.../theme/StateAccent.kt` (or where
`LocalStateAccent` resolves). Each state should provide exact
accent + glass alpha + animation rate + sound trigger.

Verify in `feature-stream/.../ui/StreamScreen.kt`:
- Background gradient uses `LocalStateAccent.current` + state-
  specific alpha
- Orb breathing rate keyed off state animation rate
- Notice severity tints match state accent

Test: `StateMatrixTest` — for each state, snapshot accent + glass
+ animation rate.

────────────────────────────────────
W1.5-6 — Margin Glow scaffolding (presence channel sibling) (~30 min)
────────────────────────────────────

Per DESIGN_BRIEF §III + PRESENCE_AND_CHAT.md §I.9, Margin Glow is
the second presence channel after Whisper Line. It's a full-edge
bezel glow whose color = current state.

Phase 3-E will ship it as a real overlay-сервіс. This Wave just
adds the **scaffolding** so it's ready:
- `feature-ambient/.../service/MarginGlowService.kt` (NEW, scaffold
  only — extends ForegroundService, manifest-registered, but
  `START_NOT_STICKY` and immediately stops). One TODO comment:
  `// Phase 3-E: real WindowManager.addView with bezel glow`
- `feature-ambient/.../ui/MarginGlowOverlay.kt` (Composable,
  rendered at preview only — accepts `state: PhantomState` and
  shows a thin glowing border)

Tests: just compile-time + scaffold-render test.

After Wave 1.5 last commit: build APK
`phantom-companion-w1.5-<sha>-<TS>.apk`. STOP. Operator does visual
check: text sizes feel intentional, gold tint matches prototype,
state morphs are smooth, sound on Timer finish.

══════════════════════════════════════════════════════
WAVE 2 — Stream history persistence (4-6 commits, ~4-6 hours)
══════════════════════════════════════════════════════

«історій чатів немає» — fix this BEFORE memory layers. History is
the data; memory layers (future Phase 3) are the indexing.

────────────────────────────────────
W2-1 — Room v6 stream_items table (~1 hour)
────────────────────────────────────

Migration MIGRATION_5_6:
```sql
CREATE TABLE stream_items(
  id TEXT PRIMARY KEY,
  thread_id TEXT NOT NULL,
  seq_no INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  kind TEXT NOT NULL,           -- 'text','widget','notice','command'
  role TEXT,                    -- 'user','assistant','system' or null
  text_content TEXT,
  widget_type TEXT,
  widget_payload TEXT,
  streaming INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_stream_thread_seq ON stream_items(thread_id, seq_no);
```

Files:
- `core-data/.../stream/StreamItemEntity.kt`
- `core-data/.../stream/StreamItemDao.kt`
- `core-data/.../stream/StreamRepository.kt`
- Wire into `PhantomDatabase`. Bump version 5 → 6.
- DAO methods: `insertOrUpdate(item)`, `delete(id)`, `byThread(threadId, limit)`,
  `lastN(limit)`, `searchText(query, limit)`.

────────────────────────────────────
W2-2 — StreamViewModel persists each emit (~1 hour)
────────────────────────────────────

Every `_state.update` that adds/modifies StreamItem also writes to
DAO via IO dispatcher coroutine. Use 200ms-window batching to avoid
write amplification on streaming tokens:
- Maintain a `pendingWrites: MutableMap<String, StreamItem>`.
- Debounce flush: 200ms after last add → bulk DAO write.
- On streaming Final → immediate flush.
- On error → flush whatever's pending.

────────────────────────────────────
W2-3 — Restoration on cold launch (~45 min)
────────────────────────────────────

On `StreamViewModel.init`, load last 100 items per thread from DAO
into memory. Render-empty-then-populate paths must handle empty
initial state without flash:
- `StreamUiState` adds `loading: Boolean` flag.
- StreamScreen shows ghosted skeleton while loading.
- Replace with real items in <50ms.

────────────────────────────────────
W2-4 — Per-thread persistence for ThreadRail (~45 min)
────────────────────────────────────

`threads_meta` table mirrors thread list:
```sql
CREATE TABLE threads_meta(
  id TEXT PRIMARY KEY,
  label TEXT NOT NULL,
  last_active_at INTEGER NOT NULL
);
```
ThreadRail reads from DAO Flow. Default «Зараз» thread auto-created
if no entries exist.

────────────────────────────────────
W2-5 — Storage retention TTL (~30 min)
────────────────────────────────────

`cleanup_after_days` config in VoicePreferences (default 90 days).
Daily WorkManager job deletes stream_items where
`created_at < now - cleanup_after_days * 86400000`.
GHOST-mode entries deleted immediately on session end.

────────────────────────────────────
W2-6 — AI history feeds from DAO, not in-memory (~45 min)
────────────────────────────────────

`StreamViewModel.kt` line ~355 currently filters in-memory items
for AI history. Change to last-N-from-DAO query. This unlocks "AI
remembers conversations across launches".

After Wave 2 last commit: build APK
`phantom-companion-w2-<sha>-<TS>.apk`. STOP. Operator: kill app
fully (force stop), reopen, verify Stream history restores. Send a
message that references a prior conversation → AI uses past context.

═══ NON-NEGOTIABLE CONSTRAINTS ═══

1. **One fix = one atomic commit.** Build green per commit.
2. **Sequential subagent dispatch.** Memory: host crashes on
   parallel Agents. ONE at a time. Direct tools first.
3. **Read before edit.** Edit hook will reject otherwise.
4. **Don't break existing surfaces.** All 7 in tray must navigate.
   All Phase 2 features still work after Wave 1.5 polish.
5. **No new dependencies.** Use what's on classpath.
6. **No phantom-os sibling-repo touches.**
7. **Operator's exact words preserved in user-facing copy.**
   Ukrainian, ти-form, lowercase, intimate. Don't paraphrase.
8. **Wave 1.5 corrections must NOT regress Wave 1 fixes.** If
   audit finds typography that conflicts with widget UX, prefer
   widget UX (operator-functional > spec-strict).
9. **No Phase 3 scope.** Even if margin time appears at end,
   stop. Phase 3 (GodMode, Email, Cloud) is the next session.

═══ HOST PROTECTION ═══

After Phase 2 overnight crashed Claude Code via parallel Agents:
- ZERO parallel `Agent(subagent_type=...)` dispatches in one message.
- Direct tools (Read/Bash/Grep/Edit) — fine to parallel calls.
- Long Bash — `run_in_background: true`, NEVER poll, wait for
  notification.
- If a Wave is taking >2× budgeted hours, STOP at next safe commit
  boundary, write `docs/W?_HANDOFF.md` with state.

═══ REPORTING FORMAT ═══

After each commit, 3-5 sentences:
1. Phase ID (e.g. W1-3) + 1-line scope
2. Files changed (count + 2-3 paths)
3. Test result + build result
4. Anything surprising
5. Next phase OR Wave-end checkpoint

After each Wave's last commit:
1. Wave ID + total commits in Wave
2. Total LoC delta + total new tests
3. APK path + md5 + size
4. Operator verification checklist
5. STOP for operator confirmation before next Wave.

═══ POST-APK OPERATOR VERIFICATION ═══

After Wave 1 APK:
1. «таймер на 1 хвилину» → countdown реально тіче, finish cue fires
2. «hello world python» → код підсвічується + кнопка copy працює
3. Запитуєш одне (наприклад «привіт») після віджета → AI відповідає
   ТЕКСТОМ, а не повторює віджет
4. «що з погодою?» — погода для ТВОЄЇ локації (через GPS або
   IP-geo fallback), не Київ
5. Без GPS + без internet → AI каже «у якому місті ти?»

After Wave 1.5 APK:
1. Шрифти виглядають intentional (display 34sp ExtraBold у Stream
   header, labels 12sp UPPERCASE)
2. Gold accent matches prototype (subtle, не overdrived)
3. Orb breath morphs smoothly через state changes (FOCUS→DREAM
   transition не jerky)
4. Timer finish — chimeBell грає
5. Insight card appearing — crystalNotice грає
6. Error в Stream — spikeAlert грає (раз)

After Wave 2 APK:
1. Force-stop app → reopen → Stream history з попередньої сесії
   відновлюється у <100ms
2. Send message що reference previous conversation → AI відповідає
   з знанням попереднього контексту (бо history feeds з DAO)
3. Daily cleanup — message з 91 day ago зникне через WorkManager
   (verify через `adb shell dumpsys jobscheduler | grep phantom`)

═══ REFERENCE PATHS QUICK-CARD ═══

```
docs/PHASE_2_5_FOUNDATIONS_PROMPT.md    ← THIS file
docs/PHASE_2_QUALITY_HARDENING_PROMPT.md ← prior session (DONE)
docs/PHASE_2_REMEDIATION_AND_3_FOUNDATIONS_PROMPT.md ← mega (next)
docs/DESIGN_BRIEF.md                    ← visual + sound + motion
docs/PRESENCE_AND_CHAT.md               ← Whisper Line spec
/tmp/phantom-design-4/phantom-app2/project/tokens.css ← color truth
/tmp/phantom-design-4/phantom-app2/project/orb.jsx    ← spring truth
~/.claude/projects/.../memory/          ← 9 entries
git log --oneline dc90d02..HEAD         ← your commits this session
```

You have ~11-16 hours. ~14-19 atomic commits across three Waves.
Three green APKs (one per Wave). Operator runs verification per
Wave on SM-A566B. After Wave 2 APK, Phase 2.5 is closed and Phase
3 (GodMode v2 / Email / Cloud) becomes next session's brief.

Begin by reading DESIGN_BRIEF.md sections III-VII, opening
tokens.css and orb.jsx in /tmp/phantom-design-4/, running
`git status` + `./gradlew test` to confirm green baseline. Then
start W1-1.
============================================================
```

---

## Why this prompt structure

**Differs from `PHASE_2_REMEDIATION_AND_3_FOUNDATIONS_PROMPT.md`:**

- **Trim to 3 Waves** — operator's "трошки доробити" scope. Drops
  Phase 3 features (GodMode, Email, Cloud) to a future session.
- **Wave 1.5 added** — visual design fidelity audit + corrections.
  This is what answers «дизайн не пророблений як Claude Design
  робив» complaint.
- **Each Wave = standalone APK** — operator can stop after any
  Wave on a green production-quality build.

## When to use this prompt

- Quality Hardening session (`dc90d02`) APK is verified by operator.
- Operator wants Phase 2 to feel «as good as the prototype» before
  starting Phase 3.
- ~11-16 hours Claude Code budget.

## Companion artifacts

- `docs/PHASE_2_5_FOUNDATIONS_PROMPT.md` (this file)
- `docs/PHASE_2_QUALITY_HARDENING_PROMPT.md` (prior, DONE)
- `docs/PHASE_2_REMEDIATION_AND_3_FOUNDATIONS_PROMPT.md` (next-up
  after this)
- 9 memory entries

After this session lands its three APKs, operator's call: start
mega-prompt's Waves 4-6 (Phase 3 capabilities) or sit on the
polished v2.5 build.
