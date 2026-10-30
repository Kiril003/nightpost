package local.phantom.obsidian

/**
 * КОНТРАКТ ІДЕНТИФІКАТОРІВ ШАРІВ — читати перед будь-якою зміною порядку.
 *
 * Обсидіан не самотній у стилі: поверх нього застосунок ставить свої
 * контрибуції, і кожна з них шукає, куди себе вставити. Ці якорі — не деталь
 * реалізації компілятора, а публічна поверхня, зламавши яку, отримаєш маршрут
 * під землею або підпис під будинком, і жоден JVM-тест цього не побачить.
 *
 * Що саме тримається за що (перевірено грепом по `feature-stream/ui/map`):
 *
 * 1. `style.layers.first()` — [ID_GROUND].
 *    `installNotDownloadedHatch`, `installHillshade` і `MapGroundBackfill.addAsGround`
 *    вставляють себе ВІДРАЗУ НАД першим шаром. Тому перший шар мусить бути
 *    `background` і мусить бути рівно один такий.
 *
 * 2. `style.layers.first { it is SymbolLayer }` — [ID_FIRST_SYMBOL].
 *    `installBuildingExtrusions` і `installGroundFacts.installWaterTruth`
 *    вставляють себе ПІД перший символьний шар, щоб підписи лишились згори.
 *    Отже ЖОДНОГО символьного шару не має бути перед заливками й лініями:
 *    всі підписи йдуть суцільним хвостом у кінці списку.
 *
 * 3. `style.sources.first { it is VectorSource }` — [SOURCE_ID].
 *    Ті самі два виклики беруть перше векторне джерело за id. Джерело в стилі
 *    рівно одне; додати друге — зробити цей вибір випадковим.
 *
 * 4. [ID_GROUND] — ще й підпис стилю. `Style.speaksObsidian()` на боці Android
 *    питає саме про нього, і за цією відповіддю чотири перефарбувальники
 *    (`applyGroundCartography`, `applyLabelVoice`, `installGroundBackfill`,
 *    `installGroundFacts`) стають порожніми: стиль уже говорить нашою мовою.
 *    Тому id тла — НЕ `background`: воно мусить бути впізнаваним.
 *
 * 5. Тап по землі (`GROUND_FEATURE_TAP`) відкидає шари, чий id містить
 *    `admin`, `boundary` або `alert`. Адмінкордони названі `…-boundary-…`
 *    саме тому: кордон області — не місце, і не має ловити палець.
 *
 * 6. `setBuildingExtrusionsVisible` гасить лише `fill-extrusion` — платний
 *    об'єм. [ID_BUILDING] та його сусіди НЕ підпадають: плаский слід будівлі —
 *    це ґрунт стилю, він живе на будь-якій ліцензії й у будь-якому режимі.
 *    Колись прохід ішов по `source-layer`, і місто зникало разом з об'ємом.
 */

/** Тло. Перший шар стилю і підпис «це Обсидіан». */
const val ID_GROUND = "obsidian-ground"

/** Перший символьний шар. Усе, що вставляється «під підписи», сідає під нього. */
const val ID_FIRST_SYMBOL = "obsidian-label-housenumber"
const val ID_LABEL_POI = "obsidian-label-poi"
const val ID_LABEL_TRANSIT = "obsidian-label-transit"

/** Плаский слід будівлі. */
const val ID_BUILDING = "obsidian-building"

/** Спільний префікс усіх шарів стилю. */
const val ID_PREFIX = "obsidian-"
