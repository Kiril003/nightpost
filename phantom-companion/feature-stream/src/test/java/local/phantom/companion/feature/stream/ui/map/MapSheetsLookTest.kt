package local.phantom.companion.feature.stream.ui.map

import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.File
import local.phantom.companion.core.data.transit.AccessMode
import local.phantom.companion.core.data.transit.Itinerary
import local.phantom.companion.core.data.transit.TransitCoverage
import local.phantom.companion.core.data.transit.TransitPlane
import local.phantom.companion.core.data.transit.metro.Headways
import local.phantom.companion.core.data.transit.metro.MetroDoc
import local.phantom.companion.core.data.transit.metro.MetroFeed
import local.phantom.companion.core.data.transit.metro.MetroLine
import local.phantom.companion.core.data.transit.metro.MetroStation
import local.phantom.companion.core.data.transit.raptor.TransitMode
import local.phantom.companion.core.data.transit.surface.SurfaceDoc
import local.phantom.companion.core.data.transit.surface.SurfaceFeed
import local.phantom.companion.core.data.transit.surface.SurfaceHeadway
import local.phantom.companion.core.data.transit.surface.SurfaceLine
import local.phantom.companion.core.data.transit.surface.SurfaceStop
import local.phantom.companion.core.design.theme.AmberNight
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.core.design.theme.PhantomThemeSpec
import local.phantom.companion.core.design.theme.SunriseWarm
import local.phantom.companion.core.net.map.OpeningHours
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.feature.stream.ui.map.dossier.BoardRow
import local.phantom.companion.feature.stream.ui.map.dossier.BoardShown
import local.phantom.companion.feature.stream.ui.map.dossier.DepartureBoard
import local.phantom.companion.feature.stream.ui.map.dossier.DossierInterchange
import local.phantom.companion.feature.stream.ui.map.dossier.DossierStopSummary
import local.phantom.companion.feature.stream.ui.map.dossier.DossierTarget
import local.phantom.companion.feature.stream.ui.map.dossier.DossierTransit
import local.phantom.companion.feature.stream.ui.map.dossier.DossierTransitLine
import local.phantom.companion.feature.stream.ui.map.dossier.PlaceDossierContent
import local.phantom.companion.feature.stream.ui.map.dossier.TransitDepartureCountdown
import local.phantom.companion.feature.stream.ui.map.dossier.instantDossier
import local.phantom.companion.feature.stream.ui.map.transit.TransitHubSheet
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Кадри аркушів, що піднімаються з мапи: картка місця, зупинка, табло,
 * смуга маршруту (є маршрут / нема), транспортний хаб, відповідь PHANTOM.
 * Ніч — AmberNight (NightColors), день — SunriseWarm (DayColors).
 *
 * ModalBottomSheet відкриває власне вікно, і PixelCopy активності його не
 * бачить, тож аркуш малюється прямо: поверхня аркуша + той самий вміст, що на
 * телефоні. Кадр лягає в build/map-sheets-look/; тест падає, лише коли аркуш
 * не намалювався.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "uk-w390dp-h844dp-xhdpi")
class MapSheetsLookTest {

    private val here = 50.4501 to 30.5234
    private val stop = 50.4470 to 30.5220

    @Test fun dossierPoiNight() = shot("dossier-poi-night", AmberNight) { dossierPoi() }
    @Test fun dossierPoiDay() = shot("dossier-poi-day", SunriseWarm) { dossierPoi() }
    @Test fun dossierStopNight() = shot("dossier-stop-night", AmberNight) { dossierStop() }
    @Test fun dossierStopDay() = shot("dossier-stop-day", SunriseWarm) { dossierStop() }
    @Test fun boardNight() = shot("board-night", AmberNight) { Sheet { Column(Modifier.padding(horizontal = 16.dp)) { board() } } }
    @Test fun boardDay() = shot("board-day", SunriseWarm) { Sheet { Column(Modifier.padding(horizontal = 16.dp)) { board() } } }
    @Test fun routeOkNight() = shot("route-ok-night", AmberNight) { band { routeOk() } }
    @Test fun routeOkDay() = shot("route-ok-day", SunriseWarm) { band { routeOk() } }
    @Test fun routeTransitNight() = shot("route-transit-night", AmberNight) { band { routeTransit() } }
    @Test fun routeTransitDay() = shot("route-transit-day", SunriseWarm) { band { routeTransit() } }
    @Test fun routeFailNight() = shot("route-fail-night", AmberNight) { band { routeFail() } }
    @Test fun routeFailDay() = shot("route-fail-day", SunriseWarm) { band { routeFail() } }
    @Test fun hubNight() = shot("hub-night", AmberNight) { hub() }
    @Test fun hubDay() = shot("hub-day", SunriseWarm) { hub() }
    @Test fun answerNight() = shot("answer-night", AmberNight) { answer() }
    @Test fun answerDay() = shot("answer-day", SunriseWarm) { answer() }

