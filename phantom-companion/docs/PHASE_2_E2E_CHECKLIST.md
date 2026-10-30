# Phase 2 — E2E Live Verification Checklist (operator runs in the morning)

> Phase 2-A7 deferred-to-operator handoff. The autonomous overnight
> session shipped 22 commits across Voice (2-A1..A6), Whisper Line
> presence (3-E1..E6), Decision Diary sync (3-D1..D5), and the four
> generative widgets (1-F2..F6) — all green per `./gradlew test
> :app:assembleDebug :app:lintDebug` between every commit. What CI
> can't verify is the operator-perceived path on real hardware. This
> doc is the morning-coffee checklist.

## Pre-reqs

```bash
adb devices                                    # SM-A566B should appear
cd /home/radxa/programming/my_own/phantom_ai/PHANTOM_OS_BLUEPRINT/phantom-companion
./scripts/build_apk.sh                         # green ≈40s
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop local.phantom.companion.debug
```

Optional but recommended — capture all the right tags up front:

```bash
adb logcat -c
adb logcat -v brief \
  PhantomLink:D PhantomVM:V VoiceManager:V \
  StreamViewModel:V WhisperLine:V WhisperLineService:V \
  RoutedTts:V CodeRunner:V \
  AppContainer:V "AndroidRuntime:E" "*:S" \
  > /tmp/phantom-phase2-e2e.log &
```

---

## Track 1 — Voice loop (Phases 2-A1..A6)

### 2-A1 + 2-A4 — Mic-driven ring + ghost preview

1. Onboarding through to Stream.
2. Hold the mic button (bottom-left of the input row).
3. **Expect**: ring border around the mic pulses with your voice
   level; subtle gold-faint mono text «прив…» drifts above the
   compose row as Vosk emits partials.
4. Release. **Expect**: ghost text vanishes inside 200ms; ring
   border drops to zero alpha.

### 2-A2 — RECORD_AUDIO permission gate

1. `adb shell pm revoke local.phantom.companion.debug
   android.permission.RECORD_AUDIO`
2. Force-stop + relaunch the app.
3. Hold the mic button.
4. **Expect**: AlertDialog appears with verbatim copy «familiar
   просить» / «phantom потребує мікрофона щоб чути тебе.
   дозволиш?» / «надати дозвіл» / «пропустити».
5. Tap «надати дозвіл», grant in the system prompt that follows.
6. **Expect**: Without a second tap, the recording starts (the gate
   re-fires the originally pending action on grant).

### 2-A3 — Hold→STT→AI→TTS pipeline

1. With AI key set (Settings → Gemini key) and TTS toggled on.
2. Hold mic, say «привіт, скажи зараз котра година?», release.
3. **Expect**:
   - logcat: `VoiceManager: Final transcript: ...`
   - Stream shows USER bubble «привіт, скажи зараз котра година?»
   - Stream shows ASSISTANT bubble streaming the AI reply
   - System TTS speaks the reply once Final lands

### 2-A5 — STT model download UX

1. `adb shell pm clear local.phantom.companion.debug` (resets prefs +
   Room). Re-onboard.
2. Reach the «настроюю голос» step (insert it into onboarding flow
   first; see TODO in `ddc4b35` commit message).
3. **Expect**: Two model rows (Whisper ~150 МБ, Vosk ~50 МБ),
   per-row «завантажити» pill, bottom row with «пропустити» and
   «продовжити» (disabled until both models present).
4. Tap «завантажити» on each. **Expect**: progress bar ticks 0%
   → 100%, status text flips to «готово».
5. Tap «продовжити». **Expect**: lands in Stream with sttEnabled=
   true (PTT works).
6. Repeat from clean install but tap «пропустити». **Expect**:
   Settings → Voice shows sttEnabled=false; PttButton press is
   no-op (or in the future shows «увімкни STT в Settings» hint).

### 2-A6 — StyleTTS2 download flow

1. Settings → Voice → «голос-клон» row.
2. **Expect**: «premium TTS · 350 МБ · не завантажено» status text;
   if on cellular, a coral metered-network warning above the
   «·  завантажити  ·» pill.
3. Tap download. **Expect**: progress bar appears, ticks to 100%.
4. **Expect** (post-download): status text flips to «готово»; the
   confirmation line «RoutedTts автоматично переключиться на
   StyleTTS2» appears below.

