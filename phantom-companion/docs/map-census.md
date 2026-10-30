# Перепис правди мапи — 24.09.2026

Гілка `cloud/map-census`, від `origin/main` `b88e846`. Лише перепис: код у цьому
коміті не змінено. Метод той самий, що й для месенджера: кожен елемент мапи й
навігації — **що обіцяє** (текст, іконка, назва) → **що робить насправді** →
**доказ** (файл:рядок, тест) → **вердикт**.

Вердикти:
- **лишити** — обіцянка дорівнює поведінці, є доказ;
- **відняти** — обіцяє більше, ніж робить (або заборонене межами безпеки):
  видаляється, не ховається прапорцем;
- **виправити** — робота справжня, але слово/форма бреше або кричить; правиться
  на кроці «віднімання» (слово) чи «форма» (колір, поверхня).

Шляхи скорочено: `map/` = `feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/`,
`nav/` = `core-nav/src/main/java/local/phantom/companion/core/nav/`,
`geo/` = `core-geo/src/main/java/local/phantom/companion/core/geo/`,
`tool/` = `core-symbiote/src/main/java/local/phantom/companion/core/tool/impl/`,
`ToolRegistryFactory` = `app/src/main/java/local/phantom/companion/data/ToolRegistryFactory.kt`.
Тести — `feature-stream/src/test/.../ui/map/`, `core-nav/src/test/...`, `core-geo/src/test/...`.

Обсяг: `ui/map` — 188 файлів / 52 672 рядки коду, 153 тестові файли;
`core-nav` — 31 файл / 5 969 рядків; `core-geo` — 48 файлів / 8 632 рядки.
Прочитано повністю: хром (`MapChrome`), панель інструментів, аркуш точки,
кабіна водія, транспортний хаб, бриф загрози, картки дос'є, ШІ-інструменти
навігації в `ToolRegistryFactory` і рушії під ними. Решта — вибірково, за
пошуком обіцянок (емодзі, латина, «наживо», «чорна скринька», «Starlink»…).

---

## 1. Межі безпеки (перевірено першими)

Правило: жодних шарів щільності незнайомих людей, жодних позицій військ / ППО /
місць влучань — ні додавати, ні лишати.

| Що знайдено | Що робить | Доказ | Вердикт |
|---|---|---|---|
| Чип «блокпост» у ряді позначок аркуша точки | Ставить позначку «блокпост» на мапу на 2 год; шар позначок синхронізується між операторами «живою площиною» (ATLAS-4) — тобто це краудсорс позицій блокпостів | `map/SheetHost.kt:464`; `core-data/.../spatial/HazardMarks.kt:7-18` («third hand — the live plane, syncing marks between operators») | **відняти** |
| Кнопка «🛑 Блокпост» у кабіні водія | Той самий запис через `reportHazard` | `map/cockpit/CarCockpitSheet.kt:736`; `map/MapScreen.kt:1275-1283` | **відняти** |
| Факт «🪖 блокпост» у дос'є з тегу `military=checkpoint` (і `highway=checkpoint`, що в OSM означає пункт перевірки на туристичній стежці) | Називає військовий об'єкт на картці місця | `map/dossier/PlaceDossier.kt:134` | **відняти** |
| Штрихування, тепла заливка й пунктирний обвід військової землі (`landuse=military`) | Підсвічує військові території поверх загальної заливки, з z9–z10 | `map/MapGroundBackfill.kt`, `map/MapGroundCartography.kt`, `tools/obsidian/.../LayersGround.kt` | **відняти** (рішення власника на кроці віднімання): військова земля малюється як будь-яка інша; сторож `MapSafetyBoundaryTest` |
| Типи повітряної тривоги (БПЛА, ракети, КАБ, балістика…) | Тип тривоги по області/району з офіційної стрічки alerts.in.ua / ubilling — це стан СИРЕНИ, не позиція ворога чи ППО | `map/LiveMapFeeds.kt:50`, `map/SirenAlerts.kt:83`, `AirAlertWatchTest`, `SirenAlertsTest` | **лишити** (емодзі — див. §4) |
| `barrier=checkpoint` з OSM (КПП, прикордонний контроль) на маршруті й у POI | Публічна дорожня інфраструктура з OSM; попередження «КПП на маршруті» | `nav/ThreatWatch.kt:86`, `nav/OverwatchNarrator.kt:94-98`, `RouteBandMathTest:379-391` | **лишити**, відкрите питання власнику (див. кінець) |
| «Слід РЕБ» (`EwTrail`) | Лише власний трек пристрою, забарвлений вироком довіри до GPS; не синхронізується | `map/nav/EwTrail.kt:9-24`, `EwTrailTest` | **лишити** |
| Шари щільності людей | Не знайдено. Люди на мапі — лише ті, хто сам поділився позицією (`peers/PeerWhereLayer.kt`, `WhoSeesMeSheet.kt`) | `PeerLayerFirewallTest` | **лишити** |
| `CassandraPredictor` — «нічне вікно ударних БПЛА», «ймовірна загроза протягом 10–25 хв» | Прогноз ударів із таблиці сусідства восьми областей і години доби | `nav/CassandraPredictor.kt:95`, `:107` | **відняти** (див. §7) |

