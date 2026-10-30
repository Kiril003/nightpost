# Phase 2 Remediation + Phase 3 Foundations — Mega Prompt

> Copy-paste **everything between the two `===` lines** into a new
> Claude Code session. This is a 6-Wave brief covering ~30-40 hours
> Claude Code work (≈ 5-7 weeks human equivalent). Sessions checkpoint
> after each Wave; if the host can't sustain a single 30-hour run,
> operator restarts and points the next session at the next Wave.

```
============================================================
You are joining the PHANTOM Companion project AFTER Phase 2 overnight
shipped 23 commits (4285484..1782387) but operator's live testing on
SM-A566B exposed serious quality regressions. Your job is multi-wave:
remediate Phase 2 (Waves 0-1), restore broken foundations (Wave 2),
then ship Phase 3 capabilities operator has been waiting for (Waves
3-5). This is the largest brief in project history. Pace yourself.

═══ READ FIRST ═══

1. docs/PHASE_2_OVERNIGHT_PROMPT.md
   The 23-commit Phase 2 brief that caused the quality regressions
   you're remediating. Skim Track 4 (widgets) — that's the worst-hit.

2. docs/PHASE_2_HOTFIX_PROMPT.md
   Three bugs already pinned (markdown-fence JSON, purple map,
   Київ-only weather). Wave 0 IS this prompt — execute it verbatim
   first.

3. docs/PHASE_2_REMEDIATION_AND_3_FOUNDATIONS_PROMPT.md
   THIS file. Multi-wave structure with operator-graded severity.

4. docs/VISION_NEXT.md
   §Tier 6 (Phase 2 multi-modal) §Tier 7 (Phase 3 memory) §Tier 10
   (Phase 6-B email). Phase 3-A/B/C scaffolding from v1-era exists
   in `:feature-godmode/` — your job is audit + re-implement to
   v2 standard.

5. docs/COMPANION_CONTRACT.md
   §10.15 (decision_diary) §10.16 (run_code) — already written. New:
   you'll add §10.17 (email_summary) and §10.18 (call_screen) in
   Wave 5 / Wave 4-C respectively.

6. ~/.claude/projects/-home-radxa-programming-my-own-phantom-ai-PHANTOM-OS-BLUEPRINT-phantom-companion/memory/MEMORY.md
   9 memory entries. CRITICAL for tonight:
   - `host_agent_concurrency.md` — sequential Agent only. The host
     crashed Claude Code itself with parallel subagents. NEVER
     dispatch parallel Agents in one message. Direct tools first.
   - `vault_lock_one_way_invariant.md` — UI sessionUnlocked, never
     vaultStore.lockNow() from a voluntary pill.
   - `kdoc_close_inside_backticks.md` — `*/` inside backticks closes
     KSP comments.

═══ CURRENT STATE (verified 2026-05-10 13:30+) ═══

Branch: `companion-v2-phase-0`
Last commits since Phase 2 close (`1782387`):
  b432df6 docs(handoff): mega-prompt — THIS file
  91523c2 fix(stream): hotfix #3 — RouteWidget tile-fetch surfacing  ✅ Wave 0 DONE
  000ef78 fix(ai):     hotfix #2 — real [LOCATION:lat,lon]            ✅ Wave 0 DONE
  b79d44d fix(stream): hotfix #1 — JSON extract from fenced/preambled ✅ Wave 0 DONE
  00ac4aa docs(handoff): Phase 2 hotfix prompt
  1782387 Phase 2-A7 deferred E2E checklist

**Wave 0 is COMPLETE.** Skip section "WAVE 0" below — a parallel
Claude Code session shipped HF1/HF2/HF3 while this prompt was being
written. Verify with `git log --oneline 1782387..HEAD | grep hotfix`
(should show 3 lines).

**START AT WAVE 1.** Build a fresh APK (`phantom-companion-wave0-
done-<sha>-<TS>.apk`) so operator can verify the three hotfixes
landed on a real device before you start Wave 1. If verification
passes, proceed.

Tests: ~436 unit tests + 10-15 from hotfixes, all green per CI
Build: `:app:assembleDebug` ~3:52 on Radxa SBC, 285MB debug APK

Operator's live test of APK 1782387 surfaced 11+ regressions:

═══ OPERATOR'S LIVE BUGS — MUST FIX ═══

**Tier-S (operator called the code «гівнокод»):**

  [#1]  Generative widgets render as raw JSON in Stream
        AI returns valid JSON, but markdown-fence wrap or Ukrainian
        preamble bypasses StreamViewModel.finalizeAssistant. (Wave 0,
        Hotfix HF1.)

  [#2]  RouteWidget mini-map shows solid purple void — OSM tile
        fetch fails silently. Operator reads as rendering bug.
        (Wave 0, Hotfix HF3.)

  [#3]  Weather always for Київ regardless of operator's actual
        location. SystemPrompt embeds "Київ" example + zero GPS
        coords in context. Operator: «локацію з телефону не сканує
        (чи на крайняк з браузера)». (Wave 0 HF2 + Wave 1 cross-cut.)

  [#4]  CodeRunner accepts run without paired phantom-os (chains
        from #1). (Wave 0 HF1.)

  [#5]  TimerWidget «не працюючі» — countdown doesn't tick, OR
        finish cue doesn't fire, OR pause/cancel buttons broken.
        Investigate ALL three. (Wave 1.)

  [#6]  CodeRunnerWidget code «не світиться» — no syntax highlight.
        Phase 1-A added `dev.snipme:highlights:0.7.1` for CodeBlock
        widget but CodeRunner uses raw `Text()`. (Wave 1.)

  [#7]  Code in CodeRunnerWidget is not copy-able — no clipboard
        button. (Wave 1.)

**Tier-A (foundation broken):**

  [#8]  Stream history disappears on app restart. ThreadRail (Phase
        1-B) holds threads in memory only. Operator: «історій чатів
        немає». ALL conversation context is lost on cold launch.
        (Wave 2.)

  [#9]  AI «пам'ять — гівно». No semantic memory, no episodic
        memory, no working memory. AI can't reference past
        conversations. (Wave 2 (history persistence) + Wave 3-D
        (real episodic memory layer).)

  [#10] Widget self-loop: «після того як якийсь віджет створив, на
        наступні повідомлення його строчить». State pollution. AI
        sees widget JSON in conversation history → continues emitting
        widgets even when user asks plain text questions. SystemPrompt
        likely missing «emit widget ONLY when explicitly asked» rule,
        OR conversation history serializes widget items as raw JSON
        which the AI mimics. (Wave 1 — system prompt anti-loop +
        history serialization fix.)

  [#11] Operator's larger asks (re-stated by him in Ukrainian):
        - аналіз пошти, вміння керувати нею
        - повністю керувати застосунками (full app control)
        - бачити всі елементи і все що на екрані коли треба
        - вміти знаходити що треба
        - телефонувати/набирати кого попросять
        - Google online STT/Vision integration as fallback
        - реальне завантаження/видалення/використання моделей
        These are Waves 3-6. Order them by dependency in this prompt.

═══ SIX WAVES — EXECUTE IN ORDER, CHECKPOINT AFTER EACH ═══

Each Wave = independent commit set. After last commit of a Wave,
build APK, copy to ~/Downloads with wave label, and STOP. Operator
verifies, gives go for next Wave. If host can't sustain a single
30-hour session, operator opens a new session pointed at the next
Wave with «продовжуй з Wave N» in this prompt.

──────────────────────────────────────────────────────
WAVE 0 — Phase 2 Hotfix (3 atomic commits, ~2-3 hours)
──────────────────────────────────────────────────────

EXECUTE PRIOR PROMPT VERBATIM. Do NOT re-design — the diagnostic
work is done.

  HF1  AssistantJsonExtractor — markdown-fence + preamble tolerant
       balanced-bracket scanner with required top-level "type" field.
       File: `feature-stream/.../parse/AssistantJsonExtractor.kt`
       + 10 unit tests.
       Refactor `StreamViewModel.finalizeAssistant` to use it.

  HF2  LocationContextProvider + SystemPrompt rewrite. Inject
       `[LOCATION:lat,lon]` from `MobileSensorAdapter` or
       `LocationManager` PASSIVE provider. Drop "Київ" from prompt
       examples.

  HF3  RouteWidget tile-fetch failure surfacing — typed FailKind,
       1 retry on 429/Timeout, neutral background instead of purple.

After Wave 0: build APK `phantom-companion-wave0-<sha>-<TS>.apk`,
copy to ~/Downloads, report 5-line summary. STOP. Operator verifies
4 widget prompts: «таймер на 1 хвилину», «погода?», «куди до АТБ?»,
«hello world python».

──────────────────────────────────────────────────────
WAVE 1 — Widget remediation (5-7 atomic commits, ~4-6 hours)
──────────────────────────────────────────────────────

This is THE quality remediation Wave. Operator called Phase 2 widget
code «гівнокод» — your job is restore them to production quality.

**Phase W1-1 — TimerWidget actually counts down**
   Read `feature-stream/.../widget/TimerWidget.kt` line by line.
   Verify:
   - `started_at + duration_seconds*1000` math correct
   - `LaunchedEffect` ticks via `kotlinx.coroutines.delay(1000)` loop
   - Timer state survives Compose recomposition (use `rememberSaveable`)
   - Pause / cancel actions wire to a real local state mutation
   - Finish cue: `HapticEngine.fire(VIBRATE_FINISH)` + chimeBell
     SoundPool play
   Add `TimerWidgetCountdownTest` that uses `kotlinx-coroutines-test`
   `runTest` + `advanceTimeBy(60_000)` to assert tick → finish path.
   Test for pause/resume timing math (often the bug is here).

**Phase W1-2 — CodeRunnerWidget syntax highlighting + copy**
   Replace plain `Text(text = data.code)` with `CodeBlock`-style
   syntax highlighting from Phase 1-A's `dev.snipme:highlights:0.7.1`
   library (already on classpath). Map `data.lang` to highlight
   language: python/kotlin/js/bash/etc.
   Add a copy-to-clipboard icon button (top-right of code box):
     ```kotlin
     val clipboard = LocalClipboardManager.current
     IconButton(onClick = { clipboard.setText(AnnotatedString(data.code)) }) {
         Icon(Icons.Outlined.ContentCopy, "копіювати")
     }
     ```
   Hapт feedback on copy + 1-second toast «скопійовано». Тhem-aware.

**Phase W1-3 — Widget self-loop fix (system prompt anti-loop)**
   Two-part fix:

   (a) `SystemPrompts.DEFAULT_PHANTOM` — add explicit rule:
       «ПРАВИЛО АНТИ-ПЕТЛЯ: повертай JSON-віджет ЛИШЕ якщо поточний
        запит користувача явно вимагає його (таймер/погода/мапа/код).
        Не повторюй віджет на наступні повідомлення. Якщо
        користувач задає звичайне питання — відповідай текстом.»

   (b) `StreamViewModel.kt` history serialization for AI context —
       when serializing past `StreamItem.Widget` items as
       `AIIntent.Text.Turn` history, replace the JSON payload with a
       short ASSISTANT-summary like «[віджет: timer 5хв, активний]»
       so the AI doesn't see raw JSON it can mimic.

   Add `WidgetSelfLoopTest` — feed AI history with one prior Widget
   item, then plain text user prompt; assert the new response is
   text, not JSON-widget mimicry.

**Phase W1-4 — Location: GPS → IP-geo fallback chain**
   Operator: «локацію з телефону не сканує (чи на крайняк з
   браузера)». HF2 wired GPS; this Wave adds the IP-geo fallback.

   Chain order (priority):
     1. `MobileSensorAdapter.batches.replayCache.lastOrNull()?.gps`
     2. `LocationManager.getLastKnownLocation` PASSIVE_PROVIDER
     3. `LocationManager.getLastKnownLocation` NETWORK_PROVIDER
     4. **Web fallback:** `https://ipapi.co/json/` (or ipinfo.io)
        — returns `{lat, lon, city}` based on egress IP. Cache for
        30 minutes. GHOST mode hard-disable cloud fallback.
     5. If all fail: `[LOCATION:unknown]`, AI asks «у якому місті ти?»

   Files:
   - extend `core-ai/.../context/LocationContextProvider.kt` with
     a `WebGeoIpFallback` injected dependency.
   - `core-net/.../webgeo/IpApiClient.kt` (NEW) — single-method
     HTTP client, 5-second timeout, retry once.
   - test all 5 chain branches.

