# PHANTOM Companion — Long-Horizon Vision

> Жива дорожня карта **після** companion-v2-phase-0 (2026-05-09).
> Phase 0 закрив wire-contract: телефон отримує всі сервер-канали,
> але UI subscriber'ів немає. Цей документ — план як перетворити
> «pipe alive, surface empty» на «магічна екосистема».
>
> Структура: 10 фаз, кожна = ~1 квартал концентрованої роботи.
> Кожен пункт має мітку складності: ⚡ (1-2 тижні), 🔥 (місяць+),
> 🌋 (квартал+), 🌌 (research-grade, рік+).
>
> **Принцип:** телефон і phantom-os кожен окремо — потужні. Разом —
> магія. Якщо одного нема — інший не деградує до іграшки.

---

## Tier 5 — Phase 1: Make the live wire visible

Pipeline вже доставляє події. Бракує UI consumer'ів.

| Phase | Surface | Channel/Flow | Difficulty |
|---|---|---|---|
| **1-A Proactive Cards** | inline insight cards над input у Stream | `agentEvents.proactive.cycle` / `pending_action` | ⚡ |
| **1-B State Morph** | `LocalStateAccent` + Orb breathing мутується real-time | `agentEvents.emotion.updated` | ⚡ |
| **1-C Proactive Chat** | server-initiated AI повідомлення з'являються в Stream як ASSISTANT | `chatEvents.message.proactive` | ⚡ |
| **1-D Full Context Pulse** | SystemPulseHeader signals тягне motion/mic/gps з phantom-os | `sensor/snapshot` ContextSnapshot | 🔥 |
| **1-E Lock Vault Verb** | remote lock vault від desktop → клемп vault, навігація з sensitive screens | `controlVerbs.lock_vault` | ⚡ |
| **1-F Handoff Sheet** | bottom-sheet «прийняти/відхилити» коли desktop передає action | `handoffEvents.created` | 🔥 |
| **1-G MapWidget Live** | повноцінний MapLibre, push-from-desktop routes | `mapEvents` | 🔥 |
| **1-H Ping-Pong Latency** | `companion_control.ping` → RTT roundtrip графік | `controlVerbs.ping` | ⚡ |

**Exit criterion:** оператор бачить, як phantom-os «думає вголос» прямо у телефоні. Telepathy proxy.

---

## Tier 6 — Phase 2: Multi-modal sensory awareness

Телефон як орган чуття. Все on-device, привертати хмару тільки коли локально малопотужно.

### 2-A Vision (camera-first)

| Feature | Tech | Difficulty |
|---|---|---|
| Object/scene class | MediaPipe ObjectDetection + ImageClassifier (вже Phase 4-A) | ⚡ |
| OCR-anywhere | ML Kit Text + custom UI gesture: 2-finger long-press → live OCR overlay будь-де (камера/screenshot/screen recording) | 🔥 |
| Face ID + face memory | ML Kit FaceDetection + FaceNet embedding → sealed in SQLCipher vault, named & associated with profile entries | 🔥 |
| Emotion read | MediaPipe FaceMesh → 7-emotion classifier (fear/joy/anger/sad/disgust/surprise/neutral); feeds into `agentEvents.emotion.detected` | 🔥 |
| Gesture vocab | MediaPipe Hands → 30-gesture set (open palm = "wait", swipe = "next slide", thumbs up = "ack"); commands AI без мови | 🌋 |
| Whole-body posture | MediaPipe Pose → standing/sitting/lying/walking/falling-detection; falls trigger HelpProtocol | 🌋 |
| AR threat overlay | ARCore + computer vision class on what camera sees → labels (fire, smoke, weapon, ice patch, missing manhole) | 🌌 |

### 2-B Audio (mic-first)

| Feature | Tech | Difficulty |
|---|---|---|
| Ambient sound class | YAMNet on-device → `phone-ringing`, `glass-break`, `dog-bark`, `siren`, `infant-cry` → notification triage | 🔥 |
| Music recognition | NNDescent + on-device fingerprint DB (top-50k tracks) — Shazam без хмари | 🌋 |
| Voiceprint speaker ID | ECAPA-TDNN → recognizes voice of trusted contacts; "Anya is here" auto-trigger | 🌋 |
| Always-on transcription | Whisper streaming мовно-локально, циклічний 5-min buffer | 🌋 |
| Anomaly detector | per-user baseline of ambient sound profile → notify if new pattern (e.g. door-knock at 3am) | 🌌 |

