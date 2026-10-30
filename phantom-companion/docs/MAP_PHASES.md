# MAP-* — Phantom Companion Map Stack

> Зведений документ для **MAP-1..MAP-6** (плюс залежний `W1-4`).
> Решта phase-IDs (Tier-1..27, Phase 0..3, BW-N, MIC-N, SV-N) — у
> `ROADMAP.md` і `VISION_NEXT.md`. Цей файл — про мапо-шар окремо.
>
> До MAP-1 повноекранна Map-поверхня була glass-card з 200dp
> прев'юшкою `demotiles.maplibre.org` — "OSM континенти без міст".
> Операторська реакція: *"це не продукт"*. MAP-* перетворює це
> на повноцінний tile-стек з 4 провайдерами, geocoding, offline
> регіонами, AI-friendly in-chat віджетом та race-free style swap.

## Зміст

- [Статус](#статус)
- [MAP-1 — tile provider catalogue + day/night + operator pin](#map-1)
- [MAP-2 — Nominatim search](#map-2)
- [MAP-3 — reverse-geocoded address chip](#map-3)
- [MAP-4 — offline region cache](#map-4)
- [MAP-5 — `MapStyleConfig` thread у in-chat віджет](#map-5)
- [MAP-6 — GeoJSON marker layers](#map-6)
- [W1-4 — 5-step location chain (залежний)](#w1-4)
- [Архітектура: повна карта файлів](#архітектура-повна-карта-файлів)
- [Ключові архітектурні рішення](#ключові-архітектурні-рішення)
- [Що далі (MAP-7+, candidate ідеї)](#що-далі)

---

## Статус

| Phase | Commit | Стан | Файлів |
|---|---|---|---|
| **MAP-1** | `bafe864` | ✅ shipped | `TileProvider.kt`, `MapPreferences.kt`, `MapSettingsScreen.kt`, `MapScreen.kt` (rewrite) |
| **MAP-2** | `6e226fe` | ✅ shipped | `NominatimClient.kt`, MapScreen search overlay |
| **MAP-3** | `58cfa86` | ✅ shipped | MapScreen reverse-geocode chip |
| **MAP-4** | `d4cf4e0` | ✅ shipped | `OfflineRegionsManager.kt`, MapSettings регіонна секція |
| **MAP-5** | pending | 🟡 working tree | `MapStyleConfig.kt` (new), `MapScreen.kt` (race fix), `MapWidget.kt` (thread config) |
| **MAP-6** | pending | 🟡 working tree | `MapWidget.kt` (GeoJSON markers) |
| **W1-4** | `87ad236` | ✅ shipped | `LocationContextProvider.kt`, AppContainer chain wiring |

**Сумарно:** 5 закомічених коммітів + 2 в `git status` (working tree), ~2560 рядків мап-коду в 13 файлах.

---

## MAP-1

> *Real tile provider abstraction + day/night + operator pin*

**Exit criterion:** оператор відкриває Map → бачить реальні OSM-street з міським масштабом, без жодного setup. Перемикає тему — мапа адаптується. Бачить свою позицію через помаранчевий pin.

### Скоп

- `TileProviderId` enum з 4 провайдерами:
  - `OPENFREEMAP` (default) — без ключа, OSM-vector (Liberty / Positron)
  - `MAPTILER` (Bright / Dark Matter) — потрібен key
  - `STADIA` (Alidade Smooth / Smooth Dark) — потрібен key
  - `CUSTOM` — operator-pasted style.json URL
- `resolveStyleUrls(provider, key, customUrl)` — fallback на OpenFreeMap коли key порожній або CUSTOM URL пустий. Перший запуск завжди працює.
- `MapPreferences` (Keystore-backed) — `providerId`, `apiKey`, `customUrl` як StateFlows. Mirror of `VoicePreferences`.
- `MapSettingsScreen` — 454 рядки UI: provider chip-row, key field, custom-URL field, тест активного style, секція кешованих регіонів (MAP-4).
- `MapScreen` rewrite (656 рядків): theme-aware setStyle, operator pin layer, locate button, bottom-rail back/share.

### Ключові рішення

- **OpenFreeMap default** — щоб перший запуск працював без жодного ключа. MapTiler / Stadia потребують реєстрації, тому як default не годяться.
- **OpenFreeMap не має справжнього dark стилю** — Positron це найкраще наближення (low-saturation grayscale). Хто хоче реально темну мапу — переключається на MapTiler з ключем.
- **Stable id strings** — `OPENFREEMAP`, `MAPTILER`, `STADIA`, `CUSTOM` персистяться в `MapPreferences`. Додавання нового провайдера НЕ перенумеровує існуючі.

---

## MAP-2

> *Nominatim search with rate-limited polite client*

**Exit criterion:** оператор друкує "Хрещатик" → мапа знаходить → камера летить → помаранчевий search-pin падає.

### Скоп

- `NominatimClient` — HttpURLConnection-based (без Ktor у `:feature-stream` навмисне, щоб не тягнути додатковий dep):
  - `User-Agent: PhantomCompanion` (вимога OSM Nominatim usage policy)
  - process-global `Mutex` + timestamp гарантує **≤1 request/1.1 s**
  - forward `search(query)` для пошуку
  - reverse `reverse(lat, lon)` — готується до MAP-3
- `MapScreen` search overlay:
  - glass-pill text field над back-row
  - submit-on-IME-search action
  - inline progress bar поки запит летить
  - dropdown результатів як glass-card
  - tap → animateCamera + search-pin drop + dropdown collapse + keyboard hide
- Search-pin шар — окремий від operator-pin шару, обидва переживають style swap (install-on-setStyle pattern).

### Чому Nominatim, не Google Places

OpenStreetMap free, без quotas (якщо рейт-ліміт дотримано), узгоджується з vision: уся локалізаційна стека — без cloud-залежних API. Платний tier для production = self-hosted Nominatim або Pelias.

---

## MAP-3

> *Reverse-geocoded operator address chip*

**Exit criterion:** оператор бачить у нижньому лівому куті мапи glass-chip з адресою його pin. Pin рухається на >150 m — chip оновлюється з 5-сек debounce. Tap chip → камера recentre на оператора.

### Скоп

- Haversine відстань з останньої reverse-lookup точки.
- Threshold 150 m + debounce 5 s гарантують надійне дотримання Nominatim 1Hz policy навіть у машині на швидкості.
- Chip компонент у `MapScreen`, рендериться над bottom-rail.
- Tap action: same як locate button.

---

## MAP-4

> *Offline region cache via MapLibre OfflineManager*

**Exit criterion:** оператор бачить cloud-button у Map → tap → поточний viewport зберігається як offline pack → видно у Settings → Map → "КЕШОВАНІ РЕГІОНИ" → працює без мережі.

### Скоп

- `OfflineRegionsManager` (245 рядків) — wrapper навколо `org.maplibre.android.offline.*`:
  - **Coroutine adapters** через `suspendCancellableCoroutine` для 4 callback API-шок (List / Status / Create / Delete)
  - `regions: StateFlow<List<RegionInfo>>` — UI-safe snapshot з name, completed/total tile counts, on-disk MB, progress fraction, `DownloadState`
  - `createRegion(name, OfflineBounds, styleUrl)` — kicks off `STATE_ACTIVE` download
  - `delete(id)` — wipe + refresh
  - `refresh()` — re-poll кожного pack
- **Region names як JSON у metadata blob** — оператор бачить "регіон · 11.05 02:42" замість opaque ID.
- **MapLibre callback nullability inconsistency** — кожен interface має різні nullability annotations (Status: Nullable+Nullable, List: Nullable+NotNull, Create/Delete: NotNull-everywhere). Overrides повинні матчити EACH interface exactly.
- MapScreen "save current view" cloud button з right-rail.
- `OfflineBounds` — MapLibre-free data class, щоб nav-граф не тягнув MapLibre у `:app` compile classpath.
- MapSettings "КЕШОВАНІ РЕГІОНИ" секція: empty-state hint, per-pack rows з `X% · Y.Y МБ · A/B тайлів` + linear progress while downloading, coral delete button, auto-refresh кожні 5 s.
- `AppContainer.offlineRegions` як lazy — `MapLibre.getInstance()` не fire поки оператор не відкриє Map/MapSettings.

### Deferred

- **WorkManager promotion** — region downloads вже survive app kill на SQLite-cache рівні; foreground-service wrapper для active progress — SV-N polish, не MAP-4.

---

## MAP-5

> *`MapStyleConfig` thread у in-chat віджет* — 🟡 working tree, ще не committed

**Exit criterion:** AI шле `{type:"map", payload:{...}}` widget → в чаті рендериться з тим же провайдером якого оператор обрав у Settings → Map (не з пустим demotiles). Theme swap також застосовується.

### Скоп

- `MapStyleConfig` — data class з `(providerId, apiKey, customUrl)` + `resolveStyleUrl(isDark)`. Default = OpenFreeMap.
- `LocalMapStyleConfig: ProvidableCompositionLocal<MapStyleConfig>` — thread у Compose дерево.
- `MainActivity` installs live config через `CompositionLocalProvider`, спостерігаючи `AppContainer.mapPrefs`.
- `MapScreen.kt` **race-condition fix**: раніше `setStyle()` викликалось в `getMapAsync` callback ПЛЮС у `LaunchedEffect(targetStyleUrl)` що bailed якщо `mapRef == null` — на cold launch стиль інстолл міг "програти race" з LaunchedEffect no-op, лишаючи operator-pin / search-pin не встановленими. Тепер єдине джерело правди — `LaunchedEffect(mapRef, targetStyleUrl)`. Plus `cameraInitialized: Boolean` flag — style swap не yank-ає камеру назад на дефолтну точку, якщо оператор панорамує.
- `MapWidget.kt` читає `LocalMapStyleConfig.current` замість прибитого demotiles. Theme swap re-bind через `LaunchedEffect(mapRef, styleUrl)`.

### Регресія яку фіксить

Шипалось до MAP-5: AI відповідає `{"type":"map", "center":[50.45,30.52], "zoom":14}` → в стрімі рендериться сіра планета без вулиць → оператор думає що щось зламано.

---

## MAP-6

> *GeoJSON marker layers* — 🟡 working tree, ще не committed

**Exit criterion:** AI шле map widget з `markers:[{lat, lon, label}, ...]` → точки рендеряться як помаранчеві кола з підписами поверх vector-tile features → переживають `setStyle` swap → escape-safe лейбли.

### Скоп

- Заміна deprecated `MapLibreMap.addMarker()` на:
  - `GeoJsonSource("phantom-markers-source")` з `FeatureCollection`
  - `CircleLayer("phantom-markers-dot")` — помаранчева крапка (`#FF8A65`, same як operator/search pins)
  - `SymbolLayer("phantom-markers-label")` з `Expression.get("label")`
- **GeoJSON escape**: вручну escapeJson() handles `"`, `\`, `\n`, `\t` — `kotlinx.serialization.json.encodeToString` тут не годиться бо GeoJSON має точну спеку coordinates як `[lon, lat]` JsonArray, не object.
- **Coordinate order pinning** — `[lon, lat]` per GeoJSON spec, не `[lat, lon]`. Mixing-up малює marker на іншому континенті — є regression test що pin'ить.
- Markers shar встановлюється у `setStyle` callback (same pattern як circles + operator pin) — переживає style swap.

### Test coverage

`MapStyleConfigTest` має 4 GeoJSON-specific тести:
- well-formed structure + `lat/lon` order
- escape `"` and `\` коректно
- handle `\n` and `\t` without breaking parse
- empty marker list returns valid empty FeatureCollection

---

## W1-4

> *5-step location chain with IP-geo fallback + GHOST gate* — closely related, committed `87ad236`

**Exit criterion:** AI prompt enrichment блок `[LOCATION:...]` має значення навіть на пристрої без GPS / без останнього location fix.

### Chain (priority order)

1. **MobileSensorAdapter GPS** — live `:core-sensor` sample
2. **LocationManager PASSIVE_PROVIDER** — last-known від інших додатків
3. **LocationManager NETWORK_PROVIDER** — last-known через WiFi/cell
4. **Cached IP-geo** — 30-min TTL, попередній `ipapi.co` lookup
5. **Fresh `ipapi.co` lookup** — cloud fallback через `IpApiClient`

### GHOST hard-gate

Якщо `contextEngine.state == GHOST` — **тільки on-device джерела** (1-3). Cloud fallback (4-5) hard-gated: vault-of-traces сесія НЕ повинна тихо leak'ати оператор IP до geo-service.

---

## Архітектура: повна карта файлів

### Map UI core (5 files, 1207 LOC)

- `feature-stream/src/main/java/.../ui/map/MapScreen.kt` — 656 LOC
- `feature-stream/src/main/java/.../ui/map/TileProvider.kt` — 97 LOC
- `feature-stream/src/main/java/.../ui/map/NominatimClient.kt` — 116 LOC
- `feature-stream/src/main/java/.../ui/map/OfflineRegionsManager.kt` — 245 LOC
- `feature-stream/src/main/java/.../ui/map/MapStyleConfig.kt` — 43 LOC

### In-chat widgets (4 files, 555 LOC)

- `feature-stream/src/main/java/.../ui/widget/MapWidget.kt` — 267 LOC
- `feature-stream/src/main/java/.../ui/widget/ArMapWidget.kt` — 77 LOC (Tier 2.5 AR stub)
- `feature-stream/src/main/java/.../ui/widget/RouteTileFetcher.kt` — 90 LOC
- `feature-stream/src/main/java/.../ui/widget/RouteTileMath.kt` — 121 LOC

### Settings + prefs (2 files, 536 LOC)

- `app/src/main/java/.../ui/settings/MapSettingsScreen.kt` — 454 LOC
- `app/src/main/java/.../data/MapPreferences.kt` — 82 LOC

### Tests (2 files, ~260 LOC)

- `feature-stream/src/test/java/.../ui/map/TileProviderTest.kt` — fallback / key-required contract
- `feature-stream/src/test/java/.../ui/map/MapStyleConfigTest.kt` — default OpenFreeMap, theme variants, MapTiler key embed, GeoJSON marker contract (8 tests)

### Залежне (indirect)

- `core-ai/.../context/LocationContextProvider.kt` — W1-4 chain
- `core-net/.../webgeo/IpApiClient.kt` — IP-geo cloud client
- `feature-stream/.../StreamViewModel.kt` — wire AI map response → MapWidget
- `feature-stream/.../ui/widget/WidgetRenderer.kt` — `{type:"map"}` dispatch
- `app/.../data/AppContainer.kt` — DI (mapPrefs, offlineRegions, ipApiClient, locationProvider)

**Сумарно:** 13 безпосередніх файлів, ~2560 LOC + 5 indirect файлів торкнуто.

---

## Ключові архітектурні рішення

1. **Чотири tile-провайдери, fallback chain** — оператор контролює baseline witout setup; платні провайдери для якості.
2. **Keystore-backed prefs** — API key чутливий, але prefs самі по собі (provider id, customUrl) — ні. Шифруємо все одно для consistency з `VoicePreferences`.
3. **Nominatim з process-global rate-limit** — 1.1s mutex замість per-call sleep, бо search + reverse share quota.
4. **In-chat widget читає той самий конфіг що full-screen** — `LocalMapStyleConfig` — operator не вирівнює налаштування у двох місцях.
5. **GeoJSON layers, не deprecated addMarker** — survive style swaps, z-order з vector-tile features коректно.
6. **GHOST gate на cloud IP-geo** — vault-of-traces сесія не leak'ає оператор IP.
7. **No Ktor in `:feature-stream`** — HttpURLConnection для Nominatim. Свідомо — щоб не тягнути client у feature шар який і так uses core-net через `:app` DI.
8. **MapLibre-free DTO для nav-граф bounds** — `:app` compile classpath не тягне MapLibre runtime з UI шару.
9. **Region names як JSON у metadata blob** — operator-readable, не opaque IDs.
10. **Match nullability per-callback-interface** — MapLibre 11.5.x має inconsistent Nullable annotations між OfflineRegion callbacks. Per-method вирівнювання, не глобальний `@Nullable`.

---

## Що далі

Кандидати для **MAP-7+** (не committed, не plan'ed):

- **MAP-7 — heatmap layer** — для location-history overlay (Tier 24 time-machine)
- **MAP-8 — 3D buildings** — vector-tile features extrude (where provider supports — MapTiler streets-v2 має)
- **MAP-9 — terrain/elevation** — для hiking/route UX
- **MAP-10 — WardrivingOverlay** — `mentioned in MAP-2 commit message as out-of-scope for MAP-2` — WiFi/BLE signal-strength heatmap operator-recorded
- **MAP-11 — Family-hive presence pins** — phantom-trusted contacts їх позиції з consent (Tier 18-C + Tier 27 mesh)
- **AR-bridge** — `ArMapWidget` зараз stub; реальний ARCore overlay = Tier 2.5/Phase 4-D
- **WorkManager promotion for active offline downloads** — foreground-service wrapper для status visibility (deferred from MAP-4)

Кожен MAP-N — окремий commit з власним exit criterion. Phase IDs живуть у commit messages + KDoc, не в окремих task-tickets.

---

> "Мапа — це 60% вашого presence-app. Якщо вона виглядає як мокап — все інше виглядає як мокап."
> — operator, після MAP-1.
