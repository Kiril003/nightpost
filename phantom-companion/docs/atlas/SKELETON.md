# ATLAS-0.4 — скелет (D3 / D4 / D5)

Три нові контракти в `feature-stream/.../ui/map/atlas/`. Чиста Kotlin-логіка,
жодного типу MapLibre поза тонкими адаптерами, жодного дотику до наявних
файлів: 0.4 нічого не вживляє — це роблять руки ATLAS-0b.

---

## 1. ContributionRegistry (D3)

`MapContribution` — одиниця всього видимого на мапі: `id`, `tier` (ярус
z-драбини), `lensTiers` (за якими ярусами Лінзи вона гасне), клас щільності
(з ярусу), `TapSpec`, install/update-хуки.

**Ярус — це рішення, а не порядок рядків.** Ординал `MapTier` = порядок
встановлення = z-порядок. Драбина зібрана з двох джерел, де порядок уже був
записаний: install-ланцюг `MapCanvas` (ґрунт) і `mapBackStep` у зворотному
порядку (хром). Увесь ґрунт — під усім хромом (закон D1).

| # | Ґрунт | # | Хром |
|---|---|---|---|
| 1 | `GROUND_CARTOGRAPHY` | 22 | `CHROME_TOOLS` |
| 2 | `LABEL_VOICE` | 23 | `CHROME_TAP_PROVENANCE` |
| 3 | `GROUND_BACKFILL` | 24 | `CHROME_DROPPED_POINT` |
| 4 | `GROUND_FACTS` | 25 | `CHROME_THREAT_OBLAST` |
| 5 | `HILLSHADE` | 26 | `CHROME_LAYER_MARKER` |
| 6 | `RADAR` | 27 | `CHROME_SAVED_PLACE` |
| 7 | `ALERT_WASH` | 28 | `CHROME_DOSSIER` |
| 8 | `COVERAGE_HATCH` | 29 | `CHROME_OFFLINE_PANEL` |
| 9 | `EW_TOPOLOGY` | 30 | `CHROME_STYLE_PICKER` |
| 10 | `OPERATOR_PIN` | 31 | `CHROME_ROUTE_COMPARE` |
| 11 | `SEARCH_PIN` | 32 | `CHROME_TRANSIT_HUB` |
| 12 | `ROUTE_LINE` | 33 | `CHROME_ALARM_SHEET` |
| 13 | `ROUTE_REGISTER` | 34 | `CHROME_PROVENANCE_SHEET` |
| 14 | `MANEUVER_PINS` | 35 | `CHROME_ANSWER` |
| 15 | `TRANSIT` | 36 | `CHROME_SEARCH` |
| 16 | `PHANTOM_LAYERS` | 37 | `CHROME_MOUNT` |
| 17 | `POI` | | |
| 18 | `SAVED_PLACES` | | |
| 19 | `INDOOR_PLAN` | | |
| 20 | `BUILDING_EXTRUSION` | | |
| 21 | `OFFLINE_PACKS` | | |

Три яруси стоять НЕ там, де стоять сьогодні, і це свідомо: `RADAR`,
`MANEUVER_PINS`, `OFFLINE_PACKS` встановлюються ліниво з update-проходу, тож
їхня нинішня z-позиція — це те, куди прохід випадково дійшов першим. Саме це
D3 і скасовує. Решта ярусів зберігає нинішню позицію.

**Tap.** Один диспетчер замість п'яти незалежних `queryRenderedFeatures`.
`TapRank` — порядок ранніх виходів із `onMapClick`: альтернатива маршруту →
зупинка транзиту → маркер шару → збережене місце → POI → те, що намалював сам
стиль (catch-all, рівно один на реєстр). Реєстр віддає `tapLayerIds(frame)` для
ОДНОГО проходу і чисто розв'язує `resolveTap(hits, frame)`: ранг вирішує перед
відстанню, відстань — лише в межах рангу.

**Ізоляція помилок** — контракт `mapStyleStep`, піднятий у значення: кожен крок
у `runCatching`, невдалий крок повідомляється і не зупиняє прохід, `SweepReport`
можна перевірити тестом замість рядка в logcat.