## 2. Хром мапи (`map/MapChrome.kt`)

| Елемент | Обіцяє | Робить | Доказ | Вердикт |
|---|---|---|---|---|
| Пігулка тривоги (верх-ліво) | Стан неба в моїй області | Вирок `sky.verdict()`, відкриває «Небо»; мовчить на тихому небі без позиції | `MapChrome.kt:224-231`; `SkyVerdictTest`, `AlertIntelTest` | лишити |
| «Землі тут не збережено» / покриття mbtiles | Пристрій не має офлайн-основи тут | Прапорці з `ui.groundNotSavedHere`, `offlineBasemapOutOfBounds` | `MapChrome.kt:234-237`; `MissingPackTest`, `OfflineBasemapStyleTest` | лишити |
| Пігулки «мене бачать» | Я видимий комусь, до коли | `PeerSharePill` з `shareBanners`, кнопка «стоп» | `MapChrome.kt:241-244`; `PeerLayerFirewallTest` | лишити (нове, не чіпати) |
| «запасний план» (TacticalFallbackBanner) | Евакуація йде деградованим шляхом | Показ при `degraded`/`InPlaceDefense` | `MapChrome.kt:248-252`, `MapChromeParts.kt:85-100` | лишити; слово тьмяне — виправити у формі |
| Чип довіри до позиції | GPS: довіряю / глушіння / підміна | `TrustChip` з `LocationTrust` | `MapChrome.kt:266-287`; `TrustChipVolumeTest`, `TrustWordHysteresisTest`, `TrustRingPolarityTest` | лишити; «GPS ✓ підтверджено фізикою» (`strings.xml:1180`) — виправити слово |
| Банер маневру + «центрувати» | Наступний маневр | `ManeuverBanner`, `followMode` | `MapChrome.kt:290-315`; `GuidanceBannerFaceTest` | лишити |
| Перемикач поверхів | Поверхи будівлі | `IndoorLevelSwitcher` лише коли є план | `MapChrome.kt:318-330`; `IndoorPlanClearsTest` | лишити; коментар з емодзі `🏢` — прибрати |
| Пігулка «укриття» | Маршрут до найближчого укриття | `routeToNearestShelter`; ховається лише на доказі «поза Україною» | `MapChrome.kt:398-413` | лишити |
| Орб кріплення | Режим «в кріпленні» | Насправді відкриває **кабіну водія** (`carCockpitOpen`), а режим кріплення — кнопка всередині кабіни | `MapScreen.kt:846`, `MapScreen.kt:1270` | виправити разом із кабіною (§3.3) |
| Слот «шари» | Панель інструментів | Відкриває `MapToolsPanel`, бейдж при живому контексті | `MapChrome.kt:623-632`; `MapLayerToggleReachTest` | лишити |
| Слот «основа» | Вибір підкладки | `BasemapRail` | `MapChrome.kt:637-645`; `TileProviderTest` | лишити |
| Слот «офлайн» | Збережені території | Лише коли виклик уміє зберігати | `MapChrome.kt:646-659`; `OfflineRegionHandlerHonestyTest` | лишити |
| Компас | На північ | `BearingReset`, тьмяний на півночі | `MapChrome.kt:661-678` | лишити |
| Орб «де я» | Стежити за мною | Лише `followMode`; три стани значка | `MapChrome.kt:695-719`; `StationaryLockTest`, `CameraPathContinuityTest` | лишити |
| Платівка приладів | Масштаб, © OSM, світло, схилення, годинник | `MapInstrumentPlate`, атрибуція безумовна | `MapChrome.kt:746-755`; `ScaleBarHonestyTest`, `MapAttributionAlwaysVisibleTest`, `LightReadoutTest`, `DeclinationReadoutTest` | лишити |
| Пігулки режимів (початок маршруту, якір, малювання) | Мапа чекає дотику | Стан у `ui.*Pick`, «готово» відмовляє словом | `MapChrome.kt:780-836`; `DrawReachTest`, `AnchorPickReachTest` | лишити |
| Пігулка ходи | Іду / авто / потяг | Лише при впевненості ≥0,55, «ймовірно» <0,7 | `MapChrome.kt:845-869`; `SceneAnnounceTest` | лишити |
| Картка транспортної одиниці | Обрана машина | `TransitVehicleCard` з живого фіда | `MapChrome.kt:871-879` | лишити |
| Смуга маршруту / ETA / прибуття | Маршрут, старт, зупинка | `MapCommandSheet`, `EtaStrip`, `ArrivedBanner` | `MapChrome.kt:885-973`; `RouteBarFormTest`, `RouteFailureBandTest` | лишити; емодзі — §4 |