### 2-C Screen content (Accessibility-first)

| Feature | Tech | Difficulty |
|---|---|---|
| Screen text → context | Phase 3-A (вже частково). Розширити до: вибране слово → дефініція inline | 🔥 |
| App-aware shortcuts | watch active app → suggest contextual actions ("ти зараз у Maps — додати в маршрут?") | 🔥 |
| Visual fold/clip | screenshot any region via gesture → ChatGPT-style "що це?" | 🔥 |
| Auto-screenshot rare moments | when emotion-detected = surprise/joy and camera/screen has unusual content → silent log | 🌋 |

### 2-D Biometric (sensor-first)

| Feature | Tech | Difficulty |
|---|---|---|
| HRV from camera | photoplethysmography on rear camera → 30-sec stress reading | 🔥 |
| Sleep cycles from accel | overnight motion → REM/deep estimates | 🔥 |
| Stress prediction | HRV + voice-pitch + screen-time pattern → 6-week burnout forecast | 🌋 |
| Hydration nag | day-long body movement profile → suggest water | ⚡ |

**Exit criterion:** телефон дивиться, чує, нюхає та відчуває. Кожне відчуття — окремий канал у локальний agent.

---

## Tier 7 — Phase 3: Memory beyond phantom-os

Не тільки "remember everything"; а *retrieve right thing fast*.

### 3-A Episodic Memory
- Кожен моментаспект (sensor + screen + audio + state) → vector embedding → sealed
- Query: "що я робив у понеділок ввечері?" → reconstruct snapshot timeline
- Privacy: GHOST-mode events не індексуються; biometric-locked retrieval

### 3-B Semantic Knowledge Graph
- Nodes: people, places, projects, ideas, files
- Edges: "Анна — мама", "Стартап X — наш разом з Б", "Книга Y — рекомендована Анею"
- Queriable through natural language: "хто рекомендував мені останню книгу?"
- Auto-built from chats, calendar, screen content, voice memos

### 3-C Procedural Memory
- Learns repeating workflows: "коли я готую каву, я завжди потім читаю Linear для 5 хв"
- Phantom proposes the next step in real time
- Can REPLAY a workflow on demand: "зроби мою ранкову рутину" → opens apps in order, reads news, brews coffee timer

### 3-D Working Memory (per-task)
- Multi-task: 5 active "threads" with sub-context
- Auto-summarize on switch: "ти повернувся до Project X. Останнє було: дизайн API endpoint."
- Forget gracefully: задачі без активності > 30 днів автоматично архівуються (не видаляються)

### 3-E Decision Diary
- Кожне велике рішення (купити X, відмовити Y) → пишеться разом з причинами
- Через місяць/квартал/рік phantom повертається: «У січні ти вирішив X тому що Z. Z тепер змінилось — переглянути?»
- Це не «памʼять про факти», а памʼять про *причини*.

### 3-F Forgetting & Privacy
- Per-event TTL (legal docs forever, casual chats — 7 days unless flagged)
- "Forget this conversation" gesture
- GHOST events не лишають слідів навіть у audit log

**Складність:** 3-A,B,D — 🌋. 3-C,E — 🌌. 3-F — 🔥.

---

## Tier 8 — Phase 4: Multi-device task orchestration

Не «phone vs desktop» — `n` довірених вузлів, координовані як один organism.

### 4-A Capability Manifest
- Кожен пристрій публікує: GPU? VRAM? Battery? Network? Camera? Display? Audio?
- Шифрований gossip protocol по Phantom Mesh (`feature-godmode/p2p`)
- Любий agent дивиться: «Hey, цей task потребує 8GB VRAM + GPU + 5min — хто з вузлів?»

### 4-B Task Graph Solver
- LLM intent → DAG of subtasks
- Кожен node tagged with required capability
- Solver picks the best vertex per node, with cost function (latency, battery, user-attention-impact)
- Real-time re-solve if a node disappears (battery dies, user walks away)

### 4-C Smart Delegation UX
- "Намалюй презентацію + згенеруй 30 слайдів + принеси цитати з Slack — це 8 хв на твоєму phantom-os, або 3 год на телефоні. Делегувати?" → Yes/No з прогрес-баром на телефоні
- Якщо обрав phantom-os: phone скидає важкі моделі з RAM (auto-unload) на час задачі — звільняє ~2GB RAM
- Якщо локально на телефоні і операція > 30s: попередження «телефон краще не чіпай N хв»
- Готовий результат зʼявляється в Stream + на phantom-os одночасно

