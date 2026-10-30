package local.phantom.companion.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * The app-wide channels every surface talks across: chat → map, map → island,
 * tool → screen. They hold no logic and depend on nothing, which is exactly why
 * they used to be the most-edited region of the container.
 */
class AppEventBus {

    /** Active thread id, published by the nav layer so `map.*` tools can scope
     *  their layers without the ViewModel growing a dependency. Read-latest at
     *  execute time only, hence a reference and not a flow. */
    val activeThreadRef: AtomicReference<String?> = AtomicReference<String?>(null)

    /** Live viewport of the open map; null while it is closed, which drops the
     *  `[MAP_VIEW:…]` prompt line. */
    val mapViewport: MutableStateFlow<
        local.phantom.companion.core.ai.context.MapViewportSnapshot?> =
        MutableStateFlow(null)

    /** A `map.*` tool wrote a layer — bring the map on screen so the operator
     *  actually sees PHANTOM's hand instead of only persisting it. */
    val mapOpenRequests: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 1)

    /**
     * Місце з чужого листа: `phantom://map`, `geo:` або посилання osm.org.
     *
     * Липкий, як і [homeTabRequest], і з тієї ж причини: намір
     * приходить в `onCreate`, а мапа складається пізніше — подія без буфера
     * загубилась би рівно в цій щілині.
     */
    val mapPointRequest: MutableStateFlow<local.phantom.companion.core.geo.link.MapLink.Point?> =
        MutableStateFlow(null)

    /** Чернетка зустрічі чекає мітку з мапи: аркуш точки першою дією пропонує «Зустрітися тут». */
    val meetupPinPick: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** Sticky: the home screen composes after the tap that asked for a tab. */
    val homeTabRequest: MutableStateFlow<local.phantom.companion.ui.home.HomeTab?> =
        MutableStateFlow(null)

    val visibleTab: MutableStateFlow<local.phantom.companion.ui.home.HomeTab> =
        MutableStateFlow(local.phantom.companion.ui.home.HomeTab.CHATS)

    /** A conversation asked for by a link or a notification. Sticky for the
     *  same reason the camera request is: the graph composes after the tap. */
    val peerChatRequest: MutableStateFlow<String?> = MutableStateFlow(null)

    /** The same for a group. Its own channel because the id is a group's: the
     *  roster lookup [peerChatRequest] lands in finds nothing for it. */
    val peerGroupRequest: MutableStateFlow<String?> = MutableStateFlow(null)

    /** Щойно прийняте запрошення — картка з шістьма цифрами, а не мовчання. */
    val peerCardRequest: MutableStateFlow<String?> = MutableStateFlow(null)

    /** A route asked for by name, debug only: ~30 surfaces need surveying. */
    val debugRouteRequest: MutableStateFlow<String?> = MutableStateFlow(null)

    /** Маршрут, замовлений шторкою: сповіщення веде на екран, а не на домівку. */
    val routeRequest: MutableStateFlow<String?> = MutableStateFlow(null)

    /** Код входу на сайт із посилання `https://phantom-os.dev/s?p=…` на цьому ж телефоні. */
    val webSignInPayload: MutableStateFlow<String?> = MutableStateFlow(null)

    val pairPayload: MutableStateFlow<String?> = MutableStateFlow(null)

    val webSignInScan: MutableStateFlow<String?> = MutableStateFlow(null)

    /** Whose conversation is open right now. A notification for a chat the
     *  operator is already reading is noise, and noise is what teaches people
     *  to silence the channel that carries the message that matters. */
    val visiblePeerChat: MutableStateFlow<String?> = MutableStateFlow(null)

    val webModalOpen: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** Яка група відкрита зараз — той самий глушник шторки, що й для розмови на двох. */
    val visiblePeerGroup: MutableStateFlow<String?> = MutableStateFlow(null)

    /**
     * Розмови, які людина в месенджері позначила «непрочитаним» (веб,
     * `chatMarkUnread`). Позначка живе у вебі; сюди її віддзеркалює міст, щоб
     * плашка вкладки «Чати» рахувала й її, а не лише справжні листи.
     */
    val webUnreadMarks: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())

    /**
     * Дзвінок PHANTOM поверх вкладки «Чати», а не окремим екраном навігації.
     * Окремий екран виймав домівку з композиції разом із WebView: «Назад»
     * будив месенджер наново — 1–2 с темного «Відкриваю месенджер…» і список
     * замість розмови з PHANTOM, з якої дзвонили (A52, 02.10).
     */
    val phantomCallOpen: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** A prompt for the Dialogue stratum fired from a chamber. replay=1 +
     *  reset-after-handle: the emitter may fire before the Continuum recomposes. */
    val streamPromptRequests: MutableSharedFlow<String> = MutableSharedFlow(replay = 1)

    /** Phantom asked for an oblast's threat brief; MapScreen opens the sheet. */
    val threatBriefRequests: MutableSharedFlow<String> = MutableSharedFlow(extraBufferCapacity = 1)

    /** `nav.start`/`nav.stop` from PHANTOM's hand to the map surface. replay=1
     *  so the command survives until MapScreen's collector composes; MapScreen
     *  resets the cache after handling so it never re-fires on a later open. */
    val navCommands: MutableSharedFlow<
        local.phantom.companion.core.nav.NavCommand> = MutableSharedFlow(replay = 1)

    /** The selected route's polyline while one is live. Null = no route. */
    val activeRoutePoints: MutableStateFlow<List<Pair<Double, Double>>?> = MutableStateFlow(null)

    /** WidgetTool posts (id, type, payload) here; StreamViewModel subscribes. */
    val toolWidgetEmissions: MutableSharedFlow<Triple<String, String, String>> =
        MutableSharedFlow(extraBufferCapacity = 8)

    /** Formatted digest lines fired by the scheduler at the operator's 9am. */
    val notificationDigestFlow: MutableSharedFlow<String> =
        MutableSharedFlow(replay = 1, extraBufferCapacity = 4)
}