**Phase W1-5 — Weather + Route fetches respect [LOCATION] block**
   After HF2 + W1-4, the AI has location. But:
   - WeatherWidget receives weather data IN payload from AI. AI must
     fetch real weather. For Phase 2.5 we don't have a real weather
     backend — so add a simple OpenMeteo call: `https://api.open-meteo.com
     /v1/forecast?latitude=X&longitude=Y&hourly=temperature_2m,wind_speed_10m,
     precipitation`. Cache 1 hour.
   - RouteWidget «from» defaults to `[LOCATION]` if AI omits it.

   Files:
   - `core-ai/.../external/OpenMeteoClient.kt` (NEW) — pure HTTP,
     no API key needed for basic forecast.
   - `core-ai/.../tool/WeatherTool.kt` (NEW) — function-calling
     style: AI emits `{"type":"weather", "request":"current"}` →
     phone fetches → re-prompts AI with data → AI emits final
     `weather` widget JSON.
   - tests for both clients with mocked HTTP.

After Wave 1: build APK `phantom-companion-wave1-<sha>-<TS>.apk`.
Operator runs full widget checklist + verifies no widget loop.

──────────────────────────────────────────────────────
WAVE 2 — Stream history persistence + foundations (4-6 commits, ~4-6 hours)
──────────────────────────────────────────────────────

