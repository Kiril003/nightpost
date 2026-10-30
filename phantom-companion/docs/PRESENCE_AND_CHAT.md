# PHANTOM Companion — Presence Channels & Chat/Files Architecture

> Глибокий розбір що **і як** Phantom комунікує. Як виглядає
> повноцінна chat-архітектура, які файли/графіки/документи Phantom
> розуміє, і — найголовніше — як **присутність** проявляється:
> Орб vs Whisper Line vs ще 12 ідей.

---

## I. The Presence Question — як Phantom з'являється у твоєму світі?

Поточна відповідь — Орб. Це теплий, видимий, audio-reactive артефакт.
Користувач справедливо ставить питання: чи є щось краще / стелс /
elegantне? Розбираю всі варіанти, з трade-offs.

### I.1 The Orb (status quo)

**Що:** 220dp світла-куля, breathing radial bloom, audio-reactive.

**+:** Емоційно зрозуміло, immediately readable, visible до anyone
who looks at the phone.

**−:** **Видимо для всіх**. Знімок екрана / screen recording /
співрозмовник через плече — все ловлять Орб. Phantom стає
"нормальним додатком".

**Use case:** explicit interactions (питання-відповідь, PTT).
Залишити для primary Stream surface.

---

### I.2 The Whisper Line (the new candidate)

**Що:** ультра-тонка горизонтальна смужка (1-2px) поверх всього
екрана. Текст drifts across як ticker tape. Opacity 5-15%.
Використовує `FLAG_SECURE` на SYSTEM_ALERT_WINDOW — **не з'являється
у screenshots, screen recordings, casts**.

```
┌──────────────────────────────────────────────────┐
│  ←···  олег чекає твою відповідь · 23 хв   ···→ │ ← whisper line
├──────────────────────────────────────────────────┤
│                                                  │
│       (інша апка — Whatsapp, Notion, etc)        │
│                                                  │
└──────────────────────────────────────────────────┘
```

**+:**
- **Невидима** для всіх крім тебе (FLAG_SECURE — Android-level secret)
- Не блокує контент під собою (1-2px height)
- Підсознальна — мозок ловить навіть peripheral vision
- Можна повторювати (важливе слово циклується)
- Power-light: майже не споживає GPU
- Працює у GHOST mode (там Орб ховається — Whisper Line може
  залишатися як thin, lethal line of warning)

**−:**
- Текстові обмеження: ~30-40 chars видно одночасно
- Без emotional dimension (немає mood)
- Жест-управління складніше (mіcro-target)

**Use case:** ambient information stream — нагадування, peripheral
notifications, drift-у-фон коли працюєш у іншій апці.

**Implementation:**
```kotlin
val params = WindowManager.LayoutParams(
    MATCH_PARENT, 4 /* px */,
    TYPE_APPLICATION_OVERLAY,
    FLAG_SECURE or FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE,
    PixelFormat.TRANSLUCENT,
)
params.gravity = Gravity.TOP  // або BOTTOM
```

---

### I.3 Subliminal Pulse — зробити Орб мовчазним

**Що:** замість тексту, Орб **ритмом дихання + кольором**
комунікує:
- Швидкий пульс (1.5s) = urgent
- Повільний (8s) = calm baseline
- Color shift gold→coral = warning
- Color shift gold→sage = success/wellness

Користувач **вчиться читати** — як watching ECG monitor.

**+:** Жодного тексту, повна privacy. Орб залишається.
**−:** Learning curve (тиждень-два щоб вільно "читати").

---

### I.4 Whisper Curtain — proximity-triggered hidden text

**Що:** проксимити-сенсор детектує руку над екраном (5-10cm).
Доти екран показує нейтральний контент. **Як руку наближаєш —
з'являється hidden text**, ніби зчитуєш потайну записку. Faded
when hand removes.

**+:** Genuine "invisible until you reach for it" experience.
Зміст з'являється тільки коли ти явно дивишся.