---

## 2. CameraService (D4)

Один власник камери. `frame()` викликається раз на кадр і робить **щонайбільше
один** запис у `MapPort`; усі, хто хотів камеру цього кадру, отримують причину
відмови значенням (`CameraOutcome`).

Інтенти: `Focus`, `FitBounds`, `ZoomNudge`, `FollowArm`/`FollowDisarm`,
`Flyover`, `BearingReset`, `IdlePitch`. Пріоритети:
`OPERATOR > NAVIGATION > ASSISTANT > AMBIENT`.

Три правила несуть основну вагу:

- **Жест витісняє все** і продовжує витісняти `GESTURE_FRESH_MS = 400` після
  того, як пальці піднялись (мітка часу, не прапорець: пропущений
  end-callback заклинив би прапорець назавжди).
- **Nudge під час follow — це зсув конверта, а не конкурентна анімація.**
  Саме тому кнопки зуму були мертві в єдиному режимі, де водій ними
  користується. `CameraOutcome.FollowBias` віддається у
  `CameraDirector.nudgeZoomBias` — концепція `MapViewModel.nudgeZoom` була
  правильна, тут вона нарешті має арбітра.
- **One-shot у польоті не переривається нижчим пріоритетом.** Модель не ріже
  обліт маршруту; оператор — ріже.

Фізика лишається в `CameraDirector` — вона добра і не вона була зламана.
`MapPort` — тонкий адаптер (жодного типу MapLibre у пакеті), `now: () -> Long`
— щоб арбітр був JVM-тестом, а не сесією на пристрої. Мертвий зон запису
(0.4 м / 0.01 зуму / 0.15° нахилу й курсу) переїхав сюди зі слідчого циклу.

---

## 3. LensEngine (D5)

Три таблиці й один обчислювач. У обчислювачі немає жодного `when (scene)`:
**новий режим — це рядки, а не гілки.** `SceneMode` уже містить
`DRIVE/WALK/TRANSIT/THREAT` — вони розв'язуються тією ж таблицею.

`LensInput` → `LensFrame` (видимі яруси + бюджети на клас щільності + дозволені
слоти хрому + `detailCaps`).

Початкові правила — транскрипція того, що мапа робить на пристрої СЬОГОДНІ,
щоб вживлення 0b нічого не змінило видимо:

| Ярус | Ворота сьогодні |
|---|---|
| `POI`, `TRANSIT` | `zoom >= 13.0` |
| `INDOOR_PLAN` | `zoom >= 16.5` **або** відкрите досьє |
| `ALERT_WASH` | перемикач `alertsVisible` |
| `RADAR` | перемикач + `detailCap` z10 (тайли вище не існують) |
| `HILLSHADE`, `BUILDING_EXTRUSION` | право `MapTerrain3d` |
| `BUILDING_EXTRUSION` | гаситься, коли є indoor-план |
| `COVERAGE_HATCH` | БЕЗ прав — «тут не завантажено» ліцензія ховати не може |
| `EW_TOPOLOGY` | ніколи (слід прибрано з землі; повернення — одне поле входу) |
| `ZOOM_DECK`, `TOOLS_CAPSULE` | не під час ведення |
| `MANEUVER_BANNER`, `ETA_STRIP` | лише під час ведення |
| `ALERT_PILL` | лише коли гучно над власною областю |

**Бюджети.** Живі сцени (`CALM/EXPLORE/GUIDE`) не нормують нічого — сьогодні
мапа теж не нормує, і 0b не має права змінити те, що бачить оператор. Рядки
для `DRIVE`/`THREAT`/`WALK` існують, щоб довести: механізм — це дані.
`BASEMAP/TERRAIN/WASH/OPERATOR/ROUTE/CHROME` не нормуються ніколи.

---

## 4. Як 0b це вживляє

1. **Порт стилю.** Реалізувати `MapStyleHandle` як обгортку
   `org.maplibre.android.maps.Style`; кожен нинішній `mapStyleStep("…") { … }`
   стає `MapContribution` з тим самим id — 21 ґрунтова контрибуція.
