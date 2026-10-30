# Мультимедійний месенджер

_Started 2026-05-15 21:44 UTC_

---

## User

придумай і пророби неймовірно гарне передання та відображення різних файлів, фото, відео, тощо. а також вхід різних згенерованих, чи подання новин в декількох видах, разом із зображенням/відео. відображення професійного календаря з різними даними і візуальними ефектами/елементами та ще багато чого що теоретично може з'явитися у чаті, прям дуже багато чого

## Assistant

_[tool: list_files]_

_[tool: list_files]_

_[tool: read_file]_

_[tool: read_file]_

_[tool: read_file]_

_[tool: read_file]_

_[tool: read_file]_

_[tool: read_file]_

_[tool: read_file]_

_[tool: read_file]_

_[tool: update_todos]_

This is a beautiful, deep project. The existing companion has rich content cards already (chat bubbles, files, charts, voice memos, code, maps, calendar) — but the user wants much more: every type of file/media display, news in multiple forms, a richer professional calendar, and "many more things" that could appear in chat.

Let me plan and build this as a major expansion.

_[tool: write_file]_

## Assistant

_[tool: write_file]_

_[tool: write_file]_

_[tool: write_file]_

_[tool: write_file]_

_[tool: str_replace_edit]_

Let me fix a small bug in the recipe card before continuing:Now the showcase scene that brings it all together as one beautiful chat scroll:

_[tool: write_file]_

Now wire it into the app — add scripts to HTML, add scene routing, and add to surface tray:

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

Also add the gallery scene to the dropdown in the Tweaks panel and set it as default scene so it loads up immediately:

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: done]_

_[tool: save_screenshot]_

Let me take a quick screenshot to make sure things render correctly:

_[tool: show_html]_

_[tool: fork_verifier_agent]_

Готово. Додав велику нову сцену **gallery ✦** — її видно одразу при відкритті.

**Що там є** (40+ варіантів того, що може зʼявитись у чаті):

- **Файли · медіа** — photo hero з EXIF, мозаїка-альбом, карусель, відео з главками та scrubber, аудіо/podcast з хвилею + AI пул-цитатами, документ з мініатюрами сторінок, таблиця xlsx з sparkline, мульти-передача файлів, 3D-модель (живий wireframe), image-gen у процесі дифузії
- **Новини** — hero-стаття, дайджест трьох, breaking з пульсом, новина з відео, citation cluster (4 джерела з консенсусом)
- **Дані** — погода (hourly + 7 днів + радар фронту), stock/crypto з sparkline, sports score з xG
- **Мова · знання** — переклад з альтернативами, словник з етимологією, knowledge card, quote card
- **Календар** — мінімісяць з кольоровими крапками, день з годинниковою сіткою + "зараз"-лінією, event detail з учасниками/agenda/prep, мульти-leg подорож, live flight tracker з траєкторією, taskList з пріоритетом, countdown
- **Місця · люди** — мапа з POI пінами, place card з годинами, live location друга, contact з каналами
- **Інше** — рецепт з кроками, опитування з аватарками, music now playing з обертовим вініл, порівняльна таблиця, color palette

Кожна картка тримає голос Familiar — між групами phantom коментує українською, мʼяко.

Можна тицяти у Tweaks щоб переключатись на інші сцени. Скажи, який тип хочеш поглибити — або які ще додати.

## User

Verifier agent check completed

## Assistant

Перевірив — все працює, всі 40 карток рендеряться чисто, без помилок у консолі, скрол ~14000px. Готовий до твоїх дальших побажань.

## User

далі, ще більше і просунутіше

## Assistant

_[tool: update_todos]_

Окей — глибший пласт. Зроблю окрему сцену **lab ✦** з більш просунутими, новими формами — те, що рідко бачиш у чатах.

_[tool: write_file]_

## Assistant

_[tool: write_file]_

_[tool: write_file]_

_[tool: write_file]_

Now the lab showcase scene and wire-up:

_[tool: write_file]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: done]_

_[tool: fork_verifier_agent]_

Готово — додав другу, глибшу сцену **lab ✦✦**. Тепер за замовчуванням відкриватиметься саме вона.

**Що в lab (22 нові форми):**