## 3. Панелі й аркуші

### 3.1 Панель інструментів (`map/MapToolsCapsule.kt`)

| Елемент | Обіцяє | Робить | Доказ | Вердикт |
|---|---|---|---|---|
| «Хто мене бачить» | Кількість тих, кому видно | Лічильник рядків із точністю, відкриває аркуш | `MapToolsCapsule.kt:176-179` | лишити |
| Тумблер «тривоги» | Шар областей | Вмикає й каже словом, чому порожньо | `MapChrome.kt:478-493`; `MapLayerToggleReachTest` | лишити |
| Тумблер «опади» | Радар RainViewer | Кадри з мережі; мовчання за 6 с → слово | `MapChrome.kt:180-187` | лишити |
| «транспорт» | Транспортний хаб | Відкриває хаб (не тумблер — роль Button) | `MapToolsCapsule.kt:209-221` | лишити |
| Камери / опори / водопропуски / броди | Шари Overpass | Справжній тумблер (сховати/показати/запросити) | `MapChrome.kt:527-578` | лишити |
| «Поруч» / «по дорозі» | Пошук категорії | `showNearby` / `showNearbyAlongRoute` | `MapToolsCapsule.kt:249-282`; `NearbyCatsTest` | лишити |
| Картка евакуації | Стан рушія евакуації | Справжні стани `EvacuationState`, але заголовки з емодзі `🔍 🛡 ✅ ⚠️` | `MapToolsCapsule.kt:583-588` | виправити: зняти емодзі |
| «ви на місці — прикриття знайдено» | Прикриття | Лише дійшли до точки з довідника | `strings.xml:715` | виправити слово |
| «Протокол безпеки на місці» | Протокол | Порада «правило двох стін» | `strings.xml:719-721` | лишити суть; «протокол» — форма |

### 3.2 Аркуш точки (`map/SheetHost.kt`)

| Елемент | Обіцяє | Робить | Доказ | Вердикт |
|---|---|---|---|---|
| Дії точки (маршрут, звідси, зберегти, поділитись…) | — | Справжні виклики VM | `SheetHost.kt:139-320`; `RouteHandoffTruthTest` | лишити |
| Позначки на 2 год: патруль, ДТП | Самозвіт, зникне за 2 год | `reportHazard` → `pushHazardMark`, TTL 120 хв | `SheetHost.kt:442-476`; `EventMarksTest`, `HazardMarkerGateTest` | лишити (патруль — відкрите питання) |
| Позначка «блокпост» | — | див. §1 | `SheetHost.kt:464` | **відняти** |