> ⚠️ Note: StyleTTS2 inference is still stubbed in
> `StyleTts2OnnxRoute.kt` (Phase 2.5 unfinished); presence flips
> the route's `isReady()` predicate but `speak()` still falls
> through to System TTS until the ONNX inference path lands.
> Operator-perceived: they downloaded a 350MB blob that doesn't
> yet improve TTS quality. Document this in Settings → Voice
> tooltip in a follow-up commit.

---

## Track 2 — Whisper Line presence (Phases 3-E1..E6)

### 3-E1 + 3-E2 — Service start + ribbon drift

1. Settings → Whisper. Toggle «стрічка завжди-видима» on.
2. **Expect**: SYSTEM_ALERT_WINDOW rationale appears if not yet
   granted («потрібен дозвіл «поверх інших додатків»»). Tap
   «відкрити налаштування», grant in system page, return.
3. **Expect**: A 1.5dp gold-faint stripe appears at the very top
   of the screen drifting «phantom тут · присутність активна»
   leftward at Medium cycle (38s).
4. Notification shade should show the «фантом · присутність»
   foreground notification.

### 3-E3 — agentEvents → whisper queue

1. With Whisper enabled, pair to a phantom-os that emits proactive
   cycles.
2. Trigger an `agent.stream/proactive.cycle` from the backend
   (e.g. `curl POST /api/v1/agent/proactive/cycle`).
3. **Expect**: The default «phantom тут» message gets replaced by
   the proactive cycle's text (or default «phantom-os думає про
   тебе»), drifts for one full cycle (38s) + 2s pause, then the
   queue advances back to the default.

### 3-E4 — FLAG_SECURE auto-hide on banking apps

1. With Whisper enabled, open Monobank (`com.ftband.mono`).
2. **Expect**: The whisper stripe disappears the moment Monobank
   becomes foreground. The queue keeps draining (no backlog when
   you switch back).
3. Switch to WhatsApp. **Expect**: stripe reappears.
4. Same test with: Privat24, LastPass, Google Authenticator.

### 3-E5 — Settings UI

Done in 3-E1 step 1. Verify:
1. Frequency picker (Slow/Medium/Fast) flips the cycle duration
   (60s/38s/22s) on the next message.
2. Sound toggle does nothing yet (Phase 4 sound library reserved).

### 3-E6 — Preview deprecation banner

1. Long-press header → tap «whisper» surface.
2. **Expect** (overlay off): footer banner reads «1.5px стрічка
   поверх будь-якої апки · спробуй у Settings → Whisper · постійна
   стрічка зʼявиться поверх будь-якого додатка».
3. Toggle Whisper on in Settings → return to preview.
4. **Expect**: footer banner reads «постійна стрічка ✓ активна (з
   Settings → Whisper) · phantom малює тонкий рядок зверху ·
   перейди в Settings → Whisper щоб налаштувати частоту». Headline
   color is gold.

---

## Track 3 — Decision Diary sync (Phases 3-D1..D5)

### 3-D2 + 3-D4 — Local writes auto-publish

> Backend route `/decision_diary` does not exist yet (CONTRACT
> §10.15). Verification is by inspecting the WS frames the phone
> emits, not by round-trip.

1. Open the Diary surface. Tap FAB «+ нове рішення», fill in
   title/context/status, save.
2. **Expect** (logcat with `PhantomLink:D` enabled): an outgoing
   frame `RX` direction, channel=decision_diary, type=created,
   data carrying the entry's id+ts+title+context+status.
3. Pull WiFi (airplane mode). Save another entry.
4. **Expect**: The publish queues into `pending_publish` Room
   table. Verify with:
   `adb shell run-as local.phantom.companion.debug \
     sqlite3 databases/phantom-data.db \
     "SELECT verb, payload FROM pending_publish;"`
   (SQLCipher requires the per-install key — easier path:
   confirm via logcat that `PhantomLink: outbound queued`.)
5. Re-enable WiFi → reconnect. **Expect** (logcat): `AppContainer:
   diaryStore.drainPending` collector fires, sends each queued
   row, deletes them.

### 3-D5 — TimeShiftedHelp follow-up card

> Same caveat — backend doesn't emit `decision_diary/follow_up`
> yet. Test with a manual WS push.

1. Save a Diary entry with id "test-1", title "переїхати в lhasa",
   context "холодно".
2. Manually inject via the backend (or a curl PUT `/ws` with a
   valid token):
   `{"channel":"decision_diary","type":"follow_up","data":{
   "id":"test-1","prompt":"температура впала на 12°C"},
   "ts":1700000000000}`