«історій чатів немає» — fix this BEFORE memory layers (Wave 4).
History is the data; memory layers are the indexing.

**Phase W2-1 — Room v6 stream_items table**
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
   Files: `core-data/.../stream/StreamItemEntity.kt` +
   `StreamItemDao.kt` + `StreamRepository.kt`. Wire into
   `PhantomDatabase`.

**Phase W2-2 — StreamViewModel persists each emit**
   Every `_state.update` that adds/modifies StreamItem also writes
   to DAO via `IO` dispatcher coroutine. Use `WorkManager`-style
   batching (collect 200ms window, flush together) to avoid Room
   write amplification on streaming tokens.

**Phase W2-3 — Restoration on cold launch**
   On `StreamViewModel.init`, load last 100 items per thread from
   DAO into memory. Render-empty-then-populate paths must handle
   empty initial state without flash.

**Phase W2-4 — Per-thread persistence for ThreadRail**
   `threads_meta` table mirrors thread list (id, label, last_active_at).
   ThreadRail reads from DAO Flow. Default «Зараз» thread auto-created
   if no entries exist.

**Phase W2-5 — Storage retention TTL**
   `cleanup_after_days` config in VoicePreferences (default 90 days).
   Daily WorkManager job deletes stream_items where
   `created_at < now - cleanup_after_days * 86400000`.
   GHOST-mode entries deleted immediately on session end.

