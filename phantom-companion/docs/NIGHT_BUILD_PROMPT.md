# PHANTOM Companion — Map-as-Organ нічна сесія

> Скопіюй усе між `===` як перше повідомлення у нову Claude Code сесію
> (бажано Opus 4.7 з 1M-context). Або запусти через `/autopilot` / `/ralph`
> для автономного циклу. Робота на ~6-8 годин.

```
============================================================
PHANTOM Companion — нічний build "Map як орган симбіоту"
Гілка: companion-v2-phase-0
Оператор: kyril (finance387@gmail.com), Samsung A56 (RFCY61HG0WB).

═══ МЕТА — НЕ звужуй, НЕ пом'якшуй ═══

За одну ніч (~6-8 годин) зробити PHANTOM map-стек повноцінним
органом симбіоту, не "ще одну мап-апку":

  ВІЗУАЛЬНО рівнятись з Google Maps:
    - Щільні POI з кольоровими піктограмами по категоріях
    - 3D building extrusions при zoom > 16, плавний tilt
    - Marker clustering (cluster expansion zoom on tap)
    - Bearing arrow з SensorManager rotation, camera-follow FAB
    - Material You bottom-sheet на POI-tap з фото з Wikipedia
    - Smooth motion: cubic-bezier(0.4,0,0.2,1), 240ms transitions
    - Day/night style swap без сіпання камери (вже зроблено MAP-1+5)

  ФУНКЦІОНАЛЬНО — частина AI-симбіоту, не "карта з AI integration":
    - Tool-calls: nearby_search, route, register_geofence, query_location_history,
      query_saved_places, propose_mood_route
    - Context blocks: [LOCATION:...], [GEOFENCES:...], [ROUTE_HISTORY:30d],
      [SPATIAL_MEMORY:nearby], [MOOD:state], [OFFLINE_REGIONS:...]
    - Memory Palace ↔ map bridge: lat/lon ↔ "earth" chamber, AI наратив
    - Time Machine: breadcrumb history з timeline slider, 90-day FSRS
    - Mood-Arc routing: LOW state → past ELEVATED coords loop
    - Magnetic-north haptic feel + sonar echo для accessibility-grade nav

═══ ЧИТАЙ ПЕРШИМ — порядок не міняй ═══

1. /home/radxa/.claude/projects/-home-radxa-programming-my-own-phantom-ai-PHANTOM-OS-BLUEPRINT-phantom-companion/memory/MEMORY.md
   11 entries з gotchas. Особливо:
   - host_agent_concurrency (Radxa крашить CC при паралельних Agent — серіалізуй)
   - maplibre_callback_nullability (Status: Nullable+Nullable, List: Nullable+NotNull,
     Create/Delete: NotNull-everywhere — overrides ламаються легко)
   - workmanager_for_oem_battery_kill (періодичне → WorkManager, не coroutine loop)
   - kdoc_close_inside_backticks (`*/` всередині backticks ламає KDoc)
   - vault_lock_one_way_invariant (UI лок ≠ lockNow())

2. docs/MAP_PHASES.md — закриті MAP-1..MAP-6 + W1-4 (location chain).
   Що вже є: TileProvider catalog (4 провайдери), Nominatim search,
   reverse-geocoded address chip, OfflineRegionsManager, MapStyleConfig,
   GeoJSON marker layers.

3. docs/MAP_ROADMAP.md — UX roadmap MAP-7..MAP-30. MAP-7..MAP-20
   integration, MAP-21..MAP-30 frontier.

4. docs/MAP_AI_NEXT_SESSION_PROMPT.md — merge-prompt MAP-AI × MAP-ROADMAP.
   Це батьківський план; цей нічний — практична частина.

5. /home/radxa/.claude/plans/scalable-watching-parasol.md — engineering
   план MAP-AI-1..14. Tool-call surface, geofence, memory palace,
   time machine, mood routing, haptic landmarks, family pins,
   magnetic-north + echo, swarm tiles.

6. docs/COMPANION_CONTRACT.md — wire-контракт з phantom-os backend.
   §10.1 (/ws path), mapEvents (MAP-13), handoffEvents (MAP-20).

7. docs/SYMBIOTE_VISION.md — Tier 14..30. Constitution (Tier 29) — 
   кожна tool/feature реєструє FeatureManifest у :core-constitution.

8. docs/DESIGN_BRIEF.md — візуальна мова: GlassCard, GlassLevel.{Card,Elevated,Hero},
   accentBorder, motion catalog, sound catalog, anti-patterns.

9. git log --oneline -30 — недавня історія. Особливо коміти:
   - 26afdd6 (app integration: tool/agent/resilience)
   - 3f8c887 (MAP-AI-3 verbs)
   - 826c37e (MAP-AI-2 context blocks)
   - 026d68d (core-tool MAP-AI-1)
   - e55ea42 (core-resilience)
   - 2df32df (core-agent supervisor)

═══ СЕРЕДОВИЩЕ ═══

- Host: Radxa SBC aarch64. КРИТИЧНО: НЕ запускай паралельних
  Agent subagents — host крашить Claude Code сам. Серіалізуй усі
  Agent виклики; prefer Read/Edit/Write/Bash прямо.
- Branch: companion-v2-phase-0. НЕ перемикайся на main.
- Build: `./gradlew :app:assembleDebug` ≈ 30s incremental, 5min clean.
  Tests: `./gradlew test`. Lint: `./gradlew :app:lintDebug`.
- Device: RFCY61HG0WB (Samsung A56), adb authorized, screen unlocked OK.
- Working tree: ПЕРЕВІР `git status` перед стартом. Має бути ЧИСТЕ
  (попередня сесія розрізала все на 6 атомарних комітів). Якщо щось
  лежить — спитай оператора або перенеси у branch.
- Gemini API key — в aiKeyStore (введений у Settings оператором).
  Якщо AI вертає 401/403 — оператор має перевести ключ.

═══ МОДЕЛЬ-РОУТИНГ ═══

| Робота | Хто | Чому |
|---|---|---|
| Архітектурні рішення, race-fix, конфлікти | Opus | Цілісність ризикна |
| Per-file scaffold (нові :core-* модулі) | Sonnet via Agent | Boilerplate |
| Test-file generation by template | Sonnet via Agent | Pattern |
| KDoc + commit messages | Sonnet via Agent | Brevity |
| MapLibre-specific код (callbacks, layers) | Opus | API хитрі |
| Compose UI polish (motion, gestures) | Opus | Detail |
| Розвідка існуючого коду | Sonnet via Explore | Read-only |

Делегуй через Agent з:
  subagent_type: "general-purpose", model: "sonnet"
  description: коротке завдання
  prompt: повний контекст + файли для створення/зміни + reference
          patterns з існуючого коду

СЕРІАЛІЗУЙ. Один Agent одночасно. Host крашить інакше.

═══ NIGHT BUILD — ПРІОРИТЕТНА ЧЕРГА ═══

────────────────────────────────────────────────
ФАЗА A — VISUAL PARITY З GOOGLE MAPS (3-4 години)
────────────────────────────────────────────────

A1. **POI-щільність** (~1.5 години)
    Корінь скарги "карти такі скудні" — OpenFreeMap дає мінімалістичний
    рендер. Треба насипати реальні POI поверх.

    Створи:
    - core-net/.../map/OverpassClient.kt — Ktor REST до overpass-api.de
      (mirror за бажанням), 24h Room cache. Запит за viewport bbox.
    - core-data/.../poi/PoiCacheDao.kt + PoiCacheEntity (Room v13)
    - feature-stream/.../ui/map/POIOverlay.kt — GeoJsonSource +
      SymbolLayer з категорійними іконками
    - feature-stream/.../ui/map/POICategoryToggle.kt — top-right
      filter row: cafe / atm / gas / hospital / parking / pharmacy

    Технічно:
    - Категорії: amenity=cafe|restaurant|atm|gas|hospital|parking|
      pharmacy|supermarket|hotel; railway=station; aeroway=aerodrome
    - Іконки: AppCompatResources → Bitmap через addImage до style
    - Collision: text-allow-overlap=false, icon-allow-overlap=true
    - Min-zoom 12 (нижче — кашка); fade-in opacity transition

    Acceptance: відкрив Map → за 2 секунди viewport заповнений
    50+ POI з кольоровими піктограмами. Кав'ярні — коричневі,
    лікарні — червоні, АТМ — зелені.

A2. **3D building extrusions** (~30 хв)
    OpenFreeMap style уже містить building source. Треба:
    - FillExtrusionLayer на building source layer
    - paint: ['get', 'render_height'] (fallback ['get', 'height'])
    - paint: ['get', 'render_min_height'] (fallback ['get', 'min_height'])
    - color: theme.glassPanel з inkSecondary alpha 0.6
    - Tilt при zoom > 16: animateCamera pitch 45° easeInOut 600ms

    Файли: feature-stream/.../ui/map/BuildingExtrusion.kt (new)
    Acceptance: zoom 17+ → будівлі підіймаються в 3D, плавна
    анімація pitch при zoom-in.

A3. **Marker clustering** (~45 хв)
    POIOverlay при низькому zoom — каша з 100+ маркерів. Cluster:
    - GeoJsonSource cluster=true, clusterRadius=50, clusterMaxZoom=14
    - CircleLayer для clusters (радіус по point_count)
    - SymbolLayer для cluster count text
    - SymbolLayer для unclustered points
    - Click cluster → expansion zoom (queryRenderedFeatures +
      cameraForLatLngBounds, easeCamera 600ms)

    Файли: вмонтувати у POIOverlay.kt
    Acceptance: zoom 11 → бачу cluster "247 кав'ярень у Києві",
    тап → smooth zoom до zoom 13 з expanded markers.

A4. **Bearing arrow + camera follow** (~30 хв)
    - core-sensor/.../BearingProvider.kt (TYPE_ROTATION_VECTOR,
      low-pass filter alpha=0.15)
    - feature-stream/.../ui/map/BearingArrow.kt — SymbolLayer
      28dp triangle, primary color, smooth rotate
    - MapScreen FAB right-bottom: CameraMode.TRACKING_GPS_NORTH toggle
    - Smooth move: easeCamera 600ms RubberBandInterpolator

    Файли: вище.
    Acceptance: іду — стрілка дивиться куди йду, camera плавно
    тримає мене у центрі.

A5. **POI bottom-sheet з фото** (~45 хв)
    queryRenderedFeatures(point, [poi-layer]) → properties → ModalBottomSheet:
    - Photo (Wikipedia mediawiki API thumbnail, 24h Room cache)
    - Title + category icon + distance from operator (haversine)
    - Hours, phone, website (з Overpass tags)
    - 3 actions: "Прокласти маршрут" / "Зберегти у My Places" /
      "Що тут?" (AI prompt з [LOCATION:lat,lon name=...] injection)

    Файли: feature-stream/.../ui/map/PoiBottomSheet.kt,
           feature-stream/.../ui/map/WikipediaPhotoFetcher.kt
    Acceptance: тапнув на кав'ярню → знизу сповзає sheet з фото,
    "Що тут?" → AI відповідає з контекстом місця ("це кав'ярня X
    на вулиці Y, працює до 22:00").

────────────────────────────────────────────────
ФАЗА B — AI ORGAN INTEGRATION (3-4 години)
────────────────────────────────────────────────

B1. **MAP-AI-4: NearbyPlacesTool** (~1 година)
    AI-сторона A1. Створи:
    - core-tool/.../impl/NearbyPlacesTool.kt — Tool implementation
      що загортає Overpass + повертає top-10 results JSON
    - FeatureManifest у core-tool/Module.kt
    - SystemPrompts.kt — додай блок про nearby_search verb
    - Реєстрація у AppContainer toolRegistry

    Acceptance: AI каже "знайди кав'ярні поряд" → tool-call →
    результати падають у Stream як StreamItem.Widget(type=poi-list)
    з тапабельними рядками.

B2. **MAP-AI-7: Memory Palace ↔ Map bridge** (~1.5 години)
    LocationMemoryBridge translate lat/lon ↔ SpatialMemoryStore
    chamber "earth":
    - core-memory-palace/.../LocationMemoryBridge.kt
    - feature-stream/.../ui/map/MemoryPinLayer.kt (SymbolLayer
      з кастомною іконкою-кристалом)
    - Tap → bubble з memory text + AI narration option
    - Context block [SPATIAL_MEMORY:id=... title=... distance_m=...]
      у LocationContextProvider/SpatialMemoryContextProvider

    Acceptance: запам'ятав спогад "тут пили каву з Олею" →
    повернувся через місяць → крапля-кристал на мапі → tap →
    "пам'ятаю, ти пив тут каву з Олею 12 квітня".

B3. **MAP-AI-8 + MAP-9: Live Breadcrumb + Time Machine** (~2 години)
    - core-data/.../location/LocationBreadcrumbDao.kt + Entity
      (Room v13 migration)
    - core-data/.../location/BreadcrumbCollector.kt (foreground
      service, 30s adaptive sampling, stops on stationary)
    - GHOST mode → пауза негайно (isGhost predicate)
    - feature-stream/.../ui/map/BreadcrumbLayer.kt (LineLayer
      з timestamp gradient: старі — жовті, нові — фіолетові)
    - feature-stream/.../ui/map/TimelineSlider.kt (90-day
      Douglas-Peucker compressed history)
    - core-tool/.../impl/QueryLocationHistoryTool.kt
    - WorkManager periodic compression job (нічна 03:00)

    Acceptance: ранок — повз вчорашню стежку видно чітку лінію
    з градієнтом часу. Slider зверху → перетягую — бачу де
    був тиждень тому. AI "де я був вчора" → tool повертає
    coords + ти бачиш polyline.

B4. **MAP-AI-10: Mood-Arc routing** (~1 година)
    LOW state з MoodArc → AI tool propose_mood_route →
    30-min loop через past ELEVATED coords у walking radius:
    - core-data/.../mood/MoodTaggedLocationDao.kt — JOIN
      LocationBreadcrumb + MoodArc snapshots
    - core-tool/.../impl/MoodRouteTool.kt
    - Викликається автоматично коли mood drops до LOW (proactive)
    - Stream notice "помітив тон — пропоную пройтись там, де
      тобі було добре" + map preview

    Acceptance: модельований LOW mood → з'являється insight
    bubble з 3 запропонованими маршрутами що йдуть через
    місця де раніше HRV пік + усмішка.

B5. **MAP-AI-13: Magnetic-north feel + echo-location** (~1 година)
    Accessibility-grade nav (без візуалу):
    - core-embodiment/.../MagneticNorthFeel.kt — continuous-low
      haptic в напрямку півночі
    - core-extension-senses/.../SonarEngine.kt — ultrasonic chirp
      + mic capture → spatial audio (HRTF-emulated)
    - Activatable via long-press на map FAB

    Acceptance: вимкнув екран, FAB-long-press, тримаю телефон —
    feel напряму до точки призначення без дивитись.

────────────────────────────────────────────────
ФАЗА C — POLISH + REGRESSION (1-2 години)
────────────────────────────────────────────────

C1. Усі нові поверхні мають GlassCard wrap (DESIGN_BRIEF §V).
C2. Анімації: motion-cubic-bezier(0.4, 0, 0.2, 1), 240ms standard.
C3. Text overflow: усі написи з category labels — TextOverflow.Ellipsis.
C4. Empty state на кожному списку (POI-list порожній → "поки тиша").
C5. Accessibility: contentDescription на FAB, кнопках, IconButton.
C6. Run `./gradlew test` — 0 fails. Якщо щось падає — фікси
    в тому ж коміті де реальний баг.
C7. APK build (`./gradlew :app:assembleDebug`) — зелений.
C8. Копія у /home/radxa/Downloads/phantom-companion-debug.apk.
C9. `adb install -r` + smoke 3 сценарії:
    a) open Map → POI cluster розкриваються → tap кав'ярні →
       bottom sheet → "Що тут?" → AI відповідає з контекстом місця
    b) "знайди кав'ярні поряд" → tool-call → poi-list widget
    c) Walk 100m з застосунком відкритим → breadcrumb з'явилась

────────────────────────────────────────────────
ФАЗА D — STRETCH (якщо є час) (опційно)
────────────────────────────────────────────────

D1. **MAP-AI-5: OSRM cloud routing** (~1 година)
    Без Valhalla JNI поки. Просто OSRM REST через Ktor:
    - core-net/.../map/OsrmClient.kt — REST під WireGuard
    - core-tool/.../impl/RouteTool.kt — tool route(from, to, mode)
    - Polyline decode → LineLayer на MapScreen
    - Turn-by-turn UA текст у side-sheet
    Acceptance: AI "проклади маршрут до Львова" → polyline на
    мапі + текстова інструкція "поверни праворуч на Хрещатик".

D2. **MAP-AI-11: Haptic landmark cues** (~1 година)
    ProprioceptiveTicker + RouteWaypointPattern:
    - core-embodiment/.../RouteWaypointDetector.kt
    - Patterns: Ahead-30m / Ahead-5m / At / left / right / U-turn / destination
    Acceptance: іду маршрутом — за 30м до повороту вібрація-патерн
    "поворот справа", за 5м — інша, на повороті — destination-pulse.

D3. **MAP-AI-12: Family Hive Layer** (~3 години — НЕ влізе у ніч)
    Відкласти. Велика робота: FamilyPresenceCrdt + GossipTransport
    через WireGuard mesh + opt-in grants. Краще окрема сесія.

═══ COMMIT ПРАВИЛА ═══

- Один phase = один коміт. Атомарний.
- Format:
  ```
  feat(map-ai): MAP-AI-N — short verb-led description

  Body explaining WHY (not WHAT — diff shows that). Reference
  problem solved + acceptance criterion. Note any docs touched.

  Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
  ```
  Або Opus 4.7 (1M context) для коли працює Opus сама.
- Тест per phase (pure Kotlin unit test). Без тестів — не shipі.
- KDoc на public API + WHY на non-obvious.
- No emoji у коді (тільки у operator-facing strings).
- Constitution-first: кожна tool/feature реєструє FeatureManifest.
- GHOST-aware: cloud-call перевіряє ContextEngine.state == GHOST →
  no-op + log.

═══ ЯКЩО ЩОСЬ ЛАМАЄТЬСЯ ═══

- Тест падає — почни з `./gradlew :module:testDebugUnitTest --tests
  'pkg.ClassName*'` для звуження. Стек у
  `module/build/reports/tests/testDebugUnitTest/index.html`.
- Build падає — перший error у logcat / build output. НЕ ховай
  через `--continue` чи `try/catch`.
- MapLibre callback NPE — див. maplibre_callback_nullability:
  Status: Nullable+Nullable, List: Nullable+NotNull,
  Create/Delete: NotNull-everywhere.
- AccessibilityNodeInfo проблеми — див. AppActionTraceBus +
  ScreenAwarenessService convention.
- runTest hangs — додай `if (ioScope == null)` гард навколо
  hot SharedFlow.collect (як AppActionTraceBus у StreamViewModel:681).
- Sanitizer ховає реальний текст — перевір що Gemini не обертає
  всю відповідь у `<think>...</think>` (system prompt це забороняє,
  але модель іноді ігнорує).

═══ НЕ РОБИ ═══

- НЕ тягни Valhalla JNI у цій ночі (~50MB JNI, окремий phase).
- НЕ міняй уже-зелений Phase-0 pair flow / WS contract.
- НЕ додавай нові залежності без необхідності (libs.versions.toml
  має все потрібне крім Wikipedia API client — він простий Ktor
  REST, не нова deps).
- НЕ робі git push без явного дозволу оператора.
- НЕ перезаписуй commit messages попередніх сесій.
- НЕ запускай паралельних Agent (host crash).

═══ ЗВІТ ═══

Перед кожним великим коміто — 1 рядок у chat:
"shipped MAP-AI-N · X tests · Y LOC · regression: green/red"

Після завершення (або раніше якщо контекст вибиває):
- `git log --oneline 26afdd6..HEAD` — список нових комітів
- Коротко: "X фаз shipped, Y тестів, APK MB, screenshots: A/B/C"
- ЯКЩО щось не вдалося — окремий блок "не дороблено: фаза N,
  блокер: <причина>".

═══ КОНЕЦЬ ═══

Удачі. Зроби з мапи такий орган щоб Google Maps виглядав як
табличка з координатами без життя.
============================================================
```

---

## Чому такий розмір прохання

- Read-list з 9 пунктів — без них агент почне з нуля + знов наступить на ті самі граблі (MapLibre nullability, host parallel agents)
- 3 фази (A/B/C) + опційна D — модель робить щось видиме кожні ~1.5 години, не "8 годин одну тему"
- Acceptance per фаза — щоб агент сам перевіряв чи закінчив, без гадання
- Список того що НЕ робити — без нього модель розширює scope на Valhalla JNI + ламає WS-контракт
- Звіт-формат — щоб ранком легко прочитати "що зроблено, де застрягло"

## Як запустити автономно

В новій Claude Code сесії:
```
omc autopilot "$(cat docs/NIGHT_BUILD_PROMPT.md)"
```
Або просто скопіюй блок між `===` як перше повідомлення в нову сесію (Opus 4.7).

Сесія повинна писати атомарні коміти регулярно — якщо на ранок гілка має 6-10 нових комітів `feat(map-ai): MAP-AI-N`, ніч пройшла продуктивно.