    // ── картка місця: АЗС — категорійна картка, години, телефон ─────────────

    @Composable
    private fun dossierPoi() = Sheet {
        val target = DossierTarget(
            lat = stop.first, lon = stop.second,
            nameHint = "ОККО", categoryHint = "amenity", subcategoryHint = "fuel",
        )
        PlaceDossierContent(
            dossier = instantDossier(target).copy(
                address = "вулиця Велика Васильківська, 72, Київ",
                tags = mapOf(
                    "amenity" to "fuel",
                    "name" to "ОККО",
                    "fuel:octane_95" to "yes",
                    "fuel:diesel" to "yes",
                    "fuel:lpg" to "yes",
                    "compressed_air" to "yes",
                    "toilets" to "yes",
                    "opening_hours" to "24/7",
                    "phone" to "+380 44 000 00 00",
                ),
                rawHours = "24/7",
                openStatus = OpeningHours.Status.Open(24 * 60),
                pending = emptySet(),
            ),
            accentColor = local.phantom.companion.core.design.tokens.LocalPhantomColors.current.accent,
            briefing = null,
            onBrief = {}, onBriefRetry = {}, onRoute = {}, onVia = {}, onSave = {}, onShare = {},
            onOpenExternal = {}, onOpenUrl = {}, onDial = {}, onShowLine = {}, onRetarget = {},
            target = target,
            origin = here,
        )
    }

    // ── зупинка: табло + лінії з кольором джерела і без нього ───────────────

    @Composable
    private fun dossierStop() = Sheet {
        val target = DossierTarget(
            lat = stop.first, lon = stop.second,
            nameHint = "Площа Льва Толстого", categoryHint = "highway", subcategoryHint = "bus_stop",
        )
        PlaceDossierContent(
            dossier = instantDossier(target).copy(
                tags = mapOf("highway" to "bus_stop", "name" to "Площа Льва Толстого", "shelter" to "yes", "bench" to "yes"),
                pending = emptySet(),
                transit = DossierTransit(
                    lines = listOf(
                        DossierTransitLine(
                            ref = "114", mode = "trolleybus", direction = "Голосіїв → Палац Спорту",
                            headwayMinutes = null, running = true,
                            departures = listOf(
                                TransitDepartureCountdown(timeClock = "12:06", inMinutes = 2, modelled = false),
                                TransitDepartureCountdown(timeClock = "12:18", inMinutes = 14, modelled = false),
                            ),
                            stops = listOf(DossierStopSummary("a", "Площа Льва Толстого", isCurrent = true)),
                        ),
                        DossierTransitLine(
                            ref = "М2", mode = "subway", direction = "Героїв Дніпра → Теремки",
                            headwayMinutes = null, running = true, colourArgb = 0xFF1F5FBF.toInt(),
                        ),
                        DossierTransitLine(
                            ref = "38", mode = "bus", direction = null, headwayMinutes = null, running = false,
                        ),
                    ),
                    interchanges = listOf(DossierInterchange(station = "Площа Українських Героїв", lineRef = "М2", walkMinutes = 3)),
                ),
            ),
            accentColor = local.phantom.companion.core.design.tokens.LocalPhantomColors.current.accent,
            briefing = null,
            onBrief = {}, onBriefRetry = {}, onRoute = {}, onSave = {}, onShare = {},
            onOpenExternal = {}, onOpenUrl = {}, onDial = {}, onShowLine = {}, onRetarget = {},
            target = target,
            origin = here,
            departureBoard = { _, _, _ -> board() },
        )
    }

    @Composable
    private fun board() = DepartureBoard(airport = false, shown = BoardShown.Rows(boardRows()))

