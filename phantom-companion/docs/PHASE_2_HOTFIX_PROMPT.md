# Phase 2 Hotfix — Full Prompt for Next Claude Code Session

> Copy-paste **everything between the two `===` lines** into a new
> Claude Code session as the very first message. Target run-time:
> ~2-3 hours autonomous execution. Three atomic fixes for regressions
> surfaced by operator's live test of APK `1782387` on SM-A566B.

```
============================================================
You are joining the PHANTOM Companion project mid-stream. Phase 2
overnight (commits 4285484..1782387) shipped 23 commits across voice
loop / Whisper overlay / Diary sync / generative widgets — all green
in CI. Operator installed APK md5 `3da6bbf454ba488e4d832016b9d27ee2`
on Samsung SM-A566B / Android 16 and reported four regressions in
live testing. Three are real bugs; one is intentional but needs
operator clarity. Your job is three atomic hotfix commits — one
per bug. ~2-3 hours total.

═══ READ FIRST ═══

1. docs/PHASE_2_OVERNIGHT_PROMPT.md
   The brief that drove the 23-commit overnight. Skim §"Track 4"
   (widgets) and §"Track 1" (voice) — bug context lives there.

2. docs/PHASE_2_HOTFIX_PROMPT.md
   THIS file. Bug analysis already done — root causes pinned.

3. ~/.claude/projects/-home-radxa-programming-my-own-phantom-ai-PHANTOM-OS-BLUEPRINT-phantom-companion/memory/MEMORY.md
   9 memory entries. CRITICAL for tonight:
   - `host_agent_concurrency.md` — sequential Agent dispatch only.
     Host crashes Claude Code with parallel subagents.
   - `vault_lock_one_way_invariant.md` — does not directly apply
     here, but reinforces UI-state vs domain-state separation
     principle. Apply same thinking to widget/text item separation.
   - `kdoc_close_inside_backticks.md` — for fix #2, watch out for
     ``[LOCATION:lat,lon]`` strings in KDoc — escape if needed.

4. core-ai/src/main/java/local/phantom/companion/core/ai/SystemPrompts.kt
   The system prompt with hardcoded "Київ" examples (root cause of
   bug #3).

5. feature-stream/.../StreamViewModel.kt (lines 423-486)
   `finalizeAssistant` — the JSON detection logic that fails on
   markdown-wrapped output (root cause of bug #1).

═══ CURRENT STATE ═══

Branch: `companion-v2-phase-0`
Last commit: `1782387` Phase 2-A7 deferred E2E checklist
Working tree: should be clean before you start (verify via
`git status`)

═══ BUGS — ROOT CAUSES ALREADY PINNED ═══

**Bug #1 — Generative widgets render as raw JSON in Stream**
   Operator types «що з погодою» / «встанови таймер на 5 хвилин» /
   «куди до магазину Епіцентр» / «запусти hello world Python» —
   AI returns valid widget JSON, but it shows as plain text in
   Stream instead of rendering as TimerWidget / WeatherWidget /
   RouteWidget / CodeRunnerWidget.

   Root cause:
   - `StreamViewModel.finalizeAssistant` (line 432-473) parses
     JSON only if `trimmed.startsWith("{") && endsWith("}")`.
   - Gemini wraps JSON in markdown fences ```json {...} ```
     OR adds a Ukrainian preamble «звичайно, ось погода:» before
     the JSON.
   - Either case → `startsWith("{")` returns false → falls through
     to `listOf(item.copy(text = resolved))` — JSON sits as text.

   **This also explains Bug #4** — CodeRunnerWidget gating works
   correctly when the widget renders, but since JSON-as-text bypasses
   the widget, the gate never engages.

**Bug #2 — Mini-map renders as solid purple placeholder**
   `RouteWidget.MiniMap` (line 248-256) attempts an OSM tile fetch
   on Dispatchers.IO inside a LaunchedEffect. On failure (no network,
   permission denied, OSM rate-limit, blocked IP) bitmap stays null
   → `failed = true` → fallback `theme.glassPanel.copy(alpha=0.4f)`
   shows as purple glass with a small "карта недоступна" label.

   The widget code is correct; the network layer fails silently to
   the operator. The "purple" is the glass-panel fallback color, not
   a rendering bug.

   No OSM logo overlay is being drawn (operator asked) — this is
   correct. OSM ToS requires attribution somewhere in the UI; that's
   a separate concern, not for this hotfix. Just make the failure
   path more obvious so operator knows network is the issue.

**Bug #3 — Weather always for Kyiv, ignoring real location**
   Two compounding causes:
   - `SystemPrompts.DEFAULT_PHANTOM` (line 24-25) embeds "Київ" in
     the example JSON: `"location":"Київ"`, `"label":"Київ"`. The AI
     naively copies the example.
   - No real GPS coordinates are injected into `enrichedContext`.
     `sensorAdapter` is gated by `BuildConfig.ENABLE_SENSOR` and not
     plumbed to AI context anyway.

**Bug #4 — Code-runner widget is willing to "run" without pairing**
   Same as Bug #1. CodeRunnerWidget.kt line 165-173 has correct
   gating via `LocalPhantomPairedStatus.current` — but the JSON-as-
   text bypass means the widget never renders, so the operator sees
   the raw `{"actions":[{"id":"run-on-radxa"}]}` payload regardless.

═══ THREE ATOMIC HOTFIX COMMITS ═══

Execute serially, one commit per fix, build green between each.

──────────────────────────────────────────────────────
HOTFIX 1 — JSON extraction tolerates markdown fences + preamble
──────────────────────────────────────────────────────

**Acceptance:**
- AI says «звичайно, ось погода:\n```json\n{"type":"weather",...}\n```»
  → WeatherWidget renders.
- AI says «{"type":"timer",...}» → TimerWidget renders (regression
  guard for the existing happy path).
- AI says «погоди трохи, ось маршрут\n{"type":"route",...}» (preamble
  without code fence) → RouteWidget renders.
- AI says «привіт» (plain text) → renders as ASSISTANT text bubble
  (no false-positive widget detection).

**Files:**
- create `feature-stream/.../parse/AssistantJsonExtractor.kt` (~80 LoC)
  - `fun extractWidgetJson(raw: String): JsonObject?`
  - Strips ```` ```json ```` and ```` ``` ```` fences.
  - Finds first balanced `{...}` substring via brace-depth scanner
    that respects string literals (so `"...{...}..."` doesn't
    confuse the counter).
  - Returns parsed `JsonObject` only if it has a top-level `"type"`
    string field.
- modify `feature-stream/.../StreamViewModel.kt` line 423-486
  - Replace the inline `trimmed.startsWith("{")` check with
    `AssistantJsonExtractor.extractWidgetJson(resolved)`.
  - Keep the existing `command` / `task-list` / `question` /
    fallback `Widget(type)` switch — only the *detection* changes.
- create `feature-stream/src/test/.../parse/AssistantJsonExtractorTest.kt`
  (~150 LoC, ~10 cases):
  - markdown ` ```json ` fence (with and without language tag)
  - markdown ` ``` ` fence
  - Ukrainian preamble + JSON
  - JSON with embedded string containing `{`
  - JSON with embedded string containing `}`
  - whitespace-only input
  - garbage prefix + JSON + garbage suffix
  - nested JSON object (`payload` contains `actions: [{...}]`)
  - missing `"type"` field → returns null
  - non-JSON noise → returns null

**Test it for real on device after commit:**
```
adb install -r ~/Downloads/phantom-companion-hotfix-<sha>-<TS>.apk
# Open Stream, type each prompt:
#   «встанови таймер на 1 хвилину»     → TimerWidget с countdown
#   «що з погодою?»                     → WeatherWidget chart
#   «куди до магазину АТБ?»             → RouteWidget mini-map
#   «запусти hello world python»        → CodeRunnerWidget gated
```

──────────────────────────────────────────────────────
HOTFIX 2 — Inject real [LOCATION] into AI context, fix prompt
──────────────────────────────────────────────────────

**Acceptance:**
- Operator's GPS lat/lon (or last-known) is appended to AI's
  `enrichedContext` as `[LOCATION:50.4501,30.5234]` (or similar
  format; just be consistent).
- SystemPrompts updated: instructions tell AI «використовуй
  координати з [LOCATION:...] блоку. НЕ копіюй прикладні координати
  «Київ» — це лише приклад формату».
- Examples in SystemPrompt CHANGED: replace literal "Київ" with
  generic placeholders («<моє місто>», `lat:<my_lat>`).
- If GPS permission not granted or sensor disabled, send
  `[LOCATION:unknown]` — AI handles gracefully («можу показати
  погоду, скажи у якому місті ти зараз»).

**Files:**
- create `core-ai/.../context/LocationContextProvider.kt` (~60 LoC)
  - `fun current(): Pair<Double, Double>?` — reads from
    `MobileSensorAdapter.batches.replayCache.lastOrNull()?.gps` if
    available, falls back to `LocationManager.getLastKnownLocation`
    with PASSIVE_PROVIDER (no battery cost).
  - Returns `null` if neither is available.
  - Uses `ContextProvider` interface so AppContainer can inject.
- modify `app/.../data/AppContainer.kt`
  - Wire `LocationContextProvider` (lazy).
  - Pass to `StreamViewModel` constructor.
- modify `feature-stream/.../StreamViewModel.kt`
  - In the `enrichedContext` builder (around line 370-380, look
    for `enrichedContext = context + ...`), prepend
    `[LOCATION:${lat},${lon}]\n` if provider returns coords,
    else `[LOCATION:unknown]\n`.
- modify `core-ai/.../SystemPrompts.kt`
  - Replace example JSON occurrences of "Київ" / `lat:50.45` /
    `lon:30.52` with generic «<моє місто>» / placeholder format.
  - Add new rule line:
    «Поточні координати оператора надаються у блоці
     `[LOCATION:lat,lon]` на початку контексту. Використовуй ЇХ для
     погоди / маршруту, НЕ приклади з цього промпту. Якщо
     `[LOCATION:unknown]` — попроси користувача назвати місто.»
- create `core-ai/src/test/.../context/LocationContextProviderTest.kt`
  - sensor-adapter has GPS → returns sensor coords
  - sensor-adapter null + LocationManager has lastKnown → returns those
  - both null → returns null
- create `core-ai/src/test/.../SystemPromptLocationTest.kt`
  - Verify systemprompt contains `[LOCATION` instruction
  - Verify NO literal "Київ" in examples (regression guard)

**Permissions note:**
- `ACCESS_COARSE_LOCATION` permission already in manifest (per
  Phase 0 pair flow). No new permission needed.
- LastKnown PASSIVE provider doesn't actively request a fix, so no
  battery impact.

──────────────────────────────────────────────────────
HOTFIX 3 — RouteWidget tile-fetch surface failure clearly
──────────────────────────────────────────────────────

**Acceptance:**
- When OSM tile fetch fails, the placeholder shows reason:
  - timeout → «карта недоступна · мережа повільна»
  - 429 / 5xx → «карта недоступна · сервер OSM не відповідає»
  - no network → «карта недоступна · перевір з'єднання»
- One automatic retry with 600ms delay before declaring failure.
- Background switches from purple `theme.glassPanel.copy(alpha=0.4f)`
  to a more neutral `theme.surfaceBase.copy(alpha=0.6f)` — operator
  read the purple as a "rendering bug", make it look like a quiet
  empty state instead.
- Successful fetch path unchanged.

**Files:**
- modify `feature-stream/.../widget/RouteWidget.kt`
  - Promote the inline `fetchTile` to return `Result<Bitmap, FailKind>`
    where `FailKind` is `Timeout | Http(code) | NoNetwork | Decode`.
  - `MiniMap` Composable receives the `FailKind` and switches
    on it for the placeholder copy.
  - One retry: if first fetch returns `Http(429)` or `Timeout`,
    delay 600ms and try once more before surfacing.
  - Background color change as above.
- modify (or create) `feature-stream/src/test/.../widget/RouteWidgetTileFetchTest.kt`
  - mock HttpURLConnection responses for: 200, 429, 500, timeout, no-network
  - assert correct FailKind classification + retry behaviour

**OSM logo concern:**
- Operator asked about hiding map service logo. Confirm current
  state in commit message: «RouteWidget малює тільки tile bitmap +
  pins; жодного logo overlay не додано. OSM ToS вимагає attribution
  десь у UI (Settings → About — окрема фаза).»
- Do NOT add attribution in this commit — it's a separate concern
  from the regression fix.

═══ EXECUTION ORDER ═══

```
Hotfix 1  JSON extraction (~1 hr)        ← unblocks all 4 widget visuals
Hotfix 2  Real [LOCATION] context (~45 min)
Hotfix 3  RouteWidget failure surfacing (~30 min)
Final    Build APK + copy to ~/Downloads + verify on device
```

Hotfix 1 is critical-path — operator can't visually verify hotfix 2
or 3 until widgets render. Land it first, build APK, hotfix 2 builds
on top, hotfix 3 builds on top.

═══ NON-NEGOTIABLE CONSTRAINTS ═══

1. **One fix = one atomic commit.** `./gradlew :<module>:test
   :app:assembleDebug :app:lintDebug` green per commit.

2. **Sequential subagent dispatch.** Memory entry
   `host_agent_concurrency.md`. ONE Agent at a time, prefer direct
   tools (Read/Bash/Grep) over Agent.

3. **Read before edit.** Edit hook will reject otherwise.

4. **Don't break Phase 1 / Phase 1.5 / Phase 2.** All 7 surfaces
   navigate, all existing widgets render. Especially: existing
   `task-list`, `question`, `command` paths in the JSON-detection
   switch — they're production after Phase 1-A onward.

5. **No new memory entries unless surprising.** "Markdown fence
   strips fixed widget rendering" is routine. Save it to commit
   message, not memory.

6. **No dependency bumps.** kotlinx-serialization is the only dep
   you'd want to bump for `JsonElement` ergonomics — don't.
   Existing 1.6.x version handles balanced-bracket scanning fine.

7. **No phantom-os sibling-repo touches.** Backend routes for
   `decision_diary/*` and `companion_control/run_code` remain a
   backend-team concern.

═══ FINAL APK BUILD ═══

After Hotfix 3 commits cleanly:
```bash
cd /home/radxa/programming/my_own/phantom_ai/PHANTOM_OS_BLUEPRINT/phantom-companion
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew :app:assembleDebug
TS=$(date +%Y%m%d-%H%M)
SHA=$(git rev-parse --short HEAD)
DEST=~/Downloads/phantom-companion-hotfix-${SHA}-${TS}.apk
cp app/build/outputs/apk/debug/app-debug.apk "$DEST"
ls -lh "$DEST" && md5sum "$DEST"
```

═══ HOW TO REPORT BACK ═══

After each commit, summarize in 3-5 sentences:
1. Hotfix ID + 1-line scope
2. Files changed (counts + 2-3 paths)
3. Test result + build result
4. Anything surprising
5. Naming next hotfix or APK build

After APK build:
- File path + md5 + size
- One-line check: «Operator runs the four prompts from Hotfix 1
  acceptance + observes weather uses real location + observes
  RouteWidget shows reason for tile failure if any.»

═══ POST-FIX OPERATOR CHECKLIST ═══

Operator will verify by typing in Stream (record into memory if
gotcha surfaces):

1. «встанови таймер на 1 хвилину» — TimerWidget renders, counts
   down, vibrates + plays cue at 0
2. «що з погодою?» — WeatherWidget renders, shows hours array;
   verify location header is operator's actual city (not Київ
   unless operator IS in Київ)
3. «куди до магазину АТБ?» — RouteWidget mini-map either renders
   OSM tile OR shows specific failure reason (not just purple void)
4. «запусти hello world python» — CodeRunnerWidget renders,
   button disabled with «не з'єднано з phantom-os» when not paired
5. Long-press «ВАРТА» → all 7 surfaces still navigate, no
   regression
6. PTT hold → STT → AI replies → System TTS speaks (regression
   guard for Phase 2-A pipeline)
7. Whisper Line overlay still drifts (Settings → Whisper toggle)

═══ REFERENCE PATHS QUICK-CARD ═══

```
docs/PHASE_2_OVERNIGHT_PROMPT.md   ← prior session's brief
docs/PHASE_2_HOTFIX_PROMPT.md      ← THIS file
docs/PHASE_2_E2E_CHECKLIST.md      ← 2-A7 deferred verification
core-ai/.../SystemPrompts.kt       ← bug #3 root
feature-stream/.../StreamViewModel.kt ← bug #1 root, line 423-486
feature-stream/.../RouteWidget.kt   ← bug #2 root, MiniMap fn
feature-stream/.../CodeRunnerWidget.kt ← bug #4 (already correct,
                                           confirms bug #1 chain)
~/.claude/projects/.../memory/      ← 9 entries, 1 critical (host concurrency)
git log --oneline 1782387..HEAD     ← your commits this session
```

You have ~2-3 hours. Three commits. One green APK at the end.
Operator runs the post-fix checklist. Don't write anything else
beyond these three fixes — Phase 2.5 (StyleTTS2 inference,
backend routes, ModelDownloadStep nav routing) is its own session.

Begin by reading the 5 documents listed above, then `git status`
+ `./gradlew test` to confirm working tree is clean and baseline
is green. Then start Hotfix 1.
============================================================
```

---

## Why this prompt structure

**Differs from `PHASE_2_OVERNIGHT_PROMPT.md`:**

- **Tighter read list (5 docs not 8)** — bugs are surgical, agent
  doesn't need to re-absorb the whole vision; just enough to
  understand each fix.
- **Root causes pre-pinned** — agent doesn't need to debug. The
  diagnostic work was done in this conversation; the prompt just
  encodes the fix.
- **Three commits hard-bounded** — no creep into Phase 2.5
  territory (StyleTTS2 inference, backend routes, etc.).
- **APK build step explicit** — operator will be running the post-
  fix checklist on device, agent must produce installable artifact.

## When to use this prompt

- Operator pastes the block above into a new Claude Code session.
- Agent runs ~2-3 hours, three commits + APK build.
- Operator installs APK, runs the 7-item post-fix checklist.

## Companion artifacts

- `docs/PHASE_2_HOTFIX_PROMPT.md` (this file)
- All Phase 2 docs (overnight prompt, E2E checklist)
- 9 memory entries (especially host concurrency)

Operator opens the new session, pastes the prompt block, and the
agent has full context. No re-explaining required.
