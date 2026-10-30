# Перепис ПК-месенджера: що обіцяло і що робило (24.09)

Той самий принцип, що вже пройшов телефон: **кожен елемент — обіцянка →
реальність → доказ**, і все, що обіцяє більше, ніж робить, **видаляється**, а
не ховається прапорцем. На телефоні театр відсікали підміною модулів
(`PHONE_SWAPS` у `vite.config.ts`: `ModalHost.phone.tsx`,
`AISynthesisStudioModal.phone.tsx`, `aiSynthesisStore.phone.ts` → `null`); на
ПК ті самі модулі жили й відкривались. Тепер їх немає в дереві взагалі — тож і
підміни телефона стали непотрібні й пішли разом із ними.

Номери рядків — на `origin/main` `b88e846` (до правки), шляхи — відносно
`phantom-os/src/frontend/src/`. «net» — кількість викликів вузла/мережі
(`fetch(`, `messengerApi`, `apiClient`, `wsClient`, `invoke(`) у файлі.

Сторож, що червоніє, якщо театр повернеться:
`__tests__/pcMessengerSubtraction.test.tsx` (монтує `MessengerRoot`, як його
монтує застосунок). Поруч оновлено `sidebarFolderLinkPhone.test.tsx` і стелю
сиріт у `reachFromEntry.test.ts` (250 → 246).

---

## 1. Шапка розмови — `components/messenger/Header.tsx`