    private fun boardRows(): List<BoardRow> {
        fun row(key: String, label: String, mode: String, colour: Int?, head: String, mins: Int?, clock: String, late: Int? = null, past: Boolean = false) =
            BoardRow(
                key = key, label = label, mode = mode, colourArgb = colour, headsign = head,
                atMs = 0L, clock = clock, inMinutes = mins, past = past, lateMinutes = late,
                usuallyLateMinutes = null, cancelled = false, platform = null, operator = null,
                tripId = null, lineKey = "$label/$mode", zone = "Europe/Kyiv", stopName = "Площа Льва Толстого",
            )
        return listOf(
            row("p", "114", "trolleybus", null, "Палац Спорту", null, "11:58", past = true),
            row("a", "114", "trolleybus", null, "Палац Спорту", 2, "12:06"),
            row("b", "М2", "subway", 0xFF1F5FBF.toInt(), "Теремки", 5, "12:09"),
            row("c", "38", "bus", null, "Виставковий центр", 11, "12:15", late = 4),
            row("d", "5", "tram", null, "Контрактова площа", 19, "12:23"),
        )
    }

    // ── смуга маршруту ──────────────────────────────────────────────────────

    @Composable
    private fun band(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)) { content() }
    }

    @Composable
    private fun routeOk() = MapCommandSheet(
        info = MapViewModel.RouteInfo(
            distanceMeters = 2350.0,
            durationSeconds = 1740.0,
            profile = RouteProfile.FOOT,
            destLat = stop.first,
            destLon = stop.second,
            destName = "Площа Льва Толстого",
        ),
        busy = false,
        options = emptyList(),
        selectedIndex = 0,
        deadlineLine = null,
        onSelectVariant = {}, onProfile = {}, onClear = {}, onStartGuidance = {}, onFlyover = {},
    )

    @Composable
    private fun routeTransit() = MapCommandSheet(
        info = MapViewModel.RouteInfo(
            distanceMeters = 6100.0,
            durationSeconds = 1560.0,
            profile = RouteProfile.TRANSIT,
            destLat = stop.first,
            destLon = stop.second,
            destName = "Площа Льва Толстого",
            itinerary = Itinerary(
                legs = listOf(
                    Itinerary.Leg.Access(
                        mode = AccessMode.WALK, fromLat = here.first, fromLon = here.second,
                        toLat = 50.448, toLon = 30.521, toStopName = "Хрещатик", seconds = 240, meters = 320,
                    ),
                    Itinerary.Leg.Ride(
                        routeLabel = "М1", mode = TransitMode.METRO, colour = -1,
                        fromStopName = "Хрещатик", toStopName = "Палац Спорту",
                        fromLat = 50.448, fromLon = 30.521, toLat = 50.438, toLon = 30.520,
                        departSec = 12 * 3600 + 240, arriveSec = 12 * 3600 + 600,
                        intermediateStops = 1, timesAreModelled = true, headsign = "Лісова",
                    ),
                ),
                departSec = 12 * 3600,
                arriveSec = 12 * 3600 + 900,
                transfers = 0,
                hasModelledTimes = true,
            ),
        ),
        busy = false,
        options = emptyList(),
        selectedIndex = 0,
        deadlineLine = null,
        onSelectVariant = {}, onProfile = {}, onClear = {}, onStartGuidance = {}, onFlyover = {},
        onSetDeadline = { true },
        onClearDeadline = {},
        onOpenManeuverList = {},
    )

    @Composable
    private fun routeFail() = MapCommandSheet(
        info = null,
        busy = false,
        options = emptyList(),
        selectedIndex = 0,
        deadlineLine = null,
        onSelectVariant = {}, onProfile = {}, onClear = {}, onStartGuidance = {}, onFlyover = {},
        failure = RouteFailure(bearingDeg = 212, straightMeters = 1840.0, profile = RouteProfile.FOOT),
    )

    // ── транспортний хаб ────────────────────────────────────────────────────

    @Composable
    private fun hub() = Sheet {
        val surface = SurfaceFeed(
            SurfaceDoc(
                city = "Київ", serviceStartSec = 5 * 3600, serviceEndSec = 24 * 3600,
                lines = listOf(
                    SurfaceLine(
                        ref = "114", mode = "trolleybus", name = "Голосіїв → Палац Спорту",
                        headway = SurfaceHeadway(600, 900, 1200),
                        stops = listOf(
                            SurfaceStop("s1", "Площа Льва Толстого", stop.first, stop.second),
                            SurfaceStop("s2", "Палац Спорту", 50.4385, 30.5205),
                        ),
                        hopSeconds = listOf(180),
                    ),
                    SurfaceLine(
                        ref = "5", mode = "tram", name = "Контрактова площа",
                        headway = SurfaceHeadway(600, 900, 1200),
                        stops = listOf(
                            SurfaceStop("t1", "Поштова площа", 50.4590, 30.5250),
                            SurfaceStop("t2", "Контрактова площа", 50.4650, 30.5160),
                        ),
                        hopSeconds = listOf(240),
                    ),
                ),
            ),
        )
        val metro = MetroFeed(
            MetroDoc(
                city = "Київ", serviceStartSec = 5 * 3600 + 1800, serviceEndSec = 24 * 3600,
                headwaySeconds = Headways(150, 300, 420),
                lines = listOf(
                    MetroLine(
                        ref = "М2", name = "Оболонсько-Теремківська", colour = "#1F5FBF",
                        stations = listOf(
                            MetroStation("m1", "Площа Льва Толстого", 50.4393, 30.5165),
                            MetroStation("m2", "Палац Спорту", 50.4385, 30.5205),
                        ),
                        hopSeconds = listOf(120),
                    ),
                ),
            ),
        )
        Box(Modifier.fillMaxWidth().height(640.dp)) {
            TransitHubSheet(
                surfaceFeed = surface,
                metroFeed = metro,
                transitRepository = null,
                coverage = TransitCoverage(plane = TransitPlane.SIMULATED_MODEL),
                currentLat = here.first,
                currentLon = here.second,
                onSelectStop = {}, onShowLineOnMap = {}, onRouteTo = { _, _, _ -> }, onClose = {},
            )
        }
    }

    // ── відповідь PHANTOM на мапі ───────────────────────────────────────────

    @Composable
    private fun answer() = Box(Modifier.fillMaxSize()) {
        MapAnswerSheet(
            answer = MapAnswer(
                question = "де найближча аптека, що працює вночі?",
                text = "Найближча цілодобова — «Подорожник» на Великій Васильківській, 72: " +
                    "620 м, близько 8 хвилин пішки. Друга — на Саксаганського, 1,1 км.",
                phase = MapAnswer.Phase.DONE,
            ),
            onClose = {}, onShowOnMap = {}, onContinueInChat = {}, onRetry = {},
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    // ── кадр ────────────────────────────────────────────────────────────────

    private fun shot(name: String, spec: PhantomThemeSpec, content: @Composable () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent {
            PhantomTheme(spec = spec) {
                val theme = LocalPhantomTheme.current
                androidx.compose.runtime.CompositionLocalProvider(
                    local.phantom.companion.core.design.motion.LocalMotionScale provides 0f,
                ) {
                    Box(Modifier.fillMaxSize().background(if (theme.isDark) MAP_NIGHT else MAP_DAY)) {
                        content()
                    }
                }
            }
        }
        repeat(40) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }
        val root = activity.findViewById<android.view.View>(android.R.id.content)
        val full = Bitmap.createBitmap(activity.window.decorView.width, activity.window.decorView.height, Bitmap.Config.ARGB_8888)
        android.view.PixelCopy.request(activity.window, full, {}, android.os.Handler(Looper.getMainLooper()))
        shadowOf(Looper.getMainLooper()).idle()
        val at = IntArray(2).also { root.getLocationInWindow(it) }
        val bitmap = Bitmap.createBitmap(full, at[0], at[1], root.width, root.height)
        val out = File("build/map-sheets-look").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        var inked = 0
        for (y in 0 until bitmap.height step 16) for (x in 0 until bitmap.width step 16) {
            if (bitmap.getPixel(x, y) != bitmap.getPixel(0, 0)) inked++
        }
        assertTrue("$name: аркуш не намалювався", inked > 50)
        controller.pause().stop().destroy()
    }

    private companion object {
        val MAP_NIGHT = Color(0xFF1D1A16)
        val MAP_DAY = Color(0xFFEAE3D2)
    }
}

/**
 * Поверхня, яку SheetHost дає аркушу: та сама [MapSheetSurface], що стоїть
 * у [MapSheet] (кути, `raised`, ручка `lineStrong`), над пеленою `scrim`.
 */
@Composable
private fun Sheet(content: @Composable () -> Unit) {
    val c = local.phantom.companion.core.design.tokens.LocalPhantomColors.current
    Box(Modifier.fillMaxSize().background(c.scrim)) {
        MapSheetSurface(Modifier.align(Alignment.BottomCenter)) { content() }
    }
}
