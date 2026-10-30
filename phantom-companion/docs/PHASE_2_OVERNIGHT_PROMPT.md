# Phase 2 Overnight — Full Prompt for Next Claude Code Session

> Copy-paste **everything between the two `===` lines** into a new
> Claude Code session as the very first message. Target run-time:
> 9-12 hours autonomous execution (≈ 4-5 weeks of human work).
> Operator goes to sleep; reads `git log -p` in the morning.

```
============================================================
You are joining the PHANTOM Companion project mid-stream. The previous
session closed Phase 1 (1-A..G) and Phase 1.5 (1.5-0, 1.5-A..E) — the
WebSocket pipeline is alive, all 7 surfaces in SurfaceTray are wired,
4 of them landed this week (Vault / Whisper preview / Diary / Motion).
Your job tonight is to ship Phase 2 — voice loop end-to-end + Whisper
Line real overlay-сервіс + Decision Diary phantom-os sync + Generative
widget pack live data.

═══ READ FIRST (in this exact order, do not skip) ═══

1. /home/radxa/.claude/plans/modular-growing-parnas.md
   The Phase 1.5 plan that just shipped. Read for context on naming,
   atomic-commit discipline, and reuse patterns. Phase 1.5-{B,C,D,E}
   are landed; do not re-implement them.

2. docs/COMPANION_CONTRACT.md
   Wire contract between phone and phantom-os backend. 14 documented
   ambiguities. Read all 14, especially §10.1 (/ws path), §10.4
   (multi-endpoint retry), §10.8 (server_proof verification), §10.11
   (profile_sync), §10.13 (agent.stream channel rename), §10.14
   (PIN-resolve missing server-side). For Phase 2 also study channel
   surface area for `decision_diary/*` (NEW — section needs to be
   added by you in §10.15) and `agent.stream/proactive.cycle` (used
   by Whisper overlay).

3. docs/ROADMAP.md
   Phases that have shipped. 1.5-E (`9e3efa4`) is the most recent.
   Append a Phase 2 section as you ship phases.

4. docs/VISION_NEXT.md
   Engineering roadmap, Tiers 5-13. This sprint covers parts of:
   - Tier 5 (voice loop) — Sprint A from `COMPANION_DEVELOPMENT_PROMPT`
   - Tier 6 generative widgets — 1-F-2..1-F-4
   - Tier 7 §3-E Decision Diary follow-ups + §3-A,B working memory hooks
   - Tier 8 §3-E Whisper Line always-on overlay (presence channel)

5. docs/DESIGN_BRIEF.md
   705-line atmospheric design specification. The visual language
   north star. For Phase 2 the relevant sections are:
   - §V (sound catalog) — chimeBell/crystalNotice/spikeAlert mapping
   - §VI (motion catalog) — spring(180,0.75) for snap, spring(120,0.85)
     for breathing, easeInOutQuart 600ms for fades
   - §VIII (whisper line section) — 1.5px height, 8s drift, gold-faint

6. docs/PRESENCE_AND_CHAT.md
   628-line. For Phase 2: §I.2 (Whisper Line full spec) + §I.9 (Margin
   Glow — adjacent, NOT for tonight) + §III (presence-tier stack).
   The overlay-сервіс implementation must match §I.2 verbatim.

7. ~/.claude/projects/-home-radxa-programming-my-own-phantom-ai-PHANTOM-OS-BLUEPRINT-phantom-companion/memory/MEMORY.md
   Index of 9 memory entries. CRITICAL ones for tonight:
   - `host_agent_concurrency.md` — DO NOT spawn parallel Agents on
     this host. The Radxa SBC crashes Claude Code itself. Always
     serialize subagent dispatch. Prefer direct tools (Read/Bash/Grep)
     over Agent. If you must use Agent, ONE at a time.
   - `vault_lock_one_way_invariant.md` — UI lock-pills must flip a
     screen-local sessionUnlocked, NEVER call vaultStore.lockNow().
     `lockNow()` is one-way; calling it from a voluntary lock pill
     bricks re-biometric until app restart. Phase 1.5-B got this
     right; replicate the pattern in any new vault-aware surface.
   - `kdoc_close_inside_backticks.md` — A `*/` inside backticks closes
     `/** */` KSP comments. KSP error message points at end-of-file,
     not the real line. If you write KDoc with `decision_diary/*`
     in backticks, escape: ``decision_diary/*`` → use `decision_diary/<verb>`.
   - `pair_flow_gotchas.md` + `nsc_cidr_pitfall.md` + `e2e_playbook.md`
     — already-fixed gotchas. Skim so you don't re-trip.

8. /tmp/phantom-design-4/phantom-app2/project/
   Latest 4-iteration design prototype (5300 lines of React/CSS/WebGL).
   Visual reference. NOT in repo. Files relevant to Phase 2:
   - `surfaces.jsx#PairScanner` — AR brackets reference (1.5-F bonus)
   - `phase2.jsx#WhisperLineScene` — overlay drift reference
   - `extras.jsx#FloatingOrbOverlay` — overlay-сервіс sibling pattern
   - `surfaces.jsx#VoiceClonePreview` + `DialogueThrum` — voice UX cues
   - `extras.jsx#SettingsSheet` — Voice + Whisper config sections
   - `phase3.jsx` — TimerWidget, RouteWidget, WeatherWidget references
   READ AS VISUAL REFERENCE. Convert React → Compose idiomatically.
   Match colors, timings, easings, copy verbatim. Ukrainian text in
   the Familiar's voice (warm, ти-form, lowercase, intimate);
   preserve word-for-word in `strings.xml`.

═══ CURRENT STATE (verified, 2026-05-10) ═══

Branch: `companion-v2-phase-0` (working tree clean before you start)

Last commits:
  9e3efa4 Phase 1.5-E Motion catalog (devOnly)
  48a305d Phase 1.5-D Decision Diary surface + Room v4 migration
  7a9286d Phase 1.5-C Whisper Line preview surface (no overlay yet)
  83e7e73 Phase 1.5-B Vault surface (biometric + GHOST ceremony)
  f123bcd Phase 1.5-A Onboarding TTS narration
  1bcceea Phase 1.5-0 SurfaceTray (long-press → surface picker)

Tests: 326 unit tests, all green. `./gradlew test :app:assembleDebug
:app:lintDebug` — green per phase. `:app:assembleRelease` — green
(no minified-build issues yet, but R8 is config'd).

Live verification (2026-05-09): pair flow + WS connect + 7-surface
tray all confirmed on Samsung SM-A566B / Android 16.

═══ INFRA ALREADY IN PLACE — DO NOT RE-CREATE ═══

These are wired. Your job is to consume them, not re-invent.

**Voice (`:core-voice`):**
- `SttEngine` interface + `WhisperSttEngine` (ONNX) + `VoskSttEngine`
  + `HybridSttEngine` (Vosk live → Whisper final) + `FakeSttEngine`
- `AudioRecorder` (PCM-16 16kHz, levelFlow `StateFlow<Float>`)
- `VoiceManager` — high-level façade, `sttResults`/`partialResults`
  SharedFlows, `audioLevel: StateFlow<Float>`, `initStt(voskPath,
  whisperPath)` accepts ModelManager-resolved paths
- `TtsEngine` interface + `AndroidSystemTts` (uk-UA, immediate) +
  `StyleTts2OnnxRoute` (stub, model not downloaded yet) + `RoutedTts`
  (tries premium, falls back to system) — wired in `AppContainer.ttsEngine`
- `UaPhonemizer` (Ukrainian text → IPA, used by StyleTTS2)
- `VoicePreferences` (Keystore-backed, TTS on/off toggle)

**Models (`:core-ai/ModelManager`):**
- `ModelType.{VOSK, WHISPER, STYLE_TTS, GEMMA}` enum
- `getModelFile(type)` returns target path
- Download progress flow exists; what's missing is the **UX surface**
  that drives downloads on first-launch

**PTT (`:core-design`, `:feature-stream`):**
- `PttButton` (massive press-and-hold button with state visualization)
- `PttRow` — places PttButton above InputBar; emits `StreamEvent.PttPress` /
  `StreamEvent.PttRelease`. **CURRENT BUG: `audioLevel = 0f` hardcoded**
  — must wire to `voiceManager.audioLevel.collectAsState()`

**Orb (`:core-design`):**
- `OrbView` accepts `audioLevel: Float` for ring-pulse modulation.
  Not wired to mic. Phase 1-D wired emotion morph; PTT mic is your job.

**Overlay sibling (`:feature-ambient`):**
- `FloatingOrbService` — full WindowManager + SYSTEM_ALERT_WINDOW
  + foreground notification + lifecycle. **STUDY THIS** before
  building WhisperLineService — it's the pattern.
- Manifest already has `<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />`

**WidgetRenderer (`:feature-stream`):**
- `WidgetRenderer.kt` parses typed JSON `{type, payload, actions}`
  and dispatches to a registry. Phase 1-A added rich content widgets
  (chart+, code-block, file-card, voice-memo, tool-trace, citation).
  Tonight you add `timer`, `route`, `weather`, `code-runner`.

**Diary (`:core-data`, `:feature-stream`):**
- `DecisionEntry` Room entity + `DecisionEntryDao` + `DiaryStore`
  (Flow façade, mirrors `VaultStore` shape) — Phase 1.5-D
- `DiaryScreen` + `DecisionEditorSheet` — local-only UI works
- **MISSING: phantom-os sync.** No `decision_diary/*` channel
  consumer; no publish path on writes. That's tonight's work.

**God-Mode stubs (`:feature-godmode`):**
- `NotificationReceiverService extends NotificationListenerService`
  — registered in manifest, currently no-op. NOT for tonight unless
  Track 4 finishes early; flagged for Phase 3-B.

═══ PHASE 2 IMPLEMENTATION ORDER ═══

Four parallel tracks, but execute SERIALLY (one phase = one atomic
commit, sequential). The order INTERLEAVES tracks so operator gets
visible wins every 1-2 hours, not all-voice-then-all-whisper.

──────────────────────────────────────────────────────
TRACK 1 — Voice loop end-to-end (Sprint A)
──────────────────────────────────────────────────────

**Phase 2-A1 — Wire PttRow audioLevel to VoiceManager**
   - File: `feature-stream/.../ui/PttRow.kt`
   - Change: replace `audioLevel = 0f` hardcoded with
     `voiceManager.audioLevel.collectAsStateWithLifecycle()`. Hoist
     VoiceManager via composition local or pass through StreamScreen
     props. Same pattern as `LocalEmotionTone` (Phase 1-D).
   - Test: `PttRowTest` — emit fake levels via `MutableStateFlow`,
     assert PttButton receives them.
   - Acceptance: hold PTT on real device → Orb ring breathes with
     mic level → release → Orb settles.

**Phase 2-A2 — RECORD_AUDIO permission flow**
   - File: NEW `feature-stream/.../voice/RecordAudioPermissionGate.kt`
   - Material3 permission rationale dialog. Verbatim copy from
     `surfaces.jsx#PermissionStep` "phantom потребує мікрофона
     щоб чути тебе. дозволиш?".
   - Hook into `PttRow` — if permission missing, hold gesture
     opens dialog instead of starting recording.
   - Test: `RecordAudioPermissionGateTest` — denied → no record;
     granted → record path called.

**Phase 2-A3 — Hold-to-record → STT → Stream pipeline**
   - File: `app/.../vm/PhantomViewModel.kt` (modify) — on
     `StreamEvent.PttPress`: `voiceManager.startRecording()`. On
     `StreamEvent.PttRelease`: `voiceManager.stopRecording()` +
     await final transcript via `sttResults.first()`.
   - Then dispatch transcript through existing `aiRouter.route()`
     → result lands in Stream as ASSISTANT message + TTS speaks
     it via `ttsEngine.speak()`.
   - Test: `PhantomViewModelPttTest` — fake VoiceManager, assert
     full event chain (press → record → release → transcript →
     route → speak).
   - **Memory check:** `vault_lock_one_way_invariant.md` does not
     apply here, but the principle (UI state vs domain state)
     does — keep recording state screen-local, not in shared store.

**Phase 2-A4 — Partial-result preview in StreamScreen**
   - File: `feature-stream/.../ui/StreamScreen.kt` (modify)
   - Subscribe to `voiceManager.partialResults`. Show as ghost-text
     above PttRow during hold (same gold-faint styling as Whisper
     Line preview, but inline). Clears on release.
   - Test: `StreamPartialPreviewTest` — emit partials → ghost text
     appears; release → cleared.

**Phase 2-A5 — Whisper-tiny ONNX model first-launch download UX**
   - File: NEW `feature-onboarding/.../ui/ModelDownloadStep.kt`
     (added to onboarding flow as a new state between PairOffer and
     Complete). Mocked in `surfaces.jsx#Onboarding` step «настроюю
     голос».
   - DownloadProgress widget (per-model: Whisper ~150MB, Vosk-UA
     ~50MB). Skippable (operator can use System TTS only).
   - Wire to `ModelManager.download(ModelType)` returning Flow
     of progress.
   - Test: `ModelDownloadStepTest` — progress flow → widget bar;
     skip button → `voicePrefs.sttEnabled = false`.

**Phase 2-A6 — StyleTTS2 model download flow**
   - File: `app/.../settings/VoiceSettingsScreen.kt` (NEW). Section
     in Settings → Voice with download CTA («голос-клон · 350МБ ·
     завантажити»). Per-OEM warning if metered network.
   - Wire to `ModelManager.download(ModelType.STYLE_TTS)`. On
     success, `RoutedTts` automatically promotes premium route
     (`StyleTts2OnnxRoute.isReady() == true` predicate).
   - Test: `VoiceSettingsScreenTest` — download progress, success
     toggles `voicePrefs.styleTtsAvailable`.

**Phase 2-A7 — Voice cycle end-to-end live verification**
   - No new code; this is the E2E commit.
   - Run on SM-A566B. `adb logcat` filter for `VoiceManager|
     PttRow|StreamViewModel|RoutedTts`.
   - Hold PTT → say «Привіт, скажи зараз котра година?» →
     transcript appears (partial then final) → AI replies in
     Stream → System TTS speaks reply (until StyleTTS2 downloads).
   - Commit message documents the verified path.

──────────────────────────────────────────────────────
TRACK 2 — Whisper Line REAL overlay-сервіс (presence)
──────────────────────────────────────────────────────

This is the most product-defining work tonight. The 1.5px gold
ambient text that drifts across the top of EVERY screen — this is
the feature no other AI assistant has. Phase 1.5-C shipped a
preview-mode surface; this track ships the real always-on overlay.

**Phase 3-E1 — WhisperLineService scaffolding**
   - File: NEW `feature-ambient/.../service/WhisperLineService.kt`
     (sibling of `FloatingOrbService`)
   - Foreground service, START_STICKY, low-importance notification
     channel «фантом · присутність» (gold accent icon).
   - WindowManager.addView with FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE
     | FLAG_LAYOUT_NO_LIMITS | FLAG_LAYOUT_INSET_DECOR. Top of screen,
     1.5dp height, full width, ignores keyboard insets.
   - Test: `WhisperLineServiceTest` — service binds, manages overlay
     view lifecycle through onCreate/onDestroy.

**Phase 3-E2 — WhisperLineRibbonView (the actual drift)**
   - File: NEW `feature-ambient/.../ui/WhisperLineRibbonView.kt`
   - ComposeView wrapped — reuses `WhisperLineRibbon` Composable
     from Phase 1.5-C (`feature-stream/.../ui/whisper/WhisperLineRibbon.kt`).
     Move that composable to `:core-design` so both feature-stream
     (preview) and feature-ambient (live) consume it.
   - Drift speed configurable via `voicePrefs.whisperFreq` enum
     (slow/medium/fast — same as preview settings).
   - Verbatim message format from `phase2.jsx#WhisperLineScene`:
     "олег чекає твою відповідь · 23 хв" — phrased as
     `"$source · $detail"`.

**Phase 3-E3 — Subscribe to agentEvents.proactive.cycle**
   - File: `feature-ambient/.../service/WhisperLineService.kt`
     (modify)
   - Inject PhantomViewModel.agentEvents flow (or shared state).
   - Filter `kind == "proactive.cycle" || kind == "proactive.pending_action"`.
   - Map to whisper line text via `WhisperLineMessageMapper.kt` (NEW).
   - Queue messages — show one at a time, advance after full drift +
     2s pause.
   - Test: `WhisperLineMessageMapperTest` — verbatim mapping for 8
     event types.

**Phase 3-E4 — FLAG_SECURE auto-hide on banking apps**
   - File: `feature-ambient/.../service/WhisperLineService.kt`
     (modify)
   - Use AccessibilityService event (already wired in `:feature-godmode`)
     to detect foreground app's package. Hide overlay (set view
     visibility GONE) when foreground app is in `SENSITIVE_APP_DENYLIST`
     (banking, password managers, OTP apps).
   - File: NEW `feature-ambient/.../denylist/SensitiveAppDenylist.kt`
     — verbatim from `monobank|privat|com.lastpass|com.google.android.apps.authenticator2|...`
   - Test: `SensitiveAppDenylistTest` — package classification.

**Phase 3-E5 — Settings → Whisper section with permission CTA**
   - File: `app/.../settings/WhisperSettingsScreen.kt` (NEW)
   - SYSTEM_ALERT_WINDOW permission rationale (per-OEM Samsung One UI 7
     deeplink to AppOps screen if non-standard).
   - Service start/stop toggle.
   - Frequency slider (mirrors `voicePrefs.whisperFreq`).
   - Sound on/off toggle.
   - Test: `WhisperSettingsScreenTest` — permission flow, toggle states.

**Phase 3-E6 — Phase 1.5-C preview deprecation**
   - File: `feature-stream/.../ui/whisper/WhisperPreviewScreen.kt`
     (modify)
   - Add banner at top: «постійна стрічка ✓ активна (з Settings →
     Whisper)» when service running, «спробуй у Settings → Whisper»
     when off. Footer note from Phase 1.5-C stays.
   - Test: `WhisperPreviewScreenTest` — banner state machine.

──────────────────────────────────────────────────────
TRACK 3 — Decision Diary phantom-os sync
──────────────────────────────────────────────────────

Phase 1.5-D shipped local-only Diary. Tonight: bidirectional sync
with phantom-os over WS. Backend route does not exist yet — you
write a CONTRACT extension (§10.15) that the backend team will
implement; meanwhile phone-side scaffolding is ready.

**Phase 3-D1 — COMPANION_CONTRACT.md §10.15 — decision_diary channel**
   - File: `docs/COMPANION_CONTRACT.md` (modify, append §10.15)
   - Spec the channel:
     ```
     channel: decision_diary
     verbs:
       - created  { id, ts, title, context, status }
       - updated  { id, status, context? }
       - deleted  { id }
       - follow_up { id, prompt }    (server → client only)
     direction: bidirectional except follow_up
     auth: requires unlocked vault (future)
     ```
   - **Memory check:** apply `kdoc_close_inside_backticks.md` —
     escape `decision_diary/*` in any KDoc. Use ``decision_diary/<verb>``
     or split: backtick around just the channel name.

**Phase 3-D2 — DiaryWebSocketSubscriber — incoming events**
   - File: NEW `feature-stream/.../diary/DiaryWebSocketSubscriber.kt`
   - Subscribe `phantomViewModel.miscEvents` filtered by
     `channel == "decision_diary"`. On `created` / `updated` / `deleted`
     verbs → call `DiaryStore.upsert/update/delete`.
   - Test: `DiaryWebSocketSubscriberTest` — fake events → store
     mutations.

**Phase 3-D3 — DiaryStore.publish() — outgoing events**
   - File: `core-data/.../diary/DiaryStore.kt` (modify)
   - Add `suspend fun publish(entry: DecisionEntry, verb: String)`
     that delegates to `PhantomLink.send()` if connected, queues
     locally in a `pending_publish` Room table if offline.
   - File: NEW `core-data/.../diary/PendingPublish.kt` (Entity + DAO)
   - Migration v5 adds `pending_publish` table.
   - Reconnect handler in `PhantomLink` drains pending queue.
   - Test: `DiaryStorePublishTest` — online → direct send; offline
     → queue; reconnect → drain.

**Phase 3-D4 — Local writes auto-publish**
   - File: `feature-stream/.../diary/DiaryViewModel.kt` (modify)
   - On every save/delete from UI, call `diaryStore.publish(entry,
     verb)` after the local write. UI does not block on publish.
   - Test: `DiaryViewModelPublishTest` — UI write triggers publish.

**Phase 3-D5 — TimeShiftedHelp follow-up rendering**
   - File: `feature-stream/.../ui/diary/DiaryFollowUpCard.kt` (NEW)
   - Subscribe `decision_diary/follow_up` events. Render as a
     full-bleed card in Stream above InputBar:
       «у січні ти вирішив "$title" тому що "$context".
        зараз phantom бачить що "$prompt" — переглянути?»
     [Переглянути] [Не зараз] [Більше не нагадувати]
   - Verbatim copy from `surfaces.jsx#PastSelfNote` (TimeShiftedHelp).
   - Tap «Переглянути» → navigates to Diary screen with that entry
     pre-expanded.
   - Test: `DiaryFollowUpCardTest` — render variants + action wiring.

──────────────────────────────────────────────────────
TRACK 4 — Generative widget pack (live data)
──────────────────────────────────────────────────────

WidgetRenderer parses JSON-schema. Phase 1-A added 6 rich-content
widgets. Tonight you add 4 functional widgets that the AI can return
in response to operator prompts. Each is a separate atomic phase.

**Phase 1-F2 — TimerWidget (live countdown)**
   - File: NEW `feature-stream/.../ui/widgets/TimerWidget.kt`
   - JSON schema: `{type:"timer", payload:{duration_seconds, label,
     started_at}, actions:[{id:"pause"},{id:"cancel"}]}`
   - Live `Animatable<Float>` ticking from started_at.
   - Vibration + chimeBell on finish (use `core-design.HapticEngine`).
   - Test: `TimerWidgetTest` — countdown ticks; cancel removes; pause
     freezes.
   - AI prompt example for live test: «встанови таймер на 2 хвилини».

**Phase 1-F3 — RouteWidget (mini-map preview)**
   - File: NEW `feature-stream/.../ui/widgets/RouteWidget.kt`
   - JSON schema: `{type:"route", payload:{from:{lat,lon}, to:{lat,lon,
     name}, distance_m, duration_sec}, actions:[{id:"navigate"}]}`
   - Static map tile via `OpenStreetMap` raster tile fetch (no MapLibre
     yet — next phase). Cache tiles in `cacheDir/route_tiles`.
   - "Запустити навігацію" CTA → existing system intent
     (`Intent.ACTION_VIEW` with `geo:` URI).
   - Test: `RouteWidgetTest` — tile URL composed correctly; intent
     dispatched on tap.

**Phase 1-F4 — WeatherWidget (12h hourly bar chart)**
   - File: NEW `feature-stream/.../ui/widgets/WeatherWidget.kt`
   - JSON schema: `{type:"weather", payload:{location, hours:[{ts,
     temp_c, wind_ms, rain_mm}, ...12]}, actions:[]}`
   - Custom Compose chart (no extra dep — Path + Canvas) with temp
     line + rain bars. Verbatim style from `phase3.jsx#WeatherWidget`
     mock.
   - Test: `WeatherWidgetTest` — payload parsing + chart bounds.

**Phase 1-F5 — CodeRunnerWidget (Radxa offload)**
   - File: NEW `feature-stream/.../ui/widgets/CodeRunnerWidget.kt`
   - JSON schema: `{type:"code-runner", payload:{lang, code,
     env_hint?}, actions:[{id:"run-on-radxa"}]}`
   - Snippet preview (reuse `CodeBlock` from Phase 1-A for syntax).
   - Run button gated on `pairStore.isPaired.value` — opens
     bottom-sheet «це виконається на phantom-os. дозволити?» →
     `phantomLink.send(channel="run_code", payload=...)`.
   - Note: backend route `companion_control/run_code` does not exist
     (CONTRACT extension §10.16 — append in same commit as widget).
   - Test: `CodeRunnerWidgetTest` — gating, dispatch.

**Phase 1-F6 — Widget AI-prompt examples → AIRouter system prompt**
   - File: `core-ai/.../prompt/SystemPrompt.kt` (modify)
   - Append section: «коли користувач просить таймер / погоду /
     маршрут / запустити код — відповідай JSON {type:..., payload:...,
     actions:...} замість тексту. Schema documented at:
     [paste schemas verbatim]».
   - This makes the AI emit widgets instead of text descriptions.
   - Test: `SystemPromptTest` — schema strings present.
   - Live: «який таймер на 5 хвилин» → `TimerWidget`. «що з погодою?»
     → `WeatherWidget`. «куди до магазину Епіцентр?» → `RouteWidget`.
     «запусти hello world Python» → `CodeRunnerWidget`.

═══ EXECUTION ORDER (interleaved by track) ═══

Sleep-friendly order — operator wakes up to a coherent set of
visible-everywhere wins, not a half-done track.

```
Phase 2-A1  Wire PttRow audioLevel              [voice, ~1 hr]
Phase 2-A2  RECORD_AUDIO permission gate        [voice, ~1 hr]
Phase 2-A3  Hold→STT→AI→Stream pipeline         [voice, ~1.5 hr]
Phase 3-E1  WhisperLineService scaffolding      [presence, ~1 hr]
Phase 3-E2  WhisperLineRibbonView drift         [presence, ~1 hr]
Phase 1-F2  TimerWidget                          [widget, ~45 min]
Phase 2-A4  Partial-result preview              [voice, ~45 min]
Phase 3-E3  agentEvents → whisper queue          [presence, ~1 hr]
Phase 1-F3  RouteWidget                          [widget, ~1 hr]
Phase 3-D1  CONTRACT §10.15 decision_diary       [docs, ~30 min]
Phase 3-D2  DiaryWebSocketSubscriber             [diary, ~45 min]
Phase 3-D3  DiaryStore.publish offline queue     [diary, ~1 hr]
Phase 3-E4  FLAG_SECURE auto-hide on banking     [presence, ~45 min]
Phase 1-F4  WeatherWidget                        [widget, ~45 min]
Phase 3-D4  Local writes auto-publish            [diary, ~30 min]
Phase 3-E5  Settings → Whisper section          [presence, ~1 hr]
Phase 1-F5  CodeRunnerWidget                     [widget, ~1 hr]
Phase 3-D5  TimeShiftedHelp follow-up card       [diary, ~1 hr]
Phase 2-A5  Whisper-tiny ONNX download UX        [voice, ~1 hr]
Phase 2-A6  StyleTTS2 download flow              [voice, ~1 hr]
Phase 1-F6  AIRouter system-prompt schemas       [widget, ~30 min]
Phase 2-A7  Voice cycle E2E live verify         [voice, ~30 min]
Phase 3-E6  Phase 1.5-C preview deprecation     [presence, ~30 min]
```

Total: ~22 phases × 30-90 min = ~17-22 hours theoretical;
Claude Code at 4-6× speed = 8-11 hours wall-clock. Within target.

If you fall behind: drop in this order
- 1-F5 CodeRunnerWidget (needs new CONTRACT section, slower)
- 2-A6 StyleTTS2 download (StyleTTS2 still stub even when downloaded
  per the route file)
- 3-E5 Whisper Settings (overlay can launch from existing Settings
  shell with hardcoded defaults)
- 3-E4 FLAG_SECURE denylist (defer to Phase 3-B when Accessibility
  service is fully wired)

═══ NON-NEGOTIABLE CONSTRAINTS ═══

1. **One phase = one atomic commit.** Each must build green AND
   pass `./gradlew test :app:assembleDebug :app:lintDebug`. No WIP
   commits.

2. **Sequential subagent dispatch.** Per memory entry
   `host_agent_concurrency.md`, do NOT dispatch parallel Agents.
   ONE at a time, or prefer direct tools. The host crashed Claude
   Code itself last time. Read/Bash/Grep are 10× lighter — use
   those when possible.

3. **Read before edit.** Edit hook will reject otherwise.

4. **Don't break Phase 1 / Phase 1.5.** All 7 surfaces in the tray
   must continue to render and navigate. Vault biometric must still
   gate. Diary local writes must still work even with sync added.
   `./gradlew test` after every commit.

5. **VaultStore.lockNow() is one-way.** Per memory entry. Never
   call it from a voluntary "lock" pill in any new surface — only
   from the `companion_control/lock_vault` server verb (already
   wired in Phase 1-F).

6. **KDoc backtick escape.** Per memory entry. If you write
   ``decision_diary/*`` in KDoc, KSP breaks. Use
   `decision_diary/<verb>` or split backticks.

7. **No dependency bumps.** 155 stale lint warnings exist; ignore
   for tonight. Bumps are their own phase.

8. **No production NSC changes.** Debug-variant override is enough.

9. **Don't touch the design prototype.** `/tmp/phantom-design-4/` —
   read-only. If something doesn't translate 1:1 to Compose, ship
   the closest faithful match and add a `// TODO(design): doesn't
   match prototype X — ask operator` comment.

10. **Ukrainian copy is verbatim.** Ти-form, lowercase, intimate.
    Never paraphrase.

11. **Backend touch is permitted only as docs.** If a phase needs
    a phantom-os route that doesn't exist — write the CONTRACT
    extension and scaffold the phone side; do NOT touch the
    `phantom-os/` sibling repo. (Backend team picks up the spec
    in their own session.)

12. **No new memory entries unless surprising.** Memory is for
    durable gotchas (host concurrency, KSP backticks, vault
    one-way lock). Routine "I added a feature" facts go into
    commit messages, not memory.

═══ WHEN TO PAUSE AND ASK OPERATOR ═══

- Before bumping a dep
- Before changing public API of a `:core-*` module
- Before merging anything that touches `:core-net/pair` (security)
- Before deleting any file
- Before pushing to remote (this branch is local-only by default)
- Before swapping ZXing → ML Kit (1.5-F bonus, not in tonight's
  plan; if you finish early, pause for explicit go)
- When you get unexpected runtime crashes that aren't covered by
  contract or memory entries

Otherwise — execute. Do not pause for routine decisions. Operator
sleeps; check in via `git log -p` in the morning.

═══ HOW TO REPORT BACK ═══

After each commit, summarize in 3-5 sentences:
1. Phase ID + 1-line scope
2. Files changed (count + 3-4 representative paths)
3. Test result (X/Y green) + build result
4. Anything surprising (proposes memory entry IF surprising —
   not for routine-anomaly things)
5. Naming next phase OR pausing

Do NOT narrate every tool call. Operator reads the diff via
`git log -p` if needed. Brief is gold.

If multiple phases land in a row without surprises, batch-summarize:

> Phases 2-A1, 2-A2, 2-A3 all green. PttRow now wires audioLevel
> and Hold→STT→AI→Stream loop verified end-to-end on
> SM-A566B. 12 new tests added. Next: 3-E1 (WhisperLineService).

═══ REFERENCE PATHS QUICK-CARD ═══

```
docs/COMPANION_CONTRACT.md       ← wire spec (extend §10.15, §10.16)
docs/ROADMAP.md                  ← what shipped (append Phase 2)
docs/VISION_NEXT.md              ← engineering plan
docs/DESIGN_BRIEF.md             ← visual language + sound + motion
docs/PRESENCE_AND_CHAT.md        ← Whisper Line full spec §I.2
docs/COMPANION_DEVELOPMENT_PROMPT.md  ← Sprint A/B/F nomenclature
docs/PHASE_2_OVERNIGHT_PROMPT.md ← THIS file (you are here)
~/.claude/projects/.../memory/   ← 9 entries, 4 critical for tonight
~/.claude/plans/modular-growing-parnas.md ← Phase 1.5 plan (context)
/tmp/phantom-design-4/phantom-app2/project/  ← visual reference
git log --oneline 9e3efa4..HEAD  ← your commits tonight
phantom-os/src/backend/          ← backend ground truth (READ-ONLY)
```

You have 9-12 hours of focused work. Phase 2-A1 through 2-A7 +
3-E1 through 3-E6 + 3-D1 through 3-D5 + 1-F2 through 1-F6 = ~22
atomic phases. Operator wakes up to a phantom that hears him,
speaks back in his own clone-voice, drifts ambient text across
every screen, remembers his decisions and reminds him when they
need revisiting, and shows live tools (timer/route/weather/code)
in Stream.

Begin by reading the 8 documents listed above. Do NOT write code
until you have read all of them and confirmed the ground state
(`git log --oneline -10`, `git status`, `./gradlew test`). Then
start Phase 2-A1.
============================================================
```

---

## Why this prompt

**Concrete deltas vs the prior `NEXT_SESSION_PROMPT.md` (Phase 1
handoff):**

- **Inherits Phase 1.5 memory entries** — `host_agent_concurrency`,
  `vault_lock_one_way_invariant`, `kdoc_close_inside_backticks` —
  so the new session doesn't re-trip the same gotchas.
- **Names the existing infra explicitly** — STT/TTS/PttRow/Orb/
  WidgetRenderer/FloatingOrbService — so the agent doesn't waste
  time discovering what's already built.
- **Interleaves tracks** — operator wakes to coherent breadth, not
  one track 100% and rest 0%.
- **Specifies CONTRACT extensions inline** — the agent writes
  spec for the backend team rather than touching the sibling repo.
- **Drop-list under time pressure** — pre-prioritized, so if hours
  run short the agent removes the right phases.
- **Explicit sequential-only Agent dispatch** — biggest lesson from
  the Phase 1.5 crash.

## When to use this prompt

- Operator is going to sleep / step away for 9-12 hours.
- A new Claude Code session opens with the block above as first
  message.
- The session executes ~22 atomic phases, commits each one, and
  the operator reads `git log -p` in the morning.

## Companion artifacts

- `docs/PHASE_2_OVERNIGHT_PROMPT.md` (this file)
- All other vision/design docs (ground truth)
- 9 memory entries (gotchas to inherit)
- Design prototype in `/tmp/phantom-design-4/`
- `~/.claude/plans/modular-growing-parnas.md` (Phase 1.5 plan)

Operator just opens the new session, pastes the block above, and
the new agent has full context. No re-explaining required.