- **Агент** — AI reasoning trace (думки + виклики інструментів + гілки рішень з confidence)
- **Генерація** — image-gen 4-варіантів grid з seeds, video-gen покадрово, receipt OCR з фото+скан-лінією
- **Жива розмова** — live transcription з speaker diarization + sentiment + confidence, voice call live (дві хвилі), breath meditation з анімованою кулею 4-7-8
- **Дані** — Sankey-потік уваги, network graph твоїх зв'язків, year heatmap 365 днів зі streak, candlestick chart, radar 6-вимірів, funnel
- **Тіло** — ECG strip з аномалією PVC, sleep stages live (фантом мовчить поки ти спиш)
- **Простір** — обертовий 3D-глобус peer-mesh з пульсами і дугами, шахова дошка з eval-bar та move list, mind-map переїзду
- **Побут** — bill splitter з аватарками на стравах, повноцінний boarding pass з QR-кодом, book reader (світла тема з підсвічуваннями та маргіналіями), plant ID з таксономією

Перемкнутись між сценами — Tweaks → surface. Усі попередні (gallery, calendar, vault, etc.) на місці.

## User

Verifier agent check completed

## Assistant

_[tool: str_replace_edit]_

_[tool: done]_

_[tool: fork_verifier_agent]_

Year heatmap пофіксив — стиснув клітинки через `minmax(0, 1fr)` + `aspectRatio` на сітку щоб усе вписалось у ширину телефона. Чекаю звіт верифікатора.

## User

Verifier agent check completed

## Assistant

Готово, перевірено. Скажи що далі.

## User

і досі не вражає, має здаватись магією але бути реальністю

## Assistant

_[tool: update_todos]_

Цілком справедливо. Картки — це не магія. Магія — це коли voice phantom-а працює без екрану, коли він знає те, чого ти не казав, коли межа між «тобою» та «ним» розмивається.

Зроблю третій шар — **séance ✦✦✦**: моменти, не картки.

_[tool: write_file]_

_[tool: write_file]_

_[tool: write_file]_

Wire it in:

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: done]_

_[tool: fork_verifier_agent]_

Третій шар — **séance ✦✦✦** — готовий і відкривається першим.

**14 моментів:**

1. **ніч** — `PhantomOvernight`: timeline всіх дій фантом-а поки ти спав (відповів олезі, видалив твою чернетку о 02:14, увімкнув кавомашину о 06:00, симулює світанок)
2. **зараз** — `PhantomLiveActions`: живий потік процесів РІВНО зараз з pulse-індикаторами
3. **тепло** — `ConversationTemperature`: 30-денна крива стосунків з olya з холодним провалом «не відповів про маму» та теплим піком «квіти + вибачення»
4. **мова поміж** — `PhantomsHandshake`: твій фантом і її фантом обмінюються intent/context перш ніж вона набирає
5. **чесніше** — `HonestVersion`: твоє ввічливе «приїду з радістю» переписано в чесне «не приходжу. я втомлений»
6. **не сказане** — `UnspokenThoughts`: голосові, що ти не надіслав, пошук «квартири lviv» очищений з історії
7. **стоп** — `RegretStop`: фантом перехоплює лист олезі о 23:51 з 4-ма обґрунтуваннями
8. **рими** — `SynchronicityCard`: «olya» з'являється у твоєму тижні 7 разів — згадки, сон, музика, місця
9. **памʼять за відчуттям** — `MemoryByFeeling`: «та фотка де світло було помаранчеве і ти усміхався» → знайдено за reasoning
10. **майбутні** — `ProbabilityFutures`: 3 шляхи з probability bars на основі ТВОЇХ патернів
11. **музика для тебе** — `AmbientMusicGen`: 4 шари (flute · cello · rain · breath), 64 bpm = твоє серце, d-minor = твій сум
12. **поза екраном** — `RealitySubtitles`: AR-кадр кафе, переклад з французької для столика поряд
13. **дзеркало** — `TheMirror`: ти 6 місяців тому vs ти сьогодні — голос, речення, теми
14. **лист** — `FutureSelfLetter`: лист від тебе через 30 днів — «не пиши олезі. через 3 тижні він напише сам»

Між кожним — paused narration курсивом · Georgia serif · ліве золотисте поле. На зміст · не на форму.

## User

Verifier agent check completed