### 4-D Bidirectional Reporting
- Result, intermediate progress, errors → push на ВСІ trusted nodes
- Якщо з phantom-os почали + завершили на телефоні → чат-thread зберігає всю історію без розривів
- Continuation: "що ти писав 5 хв тому коли я виходив?" — phantom recap

### 4-E Cross-device clipboard
- Universal Clipboard через Phantom Mesh: copy на phone → paste на phantom-os без iCloud/cloud
- AR-clipboard: copy на phone → see floating object on AR glasses → paste у physical space (Phase 9)

### 4-F Battery-aware mesh
- Worst-battery node gets minimal compute load
- Charging device offers itself as primary
- "Friend's phone offered 30% of its compute for 2h" — opt-in

**Складність:** 4-A,C,D — 🌋. 4-B — 🌌. 4-E,F — 🔥.

---

## Tier 9 — Phase 5: Spatial awareness & co-rendering

Девайси знають де вони у просторі один щодо одного. Розгортають інформацію по поверхнях.

### 5-A UWB Ranging
- Pixel/Galaxy/iPhone Ultra-Wideband chips → ±10cm distance + bearing
- "Phantom-os ліворуч 1.2m" / "Friend phone позаду 5m"
- Realtime room map of trusted devices

### 5-B Cross-screen Layout Engine
- "Покажи презентацію" → автоматично:
  - Спікер-нотатки на телефоні
  - Слайди на phantom-os display
  - Лазер-вказівник на AR-окулярах (якщо є)
- Drag-drop між screen-ями by gesture/voice

### 5-C Proximity Drop (вже частково)
- BLE/UWB peer discovery
- One-shot share files/contacts/passwords/locations без хмари
- "Throw" gesture (motion-detected swipe in direction of recipient)

### 5-D Spatial UI
- В кімнаті 3 active phantom devices → UI хочеться поділити: chats тут, map там, vault на закритому пристрої
- AR overlay: "vault icon floating над phantom-os" — пристрій-власник підсвічений
- Voice: «Покажи це на найбільшому екрані» — system автоматично знаходить

### 5-E Walk-Continuation
- Розпочав конверсацію на phantom-os, виходить з кімнати → телефон підхоплює saved state, голос продовжує без розриву
- "Walking phantom" mode: TTS читає лист поки йдеш, маршрут на годиннику

### 5-F Group Coordination
- 3 друзів у кафе, всі phantom-користувачі → spatial cluster
- Один питає phantom: «де ми зустрінемось завтра?» — рішення проголошено всім трьом
- Cooperative AR boardgame: phantom малює gameboard на столі, всі телефони рендерять свою сторону

**Складність:** 5-A,C — 🔥. 5-B,D,E — 🌋. 5-F — 🌌.

---

## Tier 10 — Phase 6: Real agent capabilities

Не «асистент який пише emails». А агент який *керує* твоїм світом.

### 6-A Calendar Omniscience
- Знає твої routines + preferences + biometric energy curve
- "Зустріч з Олегом — пятниця 10:30 чи 14:00?" → "10:30 — у тебе високий ранковий focus за останні 6 тижнів"
- Конфлікти: "ти просив тренування о 18, але дощ — пересунути?"

### 6-B Email/Message Triage
- Per-channel ML classifier: важливе → notification, не важливе → daily digest, спам → silent
- Auto-draft replies для recurring patterns
- "Anya написала про каву — відповісти 'так' моїм стилем?"

### 6-C Code Watch
- Phantom дивиться на screen під час кодування
- Suggests refactor → у floating tooltip («ти повторюєш цей паттерн втретє — extract function?»)
- Pair-programming voice: запитуєш "чому це не компілюється?" → reading screen + говорить причину

### 6-D Smart Home Omniprotocol
- Один агент — всі pidloh systems (Matter, Z-Wave, Zigbee, ESP-Home, Apple Home, Google, Tuya)
- Voice: «зроби кімнату затишною» → temperature -1°C, lights warm dim, music softer
- Learn routines automatically (fail-soft on changes)