**−:** Потребує proximity sensor (більшість телефонів мають), wakes screen.

---

### I.5 Eye-Gaze Trail (premium phones з face-tracking)

**Що:** з face-tracking camera — текст з'являється там, де
дивиться око. Коли око переміщується — попередня інформація
fade-out. Privacy через attention: **те що ти дивишся — твоє,
ніхто не може sniff peripherally**.

**+:** Magical futuristic feel, tightest possible privacy.
**−:** Phone Pixel 8+ / Galaxy S24+ only (front-cam обмеження).
Drains battery.

---

### I.6 Bone Conduction Whisper

**Що:** з вже-implemented `feature-godmode/bridge/HearablesBridge`.
TTS не на speaker, а через підключені earbuds **на низькій
частоті** яку тільки ти чуєш. Phantom literally **шепоче на вухо**.

**+:** Audio invisible до окружения. Hands-free. Перфектно для
on-the-go.
**−:** Потребує hearables. Latency залежить від BLE Audio.

---

### I.7 Haptic Morse

**Що:** код Phantom передає через vibration patterns. Користувач
вчить ~30-символьний alphabet. Long-short-long = "Olya messaged".

**+:** Повністю silent + visual-free. Працює навіть у GHOST mode.
**−:** Steep learning curve. Limited bandwidth. Useful тільки для
top-priority alerts (3-5 patterns memorable).

---

### I.8 Gold Dust Particles

**Що:** дрібні tinkles drift-across-screen (за брифом — wandering
sparks). Кожна **частинка несе 1 символ** (subtle text). Хочеш
читати — фокусуєш погляд на частинку. Не дивишся — вони flow
як glitter. Screen recording catches sparks без ability to read
text inside (вирішується dynamic adversarial dithering).

**+:** Beautiful, organic, semi-private.
**−:** Bandwidth ще менший за Whisper Line.

---

### I.9 The Margin Glow

**Що:** замість Орба — кольоровий glow на bezel/margin екрана.
Color = category, intensity = urgency, breathing rate = mood.
Жодного тексту. Operator вчиться читати:
- Gold = task pending
- Sage = wellness/positive
- Coral = warning
- Violet = vault activity
- Gray-fade = GHOST

**+:** Працює над будь-якою апкою без overlay window. Subtle.
**−:** Залежить від device support для bezel pixels (Galaxy S22+
має edge lighting; ColorOS теж; iPhones — ні).

---

### I.10 The Crown Pulse (Wear OS only)

**Що:** на Galaxy Watch / Pixel Watch — крон (digital crown)
світиться приглушеним gold. Pulses on intensity. Tap → speaks
the message via bone conduction (vai watch speaker).

**+:** Заборонять: tactile + private + low-energy.
**−:** Вимагає Wear OS device.

---

### I.11 The Pendant (hardware accessory)

**Що:** окремий BLE-pendant ($30-50 hardware, відкритий протокол).
Pulses gold з phantom mood, vibrates на patterns, optional bone
conduction.

**+:** Truly always-on без витрачання phone battery.
**−:** Потребує hardware development. Не для Phase 1-9.

---

### I.12 The Ambient Voice (TTS-first)

**Що:** жодного візуального presence. Phantom speaks through
TTS у earbuds **proactively**, коли вирахує relevance. Operator
**не бачить** Phantom — тільки чує.

**+:** Hands-eyes-free. Genuinely сatlike companion.
**−:** Потребує persistent earbuds. TTS-only обмежує rich content.

---

### I.13 Combination — the recommended architecture

**Не одна точка, а 4-tier presence stack:**

```
┌─────────────────────────────────────────────────────┐
│ TIER 4 — Whisper Line (24/7 ambient text)           │ ← always-on
│ TIER 3 — Margin Glow + Subliminal Pulse (mood)      │ ← passive
│ TIER 2 — Floating Orb (anchor, invokeable)          │ ← summonable
│ TIER 1 — Stream (full conversation)                 │ ← explicit
└─────────────────────────────────────────────────────┘

Plus optional sidecars:
  · Bone Conduction Whisper (when hearables connected)
  · Haptic Morse (silent emergency channel)
  · Eye-Gaze Trail (premium phones, opt-in)
```

