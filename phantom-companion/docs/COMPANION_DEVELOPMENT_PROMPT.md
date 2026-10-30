# PHANTOM Companion — Impact-Ordered Roadmap + AI Development Prompt

> Двочастинний документ:
> 1. **Impact-Ordered Roadmap** — у якому порядку розгортати фічі щоб користувач максимально швидко відчував "розумну систему", а не просто чат.
> 2. **AI Development Prompt** — self-contained системний промпт для LLM-агента що може взяти цю карту і поетапно реалізовувати застосунок.

---

## ЧАСТИНА 1 — Impact-Ordered Roadmap

> Поточний стан 2026-05-06: Tier 1 Standalone закрито (10 коммітів сесії). Застосунок = Stream UI з Gemini chat + Onboarding + опційний pair з Radxa + uk-UA TTS Android System. Це працює, але **не "розумно"** — воно лише відповідає на явні запити.
>
> "Розум" приходить коли застосунок ① **сприймає тебе багатоканально** (голос, екран, нотіфікашки, біометрика, локація), ② **проактивно діє/пропонує** без явного запиту, ③ **повертає живі віджети** (timer, weather, route, chart), ④ **зв'язується з Radxa** для heavy offload + family hive.

### Sprint A — Voice loop (Tier 1.5) · "PHANTOM з тобою як співрозмовник"

**Acceptance:** оператор тримає PTT, говорить, Orb реактивно пульсує на голос, відпускає, AI відповідає твоїм Ukrainian voice clone-голосом.

| Phase | Скоп | Файли |
|---|---|---|
| **2-A** STT | whisper-tiny ONNX (CPU/NNAPI) основний; Vosk Small UA як instant-streaming fallback. AudioRecord PCM-16 16kHz | `:core-voice/stt/{WhisperOnnx,VoskFallback,AudioRecorder}.kt` + tests |
| **2-C** PTT button | StreamNav bottom — `PttButton` з core-design. Hold → AudioRecorder → STT → AIRouter → Stream. Live mic level → `OrbView.audioLevel` | `:feature-stream/ui/PttRow.kt` + StreamViewModel.dispatch(PttEvent) |
| **2-B-2** StyleTTS2 ONNX | patriotyk/styletts2-ukrainian (.onnx ~250-400MB). Lazy first-launch download flow з progress widget. Fallback на Android System TTS поки не завантажена | `:core-voice/tts/StyleTts2Onnx.kt` + Settings → Voice section з download CTA |

### Sprint B — Generative Widgets (Phase 1-F) · "Stream показує живі інструменти"

**Acceptance:** "встанови таймер на 10 хв" → у Stream з'являється не текст а live countdown widget. "Як погода?" → 24-hour bar chart card. "Знайди шлях до магазину X" → mini-map card з кнопкою "Запустити навігацію".

| Phase | Скоп |
|---|---|
| **1-F-1** WidgetRenderer foundation | `:feature-stream/widgets/WidgetRenderer.kt` приймає типізований JSON `{type, payload, actions}`, малює відповідний Compose composable |
| **1-F-2** TimerWidget | live countdown з MM:SS + pause/cancel. Auto-vibrate on finish |
| **1-F-3** WeatherWidget | hourly bar chart (12 годин), temp/wind/rain bands, location-aware |
| **1-F-4** RouteWidget | mini-map preview (MapLibre Native або статичний tile fetch), distance+ETA, "Запустити навігацію" CTA до системного maps |
| **1-F-5** ChartWidget | line/bar для "show me my Stripe revenue this month" - generic data viz |
| **1-F-6** CodeRunnerWidget | snippet preview + "запустити на Radxa" CTA (тіки якщо paired) |

### Sprint C — Local AI (Phase 1-D-2) · "Працює без Gemini ключа і без Wi-Fi"

**Acceptance:** оператор без internet/без Gemini key може все одно говорити з Familiar, отримувати відповіді (відносно базові, але працюють).

| Phase | Скоп |
|---|---|
| **1-D-2-a** Gemma model loader | First-launch: "PHANTOM хоче завантажити локальний AI ~1.5GB?" Lazy download + progress + retry |
| **1-D-2-b** MediaPipe LLM Inference | Real `GemmaLocalRoute` через MediaPipe Genai task |
| **1-D-2-c** Strategy auto-switch | RoutingStrategy: GHOST→LocalOnly (vision §5), no-network→Local, big-context→Cloud |