### 3.3 Кабіна водія (`map/cockpit/CarCockpitSheet.kt`, 890 рядків)

| Елемент | Обіцяє | Робить | Доказ | Вердикт |
|---|---|---|---|---|
| Заголовок «ЦЕНТР ВОДІЯ · Бортовий помічник авто» | Бортовий помічник | Аркуш із чотирьох вкладок | `:159-160` | виправити слово |
| Вкладка «Паркування»: «Смарт-Паркування», «Зафіксовано», таймер оплати | Місце авто запам'ятовано | `ui.parkingRecord` — `mutableStateOf` у `remember { MapUiState() }`: зникає, щойно мапа виходить із композиції (перехід у чат) або процес помирає. Нагадування про оплату нема — лише чип усередині аркуша | `MapSceneState.kt:245`, `MapScreen.kt:178`, `:1240-1251` | **відняти** |
| Вкладка «Борткомп'ютер»: «ЧАС У РУСІ», середня, максимальна | Статистика поїздки | Кожен фікс додає `dtSeconds = 1`, хоч би які інтервали між фіксами; лічильник живе в тому ж `remember` і обнуляється поза мапою | `MapScreen.kt:1213-1231`; `cockpit/TripComputerTest` перевіряє лише арифметику | **відняти** (разом із тестом, що перевіряв театр) |
| «Запустити великий HUD на лобове скло» | HUD | Вмикає режим кріплення (справжній) | `:631-658` | виправити: латина «HUD», неон `0xFF00E5FF` |
| Вкладка «Події»: «запис у захищену чорну скриньку» | Чорна скринька | Звичайна позначка на 2 год у шар позначок | `:699`; `MapWorldLayers.kt:319-336` | виправити слово; «🛑 Блокпост» — **відняти** (§1); емодзі 💥 🚧 📷 — зняти; «TruCam» — латина |
| Вкладка «Сервіси»: «АЗС поруч — WOG, OKKO, SOCAR, KLO», «Зарядки EV» | Мережі АЗС | Текстовий пошук «АЗС» — жодних брендів не фільтрує | `:800-815` | виправити: зняти список брендів і латину «EV» |

### 3.4 Режим кріплення (`map/mount/MountGlanceScreen.kt`)

| Елемент | Обіцяє | Робить | Доказ | Вердикт |
|---|---|---|---|---|
| Кластер фактів (швидкість, маневр) | — | `MountModeModel` | `MountModeModelTest` | лишити |
| «🪞 ДЗЕРКАЛО HUD» | Дзеркало для відбиття в склі | Справді `scaleX = -1` | `:159-181` | виправити: емодзі, латина |

### 3.5 Небо / бриф загрози (`AlarmSheet.kt`, `ThreatBriefSheet.kt`, `AlertIntel.kt`)

| Елемент | Обіцяє | Робить | Доказ | Вердикт |
|---|---|---|---|---|
| Табло областей, журнал, джерела | Стан тривог | Стрічки країни; джерела названо | `AlarmSheet.kt`; `AirAlertWatchTest`, `SkyVerdictTest` | лишити |
| «Специфікація загрози уточнюється Фантомом» | Фантом щось уточнює | Нічого не уточнює: тип `UNKNOWN` просто показується цим рядком | `ThreatBriefSheet.kt:111-115` | **відняти** обіцянку → «стрічка не назвала тип загрози» |
| Знак загрози | — | Емодзі `⚠️ 🛸 💣 💥 🚀 ⚔️ 🛩️ ☄️ ☣️ ☢️` через `PhantomGlyph(glyph: String)`, хоча мальовані знаки родини вже є (`MapGlyphMappings.kt:15-19`) | `AlertIntel.kt:30-39`, `AlarmSheet.kt:328,416,473`, `ThreatBriefSheet.kt:134` | виправити: мальований знак |
| «Маршрут до укриття 🛡️» | — | `onRouteToShelter` | `ThreatBriefSheet.kt:192`, `AlarmSheet.kt:369` | лишити дію, емодзі зняти |

### 3.6 Транспортний хаб (`map/transit/*`)