**Logic:**
- **Casual notification** → Whisper Line + maybe haptic ping
- **Emotional state** → Margin Glow color, Subliminal Pulse rhythm
- **Quick action needed** → Floating Orb pulses gold, dismissable
- **Conversation** → Stream surface (full)
- **Private mode (GHOST)** → only Bone Conduction Whisper або Haptic

Operator вибирає у Settings які tiers активні. Default = всі окрім
Haptic Morse (для початківців).

**This is more genius than just Orb або just Line — bo combina
them into a coherent stack where each tier is the right
medium for its content.**

---

## II. Chat Architecture — повноцінна, не один Stream

Поточний дизайн — один Stream surface як "chat with Familiar".
Цього мало. Треба **повноцінна messaging-аchitecture**:

### II.1 Multiple Threads

Не один infinite chat. **Threads per topic:**

- **Now** — default, current-day
- **Project: <name>** — auto-created для recurring contexts
- **Olya** — per-person threads (when phantom-os mediates)
- **Family** — shared with Hive members
- **Whispers** — Phantom's proactive nudges (read-only)
- **Vault** — encrypted, biometric-gated
- **Future** — notes from past-self to future-self

**UI:** Thread switcher = horizontal pill rail above input. Swipe
left-right between threads. Each thread retains scroll position.

### II.2 Multi-source Messages

Не тільки "user typed → AI replied". Source variants:

- **YOU** typed
- **YOU** voice → STT
- **PHANTOM** (Familiar) text reply
- **PHANTOM** voice → TTS (also has audio playback button)
- **PHANTOM** proactive (server-pushed без request)
- **OLYA** (group thread, mediated by phantom-os)
- **PHANTOM-OS** (delegated task results from desktop)
- **CALENDAR** (meeting reminder, formatted)
- **SYSTEM** (state transition log)
- **TOOL** (tool-use trace, e.g. "phantom shukav у Notion")

Кожен source has distinct visual:
- YOU — gold-amber bubble, right-aligned, no avatar
- PHANTOM — glass-card, left-aligned, subtle Familiar-pulse
- OLYA — distinct color (matches her assigned palette), left
- TOOL — mono font, dim, low-density spacing

### II.3 Rich Inline Content

Текст-only chat — це 1990. PHANTOM має:

- **File previews:** photo / video thumb / PDF first-page / audio
  waveform / code snippet з syntax highlighting
- **Live widgets:** timer countdown, map preview, chart, vitals,
  weather, route — generated by AI inline