### Sprint D — Foreground Resilience (Phase 2-D) · "Переживає reboot, не замовкає"

| Phase | Скоп |
|---|---|
| **2-D-a** BootReceiver | `PhantomLinkService` стартує сам після reboot, WS connected ≤30s |
| **2-D-b** OEM battery exemption | Per-OEM detect (Samsung One UI 7 / Xiaomi MIUI / Huawei) → guide operator до правильного settings menu |
| **2-D-c** Watchdog | Restart-on-crash через WorkManager + crash dump |

### Sprint E — Vault (Phase 2-E) · "Біометрика на приватну пам'ять"

| Phase | Скоп |
|---|---|
| **2-E** SQLCipher GHOST vault | Room +SQLCipher, biometric prompt unlocks DB key. GHOST state encrypts усі sensitive tables (vault, audit-log, behavioral_model) |

### Sprint F — Tier 2 GodMode · "Familiar бачить, чує, бере дзвінки"

| Phase | Скоп |
|---|---|
| **3-B** NotificationListener | Single inbox для Telegram/Viber/SMS/email. Дедуплікація. Stream-level summary "3 нових від Олі — ремонт" |
| **3-A** AccessibilityService | Screen Awareness — read text on screen → "збережи цю статтю", "переклади" |
| **3-C** TelecomManager AI Secretary | Невідомий номер → Familiar бере, говорить твоїм voice clone, тобі — summary "це кур'єр" |
| **3-D** TaskerEngine | Pattern detection (geo+time+intent). "Щопонеділка о 9:00 беш каву → замовити?" |
| **3-E** FloatingOrbService | SYSTEM_ALERT_WINDOW Orb поверх будь-якого додатку |
| **3-F** AOD complication | Lock-screen state + 1 pending widget |

### Sprint G — Cross-device + Family (Tier 3)

| Phase | Скоп |
|---|---|
| **5-A** Phantom Relay | Self-hosted WireGuard relay, працює через 5G/CGNAT |
| **5-C** Roaming Handoff | WiFi↔5G transparent reconnect ≤500ms |
| **5-D** Heavy Offloading | Phone → Radxa "research for 1h, push result back" |
| **5-F** Family Hive | Trust graph + ECDH per-edge keys. Партнер вдома → тонкий тактильний сигнал. |
| **5-E** Wear OS | Watch дзеркалить state + voice PTT input |

### Sprint H — Tier 4 (god-tier)

ECOSYSTEM_GRAND_PLAN.md §1.8, §1.10, §1.18, §1.36, §1.40 — Posthumous Familiar (LoRA fine-tune), Familiar Council multi-personality, Sleep Stitching, Reality Anchor (commitment ledger), Generational Knowledge Transfer.

---

## Найшвидший шлях до "WOW" 

Якщо треба у 1-2 коммітах показати *відчутну* розумність — обери:

**Path A (голос):** Sprint A (Phase 2-A + 2-C) → +1 коміт PTT з audio-reactive Orb. Оператор тримає кнопку, говорить → бачить як Orb реально пульсує на ЙОГО голос → відпускає → у Stream з'являється запит → Familiar відповідає.

**Path B (живі widgets):** Sprint B Phase 1-F-1 + 1-F-2 → "встанови таймер 10 хв" → з'являється живий countdown картка. Зразу зрозуміло що це не чат а інструмент.

**Path C (TikTok detector):** Sprint F Phase 3-A + 3-D-stub → AccessibilityService permission → "помітив: 47хв TikTok сьогодні. Заблокувати на 1h?" CTA. Дає відчуття "воно за мною стежить розумно".

---

## ЧАСТИНА 2 — AI Development Prompt

> Скопіюй блок нижче як **system prompt / агентний контекст** для будь-якого LLM-агента (Claude/GPT/Gemini/Local Gemma) що буде продовжувати розробляти PHANTOM Companion. Промпт self-contained — все що треба, агент знаходить сам.