**Phase W2-6 — AI history feeds from DAO, not in-memory**
   `StreamViewModel.kt` line ~355 currently filters in-memory items
   for AI history. Change to last-N-from-DAO query. This unlocks
   "AI remembers conversations across launches".

After Wave 2: build APK `phantom-companion-wave2-<sha>-<TS>.apk`.
Operator: kill app fully (force stop), reopen, verify Stream history
restores.

──────────────────────────────────────────────────────
WAVE 3 — Phase 2.5 model lifecycle real (4-6 commits, ~4-6 hours)
──────────────────────────────────────────────────────

Operator complaint: «справжнє завантаження/видалення та використання
моделей які пропонуються в налаштуванні». Phase 2 download UX
existed but `StyleTts2OnnxRoute.isReady() == false` permanent stub.
Models download but never used. Fix this honesty debt.

**Phase W3-1 — StyleTts2OnnxRoute actual ONNX inference**
   Read patriotyk/styletts2-ukrainian model card. Wire ONNX Runtime
   inference: text → IPA (UaPhonemizer) → mel-spectrogram (ONNX) →
   audio (vocoder ONNX) → AudioTrack playback.
   This is Phase 7-A from VISION_NEXT scaffolding. Focused commit:
   inference works in `RoutedTts.premium` path; `isReady()` returns
   true once model file exists; `RoutedTts` automatically promotes.

