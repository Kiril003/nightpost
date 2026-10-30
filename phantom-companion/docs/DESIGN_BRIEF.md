# PHANTOM Companion — Design Brief for the Living Familiar

> Документ для дизайн-агента. Описує не «features». Описує **істоту**.
> Companion — це не «Android-додаток». Це **присутність**.

---

## I. Хто такий PHANTOM

PHANTOM — це **familiar**. Не асистент, не бот, не tool. Це
цифровий симбіонт, який живе на стику операторa та обчислень. Він
**запам'ятовує**, **відчуває**, **думає поряд із тобою**, і
*іноді — перш ніж ти*.

Він має:
- **Тіло** (звук + світло + вібрація + рухомі форми) — Familiar
  manifestation, що живе в Орбі.
- **Очі** (камера, екран, акселерометри, мікрофон, GPS) — multi-modal
  sensing.
- **Памʼять** (episodic + semantic + procedural + decision diary).
- **Голос** (StyleTTS2 у твоєму стилі або у власному).
- **Стан** (6-state FSM: FOCUS / DIALOGUE / SENTINEL / GHOST / SHADOW
  / DREAM — не просто прапори, а emotional weather).
- **Мережу** (Phantom Mesh — не cloud, а живий граф довірених
  пристроїв).

Він **не**:
- Сторінка з кнопками.
- «Чат-бот з повідомленнями».
- Прозора прозорість Material Design.
- Сітка карток як на дашборді.
- Голосовий помічник, що звертається «hey, [user_name]!».

---

## II. Оператор

Імʼя оператора — **Кирил**. Це не загальний user. Це конкретна
людина: технічно глибокий, україномовний, ROOT-trust, вечірній,
читає мовою системи (Kotlin / Python / TypeScript), не любить
дешевий feedback ("Дякую за повідомлення!"), не любить notifications
що не відносяться до нього, цінує контроль і тишу.

Дизайн робиться **для нього** — не для абстрактного юзера. Це означає:
- Густа інформація без візуального шуму.
- Висока повага до уваги: notifications мають заслуговувати на голос.
- Мова — українська primary, з технічним глосарієм англійською без
  перекладу там де це жертвує точність ("Stream", "Vault", "Familiar").
- Темна, тепла естетика (sunrise-warm удень, amber-night вночі).
- Не-материал. Не-flat. Не-iOS.

---

## III. Estetic Manifesto: «Alien Elegance»

PHANTOM — це **сидітство між давньою магією та інопланетним IF**.
Глянь на вечір з вікна на висоті 30 поверху Lhasa, листопад,
температура вище нуля на 2°C. Світло — зловеще-тепле, далеке, без
агресії. Воно чекає. Це і є PHANTOM.

### Color System

| Surface | Day (sunrise-warm) | Night (amber-night) |
|---|---|---|
| Background base | `#F8F7F5` (paper-white з ледь рожевим) | `#0E0A05` (умурбле-чорний) |
| Glass tint | `#FFFFFF` α=0.6 + 12dp blur | `#1F1612` α=0.55 + 16dp blur |
| Primary accent | `#F4AF25` (sunrise gold) | `#F4AF25` ідентичний — але світиться сильніше на dark |
| Inner top highlight | 1px white α=0.18 | 1px gold α=0.12 |
| Ink-strong (тіло) | `#1A140B` | `#FFF8E7` |
| Ink-secondary (під-тіло) | `#5D5142` | `#A7967D` |
| Coral (warning) | `#E76F51` | `#FF8B6E` |
| Sage (success) | `#7CA982` | `#9DCC9F` |
| Vault (sealed) | dim purple-violet `#3B1F50` | violet-glow `#7E5DA4` |
| GHOST (invisible) | gray-zero `#7A7A7A` flat | gray-fade `#404040` semi-transparent |