| Елемент | Обіцяв | Робив насправді | Доказ | Рішення |
|---|---|---|---|---|
| Пігулка фокус-режиму (`FocusModeSelector`) | «Глибокий фокус — тільки VIP-пінги», «Async Only — пакетна доставка раз на годину», «статус видно команді» | Кладе рядок у `useState` `MessengerRoot`, якого не читає ніхто: ні сповіщення, ні доставка, ні статус | `FocusModeSelector.tsx:46`, `MessengerRoot.tsx:112,557` (єдині читачі `focusMode`) | **Видалено** разом із типами `FocusMode*` |
| Іскра «Omni-search» | «Семантичний пошук рішень та документів» | `KnowledgeSearchModal` фільтрує 8 вписаних у код «результатів» (Кирило, Саня, `phantom_companion_architecture_spec_v2.pdf`) | `KnowledgeSearchModal.tsx:44` | **Видалено** |
| Кнопка «AI Studio» | «когнітивний полігон PHANTOM AI Studio» | `sendMessage` — `setTimeout` і відповідь за регуляркою, без виклику моделі; «Круглий стіл» — готові репліки з літеральним `confidenceScore` | `stores/aiSynthesisStore.ts:831-835`, `:505` | **Видалено** студію цілком (`components/ai-studio/**`, `aiSynthesisStore*`) |
| Меню ⋮ → «Контекстні ролі (Scopes)» | керування ролями | Лише перегляд складу; той самий склад і щаблі — у картці групи (`GroupDetailsDrawer` → `GroupRoles`) | `RoleScopesModal.tsx:9-25`, `GroupDetailsDrawer.tsx:26,517,570` | **Видалено** як дубль |
| ⋮ → «Git-Native DevOps & PR» | PR, CI, коміти | Засіяні PR; «Retry» через 1,5 с ставить `Passed` без жодного запуску; «поділитись» шле вигаданий diff | `GitNativeDevOpsModal.tsx:47,76,109` | **Видалено** |
| ⋮ → «Side-by-Side Diff & Hex» | інспектор diff/hex | Один вписаний diff `wireProtocol.ts` і масив байтів; «поділитись» шле його в розмову | `CodeDiffMathHexInspectorModal.tsx:29,40,48` | **Видалено** |
| ⋮ → «Сховище Workspace Drive» | «Хмарне & Локальне сховище» | Посів `INITIAL_DRIVE` у `localStorage`, жодного файла з вузла | `stores/workOsStore.ts:100,187` | **Видалено** |
| ⋮ → «Shared Terminal & Runner» | спільний термінал, виконання | Нічого не виконує; шле блок коду типом `code`, який іде `messengerNetworkEngine` каналом `messenger`, якого на вузлі немає | `LiveTerminalModal.tsx:67-78`, `stores/messengerStore.ts:1942-1965` | **Видалено** (блок коду шле й сам композер) |
| ⋮ → «Вхідні Webhook-хаби (CI/CD)» | вхідні вебхуки | Генерує адресу на `try.phantom-os.dev`, якої вузол не обслуговує; прийому немає | `WebhooksManagerModal.tsx:37,67` | **Видалено** |
| ⋮ → «P2P Swarm роздача» | BitTorrent-подібна роздача | `seedSwarmFile` лише додає запис у `localStorage`-стор; жодного байта не передається | `stores/meshStore.ts:210-220`, `P2PFileSwarmModal.tsx:22` | **Видалено** |
| ⋮ → «Project Memory Graph» | семантична памʼять проєкту | Засіяні вузли `INITIAL_MEMORY` | `stores/agenticStore.ts:37,70` | **Видалено** |
| ⋮ → «Скарбниця (Multi-sig Vault)» | мультипідпис, голосування коштами | Засіяні пропозиції в `useState` | `SpaceVaultModal.tsx:37` | **Видалено** |
| ⋮ → «Моніторинг вузла» | моніторинг | Після чистки 06.09 — підмножина діагностики каналу, яку повніше показує «Мережевий зв'язок»; «вітали» бере лише з моста телефона, на ПК порожньо | `NodeDashboardModal.tsx:18-21,70`, `P2PNetworkModal.tsx:45,67` | **Видалено** як дубль |
| ⋮ → «Автоматизації (IFTTT & Cron)» | правила, cron | Засіяні правила, жодного планувальника | `AutomationPipelineModal.tsx:40` | **Видалено** |
| ⋮ → «Реляційна база (Data Grid)» | реляційна таблиця | Посів задач у `localStorage` (`workOsStore`) | `RelationalDataGridModal.tsx:30`, `stores/workOsStore.ts:73` | **Видалено** |
| ⋮ → «CRDT Time Machine (Снапшоти)» | CRDT-знімки й відкат | Масив-константа знімків, «відкат» — `setTimeout` | `TimeMachineSnapshotModal.tsx:35,70` | **Видалено** |
| ⋮ → «IoT Сенсори & YubiKey» | апаратні ключі, телеметрія | Після чистки — перелік без джерела; FIDO2 не опитується | `IoTEqsTelemetryModal.tsx:15,132` | **Видалено** |
| ⋮ → «Agentic Runtime & Нейроергономіка» | агентний рантайм | Засіяні CVE й машини, таймер «глибокої роботи» — `setInterval` | `AgenticWorkspaceVirtualizationModal.tsx:80,183,293` | **Видалено** |
| ⋮ → «Physical Computing, GIS & WebGPU» | UART, GIS, WebGPU | Засіяний UART-лог і `setInterval` | `PhysicalComputingGisCanvasModal.tsx:32,150` | **Видалено** |
| ⋮ → «Список покупок / справ», «Спільний бюджет», «Навчальні картки», «Опитування спільноти» | картку в розмову | Справжня картка: `addCustomMessage` → `sendCard` → дріт `PeerCard` | `stores/messengerStore.ts:1957-1961`, `services/composerCards.ts:42-48` | **Лишено**; зняті латинські/«&»-підписи («(Flashcards)», «& Витрати») |
| ⋮ → «Мережевий зв'язок» | діагностика мережі | `P2PNetworkModal`: дороги з `messengerApi`, `networkEngine.getDiagnostics()` | `P2PNetworkModal.tsx:25,45,67` | **Лишено** |
| ⋮ → «Студія карток» | картки в розмову | `ActionHubModal` → `addCustomMessage` → `sendCard` | `MessengerRoot.tsx:687-701` | **Лишено** |
| ⋮ → «Картка співрозмовника», «Відкладені», «Звук», «Налаштування»; дзвінок, пошук, закріплене | — | Справжні дії (сторожі `peerCardReachable`, `callsAreHonest`) | — | **Лишено** |

## 2. Бічна панель — `components/messenger/Sidebar.tsx` (лише ПК-гілка; телефонна — `PhoneChatList`, не чіпана)