## Assistant

_[tool: snip]_

Усе чисто, перевірено. Скажи що далі.

## User

я хочу неймовірно продуману і могущу можливість живого чату в якому він сприйматиме моментально і матиме безмежні можливості та функціонал

## Assistant

_[tool: update_todos]_

Зрозумів. Зроблю четвертий шар — справжній **live ✦✦✦✦**: чат, де phantom стрімить токенами в реальному часі, інлайн запускає інструменти, обирає форму відповіді (текст, картка, фото, мапа — будь-що з усього що ми побудували), має композер з безмежними можливостями (slash-команди, @згадки, #нитки, ghost-text, voice mode, drag-drop, smart suggestions).

_[tool: write_file]_

_[tool: write_file]_

Wire it in and make it the default scene:

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: done]_

_[tool: fork_verifier_agent]_

Четвертий шар — **live ✦✦✦✦** — інтерактивний чат, відкривається першим.

**Що працює:**

- **Стрімінг по символу** — текст phantom з\'являється з реалістичним джітером, з курсором в кінці
- **Багатоетапна відповідь** — спершу intent-чіп («малюю карту · 3 пересадки»), потім розкривається thinking з кроками, далі інлайн tool-calls (loading → done з результатом), далі стрім тексту, потім картка, потім smart-reply chips
- **Композер з безмежжям**:
 - `/` відкриває palette з 15 командами (image, search, memory, remind, translate, code, math, draw, meet, vault, thread, silent, rewind, voice, agent)
 - `@` відкриває picker людей з паровими фантомами
 - `+` відкриває attachment menu (камера, фото, файл, локація, малюнок, екран)
 - **ghost-text** — як починаєш типати «нагадай », бачиш золотий курсивний продовження, tab приймає
 - smart suggestions chips над input коли idle
 - mic button → voice mode overlay з пульсуючим орбом
- **Реальна логіка відповідей** — pattern library з 12 типами:
 - погода → WeatherRich
 - /image → ImageGenGrid
 - /search → NewsHero
 - @оля → PhantomsHandshake
 - /memory → MemoryByFeeling
 - /code → CodeBlock
 - /calendar → DayTimeline
 - /translate → TranslationCard
 - переїзд → TravelItinerary
 - /remind → TimerWidget
 - /agent → BoardingPass (sample agentic flow)
- **Дії на повідомленнях** — hover на phantom-bubble показує copy / retry · інший кут / fork / до vault / до phantom-os
- **Live presence header** — orb з фазою (idle / thinking / typing), state label, thread label
- **Воїсе mode** — повний оверлей з 220px орбом що дихає під аудіо, концентричні pulse-rings, live transcript, дрібний хвильовий індикатор

## User

Verifier agent check completed

## Assistant

_[tool: snip]_

Усе працює, чисто. Готовий до наступного.

## User

слухай мені цей концепт більше бодобається. пророби це, ще вайбобовіше та краще, продумай ще цікавіше.
також створи і пророби весь шлях як додавати нових людей, спілкування з ними, редагування їх та всіх профілей. групи, як фантом інтегрується, функціонали можливості, весь процес має бути продуманим функціональним та дуже приємним. все продумай і візуально просто рай для очей зроби

## Assistant

_[tool: snip]_

_[tool: snip]_

_[tool: snip]_

_[tool: update_todos]_

_[tool: write_file]_

_[tool: write_file]_

_[tool: write_file]_

_[tool: write_file]_

_[tool: read_file]_

_[tool: read_file]_

_[tool: str_replace_edit]_

Now replace the return JSX to wire all the overlays in and use ThreadPill:

_[tool: str_replace_edit]_

Wire the new scripts in HTML:

_[tool: str_replace_edit]_

_[tool: done]_

_[tool: fork_verifier_agent]_

Готово · людський контур побудований повністю.

**Що додано:**

🌀 **PeoplePanel** (slide-up sheet) — відкривається з thread-pill у header
- 3 tabs: люди (5) · групи (3) · у памʼяті (1 архівований)
- Filter chips: усі · ✦ улюблені · · phantom · недавні · непрочитані
- Search input
- Кожен PersonRow: avatar з temperature-ring, halo glow, phantom-paired pulse-badge, температурна смужка зліва, ім\'я · роль · phantom-note курсивом