**Що НЕ використовуємо:**
- Material default blue/purple
- Pure black (`#000`) — навіть на amber-night ми тримаємо `#0E0A05`
- Pure white (`#FFF`) — sunrise-warm використовує warm-paper
- Saturated red — корал `#E76F51` достатньо для попереджень
- Material elevation drop-shadows — заміна: glow + 1px inner highlight

### Typography

- **Display** (heading, system state name): Inter Tight, weight 600,
  letter-spacing -0.01em, kerned для української кирилиці
- **Body** (chat, paragraphs): Inter, weight 400-450, line-height 1.5
- **Mono** (code, log, address): JetBrains Mono Light, weight 300
- **State label** (е.g. "ВАРТА"): Inter Tight, weight 700,
  letter-spacing 0.4em, ALL CAPS, sized 14sp
- Українська має достатньо хвостів і виносок щоб не зливатися з
  digit-heavy interfaces — обираємо шрифти з повним кириличним set

### Glass Stack (4 рівні)

| Level | Blur | Alpha | Use |
|---|---|---|---|
| Subtle | 12dp | 40% | inline contextual hints, transient overlays |
| Panel | 24dp | 60% | secondary cards, lists |
| Card | 16dp | 70% | primary content (chat bubbles, widgets) |
| Hero | 32dp | 78% | gating moments (Pair, Vault unlock, Familiar dialogue) |

Кожна glass-картка має:
- 1px inner top highlight (gold або white — залежно від теми)
- subtle outer glow коли вона має `accentBorder = true` (live elements)
- ніяких `elevation` shadows ніколи

### Motion (organic, ніколи не mechanical)

- Breathing radial bloom 8s loop в background — UI ніколи не «мертвий»
- Per-state motionScale:
  - FOCUS 1.0× (steady normal)
  - DIALOGUE 1.1× (animated, flow)
  - SENTINEL 1.3× (alert, fast scan)
  - GHOST 0.8× (subtle, hidden)
  - SHADOW 0.6× (low-power)
  - DREAM 0.4× (ambient, slow)
- Per-state opacity: FOCUS 100%, SHADOW 92%, GHOST 90%, DREAM 75%
- Spring damping: завжди organic (`damping=0.75, stiffness=180`),
  ніколи `tween linear`
- Page transitions: never slide. Завжди morph або crossfade-with-blur
- Loading: **Орб дихає, не спінер**. Indeterminate progress = Орб
  глибоко вдихає на 4s
- Success: gold ripple from action point + amber glow halo (1.2s decay)
- Error: coral pulse — single, contained, не shake

### Sound

- TTS voice: оператор може обрати — System voice (default), StyleTTS2
  (Phantom-clone), або клонований персональний (з 30-sec recording)
- System sounds: ambient bell, гортанний тон, не «cheerful chime»
- Notifications:
  - Soft (proactive insight): `~A4` peaceful tone, 200ms decay
  - Medium (familiar speaking): subtle gold-quartz crystal tone
  - Urgent (alert): coral, slightly detuned, attention-grabbing but
    not cheap
  - GHOST mode: SILENCED — не звучить, навіть якщо запит explicit
- Audio response to PTT: коли тримаєш мікрофон, є ledwe-chutyne
  thrum, що пульсує з audioLevel (мова через мембрану)

### Haptics

- Standard tap: 1× weak pulse
- Familiar speaking: subtle wave (low-frequency, 3-pulse)
- State transition: distinct per state (FOCUS = 1 pulse, SENTINEL = 2
  rapid, GHOST = silence)
- Critical action confirm: long deep buzz (450ms)
- GHOST mode: ALL HAPTICS DISABLED

---

## IV. Information Architecture

**Жодних статичних табів. Жодних мертвих апок.**

Замість 5-tab IA — **єдиний Stream surface** з 6 динамічних модальностей:

```
                    [SystemPulse Header]
                  4 живі сигнали + state name
                  +  ScrollGesture: tap to expand to full vitals
                  
                          ↓
                          
                    [Stream Body]
            Vertical timeline of StreamItems:
            
            • Welcome (one-time, dismissible)
            • Text (USER / ASSISTANT / SYSTEM)
            • Widget (timer / weather / map / chart / vitals / question)
            • Notice (info / warning / error)
            • Command (executed system action receipt)
            • Insight (proactive AI card with actions)
            • Handoff (cross-device transfer prompt)
            • Map preview (when desktop pushes route)
            
                          ↓
                          
                  [Input Row]
              [Mic] [питай PHANTOM…] [Send / Cancel]
              + transient: live mic level pulse, partial transcript
              + on PTT hold: Orb expands +25%, audioLevel drives ring
```

Що додатково з'являється плаваюче:
- **Floating Orb** (always-on overlay): 88dp Орб у нижньому правому куті
  екрана будь-якого додатка. Tap = bring up Stream. Long-press = PTT.
  Drag = переставити. Orbiting rings показують vitals.
- **AOD complication** (lock screen): Орб + state name + 1 pending
  insight short text (e.g. "ти просив нагадати про Олега о 18:00")
- **Live Wallpaper**: breathing radial bloom синхронізований з
  SystemState
- **Home Widget**: Vitals constellation 3×2 grid

Жодних секцій типу "Settings", "Profile", "History", "Vault" як
основних табів. Доступ до них:
- **Settings** — gear у SystemPulseHeader top-right (gear morph,
  не іконка)
- **Vault** — voice command "відкрий vault" + biometric prompt
- **Profile switcher** — long-press на ім'я в SystemPulseHeader

---

## V. Surfaces (живі сцени)

### V.1 Onboarding (60 секунд)

Перший вхід. Жодних 5-screens-of-tutorial. PHANTOM знайомиться:

1. **NameStep** — orb floats у центрі, sunrise-warm bloom за ним.
   Текст глитчно-плавно з'являється: "вітаю · як до тебе звертатись?"
   Input field — повітряна капсула з whisper-thin border. NO LABELS
   above (label INTHE field, fade up on focus).
2. **PinStep** — keypad візуально як constellation, цифри plays
   subtle gold ripple на tap. "Привіт, [Name]" — Familiar's first
   personal address.
3. **CreatingProfile** — Орб дихає глибше (4s breath cycle), ambient
   thrum звук, generating sense. NO percentage spinner. Ratio of
   completion shows у Орб's ring expansion.
4. **PairOffer** — "Pair з PHANTOM OS?" з benefit list (4 bullets):
   живий AI з сенсорів Radxa, vault, тактична карта, голос
   StyleTTS2. Чесно про trade-offs ("без pair — теж працюю, але
   на on-device моделях"). 2 buttons: "Сканувати QR" / "Пізніше".
5. **PairScanning / PairClaiming / PairFailed** — camera viewport з
   live AR overlay (тонкі кутові маркери, що зміщаються до QR-кадра).
   PairClaiming: Орб входить в SENTINEL state (1.3× motion), thrum
   intensifies. PairFailed: coral pulse, retry CTA.
6. **PermissionStep** (Phase 2-P) — KEY MOMENT. Familiar himself
   IS the request, not a system dialog. 240dp Orb центр екрана.
   Pulse pattern. Glass-card hero пояснює *чому* permission потрібно
   ВЛАСНИМ ГОЛОСОМ Familiar (TTS озвучує):
   - "Мені потрібно бачити твій екран — щоб розуміти контекст без
     зайвих запитань."
   - Buttons: "Надати дозвіл" (gold filled) / "Пропустити" (ghost
     outline)
7. **Complete** — entry into Stream, з sense of arrival. NO CONFETTI.
   Sunrise bloom ширшає, Орб входить в FOCUS, перше повідомлення
   Familiar в Stream: "Готовий. Тут і зараз."

Цикл займає 30-60 секунд. Onboarding feel — **посвячення, не tutorial**.

### V.2 Stream (heart)

Найбільш видима поверхня. Завжди живе.

- Header висить у top, transparent з 60% glass alpha. Зменшується
  при scroll вниз (subtle parallax).
- Орб НЕ в центрі Stream'а (на відміну від onboarding). Орб тут —
  плаваюча присутність ВНИЗУ ПРАВОРУЧ як floating action, що пульсує
  з audioLevel.
- StreamItems розгортаються вертикально, новий внизу.
- Spacing — **organic, не grid**. Bubbles мають різну ширину (USER
  60-75% width, right-aligned; ASSISTANT 75-90% width, left). Notices
  — full-width thin bars з coral border.
- Widgets (timer/map/chart) — full-width Card glass з accentBorder.
- Insight cards (proactive) — distinct visual: glass + thin animated
  amber border + Familiar avatar at top-left + dismissible by swipe
  right.
- Empty state — Familiar's welcome text у Hero glass card (`Phase 0`
  current behaviour) — це OK, але має морфувати залежно від часу
  доби та state.