2. **Установник.** Блок `setStyle { … }` у `MapCanvas` замінюється на
   `registry.install(handle)` + `registry.update(handle, frame)`. Порядок
   більше не з рядків.
3. **Tap.** П'ять ланцюгових `queryRenderedFeatures` → один прохід по
   `registry.tapLayerIds(frame)` у товщину `TAP_TOLERANCE_DP`, далі
   `resolveTap`. Гілки `if (hits.isNotEmpty())` стають обробниками
   контрибуцій.
4. **Камера.** `MapPort` реалізується поверх `MapLibreMap` (+ `RouteFlyover`
   для `flyover`). ~15 писарів (`MapChrome:502/509/488`, `MapScreen:893/905/915/1029`,
   `SheetHost:481`, `MapCanvas:517/686/796/1301/1322`, `RouteFlyover:113`)
   стають `service.submit(...)`. Кадровий цикл `MapCanvas:1393-1750` віддає
   позу директора у `service.frame(pose)` і слухає `FollowBias`.
   `MapUiState.cameraGestureAtMs` / `CAMERA_GESTURE_FRESH_MS` видаляються —
   про свіжість жесту питають сервіс.
5. **Лінза.** `deriveMapScene` живить `LensInput.scene`; ~35 розкиданих булів
   гаснуть на користь `frame.lit(tier)` / `frame.allows(slot)`.

Знайдено дорогою: `MapScreen:893/905/915` (тапи по результатах пошуку) летять
камерою, але НЕ знімають `followMode` — у ведінні цикл повертає камеру назад
наступного кадру. `CameraIntent.Focus(disarmsFollow = true)` лагодить це
безкоштовно.

---

## 5. Два храповики, яких вимагає майстерплан

Обидва — структурні тести в стилі наявних `CameraReleaseFirewallTest` /
`MapDepthFirewallTest`: читають `*/src/main/**/*.kt` як файли, гасять коментарі
та рядкові літерали (інакше KDoc, який пояснює правило, сам його «порушує»),
і мають allow-list, який має лише скорочуватись.

**ЗАКОН ВОРІТ:** таке читання невидиме для up-to-date перевірки Gradle —
запускати з `--rerun-tasks`, як це робить `scripts/verify.sh`.

### R1. Жодного прямого запису камери поза CameraService

Заборонені лексеми: `animateCamera`, `easeCamera`, `moveCamera`,
`cameraPosition =`, `CameraUpdateFactory.`
Allow-list: єдина реалізація `MapPort` (+ `RouteFlyover`, доки вона виконує
власний багатоетапний обліт через порт). Падіння тесту називає файл і рядок.

### R2. Жодних геоприв'язаних Compose-оверлеїв

Заборонено: `projection.toScreenLocation` / `fromScreenLocation` у файлі, що
містить `@Composable`. Проєкцію для hit-test читати можна — але з файлів без
Compose, і саме тому правило формулюється через `@Composable`, а не через саму
проєкцію.

Порахований стартовий allow-list — **рівно 4 файли** (сьогодні; кожен із них і
є джерелом деламінації з §1 вердикту):

- `indoor/IndoorFloorOverlay.kt` — 6 викликів проєкції
- `MapCanvas.kt` — 4
- `HazardMarkerOverlay.kt` — 1
- `transit/TransitVehicleMarkerOverlay.kt` — 1

Легітимний читач проєкції поза Compose сьогодні один: `nav/RenderedRoads.kt`
(знімає центр камери для снапа до дороги, нічого не малює).

Храповик фіксує число («не більше 4»), тож кожен НОВИЙ оверлей падає одразу, а
кожна міграція в шари стилю опускає планку. Застереження: перевірка на рівні
файлу — приблизна: не-Compose helper, доданий у файл із allow-list, пройде
мовчки. Для 0b цього досить, бо всі чотири файли й так переселяються.

Третій кандидат, коли Лінза буде вживлена: `Property.visibility` поза
контрибуціями (майстерплан §5).