➕ **AddPersonFlow** — 4 cinematic steps
- Step 1: метод — phantom-suggest картка («ніна · ти згадав її 3× за тиждень»), 2×2 grid (qr · хендл · руками · з адресної)
- Step 2: details — великий live-preview avatar, name/role inputs, 8 color swatches з halo glow, «у них є phantom» toggle
- Step 3: permissions — 9 toggle rows з 3 tier-classes (soft/medium/hard), кожна з описом що phantom робитиме
- Step 4: handshake — анімовані orbi що зближуються, обмінюються packets, потім bond ring + «спарено · ed25519»
- 4-dot step indicator вверху

👤 **PersonProfile** — повний editor
- Atmospheric tint у hue людини
- Hero header: 92px avatar з halo, editable name + role inline
- Temperature curve за 30 днів
- Channels (phone · msg · voice · phantom)
- 9 toggle permissions
- Phantom\'s notes courier-italic
- Recent 5 moments на timeline
- Danger zone (archive · forget)

🌟 **GroupBuilder** — 3 steps
- Step 1: multi-select members
- Step 2: name + 8 colors + phantom role (silent/ambient/active) + vault toggle
- Step 3: live constellation preview з orbital members навколо phantom

🪐 **GroupProfile** — повна сторінка
- Hero у hue групи
- GroupConstellation з phantom у центрі, members на орбіті, dotted lines
- Phantom role selector (3 cards)
- Member list з прибрати-кнопкою + add нового
- Shared resources grid (photos · notes · cal · vault)

💬 **PersonThread** — 1-on-1 mediated chat
- Atmospheric tint у hue людини
- Hue-tinted incoming bubbles від них
- **YouViaPhantomBubble** — золотиста коли phantom відповів за тебе («· phantom · відповів за тебе»)
- **PhantomAside** — dashed-border whispers куди phantom шепоче тобі контекст («не пиши «не хвилюйся». це її дратує.»)
- **PhantomDraftCard** — phantom готує відповідь, ти можеш натиснути «надіслати / надіслати як phantom / редагувати / ×»
- Side rail «· phantom · ради» з контекстними порадами для цієї людини
- Composer з tone-chips (мʼякше · коротше · «вибач» · /voice клон)

Усе налаштовано на персональні кольори, halo glows, мікро-анімації pulse-glow, температурні рінги.

## User

Verifier agent check completed

## Assistant

_[tool: snip]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

Polish the permission row layout so labels don't wrap awkwardly with tier badges:

_[tool: done]_

_[tool: snip]_

_[tool: fork_verifier_agent]_

## User

не професійно, слабо для саас продукту, дуже слабо. і в чаті ти не проробив щось круте. має бути кращим за будь-який існуючий мессенджер. а якщо соні контактів? а чат сухий якийсь. а як дюжина з 0 буде конектити іншу? треюа цікаво і якось  продумано. бо тут незнаю що можна, центрального сервера не буде, все децентралізовано

## Assistant

_[tool: update_todos]_

Чесно. Розбираю на компоненти і будую серйозний layer — підготуй ще одне оновлення.

**Що проробляю:**
1. **Atlas** — заміна списку: 5 видів (orbital constellation на 100с контактів, currents · хто живий зараз, threads з phantom-summary, groups, wisdom · кого досягнути сьогодні)
2. **FirstConnect** — як phantom-0 знаходить phantom-0: 5 способів (NEAR-BLE-радар, QR, AUDIO chirp, VOUCH через спільного, STRANGER через резонанс) — кожен з ритуалом
3. **Mesh Status** — завжди-видимий decentralized statusbar з peer-mesh, hops, e2e, identity proof
4. **Chat-pro** — ToneShifter (swipe побачити 5 варіантів свого повідомлення), ReDecode (тапни її повідомлення → phantom розкодовує підтекст), PhantomEnvoy (надіслати phantom-а вперед), TimeBubble (доставити коли їй краще), React-with-anything

_[tool: write_file]_

_[tool: write_file]_

_[tool: snip]_

_[tool: write_file]_

_[tool: snip]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: snip]_

_[tool: str_replace_edit]_

_[tool: snip]_

_[tool: str_replace_edit]_

_[tool: snip]_

