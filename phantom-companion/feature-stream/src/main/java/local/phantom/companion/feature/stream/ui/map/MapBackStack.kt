package local.phantom.companion.feature.stream.ui.map

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/**
 * Що закриє «назад» на мапі.
 *
 * Мапа була ЄДИНОЮ великою поверхнею застосунку без [BackHandler] — він є у
 * стрічки, Людей, розмови з людиною, груп, скрині й Continuum. Через це «назад»
 * із будь-якого аркуша мапи не закривав аркуш, а виходив із застосунку: власник
 * назвав це «мене щойно з мапи вибило», і це відтворюється з першої спроби —
 * відкрити пошук, натиснути «назад», опинитись на робочому столі.
 *
 * Порядок нижче — це порядок «наскільки воно зараз поверх усього», а не порядок
 * оголошення полів. Перекриття на екрані і є стосом.
 */
internal enum class MapBackStep {
    /** Телефон у кріпленні: непрозорий екран поверх зупиненої мапи. */
    MOUNT,

    /**
     * Аркуш «як ставити дім». Вище за пошук свідомо: він відкривається З
     * пошуку і стоїть поверх нього, тож «назад» знімає спершу питання.
     */
    ANCHOR_PICK,

    /** Повноекранний пошук. */
    SEARCH,

    /** Відповідь PHANTOM на питання з мапи. */
    ANSWER,

    /** Аркуші, кожен зі своїм тлом поверх мапи. */
    ALARM_SHEET,
    TRANSIT_HUB,
    /** Аркуш поворотів. Мав аркуш, але не мав кроку: «назад» із нього скидав
     *  МАРШРУТ або виходив із застосунку — рівно та вада, проти якої писано цей файл. */
    MANEUVER_LIST,
    OFFLINE_PANEL,

    /** Вибране на землі — досьє, збережене місце, позначка шару, область. */
    DOSSIER,
    SAVED_PLACE,
    DRAW_NAMING,
    LINE_CARD,
    LAYER_MARKER,
    /** Картка людини, що поділилась місцем. */
    PEER_POINT,
    /** Картка чужого надісланого маршруту. */
    PEER_ROUTE,
    THREAT_OBLAST,
    DROPPED_POINT,

    /** Розгорнута капсула інструментів. */
    TOOLS,

    /** Шпилька, що лишилась на землі після пошуку. */
    SEARCH_PIN,

    /** Обраний «звідси маршрут» початок, що чекає на кінець. */
    ROUTE_ORIGIN,

    /**
     * Якір, що чекає на довгий дотик. Стан того ж роду, що [ROUTE_ORIGIN], і
     * тому саме тут, а не біля свого аркуша: поки чекає лише пілюля, будь-який
     * відкритий аркуш поверх мапи знімається раніше за неї.
     */
    ANCHOR_PLACING,

    /**
     * Малювання ламаної — той самий рід стану, що [ANCHOR_PLACING]: мапа чекає
     * дотиків, а все, що лежить поверх неї, знімається раніше. «Назад» кидає
     * саме режим, а не останню вершину: вершину прибирає своя кнопка, і
     * плутати ці два жести означало б втрачати роботу пів-натиском.
     */
    DRAW,

    /**
     * Прокладений, але ще не початий маршрут.
     *
     * Живе ведення сюди НЕ потрапляє свідомо: скасувати поїздку жестом «назад»
     * — це втратити маршрут через рух великого пальця. Поки [MapBackState.guiding]
     * істинне, цей крок не пропонується, і «назад» на порожній мапі робить те,
     * що робить система.
     */
    ROUTE,
}

/** Знімок мапи, достатній, щоб вирішити долю одного натискання «назад». */
internal data class MapBackState(
    val mount: Boolean,
    // Default, бо стан старший за це поле: тести будують його поіменно.
    val anchorPick: Boolean = false,
    val anchorPlacing: Boolean = false,
    val search: Boolean,
    val answer: Boolean,
    val alarmSheet: Boolean,
    val transitHub: Boolean,
    val maneuverList: Boolean = false,
    val offlinePanel: Boolean,
    val dossier: Boolean,
    val savedPlace: Boolean,
    val layerMarker: Boolean,
    val peerPoint: Boolean,
    val threatOblast: Boolean,
    val droppedPoint: Boolean,
    val tools: Boolean,
    val searchPin: Boolean,
    // Default, бо стан старший за це поле: тести будують його поіменно.
    val routeOrigin: Boolean = false,
    val drawNaming: Boolean = false,
    val lineCard: Boolean = false,
    val peerRoute: Boolean = false,
    val drawing: Boolean = false,
    val route: Boolean,
    val guiding: Boolean,
)

/**
 * Верхній шар, який «назад» зніме. `null` — знімати нема чого, і натискання
 * має піти системі: на корені мапи це нормальний вихід, і перехоплювати його
 * означало б замкнути людину в застосунку.
 */