| Елемент | Обіцяє | Робить | Доказ | Вердикт |
|---|---|---|---|---|
| Вкладки за покриттям | Лише те, на що є відповідь | `hubTabs(plane, …)` | `TransitGlassLaw.kt:74-103`; `TransitGlassLawTest` | лишити |
| Вкладки з емодзі `🚏 🚌 🚇 ⚡` | — | Декор | `TransitHubSheet.kt:62-65`, `:183` | виправити: зняти |
| Картка лінії: «Інтервал ~N хв», «🌅 Пік / ☀️ День / 🌙 Вечір» | Інтервали ЦІЄЇ лінії | Одна константа на вид транспорту для всіх ліній (`MODE_HEADWAYS`, «Modelled headways… Deliberately conservative»), подана як факт лінії, без слова «модель» | `TransitHubTabs.kt:213`, `:256-268`; `scripts/fetch_surface_transit.py:46,376` | **відняти** (вигадані дані) |
| Живий флот | Скільки машин на склі | `glassFleet(fleet)` з фіда, «застаріло» при деградації | `TransitHubTabs.kt:400-457`; `TransitPillLabelTest` | лишити; колір — форма |
| Фон хаба `0xFF090D16`, `Color.White` у тексті | — | Примусово темний і в світлій темі | `TransitHubSheet.kt:110`; `TransitHubTabs.kt` (16 сирих кольорів) | виправити у формі |

### 3.7 Дос'є місця (`map/dossier/*`)

| Елемент | Обіцяє | Робить | Доказ | Вердикт |
|---|---|---|---|---|
| Завантаження, кеш, джерела, «локальне перше» | — | `DossierLoader`, `DossierCache` | `DossierLoaderTest`, `DossierCacheTest`, `DossierNamesWhatTheMapDrawsTest` | лишити |
| «ПАЛЬНЕ В НАЯВНОСТІ» | Пальне є зараз | Теги OSM `fuel:*` — що продають узагалі, не наявність | `DossierObjectCards.kt:74` | **відняти** обіцянку → «види пального (OSM)» |
| «☕ Кафе / WOG Cafe / Hot-Dog» | Кафе певної мережі | Будь-який `shop=*` | `DossierObjectCards.kt:109` | **відняти** |
| «📡 Starlink / Wi-Fi» в укритті | Starlink | `internet_access=wlan|yes` | `DossierObjectCards.kt:194` | **відняти** Starlink → «Wi-Fi» |
| «💊 Програма «Доступні ліки»» | Урядова програма | `dispensing=yes` = аптека відпускає за рецептом | `DossierObjectCards.kt:260` | **відняти** → «відпуск за рецептом» |
| «🛗 Панорамні ліфти» | Панорамні | `elevator=yes` | `DossierObjectCards.kt:300` | **відняти** → «ліфт» |
| «👶 Дитячий куточок» | Куточок | `highchair=yes` = дитячий стілець | `DossierObjectCards.kt:222` | **відняти** → чесні дві ознаки |
| «☂️ Критий захищений» | Захищений | `covered=yes` або підземний | `DossierObjectCards.kt:159` | **відняти** «захищений» |
| Заголовки карток «⛽ АВТОЗАПРАВНИЙ КОМПЛЕКС (АЗС)», «🛍️ ТОРГОВО-РОЗВАЖАЛЬНИЙ ЦЕНТР» + ~45 емодзі-чипів | — | Декор | `DossierObjectCards.kt` (47 емодзі), `PlaceDossier.kt` (17), `DossierTransitCard.kt` (13) | виправити: зняти емодзі й латину («EV Charge», «Pet-friendly», «Drive-Through», «Park & Ride») |
| «PHANTOM осмислює...» | Думає | Стан `THINKING` — запит до моделі в дорозі | `strings_dossier.xml:33`, `PlaceDossierSheet.kt:365` | виправити слово («чекаю відповіді») |

### 3.8 Каталог «відкрити поруч» (`MapDiscoveryCatalog.kt`)