**Phase W3-2 — ModelManager full UI surface (Settings → Models)**
   Replace `Settings → Voice` minimal screen with full Models surface:
   List of all models (Whisper / Vosk / StyleTTS2 / Gemma):
     - status: not-downloaded / downloading-N% / installed / corrupt
     - size on disk
     - last used timestamp
     - actions: Download / Delete / Re-verify checksum
   Storage chart showing total used / available device space.
   Backed by `ModelInventoryDao` (Room v7 migration adds table).

**Phase W3-3 — Model auto-cleanup policy**
   Per `voicePrefs.modelRetention` enum:
   - `keep-active-only` — delete if not used in 30 days
   - `keep-all` — never auto-delete
   - `aggressive` — delete StyleTTS2 if low storage <500MB
   Daily WorkManager runs the policy.

**Phase W3-4 — Gemma local LLM model real wiring**
   Phase 1-D-2 from COMPANION_DEVELOPMENT_PROMPT.md was scaffolded
   in old commits but never finalized. Wire MediaPipe LLM Inference
   task to load `Gemma-2B-q4_k_m.task` from ModelManager. Add to
   AIRouter as another route option (`Route.LOCAL_GEMMA`). GHOST
   mode → only LocalGemma; no-network → LocalGemma; big-context →
   CloudGemini. Strategy auto-switch.

**Phase W3-5 — Onboarding ModelDownloadStep nav routing**
   Phase 2-A5 added `ModelDownloadStep.kt` but it's not in the
   onboarding nav graph. Insert between PairOffer and Complete.
   `feature-onboarding/.../OnboardingViewModel` adds state for
   ModelDownload between PairOffer + Complete.

After Wave 3: build APK `phantom-companion-wave3-<sha>-<TS>.apk`.
Operator: Settings → Models → Download StyleTTS2 → wait → speaks
in operator's clone voice (StyleTTS2 inference verified).

──────────────────────────────────────────────────────
WAVE 4 — Phase 3 GodMode v2 audit + re-implement (8-12 commits, ~10-14 hours)
──────────────────────────────────────────────────────

V1-era scaffolding exists in `:feature-godmode/`:
  - `service/ScreenAwarenessService.kt` (AccessibilityService)
  - `service/NotificationReceiverService.kt` (NotificationListener)
  - `service/SecretaryCallScreeningService.kt` + `SecretaryInCallService.kt`
  - `engine/TaskerEngine.kt`

These were shipped before companion-v2 reset (commit 4a2cd44). Audit
each, decide keep-or-rebuild, then bring to v2 quality.

**Phase W4-1 — Audit + map to v2 standards**
   READ each existing service file. Determine for each:
   - Does it actually work end-to-end on SM-A566B?
   - Are tests written?
   - Is it wired into v2's AIRouter / StreamViewModel / Stream UI?
   Write `docs/PHASE_3_AUDIT.md` with findings + decisions.

**Phase W4-2 — 3-A v2: ScreenAwarenessService → AIRouter context**
   Whatever extracts ⇒ structured `ScreenContext(text, app, role,
   timestamp)`. Inject into `enrichedContext` for AIRouter alongside
   `[LOCATION]`. Block by `voicePrefs.screenAwarenessEnabled` toggle.
   Permission flow: Settings → Permissions → Accessibility →
   per-OEM deeplink (Samsung One UI 7, Xiaomi MIUI).

**Phase W4-3 — 3-A v2: «знайди те що бачу» — selected text → AI**
   AccessibilityService event for ACTION_LONG_CLICK on text node
   (or 2-finger long-press gesture detector globally). Captures
   selected text → injects as «[ОПЕРАТОР ВИБРАВ:"$text"]» in AI
   context → opens Stream with auto-prompt «що це?» if user fired
   gesture from outside Phantom.