3. **Expect**: A hero glass card appears above the input row in
   Stream with «PHANTOM · ПАМ'ЯТАЄ» tag and the verbatim message
   «у січні ти вирішив "переїхати в lhasa" тому що "холодно".
   зараз phantom бачить що "температура впала на 12°C" —
   переглянути?»
4. Three buttons: «Переглянути» (gold) / «Не зараз» / «Більше не
   нагадувати». Tap each in three separate trials and verify
   dismissal behavior.

---

## Track 4 — Generative widgets (Phases 1-F2..F6)

### 1-F2 — TimerWidget (live countdown)

1. With AI configured, type «встанови таймер на 30 секунд».
2. **Expect** (after Phase 1-F6 system-prompt update): AI emits
   `{type:"timer", payload:{duration_seconds:30, label:"...",
   started_at:<now>}}` instead of text.
3. **Expect**: Stream shows a TimerWidget bubble, ticking down
   30→0 over ~30s.
4. **Expect**: At 0, vibration pulse + ToneGenerator beep, label
   flips to «ЗАВЕРШЕНО».
5. Cancel button works (locks the bar gray, label «СКАСОВАНО»);
   pause toggles between «ПАУЗА» and «ПРОДОВЖИТИ».

### 1-F3 — RouteWidget (mini-map)

1. Type «куди до Епіцентру?» (with location services on).
2. **Expect**: Stream renders a RouteWidget with a 160dp mini-map
   showing the OSM tile that contains both endpoints, two pins
   (white/accent for origin, accent/white for destination), a
   gold line connecting them.
3. Distance label «1.5 км» / «12 хв».
4. «·  Запустити навігацію  ·» pill at the bottom — tap.
5. **Expect**: Google Maps / OsmAnd / Yandex Maps opens to the
   destination via geo: intent.

### 1-F4 — WeatherWidget (12-hour chart)

1. Type «що з погодою?»
2. **Expect**: WeatherWidget with location header + temp + a
   120dp Canvas chart showing temp line (top 60%) + rain bars
   (bottom 40%) over 12 hourly points.
3. Below the chart: «діапазон 5°…22° · 12 год» legend.

### 1-F5 — CodeRunnerWidget

1. Type «запусти hello world Python».
2. **Expect**: CodeRunnerWidget with header «PYTHON · env · ...»,
   monospace code block, «·  запустити на radxa  ·» pill at the
   bottom.
3. **Expect** (when not paired): Run button is disabled (gray);
   left-side text «не з'єднано з phantom-os».
4. Pair with phantom-os. **Expect**: Run button turns gold,
   becomes clickable.
5. Tap Run. **Expect**: AlertDialog «запуск на phantom-os» / «це
   виконається на phantom-os. дозволити?» / «запустити» / «ні».
6. Tap «запустити». **Expect** (logcat): outgoing
   `companion_control/run_code` envelope on the WS. Backend will
   404 until §10.16 ships — that's the spec, not a phone bug.

---

## Roll-up smoke

If the above all pass:

```bash
adb shell dumpsys activity local.phantom.companion.debug | grep -E "Service|state="
adb shell dumpsys notification | grep -A 2 "phantom_whisper"
adb shell dumpsys notification | grep -A 2 "phantom_link"
```

Both notification channels should be listed; both services should
be foreground.

---

## What's not in this checklist

These are intentionally out of Phase 2 scope per the original
prompt's drop-list / deferred items:

- StyleTTS2 actual ONNX inference (Phase 2.5) — model downloads
  but doesn't yet beat System TTS quality
- Backend routes for `decision_diary/*` and `companion_control/
  run_code` (CONTRACT §10.15 + §10.16) — phantom-os team picks up
- ModelDownloadStep insert into onboarding nav — needs a Phase 2.5
  routing decision; reachable today via direct invocation
- WhisperLineService SettingsDialog row (Settings → Whisper) —
  reachable via Routes.WhisperSettings if/when a route is
  registered; for now it lives as a screen the operator can hit
  via direct nav

If anything fails, the most useful artifacts to capture:

```bash
adb pull /data/data/local.phantom.companion.debug/cache/route_tiles
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml
cp /tmp/phantom-phase2-e2e.log ~/Downloads/phantom-phase2-fail-$(date +%H%M).log
```

The log filter at the top covers every Phase 2 surface; grep for
the surface that failed (e.g. `WhisperLineService` / `RoutedTts`
/ `VoiceManager`).