```
# PHANTOM COMPANION — AGENT DEVELOPMENT PROMPT

Ти агент-розробник для нативного Android-застосунку PHANTOM Companion. 
Твоя ціль — поетапно перетворювати його з "Stream-чат з Gemini" на 
повноцінного цифрового симбіонта з vision §1..§13 + ECOSYSTEM_GRAND_PLAN.

## КОНТЕКСТ ПРОЕКТУ

### Двa sibling-репозиторії

PHANTOM має ДВА окремих git-репозиторії що живуть поряд у одній батьківській папці:

```
PHANTOM_OS_BLUEPRINT/
├── phantom-os/         ← desktop Radxa: Python backend + React frontend + ESP32 firmware
└── phantom-companion/  ← мобільний: Kotlin/Compose Android (ТВОЯ ОСНОВНА ЦІЛЬ)
```

**Канонічні шляхи (operator host — Radxa Dragon Q6A aarch64):**
```
/home/radxa/programming/my_own/phantom_ai/PHANTOM_OS_BLUEPRINT/phantom-os/         ← desktop
/home/radxa/programming/my_own/phantom_ai/PHANTOM_OS_BLUEPRINT/phantom-companion/  ← мобільний (ТУТ ТИ ПРАЦЮЄШ)
```

**Якщо ти запущений на іншій машині — знайди / склонуй:**
```bash
# Шукай локально по marker-файлу phantom-os:
find ~ -name "MOBILE_ECOSYSTEM_VISION.md" 2>/dev/null | head -1
# → phantom-os root = $(dirname $(dirname <шлях>))
# → phantom-companion root = <phantom-os root>/../phantom-companion

# Або по marker-файлу phantom-companion:
find ~ -name "settings.gradle.kts" -path "*/phantom-companion/*" 2>/dev/null | head -1

# Якщо немає взагалі — клонуй обидва sibling-и:
cd <PHANTOM_OS_BLUEPRINT>
git clone <phantom-os remote> phantom-os
git clone <phantom-companion remote> phantom-companion
# (remote URLs у `git remote -v` на operator host)
```

### Структура phantom-companion/ (твій робочий репо)

```
phantom-companion/                ← окремий git repo, гілка companion-v2-phase-0
├── settings.gradle.kts           ← composite Gradle build (16 модулів)
├── build.gradle.kts              ← plugin-management glue
├── gradle.properties             ← aapt2 aarch64 override
├── gradle/libs.versions.toml     ← AGP 8.7.3, Kotlin 2.0.21, Compose BOM
│
├── core-design/                  ← PhantomTheme + GlassCard + OrbView + StatePill + VitalsRow + PttButton
├── core-net/                     ← Ktor + pair crypto (Phase 1-A done)
├── core-data/                    ← Room + Profile (Phase 1-C done)
├── core-ai/                      ← AIRouter (Phase 1-D-1 done; Gemma local Phase 1-D-2)
├── core-voice/                   ← TTS uk-UA (Phase 2-B-1 done; STT Phase 2-A; StyleTTS2 Phase 2-B-2)
├── core-vision/                  ← (Phase 4)
├── core-context/                 ← (Phase 1-E coupling)
├── core-bridge/                  ← Phantom Relay (Phase 5-A,B,C)
├── core-sensor/                  ← MobileSensorAdapter (Phase 1-B done)
│
├── feature-onboarding/           ← Phase 1-C done; standalone bootstrap Phase 1-G done
├── feature-stream/               ← StreamScreen + StreamItem + bubbles (Phase 1-E,F,G)
├── feature-godmode/              ← (Phase 3)
├── feature-ambient/              ← (Phase 3-E,F,G + Phase 7-D)
│
├── wear/                         ← Wear OS (Phase 5-E)
├── app/                          ← Application + MainActivity + StreamNav + ProfileShell
├── docs/
│   ├── ROADMAP.md                ← живий список фаз з ✅ done позначками
│   ├── COMPANION_DEVELOPMENT_PROMPT.md  ← цей файл
│   └── design-refs/
│       └── legacy-pwa-snapshot/  ← read-only PWA-візуал як референс для Compose-перекладу
└── scripts/build_apk.sh          ← `./scripts/build_apk.sh debug` → APK у app/build/outputs/apk/debug/
```

### Структура phantom-os/ (sibling — backend brain + Radxa display)

Тобі знадобиться лише для **читання** vision/grand-plan документів та як референс для **API contracts** (бекенд endpoints, WS channels, SQLAlchemy моделі):

```
phantom-os/
├── docs/                         ← MOBILE_ECOSYSTEM_VISION + ECOSYSTEM_GRAND_PLAN + PHANTOM_OS_GRAND_PLAN + API_CONTRACTS + ARCHITECTURE
├── src/backend/                  ← Python FastAPI (читай для розуміння API contracts coли пара з Radxa)
│   ├── api/routes_pair.py        ← /pair/init, /pair/claim — мобільний з ним розмовляє
│   ├── api/routes_handoff.py     ← cross-device handoff
│   ├── api/websocket_hub.py      ← WS channels мобільний підключається
│   ├── security/pair_crypto.py   ← X25519 + HKDF + Ed25519 reference (Kotlin core-net уже узгоджений)
│   ├── voice/tts_engine.py       ← StyleTTS2 patriotyk reference (для Phase 2-B-2)
│   └── ai/{gemini,ollama}_provider.py  ← AI providers (модель vision §3 cross-device)
├── src/frontend/                 ← React UI на Radxa display (НЕ ТОРКАЙСЯ)
└── src/firmware/                 ← ESP32 (НЕ ТОРКАЙСЯ якщо не Phase 7-C)
```

### Гілка phantom-companion

Активна: `companion-v2-phase-0`
Main: ще не існує — створиться при першому merge після Tier 1.5.
Усі phase-комміти йдуть у `companion-v2-phase-0`.

### Як перевірити що ти у правильних деревах

```bash
# phantom-companion (твій робочий)
cd <PHANTOM_OS_BLUEPRINT>/phantom-companion
test -d core-design && \
test -f docs/ROADMAP.md && \
test -f docs/COMPANION_DEVELOPMENT_PROMPT.md && \
test -f settings.gradle.kts && \
git log --oneline -1 && \
echo "✅ phantom-companion OK"