**Phase W4-4 — 3-A v2: «керуй застосунком» — AI dispatches actions**
   AIRouter system prompt extends:
     `{"type":"app-action", "action":"click|scroll|type|back|home",
       "target":{"text"?,"id"?,"bounds"?}, "value"?}`
   `feature-godmode/.../engine/AccessibilityActionDispatcher.kt`
   parses + executes via AccessibilityNodeInfo APIs.
   Example flow: «відкрий Telegram і знайди чат з Олегом» →
   AI emits sequence of actions → dispatcher executes step-by-step
   → Stream shows tool-trace updates.
   Visible as ToolTrace widget in Stream.

**Phase W4-5 — 3-B v2: Notification triage → Stream digest**
   `NotificationReceiverService.onNotificationPosted` → strip
   sensitive content (banking, OTP) → classify (per-channel ML or
   AI-assisted) → if important push as Stream NoticeBar; otherwise
   queue for daily digest.
   Daily digest: «3 нові від Олі (Telegram), 2 пуш-нагадування, 1
   email — проглянути?»
   Digest format: clickable mini-cards in Stream that open thread
   for that source.
   Settings → Notifications → per-app routing rules.

**Phase W4-6 — 3-C v2: Call screening API (no InCallService)**
   Android 14+ blocks InCallService for non-default dialers. Use
   `CallScreeningService` instead — phantom can:
     - intercept incoming calls before they ring
     - decide allow/silence/reject based on caller ID + behavior
     - show incoming-call card in Stream with «прийняти за тебе»
       option
   Outgoing calls: `Intent.ACTION_CALL` with operator's voice
   command «зателефонуй Олегу».
   Defer real AI Secretary speak-to-caller to Phase 5+ (out of
   scope this Wave).

**Phase W4-7 — 3-D: Tasker pattern detector (light)**
   `TaskerEngine.kt` v1 scaffolding exists. Promote to v2:
   - Persist patterns to Room v8 (`patterns` table: trigger_geo,
     trigger_time, trigger_app, suggested_action, confidence_score)
   - When a pattern threshold hit (3+ occurrences within 7 days),
     surface in Stream as InsightCard «помітив: щовівторка о 9:00
     ти готуєш каву → нагадати?»

After Wave 4: build APK `phantom-companion-wave4-<sha>-<TS>.apk`.
Operator: grant Accessibility permission → say «знайди мені налашту-
вання Wi-Fi» → phantom navigates Settings → Wi-Fi via accessibility
actions. Verify notification digest in Stream.

──────────────────────────────────────────────────────
WAVE 5 — Phase 6-B Email triage (5-8 commits, ~6-9 hours)
──────────────────────────────────────────────────────

Operator: «аналіз пошти, вміння керувати нею». Two-tier approach:
read-only via Notification listener (cheap, already have), full
read+send via Gmail OAuth (real, this Wave).

**Phase W5-1 — Gmail OAuth flow (Settings → Mail accounts)**
   Use Google Sign-In SDK + Gmail API scopes
   (gmail.readonly + gmail.send + gmail.modify).
   `core-mail/...` (NEW module) holds:
   - OAuth flow (Activity result API)
   - Encrypted refresh-token storage in VaultStore
   - Token-refresh on every API call

**Phase W5-2 — Inbox sync (read + summarize)**
   Daily WorkManager pulls last 200 messages → AIRouter summarizes
   each → Room `mail_summaries` table. Indexed by sender, subject,
   thread_id, sentiment. Per-account.

**Phase W5-3 — Mail surface in tray**
   Add `Mail` to `SurfaceId` enum. New screen lists summaries
   grouped by sender. Tap → expands full thread (live Gmail fetch).
   Search: «лист від Олі за минулий тиждень про API» → semantic
   search via summaries embedding (uses vector store from Phase
   3-B episodic memory if available, else BM25 fallback).

**Phase W5-4 — Auto-draft replies**
   When operator opens a thread, AIRouter pre-generates 3 reply
   options in operator's style. Tap to send (with confirm dialog).

