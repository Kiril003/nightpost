package local.phantom.companion.feature.stream.ui.map.atlas

/**
 * ATLAS D3 — the z-ladder.
 *
 * Until now «що поверх чого» was the order of lines in two files: the install
 * chain in `MapCanvas` (ground) and [local.phantom.companion.feature.stream.ui.map.mapBackStep]
 * (chrome). Neither was a decision anybody could review; the second one is
 * nonetheless the only place the order was ever written down deliberately, so
 * both are the seed here.
 *
 * **The ordinal IS the contract.** Lower ordinal draws lower and installs
 * first; the tap ladder is separate ([TapRank]) precisely because «what the
 * finger means» is not «what covers what». Reordering this enum reorders the
 * map — that is the point: the order becomes a diff instead of an accident of
 * where somebody happened to call an installer.
 *
 * Three tiers do NOT sit where they sit today, and the difference is
 * deliberate: [RADAR], [MANEUVER_PINS] and [OFFLINE_PACKS] are installed
 * lazily from the *update* pass, so their current z-position is whatever the
 * update pass happened to reach first. They are placed at their logical home
 * here (radar under the alert wash, matching `applyRadar`'s own
 * `addLayerBelow`; maneuvers directly over the route they annotate). Every
 * other tier keeps the position it has on the device today.
 */
internal enum class MapTier(val surface: MapSurface, val density: DensityClass) {
    // ── Ground: inside the engine's frame, composited with the map ────────
    GROUND_CARTOGRAPHY(MapSurface.GROUND, DensityClass.BASEMAP),
    LABEL_VOICE(MapSurface.GROUND, DensityClass.BASEMAP),
    GROUND_BACKFILL(MapSurface.GROUND, DensityClass.ANNOTATION),
    GROUND_FACTS(MapSurface.GROUND, DensityClass.ANNOTATION),
    HILLSHADE(MapSurface.GROUND, DensityClass.TERRAIN),
    RADAR(MapSurface.GROUND, DensityClass.WASH),
    ALERT_WASH(MapSurface.GROUND, DensityClass.WASH),
    COVERAGE_HATCH(MapSurface.GROUND, DensityClass.WASH),
    EW_TOPOLOGY(MapSurface.GROUND, DensityClass.OPERATOR),
    OPERATOR_PIN(MapSurface.GROUND, DensityClass.OPERATOR),
    SEARCH_PIN(MapSurface.GROUND, DensityClass.PERSONAL),
    ROUTE_LINE(MapSurface.GROUND, DensityClass.ROUTE),
    ROUTE_REGISTER(MapSurface.GROUND, DensityClass.ROUTE),
    MANEUVER_PINS(MapSurface.GROUND, DensityClass.ROUTE),
    // СИМБІОЗ — іменовані контакти. Стоять НАД знаками подій і під маневром:
    // людина, яку ти знаєш, важливіша за дворові ворота, але не важливіша за
    // поворот, у який ти зараз заїжджаєш. Окремий ярус, бо люди — не восьмий
    // клас загрози, і анонімна краудова площина з ними не змішується.
    PEER_FIGURES(MapSurface.GROUND, DensityClass.PEER),
    // Розкладка в MapLibre йде за порядком шарів: хто встановлений раніше,
    // той стає першим і виграє тісноту. Тому знаки подій — одразу за
    // маневрами й ДО транзиту з місцями: маневр лишається головнішим, а
    // блокпост бʼє вивіску кавʼярні.
    EVENT_MARKS(MapSurface.GROUND, DensityClass.EVENT),
    TRANSIT(MapSurface.GROUND, DensityClass.TRANSIT),
    PHANTOM_LAYERS(MapSurface.GROUND, DensityClass.PERSONAL),
    // Below the pins by ATLAS-0b ruling, not by history: extrusions used to
    // install last and swallowed the pins standing on the roofs they raised.
    // A building is ground the operator reads THROUGH, never a lid over a
    // place somebody marked.
    BUILDING_EXTRUSION(MapSurface.GROUND, DensityClass.STRUCTURE),
    POI(MapSurface.GROUND, DensityClass.PLACE),
    SAVED_PLACES(MapSurface.GROUND, DensityClass.PERSONAL),
    INDOOR_PLAN(MapSurface.GROUND, DensityClass.STRUCTURE),
    OFFLINE_PACKS(MapSurface.GROUND, DensityClass.WASH),

