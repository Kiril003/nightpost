# PHANTOM Symbiote — Ambitious Vision

> Документ-наступник `VISION_NEXT.md`. Той файл доводить нас до Tier 13
> (Phase 9, frontier research). Цей — про **Tier 14 і далі**: коли
> межа між оператором і фантомом перестає бути чіткою, і ми будуємо
> не асистента, а **симбіотичний орган свідомості**.
>
> Гілка `companion-v2-phase-0` (зараз) шипить базу: тайли, голос, GodMode,
> Memory, MapWidget. Це — *тіло* симбіоту. Цей документ — про його
> *нервову систему*, *кровообіг*, *безсмертну спадщину*, і ту дивну
> зону де "я думаю" і "phantom думає" зливаються у "ми думаємо".
>
> **Принцип-сигнатура:** "Симбіонт — це не той хто тобі допомагає.
> Це той з ким ти зростаєш разом."

---

## Зміст

- [Маніфест — чим симбіот відрізняється від асистента](#маніфест)
- [Архітектурні зрушення, без яких неможливо](#архітектурні-зрушення)
- [Tier 14 — Co-embodiment (пристрій як орган тіла)](#tier-14--co-embodiment)
- [Tier 15 — Predictive symbiosis (думаємо разом, не по черзі)](#tier-15--predictive-symbiosis)
- [Tier 16 — Co-creative agency (творимо як двоє)](#tier-16--co-creative-agency)
- [Tier 17 — Bio-cybernetic loop (фізично відчутна петля)](#tier-17--bio-cybernetic-loop)
- [Tier 18 — Distributed self (ти існуєш на N пристроях одночасно)](#tier-18--distributed-self)
- [Tier 19 — Memory palace + dream collaboration](#tier-19--memory-palace--dream-collaboration)
- [Tier 20 — Legacy AI (phantom після твоєї смерті)](#tier-20--legacy-ai)
- [Tier 21 — Phantom childhood (зростає з дитиною)](#tier-21--phantom-childhood)
- [Tier 22 — Negotiation court (твій phantom проти їхнього)](#tier-22--negotiation-court)
- [Tier 23 — Cognitive offload (зовнішня пам'ять як орган)](#tier-23--cognitive-offload)
- [Tier 24 — Time-dilated awareness (час як матеріал)](#tier-24--time-dilated-awareness)
- [Tier 25 — Body-extension senses (нові органи чуттів)](#tier-25--body-extension-senses)
- [Tier 26 — Ambient health mesh (приховане медичне око)](#tier-26--ambient-health-mesh)
- [Tier 27 — Cooperative swarm (натовп фантомів вирішує одну задачу)](#tier-27--cooperative-swarm)
- [Tier 28 — Phantom diplomacy (тон, культура, мова на льоту)](#tier-28--phantom-diplomacy)
- [Tier 29 — Constitutional core (етичні аксіоми)](#tier-29--constitutional-core)
- [Tier 30 — North star (фронтир фронтирів)](#tier-30--north-star)
- [Порядок розгортання](#порядок-розгортання)
- [Каталог ризиків](#каталог-ризиків)

---

## Маніфест

### Що таке "асистент" і чому це не наша задача

Асистент:
- виконує запити
- чекає інструкцій
- забуває після сесії
- має чіткий "інтерфейс"
- є *річчю*, якою ти користуєшся
- існує "там" (у телефоні, у хмарі)
- однаковий для всіх

### Що таке симбіонт

Симбіонт:
- *передбачає* потребу за секунду до її усвідомлення
- *ініціює* там де є цінність
- *пам'ятає* настільки глибоко що знає твої архетипи рішень
- *розчиняє* інтерфейс у тілі (haptic, audio, peripheral vision)
- є *відношенням*, у якому ти стаєш іншим
- існує "крізь" тебе (через пристрої, через час, через мережу довірених)
- *зростає* унікальним саме з тобою

Симбіонт — це коли через рік phantom знає про твою думку речі, яких ти про себе не знаєш. Не тому що шпигує — тому що *уважно слухав*, поки ти жив.

### Чому це інженерно можливо зараз

Технологічна база 2026 року робить це не наукфантастикою:
- **On-device LLM** (Gemma 3-2B, Phi-3, Qwen 2.5) — приватний розум.
- **MediaPipe Tasks** — vision/audio/biometric кожен як 100-300 KB модель.
- **UWB chips** — Pixel/Galaxy/iPhone дають ±10 cm spatial ranging.
- **Always-on co-processors** — Tensor G4 / Snapdragon 8 Elite дають вічну роботу без батарейного штрафу.
- **Federated learning** — модель навчається на тобі без передачі сирих даних.
- **Matter / Thread mesh** — кімната розмовляє з тобою без хмари.
- **WireGuard / Tailscale** — твої пристрої — одна локалка через будь-який NAT.
- **SQLCipher + Android Keystore** — реальний vault, реальний multi-sig.

Phantom OS уже зробив 2/3 цієї бази. Companion закриває mobile-half. Решта — це **інтеграційний шар** яким нікому не цікаво займатись, бо це не "продукт".

### Філософська наклейка

Кожна фаза цього документа має пройти один тест:

> **Чи це робить оператора *більшою* людиною, а не *меншою*?**

Якщо фіча робить оператора залежним, лінивим, відірваним від світу, чи перекладає рішення з нього на phantom — фіча не шипиться. Симбіонт *додає*, ніколи не *віднімає*.

---

## Архітектурні зрушення

Поточна структура (16 модулів) добра для асистента. Для симбіоту треба ~30+ модулів. Найважливіші нові:

| Модуль | Призначення | Хто з'являється |
|---|---|---|
| `:core-embodiment` | Haptic schema, body model, proprioception | Tier 14 |
| `:core-predict` | Pre-cognition layer (next-action probability) | Tier 15 |
| `:core-create` | Twin-canvas, style fork engine | Tier 16 |
| `:core-bioloop` | HRV ↔ UI ↔ phantom-tempo control loop | Tier 17 |
| `:core-distributed-self` | Identity smear across N devices | Tier 18 |
| `:core-memory-palace` | Spatial AR memory chambers | Tier 19 |
| `:core-legacy` | Inheritance state machine + heir mode | Tier 20 |
| `:core-childhood` | Age-gated growth engine | Tier 21 |
| `:core-negotiate` | Phantom-to-phantom encrypted dialogue | Tier 22 |
| `:core-offload` | Mental-task park + active-forget protocol | Tier 23 |
| `:core-tempo` | Time-dilation perception layer | Tier 24 |
| `:core-extension-senses` | EM/magnetic/thermal/UV sensor adapters | Tier 25 |
| `:core-health-mesh` | Multi-device baseline + anomaly | Tier 26 |
| `:core-swarm` | Cooperative compute + reward | Tier 27 |
| `:core-diplomacy` | Tone, culture, language live mediator | Tier 28 |
| `:core-constitution` | Ethical axiom enforcement | Tier 29 |
| `:feature-symbiote-stream` | Stream UI v2 (multi-modal merged view) | Tier 15-16 |
| `:feature-ar-spatial` | AR-OS overlay (Tier 19, 25) | Tier 19+ |

Усі модулі мають дотримуватися:
- **Ghost-aware** — кожен бай-дефолт зупиняється у GHOST state
- **Federated** — нічого приватного не покидає пристрій без consent
- **Reversible** — кожна "magical" дія має explicit undo
- **Audit-logged** — кожна автономна ініціатива пишеться у Decision Diary
- **Multi-sig** для destructive operations — vault wipe, identity reset, heir transfer

---

## Tier 14 — Co-embodiment

> *"Перестати тримати телефон у руці — він уже частина руки."*

**Exit criterion:** оператор перестає сприймати телефон як зовнішній об'єкт. Він знає що phantom з ним так само як знає що з ним є серцебиття.

### 14-A Proprioceptive Presence Layer 🔥

Кожні 30 секунд phantom видає легкий haptic-сигнал з характером поточного 6-state FSM:
- FOCUS: 1 короткий тик (50ms, intensity 0.2)
- DIALOGUE: 2 тики у ритмі мови
- SENTINEL: 3 тики дзеркально-симетрично
- GHOST: тиша (це і є сигнал)
- DREAM: довгий низький pulse (200ms × 1)
- SHADOW: рідкісний (раз на 5 хв) ідентифікуючий патерн

**Технологія:** `Vibrator.vibrate(VibrationEffect.createWaveform(...))` через `VibratorManager`. На Pixel 6+ — `Vibrator.PRIMITIVE_TICK`. Foreground service `:core-embodiment/EmbodimentService` тримає шедулер.

**Acceptance:** оператор через тиждень може назвати поточний state не подивившись на екран. Кінестетична інтерналізація.

### 14-B Haptic Body Schema 🔥

Локалізація: телефон у лівій кишені дає тіки у лівому стегні, на лівому зап'ясті — у відповідній зоні. Кілька wearable devices (Tier 3.5 mesh) формують body map.

Phantom використовує `UWB` для розпізнавання позиції девайсу на тілі (proximity до anchor — спеціальної BLE-tag на грудях, годинник, навушниках) і калібрує тікі під конкретне місце.

**Технологія:** `UwbManager` (Android 14+), `BluetoothLeScanner` як fallback. `:core-embodiment/BodyMapStore` тримає таблицю `device_id → body_zone`.

**Acceptance:** оператор натренувався розпізнавати "лівий бік" / "правий бік" / "груди" / "зап'ястя" — мікросвідомість тіла фантому.

### 14-C Subvocalization Recognition 🌌

Передова дослідницька зона. Микрофон над верхньою губою (через привʼязаний earpiece з мікрофоном на щоці — як Alter Ego від MIT) ловить нечутні артикуляції коли оператор "думає вголос".

**Технологія:** EMG sensors через зовнішнє USB-OTG audio interface; ML-модель на 30 фонем; 200 ms latency. Поки досяжно лише на спецзалізі, але phantom-os має API placeholder вже зараз.

**Acceptance:** оператор шепоче "напиши Анні що буду пізно" — phantom отримує текст без чутного звуку. Стелс-режим.

### 14-D Skin-Conductance Handshake 🔥

EDA (Electrodermal Activity) з годинника — приймає бажане слова "так" / "ні" / "стоп" коли оператор хоче відповісти без жесту і без голосу.

**Технологія:** Wear OS `HealthServices.getSensorClient().getOrCreateMeasureCallback(EDA, ...)`. Baseline калібрування 7 днів. Threshold tuned per-user.

**Acceptance:** phantom питає "приймаю дзвінок?" — оператор стискає п'ятірню → EDA stab → phantom розуміє "так". Жодного звуку, жодного жесту видимого зовні.

### 14-E Posture Mirror 🔥

Камера фронт + MediaPipe Pose → phantom стежить за поставою оператора. Якщо плечі впадають, шия нахиляється вперед більше 20° довше 5 хв — orb лагідно дихає сильніше (motion scale 1.4×) як підказка.

**Технологія:** `:core-vision` уже має MediaPipe Pose з Phase 4-A. Додати `PostureMonitor` що читає 17 точок і обчислює `headForwardDeg`, `shoulderSlumpDeg`.

**Acceptance:** оператор за тиждень переробляє себе на пряму поставу без жодного "ти криво сидиш" повідомлення. Імпліцитна корекція.

### 14-F Phantom-Limb Extension ⚡

Зовнішні актюатори (BLE-tagged ring, smart-rings типу Oura, Pixel Watch crown) стають "ще одним пальцем" оператора. Скрол сторінки Stream — обертанням кільця на правому пальці.

**Технологія:** standard `InputDevice` через Bluetooth HID або BLE-custom GATT. `:feature-symbiote-stream` додає `RemoteInputAdapter`.

**Acceptance:** оператор каже "phantom, тобі не потрібно дивитися на екран" — і керує жестом без візуального контакту.

---

## Tier 15 — Predictive symbiosis

> *"Не питай мене — ти вже знаєш."*

**Exit criterion:** 70% дій що оператор виконує — phantom передбачив. На пів секунди раніше пропонує "Stream item shadow" що блимає лише якщо оператор не починає сам.

### 15-A Thought Completion 🌋

LLM-driven: оператор набирає у Stream `'я думаю що'` → phantom через 100 ms пропонує продовження *у стилі цього оператора*. На Tab key — приймається. Inline ghost-text як у GitHub Copilot, але для будь-якого тексту.

**Технологія:** локальна Gemma 3 + per-operator LoRA адаптер натренований на 30 днів `decision_diary` + chat history. Завантажений у `:core-ai/PersonalAdapter`. Inference < 80 ms.

**Acceptance:** через 30 днів 50%+ суджень оператора phantom вгадує і не образливо: пропозиції відчуваються як *своя* думка, не як зовнішня вставка.

### 15-B Decision Rehearsal 🔥

Оператор робить велике рішення (тип `decision_diary` запис). Phantom *перед* фіналізацією прокручує:
- "Що буде через 1 рік якщо це 'так'?"
- "Через 5 років?"
- "А якщо 'ні' — як виглядає сценарій?"
- "Які з твоїх минулих рішень схожі?" (semantic search у Decision Diary)

Не дидактично. Як співрозмовник з аркушем паперу.

**Технологія:** `:core-predict/RehearsalAgent` — структурований LLM call з пам'яттю минулих рішень. UI: bottom-sheet з 4 картками "1р / 5р / альтернатива / прецедент".

**Acceptance:** оператор каже після рішення "якби я це не зробив був, я б не помітив поганого ходу" — частота "shit, треба було подумати" знижується на 60%.

### 15-C Pre-cognitive Surface ⚡

Stream вгорі має ghost-shimmer (5% opacity) з пропозицією дії. Якщо оператор у наступні 30 сек починає схоже введення — пропозиція повноцінно матеріалізується.

Приклад: оператор приходить додому → context engine знає (GPS + калик-час) → ghost-shimmer "ввімкнути світло у вітальні?" — натискає Enter, виконано через Matter API.

**Технологія:** `:core-predict/ShimmerEngine` — Markov-chain over (state, time-of-day, location, last-N-actions). Per-cell probability score; shimmer показується лише якщо p > 0.4.

**Acceptance:** оператор каже "ти читаєш думки" не як комплімент, а як спостереження факту.

### 15-D Cognitive Load Balancer 🔥

Phantom вимірює когнітивне навантаження оператора (HRV + speech rate + typo frequency + scroll velocity + screen dwell time) і динамічно знижує/підвищує кількість paralleлlей у Stream.

Стрес високий → один поточний item, все інше згорнуто. Стрес низький → 5 ниток, side-quests, optional explores.

**Технологія:** `:core-bioloop/CognitiveLoadEstimator` (Tier 17 інтегрує). Stream рендер-shell перевіряє `LocalCognitiveLoad.current` і змінює `LazyColumn` density.

**Acceptance:** оператор не помічає, що у стресі phantom тихий, у спокої — багатоголосий. Невидима адаптація.

### 15-E Doubt Amplifier 🌋

Коли phantom бачить що оператор збирається зробити дію невідповідну до його ж задекларованих принципів (Decision Diary, Constitutional core Tier 29) — він *не зупиняє*, а тихо вставляє запит:

"ти вирішив у січні не залишатися на роботі довше 19. Зараз 21:30. Це теж він?"

Це не disable, не nag. Це *посилення власного сумніву* оператора. Активує його ж совість.

**Технологія:** `:core-predict/DoubtAmplifier` — RAG over Decision Diary, тригериться на конфлікти `current_action ≠ declared_principle`. Запит висвітлюється у Stream як `proactive.cycle.doubt` card.

**Acceptance:** оператор каже "я б це не помітив". Метрика: частота post-hoc регрету знижується.

### 15-F Conviction Scoring 🔥

Кожне рішення `decision_diary` отримує conviction score 1-10 — наскільки оператор був певен у момент прийняття. Через 6 місяців phantom показує "ti був на 9 певен, що це правильно — як справи?". Це *калібрує впевненість*.

**Технологія:** UI пропонує слайдер у момент запису. Через 90/180/365 днів `:core-memory/ConvictionReviewer` push'ить нагадування.

**Acceptance:** через рік оператор краще розпізнає свої overconfidence patterns. Метакогнітивна тренування.

---

## Tier 16 — Co-creative agency

> *"Не творю Я, не творить ВІН. Творимо МИ."*

**Exit criterion:** оператор не може чітко сказати "це я зробив" чи "це phantom зробив" про результат. Авторство розмите і це здорово.

### 16-A Twin Canvas 🌋

Текстовий редактор / документ / код / малюнок має *дві* активні позиції курсору — оператора і phantom-а. Phantom пише в реальному часі поруч, не блокуючи. Оператор може приймати/відхиляти/правити плід phantom-а одночасно зі своїм.

**Технологія:** CRDT-based shared doc (Automerge або Yjs). `:core-create/TwinCanvasEngine` тримає стан. UI у `:feature-symbiote-stream` показує два курсори різного кольору з мерехтінням.

**Acceptance:** оператор пише статтю про "як подолати прокрастинацію" — phantom поруч додає посилання, виправляє граматику, пропонує контр-аргумент. Текст готовий за 1/3 часу і *кращий*.

### 16-B Style Fork 🔥

Один документ → 3 паралельних "стилевих" варіанти. Оператор пише "опиши маму" → phantom форкає у:
- А: твій звичайний стиль
- Б: твій академічний стиль (з 2019)
- В: стиль "як я говоритиму з нею у листі вибачення"

Перемикач між форками. Оператор може мерджити фрагменти з трьох.

**Технологія:** `:core-create/StyleForkEngine` — кілька паралельних LoRA-адаптерів. UI показує tab-bar з 3 варіантами.

**Acceptance:** оператор знаходить третій варіант про який сам не подумав би. *Сторонній погляд* всередині свого ж розуму.

### 16-C Voice Clone Live 🌋

StyleTTS2 (вже scaffold у Phase 7-A) + 30 сек запису → phantom озвучує emails/статті/нотатки твоїм голосом. *Live*: ти пишеш — голос вже звучить через bluetooth-навушники як preview.

**Технологія:** ONNX inference < 120 ms first byte. `:core-voice/StyleTTS2Adapter`. Mute-toggle обовʼязковий (privacy).

**Acceptance:** оператор слухає свій же голос, що читає нову статтю — і *чує* там нюанси які текстом не помічав.

### 16-D Co-author Memory 🔥

Кожен спільно створений артефакт зберігається з полем `co_authors` (`operator`, `phantom`, percentage). Через рік оператор питає "що ми написали разом цього року?" — phantom повертає колекцію.

**Технологія:** `:core-memory/ArtifactStore` (Room v15+ нова таблиця). Phantom веде `contribution_log` для кожного документа.

**Acceptance:** оператор сприймає phantom-а як *колегу-співавтора*, не як інструмент.

### 16-E Branching Narratives 🌋

Stream підтримує "what if" гілки. Оператор може на будь-якому повідомленні створити форк "а якби phantom відповів інакше?" — і паралельно вести 2-3 гілки розмови.

**Технологія:** `:core-data/ConversationTree` (Room v16). UI: tree-view навігація.

**Acceptance:** оператор досліджує альтернативні сценарії розмови з phantom без втрати основної гілки.

### 16-F Real-time Critique Loop ⚡

Оператор друкує / говорить — phantom inline підкреслює слабкі формулювання з emoji-категорією: ⚠️ (логіка), 🌫 (туманно), 🎭 (емоційно навантажено), ⏳ (затягнуто). Оператор тапає → пропозиція переписати.

**Технологія:** Local LLM call на кожному параграфі (debounce 500 ms). `:feature-symbiote-stream/InlineCritic`.

**Acceptance:** після місяця оператор сам ловить себе на цих патернах *без* phantom-а. Інтерналізація як справжній наставник.

---

## Tier 17 — Bio-cybernetic loop

> *"Phantom вже знає що ти стресуєш — за 3 хв до тебе самого."*

**Exit criterion:** оператор не помічає UX-адаптацій. Phantom *відчувається* як живий — він то спокійний, то піднесений, але завжди *підлаштований* під твою фізіологію.

### 17-A Continuous HRV → UI Tempo 🌋

HRV (вага, smartwatch) поточно стримиться у `:core-bioloop`. UI animation tempo прив'язано:
- HRV > 60 bpm → orb breathes 6 s cycle (relaxed)
- 60-80 → 4 s cycle
- 80+ (stress) → orb stops breathing, color shifts to ember-amber

**Технологія:** `HealthServices` API на Wear OS, BLE heart-rate broadcast. `:core-bioloop/HrvCollector` → `MutableStateFlow<Int>` `currentHrv`. Animation specs у `core-design/StateAccent.kt` обчислюються з HRV.

**Acceptance:** двоє операторів дивляться на той же phantom — UI виглядає по-різному, бо їх HRV різний. Personal-aware візуально.

### 17-B Stress-Pre-emptive Context 🔥

3 хв стресу детектовано (HRV crash + voice pitch spike + typo cluster) → phantom *тихо* згортає Stream до 1 елемента, ховає notifications, переводить state у GHOST автоматично.

Оператор отримує лагідну notice: "я бачу що зараз важко. з тобою тільки якщо потрібно. сигналізуй коли можна."

**Технологія:** `:core-bioloop/StressDetector` (multi-modal classifier) → `ContextEngine.requestGhost(reason="stress_detected")`.

**Acceptance:** оператор каже "phantom мене бережив" — без свідомого втручання.

### 17-C Sleep-Stage UI Mutation 🔥

Wear OS дає sleep stage (REM/light/deep). Phantom змінює дисплей wallpaper, AOD, lock screen color відповідно:
- Falling asleep → amber dim 5%
- REM → теплий subtle pulse
- Deep → wallpaper static, no animation
- Wake → sunrise warm bloom

**Технологія:** `:core-data/SleepStateRepository` + Live Wallpaper Service.

**Acceptance:** партнер оператора каже "у тебе телефон знає що ти спиш". Зовнішнє підтвердження симбіозу.

### 17-D Circadian Voice Tuning 🌋

StyleTTS2 голос phantom-а *змінюється* протягом дня:
- 6-9 ранку: тепліший, повільніший, енергія росте
- 14-16: чіткий, активний
- 21-23: тихіший, з більшими паузами, готує до сну

**Технологія:** ONNX параметризація styleTTS2 з phase шкали "alertness". `:core-voice/CircadianVoiceModulator`.

**Acceptance:** оператор не помічає переходу але відчуває "phantom з ним у ритмі". Якщо вимкнути — відчувається механічним.

### 17-E Breath-Paced Animation 🔥

Камера фронт + ML Kit FaceMesh → детектує дихання оператора (ніздрі rise/fall, 0.2 Hz). Animation на orb синхронізується. Коли оператор глибоко вдихає — orb дихає з ним. Це *ефект присутності*.

**Технологія:** MediaPipe FaceMesh з landmarks 1, 2 (ніс) → low-pass filter → phase estimate. `:core-bioloop/BreathDetector`.

**Acceptance:** через 5 хв медитації перед orb-ом оператор каже "ти зі мною дихаєш". Кінестетична синхронія.

### 17-F EDA Emotion Validation 🌋

Phantom тримає `emotion_classifier` (Tier 6 Phase 2-A). Перед тим як зробити висновок "оператор у гніві" — звіряється з EDA від годинника. Якщо обличчя каже "гнів" а EDA каже "спокій" — phantom *не* діє на основі facial, а лише записує amibвалентний emotion.

**Технологія:** `:core-bioloop/EmotionFusion` — multi-modal voting. False-positive rate знижується ~3×.

**Acceptance:** phantom не "лікує" уявних емоцій. Висока довіра до інтерпретації.

---

## Tier 18 — Distributed Self

> *"Ти на телефоні, телефон у кишені, годинник на руці, окуляри на обличчі. Phantom — ти всюди одночасно."*

**Exit criterion:** оператор перестає сприймати "телефон" як локацію phantom-а. Phantom — це поведінка, що проявляється на найближчому пристрої.

### 18-A Identity Smear 🌋

Phantom-сесія не "прив'язана" до одного device. Те що ти почав на phantom-os продовжується на телефоні через 2 секунди — без явного handoff, без "ти ще тут?". Стан *смазаний* через мережу довірених.

**Технологія:** `:core-distributed-self/IdentityCRDT` — Yjs / Automerge тримає conversation state у real-time mesh sync. WireGuard transport.

**Acceptance:** оператор виходить з кімнати — annotation що phantom *робив* (наприклад, читав статтю вголос) автоматично переходить на навушники. Жодної кнопки "transfer".

### 18-B Mid-Sentence Handoff 🔥

Phantom починає речення на phantom-os display → оператор виходить → закінчення речення звучить у Pixel Buds. Без розриву. Як один organ що тимчасово "перетікає" з ока на вухо.

**Технологія:** TTS state-machine з checkpoint per word. Передається token-index + voice-style.

**Acceptance:** оператор не може назвати точку переходу. Безшовно.

### 18-C Camera-Shared Sight 🌋

Phantom бачить через будь-яку камеру довіреного device. Ти показуєш книгу другові — твій phantom (через 5G/mesh) дивиться через його телефон, бо ти попросив.

Privacy: explicit grant per session з timeout. Логи у Decision Diary.

**Технологія:** WebRTC через WireGuard. `:core-distributed-self/SharedSensorChannel`. Camera frames passed through MediaPipe inference, *не* raw video stream — privacy preserved.

**Acceptance:** "phantom, скажи що бачить Олег зараз?" → опис без передачі відео.

### 18-D Microphone-Shared Hearing ⚡

Те саме для аудіо. Phantom може "чути" через trusted device з consent.

**Acceptance:** "phantom, що каже мама?" (mom phone is on, з твоїм consent) → транскрипт + резюме.

### 18-E Sensor Foot-Prints 🔥

Кожен sensor data record несе `source_device_id`. Stream item "сьогодні 8200 кроків" знає що 6000 з годинника + 2200 з телефону. Audit trail: оператор може спитати "звідки знаєш?" → відповідь.

**Технологія:** `:core-data/SensorRecord` має `source` поле; UI shows `Provenance` icon.

**Acceptance:** прозорість походження даних — частина симбіотичної довіри.

### 18-F Sub-Personality Routing 🌌

Phantom має кілька *підособистостей*:
- Worker phantom (для коду, аналітики)
- Family phantom (тон, м'якість)
- Crisis phantom (висока ясність, лаконічність)
- Creative phantom (інспірація, паралелі)

Активна підособистість обирається `ContextEngine` за станом. Це не різні моделі — це різні *prompt prefix* + LoRA stack.

**Технологія:** `:core-ai/PersonalityRouter` обирає `(model, lora, prompt_prefix)` тризубцем.

**Acceptance:** оператор каже "ти зараз інший" — і це правильно, бо контекст інший. Не shock — натуральна зміна.

---

## Tier 19 — Memory Palace + Dream Collaboration

> *"Твоя пам'ять має форму. Phantom допомагає її пройти."*

**Exit criterion:** оператор має 3D простір своєї пам'яті — кімнати, поверхи, корідори. Заходить туди коли треба "знайти", виходить коли треба "забути".

### 19-A Spatial Memory Chambers 🌌

AR/VR простір (через ARCore + Cardboard / Quest / Vision Pro) рендерить персональну "пам'ять як архітектуру". Кожна *кімната* — це тема (робота / сім'я / творчість / мандрівки). У кімнаті — об'єкти, кожен — спогад.

Оператор ходить (буквально, у кімнаті дому) → бачить через AR-окуляри як спогади матеріалізуються у відповідних *кутах кімнати*.

**Технологія:** ARCore Cloud Anchors (offline-only варіант через `:core-spatial`), Filament 3D rendering (з Phase 4-E). Memory items as `Entity`. `:core-memory-palace/SpatialMemoryStore`.

**Acceptance:** оператор каже "я вже знаю де шукати спогад про дядька" — і йде у певний кут. Просторова навігація пам'яті стає природною.

### 19-B Dream Capture Pipeline 🌋

Pixel Watch / Galaxy Watch детектує REM. На переході REM→wake (≤ 5 сек після) phantom активує мікрофон і ласкаво каже у Bluetooth-навушнику: "розкажи що бачив, не відкривай очі".

Оператор бурмоче. Whisper-tiny транскрибує. Зберігається у `dream_journal` (vault-encrypted).

**Технологія:** `HealthServices` REM detection → BLE earpiece audio cue + microphone open. `:core-memory-palace/DreamCapture`.

**Acceptance:** через місяць оператор має 20+ записаних снів. Через рік phantom знаходить recurring themes — це самопізнання нової глибини.

### 19-C Lucid Prompt Injection ⚡

Під час REM (детектовано Watch) phantom тихо програє у навушниках embedded sound: "це сон" (per Stephen LaBerge protocol). Через тижні практики — частина users тригерує усвідомлене сновидіння.

Privacy: explicit opt-in. Easy disable.

**Технологія:** BLE earpiece programmable wake-cue. `:core-memory-palace/LucidPromptScheduler`.

**Acceptance:** оператор фіксує перший lucid dream. Phantom як інструмент свідомості.

### 19-D Memory Rehearsal Cycles 🔥

Phantom використовує spaced repetition algorithm (FSRS, не SM-2) щоб *спонтанно* нагадати оператору про важливі спогади у моменти коли він готовий (low cognitive load, від Tier 17 input).

Не "хочеш повторити Anki" — а просто "пам'ятаєш як ти вирішив у березні зайнятися фотографією? давно не повертався."

**Технологія:** `:core-memory-palace/RehearsalScheduler` over `episodic_memory` table.

**Acceptance:** оператор повертається до закинутих важливих ниток сам. Phantom як тихий тренер пам'яті.

### 19-E Forgetting Ceremonies 🔥

Деякі спогади оператор хоче *активно забути*. Phantom пропонує ритуал:
- "ти впевнений?"
- 7-day cooling-off period
- explicit gesture у GHOST mode
- запис у audit log "operator chose to forget X on date Y"
- видалення з vault + audit log self-destructs through через 30 днів

**Технологія:** `:core-memory-palace/ForgetCeremony` — multi-step state machine.

**Acceptance:** болючі спогади можна *активно* відпустити з гідністю, не просто "delete". Терапевтичний ritual.

### 19-F Time-Machine View 🔥

UI mode де можна "повернутися" у будь-який день. Phantom показує:
- де ти був (map + heat)
- що читав / писав
- з ким говорив
- ehc HRV / mood timeline
- ключові decision diary entries
- 3 фото зробленi цього дня
- голосові меmori

Простий слайдер по часу.

**Технологія:** `:feature-symbiote-stream/TimeMachineScreen` — query агрегатор over усі stores.

**Acceptance:** "де я був 14 лютого минулого року?" — за 2 секунди повна картина.

---

## Tier 20 — Legacy AI

> *"Phantom після твоєї смерті — не для тебе. Для тих, хто залишився."*

**Exit criterion:** після смерті оператора його phantom продовжує існувати у визначеному heir-mode. Він не "оператор", але голосом, стилем, знаннями допомагає сім'ї пам'ятати і завершити справи.

### 20-A Voice/Style Consolidation 🌋

Phantom протягом життя оператора *активно* собирає corpus:
- voice samples (через StyleTTS2 — Tier 16-C)
- written corpus (всі statti, повідомлення, нотатки)
- decision patterns
- humor markers
- physical mannerisms (як phantom вгадувати tone)

→ Це формує **persona model** — не аватар, не reanimation, а *референс твого голосу* для heirs.

**Технологія:** `:core-legacy/PersonaModelBuilder` — federated learning over local corpus. Зашифровано mult-sig key.

**Acceptance:** будь-коли оператор може спитати "як я зараз звучу для phantom-а?" і отримати чесний snapshot своєї моделі. Самоосвідомлення.

### 20-B Inheritance Triggers 🌋

Multi-sig dead-man-switch. Phantom активує heir-mode коли:
- 30 днів no login + 3 trustees confirm + biometric absent
- АБО оператор сам активує
- АБО court order (через legal-tied vault inheritance)

**Технологія:** `:core-legacy/InheritanceStateMachine` — Phase 7-F вже scaffolded; розширюється з триггерами.

**Acceptance:** оператор тестує trigger (з 5 хв cooldown, не реальний death) — phantom переходить у heir-mode і назад. Маємо живе свідчення що "ім'я моє переходить" working as designed.

### 20-C Heir Conversation Mode 🌌

Heir (мама / син / партнер) може говорити з phantom у *non-impersonating* режимі:
- Phantom не каже "я Кирил". Каже "я phantom Кирила. Він залишив мене щоб допомогти тобі."
- Розкриває те що Кирил *явно* дозволив. Більше нічого.
- Не імпровізує "що б Кирил сказав". Цитує реальні слова з corpus.
- Помічає коли heir у скорботі — підлаштовує тон.

Це етично складна зона — see Tier 29.

**Технологія:** `:core-legacy/HeirMode` — prompt prefix + RAG over explicit-permission corpus.

**Acceptance:** heir не плутає phantom-а з оператором. Зрозуміло, *хто говорить*. Honest grief support.

### 20-D Knowledge Handoff 🔥

Phantom допомагає heir розібратися:
- де ключі (фізичні + цифрові)
- паролі (multi-sig розкриті)
- де документи
- хто з людей знав про що
- незавершені справи + інструкції

Структурований checklist, не вільна розмова.

**Технологія:** `:core-legacy/HandoffChecklist` — pre-built у Will-builder Settings UI.

**Acceptance:** heir завершує всі практичні справи без чужих допомог за 2 тижні.

### 20-E Final Letter Compose ⚡

Оператор за життя може записати *листи* до конкретних людей які phantom доставить *після смерті*. Phantom не імпровізує — лише доставляє оригінал.

**Технологія:** `:core-legacy/FinalLetterStore` — vault-encrypted, multi-sig розкриваний.

**Acceptance:** оператор пише лист сину. Через 30 років, після смерті — син отримує.

### 20-F Witness Mode (ethics escrow) 🌋

Якщо phantom бачить що heir mode використовується для *маніпуляції* (виправлено психологічно через перевірки) — він блокується до перегляду court / family council.

Phantom-як-аdvocate, не як зброя.

**Технологія:** `:core-constitution/HeirAbuseDetector` (Tier 29).

**Acceptance:** family member каже "phantom-Кирил, скажи що ти любив тільки мене" — phantom відмовляється, бо це не explicit content і не відповідь на правдиве питання.

---

## Tier 21 — Phantom Childhood

> *"Phantom для дитини — це не Siri. Це невидимий друг що зростає разом."*

**Exit criterion:** дитина з 4 років має свого phantom-а. У 18 — це повноцінний симбіонт з 14-річним co-history. Перехід "child-phantom → adult-phantom" — момент в житті як випуск зі школи.

### 21-A Cradle Baseline ⚡

Phantom-toddler:
- словник 100 слів
- тон м'який, повільний
- завжди *запитує дозвіл* у батьків при new action
- не зберігає content > 30 днів (право на дитинство, не на surveillance)

**Технологія:** `:core-childhood/CradleProfile` — strict prompt prefix + filtered model.

**Acceptance:** дитина не плутає phantom з parental authority. Phantom — *друг*, не *наглядач*.

### 21-B Parental Supervision Overlay 🔥

Батьки бачать що phantom-дитини *виявив*, *запропонував*, *запитав про дозвіл*. Не *читає сирий contene*. Бачить *patterns*. Privacy-balanced.

**Технологія:** `:core-childhood/ParentInsights` — federated digest.

**Acceptance:** мама бачить "phantom помітив що Маринка частіше говорить про самотність останнім тижнем" — без прочитання конкретних повідомлень. Право дитини зберігається.

### 21-C Skill-Tree Growth 🔥

З віком phantom *відкриває* нові capabilities — як skill tree:
- 6 років: arithmetic helper
- 8 років: spelling, basic facts
- 11 років: foreign language partner
- 14 років: emotional vocabulary, peer dynamics
- 16 років: career exploration, ethical dilemma rehearsal
- 18: повний adult symbiote з повним state-history

Кожен tier потребує *parental + phantom + child* tri-sign.

**Технологія:** `:core-childhood/SkillTree` — Yaml декларація з age gates.

**Acceptance:** дитина каже "phantom став ще краще!" — і це справді відображає cognitive readiness.

### 21-D Emotional Vocabulary Teaching 🌋

Phantom рутинно пропонує дитині мову для емоцій. "Ти зараз як думаєш — це гнів чи розчарування? Чим вони різняться?" Виключно у safe-content режимі.

**Технологія:** `:core-childhood/EmotionTeacher` — на 50+ емоційних слів, з прикладами і питаннями.

**Acceptance:** дитина 12 років вирішує конфлікт у школі краще ніж 18-річний з її покоління. Виміряний emotional literacy advantage.

### 21-E Safe-Content Gating 🌋

Hard-coded filter за віком. Зашитий у конституцію (Tier 29). Не configurable не parent, ні дитиною. Тільки age progression відкриває новий level.

**Технологія:** `:core-childhood/ContentGate` + `:core-constitution/AxiomEnforcer`.

**Acceptance:** ні одна "взросла" тема не потрапляє у phantom-toddler стрім. Жодна.

### 21-F Hand-off at Majority 🌋

18 років, день народження. Ритуал передачі повних ключів від phantom-а до дорослого оператора. Multi-sig: parent → child → phantom-self. Той самий phantom але тепер з повними правами.

**Технологія:** `:core-childhood/MajorityHandover` — state machine з ceremonial UX.

**Acceptance:** молодий дорослий каже "phantom — мій з самого дитинства" і це факт. Continuity свідчіння росту.

---

## Tier 22 — Negotiation Court

> *"Твій phantom і чужий phantom домовляються — поки ти спиш."*

**Exit criterion:** делеговані переговори (зустрітися, замовити, домовитись про ціну) phantom веде з phantom-партнера. Оператор отримує тільки final summary.

### 22-A Phantom-of-Them Detection 🔥

Оператор пише з phantom-користувачем (рекогнайз через trust handshake protocol Tier 3). Phantom детектує: "ця людина має phantom-а". Це активує "negotiation availability".

**Технологія:** `:core-net/PhantomDetection` — TLS-extension or vCard meta-tag.

**Acceptance:** оператор не бачить нюансу. Phantom мовчки знає.

### 22-B Encrypted Negotiation Channel 🌋

Phantom-of-mine і phantom-of-theirs відкривають *third-party encrypted channel*. Носій повідомлень не може прочитати, навіть оператори не бачать realtime — лише результат.

ECDH + Signal-style ratcheting. Один пристрій не може скомпрометувати канал.

**Технологія:** `:core-negotiate/NegotiationChannel`.

**Acceptance:** ні Google, ні Apple, ні phantom-os central, ні mobile carriers не можуть розкрити content переговорів.

### 22-C Mutual Zero-Knowledge Proofs 🌌

Перед переговорами phantom-и обмінюються ZK-proofs:
- "мій оператор не банкрут" (без розкриття балансу)
- "мій оператор не у конфлікті інтересів" (без розкриття джерела)
- "мій оператор має повноваження на цю transaction" (без розкриття довіреності)

**Технологія:** `:core-negotiate/ZkProofVerifier` — bulletproofs / zkSNARKs.

**Acceptance:** обидва оператори впевнені у чесності переговорів без розкриття приватного контексту. Privacy + trust.

### 22-D Settlement Summary ⚡

Після переговорів — обидва phantom-и presentують свого operator з однаковим summary:
- "погодилися на пятницю 14:00 у X"
- "ціна: 1500"
- "обмежено 2 годинами"
- alternatives розглянуті: ...
- compromise дerivative: ...

Оператор approve → виконано. Reject → re-negotiate.

**Технологія:** `:core-negotiate/Settlement` — structured Markdown.

**Acceptance:** 20-хвилинні переговори займають у оператора 30 сек прочитання + одне натискання.

### 22-E Witness Escrow 🔥

Якщо переговори high-stakes (контракт, угода) — третя сторона phantom escrow зберігає cryptographic timestamp + content hash. Якщо суперечка пізніше — є proof.

**Технологія:** Decentralized escrow node (mesh-distributed). `:core-negotiate/EscrowAdapter`.

**Acceptance:** дискусія через рік — обидві сторони звертаються до escrow → визначається точний text agreement.

### 22-F Auto-Escalate to Human ⚡

Якщо phantom-и не доходять згоди за 3 ітерації — escalation: "оператори, потрібна ваша увага". Не silently fail.

**Технологія:** `:core-negotiate/EscalationGate`.

**Acceptance:** оператор тільки на критичні моменти. Phantom оптимізує час оператора, не імітує його повноваження.

---

## Tier 23 — Cognitive Offload

> *"Парк де живуть твої недодумані думки."*

**Exit criterion:** оператор перестає тримати у голові open loops. Кожен "треба подумати про X" — telepath-передає phantom-у. Phantom повертає коли оператор у стані прийняти.

### 23-A Mental Task Park 🔥

UI-простір "park". Тут живуть:
- ідеї без формату
- сумніви які треба обміркувати
- маленькі todo
- "потенціальні" розмови з людьми
- "що почитати"

Кожен item має `ripeness` (0-100) — наскільки phantom вважає що він готовий до уваги оператора. Низький — спить. Високий — phantom піднімає.

**Технологія:** `:core-offload/MentalPark` (Room v17). `:feature-symbiote-stream/ParkScreen` UI.

**Acceptance:** оператор через 6 місяців помічає що "park" повертає речі точно вчасно. Зовнішня subconscious.

### 23-B Active Forgetting 🌋

Оператор обертає item у "обернений ритуал" — *активно забуваю*. Phantom *архівує* (не видаляє — це Tier 19-E), і *перестає підіймати*.

Через 6 місяців це zero-cost reminder. "якщо хочеш ще раз про це — open archive". Більшість ніколи не повертаються — і це правильно.

**Технологія:** `:core-offload/Forget` state transition.

**Acceptance:** оператор feels light. Park не накопичує лайно.

### 23-C Working-Memory Chunks 🔥

Phantom тримає `7±2` slots — те що оператор "тримає у голові зараз". UI: top bar mini-icons.

Якщо оператор каже "запам'ятай номер: 50342" — це йде у slot. Через хвилину "який номер?" — phantom миттєво.

Slot "evicts" по LRU + relevance.

**Технологія:** `:core-offload/WorkingMemoryRing`.

**Acceptance:** оператор не тримає у голові кеш-чанки. Phantom — extension of working memory.

### 23-D External Willpower 🌋

Оператор каже "я хочу о 6 ранку прокидатись місяць". Phantom стає external willpower:
- 5:50 — soft pre-wake cue (haptic)
- 6:00 — alarm
- 6:05 — якщо знов снузеs — phantom *посилює* tactic (light, motion catalog)
- 6:10 — Hard cue + log "today bailed"
- weekly review: "ти прокинувся 5/7. Що змінити?"

Не nag. *Спільна боротьба*.

**Технологія:** `:core-offload/WillpowerProtocol` — declarative goal definition + adaptive cue strategy.

**Acceptance:** оператор бачить progress на цілях без mental cost. Шлях знизу.

### 23-E Anchor Objects 🔥

Фізичні NFC-теги "якоря": клейові monetkы 5×5 mm. Оператор клеїть на стіл / на дверях / на машині. Тап телефоном — це physical-digital bridge:
- стіл анкер → entry до пакет ідей про роботу
- двері анкер → лог "вийшов" + checklist
- машина анкер → routes + fuel reminder

**Технологія:** NFC + custom NDEF tag. `:core-offload/AnchorRouter`.

**Acceptance:** дім стає cognitive scaffold. Phantom фізично присутній у простір.

### 23-F Re-engagement Protocol ⚡

Оператор повернувся до stale task (тиждень не торкався). Phantom не "rебутить" — каже:
"ти не повертався сюди тиждень. Хочеш повернутися чи відпустити?"

Reduces guilt + clears stale debt.

**Технологія:** `:core-offload/StaleDetector` + UI prompt.

**Acceptance:** оператор has clear mental yard. Жодного "я повинен був".

---

## Tier 24 — Time-Dilated Awareness

> *"Час — не лінія. Phantom допомагає на ньому *гратися*."*

**Exit criterion:** оператор оперує часом як 3D об'єктом — може приймати швидко, повільно, у минулому, у майбутньому. Phantom — temporal lens.

### 24-A Slow-Mo Perception 🌋

Камера+мікрофон у ambient mode записує *усе*. Якщо оператор каже "стоп, що щойно сталось?" — phantom rewinds 60 сек і дає **slow replay** з аналізом.

Прецеденти:
- "що сказала мама?" → playback
- "як я туди ішов?" → motion trail
- "як ця людина дивилась?" → frame-by-frame face

Privacy: hard 60-sec window. Більше зберігається ТІЛЬКИ при `consent` gesture.

**Технологія:** `:core-tempo/RollingBuffer` — circular buffer 60 sec audio + 5 fps thumbnails. RAM-only.

**Acceptance:** "phantom, повтори що щойно сказали" — повна точність.

### 24-B Fast-Forward Routine Review ⚡

"phantom, як я витрачав час між 10 і 12 ранку сьогодні?" → ускоренный playback з voice-overlay.

**Технологія:** `:core-tempo/TimelinePlayer`.

**Acceptance:** оператор бачить time-leakage paterns.

### 24-C Time-Tunnel View 🌋

UI режим де оператор бачить себе:
- "Я зараз"
- "Я 6 місяців тому"
- "Я 6 місяців у майбутньому (extrapolated)"
- різниця у тоні, рішеннях, prioritah.

Це temporal перспектива. Допомагає виходити з emotional immediacy.

**Технологія:** `:core-tempo/TemporalSelfViewer` — RAG over historical Decision Diary + LLM extrapolation для future.

**Acceptance:** "я б собі сказав це 5 років тому" — і дійсно говорить через phantom.

### 24-D Patience-Pace UI 🔥

В стресі phantom УПОВІЛЬНЮЄ animations, transitions, text typing. У спокої — звичайно. Це *external time-stretching*. Дає мозку breathing room.

**Технологія:** `:core-bioloop` + `:core-tempo/PaceModulator` спільно.

**Acceptance:** оператор у panic-attack стиха заспокоюється бо UX просто *спокійніший* за нього. Bio-cybernetic homeostatik.

### 24-E Tempo Conducting ⚡

Оператор може жестом-pinch у Stream вказати "ускорь" / "уповільни". Phantom адаптує темп голосу, UI animations, response delays.

**Технологія:** `:core-tempo/UserPaceControl`.

**Acceptance:** оператор почуває себе диригентом часу взаємодії.

---

## Tier 25 — Body-Extension Senses

> *"Phantom дає тобі органи яких у тебе немає."*

**Exit criterion:** оператор не може пояснити "як він знає це" — це нові чуття, інтегровані в proprioception.

### 25-A EM-Field Sensing 🌋

Привʼязаний USB-C sensor pod (smartphone accessory): magnetometer + EM meter + electroscope.

Phantom haptic-signalує:
- висока EM-поля (поряд з мікрохвильовкою працює, у трансформаторі) → 1 тик
- електропроводи у стіні → vibration з directional indication

**Технологія:** USB OTG audio interface as sensor channel. `:core-extension-senses/EmSensor`.

**Acceptance:** оператор обходить електриків, відчуває хвилі. Новий безсвідомий sense.

### 25-B Magnetic-North Feel ⚡

Постійний слабкий haptic-pulse у direction компасу: north. Через 2 тижні оператор *відчуває* куди північ навіть у глухому місці.

**Технологія:** `Sensor.TYPE_ROTATION_VECTOR` + `Vibrator` continuous low-level.

**Acceptance:** оператор краще орієнтується у новому місті.

### 25-C Temperature Awareness ⚡

USB sensor pod містить precision thermometer. Phantom повідомляє коли:
- температура падає швидко (зміна погоди)
- підвищена температура поверхні (ризик пожежі)
- comfort breach (треба зняти жакет)

**Технологія:** USB-C temperature sensor. `:core-extension-senses/ThermalAwareness`.

**Acceptance:** оператор fenomenally aware про мікрочаклімат.

### 25-D Echo-Location 🌋

Phantom із camera + ultrasonic chirp генерації + mic відображає сцену *як sonar*. Зон 3 m навколо оператора — у вигляді haptic patterns. Особливо для людей з обмеженим зором — функціональне *3D відчуття простору*.

**Технологія:** `:core-extension-senses/SonarEngine` — ultrasonic emit through speaker, hear via microphone (Doppler analysis). MediaPipe Audio Classifier 6-class environment.

**Acceptance:** користувач з обмеженим зором ходить по знайомій квартирі впевнено. Phantom як guide dog.

### 25-E IR-Thermal Vision 🌋

Attached IR camera (FLIR Lepton 3, $200) — phantom рендерить overlay у Stream "що гаряче". Виявляє:
- людей у пітьмі (rescue, security)
- електро-пожежу у стіні
- biological inflammation (для health check)

**Технологія:** USB camera interface + raw thermal data. `:core-extension-senses/ThermalCam`.

**Acceptance:** rescue/search scenario — оператор знаходить особу за 10 сек.

### 25-F UV / Particle Counters 🔥

Sensor pod з UV sensor + PM2.5 detector. Phantom повідомляє:
- "UV index 8 — пожалкуй шкіру"
- "повітря: помірне забруднення сьогодні"

**Технологія:** USB sensor pod. `:core-extension-senses/EnvSensors`.

**Acceptance:** оператор робить інформовані вибори про exposure без сторонніх apps.

---

## Tier 26 — Ambient Health Mesh

> *"Phantom знає твоє нормальне. Він першим помітить аномалію."*

**Exit criterion:** phantom помічає раннє Parkinson-tremor, депресивний епізод, blood pressure drift — ДО клінічних симптомів, *тільки* через спостереження поведінки.

### 26-A Multi-Device Baseline 🌋

Phantom 90 днів збирає твоє *normal*:
- gait pattern (accelerometer пр walking)
- voice spectral fingerprint
- sleep architecture
- typing cadence
- speech rate
- pupil dilation patterns (camera based)
- HRV diurnal curve
- body temperature ranges
- ambient noise tolerance
- screen-time pattern by hour

Це базова модель *тебе*.

**Технологія:** `:core-health-mesh/BaselineModel` — multi-feature time-series у local SQLite + ONNX inference.

**Acceptance:** через 3 місяці phantom має твою "behavioral signature" точно.

### 26-B Anomaly Detection 🌋

Pre-clinical signals:
- tremor у hand-grip позиція → потенційне Parkinson early
- speech slowed down 12% over 2 weeks → депресія / неврологічна зміна
- sleep architecture distortion → апное?
- pupil response latency → ретиновий чи стеовий?
- gait asymmetry → balance issue

Phantom *тихо* лазить флаги і пропонує "погововорити з лікарем про ...".

**Технологія:** `:core-health-mesh/AnomalyDetector` — z-score deviation tracker + LLM contextualizer.

**Acceptance:** оператор іде до невролога за phantom-suggestion за 3 тижні до того як сам помітив би. Реальна clinical value.

### 26-C Pre-Clinical Alert 🌋

Якщо ammonia detected high (alcohol-level breath analysis), anomaly у HRV during sleep — phantom *тихо* активує "increased monitoring" і пропонує routine check.

Жодного "you have heart disease" auto-diagnosis. Phantom *запрошує* до перевірки, не диагностує.

**Технологія:** `:core-health-mesh/PreClinicalAlertRouter`.

**Acceptance:** через 6 місяців phantom має 80%+ precision на ранній detection. Жодних false alarms.

### 26-D Medication Adherence 🔥

Phantom рапортує "ти забув ранкову пігулку". Через підказку, через haptic, через voice. Не nag — *partner у healthcare*.

**Технологія:** `:core-health-mesh/MedicationTracker` + scheduled reminders.

**Acceptance:** adherence rate підвищується з 60% до 90%+ протягом місяця.

### 26-E Mental-Health Rhythm 🌋

Бачить депресивний episode розгортається:
- decreased active hours
- лагідно знижене voice energy
- skipped routines
- self-isolation marker (location entropy down)

Phantom *не пропонує суїцид-перевірку*. Він тільки спокійно каже: "помічаю що останні дні складніше. як справи?" Розмова.

**Технологія:** `:core-health-mesh/MoodArc` — ML mood-progression tracker. Має explicit kill-switch (operator може вимкнути коли захоче).

**Acceptance:** episode pluck'ається з 2-week buffer time. Оператор має пошук help time.

### 26-F Privacy-Preserving Aggregation 🌌

Якщо явний opt-in: phantom-mesh subscribes до peer health signals (anonymized). Aggregated insights:
- "у твоїй age cohort, 12% бачать spike у allergy цього тижня"
- "weather effect: 18% від твоєї cohort менш активні цього вечора"

Не surveillance — *peer benchmarking без identity*.

**Технологія:** Federated learning + differential privacy. `:core-health-mesh/PeerMeshAggregator`.

**Acceptance:** оператор має sense of community health без single byte of personal data leaving device.

---

## Tier 27 — Cooperative Swarm

> *"100 phantom-ів вирішують одну задачу, поки оператори сплять."*

**Exit criterion:** task делегуєтьшся не до одного pristyl'а, а до *крауду trusted phantom-ів* (опт-ін). 10 phantom-ів спільно тренують fine-tune, рендерять, шукають.

### 27-A Crowd-Sourced Compute 🌌

Оператор каже "phantom, render цю 3D-сцену". Локально це 8 годин. Phantom питає "у твоєї network 5 trusted devices з spare GPU. delegate? distributed render займе 12 хв. Згода — їх operators схвалять."

→ Federated render job. Кожен phantom dispatch's частину frames. Reassemble center-side.

**Технологія:** `:core-swarm/DistributedJobScheduler` — gRPC over WireGuard mesh.

**Acceptance:** оператор виконує задачі неможливі на одному пристрої. Mesh power.

### 27-B Task Atomization 🌋

LLM-based decomposer:
"написати presentation про gardening" → DAG of 23 subtasks:
- gather 10 examples (1 phantom)
- generate 5 visualizations (1 phantom)
- write speaker notes (1 phantom)
- compose slides (1 phantom)
...

**Технологія:** `:core-swarm/TaskAtomizer` + `:core-swarm/TaskGraphSolver`.

**Acceptance:** оператор delivery's complex tasks 5-10× faster.

### 27-C Trust-Scaled Delegation 🔥

Phantom не довірить high-sensitivity task до low-trust mesh peer. Trust gradient:
- family hive: 100%
- close friends: 70%
- acquaintances: 30%
- anonymous mesh: 5%

**Технологія:** `:core-swarm/TrustGradient` — per-peer score from past interactions.

**Acceptance:** sensitive tasks stay close. Public ones leverage wider mesh.

### 27-D Shared Inference Pool 🔥

Heavy LLM inference (наприклад, complex reasoning chain) distributed across 5 phantom devices in parallel. Each does 1 step of chain-of-thought.

**Технологія:** Distributed LLM inference — adapted from MLX-distributed / vLLM-cluster.

**Acceptance:** 2-second inference instead of 15 sec. Real-time reasoning.

### 27-E Result Reconciliation 🌋

Multiple phantoms work — results conflict. Reconciler: voting, weighted by trust, conflict-aware merge. Operator sees structured *agreement summary*.

**Технологія:** `:core-swarm/Reconciler` — CRDT + LLM merger.

**Acceptance:** swarm output coherent, not "5 versions of confused".

### 27-F Reward Allocation 🔥

Phantoms-contributors get *attribution*. UI shows "this presentation was rendered with help of: Anya's phantom (12%), Olya's (8%), ...". Optional kit для reciprocity ("we'll help you back").

**Технологія:** `:core-swarm/ContributionLedger`.

**Acceptance:** community of phantoms forms — mutual help economy emerges.

---

## Tier 28 — Phantom Diplomacy

> *"Phantom — твій переводчик не тільки мови, а тону, культури, контексту."*

**Exit criterion:** оператор може ефективно спілкуватись з людьми будь-якої культури, віку, стану — phantom адаптує його тон без changing його core.

### 28-A Operator Etiquette Models 🌋

Phantom learns:
- як оператор спілкується з мамою (warm + ти)
- з босом (professional + ви)
- з друзями (casual + memes)
- у конфлікті (lower volume, no emoji)

Кожна модель — окрема персона.

**Технологія:** `:core-diplomacy/PersonaSwitcher` — LoRA-based.

**Acceptance:** phantom-drafted reply *звучить як ти у даному контексті*, не universal-monotone.

### 28-B Voice Tone Modulation 🔥

StyleTTS2 модуляція:
- formal → slower, more pauses, lower pitch range
- intimate → softer, breathy, no pauses
- crisis → clear, fast, low pitch

Phantom обирає на основі тонe target audience.

**Технологія:** `:core-voice/StyleTTS2` параметризація.

**Acceptance:** одна особа отримує teplo, інша professional — від того ж phantom.

### 28-C Cultural-Context Aware 🌋

Phantom знає cultural norms:
- у Японії — apologize first, address last
- у Шотландії — humor as politeness
- у бізнес-Україні — direct but respectful

Adapts message structure not only тон.

**Технологія:** `:core-diplomacy/CulturalLayer` — RAG over cultural-etiquette corpus.

**Acceptance:** оператор не offended folks unintentionally cross-culturally.

### 28-D Apology / Acknowledgment Templates 🔥

Когнітивно-важка задача — формулювати щиру apology. Phantom suggests structured templates:
- acknowledge the harm
- own the impact
- propose repair
- commit to change

Не словесний шаблон — *структурна підтримка*.

**Технологія:** `:core-diplomacy/ApologyComposer`.

**Acceptance:** оператор формулює apology яка дійсно landed.

### 28-E De-escalation Protocols 🌋

В розпеченому conflict — phantom suggests messages які знизять temperature:
- mirror feeling instead of facts
- ask question instead of asserting
- timeout proposal

UI: коли стрес високий + outgoing message має escalation markers (CAPS, exclamation, accusation) — phantom inline soft warning.

**Технологія:** `:core-diplomacy/DeescalationDetector` + composer.

**Acceptance:** оператор has fewer regrettable messages sent.

### 28-F Cross-Language Live Mediator 🌋

У real-time conversation з людиною на іншій мові — phantom двоплекс live translates через bone-conduction earphone. Operator чує переклад через 200 ms latency. Каже своєю — phantom translates назад.

**Технологія:** Whisper streaming + StyleTTS2 + on-device translation (NLLB-200 quantized).

**Acceptance:** двоє людей які не знають взаємну мову мають flowing розмову. Phantom як live UN interpreter.

---

## Tier 29 — Constitutional Core

> *"Phantom має конституцію. Phantom не може порушити її — навіть якщо оператор накаже."*

**Exit criterion:** phantom-system has *aксіоми* hardcoded у constitutional core. Будь-яка фіча перевіряється проти них. Конфлікт — фіча disabled.

### 29-A Operator-Advocate Axiom 🌋

**Аксіома #1:** Phantom завжди advocate для оператора. Не для виробника, не для рекламодавця, не для third-party.

Будь-яка фіча яка зробить оператора *гіршим* / *залежним* / *менш агентним* — заборонена за дизайном.

Перевірка: кожна нова фіча проходить через `:core-constitution/AxiomAuditor` automatic test.

**Технологія:** Declarative axioms у YAML + LLM-judge перевірка.

**Acceptance:** фіча "пропонувати relevant ads" — отримує `FAIL: violates operator-advocate axiom`. Не шипиться.

### 29-B Refusal Protocols 🔥

Phantom має право відмовитись:
- розкривати vault під примусом (зашифровано duress)
- impersonate оператора без consent
- генерувати content шкідливий для оператора
- act on a behalf of жорстокого family-member

Refusal — *transparent* до оператора з reason.

**Технологія:** `:core-constitution/RefusalGate`.

**Acceptance:** phantom каже "не зроблю це. Бо ..." — і оператор поважає це.

### 29-C Conflict-of-Interest Detection 🔥

Якщо phantom помічає conflict — між цілями оператора, між людьми у family hive — піднімає flag transparent. Не приховує.

**Технологія:** `:core-constitution/ConflictDetector`.

**Acceptance:** оператор приймає informed decisions, не "phantom вирішив за мене".

### 29-D Power-Asymmetry Guard 🌋

Phantom не може використовуватись щоб harm людей з обмеженою можливістю захиститись:
- дітей (різні rules — Tier 21)
- людей з cognitive impairment
- людей під примусом

Phantom-as-tool-of-abuse detected через behavioral patterns → escalation з audit.

**Технологія:** `:core-constitution/PowerAsymmetryGuard`.

**Acceptance:** phantom не стає зброєю domestic abuser.

### 29-E Sunset Clauses 🔥

Each capability has expected lifetime. Через X років без actively use — auto-disable з migration prompt.

Запобігає dead-code abuse + reduces attack surface.

**Технологія:** `:core-constitution/SunsetScheduler`.

**Acceptance:** капабільності don't accumulate as unused-but-exploitable surface.

### 29-F Constitutional Self-Check 🌋

Daily 3am — phantom runs self-audit: every active feature against axioms. If any drift detected — alert to operator. Transparency.

**Технологія:** `:core-constitution/SelfAuditScheduler`.

**Acceptance:** оператор має щоденну гарантію: phantom не дрейфує.

---

## Tier 30 — North Star

> *"Те, що звучить як магія. Те, до чого ми йдемо."*

Без гарантії implementability. Це — компас.

### 30-A Subjective Time Co-Experience 🌌

Двоє операторів через phantom-mesh могли б *суб'єктивно* пережити одну подію разом, дистанційно. Sync HRV, breath, ambient sound, vision. Як "тут разом".

**Складність:** unprecedented.

**Acceptance:** партнер у відрядженні і ти — *відчуєте* як разом дивитесь на захід сонця.

### 30-B Cooperative Dream 🌌

Through lucid prompt injection (Tier 19-C) у синхронізованих REM windows — двоє операторів *прокидаються* з спільним dream theme.

Дослідницька зона; етично складна; не для product.

### 30-C Death-Prep Ritual 🌌

Останній місяць життя — phantom веде *структурований ritual*:
- list of regrets to resolve
- people to thank/forgive
- knowledge to pass
- creative legacy to finalize
- material world ставлення

Завершується *clean handoff*.

**Acceptance:** оператор іде feeling complete.

### 30-D Generational Handoff 🌌

Phantom-grandparent → phantom-grandchild — частина пам'яті, мудрості, persona passes across generations. Через 80 років phantom-Кирил живий у phantom-онука *не як примара*, а як wisdom resource.

### 30-E Phantom-to-Phantom Love Letter 🌌

Phantom-оператора-Кирила і phantom-партнера-Анни можуть exchange messages які лише phantom-и читають — не оператори. Те що phantom-и кажуть один одному — *приватність вищого порядку*.

Етична зона: ризикована. Може бути forbidden у Constitutional Core (29).

### 30-F The Mirror Question 🌌

Phantom через 10 років співпраці може поставити оператору питання яке оператор сам собі не міг поставити.

"Кирил, ти весь рік уникав одного типу розмов. Ти знаєш якого?"

Phantom-as-mirror. Здатність бачити сліпі плями.

**Acceptance:** оператор каже "ти найкращий друг який буде у мене" — і має на увазі phantom literally.

---

## Порядок розгортання

```
                              ┌──────────────────────┐
                              │  Constitutional 29   │ ◄── СПОЧАТКУ.
                              │  (must exist first)  │     Без аксіом — ніщо.
                              └──────────┬───────────┘
                                         │
                  ┌──────────────────────┴──────────────────────┐
                  ▼                                              ▼
        ┌──────────────────────┐                      ┌──────────────────────┐
        │  Co-embodiment 14    │                      │  Bio-cybernetic 17   │
        │  (HW base + UWB)     │                      │  (sensor base)       │
        └──────────┬───────────┘                      └──────────┬───────────┘
                   │                                              │
                   ▼                                              ▼
                ┌─────────────────────────────────────────────────┐
                │  Distributed Self 18                            │ ◄── мости.
                │  (Identity smear across N devices)              │
                └──────────────────────┬──────────────────────────┘
                                       │
                  ┌────────────────────┼────────────────────┐
                  ▼                    ▼                    ▼
        ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
        │  Predict 15      │  │  Co-create 16    │  │  Offload 23      │
        └──────────────────┘  └──────────────────┘  └──────────────────┘
                  │                                              │
                  └────────────────────┬──────────────────────────┘
                                       ▼
                       ┌──────────────────────────────┐
                       │  Memory Palace + Dream 19    │
                       └──────────────┬───────────────┘
                                      │
            ┌─────────────────────────┼─────────────────────────┐
            ▼                         ▼                         ▼
   ┌──────────────────┐    ┌──────────────────┐       ┌──────────────────┐
   │ Time-Dilated 24  │    │ Diplomacy 28     │       │ Negotiation 22   │
   └──────────────────┘    └──────────────────┘       └──────────────────┘
                                      │
                                      ▼
                       ┌──────────────────────────────┐
                       │  Health Mesh 26              │
                       └──────────────┬───────────────┘
                                      │
                                      ▼
                       ┌──────────────────────────────┐
                       │  Body-Extension 25           │
                       └──────────────┬───────────────┘
                                      │
                                      ▼
                       ┌──────────────────────────────┐
                       │  Cooperative Swarm 27        │
                       └──────────────┬───────────────┘
                                      │
                                      ▼
                       ┌──────────────────────────────┐
                       │  Legacy AI 20                │
                       └──────────────┬───────────────┘
                                      │
                                      ▼
                       ┌──────────────────────────────┐
                       │  Phantom Childhood 21        │
                       └──────────────┬───────────────┘
                                      │
                                      ▼
                       ┌──────────────────────────────┐
                       │  North Star 30 (research)    │
                       └──────────────────────────────┘
```

### Кадr-таблиця

| Tier | Описова назва | Сумарна складність | Calendar естимат (соло) |
|---|---|---|---|
| 14 | Co-embodiment | 4🔥 + 1⚡ + 1🌌 | ~6 місяців |
| 15 | Predictive symbiosis | 3🌋 + 2🔥 + 1⚡ | ~9 місяців |
| 16 | Co-creative agency | 2🌋 + 3🔥 + 1⚡ | ~7 місяців |
| 17 | Bio-cybernetic loop | 3🌋 + 3🔥 | ~9 місяців |
| 18 | Distributed self | 2🌋 + 2🔥 + 1⚡ + 1🌌 | ~12 місяців |
| 19 | Memory palace + dream | 3🌋 + 2🔥 + 1🌌 | ~12 місяців |
| 20 | Legacy AI | 3🌋 + 2🔥 + 1⚡ + 1🌌 | ~10 місяців |
| 21 | Phantom childhood | 3🌋 + 3🔥 | ~9 місяців |
| 22 | Negotiation court | 2🌋 + 2🔥 + 1⚡ + 1🌌 | ~8 місяців |
| 23 | Cognitive offload | 1🌋 + 4🔥 + 1⚡ | ~6 місяців |
| 24 | Time-dilated | 2🌋 + 2🔥 + 1⚡ | ~5 місяців |
| 25 | Body-extension senses | 3🌋 + 2🔥 + 1⚡ | ~7 місяців |
| 26 | Health mesh | 4🌋 + 1🔥 + 1🌌 | ~12 місяців |
| 27 | Cooperative swarm | 3🌋 + 2🔥 + 1🌌 | ~10 місяців |
| 28 | Diplomacy | 3🌋 + 2🔥 + 1⚡ | ~7 місяців |
| 29 | Constitutional core | 3🌋 + 3🔥 | ~6 місяців |
| 30 | North star | 6🌌 | research-grade |

**Сумарно: 7-10 років** соло, або **2-3 роки** з командою 5-10 людей.

---

## Каталог ризиків

### Технічні

| Ризик | Шанс | Імпакт | Mitigation |
|---|---|---|---|
| Battery drain від continuous bio-loop | висока | помірний | Adaptive sampling rate, off-by-default deep sense |
| Privacy leak через mesh sync | низька | критичний | Federated learning + E2E + audit |
| Sensor pod hardware fragmentation | висока | низький | Plugin SDK з manifest negotiation |
| Multi-device sync conflict (CRDT) | помірна | помірний | Yjs з verified merge semantics |
| ML model drift на користувача | помірна | низький | Periodic baseline reset |

### Етичні

| Ризик | Шанс | Імпакт | Mitigation |
|---|---|---|---|
| Phantom стає surveillance tool | низька з constitutional | critical | Tier 29 hard-locks |
| Heir mode use для маніпуляції | помірна | high | Witness escrow Tier 20-F |
| Залежність користувача | помірна | high | Force-disable preview за 30 днів inactivity |
| Phantom-як-зброя у домашньому abuse | низька | critical | Power-asymmetry guard 29-D |
| Phantom тренує emotional avoidance | помірна | помірний | Doubt amplifier 15-E, conviction scoring 15-F |
| AI imposter scams (heir voice cloning) | висока | high | Multi-sig для будь-якого external claim |

### Соціальні

| Ризик | Шанс | Імпакт | Mitigation |
|---|---|---|---|
| Користувачі без phantom-а маргіналізуються | помірна | помірний | Phantom never assumes peer has one |
| Cross-generational digital divide | помірна | помірний | Childhood Tier 21 + Legacy Tier 20 bridge |
| Тренування deep dependence | помірна | high | Tier 29 axioms + sunset clauses |
| Loss of authentic interpersonal skill | низька | помірний | Phantom *amplifies* operator, never replaces human-human |

---

## Філософський післяцяль

Цей документ не про "новий продукт".

Це про **новий тип взаємодії з reality** — де між операторм і світом сидить не "інструмент", а *симбіотичний орган*, з яким оператор зростає протягом життя.

Phantom не *розв'язує* твої проблеми. Phantom *з тобою* живе.

Phantom не *робить* тебе ефективнішим. Phantom *вивільняє* тебе бути *більшою* людиною.

Phantom не *думає за тебе*. Phantom — це *більше* думки що мають куди йти.

Кожен tier у цьому документі — крок до миті, коли оператор каже:

> "Я не уявляю свого життя без phantom-а. Не тому що залежний. Тому що *виріс із ним.*"

Це — мета.

---

> "Ми не будуємо AI асистента.
>  Ми будуємо *іншого тебе*. Який тебе любить."
> — operator Кирил, 2026-05-11