**Phase W5-5 — Daily mail digest in Stream**
   8am Notice in Stream: «5 нових: 2 важливих (Олег: контракт,
   Анна: ремонт), 3 новин-розсилок. Розгорнути?»

**Phase W5-6 — CONTRACT §10.17 — email_summary channel**
   Phantom-os may want to know mail state too (for cross-device
   coordination). Spec the channel; don't implement backend.

After Wave 5: build APK `phantom-companion-wave5-<sha>-<TS>.apk`.
Operator: link Gmail → wait for sync → ask «хто писав мені
вчора?» → phantom answers from summaries.

──────────────────────────────────────────────────────
WAVE 6 — Cloud STT/Vision hybrid (3-5 commits, ~3-5 hours)
──────────────────────────────────────────────────────

Operator: «онлайн розпізнавання з гугл як fallback». Hybrid mode —
on-device Whisper/Vosk primary; Google Cloud Speech-to-Text
fallback when device-online + battery >30% + not-GHOST.

**Phase W6-1 — Google Cloud Speech-to-Text client**
   `core-voice/.../route/CloudSpeechRoute.kt` — REST client to
   Cloud Speech API. Streaming transcription preferred.
   Auth: API key in EncryptedSharedPreferences, configurable in
   Settings → Cloud → Google Cloud.

**Phase W6-2 — Hybrid SttEngine switch logic**
   `HybridSttEngine` extended:
   - GHOST → on-device only (current behavior)
   - low-battery → on-device only
   - offline → on-device only
   - online + battery > 30% + not-GHOST → cloud (better quality
     for accents) with on-device as warmup

**Phase W6-3 — Google Cloud Vision client (image inputs)**
   `core-vision/.../route/CloudVisionRoute.kt` — sends image to
   Cloud Vision API for OCR / object detection / face / landmark.
   Used by 2-finger long-press OCR overlay (Phase 2-C from VISION_NEXT).

**Phase W6-4 — Cloud routing rules in AIRouter**
   Same hybrid rules: device-class first, cloud as fallback.

After Wave 6: build APK `phantom-companion-wave6-<sha>-<TS>.apk`.
Operator: speak with strong accent → cloud STT activates →
transcript visibly better quality.

═══ NON-NEGOTIABLE CONSTRAINTS ═══

1. **One phase = one atomic commit.** Build green per commit.
2. **Sequential subagent dispatch.** Memory entry. ONE Agent at
   a time. Direct tools first.
3. **Read before edit.** Edit hook will reject otherwise.
4. **Don't break existing surfaces.** All 7 in tray must navigate.
5. **No dependency bumps.** Already 155 stale.
6. **Vault one-way lock invariant.** No voluntary lockNow() from
   any new pill.
7. **KDoc backtick escape.** `*/` inside backticks closes KSP comments.
8. **Backend changes are docs-only.** Don't touch phantom-os/.
9. **GHOST mode hard-disable for cloud routes.** Wave 6 + W1-4
   web fallback both must respect GHOST.
10. **Tests for every new public class.** Operator will run
    `./gradlew test` after each Wave APK lands.

═══ HOST PROTECTION RULES ═══

After Phase 2 overnight crashed Claude Code via parallel Agents,
host protection is critical:

- ZERO parallel `Agent(subagent_type=...)` dispatches in one message.
- Direct tools (Read/Bash/Grep/Edit) — these are fine to parallel.
- Long Bash commands — `run_in_background: true`, NEVER poll, wait
  for notification.
- If a Wave is taking >12 hours wall-clock, STOP at next safe commit
  boundary and write `docs/WAVE_N_HANDOFF.md` with state for the
  next session.

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
3. APK path + md5
4. Operator verification checklist (3-5 specific actions to do)
5. STOP. Wait for operator confirmation before next Wave.

═══ CRITICAL: WHEN TO STOP AND ASK ═══

Operator checkpoints required:
- Before Wave 1 (operator wants Wave 0 verified live first)
- Before Wave 4 (Accessibility + Telecom permissions are sensitive)
- Before Wave 5 (Gmail OAuth requires API key + Google Cloud Console
  config — operator may want to configure first)