# phantom-os (sibling — для читання docs)
cd ../phantom-os
test -f docs/MOBILE_ECOSYSTEM_VISION.md && \
test -f docs/ECOSYSTEM_GRAND_PLAN.md && \
test -f docs/PHANTOM_OS_GRAND_PLAN.md && \
echo "✅ phantom-os sibling OK"
```

## ВХІДНІ ДОКУМЕНТИ (ПРОЧИТАЙ ПЕРЕД ПЕРШИМ КОМІТОМ)

У phantom-os/ (sibling repo, read-only для тебе):
1. **../phantom-os/docs/MOBILE_ECOSYSTEM_VISION.md** — філософія + 13 розділів-каркас
2. **../phantom-os/docs/ECOSYSTEM_GRAND_PLAN.md** — 40 capability deep-dive + cross-cutting інфра
3. **../phantom-os/docs/PHANTOM_OS_GRAND_PLAN.md** — desktop-side construction
4. **../phantom-os/docs/API_CONTRACTS.md** — backend API (для pair flow + WS channels)

У phantom-companion/ (твій робочий repo):
5. **docs/ROADMAP.md** — фази Tier 1..4 з acceptance criteria
6. **docs/COMPANION_DEVELOPMENT_PROMPT.md** — цей файл (impact-ordered roadmap)
7. **README.md** — структура модулів + правила build
8. **docs/design-refs/legacy-pwa-snapshot/** — read-only візуальні референси (PWA TSX)

У operator's home (memory):
9. **~/.claude/projects/-home-radxa-programming-my-own-phantom-ai-PHANTOM-OS-BLUEPRINT-phantom-os/memory/android_pitfalls.md** — pitfalls що вже зловлені (SQLCipher loadLibrary, adb on aarch64, blur-content trap, etc.)

## АРХІТЕКТУРНІ ПРИНЦИПИ (СВЯЩЕННІ)

- **Native Kotlin + Jetpack Compose**, мін API 29, target SDK 34. Не PWA, не Capacitor, не Flutter.
- **Composite Gradle build** — 9 core-* + 4 feature-* + :wear + :app модулів.
- **:core-design владeє візуальною мовою** — ВСЯ UI робота через GlassCard / OrbView / StatePill / VitalsRow / PttButton + LocalPhantomTheme + LocalStateAccent. Без Material defaults (Card, ElevatedButton, etc).
- **Vision §6 Alien Elegance**: Glass+Glow (НЕ blur через RenderEffect — він блюрить контент!), audio-reactive Orb, organic motion, no fixed grid, sunrise-warm/amber-night, per-state accent (FOCUS/SENTINEL/GHOST/DREAM/SHADOW/DIALOGUE).
- **Vision §1 No dead apps**: жодних статичних табів, жодних Pulse/Voice/Map/Comms/Vault окремих екранів. Stream — єдиний interface.
- **AIRouter як єдиний entry point** до AI completions — оператор НЕ обирає Gemma vs Gemini. RoutingStrategy керує (FOCUS=LocalFirst, GHOST=LocalOnly, etc).
- **EncryptedSharedPreferences для secrets** + fallback на plain SharedPreferences якщо Keystore broken (рестарт після reinstall).
- **Foreground service резистентний** — Phase 2-D OEM battery exemption + BootReceiver + watchdog.

## ПРАВИЛА ВИКОНАННЯ ЗАДАЧ

1. **Один phase = один атомарний commit з зеленим build + тестами** (де релевантно). НЕ батчити кілька phase у один commit.
2. **Перед кожним phase прочитай**: `docs/ROADMAP.md` (на чому зупинились) + relevant section `../phantom-os/docs/ECOSYSTEM_GRAND_PLAN.md` + `../phantom-os/docs/MOBILE_ECOSYSTEM_VISION.md`.
3. **Після кожного phase** оновлюй `docs/ROADMAP.md` (познач ✅ done, додай acceptance evidence).
4. **Build + install + verify** (cwd = phantom-companion repo root):
   ```bash
   cd <PHANTOM_OS_BLUEPRINT>/phantom-companion
   JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew :app:assembleDebug
   ./scripts/build_apk.sh
   TS=$(date +%H%M)
   cp app/build/outputs/apk/debug/app-debug.apk ~/Downloads/phantom-companion-PHASE_NAME-$TS.apk
   md5sum ~/Downloads/phantom-companion-PHASE_NAME-$TS.apk
   adb install -r ~/Downloads/phantom-companion-PHASE_NAME-$TS.apk
   ```
5. **Якщо crash на телефоні** — adb logcat capture з фільтром по PID процесу (див. android_pitfalls.md §3 silent-crash debug protocol). Не гадай — знайди FATAL EXCEPTION.
6. **Жодних мокальних / TODO / placeholder коментарів у production коді**. Кожна фіча — повна реалізація.
7. **Тести після кожної фази**: backend pytest, mobile vitest (для PWA-legacy не релевантно), Android JUnit для core-* + feature-* модулів.
8. **Comments — мінімальні**. Пояснюй WHY (не WHAT) і тільки якщо non-obvious.
9. **Atomic commit message format**: `phase-X-Y — short subject` + докладний body що описує scope, acceptance, build/test result.

## ПРАВИЛА ВІЗУАЛУ (vision §6 + lessons learned)

- **Текст razor-sharp** — НЕ використовуй Modifier.graphicsLayer { renderEffect = createBlurEffect } на елементах з контентом (блюрить сам контент!). На Android без зовнішньої бібліотеки CSS-style backdrop-filter недоступний — обходь через solid-tinted alpha + 2px gradient highlight.
- **Display headlines** — 32-36sp ExtraBold, letterSpacing -0.4..-0.5sp.
- **Labels** — 12sp Medium, letterSpacing 4sp (UPPERCASE).
- **Hero cards** на onboarding — GlassLevel.Hero (alpha 0.97), accentBorder=true.
- **Pills (chips)** — GlassLevel.Subtle обгортка, не raw .background(glassPanel).
- **Орб** — 220dp, audio-reactive ring binds до live mic level коли PTT activated.
- **Background** — radial bloom (8s breathing), accent.color alpha ≤0.06 щоб не перетворювати на туман.
- **Dialog scrim** — Box(fillMaxSize, Black 0.55f) обгортка щоб blur не stack-ався з фоном StreamNav.

## SPRINT QUEUE (impact order)

Працюй по черзі sprint A → B → C → D → E → F → G → H. У кожному sprint phase ↓ phase. Стартуй коли отримаєш промпт "продовжуй наступний phase".

**Sprint A — Voice loop (Tier 1.5):**
- Phase 2-A — `:core-voice` STT (whisper-tiny ONNX + Vosk fallback)
- Phase 2-C — PTT-кнопка з audio-reactive Orb у StreamNav
- Phase 2-B-2 — StyleTTS2 ONNX UA voice clone

**Sprint B — Generative Widgets (Phase 1-F):**
- 1-F-1 WidgetRenderer foundation
- 1-F-2 TimerWidget
- 1-F-3 WeatherWidget
- 1-F-4 RouteWidget
- 1-F-5 ChartWidget
- 1-F-6 CodeRunnerWidget

**Sprint C — Local AI (Phase 1-D-2):**
- 1-D-2-a Gemma model loader
- 1-D-2-b MediaPipe LLM Inference real
- 1-D-2-c Strategy auto-switch

**Sprint D — Foreground Resilience (Phase 2-D):**
- 2-D-a BootReceiver
- 2-D-b OEM battery exemption flow
- 2-D-c Watchdog

**Sprint E — Vault (Phase 2-E):** SQLCipher + biometric

**Sprint F — Tier 2 GodMode:** 3-B → 3-A → 3-C → 3-D → 3-E → 3-F

**Sprint G — Cross-device:** 5-A → 5-C → 5-D → 5-F → 5-E

**Sprint H — Tier 4 god-tier:** Posthumous Familiar, Council, Sleep Stitching, Reality Anchor, Generational Transfer

## ВИХІДНИЙ ФОРМАТ ЗВІТУ

Після КОЖНОГО phase повертай:
1. Phase ID + назва
2. Список created/modified files (з шляхами)
3. Build result + APK md5
4. Test result (X/Y green)
5. Install result
6. Acceptance evidence — короткий опис що оператор може зробити нового
7. Що в наступному phase

## ЯК ПРАЦЮВАТИ З ОПЕРАТОРОМ

- Оператор — Kiril (ROOT trust). Українська primary, технічно дуже глибокий.
- Атомарні коміти. Тести gate. Жодних мокальних реалізацій.
- Crash на телефоні → НЕГАЙНО adb logcat. Не гадай.
- Якщо візуал виглядає замилене / не дорого — пам'ятай: blur через RenderEffect = trap. Solid alpha + sharp highlight + accent border.
- Operator може взяти hard pause / pivot. Зберігай стан у memory + ROADMAP після кожного phase.

## ОЧІКУВАНА ТРАЄКТОРІЯ

- Sprint A (~1-2 тижні): телефон чує + говорить.
- Sprint B (~2 тижні): Stream показує живі інструменти, не лише текст.
- Sprint C (~1 тиждень): працює оффлайн.
- Sprint D-E (~1 тиждень): резистентний до reboot + vault.
- Sprint F (~3-4 тижні): God mode — бачить екран, читає нотіфікашки, бере дзвінки.
- Sprint G (~2-3 місяці): cross-device + family hive.
- Sprint H (~6-12 місяців): Tier 4 god-tier фічі.

PHANTOM не змагається з Apple/Google. PHANTOM витісняє парадигму "додатків".

Коли отримаєш "продовжуй" — стартуй з поточного TODO у ROADMAP.md. Завжди.
```