### 6-E Negotiation Proxy
- Купити квиток / забронювати готель / домовитись про зустріч → phantom веде переговори у твоєму стилі через email/messenger
- Reports cost/options + asks before commit
- Можна делегувати full autonomy для покупок < $50

### 6-F Social Presence
- Знає (з consent) хто з phantom-користувачів поряд
- "Олег у кафе через 2 будинки" — пропонує зустрітися
- Privacy-first: всі сигнали — opt-in per relationship, з гранулярним control

**Складність:** 6-A,B — 🔥. 6-C,D — 🌋. 6-E,F — 🌌.

---

## Tier 11 — Phase 7: Personalization at the cellular level

### 7-A Voice Clone
- StyleTTS2 (вже scaffolded) → 30 sec recording → персональний TTS
- Phantom озвучує emails твоїм голосом, читає news твоїм стилем

### 7-B Style Transfer Writer
- LLM fine-tune (Federated Learning Hook вже Phase 7-B) → пише у твоєму стилі
- Drafts emails / messages / blog posts that sound *exactly like you*
- Можна перемикати regs: "написати формально" / "по-дружньому" / "як я писав 3 роки тому"

### 7-C Mood-Aware UI
- Detected emotion + biometric state → UI density / colors / animation speed
- Stress detected → reduce notifications, calm color palette, slower animation
- Excited detected → more action options visible, brighter accent

### 7-D Decision Style Memory
- Phantom помічає: "ти зазвичай обираєш cheaper option for travel" / "ти завжди sleeps on important decisions for 24h"
- При нових рішеннях applies these defaults

### 7-E Per-context Persona
- Робота: формальний, цитує дані
- Сімʼя: теплий, м'який
- Друзі: гумор, slang
- Auto-detected from current calendar/contacts/location

**Складність:** 7-A — ⚡ (scaffolding done). 7-B — 🌋. 7-C,D,E — 🔥.

---

## Tier 12 — Phase 8: Trust, security, resilience

### 8-A Family Hive (Phase 5-F вже scaffolded)
- Multi-user vault з role-based access
- Operator/Companion/Guest/Guardian
- Time-limited grants ("Anya, ти можеш бачити мою локацію наступні 4 год")

### 8-B Vault Inheritance (Phase 7-F scaffolded)
- Multi-sig dead-man-switch
- "якщо я не logged-in 30 днів + 3 trustees confirm → unlock vault to my heir"

### 8-C Tamper Detection
- Hardware sensors: accelerometer drop pattern, magnetic field anomaly, RFID nearby
- "Phone show signs of being copied to another device" → emergency vault clamp

### 8-D Co-witness Verification
- Critical actions (large transfer, vault export, key change) require co-confirmation з 2nd trusted device
- Підтвердження через voice + face + biometric + location-match

### 8-E GHOST-mode Plus
- One gesture → phantom не пише нічого 30 min + auto-deletes any received messages + camera/mic disabled hard-level
- Resilient to forensics: random-fill памʼять, не залишає evidence в swap

### 8-F Mesh Resilience
- 2+ trusted devices = full functionality offline
- Internet down → mesh keeps state synced via Bluetooth/UWB/WiFi-Direct
- Re-sync на reconnect через Phantom Relay

**Складність:** 8-A,B — done/scaffolding. 8-C,D — 🌋. 8-E,F — 🔥.

---

## Tier 13 — Phase 9: Frontier (research-grade magic)

Не «зробимо за рік». Mark them as the North Star.

### 9-A Telepathic Priming
- AR glasses + EEG headband (Muse, Neurosity) → focus state input
- Phantom тихенько surface-ить інформацію саме коли ти стресуєш чи готовий
- Voice subvocalization recognition (Alter Ego за MIT) — мовчазне керування

### 9-B Time-Shifted Help
- "Future you" leaves notes for "past you"
- Видно at exact moment of relevance: запам'ятав сказати про tax — phantom нагадує saме перед розмовою з accountant

### 9-C Companion-as-Witness
- Legal-grade event recording (cryptographic timestamps + sensor + audio + video)
- Used in incidents (accidents, harassment) — auto-shares with trusted lawyer if "panic" gesture detected
- Privacy: only kicks in via explicit gesture; can't be silently abused

### 9-D Dream Journal
- Phone detects sleep state + REM timing
- Wake-up + 1 sec → captures voice memo before dream fades
- AI clusters recurring dream themes → potential mental health insight