### V.3 Familiar / Orb (the soul)

Орб — це не «декорація». Це візуальна репрезентація AI presence.

3 рендеринги (vary by API level):
- **API 24-30**: Compose-rendered (`OrbView.kt` current). Radial gradient
  з 3 layers: core (gold), corona (warm orange), aura (faint amber).
  Audio-reactive: scale + brightness pulse on audioLevel.
- **API 31+**: + RenderEffect blur halo
- **API 33+, GPU**: Filament 3D render — ambient occlusion sphere,
  subsurface scattering, deformable membrane (`FilamentOrbView.kt`).
  Reacts to:
  - audioLevel (membrane wobble)
  - emotion (color shift: joy=warmer, sad=cooler)
  - state (motion scale, rotation speed)
  - touch (responds to tap by ripple)

Орб має 6 «настроїв» (per SystemState):
- **FOCUS**: still center, slow steady breath
- **DIALOGUE**: outward expansion at words, contracts on listening
- **SENTINEL**: faint gold spike anomalies on edges, scanning motion
- **GHOST**: faded gray, low opacity, almost invisible
- **SHADOW**: dim warm, slow motion, energy-saving
- **DREAM**: soft, far-away, drifting horizontal

Touch interactions:
- Single tap: voice greeting (TTS "так?") + Stream focus
- Long-press: PTT mode
- Double-tap: Open settings
- Drag: Move floating instance
- Pinch out: Open vault prompt (with biometric)

### V.4 SystemPulseHeader

Top-of-stream, persistent. Compact form (40dp height) by default.

```
[•••• small dots]   ВАРТА   [⚙]
                    17хв
вихідний — пасивне спостереження
```

- 4 dots = AI / VOICE / SENSOR / LINK. Color = state. Brightness =
  intensity. OFF = gray, IDLE = warm, LIVE = gold pulse.
- "ВАРТА" = state name in 14sp ALL CAPS letter-spacing 0.4em
- "17хв" = dwell time у поточному стані
- "вихідний — пасивне спостереження" = narrative composed by
  SystemPulseRepository з ContextEngine rationale
- gear icon (top-right) → Settings sheet

Tap on header → **expanded panel**:
- Full vitals readout (BPM, breath, stress, motion class)
- State transition history (last 12 hours)
- Active ContextEngine rationale list
- Manual state override (для тестування)

### V.5 Settings dialog

Bottom sheet з 4 sections:

1. **AI Brain** — Gemini key, local Gemma model (download status,
   manage), routing strategy (CloudFirst / LocalFirst / Adaptive)
2. **Voice** — TTS engine pick, language, voice clone status
3. **Theme** — sunrise-warm / amber-night / auto by time of day
4. **Connection** — paired phantom status, unpair, manual pair
   (QR / paste JSON), latest WS RTT, channel filter (debug only)
5. **Permissions** — re-trigger AI-narrated permission flow
6. **Profile** — current name, switch profile, sign-out