| Елемент | Обіцяє | Робить | Доказ | Вердикт |
|---|---|---|---|---|
| «Пункт незламності: опалення, інтернет Starlink та гаряча вода» | Що є в кожному пункті | Селектор OSM; що в конкретному пункті — невідомо | `MapDiscoveryCatalog.kt:181`, `strings_discovery.xml:17` | **відняти** Starlink/гарячу воду → що це за місце |
| «Заряджання електромобілів та станцій EcoFlow» | EcoFlow | `amenity=charging_station` | `strings_discovery.xml:3` | **відняти** бренд |

### 3.9 Пошук, офлайн, внутрішні плани, люди

| Елемент | Вердикт | Доказ |
|---|---|---|
| Пошук (`MapSearch*`, `MapPoiSearch`, Nominatim/Photon) | лишити | `MapSearchFirstScreenTest`, `PlaceSearchDeviceFirstTest`, `NominatimAndPhotonGeocodeTest`, `SearchGapTest` |
| Офлайн-території, місця, бюджет | лишити | `OfflinePlacesTest`, `OfflineBudgetTest`, `OfflineRegionHandlerHonestyTest` |
| Внутрішні плани, входи | лишити; `IndoorFloorOverlay` — 19 сирих кольорів → форма | `IndoorRoomLabelTest`, `EntranceSignTest` |
| Люди на мапі (`peers/*`) | лишити, не чіпати | `PeerFiguresTest`, `PeerRouteLayerTest`, `PeerLayerFirewallTest` |

## 4. Шари мапи (MapLibre)

| Шар | Вердикт | Доказ |
|---|---|---|
| Основа «Обсидіан», підписи, дороги, поселення | лишити | `ObsidianStyleContractTest`, `MapRoadHierarchyTest`, `MapLabelVoiceTest`, `MapSettlementBlendTest` |
| Заливки землі (`MapGroundCartography`) | лишити; кольори — під сторож контрасту на кроці форми | `MapGroundCartographyTest` |
| Штрих і тон військової землі | **віднято** (§1) | `MapSafetyBoundaryTest` |
| Області тривог, райони, мітки типу | лишити | `AirAlertWatchTest` |
| Позначки подій, ворота, КПП | лишити | `EventMarksTest`, `HazardMarkerGateTest` |
| Маршрут, смуги, стрілки маневрів, пін | лишити | `RouteBandMathTest`, `LaneBandsTest`, `ManeuverPinsTest`, `MapPinFaceTest` |
| Ореол довіри | лишити | `TrustHaloDashTest`, `TrustHaloScreenCapTest` |
| «Встигаю дійти» (ізохрона), «поле небезпеки» | лишити: рахуються з графа доріг і власних позначок | `GenerativeOverlaysTest` |
| Не завантажено (штрих) | лишити | `NotDownloadedHatchLayerTest` |
| Кришталеве місто (будівлі Overpass) | лишити | `CityBuildingParserTest`, `CrystalGeoTest` |

## 5. Навігація (UI, `map/nav/*`)

| Елемент | Вердикт | Доказ |
|---|---|---|
| Банер маневру, гліф маневру | лишити | `GuidanceBannerFaceTest`, `ManeuverGlyphTest` |
| Смуга ETA, «зупинити» утриманням, прибуття словом | лишити | `GuidanceChrome.kt:120-123`, `:538` |
| Список маневрів, «Паркуйся і йди» | лишити дію; `🚗 ➔ 🅿️ ➔ 🚶` — зняти емодзі | `ManeuverListSheet.kt:161-165`, `MapCommandSheet.kt:451-455` |
| Застереження маршруту `🚧 🌊 ⚠️`, ноги `🚗 🚶`, види транспорту `🚊 🚎 🚌 🚆 🚠 ⛴` | лишити зміст, зняти емодзі | `MapCommandSheet.kt:1237-1255`, `:1553`, `:1659-1665` |
| Камера-режисер, інерційне ведення, облік доріг | лишити | `CameraDirectorTest`, `CoastPilotTest`, `RoadTruthTest` |
| Політ над маршрутом | лишити | `RouteFlyoverPortTest` |

## 6. `core-nav` — рушії, що справді ведуть