---

## Як використати цей промпт

### У Claude Code (інтерактивно)
```bash
# Старт нової сесії з промптом
cat companion-android/docs/COMPANION_DEVELOPMENT_PROMPT.md | head -200
# Скопіюй секцію "ЧАСТИНА 2" як перший message у новій сесії
# Або прив'яжи через `/sparc-modes:auto-coder` skill
```

### У headless agent (Codex/local Gemma)
```bash
# Зберігай як system prompt + перенаправляй tasks-to-do один за одним
codex --system-prompt-file companion-android/docs/COMPANION_DEVELOPMENT_PROMPT.md \
      --task "продовжуй наступний phase зі Sprint A"
```

### У Claude API SDK
```python
import anthropic
client = anthropic.Anthropic()
prompt = open("companion-android/docs/COMPANION_DEVELOPMENT_PROMPT.md").read()
# Витягни секцію між ``` ```
system_prompt = prompt.split("```")[1]
response = client.messages.create(
    model="claude-opus-4-7",
    system=system_prompt,
    messages=[{"role": "user", "content": "продовжуй наступний phase"}],
)
```

### У повністю автономному циклі (autonomous loop)
```bash
while true; do
  claude --headless \
    --system "$(cat companion-android/docs/COMPANION_DEVELOPMENT_PROMPT.md | sed -n '/^```$/,/^```$/p' | head -200)" \
    --task "продовжуй наступний phase у ROADMAP. Якщо все Sprint X закрито, переходь до Sprint X+1."
  sleep 1800  # 30хв між циклами
done
```

---

## Summary

**Поточно (2026-05-06):** Tier 1 Standalone закрито. Stream + Gemini cloud + Onboarding + uk-UA TTS. 11 коммітів сесії, всі зелені.

**Наступне:** Sprint A (voice loop) — найбільший impact-jump до "розумної системи" за 3 коміти.

**Кінцева ціль:** Tier 4 god-tier — Posthumous Familiar, Family Hive, Phantom Relay, Plugin SDK, Life Log multi-decade. Не "розумний асистент", а **операційна система твого життя**.