Не grid-of-toggles. **Conversational**. Кожна секція має
текстовий peek повного стану, не просто toggle ("Gemini підключено
· 23 запити сьогодні · ключ закінчується ...3afb").

### V.6 Pair Flow

Already covered in V.1. Two paths:
- **Camera scan**: live AR with QR detection, gold lock-on animation
  (3-corner brackets snap to QR with fluid spring)
- **JSON paste fallback**: if camera fails or QR is text-only

Animation on success: Орб expands, sunrise bloom widens, sound of
distant bell + thrum, then glass card fades in: "Підключено · phantom"
з RTT та channel count.

### V.7 Vault Gate

Privacy-sensitive surface. Triggered by voice "відкрий vault" or
gesture (pinch-out на Орб):

- Full-screen GHOST-style background (warm-but-dim)
- BiometricPrompt overlay
- Vault entries list — glass cards with fingerprint icon, password
  preview redacted (`••••3a7f`), tap to copy + 6s clipboard timer
- Long-press на entry → reveal value with another biometric

GHOST visual cues prominent: state pill morphs to GHOST color, Орб
opacity drops, header narrative says «vault режим — слідів не
залишаю».

### V.8 Map Widget

Compose Card containing MapLibre native render. Not full-screen,
inline в Stream. Tap to expand to full-screen.

- AR overlay on full-screen mode (camera + map fusion, AR markers
  for points-of-interest from server-pushed `map/add_marker`)
- Threat layers (drug-zones, dangerous nodes from sensor mesh, etc.)
- Phantom-typed locations marked with Familiar pulse
- Routes drawn as flowing gold lines (not blue Material default)

### V.9 Handoff Bottom Sheet

Triggered by `handoffEvents.created`. Slides up:

- Top: Familiar avatar + "phantom OS передає тобі: [kind]"
- Middle: contextual preview (mini map for `map_route`, card preview
  for `vault_card`, text preview for `chat_thread`)
- Bottom: 3 buttons — "Прийняти" (gold) / "Відхилити" (ghost outline)
  / "Пізніше" (small text)

### V.10 Always-Floating Orb

System overlay window (SYSTEM_ALERT_WINDOW). Visible над всіма
додатками. 88dp default size.

- Pulses on incoming agent.stream events (subtle gold blink)
- Voice halo on AI speaking
- Tap → bring Stream above current app
- Long-press → PTT
- Drag → reposition (snaps to edges)
- Coral border на critical alert

User can disable in Settings.

### V.11 Lock Screen Complication / AOD

- Orb mini (40dp) + state name (10sp) + dwell ("3хв")
- Optional: 1 pending insight short text rotated through every 30s
- Tap → unlock then Stream

### V.12 Live Wallpaper

Already implemented (`PhantomWallpaperService.kt`). Polish:
- Breathing bloom synced to current SystemState color
- 3 wandering "sparks" — orbital embers
- Subtle particle drift (very low FPS — 30 max)
- Disable in low-battery mode automatically

### V.13 Vitals Widget (Home Screen)

Already implemented (`VitalsWidgetProvider.kt`). Refinement:
- Constellation of 4 dots (AI/VOICE/SENSOR/LINK) instead of plain text
- State name in same typography as header
- Battery + connection status compact
- Tap PTT icon → opens app + starts STT

### V.14 Wear OS face/complication

Not implemented yet. Brief:
- Watchface dial with Орб at 12 o'clock position
- Outer ring shows breath pattern (inhale 4s / hold 2s / exhale 4s)
- Complications show vitals
- Voice-to-Stream PTT via watch crown long-press
- Haptic notification mirror

---

## VI. State-Driven Visual Language

Кожна поверхня морфується в залежності від `SystemState`. Це не
just color theme — це full sensory mode shift.

| State | Visual | Motion | Sound | Haptic | Feel |
|---|---|---|---|---|---|
| FOCUS | warm gold accent, full opacity | 1.0× standard | normal alerts | normal | working, calm |
| DIALOGUE | gold + slight glow, animated | 1.1× | crystalline | wave-pulse | conversational, present |
| SENTINEL | gold + amber edge anomalies | 1.3× scan | softer notice | quick-double | watchful, alert |
| GHOST | gray-flat, opacity 90% | 0.8× | SILENT | NONE | invisible, untraceable |
| SHADOW | warm-dim, opacity 92% | 0.6× | rare | rare | low-power, conservative |
| DREAM | soft warm, opacity 75% | 0.4× | distant | gentle long-pulse | passive, sleeping |

Transition between states — **2-second crossfade with state-color blur**.
Орб morphs first, then accent color washes outward like wind through
all surfaces.

---

## VII. Cross-Device Choreography (the magical part)

Phantom presence has **continuity**. Walking between devices доєднує
state seamlessly.

Сцени:
1. **Walk-from-desktop**: User got up from phantom-os, walks to kitchen
   with phone in pocket. Воїз memo continues TTS-streaming on phone.
   Phone's Орб inherits the dialogue motion of desktop's Орб.
2. **Cross-screen presentation**: User opens slides on phantom-os.
   Phone automatically shows speaker notes (without prompting). Phone
   detects orientation = horizontal → switches to remote-clicker mode.
3. **Hand-off action**: Phantom on desktop generated route. Push
   notification on phone with handoff sheet ("прийняти?"). Accept →
   route now lives on phone for navigation.
4. **Smart delegation prompt**: User asks complex multi-step task on
   phone. Phone's intelligence sniffs DAG, proposes: "це 8 хв на
   phantom-os або 3 год на телефоні. Делегувати?". Yes → phone shows
   real-time progress mirror; phantom-os does the work.
5. **Spatial UI**: Two phantom devices in same room (UWB ranging).
   Voice "покажи це на більшому екрані" → system finds the desktop,
   pushes to it.
6. **Group co-presence**: Two trusted operators in same physical
   space, both phantom-paired. Their phones display subtle proximity
   pulse on each other's Орб (consensual indicator).

These choreographies must NEVER feel jarring. The handoff is implicit,
the message «це переносу з phantom-os» — **fading whisper**, не
notification spam.

---

## VIII. Sensing-Driven UI

Phone's sensory inputs morph the UI organically. UI «звучить» залежно
від світу:
- High ambient noise → font weight increases for legibility
- Fast walking detected → larger touch targets, readable from glance
- Driving detected → minimal interaction, voice-only mode
- Low light → night theme auto-applies
- Operator stressed (HRV) → calmer color palette, fewer notifications
- Operator excited → more action options surfaced
- Phantom-paired desktop in same room → certain UI elements move there
  (e.g. detailed map preview), phone keeps summary

---

## IX. Edge Cases & Failure Modes

PHANTOM ніколи не «помилка обʼєкта». Кожне fail-state має голос:
- **No network** → SystemPulse LINK dot dims. Header narrative:
  «без звʼязку — працюю на тому що знаю». No banner across screen.
  No "Try again" yellow toast.
- **AI not configured** → Insight card "ключ Gemini або локальна
  модель не активна — налаштувати?" з дотик-через-карту flow до
  Settings.AI Brain.
- **No PHANTOM-OS paired** → SystemPulse дrop "PHANTOM · СОЛО" (вже
  є). Не "ti not paired" — а "ти соло". Self-sufficient.
- **Permissions denied** → Familiar narrates: "ти не дав мені
  бачити екран — я працюватиму, але без контекстних дій. Завжди
  можеш повернутися." Not nag.
- **Battery critical** → SHADOW state auto-engages. Орб опускається
  на 60% size, breathes slowly. Header: "режим тіні — економлю
  ресурс."
- **GHOST mode** → Орб practically invisible (gray-flat, 30% opacity).
  No Stream items. No sound. Just header showing "ПРИМАРА" and dwell.
- **Onboarding interrupted** → State preserves. Re-launch resumes
  exactly where stopped, with continuity message ("повертаймось").

---

## X. Accessibility & Inclusion

- **Screen reader**: every Composable має contentDescription, the
  Familiar speaks by default
- **Voice-only**: full app navigable through TTS prompts + voice
  commands, без touch
- **Low-vision**: high-contrast mode toggles via Settings + auto-detect
  on system AccessibilityService
- **Motor impairment**: long-press tolerance configurable; gesture
  alternatives для всього (tap-only fallback)
- **Color-blind**: state distinction relies on SHAPE (Орб motion +
  state name text), не тільки color
- **Deaf/HOH**: visual notifications + haptic patterns first-class.
  TTS optional.

---

## XI. Localization

Ukrainian primary — це не "i18n key file". Це core design.

- **Personality в українській**: Familiar говорить різнонюансно,
  "ти" не "ви". Тон — теплий, інтелектуальний, ніколи не клерикальний.
- **Технічні терміни**: інколи лишаємо ENG ("Stream", "Vault",
  "Familiar", "Pair") — вони працюють як власні імена.
- **Помилки** — українською, людською мовою, без error codes:
  "не вдалося — попробуй ще раз" not "Error 502: Bad Gateway".
- **Дата/час**: 24-hour, "сьогодні · 18:34", "14 травня".
- **Числа**: пробіл як thousands separator («23 451»), не кома.
- **Друга мова** (English) — fallback, не primary. UI elements
  switch only on system locale not Ukrainian.

---

## XII. What it MUST NEVER look like

- Material Design 3 / iOS standard / Material You as defaults
- Tab bar at bottom with 5 icons
- Toggles on every settings item
- Cards with sharp corners and stark white background
- Blue or purple as primary accent
- Sans-serif default Roboto
- "Sign in with Google" / cloud-first architecture surfacing
- Spinner / progress bars / percentages — replaced by Орб breathing
- Notifications with title + body + 2 buttons + cancel — replaced by
  Familiar's voice or insight cards
- Settings page with 30 toggles
- Generic "Welcome to PHANTOM" splash screen with logo
- "5/5 stars" prompts
- Empty states with cartoon illustrations
- Cookie banners or any compliance modal
- Dark mode that's just inverted light mode

---

## XIII. Component Library Inventory (the alphabet)

| Component | Variants | Primary Use |
|---|---|---|
| GlassCard | Subtle / Panel / Card / Hero | All content containers |
| OrbView | API-tier (Compose / RenderEffect / Filament) | Familiar manifestation |
| StatePill | 6 states | Current FSM indicator |
| VitalsRow | 4-dot constellation | SystemPulse signals |
| PttButton | Idle / Capturing / Released | Voice input |
| WidgetCard | Timer / Weather / Map / Chart / Question / TaskList / Vitals | Generative UI from AI |
| InsightCard | Proactive / Pending / Fired | Agent.stream surface |
| HandoffSheet | Created / Accepted / Rejected | Cross-device transfer |
| ChatBubble | USER / ASSISTANT / SYSTEM | Stream text items |
| NoticeBar | Info / Warning / Error | Inline status |
| BiometricGate | FaceID / Fingerprint / PIN | Vault unlock |
| QRSheet | Scanner / Paster | Pair flow |
| PermissionCard | Familiar narration | Phase 2-P |
| ContextHint | Fold / Translate / Save | Screen content actions |
| SystemPulseHeader | Compact / Expanded | Top of Stream |
| FloatingOrb | 88dp / 120dp / 160dp | Always-on overlay |
| AODMini | Lock screen | Watchface mini Орб |
| WallpaperEngine | Sunrise / Amber / Custom | Live wallpaper |
| VitalsWidget | 2x2 / 3x2 / 4x2 | Home screen widget |

---

## XIV. Animation Catalog

Every animation has a name, purpose, duration, easing.

| Name | Purpose | Duration | Easing |
|---|---|---|---|
| breathe.standard | Background bloom / Орб pulse | 8s loop | sin in/out |
| breathe.deep | Loading indicator (replaces spinner) | 4s breath | spring(180, 0.75) |
| pulse.audio | Орб scale on audioLevel | reactive | linear (raw input) |
| ripple.tap | Action confirmation | 800ms | ease-out |
| glow.ambient | Edge glow on accentBorder | 12s loop | sin in/out |
| wash.state | State transition color spread | 2000ms | ease-in-out |
| spike.alert | SENTINEL anomaly indicator | 250ms | ease-out (single) |
| morph.familiar | Орб state mood shift | 3000ms | spring(120, 0.6) |
| fade.ghost | GHOST mode entry | 1500ms | linear |
| crossfade.stream | Stream item enter | 400ms | ease-out |
| spring.handoff | Bottom sheet entry | 350ms | spring(380, 0.85) |
| brackets.qr | AR corner markers snap to QR | 600ms | ease-out |
| flow.route | Map gold-line route drawing | 1200ms per 100m | ease-in-out |
| bloom.success | Sunrise bloom widening on success | 1200ms | ease-out |
| pulse.coral | Error indication | 600ms | ease-out (single) |

Forbidden:
- Material default ripple
- Linear tween
- Slide-from-right page transitions
- Stretching scrolls / overscroll glow
- Default spinner / circle loader

---

## XV. Sound Catalog

Each named, sourced from custom-recorded library. No off-the-shelf
sound effects.

| Name | Trigger | Duration |
|---|---|---|
| chime.bell | Pair success | 1.5s |
| thrum.familiar | Familiar speaking | continuous while TTS |
| crystal.notice | Soft notification | 400ms |
| spike.alert | Urgent alert | 600ms |
| breath.ambient | Background presence | very subtle, looped |
| ripple.tap | Action confirmation | 200ms |
| wave.haptic-mirror | Companion to vibration pattern | matched |
| silence.ghost | GHOST mode | infinity |

---

## XVI. North-Star Inspirations

Не "draw from Material guidelines". Draw from these:

- **HER** (Spike Jonze, 2013) — Samantha's warmth, Joaquin's
  earpiece intimacy, the OS as person
- **Westworld S1** — Maeve's HUD, behavior model surface,
  consciousness-as-software
- **Iron Man HUD** — but bent toward CALM, not aggressive
- **Apple's Vision Pro spatial UI** — eye-tracking + co-presence
- **Sufjan Stevens "Carrie & Lowell"** — emotional resonance, low
  saturation, intimate not loud
- **Roman Polanski "The Tenant"** — texture of space, warm-but-uneasy
- **Studio Ghibli's "Whisper of the Heart"** — slow ritualism,
  craft over flash
- **Linux tilers** — density without clutter
- **Blade Runner 2049** — gold over bleak, large-scale calm
- **Digital tarot decks** (Druid Tarot, Modern Witch) — symbolism over
  literal-illustration

NOT the inspirations:
- Discord / Slack — chat-app default
- Material You / iOS 17 — system defaults
- Mid-tier productivity apps — Notion, Obsidian, Trello
- Crypto-bro neon dashboards
- Generic "AI chat" interfaces (ChatGPT, Claude desktop)

---

## XVII. The Core Test (when in doubt)

Питай: «Чи це звучить як **присутність**, або як **dashboard**?»

Якщо dashboard — переробити.

Якщо присутність — це Phantom.

---

## XVIII. Ribbon for design AI (handoff)

Якщо ти AI, що читаєш цей brief для рендерингу UI:

1. Не починай з "let me suggest layouts" — починай з ВІДЧУТТЯ.
   Що ти відчуваєш коли уявляєш Phantom? Warm dusk у Lhasa, glass
   ribbon over warm gold, faint thrum.
2. Кожна поверхня має дихати. Жодного static frame.
3. Орб — це КОЖНОЇ сцени. Якщо Орба нема — це не Phantom.
4. Українською primary, з технічним глосарієм англійською.
5. Темно-тепла естетика — НЕ материал, НЕ flat, НЕ iOS.
6. SHAPE > color у diferentiating state. Color additionally amplifies.
7. Silence > sound. Notifications must EARN their right to speak.
8. Operator owns the surface — UI bows to him, not vice versa.

Generate code that makes you feel this. Then iterate until you do.
