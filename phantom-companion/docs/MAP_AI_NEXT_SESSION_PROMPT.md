# MAP-AI × MAP-ROADMAP — Next Session Prompt

> Скопіюй усе між `===` як перше повідомлення у новій Claude Code сесії.
> Цей промпт об'єднує `docs/MAP_ROADMAP.md` (UX-roadmap MAP-1..MAP-30) і
> `/home/radxa/.claude/plans/scalable-watching-parasol.md` (engineering
> foundation MAP-AI-1..14) у **єдиний merge-план "Map-as-organ + AI-agent"**.

```
============================================================
Ти приймаєш PHANTOM Companion mid-stream. Branch `companion-v2-phase-0`.
Гілка вже має зрілу базу: 16 symbiote core-modules + MAP-1..MAP-6 закриті.
Твоє завдання — реалізувати **merge-roadmap "Map-as-organ"** який поєднує
найкраще з двох планів: `docs/MAP_ROADMAP.md` (UX-driven 30 фаз) та
`/home/radxa/.claude/plans/scalable-watching-parasol.md` (engineering
foundation MAP-AI-1..14 з tool-calls).

═══════════════════════════════════════════════════════════
ПРОЧИТАЙ ПЕРШИМ (у цій послідовності, не пропускай)
═══════════════════════════════════════════════════════════

1. `docs/MAP_ROADMAP.md` — UX-первинний roadmap. Філософія мапи (§Філософія).
   Закриті MAP-1..MAP-6. Інтеграційна верхівка MAP-7..MAP-20. Фронтир
   MAP-21..MAP-30. Backend-залежності від phantom-os. Кросс-посилання з
   Tier-планів.

2. `/home/radxa/.claude/plans/scalable-watching-parasol.md` — engineering
   план MAP-AI-1..14. Tool-call surface, context blocks, AppAction verbs,
   routing, geofence, memory palace, time machine, mood routing, haptic
   landmarks, family pins, magnetic-north + echo, swarm tiles.

3. `docs/MAP_PHASES.md` — короткий summary закритих MAP-1..MAP-6 фаз з
   архітектурними рішеннями (4 провайдери, OpenFreeMap default, GHOST
   IP-geo gate, 13 файлів × ~2560 LOC).

4. `docs/SYMBIOTE_VISION.md` — Tier 14..30 vision. Constitution (Tier
   29) — це не decoration. Кожна нова tool/feature реєструє
   `FeatureManifest` у `:core-constitution` і проходить `AxiomAuditor`
   на boot.

5. `docs/COMPANION_CONTRACT.md` — wire-контракт з phantom-os backend.
   MAP-13 (`mapEvents`) і MAP-20 (`handoffEvents`) WS канали — звідси.

6. `docs/ROADMAP.md` — overall phase-tracking. Не міняй ✅-закриті
   фази; додавай тільки нові.

═══════════════════════════════════════════════════════════
СЕРЕДОВИЩЕ
═══════════════════════════════════════════════════════════

- **Host:** Radxa SBC aarch64, Linux 6.17. **КРИТИЧНО: host crashes
  Claude Code itself when parallel Agent subagents are dispatched.**
  Завжди серіалізуй Agent виклики; prefer direct tools (Read/Write/
  Edit/Bash) для основної роботи. Це задокументовано у MEMORY.md
  pair_flow_gotchas та host_agent_concurrency.

- **Branch:** `companion-v2-phase-0`. Не перемикайся на main без явного
  дозволу оператора.

- **Build:** `./gradlew :app:assembleDebug` ≈ 30s clean / 5min full
  після `./gradlew clean`. Тести: `./gradlew test`. Lint:
  `./gradlew :app:lintDebug`.

- **adb device:** RFCY61HG0WB (Pixel 6). E2E playbook — у MEMORY.md
  `e2e_playbook.md`.

- **Working tree:** на початку перевір `git status`. Можливо лежать
  uncommitted правки від паралельного autopilot. **Не коміть чуже без
  явного дозволу.**

═══════════════════════════════════════════════════════════
МОДЕЛЬ-РОУТИНГ — використовуй Sonnet де можна
═══════════════════════════════════════════════════════════

Оператор хоче economy на токенах. Roughly:

| Тип роботи | Модель | Чому |
|---|---|---|
| Архітектурні рішення, конфлікт-резолюція | Opus (ти) | Потребує цілісного бачення |
| Per-file scaffold (build.gradle, AndroidManifest, Module.kt) | Sonnet via Agent | Boilerplate |
| Repeating test-file generation (unit tests by template) | Sonnet via Agent | Pattern-following |
| KDoc / phase doc writing | Sonnet via Agent | Markdown formatting |
| Розвідка існуючого коду (де живе X, як працює Y) | Sonnet via Explore Agent | Read-only search |
| Build error investigation | Sonnet via debugger Agent | Pattern-matching |
| Кросс-cutting refactor | Opus (ти) | Цілісність ризикна |
| Critical security/constitution code | Opus (ти) | Не помилитись |
| Commit messages (атомарні, slot-into existing style) | Sonnet via Agent | Pattern + brevity |

**Делегуй Sonnet-агенту через `Agent` tool з:**
```
subagent_type: "general-purpose"
model: "sonnet"
description: "Scaffold core-routing module structure"
prompt: "Create build.gradle.kts + consumer-rules.pro + AndroidManifest.xml + Module.kt for new :core-routing module. Follow exact convention from :core-tempo (read /home/radxa/.../core-tempo/build.gradle.kts as reference). Namespace: local.phantom.companion.core.routing. Dependencies: project(':core-constitution'), libs.kotlinx.coroutines.android, libs.androidx.core.ktx, libs.ktor.client.core (it will need Ktor for OSRM). Add testImplementation libs.junit + libs.kotlinx.coroutines.test. minSdk 29, compileSdk 34, jvmTarget 17."
```

**Серіалізуй Agent дзвінки.** Жодного парелельного fan-out — host crashить.

═══════════════════════════════════════════════════════════
MERGE-ROADMAP — ПОРЯДОК ВИКОНАННЯ
═══════════════════════════════════════════════════════════

Це об'єднаний план з обох документів. Кожна фаза = окремий атомарний
комміт. Кожен phase ID живе у commit message + у KDoc.

### TIER 0 — FOUNDATION (тижні 0-4)
Без цього всі наступні фази залишаються "AI повертає JSON-віджет",
не "AI-агент який програмно діє з мапою".

**MAP-AI-1** Tool-call surface на AIRouter (~2 тиж)
- `AIChunk.ToolCall(name, argsJson, callId)` + `AIChunk.ToolResult`
- `AIIntent.WithTools(tools)` + `Tool` schema
- `AiTool` interface, `ToolDispatcher`, `ToolRegistry` у `:core-ai`
- Gemini route emits native `functionCall`; Gemma local — JSON sniffer
- StreamViewModel loop: collect → if ToolCall, dispatcher, re-issue
- Constitution: per-tool `FeatureManifest` registration

**MAP-AI-2** Map context blocks (~1 тиж)
- `[OFFLINE_REGIONS:…]`, `[SPATIAL_MEMORY:nearby]`, `[GEOFENCES:active]`, `[ROUTE_HISTORY:30d]`, `[MOOD:state]`
- Pattern parallel to existing `LocationContextProvider`

**MAP-AI-3** Map-aware AppAction verbs (~1 тиж)
- `NavigateTo(lat,lon,mode)`, `SearchPlace(q)`, `ShowOnMap(geom)`,
  `SaveOfflineRegion(bbox)` у `feature-godmode/AppAction.kt`
- Authorization tiers: read-only silent, mutation explicit, destructive
  long-press constitutional gesture

### TIER 1 — QUICK WINS (тижні 4-8)
Демо-вражень з низької інвестицією. Це з MAP_ROADMAP near-term.

**MAP-7** Marker tap → bottom sheet (~1 день)
- `queryRenderedFeatures` на MARKERS_DOT_LAYER
- Bottom sheet з: "Прокласти маршрут", "Зберегти як місце", "Що тут?"
- "Що тут?" emits AI prompt з `[LOCATION:…]` injection

**MAP-10** Bearing arrow + camera follow (~3 дні)
- TYPE_ROTATION_VECTOR з low-pass filter
- SymbolLayer arrow замість operator dot
- `CameraMode.TRACKING_GPS_NORTH` toggle

**MAP-11** Inline MapWidget → fullscreen tap (~2 дні)
- `onExpand` callback у MapWidget
- Deeplink-style route з lat/lon/zoom/markers args

**MAP-17** Voice → map gesture (~3 дні)
- `:core-voice` STT-final detect "де я" / "покажи мапу"
- Auto-navigate до Routes.Map + voice address chip

**MAP-18** DecisionDiary places overlay (~2 дні)
- DiaryEntry.place: LatLng? (Room migration)
- DecisionPlaces layer toggle + connected pulse

### TIER 2 — INTEGRATION (тижні 8-16)
Real product capabilities. Mix з обох планів.

**MAP-AI-4 + MAP-16** Overpass POI (~1.5 тиж)
- `OverpassClient` mirror NominatimClient з 24h Room cache
- AI tool: `nearby_search(q, radius_m)`
- Category icon-toggles top-right (cafe/atm/gas/hospital)

**MAP-AI-5 + MAP-8** Routing engine (~3 тиж)
- Тиждень 1: OSRM cloud через Ktor REST (за WireGuard)
- Тиждень 2-3: Valhalla embedded JNI (~50MB armv8a)
- `RoutingEngine` interface + `MapPreferences` toggle
- AI tool: `route(from, to, mode)` повертає polyline + ETA + turn-by-turn UA
- `RouteLayer` + LineLayer overlay на MapScreen

**MAP-9** Live breadcrumb history (~10 днів)
- Room v18 `location_breadcrumb` table
- `BreadcrumbCollector` foreground service (adaptive 30s sampling, stops on stationary)
- GHOST mode → пауза негайно
- LineLayer з timestamp gradient

**MAP-12** AI Place-Mention Auto-Pin (~1 тиж) ⭐ унікально
- NER detector над AI responses (LLM `<EXTRACT_PLACES>` tag або spacy ONNX)
- Lightweight "show on map" pill у Stream item ledger
- Tap pill → inline MapWidget injects поточну розмову
- GHOST disabled

**MAP-13** `mapEvents` WS subscriber (~1 тиж) ⭐ unique (backend integration)
- Wire spec у `phantom-os/docs/API_CONTRACTS.md` — узгодити SPEC раніше
- `push_route`, `push_pin`, `clear_overlay` verbs
- Notice "Десктоп прислав маршрут" → tap → MapScreen overlay

**MAP-15** My Places SQLCipher (~1 тиж)
- Room v19 vault-encrypted `saved_places` table
- Categories: home, work, favorite, watch-zone, dangerous-zone
- Long-press на мапі → mini-form
- AI tool: `query_saved_places(category)`

**MAP-20** Handoff sheet UI (~1 тиж)
- `handoffEvents` WS subscriber
- Bottom-sheet Accept / Defer 10 min / Decline
- Persistent — виживає app restart

### TIER 3 — SYMBIOTE DEPTH (тижні 16-28, паралельні треки)

**MAP-AI-6** Geofence + AnchorRouter (~2 тиж)
- `:core-geofence` module: GeofenceStore (Room) + GeofenceMonitor foreground
- WorkManager polling, не Google `GeofencingClient`
- AI tools: `register_geofence(zone, dwell_s, action)`, `MatterCommandTool`

**MAP-AI-7 + MAP-22** Memory Palace ↔ map (~1.5 тиж)
- `LocationMemoryBridge` translate lat/lon ↔ SpatialMemoryStore chamber `"earth"`
- MemoryPinLayer + tap → bubble + AI narration
- `[SPATIAL_MEMORY:id=… title=… distance_m=…]` context block

**MAP-AI-8 + MAP-25** Time Machine (~2 тиж)
- `:core-location-history` — Room 90d retention з Douglas-Peucker compression
- TimelineSlider у MapScreen
- RollingBuffer.snapshot(atMs)
- AI tool: `query_location_history(window)`

**MAP-AI-9 + MAP-29** Constitutional avoidance (~1.5 тиж)
- `AvoidanceStore` (SQLCipher polygon + reason)
- Valhalla `avoid_polygons` integration
- AI never sees raw polygon, тільки count
- Constitutional refusal у GHOST mode

**MAP-AI-10** Mood-Arc routing (~1.5 тиж) ⭐ унікально
- `MoodTaggedLocationDao` joins LocationHistory з MoodArc snapshots
- AI tool: `propose_mood_route()` для LOW state
- 30-min loop через past ELEVATED coords у walking radius

**MAP-AI-11** Haptic landmark cues (~1 тиж) ⭐ унікально
- ProprioceptiveTicker extension з RouteWaypointPattern
- RouteWaypointDetector — Ahead-30m / Ahead-5m / At
- Distinct patterns: left/right/U-turn/destination

**MAP-AI-12 + MAP-14** Family Hive Layer (~3 тиж)
- FamilyPresenceCrdt (LWW-Element-Set per peerId)
- PresenceGossipTransport over existing WireGuard mesh
- Per-member opt-in з time-limited grants
- Status colors: active / stationary / panic-triggered
- AI tools: `family_request_share`, `family_revoke_share`

**MAP-19** AR threat layer (~3 тиж)
- ARCore overlay у ArMapWidget
- MediaPipe Object Detection + ONNX safety-classifier
- 4 detectors: hot-surface (IR), smoke, icy ground, electrical (EM)
- Privacy: face-mask blur on by default

### TIER 4 — FRONTIER (тижні 28+)

**MAP-21** Cooperative live annotation
- Two operators draw на тій самій мапі через mesh-CRDT
- Yjs / Automerge для overlay state

**MAP-AI-13 + MAP-26** Magnetic-north feel + Echo-location (~2 тиж)
- MagneticNorthFeel — continuous-low haptic
- SonarEngine — ultrasonic chirp + mic → spatial audio
- Accessibility-grade nav

**MAP-AI-14 + MAP-28** Swarm tile pre-render (~2 тиж)
- TilePrefetchOrchestrator
- TileFragmentGossip over WireGuard
- TileProvider lookup: memory → disk → swarm → HTTP

**MAP-23** Predictive movement
- Pattern-based pre-route, pre-fuel notifications
- Tier 15-C ShimmerEngine integration

**MAP-24** Family Hive geo-fences
- Parent draws "школа" / "дім бабусі" zones
- Child-tracking specific — Guardian protocol

**MAP-27** Negotiation Court for routes ⭐ генільно
- Tier 22 NegotiationChannel — phantoms домовляються про meeting point
- Враховуючи routes, weather, traffic, schedules
- Operator sees only finalized "ось де зустрінемось"

**MAP-30** "The Map of You"
- 10-year biographical visualization
- Zones of activity / joy / grief / paths walked
- Privacy-locked behind vault biometric

═══════════════════════════════════════════════════════════
ЦІЛЬ
═══════════════════════════════════════════════════════════

Не "ще одна мап-апка". Не "AI що показує мапу". А **AI-агент який живе
в просторовому вимірі симбіоту**:

- Програмно діє через tool-calls (search, route, geofence, save place)
- Знає де ти був (breadcrumb), куди йдеш (predictive), кого поряд
  бачиш (family hive), що пам'ятаєш (memory palace)
- Дотримується конституції (GHOST refusal, no sponsored places,
  privacy-by-design)
- Розширюється на пристрої (haptic landmarks, magnetic-north feel,
  echo-location)
- Працює offline (Valhalla + offline tiles + on-device geocoder)
- Мережно (mesh-shared annotations, swarm tile prefetch, family pins)

**Це категорія "spatial symbiosis" якої не існує** — не Google Maps з
кращою AI, не Apple Maps з шерінгом. Це **операторський
просторовий орган**.

═══════════════════════════════════════════════════════════
ПРАВИЛА КОДУ
═══════════════════════════════════════════════════════════

1. **Менше коментарів.** Тільки KDoc на public API + WHY на non-obvious
   рішення. WHAT-коментарі (what does this do) — заборонено, бо
   well-named identifiers вже це говорять.

2. **No emoji у коді.** Допускаються у operator-facing strings
   (markdown, error messages, UA-text).

3. **Pure JVM-testable core, Android/network adapters в `:app`.** Це
   convention яку проект тримає. Не тягни MapLibre / Room / Ktor у
   `:core-*` без явної потреби.

4. **Constitution-first.** Кожна нова tool / feature реєструє
   `FeatureManifest` у `core-constitution`. Read-only — silent;
   mutation — explicit; destructive — long-press gesture.

5. **GHOST-aware.** Кожен новий cloud-call перевіряє ContextEngine.
   state == GHOST → no-op + log. Це не option, це axiom.

6. **No new deps без авторизації.** Existing libs.versions.toml
   достатньо для більшості. Виняток: Valhalla JNI + GraphHopper —
   це план-схвалена нова залежність для MAP-AI-5.

7. **Atomic commits.** Один phase = один commit (або 2-3 коли scope
   потребує). Format:
   ```
   feat(map): MAP-AI-N — short verb-led description
   
   Body explaining WHY (not WHAT — diff shows что). Reference
   problem solved + acceptance criterion. Note any docs touched.
   
   Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
   ```
   Або `Co-Authored-By: Claude Opus 4.7 (1M context)` для коли працює
   Opus сама. Дотримуйся існуючого стилю — подивись `git log --oneline -20`.

8. **Tests per phase.** Не shippи фазу без тестів. Pattern — у
   `core-tempo/src/test/...` як reference.

═══════════════════════════════════════════════════════════
КРИТИЧНІ ФАЙЛИ ДЛЯ MAP-AI-1 (стартова фаза)
═══════════════════════════════════════════════════════════

Modify:
- `core-ai/src/main/java/local/phantom/companion/core/ai/AIIntent.kt`
- `core-ai/src/main/java/local/phantom/companion/core/ai/AIResponse.kt:8`
- `core-ai/src/main/java/local/phantom/companion/core/ai/route/GeminiCloudRoute.kt:173-208`
- `core-ai/src/main/java/local/phantom/companion/core/ai/route/GemmaLocalRoute.kt:47-64`
- `core-ai/src/main/java/local/phantom/companion/core/ai/route/RoutedAIRouter.kt`
- `feature-stream/src/main/java/local/phantom/companion/feature/stream/StreamViewModel.kt:737-810`
- `app/src/main/java/local/phantom/companion/data/AppContainer.kt`

Create:
- `core-ai/src/main/java/local/phantom/companion/core/ai/tool/AiTool.kt`
- `core-ai/src/main/java/local/phantom/companion/core/ai/tool/ToolDispatcher.kt`
- `core-ai/src/main/java/local/phantom/companion/core/ai/tool/ToolRegistry.kt`

Reuse pattern from existing `core-ai/.../tool/WeatherTool.kt`.

═══════════════════════════════════════════════════════════
ВЕРИФІКАЦІЯ
═══════════════════════════════════════════════════════════

Після кожної фази:
- `./gradlew :app:assembleDebug` ✅ зелено
- `./gradlew :app:lintDebug` ✅ зелено
- `./gradlew test` ✅ зелено
- ConstitutionRegistry.rejections empty
- E2E smoke per `docs/E2E_MANUAL_CHECKLIST.md` append-row

Перед commit'ом, перевір:
- Чи фаза справді закрила exit criterion (не тільки compile)
- Чи додано regression test для головної фічі
- Чи KDoc + commit message узгоджені з convention

═══════════════════════════════════════════════════════════
СЕСІЙНІ МЕТРИКИ — НЕ ВІДВОЛІКАЙСЯ
═══════════════════════════════════════════════════════════

- **НЕ** торкайся `feature-stream/.../parse/AssistantTextSanitizer.kt`,
  `core-ai/SystemPrompts.kt`, `MapScreen.kt` / `MapWidget.kt` / `ChartWidget.kt`
  uncommitted changes без явної потреби — це паралельна робота іншого
  процесу. Якщо треба — підбери rebase tactic.

- **НЕ** ламай уже-зелений build. Якщо твоя зміна потребує widespread
  refactor — зупинись, спитай оператора, не "fix in this commit".

- **НЕ** додавай нові залежності без апруву (виняток — Valhalla JNI
  для MAP-AI-5 уже схвалено).

- **НЕ** запускай парелельних Agent (host crash).

- **НЕ** комітьте чужі working-tree файли. Перевір git status, скомміть
  тільки що ти зробив.

═══════════════════════════════════════════════════════════
ПОЧАТКОВА ДІЯ
═══════════════════════════════════════════════════════════

Прочитай 6 документів вище. Запиши свій план на сьогоднішню сесію:
яку фазу робиш, скільки часу очікуєш, що буде у commit. Покажи
оператору. Потім почни MAP-AI-1.

Удачі. Зроби з симбіоту просторовий орган якого не існує.
============================================================
```

---

## Цей промпт — навіщо

Цей файл — handoff документ. Не вирізайте з нього в наступний раз:
- послідовність read-first
- модель-роутинг (Sonnet для repetitive Agent calls)
- TIER 0..4 фазована структура
- конкретні файли для першої фази
- правила коду + commit-convention
- gotchas (Radxa host parallel-agent crash, чужі working tree)

Якщо хочеш start session — копіюй блок між `============================================================`
лініями. Цей doc лишається як reference у репо.

> **Філософія merge:** UX-плани (MAP_ROADMAP) показують ЩО ми будуємо.
> Engineering-план (scalable-watching-parasol) показує ЯК. Без MAP-AI-1
> tool-calls "AI що працює з мапою" — це AI що повертає JSON. З
> MAP-AI-1 → це agent. Foundation first.