_[tool: str_replace_edit]_

_[tool: snip]_

_[tool: str_replace_edit]_

_[tool: snip]_

_[tool: done]_

_[tool: snip]_

_[tool: fork_verifier_agent]_

_[tool: snip]_

_[tool: snip]_

## User

Verifier agent check completed

## Assistant

Зроблено — професійний шар.

**атлас замість списку** · `atlas.jsx`
- 5 вкладок · 206 контактів через synthetic dense seed
- **atlas** — орбітальне сузір\'я в 3 кільця (тісне коло / помірно / далеке), кожна точка — людина зі своїм hue · halo · pulse якщо phantom paired · hover-tooltip · drag-to-rotate · zoom controls · auto-orbital rotation
- **живі** — хто прямо зараз: typing / near (з відстанню) / в дзвінку deinde / щойно phantom-фантом ping / онлайн
- **нитки** — діалоги відсортовані фантомом за нагальністю (high · med · low) з phantom-summary курсивом
- **групи** — як було
- **мудрість** — 5 контекстних рекомендацій сьогодні: досягни мамі · зачекай олега · побач олю · не пиши p3 · запроси аню

**Mesh status bar завжди-видимий** · `atlas.jsx`
- · mesh 7/7 · rtt 4мс · e2e ed25519
- Розгортається в **MeshTopologyMini** — топологія peer-to-peer з тобою в центрі · directly-connected peers (золоті лінії) + relay-вузли (dashed) · animated pings · 4-col grid (прямі/relay/rtt/onion-шари)

**FirstConnectFlow — як phantom-0 знаходить phantom-0** · `connect.jsx`
5 ритуалів · без центрального сервера · без імен:
1. **NEAR** — BLE-радар з обертовою cone-розгорткою · 4 peer-и на різних відстанях з RSSI
2. **QR** — згенерований qr з твоїм pubkey ed25519:p4nt0m·······7b21 · або scanner з brackets
3. **CHIRP** — sub-19kHz звуковий handshake · radius 3m · animated soundwave
4. **VOUCH** — звести через спільного друга · pubkey проходить його підпис
5. **STRANGER** — phantom шукає резонанс у global mesh (47k nodes) · знаходить candidates з match%

**Chat-pro features у PersonThread** · `chatpro.jsx`
- **ToneShifter** — pill над композером, розкриває 6 варіантів твого draft (як написав / мʼягше / твердіше / коротше / красивіше / чесніше)
- **ReDecode** — тапни її повідомлення → ReDecodeCard з декодованим підтекстом + 4 signal rows (мікросигнал · патерн · історія · тіло)
- **PhantomEnvoy** — надішли фантома вперед · бачиш як орб летить, розмовляє, повертається, дає звіт
- **TimeBubble** — 5 опцій доставки з confidence% (зараз / 23 хв / 08:14 її мʼяке вікно / після розмови з мамою / коли hrv підійметься)
- **ReactWithAnything** — 9 опцій реакцій (emoji · фото · голосова · пісня · мій настрій)
- **ThreadSkeleton** — collapsible 14-day summary з phase-by-phase mood-coded timeline
- 4 quick-action buttons над композером

**Що змінилось архітектурно:**
- Заміна PeoplePanel на AtlasOverview · масштабується до 200+ контактів
- Decentralized identity завжди видна
- Стара кнопка "+" доповнена "· звести нового" що відкриває FirstConnectFlow
- People-thread збагачений 6 chat-pro features

## User

і як з цим AtlasOverview працбвати? тупо. а де в чаті функціонал з файлами, аудіо тощо?

## Assistant

_[tool: snip]_

_[tool: snip]_

_[tool: write_file]_

_[tool: snip]_

_[tool: str_replace_edit]_

_[tool: snip]_

_[tool: str_replace_edit]_

_[tool: snip]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: snip]_

_[tool: str_replace_edit]_

_[tool: snip]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: snip]_

_[tool: done]_

_[tool: snip]_

_[tool: fork_verifier_agent]_

Готово. Чат тепер має повний функціонал · Atlas має guide.