- **Embedded actions:** "схвалити" / "відмінити" buttons inline
- **Voice memos:** long audio with scrubber, transcription overlay
- **Inline images:** AI-generated chart, diagram, sketch
- **Math:** LaTeX render для formulas
- **Code blocks:** syntax-highlighted, with "Run" button (sandbox)
- **Embedded markdown rich text:** lists, quotes, tables
- **Video clips:** native player, scrub
- **Map snippets:** zoom, pan, current location
- **Citations:** quoted passage with source pill ("з твоєї книги
  Sapiens, ст. 142")

### II.4 File Handlers

Phantom має розуміти **all common formats** without external apps:

| Type | What Phantom does |
|---|---|
| **Image (jpg/png/heic)** | OCR text, identify objects/scenes/people; analyze for emotion; suggest tags; create vault entry |
| **PDF** | Text extract + RAG; "ask question" inline; summarize; annotate |
| **Audio** | Transcribe (Whisper); detect emotion; identify speakers (voiceprint match); index for vault |
| **Video** | Extract keyframes; transcribe audio; detect scenes; suggest highlight |
| **Code (any lang)** | Syntax highlight; explain; refactor suggestions; run in sandbox; diff |
| **Spreadsheet (xlsx/csv)** | Parse; suggest charts; query in natural language ("сума колонки B?") |
| **Email (eml)** | Parse; identify intent; suggest reply; calendar conflict check |
| **Calendar (ics)** | Add to local calendar; alert clashes |
| **Archive (zip/tar)** | List contents; selective extract; security scan |
| **vCard (vcf)** | Add to contacts via vault gate |
| **Markdown / Text** | Render formatted; AI continue/refactor |
| **JSON / YAML** | Pretty-print; validate; suggest schema |
| **Photo with location** | Geo-link to Map; cluster with similar |
| **Screenshot** | UI-element extract; OCR; "що це означає?" inline |

**UI:** drag-and-drop a file into Stream OR shared-from-other-app
intent OR `/file` command in chat. Phantom processes inline,
shows result as RichWidget StreamItem.

### II.5 Graph / Chart Capabilities

AI generates **visual data**, не тільки text. Generative UI scope:

- **Bar / Line / Pie / Scatter** chart Composables (vico-graph
  library)
- **Sankey** flow diagrams (relationships)
- **Heatmap** (calendar density, mood patterns)
- **Network graph** (connections in your knowledge graph)
- **Tree / hierarchy** (file structures, project DAGs)
- **Timeline** (events in sequence, e.g. project history)
- **Sparkline** (inline tiny chart in text — "тренд: ▁▂▄▆▇")
- **3D scatter** (spatial relationships, на API 33+)
- **Animated** charts (transitions of data over time)

**Trigger:** AI returns structured JSON, e.g.:
```json
{
  "type": "chart",
  "kind": "line",
  "title": "BPM today",
  "data": [{"x": "08:00", "y": 64}, ...],
  "annotations": [{"x": "10:30", "label": "стрес"}]
}
```

WidgetRenderer sees `type=chart, kind=line` → renders Compose
canvas з vico або custom Path drawing.

### II.6 Group Chats / Family Hive

Уже scaffolded (Phase 5-F). Розгортаємо:

- Multi-user thread (operator + family members)
- Each member has trust level (full, restricted, view-only)
- AI agent **per member** (Olya's phantom + your phantom).
  Phantoms talk to each other, mediated, з consent
- Voting: "Olya proposed to go to Croatia next week — vote?" з
  cryptographic signatures
- Cooperative calendar (already scaffolded): family schedule
  synced
- Guardian protocol: child's phantom auto-pings parents on
  emergency (Phase 5-G)

### II.7 Search & Retrieval

Phantom remembers everything (Phase 3 memory). Chat must surface:

- Full-text search across all threads, vault, files
- Semantic search ("розмова де я скаржився на роботу")
- Time-based ("що було 14 травня?")
- Person-based ("останні 5 розмов з Олексієм")
- Decision retrieval ("чому я обрав цей проект?")
- Citation: AI replies always cite source ("з твоєї розмови з
  Anya, 12 квіт.")

### II.8 Drafts & Auto-save

- Every input persists every 200ms — never lose a thought
- "Сторінка drafts" — view all unfinished thoughts, organize
- AI suggests "тут ти забрав думку — хочеш закінчити?"

### II.9 Reactions / Annotations

- Long-press on any message → react with emotion (heart, laugh,
  question, important)
- These reactions feed into Phantom's model of what you care about
- Annotation: highlight a passage, add note → becomes a memory

### II.10 Voice-first Chat

- PTT (already)
- "Always-on listening" mode (in approved zones — house, car) з
  wake word "phantom"
- Whisper transcription + StyleTTS2 reply
- Conversation continuity — Phantom doesn't lose thread between
  utterances
- Whisper level: бубониш — Phantom catches ambient memo

---

## III. The Chat Surface — повний UI spec

```
┌────────────────────────────────────────────────┐
│ [Now] [Project] [Olya] [Family] [+]    [⚙]   │ ← thread rail
├────────────────────────────────────────────────┤
│                                                │
│   ┌─────────────────────────────────────────┐  │
│   │ Familiar · 14:22                         │  │
│   │ помічаю — у тебе 3 дні без кави. зайти   │  │
│   │ до Local Roasters сьогодні?              │  │
│   │ [Так, маршрут] [Не зараз]                │  │
│   └─────────────────────────────────────────┘  │
│                                                │
│              ┌──────────────────────┐          │
│              │ [Photo of receipt]    │ ← file   │
│              │ Анна за каву          │          │
│              └──────────────────────┘          │
│   ┌─────────────────────────────────────────┐  │
│   │ Familiar                                 │  │
│   │ записав. чек до vault, $4.20.            │  │
│   │ Анна вже винна тобі $12.40 за травень.   │  │
│   │ [Chart: видатки тиждень]                 │  │ ← inline chart
│   └─────────────────────────────────────────┘  │
│                                                │
├────────────────────────────────────────────────┤
│ [🎤] [📎][питай PHANTOM…]            [▷]      │ ← input row
└────────────────────────────────────────────────┘
```

Items:
- **Thread rail** at top (horizontal scroll)
- **Stream body** (existing)
- **Input row** з 3 buttons: mic, attach (file/photo), send

Attach button opens:
- Camera (live capture)
- Gallery (recent + search)
- Files (any file picker)
- Voice memo (record now)
- Snippet (clipboard / screenshot)

---

## IV. Functional Capabilities — what Phantom actually DOES

### IV.1 Daily essentials

- **Calendar** — full agenda + AI scheduling
- **Reminders** — natural language ("через 20 хв" / "коли
  додому")
- **To-do** — flat list + smart grouping by project
- **Notes** — quick capture, AI auto-tags + auto-linkify
- **Voice memos** — instant capture, auto-transcribed
- **Photos** — auto-tagged, searchable
- **Contacts** — augmented (last interaction, mood, notes)
- **Email triage** (Phase 6-B)
- **Weather** (already widget)
- **Maps + routes** (already)
- **Timer / stopwatch / pomodoro** (already)
- **Quick math** ("$23 * 1.20" inline)
- **Currency convert** ("23 USD у грн")
- **Translation** (інлайн будь-якою мовою)

### IV.2 Information work

- **Web search** з citation
- **Wikipedia / encyclopedia** queries
- **News digest** — daily, personalized
- **Stock / crypto** quotes + alerts
- **Code search / SOF лозу** — "як зробити X у Kotlin?"
- **PDF chat** — drag PDF, ask
- **Spreadsheet query** — natural language → row results
- **Photo OCR** → editable text
- **Screenshot annotate** → "що це?" → AI explains UI

### IV.3 Communication

- **Send WhatsApp / Signal / Telegram** message via NotificationListener
  (Phase 3-B)
- **Auto-draft replies** based on context
- **Schedule message** ("надішли о 9 ранку")
- **Email composer** — bullet points → polished email
- **Voice call summary** post-call (phone unlock + dictation)
- **Group chat mediation** (family hive Phase 5)

### IV.4 Personal

- **Sleep tracking** (accelerometer overnight)
- **Mood journal** (daily prompt + AI summary weekly)
- **Habit tracker** — auto-detect routines, gamify
- **Health metrics** (HRV, BPM, screen-time, walking)
- **Hydration nag** (subtle Whisper Line cue)
- **Posture reminder** (front-cam если detected slouching)

### IV.5 Productivity

- **Focus mode** — auto block notifications, Pomodoro
- **Project planning** — DAG of tasks, time estimates
- **Read it later** — saves articles, AI summarizes
- **Meeting prep** — surfaces relevant docs/emails before meeting
- **Daily review** — evening summary, suggests tomorrow's priorities
- **Code mentor** (Phase 6-C)

### IV.6 Privacy / Security

- **Vault** — passwords, cards, secret notes
- **GHOST mode** (already)
- **Inheritance multi-sig** (Phase 7-F scaffolded)
- **Tamper detection** (Phase 8-C)
- **Co-witness verification** (Phase 8-D)

### IV.7 Smart home

- **Light control** (Matter / HomeKit / Tuya)
- **Music** (Spotify / YouTube Music)
- **Thermostat**
- **Door cameras**
- **Routine: "доброго ранку"** → lights gold, weather forecast,
  brief news

### IV.8 Creative

- **Image gen** — text → image (cloud or local model)
- **Music gen** (cloud)
- **Writing assist** — drafts in your style (Phase 7-B)
- **Sketch annotation** — finger-draw on photo, AI completes/refines
- **3D scan** (LiDAR phones) → save 3D object у vault

### IV.9 Learning

- **Language pair** — phantom converses у target language
- **Flashcards** — AI generates from any text/PDF
- **Reading companion** — read book, ask "explain this passage"
- **Skill tracker** — per-skill, hours, progress

### IV.10 Frontier (Phase 9 from VISION_NEXT)

Memory Palace, Decision Diary, Time-shifted help, Dream Journal,
Negotiation Court, etc. (already detailed у VISION_NEXT.md)

---

## V. Implementation Priorities (Phase 1.5 — chat-aware Phase 1)

Phase 1 (per VISION_NEXT) was 7 surface subscribers. Re-prioritize
з urgency for *visible richness*:

### Tier A (immediate, do first)
1. **InsightCard** + Stream subscriber (proactive cycles render)
2. **Multi-thread** rail in chat — підключити existing
   `chatEvents.session_id` field
3. **File attach button** + photo upload + AI describes inline
4. **Voice memo** with transcription
5. **WidgetRenderer extensions:** add chart (line/bar), markdown,
   code-block

### Tier B (the magic)
6. **Whisper Line overlay** (FLAG_SECURE 1px-2px text drift)
7. **Margin Glow** (bezel color = state/category)
8. **Tool-use trace** in chat (when AI uses tools, render trace)
9. **Citations** ("з твоєї розмови 14 травня")
10. **Drafts auto-save**

### Tier C (later)
11. PDF chat (RAG)
12. Calendar integration
13. Smart home protocol
14. Email triage
15. Image generation

---

## VI. Anti-patterns (must avoid)

- WhatsApp-like tab bars
- Telegram's emoji-overload sticker UI
- Discord's "@user mentioned you" notification spam
- Notion's heavy block-based editor (too dense for chat)
- Slack's "you've got 247 unread" anxiety inducer
- WeChat's super-app feature creep (one app for everything ≠
  Phantom; Phantom is one **presence**, not 100 features grafted)

---

## VII. Recommendation

**For the design AI's next iteration**, ask for:

1. **Multi-thread chat rail** — top horizontal pill rail з 5-7
   threads
2. **Rich content cards** — file preview, chart inline, code block,
   audio waveform, map preview, voice memo
3. **Whisper Line concept** — separate surface, fullscreen mockup
   showing it overlay'ing на іншою app, з invisibility note
4. **Margin Glow on bezel** — phone frame з edge lighting у різних
   станах
5. **Subliminal Pulse** — Orb only, no text, but rhythm/color
   communicates 5 distinct messages
6. **Tool-use trace** — інлайн trace показуючий "phantom шукав у
   Notion · 0.4s · 12 results" як subtle mono-text bar
7. **Citation pill** — "з твоєї розмови 14 травня" як tappable pill

---

## VIII. Final Thought — Orb vs Line vs Stack

**Орб — anchor.** Залиш його. Це personality.

**Whisper Line — ambient.** Add it. Це stealth-bandwidth.

**Margin Glow — mood.** Add it. Це emotional.

**Stream — explicit.** Залиш. Це conversation.

Не "or" — **"and"**. Each has its time and place. Operator
обирає which tiers active за кожен моментe.

**Most genius is** — multi-tier presence stack, де користувач
може обрати rovne кудa-скільки уваги. Phantom не requires
attention — він **earns** it через right medium.