    // ── Chrome: screen-anchored, no per-frame sync with the ground ────────
    // Seeded from mapBackStep's dismissal order, reversed: what «назад» takes
    // off first is what sits on top.
    CHROME_TOOLS(MapSurface.CHROME, DensityClass.CHROME),
    CHROME_TAP_PROVENANCE(MapSurface.CHROME, DensityClass.CHROME),
    CHROME_DROPPED_POINT(MapSurface.CHROME, DensityClass.CHROME),
    CHROME_THREAT_OBLAST(MapSurface.CHROME, DensityClass.CHROME),
    CHROME_LAYER_MARKER(MapSurface.CHROME, DensityClass.CHROME),
    CHROME_SAVED_PLACE(MapSurface.CHROME, DensityClass.CHROME),
    CHROME_DOSSIER(MapSurface.CHROME, DensityClass.CHROME),
    CHROME_OFFLINE_PANEL(MapSurface.CHROME, DensityClass.CHROME),
    CHROME_STYLE_PICKER(MapSurface.CHROME, DensityClass.CHROME),
    CHROME_ROUTE_COMPARE(MapSurface.CHROME, DensityClass.CHROME),
    CHROME_TRANSIT_HUB(MapSurface.CHROME, DensityClass.CHROME),
    CHROME_ALARM_SHEET(MapSurface.CHROME, DensityClass.CHROME),
    CHROME_PROVENANCE_SHEET(MapSurface.CHROME, DensityClass.CHROME),
    CHROME_ANSWER(MapSurface.CHROME, DensityClass.CHROME),
    CHROME_SEARCH(MapSurface.CHROME, DensityClass.CHROME),
    CHROME_MOUNT(MapSurface.CHROME, DensityClass.CHROME),
    ;

    companion object {
        /** Ground before chrome, always — D1's law expressed as an invariant. */
        val GROUND: List<MapTier> = entries.filter { it.surface == MapSurface.GROUND }
        val CHROME: List<MapTier> = entries.filter { it.surface == MapSurface.CHROME }
    }
}

/**
 * Where a contribution draws. GROUND means inside the engine (a style layer,
 * composited with the map in one frame); CHROME means screen-anchored Compose,
 * which by D1's law may never be geo-anchored.
 */
internal enum class MapSurface { GROUND, CHROME }

/**
 * How a contribution competes for the viewport's budget. The Lens hands out a
 * symbol allowance per class; classes that describe the ground rather than
 * events on it ([BASEMAP], [WASH], [TERRAIN]) are area-drawn and never
 * budgeted, and the operator's own dot is never negotiable.
 */
internal enum class DensityClass {
    BASEMAP,
    TERRAIN,
    STRUCTURE,
    WASH,
    OPERATOR,
    ROUTE,
    /**
     * Подія на землі: блокпост, ДТП, укриття. Бюджету не має свідомо — знаки
     * уже проріджені ДОРЕЧНІСТЮ (`HazardGate`: ворота лише зблизька або в
     * коридорі, одні на скупчення, стеля на екран). Другий ліміт поверх цього
     * знімав би з екрана не «зайве», а те, що воротар уже визнав вартим його.
     */
    EVENT,
    /**
     * Іменований контакт. Бюджету не має і не матиме: контракт уже тримає їх
     * малими (до 32 живих точок у групі), а ліміт поверх цього знімав би з
     * екрана не сміття, а людину — і саме ту, якої не вистачить під сиреною.
     */
    PEER,
    TRANSIT,
    PLACE,
    PERSONAL,
    ANNOTATION,
    CHROME,
}

/**
 * Tap priority, seeded from the `onMapClick` chain in `MapCanvas`: the order
 * of the early-returns there IS the priority, and it was five independent
 * queries that did not know about each other. Lower ordinal wins.
 *
 * [GROUND_FEATURE] is the catch-all — whatever the base style itself rendered
 * under the finger. It is last on purpose: a named lake must never outrank a
 * pin somebody placed.
 */
internal enum class TapRank {
    ROUTE_ALTERNATIVE,
    TRANSIT_STOP,
    LAYER_MARKER,
    // Фігурка людини — велике навмисне тіло, і палець на ній означає ЛЮДИНУ,
    // а не блокпост під її ногами й не вивіску за спиною. Нижче власної мітки
    // оператора: ту він поставив рукою і знає, що вона там.
    PEER_POINT,
    // Знаки подій прийшли з Compose-оверлея, де мали власний обробник і
    // виграва́ли палець у всіх — Compose лежав ПОВЕРХ движка. У реєстрі це
    // означає ранг над місцями: подія попереду важливіша за вивіску під нею.
    EVENT_MARK,
    SAVED_PLACE,
    POI,
    GROUND_FEATURE,
}

/** The screen slots chrome contributions compete for (ATLAS D5 output). */
internal enum class ChromeSlot {
    ALERT_PILL,
    TRUST_CHIP,
    MANEUVER_BANNER,
    ETA_STRIP,
    LOCATE_ORB,
    COMMAND_PILL,
    ZOOM_DECK,
    BASEMAP_DECK,
    LAYER_RAIL,
    TOOLS_CAPSULE,
    INDOOR_SWITCHER,
    BOTTOM_SHEET,
    ANSWER_BAR,
}