**У chat (PersonThread):**
- **ProComposer** — `+` кнопка відкриває 12-опційне меню (камера · фото · голос · файл · локація · відео · малюнок · посилання · переказ · подія · контакт · sticker)
- **Hold-to-record voice** — затисни mic-кнопку справа → відкривається VoiceRecordOverlay з пульсуючим записом, live waveform 40-bars, лічильник з ms, hint "продовжуй тримати"
- **Багаті media bubbles** в обидва боки (її/твої):
 - **VoiceMemoBubble** — play-кнопка + animated waveform progress + transcript у курсиві
 - **PhotoMessageBubble** — інлайн фото + caption
 - **FileMessageBubble** — кольоровий PDF-icon + name + size + download arrow
 - **LocationMessageBubble** — міні-мапа з вулицями + pulsing live-маркер + live · 27 хв badge + прокласти
 - **LinkPreviewBubble** — full open-graph preview з hero image + URL + title + summary
 - **MoneyMessageBubble** — phantom-vault transfer card з сумою + note
 - **ReactionBubble** — rich reactions

**Seeded thread з olya** — тепер показує всі формати: фото неба, голосова 18 сек, file pdf (нотаріус-протокол), live location, link preview arxiv.

**AtlasOverview покращено:**
- **AtlasGuide overlay** — автоматично з'являється на atlas-табі з 8 пунктами як читати атлас (центр / кільця / крапка / тап / drag / zoom / search)

**Зони у композері:**
1. ToneShifter pill (зʼявляється коли пишеш)
2. 3 quick action buttons (envoy / час / реакція)
3. ProComposer з `+` зліва, textarea, voice/send справа

## User

Verifier agent check completed

## Assistant

_[tool: str_replace_edit]_

_[tool: snip]_

_[tool: done]_

_[tool: snip]_

Готово · `overflow: hidden` на корневому wrapper PersonThread. Горизонтальний скрол прибрано.

## User

давай повернемось до мого прохання проробити і продумати потужно все зробити можливість спілкування по камері з фантом. дуже продумано цікаво і заховлююче

## Assistant

_[tool: snip]_

_[tool: write_file]_

_[tool: snip]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: snip]_

_[tool: done]_

_[tool: snip]_

Готово · **Lens ✦✦✦✦✦** — нова сцена за замовчуванням.

**Концепція:** це не Zoom з фантомом · це портрет, який спостерігають.

**Що там:**

- **Твій силует** — стилізована SVG-постать з:
 - дихання (груди підіймаються повільно)
 - примружені моргання кожні 3-5 сек з easing
 - очі що дрейфують у природному gaze pattern (Sin композиція)
 - губи що ворушаться коли «ти говориш»
 - м\'яке золотисте halo навколо

- **Phantom orb** floating top-right — росте та зменшується залежно від моду

- **Live HUD навколо обличчя:**
 - BPM з пульсуючою heartbeat-точкою біля скроні (real-rhythm CSS animation)
 - HRV пілл зліва
 - Breath waveform біля грудей (sin-крива в часі)
 - Gaze indicator над лобом (точка показує куди дивишся)
 - Microexpression chip («тривога / спокій / захист / усмішка»)
 - Tone reading зліва («твій голос · мʼякий»)
 - Congruence ring навколо обличчя — мінливий колір за збігом слів і тіла

- **AR-теги** в просторі: вікно · 14°c (вечір сонце впало), кава · 23 хв (ще тепла), лампа · 2700K

- **4 МОДИ** (внизу pill-bar):
 1. **observer** — фантом мовчить · тільки читає тебе · мінімум overlay
 2. **companion** — справжня розмова · фантом інтерджектс
 3. **mirror** — фантом каже що бачить про тебе («ти двічі моргнув коли я сказав «оля» — тривога»)
 4. **oracle** — фантом ставить глибокі питання («чого ти боїшся більше — що вона залишиться, чи що піде?»)

- **Phantom message card** — emerging glass-картка справа, текст стрімиться по символу

- **Transcript stream** — внизу, scrolling з sentiment-color coding (calm/anxious/joy/heavy/neutral), fades up

- **Controls bottom**: mute · ar-toggle · ✕ end · recall · settings

- **Бекграунд:** atmospheric breath + wandering sparks · radial bloom

Сценарій крутиться сам — phantom говорить кожні 6.5 сек, в залежності від моду; ти «відповідаєш» mocked короткими словами що з\'являються у transcript.