| Елемент | Обіцяв | Робив насправді | Доказ | Рішення |
|---|---|---|---|---|
| Рейка: іскра «AI Synthesis Lab» | студія | Та сама студія з §1 | `Sidebar.tsx:1050-1064` | **Видалено** |
| Меню папки → «Позначити як прочитані» | прочитати всю папку | Тост «Усі чати позначено», але `onMarkFolderAsRead` MessengerRoot не передає | `Sidebar.tsx:620-627`, `MessengerRoot.tsx:462-488` | **Видалено** |
| → «Вимкнути звук» / «🔔 Active» | тиша папки | Тост без обробника (`onToggleMuteFolder` не передається) | `Sidebar.tsx:629-637` | **Видалено** |
| → «Колірний акцент», «Налаштувати Vibe» (пресети «⚡ Deep Work & Design»…) | колір і підпис | Тост без обробника (`onSetFolderVibeAndColor` не передається) | `Sidebar.tsx:163-170,671-687` | **Видалено** з палітрою й пресетами |
| → «Змінити Emoji» (`FolderIconPickerModal`) | іконка папки | Тост без обробника (`onSetFolderIcon` не передається) | `Sidebar.tsx:2491-2496` | **Видалено** разом із модалкою |
| → «Копіювати посилання / Deep Link» | посилання на простір | `?folder=` не читає ніхто — з адреси береться лише `u`/`user`/`who` | `Sidebar.tsx:656-669`, `MessengerRoot.tsx:202-203` | **Видалено** (тест `sidebarFolderLinkPhone` переписано) |
| → «Поділитися простором» (`ShareFolderModal`) | спільний доступ | Сама модалка каже, що ділитися нікуди, і поруч обіцяє «Всі учасники отримають доступ» + два перемикачі без дії | `ShareFolderModal.tsx:26-27,119,165,188` | **Видалено** разом із модалкою |
| → «Перейменувати» (папку) | інлайн-перейменування | Ставить стан, для якого немає розмітки; «утримувач посилань» `if (… && false)` | `Sidebar.tsx:785-814` | **Видалено** (назва міняється в «Налаштувати простір…») |
| → «В архів» + секція «Архів просторів» | архів | Тост без обробника; жоден шлях не ставить `isArchived` | `Sidebar.tsx:639-654,2500-2600` | **Видалено** |
| → «Очистити чати» | очистити папку | Тост без обробника (`onClearFolderChats` не передається) | `Sidebar.tsx:689-698` | **Видалено** |
| → «Priority View (лише нові)» | фільтр непрочитаних | Справжній локальний фільтр | `Sidebar.tsx:1988-2008` | **Лишено**, підпис — «Лише непрочитані» |
| → «Швидкий перегляд», «Склад папки», «Експорт чатів (JSON)», «Налаштувати простір…», «Видалити папку» | — | Справжні дії над тим, що вже є в списку / сторі | `Sidebar.tsx:909-928,2220-2233,712-782,2305-2315,700-709` | **Лишено**; ім'я файла експорту `aura_…` → `phantom_…`, емодзі з тостів геть |
| Панель «Перемкнути активну ідентичність» (6 «сфер») | зміна ідентичності | `personas` не має жодного записувача — міняється лише латинський підпис `work`, кожна сфера пише «В мережі» без джерела | `Sidebar.tsx:2603-2676`, `stores/messengerStore.ts:2582-2594` | **Видалено** разом із `switchPersonaSphere` і типами `PersonaSphere`/`UserProfilePersona` |
| Чип «E2EE» на кожному рядку ПК | шифрування цієї розмови | Малювався кожному `dm` однаково — сам коментар поруч каже «про кожного однаково, тобто ні про кого» | `Sidebar.tsx:1545-1567` | **Видалено** |
| Заставка «Оберіть бесіду» (💬, «новий простір для співпраці») | — | Декор-емодзі | `MessengerRoot.tsx:666` | Емодзі геть, речення просте |

## 3. Усе, що тягнулось із цих дверей