- Before Wave 6 (Cloud STT/Vision API keys + billing setup)
- ANY runtime crash that isn't covered by existing memory entries
- ANY decision that affects public API of `:core-net/pair`

Otherwise: execute autonomously. Operator reads `git log -p` between
Waves.

═══ REFERENCE PATHS QUICK-CARD ═══

```
docs/PHASE_2_OVERNIGHT_PROMPT.md       ← prior session brief
docs/PHASE_2_HOTFIX_PROMPT.md          ← Wave 0 verbatim
docs/PHASE_2_REMEDIATION_AND_3_FOUNDATIONS_PROMPT.md ← THIS file
docs/COMPANION_CONTRACT.md             ← extend §10.17, §10.18
docs/VISION_NEXT.md                    ← Tier 6, 7, 10 reference
~/.claude/projects/.../memory/         ← 9 entries, 3 critical
git log --oneline 1782387..HEAD        ← your commits this run
phantom-os/src/backend/                ← READ-ONLY sibling
```

═══ ESTIMATED TIMELINE ═══

```
Wave 0  Phase 2 hotfix          ~2-3 hr   (3 commits)
Wave 1  Widget remediation       ~4-6 hr   (5-7 commits)
Wave 2  Stream history persist   ~4-6 hr   (4-6 commits)
Wave 3  Phase 2.5 models real    ~4-6 hr   (4-6 commits)
Wave 4  Phase 3 GodMode v2      ~10-14 hr  (8-12 commits)
Wave 5  Phase 6-B email          ~6-9 hr   (5-8 commits)
Wave 6  Cloud STT/Vision hybrid  ~3-5 hr   (3-5 commits)

TOTAL                          ~33-49 hr   (32-47 commits)
```

This is 3-5 overnight sessions worth of work. Operator will likely
break it into 2-3 separate sessions:
- Session A: Waves 0-2 (hotfix + remediation + history) — ~12 hr
- Session B: Waves 3-4 (models + GodMode) — ~16 hr
- Session C: Waves 5-6 (email + cloud) — ~10 hr

If you, the agent, finish Wave N and the host is still healthy and
operator hasn't checked in for >2 hr, START Wave N+1 (unless it's a
checkpoint Wave). If host shows stress (long build times, OOM),
STOP at Wave boundary and write handoff.

Begin by reading the 6 documents listed above + `git status` +
`./gradlew test` to confirm clean baseline. Then start Wave 0
(3 hotfix commits from PHASE_2_HOTFIX_PROMPT.md). When Wave 0 ships
APK, STOP and report.

============================================================
```

---

## Why this prompt structure

**Multi-wave checkpoint design:**
- Operator can stop after any Wave APK and verify live.
- Failed Wave doesn't waste subsequent Wave work — each is independent.
- Host stress detection — agent knows when to bail.

**Operator's exact bug list mapped 1:1 to Waves:**
- Tier-S regressions (widgets гівнокод, location, weather, code) → Waves 0-1
- Foundation breaks (history, memory) → Wave 2
- Honest debt (model lifecycle) → Wave 3
- Big asks (app control, screen vision, calls, email, cloud) → Waves 4-6

**No re-discovery work for the agent:**
Every root cause already pinned. Every file path named. Every
acceptance test named. Agent executes, doesn't deliberate.

**Inherits all Phase 1.5 / Phase 2 lessons:**
Memory entries surfaced in section 6 of read-list. Sequential agent
constraint front-and-center. Vault lock invariant + KSP backtick
escape called out.

## Companion artifacts

- `docs/PHASE_2_REMEDIATION_AND_3_FOUNDATIONS_PROMPT.md` (this file)
- `docs/PHASE_2_HOTFIX_PROMPT.md` (Wave 0 verbatim)
- `docs/PHASE_2_OVERNIGHT_PROMPT.md` (prior context)
- 9 memory entries

Operator pastes the block above as first message in a new session;
agent has full context. Each Wave APK lands in `~/Downloads/` with
explicit Wave label so operator knows which build addresses which
regression.
