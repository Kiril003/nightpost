# PHANTOM Companion — Map Roadmap

> Єдиний документ-істина про мапу у companion. Закриті фази + чергові
> + далекий горизонт. Раніше ця інформація була розпорошена між
> commit-меседжами, KDoc-ами в `MapScreen.kt`/`MapWidget.kt`, рядком
> у ROADMAP.md, та лайв-аудитом у чаті. Цей файл — *одна точка істини*.
>
> **Поточна гілка:** `companion-v2-phase-0`
> **Найсвіжіший shipped:** MAP-7 (2026-05-12) — Marker Tap → Bottom-Sheet
>
> Серія MAP-* — це окрема трекова нумерація для робіт навколо мапи,
> паралельна до основної Tier-нумерації (`ROADMAP.md`, `VISION_NEXT.md`,
> `SYMBIOTE_VISION.md`). MAP-1..MAP-7 — закрито. MAP-8..MAP-20 —
> інтеграційна верхівка ("Map-as-organ"). MAP-21+ — фронтир.

---

## Зміст

- [Філософія мапи у симбіотичному phantom-і](#філософія-мапи)
- [Поточний стан (2026-05-12)](#поточний-стан)
- [Архітектурна карта файлів](#архітектурна-карта-файлів)
- [Закриті фази (MAP-1..MAP-7)](#закриті-фази-map-1map-7)
  - [MAP-1 — Real tile provider catalogue](#map-1)
  - [MAP-2 — Nominatim search](#map-2)
  - [MAP-3 — Reverse-geocoded operator address chip](#map-3)
  - [MAP-4 — Offline regions cache](#map-4)
  - [MAP-5 — In-chat MapWidget unified with MapScreen](#map-5)
  - [MAP-6 — SymbolLayer markers](#map-6)
  - [MAP-7 — Marker tap → bottom sheet ✅](#map-7)
- [Інтеграційна верхівка (Map-as-organ)](#інтеграційна-верхівка-map-8map-20)
  - [MAP-8 — Polyline + route rendering](#map-8)
  - [MAP-9 — Live breadcrumb history](#map-9)
  - [MAP-10 — Bearing arrow + camera follow](#map-10)
  - [MAP-11 — Inline MapWidget → fullscreen](#map-11)
  - [MAP-12 — AI place-mention auto-pin](#map-12)
  - [MAP-13 — `mapEvents` WS subscriber](#map-13)
  - [MAP-14 — Family Hive members layer](#map-14)
  - [MAP-15 — My Places (SQLCipher)](#map-15)
  - [MAP-16 — Overpass POI overlay](#map-16)
  - [MAP-17 — Voice → map gesture](#map-17)
  - [MAP-18 — DecisionDiary places](#map-18)
  - [MAP-19 — AR threat layer](#map-19)
  - [MAP-20 — Handoff sheet UI](#map-20)
- [Фронтир (MAP-21+)](#фронтир-map-21)
- [Бекенд-залежності (phantom-os)](#бекенд-залежності-phantom-os)
- [Кросс-посилання з Tier-планів](#кросс-посилання)

---

## Філософія мапи

Карта у companion **не є самоціллю**. Це — *просторовий вимір* симбіоту.
Окрема апка-мапа є — Google Maps, OsmAnd, Apple Maps. Phantom-companion
*не* конкурує з ними і *не* прагне бути "ще одним картовим клієнтом".

Що phantom-companion-мапа робить **унікально**:

1. **Map ↔ AI bridge** — AI що говорить про місце → мапа реагує. У звичайних мапах AI окремий, мапа окрема.
2. **Multi-modal context** — мапа знає що ти зараз говориш, що чуєш, як пульс. Інші мапи знають тільки GPS.
3. **Family trust graph** — точки інших операторів з phantom-mesh (явним consent), не комерційні sharing flows.
4. **Decision Diary integration** — рішення прив'язані до місць. Мама-мапа місць рішень.
5. **Memory Palace anchor** — точки на мапі несуть episodic memory (Tier 19).
6. **Operator-advocate** — мапа не показує реклами/sponsored places. Constitutional аксіома 29-A.
7. **Offline-first** — повний функціонал без інтернету через OfflineManager + on-device геокодер + cached POI.

> Мапа — це не "де я". Мапа — це **"де я був, де я буду, з ким, чому,
> і як це з'єднується із усім іншим в моєму phantom-і"**.

---

## Поточний стан

```
gilka:                  companion-v2-phase-0
останній MAP-комміт:    d4cf4e0 (MAP-4) → +d (MAP-5) → +d (MAP-6) → +d (MAP-7) [pending commit]
build:                  :app:assembleDebug ✅ зелено (2026-05-12)
tests:                  :feature-stream:test ✅ 8/8 у MapStyleConfigTest
                                            ✅ 9/9 у TileProviderTest
adb status:             RFCY61HG0WB · installed local.phantom.companion.debug
runtime:                PID alive, MapLibre gestures live, BottomSheet interactive
```

### Робочий MapWidget payload-схема

AI може повертати у Stream:

```json
{
  "type": "map",
  "payload": {
    "latitude": 50.4501,
    "longitude": 30.5234,
    "zoom": 14.0,
    "markers": [
      {"lat": 50.4501, "lon": 30.5234, "label": "Майдан"},
      {"lat": 50.4475, "lon": 30.5232, "label": "Хрещатик 22"}
    ],
    "circles": [
      {"lat": 50.4501, "lon": 30.5234, "radiusM": 500, "color": "#FF8A65"}
    ]
  }
}
```

### Operator entry points

| Як | Звідки |
|---|---|
| Inline MapWidget у Stream | AI повертає `{type:"map"}` |
| Повноекранна MapScreen | SurfaceTray longpress → "Карта" → `Routes.Map` |
| Налаштування | Settings → Map → провайдер/ключ |
| RouteWidget (окремий тип) | AI повертає `{type:"route"}` — статичний preview + geo: intent |

---

## Архітектурна карта файлів

```
feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/
├── map/
│   ├── MapScreen.kt              [780+ LOC] Повноекранна мапа з пошуком/гео/offline/interaction
│   ├── TileProvider.kt           [98 LOC]   Каталог OPENFREEMAP/MAPTILER/STADIA/CUSTOM
│   ├── MapStyleConfig.kt         [44 LOC]   CompositionLocal + data class (MAP-5)
│   ├── NominatimClient.kt        [116 LOC]  Rate-limited polite client (1 Hz)
│   ├── POIOverlay.kt             [150 LOC]  Clustering + Marker rendering (MAP-6/7)
│   └── OfflineRegionsManager.kt  [245 LOC]  MapLibre OfflineManager wrapper
└── widget/
    ├── MapWidget.kt              [275 LOC] In-chat MapWidget (MAP-5 + MAP-6)
    ├── ArMapWidget.kt            [42 LOC]  AR overlay scaffold (Phase 4-D)
    └── WidgetRenderer.kt         [101 LOC] Реєстр type → composable

app/src/main/java/local/phantom/companion/
├── data/
│   └── MapPreferences.kt         [82 LOC]  EncryptedSharedPreferences для prefs
├── ui/settings/
│   └── MapSettingsScreen.kt      [454 LOC] Provider chips + key field UI
├── navigation/
│   ├── Routes.kt                  Routes.Map визначення + Stream prompt support
│   └── PhantomNavGraph.kt         MapDestination wrapper з locationProvider polling + MAP-7 actions
└── MainActivity.kt:73-103         LocalMapStyleConfig provider installation

feature-stream/src/test/java/local/phantom/companion/feature/stream/ui/map/
├── TileProviderTest.kt           [85 LOC]  9 тестів каталогу
└── MapStyleConfigTest.kt         [144 LOC] 8 тестів CompositionLocal + GeoJSON
```

---

## Закриті фази (MAP-1..MAP-7)

### MAP-1
**Real Tile Provider Abstraction + Day/Night + Operator Pin** · *bafe864*

**Що зроблено:**
- 4-провайдерний каталог: OpenFreeMap (default, key-less), MapTiler, Stadia, Custom URL
- Theme-aware: `theme.id == "amber-night"` → dark style URL
- Operator pin: 2-layer (halo + dot) через `GeoJsonSource`
- `LocationProvider` polling @ 1.5 Hz зі 5-step fallback chain

### MAP-2
**Nominatim Search with Rate-limited Polite Client** · *6e226fe*

**Що зроблено:**
- `NominatimClient` з polite-Hz rate limiter (≤1 req/sec)
- Search overlay UI у MapScreen: glass-pill з search icon
- Tap результат → `animateCamera` + orange `SEARCH_LAYER` pin

### MAP-3
**Reverse-Geocoded Operator Address Chip** · *58cfa86*

**Що зроблено:**
- При русі operator-pin на ≥150 m + 5-sec debounce → `nominatim.reverse(lat, lon)`
- Address chip у bottom-left glass-card; tap → recenter на operator

### MAP-4
**Offline Region Cache via MapLibre OfflineManager** · *d4cf4e0*

**Що зроблено:**
- `OfflineRegionsManager` — обгортка над MapLibre OfflineManager
- IconButton у MapScreen знімає `OfflineBounds` → `createRegion`
- Помилки емітяться через `PhantomErrorBus`

### MAP-5
**In-chat MapWidget unified with MapScreen**

**Що зроблено:**
- **`MapStyleConfig` data class** + **`LocalMapStyleConfig` CompositionLocal**
- `MapWidget` тепер читає `LocalMapStyleConfig.current` замість demotiles
- Race fix у MapScreen: `LaunchedEffect(mapRef, targetStyleUrl)`

### MAP-6
**SymbolLayer Markers (replaces deprecated addMarker)**

**Що зроблено:**
- `GeoJsonSource` + `CircleLayer` + `SymbolLayer`
- `buildMarkersGeoJson()` із JSON-safe escape для лейблів
- Markers рендеряться у тому самому setStyle callback

### MAP-7 ✅
**Marker Tap → Bottom-Sheet Actions** · ⚡ (~1 день) landed 2026-05-12

**Acceptance:**
- Tapping a single POI marker opens a Glass-styled BottomSheet.
- Displayed name and category/subcategory.
- "Ask AI" returns to Stream with `[LOCATION:lat,lon]` injection.
- "Open in Maps" triggers external geo intent.

**Скоп:**
- ✅ Add `MapLibreMap.addOnMapClickListener` + queryRenderedFeatures на `POI_LAYER_ID`
- ✅ Якщо tap landed на маркер → bottom-sheet з actions:
  - ✅ "Запитати ШІ" → injection [LOCATION:lat,lon] (nav back + prompt)
  - ✅ "Відкрити у [Google Maps / OsmAnd]" → `Intent.ACTION_VIEW geo: URI`
  - ✅ "Зберегти як місце" → MAP-15 My Places (Stub/Action item)

---

## Інтеграційна верхівка (MAP-8..MAP-20)

### MAP-8
**Polyline + Route Rendering** · 🔥 ✅
- ✅ Інтегровано `LineLayer` у `MapScreen` та `MapWidget`
- ✅ Реалізовано shared `MapGeoJsonHelpers` для рендерингу маршрутів
- ✅ Підтримка динамічної зміни кольору та ширини лінії (AI-controlled)
- ✅ Unit-тести для валідації GeoJSON-контракту

... [rest of file unchanged] ...