| Рушій | Вердикт | Доказ |
|---|---|---|
| `GuidanceController`, `GuidanceSession`, `GuidanceHub`, `NavigationEngine`, `RoutePlanner`, `RouteRegister`, `RouteTrim`, `RouteCoastingEngine`, `OfflineManeuverSynthesizer`, `DeadlineWatch`, `BreadcrumbTracker`, `TrackExporter`, `ThreatWatch`, `ShelterVectorEngine`, `ExfilEngine`, `SentinelFenceEngine` | лишити | `GuidanceSessionTest`, `RerouteLoopTest`, `RoutePlanner*Test`, `DeadlineWatchTest`, `ThreatWatchTest`, `ShelterVectorEngineTest`, `ExfilEngineTest`, `SentinelFenceEngineTest` |
| `OverwatchNarrator` | лишити (голосові підказки під час ведення з реальних кадрів) | `OverwatchNarratorTest`, викликається з `MapLiveSession.kt:193` |
| `SentinelFenceEngine` — докстрінг «mesh hive alerts» | виправити документ (мережевих сповіщень нема) | `nav/SentinelFenceEngine.kt:10` |

## 7. `core-nav` + ШІ-інструменти: театр

Ці рушії не мають входу з мапи; їх кличе лише модель як інструменти
(`ToolRegistryFactory.kt:468-610`). Вони відповідають упевненим текстом, за
яким нічого не стоїть.

| Рушій / інструмент | Обіцяє | Робить | Доказ | Вердикт |
|---|---|---|---|---|
| `MapPatrolEngine` + `patrol.start` / `patrol.status` | «Автономна місія патрулювання сектора: аудит укриттів, моніторинг загроз, розвідка коридору» | Кладе запис у мапу в пам'яті зі статусом ACTIVE. `completeMission` не викликає ніхто → місія «триває» вічно, знахідок 0 | `nav/MapPatrolEngine.kt:6-12`, `:64`; 0 викликів `completeMission` поза тестом; `tool/MapPatrolTools.kt:49` | **відняти** |
| `SceneDirector` + `scene.direct` | «Режисер камери й шарів: нахил 45°, шари загроз…» | Повертає текст і числа; камера й шари не змінюються | `nav/SceneDirector.kt:28-66`; `ToolRegistryFactory.kt:597-615` | **відняти** |
| `CassandraPredictor` + `risk.assess` | «Прогноз загрози точки: ймовірна загроза протягом 10–25 хв» | Бал із таблиці сусідства 8 областей, години доби, кількості укриттів; пише «у радіусі 500 м», а шукає в 3000 м | `nav/CassandraPredictor.kt:79,95,107`; `ToolRegistryFactory.kt:512` | **відняти** (і межа безпеки: прогноз ударів) |
| `MapTimelineEngine` + `timeline.query` | «Стан тривог у той момент минулого» | Підставляє ПОТОЧНІ тривоги як минулі; порожньо → «Небо спокійне» навіть при мовчазній стрічці | `ToolRegistryFactory.kt:576-584`; `nav/MapTimelineEngine.kt:50-54` | **відняти** |
| `AtmosphericRadarEngine` + `weather.radar` | «Радар N dBZ» | dBZ вигадано з мм/год моделі Open-Meteo; радарного виміру нема | `nav/AtmosphericRadarEngine.kt:69-72,91` | виправити (наступна хвиля: прибрати dBZ зі слова) |
| `PlaceContextEngine` + `place.context` | «Домашня варта активна» | Булеве поле; жодної варти не запускає | `ToolRegistryFactory.kt:731`; `nav/PlaceContextEngine.kt:64-118` | виправити (наступна хвиля) |
| `SpatialSearchEngine` + `spatial.search` | «Єдиний пошук: категорії, Vault, координати» | Vault-гілка ніколи не отримує даних (`savedTargets` не передається); 40 емодзі-категорій з «тактичними причинами» | `ToolRegistryFactory.kt:766`; `nav/OsmSpatialUniverse.kt` | виправити (наступна хвиля) |

## 8. `core-geo`

