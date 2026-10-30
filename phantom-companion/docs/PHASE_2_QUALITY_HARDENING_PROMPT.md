# Phase 2 Quality Hardening — 6-Hour Focused Prompt

> Copy-paste **everything between the two `===` lines** into a new
> Claude Code session. Target run-time: **~6 hours Claude Code
> (≈ 1 month human equivalent)**. 6 atomic commits + final APK.
> Operator is sick of bad UX, not missing features — this is the
> "make Phase 2 actually good" sprint, not "ship more capabilities".

```
============================================================
You are joining the PHANTOM Companion project AFTER Phase 2 ship +
3 hotfixes. The widget pipeline + voice infra + Whisper overlay
all exist on `companion-v2-phase-0` branch, but operator's live
testing on SM-A566B exposes that the QUALITY of these features
is unacceptable. He doesn't want more features yet. He wants the
features he has to actually work, recover from errors, react to
his actions, and tell him what's happening.

Your job: 6 atomic commits hardening Phase 2 to production quality.
~1 hour per commit. Final APK in ~/Downloads. No new feature scope.
No new screens (except a small Diagnostics screen). Just fix the
five wounds operator named.

═══ READ FIRST ═══

1. docs/PHASE_2_QUALITY_HARDENING_PROMPT.md
   THIS file. The five wounds + six fixes are pre-pinned below.

2. docs/PHASE_2_OVERNIGHT_PROMPT.md
   Context for what was shipped. Skim Track 1 (voice) + relevant
   Settings screens.

3. ~/.claude/projects/-home-radxa-programming-my-own-phantom-ai-PHANTOM-OS-BLUEPRINT-phantom-companion/memory/MEMORY.md
   9 memory entries. CRITICAL for tonight:
   - `host_agent_concurrency.md` — sequential Agent dispatch only.
   - `vault_lock_one_way_invariant.md` — UI sessionUnlocked, not
     vaultStore.lockNow() from voluntary actions. Same principle
     applies to Settings actions: state changes are screen-local
     OR Store-mediated, never side-channel.
   - `kdoc_close_inside_backticks.md` — KSP backtick gotcha.

4. app/src/main/java/local/phantom/companion/ui/settings/
   The settings screens that operator says are broken. Read them
   ALL before writing anything.

5. app/src/main/java/local/phantom/companion/data/AppContainer.kt
   Wiring of TTS / STT / theme / preferences. Theme switching
   reactivity lives here.

═══ CURRENT STATE ═══

Branch: `companion-v2-phase-0`
Last commit at session start should be `70fea89` or later (mega-prompt
+ Wave 0 hotfixes already shipped).
Working tree: clean before you start (`git status` to verify).

═══ THE FIVE WOUNDS ═══

Operator's words (Ukrainian, tone preserved as severity signal):

  [W1] «після завантаження моделей мало того що їх не використовує»
       Operator downloads Whisper / Vosk / StyleTTS2 from Settings →
       Models tap. Files land on disk. But VoiceManager is initialized
       with the path BEFORE the file existed, so STT still uses
       FakeSttEngine, TTS still uses AndroidSystemTts. Operator has
       no signal that download succeeded BUT inference is unchanged.
       Honest debt: download UX shipped without `refreshModels()`
       wiring back into the active engine.

  [W2] «уйобіщна кнопка в налаштуваннях "видалити все" з'являється
       і крім неї ні зберегти нічого не можу»
       The settings dialog/screen renders only a destructive
       "Видалити все" button. Operator can't save other changes.
       Either the save button is hidden behind state OR the screen
       was built incorrectly OR there's a modal dialog blocking
       non-destructive actions. INVESTIGATE — read every Settings
       Composable file before designing the fix.

  [W3] «зміни теми одразу не видні»
       Theme is set via `compositionLocalOf<PhantomTheme>` and
       provided once at root. Changing theme in Settings persists
       to VoicePreferences/UserPrefs but doesn't trigger
       recomposition of root-level CompositionLocalProvider →
       operator must restart app. Theme should react like
       LocalEmotionTone (Phase 1-D pattern).

  [W4] «ще якусь інфу не відзвітовує»
       Vague but real. Likely candidates: download progress doesn't
       reach UI, model verification status hidden, AI route
       (cloud/local/error) invisible to operator, sensor status
       unknown, paired status only on ProfileShell which is buried.
       Investigate by adb-logcat'ing what's logged but never shown
       in UI.

  [W5] «якщо щось не так — йому похуй, не обробляє»
       Error swallowing is widespread. Look for `runCatching {...}.
       getOrElse { Log.e(...) ; null }` patterns where the operator
       gets `null` and silence. Especially:
         - VoiceManager init failure → silent FakeSttEngine
         - TTS init failure → silent nullTts() (operator types and
           AI replies, but no voice — operator doesn't know why)
         - Pair claim failure → toast disappears in 2s
         - Network failures in widget data fetches
       Errors must surface to operator as Stream Notice OR Settings
       diagnostics, not Logcat-only.

═══ SIX ATOMIC FIXES — EXECUTE IN ORDER ═══

Each commit takes ~1 hour. Build green per commit.

──────────────────────────────────────────────────────
QH1 — Settings save UX investigation + fix (~1 hour)
──────────────────────────────────────────────────────

**Step 1 — DIAGNOSE.** Read every file in
`app/src/main/java/local/phantom/companion/ui/settings/`. Map:
- which screens have «Видалити все» button — and is it the only
  visible action?
- which save flows exist (per-screen apply / global save / reactive
  no-save)?
- is there a ModalBottomSheet or AlertDialog blocking save?

**Step 2 — DESIGN.** Settings should be REACTIVE (no manual save):
each toggle/slider mutates VoicePreferences immediately, persists
to encrypted prefs, UI confirms with tiny haptic + bottom-line «✓
збережено». Destructive actions («Видалити все», «Очистити cache»)
require AlertDialog confirm — distinguished by color (red ink) and
explicit «так, видалити».

**Step 3 — IMPLEMENT.**
- Files: every Settings screen file. Each toggle: `onCheckedChange =
  { viewModel.setX(it) }` where viewModel writes through to prefs
  immediately.
- File: NEW `app/.../ui/settings/SaveConfirmationStrip.kt` — bottom
  bar shows «✓ збережено» for 1.5s after each change.
- Move «Видалити все» action into a "Небезпечна зона" expandable
  section (collapsed by default). Color: theme.semanticDanger.
- AlertDialog с подвійним підтвердженням («так, видалити» — потім
  «справді так?» з 3-second cooldown).

**Step 4 — TEST.**
- `SettingsReactivityTest` — toggle change triggers immediate prefs
  write
- `DangerZoneTest` — destructive action requires AlertDialog confirm
  + 3-second cooldown

──────────────────────────────────────────────────────
QH2 — Theme reactivity (~45 min)
──────────────────────────────────────────────────────

**Diagnosis:** `LocalPhantomTheme` is `staticCompositionLocalOf` —
provided ONCE at root. Theme changes don't propagate.

**Fix:**
- File: `core-design/.../theme/LocalPhantomTheme.kt`
- Change to `compositionLocalOf<PhantomTheme>` (non-static,
  invalidates subscribers on change).
- File: `app/.../MainActivity.kt` (or root composable)
  - Replace `CompositionLocalProvider(LocalPhantomTheme provides
    fixedTheme)` with:
    ```kotlin
    val themePrefs by container.userPrefs.themeFlow.collectAsState()
    val resolvedTheme = remember(themePrefs) { themeFor(themePrefs) }
    CompositionLocalProvider(LocalPhantomTheme provides resolvedTheme) { ... }
    ```
- `userPrefs.themeFlow` is a `Flow<ThemeMode>` — add to
  `UserPreferences.kt` if missing. Persist to encrypted prefs.
- Theme switcher in Settings: dropdown («Світла», «Темна»,
  «Авто·системна»). Tap → flow emits → root re-resolves → UI
  re-tints in <16ms.

**Test:**
- `ThemeReactivityTest` — composable test that toggles themeFlow
  and asserts new colors propagate without restart.

──────────────────────────────────────────────────────
QH3 — Models actually consumed after download (~1.5 hours)
──────────────────────────────────────────────────────

**Diagnosis:**
1. `AppContainer.voiceManager` initialized once at lazy access. If
   model files don't exist yet, `initStt(voskDir, whisperPath)`
   wraps with FakeSttEngine.
2. `ModelManager.download(type)` writes to disk but never re-fires
   `voiceManager.refreshModels()`.
3. `StyleTts2OnnxRoute` — `isReady()` is a stub permanent-false.

**Fix (this is the biggest commit, ~1.5 hour):**

**Part A — Re-init on download complete:**
- File: `core-ai/.../ModelManager.kt`
  - Expose `downloadEvents: SharedFlow<DownloadEvent>` with kinds
    Started / Progress / Completed / Failed.
- File: `app/.../data/AppContainer.kt`
  - In `init`, subscribe `modelManager.downloadEvents` filtered to
    Completed → call `voiceManager.refreshModels(voskDir,
    whisperPath)`.
  - On Completed of STYLE_TTS → call `(ttsEngine as? RoutedTts)
    ?.refreshPremiumStatus()`.

**Part B — Real StyleTTS2 inference (this is Phase 7-A scaffolding):**
- File: `core-voice/.../route/StyleTts2OnnxRoute.kt`
  - Implement `isReady()` to check ONNX file exists + checksum.
  - Implement `speak(text)`:
    1. UaPhonemizer (existing) → IPA tokens
    2. ONNX Runtime session for acoustic model → mel-spectrogram
    3. ONNX Runtime session for vocoder (HiFi-GAN included in
       patriotyk/styletts2-ukrainian) → 24kHz audio waveform
    4. AudioTrack play
  - Tests with a tiny 1-sec mock audio output (we don't run real
    inference in unit tests; mock the ONNX session).

**Part C — Operator-visible signal:**
- Settings → Voice screen shows current active route:
  «голос: clone (StyleTTS2)» / «голос: System TTS (uk-UA)» /
  «голос: вимкнено». Updates reactively from RoutedTts state flow.
- Settings → STT shows current active engine: «слух: Whisper-tiny +
  Vosk-UA» / «слух: Vosk-UA only» / «слух: вимкнено».

**Test:**
- `VoiceManagerRefreshTest` — fake download event → `initStt` called
- `StyleTts2RouteIsReadyTest` — file exists vs not vs checksum
  mismatch
- `RoutedTtsAutoPromoteTest` — premium becomes ready → next speak()
  uses premium
- `SettingsActiveRouteIndicatorTest` — UI updates on route change

──────────────────────────────────────────────────────
QH4 — Error surface to Operator, not Logcat (~1 hour)
──────────────────────────────────────────────────────

**Diagnosis:** `runCatching {...}.getOrElse { Log.e(...); null }`
swallows everywhere. Operator gets `null` and silence.

**Fix:**

**Part A — Centralized error envelope:**
- File: NEW `core-data/.../errors/PhantomError.kt`
  ```kotlin
  data class PhantomError(
      val id: String,
      val ts: Long,
      val source: String,        // "VoiceManager", "PairFlow", "AIRouter"
      val severity: Severity,    // Info | Warn | Error | Critical
      val message: String,       // operator-facing, Ukrainian
      val cause: Throwable?,     // raw, stays Logcat-only
  )
  ```
- File: NEW `core-data/.../errors/PhantomErrorBus.kt`
  - `MutableSharedFlow<PhantomError>` with `extraBufferCapacity = 32`
  - Single instance in AppContainer, injected everywhere.

**Part B — Replace silent failures:**
- AppContainer: 4 known sites (`aiRouter`, `ttsEngine`, `voiceManager`,
  `modelManager`) — replace `Log.e` with `errorBus.tryEmit(PhantomError(...))`.
- StreamViewModel — already has Notice items; subscribe `errorBus`,
  on Error/Critical → render as Notice in Stream.

**Part C — Settings → Діагностика screen:**
- File: NEW `app/.../ui/settings/DiagnosticsScreen.kt`
  - Last 50 errors (rolling buffer in `PhantomErrorBus.history`)
  - Group by source, expandable. Tap → full stack trace.
  - Status row at top: пара (✅ ні) / WS (✅ connected) / TTS / STT /
    GPS — все live state from respective stores.

**Test:**
- `PhantomErrorBusTest` — emit + history capture
- `StreamErrorSurfaceTest` — fake Critical error → Notice item
  appears in Stream

──────────────────────────────────────────────────────
QH5 — AI / VoiceManager / WS auto-recover (~45 min)
──────────────────────────────────────────────────────

**Fix:**

**AIRouter:**
- Add retry-with-backoff on `AIError.NetworkTransient` (1s, 3s, 7s).
- After 3 failures, fall back to LocalGemma if available, else
  emit `AIError.Permanent` to errorBus.

**VoiceManager:**
- `try { ... } catch (oom: OutOfMemoryError) { ... }` — STT can OOM
  on long recordings; gracefully terminate session, emit error.
- Engine self-restart on `IllegalStateException` from Vosk.

**PhantomLink (WebSocket):**
- Already has reconnect logic; verify it surfaces «з'єднання
  втрачено · перепідключаюсь» as Notice in Stream.

**Test:**
- `AIRouterRetryTest` — 2 failures + 1 success → result returned
- `AIRouterFallbackTest` — 3 failures → falls to local
- `VoiceManagerOomTest` — synthetic OOM → graceful error, not crash

──────────────────────────────────────────────────────
QH6 — Final APK + Settings → Diagnostics polish (~30 min)
──────────────────────────────────────────────────────

**Verify:**
- Operator can save settings other than «Видалити все» (QH1)
- Theme switches without restart (QH2)
- Downloaded models actually used (QH3)
- Errors surface to Stream as Notice (QH4)
- Network blip → AI auto-recovers (QH5)

**Build APK:**
```bash
cd /home/radxa/programming/my_own/phantom_ai/PHANTOM_OS_BLUEPRINT/phantom-companion
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew :app:assembleDebug
TS=$(date +%Y%m%d-%H%M)
SHA=$(git rev-parse --short HEAD)
DEST=~/Downloads/phantom-companion-quality-${SHA}-${TS}.apk
cp app/build/outputs/apk/debug/app-debug.apk "$DEST"
ls -lh "$DEST" && md5sum "$DEST"
```

**Polish in same commit (small):**
- Diagnostics screen accessible from Settings root («· діагностика
  системи · »)
- Pair status on header now reactively reflects WS state
- Active TTS/STT engines visible in Settings → Voice without diving
  into sub-screens

═══ NON-NEGOTIABLE CONSTRAINTS ═══

1. **One fix = one atomic commit.** Build green per commit.
2. **Sequential subagent dispatch.** Memory: host crashes on
   parallel Agents. ONE at a time. Direct tools first.
3. **Read before edit.** Edit hook will reject otherwise.
4. **Don't break existing surfaces.** All 7 in tray must navigate.
5. **No new dependencies.** Use what's on classpath.
6. **No phantom-os sibling-repo touches.**
7. **Operator's exact words preserved in user-facing copy.**
   Ukrainian, ти-form, lowercase, intimate. Don't paraphrase.
8. **No feature creep.** If you find another bug while fixing one,
   add it to a `docs/PHASE_2_QH_FOLLOWUPS.md` list — don't try
   to fix it this session.

═══ SCOPE GUARDRAIL ═══

If you finish a fix in less time than budgeted, USE THE MARGIN to:
- Add more diagnostic visibility
- Add more tests around the affected code
- Polish the visible UX of THAT fix specifically

Do NOT use the margin to start the next bigger Wave. The mega-prompt
(`PHASE_2_REMEDIATION_AND_3_FOUNDATIONS_PROMPT.md` Waves 1-6) is the
next session's brief — not yours.

═══ HOW TO REPORT BACK ═══

After each commit, 3-5 sentences:
1. QH ID + 1-line scope
2. Files changed (count + 2-3 paths)
3. Test result + build result
4. Anything surprising
5. Next QH

After QH6 final:
1. APK path + md5 + size
2. 5-item operator verification checklist (one per QH1-QH5)
3. List of issues you found but DEFERRED to followups doc
4. STOP. No mega-prompt phases this session.

═══ OPERATOR VERIFICATION CHECKLIST (POST-APK) ═══

For QH6 to be «зеленим» on operator's device:

1. Settings → toggle any non-destructive option → see «✓ збережено»
   confirmation strip → no save button needed → reopen app → state
   persists.
2. Settings → Theme dropdown → switch dark/light → tint changes IN
   PLACE without restart, in <1 second.
3. Settings → Models → download Whisper-tiny → wait → Stream PTT →
   say «привіт» → see operator-facing «слух: Whisper» indicator
   active → real transcription appears.
4. Adb pull network cable → AI prompt → Stream Notice «AI: мережа
   відсутня · спробую локально» → either local Gemma replies (if
   model installed) or error Notice — but operator gets a signal.
5. Settings → Діагностика → see last 5 errors (test by faking
   one), see live status row (pair/WS/TTS/STT/GPS).

═══ REFERENCE PATHS QUICK-CARD ═══

```
docs/PHASE_2_QUALITY_HARDENING_PROMPT.md     ← THIS file
docs/PHASE_2_REMEDIATION_AND_3_FOUNDATIONS_PROMPT.md  ← next session
docs/PHASE_2_OVERNIGHT_PROMPT.md             ← prior context
~/.claude/projects/.../memory/               ← 9 entries
git log --oneline 70fea89..HEAD              ← your commits this session
```

You have ~6 hours. 6 commits. One green APK at the end. Operator
verifies the 5-item checklist. He goes to bed satisfied that Phase
2 is no longer «гівнокод».

Begin by reading every file under `app/src/main/java/local/phantom/
companion/ui/settings/`. Don't write code until you can map every
existing settings flow. Then start QH1.
============================================================
```

---

## Why this prompt structure

**Differs from `PHASE_2_REMEDIATION_AND_3_FOUNDATIONS_PROMPT.md`:**

- **Quality not features.** Six fixes target the five wounds operator
  named explicitly, in his words.
- **No new screens** (except small Diagnostics reusing existing
  patterns).
- **No new dependencies / no module changes.** Pure refactor + wire-up.
- **Hard scope guardrail.** If margin time appears, polish the
  current fix — don't drift into the next bigger Wave.
- **Operator-specific verification checklist.** Each QH has a
  named action operator can perform on his device to confirm.

## When to use this prompt

- Operator's last APK shows quality issues he won't tolerate.
- Want to NOT add scope before what's there is solid.
- ~6 hour budget Claude Code = 1 month human equivalent of polish.

## Companion artifacts

- `docs/PHASE_2_QUALITY_HARDENING_PROMPT.md` (this file)
- `docs/PHASE_2_REMEDIATION_AND_3_FOUNDATIONS_PROMPT.md` (next-up)
- `docs/PHASE_2_OVERNIGHT_PROMPT.md` (prior context)
- 9 memory entries

After this session lands its APK, operator decides whether to start
the mega-prompt's Waves 1-6 or sit on the polished build a while.