| Що | Чому пішло |
|---|---|
| `modals/ModalHost.tsx` + 56 модалок (таблиця §4) | Жодна не робить виклику вузла; двері — шапка, палітра, шина подій, слеш-команди |
| `CommandPaletteModal` (Ctrl+K), Ctrl+` (термінал) | Палітра лише диспетчеризує `phantom:open-*` у ті самі модалки (у т. ч. Blueprint, Agentic, Physical) |
| Шина `phantom:open-*` / `phantom:open-modal` у `MessengerRoot` | 47 подій → `openModal`; слухати більше нічого |
| Слеш-команди `/git`, `/roles` у `MessageComposer` | Відкривали дві з цих модалок; лист на «/» тепер іде звичайним текстом |
| `stores/modalStore.ts`, `workOsStore.ts`, `agenticStore.ts`, `meshStore.ts`, `canvasStore.ts` | Користувачі — лише видалені модалки |
| `stores/aiSynthesisStore.ts`, `aiSynthesisStore.phone.ts`, `components/ai-studio/**` (11 файлів) | Студія (§1) |
| `ModalHost.phone.tsx`, `PHONE_SWAPS` / `phoneModalHost` у `vite.config.ts`, `manualChunks` для `RoleScopes/GitNative/CodeDiff` | Підміняти й ділити більше нічого |
| Мертві екземпляри `FolderInsightsModal`/`ShareFolderModal` у `MessengerRoot` + поля `isFolderInsightsOpen`/`isShareFolderOpen` стору | Жоден код не ставив їх у `true` |

## 4. Модалки за `ModalHost` (усі видалено)

Заголовок — те, що бачила людина; «net» — виклики вузла. У жодної net ≠ 0
(єдиний «мережевий» — `WebhooksManagerModal`, і він лише складає адресу на
`try.phantom-os.dev`, див. §1). «posts» — скільки разів модалка кладе лист у
розмову.

| Модалка | Заголовок | net / таймери / posts | Доказ (засів / таймер) |
|---|---|---|---|
| `AcademicLmsHubModal` | «Академічний LMS Хаб & Autograder» | 0 / 1 / 0 | `:43` засіяні здачі; `:77` таймер «перевірки» |
| `AcademyHubModal` | «Сфера: Навчання & Академія» | 0 / 0 / 2 | `:45` засіяні дедлайни |
| `AdvancedResearchMeshModal` | «Семантичний Атлас Знань & Наукові Блокноти» | 0 / 1 / 0 | `:36` засіяні вузли; `:57` таймер |
| `AgenticWorkspaceVirtualizationModal` | «Agentic Runtime & Нейроергономіка» | 0 / 6 / 0 | `:80` засіяні CVE; `:183` `setInterval` |
| `AmbientContextModal` | «Ambient Computing & Контекстна поведінка» | 0 / 0 / 0 | `:33` засіяні тригери |
| `AutomationPipelineModal` | «Local-First Automations & IFTTT» | 0 / 0 / 2 | `:40` засіяні правила |
| `AutonomousOpsWarRoomModal` | «Autonomous Ops, War Room & P2P Арбітраж» | 0 / 0 / 0 | `:42` засіяні інциденти |
| `CanvasPresentationSpeakerMatrixModal` | «Canvas Презентація, Спікери Дзвінка…» | 0 / 0 / 0 | `:46` слайди-константа |
| `CodeDiffMathHexInspectorModal` | «Code Diff, Math Canvas & Hex Inspector» | 0 / 1 / 1 | `:29` байти-константа; `:40,48` вписаний diff |
| `CollaborativeWhiteboardPlaygroundModal` | «Multiplayer Whiteboard & Code Runner» | 0 / 3 / 1 | `:67` `Math.random()` координати; «multiplayer» без мережі |
| `CommandPaletteModal` | палітра команд | 0 / 1 / 0 | `:82` список команд → `phantom:open-*` (`:140-680`) |
| `CommerceMicroAppsModal` | «E-Commerce Вітрини, CRM…» | 0 / 0 / 0 | `:41` засіяні товари |
| `CommunityClubModal` | «Сфера: Спільноти, Хобі & Клуби» | 0 / 0 / 0 | `:51` засіяні теми |
| `CorporateHROpsModal` | «Корпоративний HR, Онбординг…» | 0 / 0 / 0 | `:49` засіяні кроки |
| `CreativeStudioModal` | «Сфера: Творчість, Медіа & Дизайн» | 0 / 0 / 0 | `:42` мудборд-константа |
| `DataLifecyclePruningModal` | «Кристалізація знань та Життєвий цикл даних» | 0 / 1 / 0 | `:35` засіяні політики; `:75` таймер |
| `DisasterMeshDtnModal` | «Multi-Transport Failover & P2P DTN» | 0 / 0 / 0 | `:48` транспорти-константа |
| `FamilyHubModal` | «Сфера: Сімʼя та Побут» | 0 / 0 / 0 | `:55` засіяні покупки |
| `GitNativeDevOpsModal` | «Git-Native DevOps & Інженерний Хаб» | 0 / 1 / 1 | `:47` засіяні PR; `:109` «Passed» за таймером |
| `HeadlessInfrastructureModal` | «Системний Демон, Спільний Буфер & P2P WireGuard» | 0 / 1 / 0 | `:45` засіяний буфер |
| `HumanCentricBioContextModal` | «Cognitive Fatigue Protection & Neural Voice Masking» | 0 / 0 / 0 | `:20-21` лише локальні перемикачі |
| `InteractiveMediaAnnotationModal` | «Audio Waveform Scrubber… & PDF Анотатор» | 0 / 0 / 0 | `:33` засіяні мітки |
| `InteractiveVisualization3DModal` | «Data-to-Graph, Mermaid & 3D CAD» | 0 / 0 / 0 | статична розмітка |
| `IoTEqsTelemetryModal` | «Апаратні ключі, IoT & Edge Телеметрія» | 0 / 1 / 0 | `:132` «YubiKey 5 NFC» без опитування |
| `KnowledgeSearchModal` | «Omni-search» | 0 / 0 / 0 | `:44` вісім вписаних результатів |
| `LiveSpotlightMicroBountiesModal` | «Live Spotlight… & Micro-Bounties» | 0 / 0 / 0 | `:33` засіяні баунті |
| `LiveTerminalModal` | «Shared Terminal & Runner» | 0 / 2 / 1 | `:67-78` лише код-блок у нікуди |
| `LoRaWalkieTalkieModal` | «LoRa SX1262 Mesh & Direct P2P Walkie-Talkie» | 0 / 0 / 1 | `:25-27` лише локальний стан; радіо немає |
| `LocalErpEscrowModal` | «Local ERP, Складський Облік & Escrow Угоди» | 0 / 0 / 1 | `:43` засіяний склад |
| `NeuroErgonomicsModal` | «Нейро-ергономіка & Зниження когнітивного шуму» | 0 / 0 / 0 | `:22-24` локальні перемикачі без споживача |
| `NodeDashboardModal` | «Зʼєднання вузла» | 0 / 2 / 0 | дубль `P2PNetworkModal` (§1) |
| `P2PComputeSharingModal` | «Суверенний P2P-Комп'ютінг та Розподілений бекап» | 0 / 0 / 0 | `:24-25` лише перемикачі |
| `P2PFileSwarmModal` | «P2P File Swarm (BitTorrent Mesh)» | 0 / 0 / 0 | `meshStore.ts:210` запис без передачі |
| `PersonalWellnessModal` | «Сфера: Особистий простір & Здоровʼя» | 0 / 0 / 0 | `:37` засіяні звички |
| `PhantomArchitectureBlueprintModal` | «Phantom OS — Повна Технічна & Продуктова Специфікація» | 0 / 0 / 0 | `:268-281` «DLP Engine» = `includes('KEY') \|\| includes('AKIA')` над власним полем вводу |
| `PhantomRuntimeVfsModal` | «Phantom Runtime Environment & POSIX VFS» | 0 / 0 / 0 | `:39` засіяні монтування |
| `PhysicalComputingGisCanvasModal` | «Hardware, Offline GIS, WebGPU & W3C Credentials» | 0 / 2 / 0 | `:32` засіяний UART; `:150` `setInterval` |
| `PlanningPokerGanttWidgetsModal` | «Planning Poker, Gantt…» | 0 / 0 / 0 | `:38` засіяні голоси |
| `ProjectMemoryGraphModal` | «Project Memory Graph» | 0 / 0 / 0 | `agenticStore.ts:37` |
| `ProtocolDrivenSchemaModal` | «Кастомні схеми даних & Zero-Code Mini Views» | 0 / 0 / 0 | `:35` засіяні поля |
| `RelationalDataGridModal` | «Реляційна Таблиця & Kanban (Work OS Grid)» | 0 / 0 / 0 | `workOsStore.ts:73` |
| `ResourceGovernanceModal` | «Енергоефективність & Стійкість ОС» | 0 / 0 / 0 | `:40` порожні «воркери» без джерела |
| `RoleScopesModal` | «Ролі» | 0 / 0 / 0 | дубль `GroupRoles` (§1) |
| `SecOpsComplianceModal` | «SecOps, Merkle-Аудит, DLP & Відкликання Ключів» | 0 / 0 / 0 | `:43` засіяний аудит-лог |
| `SemanticBusPipesModal` | «Semantic Bus & Event Fabric» | 0 / 1 / 0 | `:44` засіяні канали; `:98` таймер |
| `SmartBreadcrumbsContextPeekModal` | «Smart Breadcrumbs, Hover Peek…» | 0 / 0 / 0 | статична розмітка |
| `SpaceVaultModal` | «Спільна скарбниця простору (Multi-sig Vault)» | 0 / 0 / 0 | `:37` засіяні пропозиції |
| `SpatialMultiPaneWorkspaceModal` | «Spatial Multi-Pane Workspace & Activity Heatmaps» | 0 / 0 / 0 | `:23-24` лише вибір розкладки |
| `SpatialProjectionsModal` | «Проекційні шари & Infinite Semantic Zoom» | 0 / 0 / 0 | статична розмітка |
| `TimeMachineSnapshotModal` | «CRDT Time Machine & Snapshots» | 0 / 2 / 0 | `:35` знімки-константа; `:70` таймер |
| `UniversalBridgeModal` | «Мости до інших мереж» | 0 / 0 / 0 | `:35` перелік запланованого, мостів немає |
| `VisualStateMachinePipelineModal` | «Visual State Machines & Pipeline Step Tracing» | 0 / 0 / 0 | `:32` засіяні кроки |
| `WasmAppSandboxModal` | «Embedded WASM Apps & Системний шар OS» | 0 / 0 / 0 | `:35` застосунки-константа |
| `WebhooksManagerModal` | «Work OS Webhooks & Інтеграції» | 0 / 2 / 1 | `:37,67` адреса на `try.phantom-os.dev` |
| `WorkspaceDriveModal` | «Workspace Drive (Хмарне & Локальне сховище)» | 0 / 0 / 0 | `workOsStore.ts:100` |
| `ZeroLeakSecurityModal` | «True Zero-Leak Security & Isolation» | 0 / 0 / 0 | `:41` бази-константа |

## 5. Що лишилось і чому

- Список розмов, пошук на пристрої, категорії, меню розмови (перейменувати,
  прочитано, очистити, видалити — все через `messengerApi`), папки з
  фільтром/складом/експортом/налаштуванням/видаленням, картка себе, смуга черги
  доставки — за кожним стоїть вузол або дані, що вже в списку.
- Шапка: картка співрозмовника, дзвінки (сторож `callsAreHonest`), пошук,
  закріплене, картки-віджети через `sendCard`, «Мережевий зв'язок»,
  «Студія карток», відкладені, звук, налаштування.
- Телефонна гілка `Sidebar` (`PhoneChatList`, `ChatActionsSheet`) і
  телефонні сторожі — не чіпані.

## 6. Чого цей прохід не робив

- `ActionHubModal`, `SmartFolderModal`, `FolderInsightsModal`,
  `P2PNetworkModal`, `SettingsModal` пройдено лише до «за дверима є справжня
  дія»; їхній власний вміст окремо не переписувався.
- Демо-пара `kiril`/`kyrylo` у `MessengerRoot` (гілка ПК без моста) — поза
  межею Header/Sidebar, лишилась як була.
- Старі ключі `localStorage` видалених сторів (`phantom_mesh_store`,
  `phantom_agentic_store`, `phantom_canvas_store`, workOs) у браузерах, де їх
  уже записали, ніхто не стирає — їх просто більше ніхто не читає.
- Вигляд у живому браузері/Tauri не дивився: доказ — vitest (jsdom), tsc і
  збірка.