internal fun mapBackStep(s: MapBackState): MapBackStep? = when {
    s.mount -> MapBackStep.MOUNT
    s.anchorPick -> MapBackStep.ANCHOR_PICK
    s.search -> MapBackStep.SEARCH
    s.answer -> MapBackStep.ANSWER
    s.alarmSheet -> MapBackStep.ALARM_SHEET
    s.transitHub -> MapBackStep.TRANSIT_HUB
    s.maneuverList -> MapBackStep.MANEUVER_LIST
    s.offlinePanel -> MapBackStep.OFFLINE_PANEL
    s.dossier -> MapBackStep.DOSSIER
    s.savedPlace -> MapBackStep.SAVED_PLACE
    s.drawNaming -> MapBackStep.DRAW_NAMING
    s.lineCard -> MapBackStep.LINE_CARD
    s.layerMarker -> MapBackStep.LAYER_MARKER
    s.peerPoint -> MapBackStep.PEER_POINT
    s.peerRoute -> MapBackStep.PEER_ROUTE
    s.threatOblast -> MapBackStep.THREAT_OBLAST
    s.droppedPoint -> MapBackStep.DROPPED_POINT
    s.tools -> MapBackStep.TOOLS
    s.searchPin -> MapBackStep.SEARCH_PIN
    s.routeOrigin -> MapBackStep.ROUTE_ORIGIN
    s.anchorPlacing -> MapBackStep.ANCHOR_PLACING
    s.drawing -> MapBackStep.DRAW
    s.route && !s.guiding -> MapBackStep.ROUTE
    else -> null
}

/** Читає стан мапи у знімок, який вміє оцінити [mapBackStep]. */
internal fun MapUiState.backState(
    guiding: Boolean,
    hasRoute: Boolean,
    answerLive: Boolean,
): MapBackState =
    MapBackState(
        mount = mountMode,
        anchorPick = anchorPick?.placing == false,
        anchorPlacing = anchorPick?.placing == true,
        search = searchOpen,
        answer = answerLive,
        alarmSheet = alarmSheetOpen,
        transitHub = transitHubOpen,
        maneuverList = maneuverListOpen,
        offlinePanel = offlineOpen,
        dossier = dossierTarget != null,
        savedPlace = selectedSavedPlace != null,
        layerMarker = selectedLayerMarker != null,
        peerPoint = selectedPeer != null,
        peerRoute = selectedPeerRoute != null,
        threatOblast = selectedThreatOblast != null,
        droppedPoint = droppedPoint != null,
        tools = toolsExpanded,
        searchPin = searchPin != null,
        routeOrigin = routeOriginPick != null,
        drawNaming = drawNaming != null,
        lineCard = selectedLine != null,
        drawing = drawPick != null,
        route = hasRoute,
        guiding = guiding,
    )

/**
 * Знімає один шар. Викликається лише тоді, коли [mapBackStep] сказав, який саме,
 * тож гілки тут не змагаються за те, «що важливіше» — це вже вирішено.
 */
internal fun MapUiState.applyBack(
    step: MapBackStep,
    clearRoute: () -> Unit,
    clearAnswer: () -> Unit,
    hideKeyboard: () -> Unit,
) {
    when (step) {
        MapBackStep.MOUNT -> mountMode = false
        MapBackStep.SEARCH -> {
            searchQuery = ""
            collapseSearch()
            hideKeyboard()
        }
        MapBackStep.ANCHOR_PICK -> anchorPick = null
        MapBackStep.ANCHOR_PLACING -> anchorPick = null
        MapBackStep.ANSWER -> clearAnswer()
        MapBackStep.ALARM_SHEET -> alarmSheetOpen = false
        MapBackStep.TRANSIT_HUB -> transitHubOpen = false
        MapBackStep.MANEUVER_LIST -> maneuverListOpen = false
        MapBackStep.OFFLINE_PANEL -> {
            offlineOpen = false
            offlineSelectedPack = null
        }
        MapBackStep.DOSSIER -> dossierTarget = null
        MapBackStep.SAVED_PLACE -> selectedSavedPlace = null
        MapBackStep.DRAW_NAMING -> drawNaming = null
        MapBackStep.LINE_CARD -> selectedLine = null
        MapBackStep.DRAW -> drawPick = null
        MapBackStep.LAYER_MARKER -> selectedLayerMarker = null
        MapBackStep.PEER_POINT -> selectedPeer = null
        MapBackStep.PEER_ROUTE -> selectedPeerRoute = null
        MapBackStep.THREAT_OBLAST -> selectedThreatOblast = null
        MapBackStep.DROPPED_POINT -> {
            droppedPoint = null
            droppedAddress = null
        }
        MapBackStep.TOOLS -> toolsExpanded = false
        MapBackStep.SEARCH_PIN -> searchPin = null
        MapBackStep.ROUTE_ORIGIN -> routeOriginPick = null
        MapBackStep.ROUTE -> clearRoute()
    }
}

/**
 * Вмикається рівно тоді, коли є що зняти. Вимкнений [BackHandler] пропускає
 * натискання далі — саме тому корінь мапи лишається виходом із застосунку, а не
 * пасткою.
 */
@Composable
internal fun MapBackHandler(
    ui: MapUiState,
    guiding: Boolean,
    hasRoute: Boolean,
    answerLive: Boolean,
    clearRoute: () -> Unit,
    clearAnswer: () -> Unit,
    hideKeyboard: () -> Unit,
) {
    val step = mapBackStep(
        ui.backState(guiding = guiding, hasRoute = hasRoute, answerLive = answerLive),
    )
    BackHandler(enabled = step != null) {
        step?.let {
            ui.applyBack(
                it,
                clearRoute = clearRoute,
                clearAnswer = clearAnswer,
                hideKeyboard = hideKeyboard,
            )
        }
    }
}