| Що | Вердикт | Доказ |
|---|---|---|
| Небо: сонце, світло, схилення, годинник | лишити | `SolarAlmanacTest`, `MagneticDeclinationTest`, `ClockTruthTest`, `UsableLightTest` |
| PMTiles, офлайн-основа, пакети, ліцензії | лишити | `PmTilesReaderTest`, `OfflineBasemapViewTest`, `LicenseFirewallTest` |
| Метро, внутрішні графи | лишити | `MetroStationLayerTest`, `IndoorGraphTest` |
| `nav/TurnByTurnGuidanceEngine` + `nav/OfflineGraphRouter` | Другий, паралельний рушій ведення. Жодного виклику поза власним тестом — справжнє ведення живе в `core-nav` | `NavEngineTest` — єдиний користувач | **відняти** (мертвий двійник) |
| `cameras/SpeedCameraLayer` | Жодного виклику поза тестом | `SpeedCameraLayerTest` — єдиний користувач | **відняти** |
| `sky/LunarAlmanac` | Не підключено, але точний | `LunarAlmanacTest` | лишити (питання: де місяць має жити) |
| `indoor/BuildingIndoorEngine` — категорії з емодзі `🍔 🛍️ 📱 🛒 💊 🎬 🏦 🏢` | Емодзі малюється префіксом назви кімнати на внутрішньому плані | `BuildingIndoorEngine.kt:29-37`, `map/indoor/IndoorFloorOverlay.kt:265` | виправити: зняти емодзі |

## 9. Форма (для кроку 3)

- Сирі кольори замість токенів: 130 `Color(0x…)` у 16 файлах `ui/map`
  (найбільше: `DossierTransitCard` 25, `IndoorFloorOverlay` 19, `DossierObjectCards` 18,
  `TransitHubTabs` 16, `CarCockpitSheet` 11, `TransitHubSheet` 11).
- Неоновий блакитний `0xFF00E5FF` (кабіна), бірюза `0xFF14B8A6`/`0xFF0D9488`
  (хаб) — «ШІшна» палітра поза мовою форми месенджера.
- Транспортний хаб примусово темний (`0xFF090D16`, `Color.White`) у світлій темі.
- Картки дос'є: кожна ознака — власний кольоровий бейдж (А-95 зелений, дизель
  жовтий…), тобто колір без значення.

## 10. Підсумок → план віднімання (`cloud/map-subtract`)

1. Безпека: чип і кнопка «блокпост»; «блокпост» у закритому словнику живого шару (клієнт, сервер — міграція `0002`, прийом чужих міток); дос'є `military=*` і `access=military`; підсвітка військової землі.
2. Кабіна: вкладки «Паркування» і «Борткомп'ютер» (+ `ParkingRecord`, `TripStats`,
   `TripComputerTest`); слова «чорна скринька», «ЦЕНТР ВОДІЯ», бренди АЗС, «HUD», «EV».
3. Транспортний хаб: інтервали лінії з моделі; емодзі вкладок і видів.
4. Дос'є: «в наявності», WOG, Starlink, «Доступні ліки», «панорамні», «куточок»,
   «захищений»; емодзі й латина в чипах; «PHANTOM осмислює».
5. Каталог: Starlink/гаряча вода/EcoFlow.
6. Бриф загрози: «уточнюється Фантомом»; емодзі загроз → мальовані знаки.
7. Емодзі в евакуації, маршруті, списку маневрів, кріпленні.
8. `core-nav` + інструменти: `MapPatrolEngine`, `SceneDirector`, `CassandraPredictor`,
   `MapTimelineEngine` разом із `patrol.*`, `scene.direct`, `risk.assess`,
   `timeline.query` і їхніми тестами.
9. `core-geo`: `TurnByTurnGuidanceEngine`, `OfflineGraphRouter`, `SpeedCameraLayer` + тести.

## Відкрите власнику

- `barrier=checkpoint` з OSM (КПП на маршруті, голос «попереду блокпост»): публічна
  дорожня інфраструктура чи позиція військ? Лишено до рішення.
- Позначка «патруль» синхронізується між операторами — лишено (не військове),
  але це краудсорс позицій поліції.
- `MapZoneTool` (core-symbiote) пропонує причину зони «обстріл, міни, блокпост» —
  поза модулями мапи, не чіпано.