### 9-E Voice-Signature Pairing
- Pair another device by talking near it («pair this device, code 8421») — voiceprint identifies you
- No QR/PIN needed for trusted-circle members

### 9-F Cooperative Gaming
- Phantom plays multiplayer games with you (chess, Werewolf) — adjusts difficulty by your skill
- Two phantoms collaborate to play a co-op game even if you're alone

### 9-G Spatial Code Editor
- Codebase rendered as 3D city in AR
- Modules = districts, classes = buildings, methods = rooms
- Move through code by walking; rebuild by gesture

### 9-H Memory Palace
- VR/AR room you can re-enter
- Place memories in physical space (a book on virtual shelf, photo on virtual wall)
- Walk through to retrieve

### 9-I Negotiation Court
- Two phantoms (yours + theirs) negotiate on behalf of operators in real-time
- Both surface only the agreed terms after, with full transcript available

### 9-J Phantom Swarms
- 100s of phantom devices coordinate as one super-intelligence for collective task
- Crowd-sourced AI: every phantom contributes a slice of computation to a global problem

### 9-K Ambient Health Mesh
- Phantom mesh learns *your* normal: gait, voice, sleep, mood, ambient temp preference
- Detects anomalies *before* you notice — early Parkinson tremor, depression onset, blood-pressure drift
- All on-device, never to cloud

**Складність:** усі 🌌, окрім 9-E (🌋) та 9-F (🔥).

---

## Принципи що не змінюються

1. **On-device first.** Cloud — fallback. Privacy by architecture.
2. **Each device powerful alone.** Никаких single-point-of-failure.
3. **Mesh is magic.** Coordinated devices > sum of parts.
4. **Operator owns the keys.** No cloud-side master key. Vault inheritance is multi-sig.
5. **GHOST is sacred.** When user wants invisibility — system goes invisible-by-construction.
6. **Forgetting is feature.** Memory has TTL by category. "Right to be forgotten" is on-device.
7. **Trust gradient.** Operator > Family Hive > Trusted Circle > Strangers. Each tier sees less.
8. **Bidirectional.** Mobile and desktop never have asymmetric relationship — always peers.

---

## Послідовність розгортання

```
Phase 1 (зараз) ─────► UI surfaces для вже-наявних channels
       │
       ▼
Phase 2 ──────────────► Multi-modal sensory (vision/audio/biometric)
       │
       ▼
Phase 3 ──────────────► Memory layers (episodic/semantic/procedural)
       │
       ▼
Phase 4 ──────────────► Multi-device orchestration (task graph)
       │
       ▼
Phase 5 ──────────────► Spatial computing (UWB ranging, co-rendering)
       │
       ▼
Phase 6 ──────────────► Agent capabilities (calendar/code/home/social)
       │
       ▼
Phase 7 ──────────────► Personalization (voice/style/mood)
       │
       ▼
Phase 8 ──────────────► Trust/resilience hardening
       │
       ▼
Phase 9 ──────────────► Frontier research (telepathic, dream, swarm)
```

Кожна фаза — `1 атомарний commit на phase-ID`, як було в Phase-0.
Не починаємо наступну поки попередня не shipped зі зеленим build.
Reboot/install device test після кожної phase у Tier 5-7.

---

## Що читати перед роботою (referencing)

| Топік | Документ |
|---|---|
| Wire contract (server↔phone) | `docs/COMPANION_CONTRACT.md` |
| E2E manual checklist | `docs/E2E_MANUAL_CHECKLIST.md` |
| Closed phases | `docs/ROADMAP.md` |
| Pair-flow gotchas | `~/.claude/projects/.../memory/pair_flow_gotchas.md` |
| NSC pitfall | `~/.claude/projects/.../memory/nsc_cidr_pitfall.md` |
| Plumbing-vs-UI gap | `~/.claude/projects/.../memory/phase0_intelligence_gap.md` |
| Live E2E playbook | `~/.claude/projects/.../memory/e2e_playbook.md` |
| Філософія / 40 capabilities | `../phantom-os/docs/MOBILE_ECOSYSTEM_VISION.md` |
| Ecosystem grand plan | `../phantom-os/docs/ECOSYSTEM_GRAND_PLAN.md` |

---

> «Поодинці — мега-потужні системи. Разом — магічна екосистема.»
> — оператор Кирил, 2026-05-09
