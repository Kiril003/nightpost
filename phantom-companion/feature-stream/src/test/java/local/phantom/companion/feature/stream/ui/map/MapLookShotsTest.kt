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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.File
import local.phantom.companion.core.design.theme.AmberNight
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomRadii
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.core.design.theme.PhantomThemeSpec
import local.phantom.companion.core.design.theme.SunriseWarm
import local.phantom.companion.core.truth.FactKind
import local.phantom.companion.feature.stream.ui.map.here.HereNowState
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyph
import local.phantom.companion.feature.stream.ui.map.icons.asImageVector
import local.phantom.companion.feature.stream.ui.map.here.HereReach
import local.phantom.companion.feature.stream.ui.map.here.HereRow
import local.phantom.companion.feature.stream.ui.map.peers.PeerPointCard
import local.phantom.companion.feature.stream.ui.map.peers.PeerWhereCard
import local.phantom.companion.feature.stream.ui.map.peers.TappedPeer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Кадри аркушів мапи — ті самі композабли, що на телефоні, на екрані A52.
 * Кадр лягає в build/map-look/; тест падає, лише якщо аркуш не намалювався.
 */
@RunWith(RobolectricTestRunner::class)
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "uk-w411dp-h891dp-xhdpi")
class MapLookShotsTest {

    private val here = 49.8397 to 24.0297
    private val point = 49.8419 to 24.0315
    private val nowMs = 1_790_000_000_000L

    @Test fun pointSheetNight() = shot("point-night", AmberNight) { pointSheet() }
    @Test fun pointSheetDay() = shot("point-day", SunriseWarm) { pointSheet() }
    // Великий системний шрифт, як у власника: підписи плиток мусять влізти.
    @Test fun pointSheetLargeFont() = shot("point-large-font", SunriseWarm, fontScale = 1.3f) { pointSheet() }
    @Test fun dossierLargeFont() = shot("dossier-large-font", SunriseWarm, fontScale = 1.3f) { dossier() }
    @Test fun layerMarkerNight() = shot("marker-night", AmberNight) {
        SheetFrame {
            LayerMarkerCard(
                marker = TappedLayerMarker(
                    label = "Укриття «Школа №8»",
                    lat = point.first,
                    lon = point.second,
                    layerTitle = "Укриття",
                    detail = "Підвал, вхід з двору",
                    glyph = null,
                ),
                accentColor = LocalPhantomTheme.current.primary,
                onAskAi = {},
                onRoute = {},
                onOpenExternal = {},
            )
        }
    }
    @Test fun peerWhereNight() = shot("peer-where-night", AmberNight) {
        SheetFrame {
            PeerWhereCard(
                peer = peer(precision = "QUARTER", near = "NEAR"),
                accentColor = LocalPhantomTheme.current.primary,
                onWrite = {},
                onWhoSees = {},
                nowMs = nowMs,
            )
        }
    }
    @Test fun peerExactDay() = shot("peer-exact-day", SunriseWarm) {
        SheetFrame {
            PeerWhereCard(
                peer = peer(precision = "EXACT", near = "SAME"),
                accentColor = LocalPhantomTheme.current.primary,
                onWrite = {},
                onWhoSees = {},
                nowMs = nowMs,
                onRoute = {},
                origin = here,
            )
        }
    }
    @Test fun worldSheetDay() = shot("world-sheet-day", SunriseWarm) { worldSheet() }
    @Test fun worldSheetNight() = shot("world-sheet-night", AmberNight) { worldSheet() }

    /** Усі шістнадцять тіл: звичайне, акцент (мета/тривога) і приглушене (зачинено/давнє). */
    @Composable
    private fun worldSheet() {
        val dark = LocalPhantomTheme.current.isDark
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            local.phantom.companion.feature.stream.ui.map.world.WorldKind.entries.chunked(4).forEach { row ->
                androidx.compose.foundation.layout.Row {
                    row.forEach { kind ->
                        Column(Modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            androidx.compose.foundation.layout.Row {
                                listOf(false to 1f, true to 1f, false to 0.5f).forEach { (accent, alpha) ->
                                    val bmp = remember(kind, accent, dark) {
                                        local.phantom.companion.feature.stream.ui.map.icons.marks.WorldFigures
                                            .figure(kind, dark, accent, density).asImageBitmap()
                                    }
                                    androidx.compose.foundation.Image(bmp, null, Modifier.size(28.dp, 34.dp).alpha(alpha))
                                }
                            }
                            local.phantom.companion.core.design.theme.PhantomCaption(kind.id, maxLines = 1)
                        }
                    }
                }
            }
            local.phantom.companion.core.design.theme.PhantomCaption("shelter — дихання під тривогою, кадри 0…1 періоду")
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.Bottom) {
                val shelter = remember(dark) {
                    local.phantom.companion.feature.stream.ui.map.icons.marks.WorldFigures
                        .figure(local.phantom.companion.feature.stream.ui.map.world.WorldKind.SHELTER, dark, true, density).asImageBitmap()
                }
                (0..5).forEach { i ->
                    val f = local.phantom.companion.feature.stream.ui.map.world.ShelterBreath
                        .frame(local.phantom.companion.feature.stream.ui.map.world.ShelterBreath.PERIOD_MS * i / 6)!!
                    Box(Modifier.size(64.dp), contentAlignment = Alignment.BottomCenter) {
                        Box(
                            Modifier.padding(bottom = 2.dp).size((f.radiusDp * 2).dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(LocalPhantomTheme.current.primary.copy(alpha = f.opacity)),
                        )
                        androidx.compose.foundation.Image(shelter, null, Modifier.size(36.dp, 44.dp))
                    }
                }
            }
        }
    }

    @Test fun whoSeesNight() = shot("who-sees-night", AmberNight) {
        SheetFrame {
            local.phantom.companion.feature.stream.ui.map.peers.WhoSeesMeSheet(
                rows = listOf(
                    local.phantom.companion.core.data.peers.PeerSeeRow("a", "Оксана", local.phantom.companion.core.data.peers.PeerPrecision.QUARTER, true),
                    local.phantom.companion.core.data.peers.PeerSeeRow("b", "Тарас", null, true),
                    local.phantom.companion.core.data.peers.PeerSeeRow("c", "Мама", local.phantom.companion.core.data.peers.PeerPrecision.EXACT, true),
                    local.phantom.companion.core.data.peers.PeerSeeRow("d", "Андрій з роботи", null, false),
                ),
                accentColor = LocalPhantomTheme.current.primary,
                onSet = { _, _ -> },
                onVanish = {},
            )
        }
    }
    @Test fun peerPointNight() = shot("peer-point-night", AmberNight) {
        SheetFrame {
            PeerPointCard(
                peer = peer(precision = null, near = null),
                origin = here,
                accentColor = LocalPhantomTheme.current.primary,
                onWrite = {},
                onRoute = {},
                onShowMe = {},
            )
        }
    }

    @Test fun controlsNight() = shot("controls-night", AmberNight) { controls() }
    @Test fun controlsDay() = shot("controls-day", SunriseWarm) { controls() }

    // Пігулки й орб — та сама мова, що й чипи дій (MapCardParts.kt). Деки
    // з гніздами та пігулки «Укриття» в живій мапі більше нема: дека й
    // ShelterQuickPill не мали жодного виклику, тож кадр їх більше не малює.
    // Повна поверхня (тривога, платівка, поверхи, картки) — MapSurfaceLookShotsTest.
    @Composable
    private fun controls() = Box(Modifier.fillMaxSize().padding(16.dp)) {
        Column(
            Modifier.align(Alignment.TopStart),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            RouteOriginPill(label = "вулиця Городоцька, 12", onCancel = {})
            NoticePill(text = "Маршрут прокладено")
        }
        Column(
            Modifier.align(Alignment.CenterEnd),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            MapControlOrb(PhantomGlyph.COMPASS.asImageVector(), "Північ", {})
        }
    }

    // Аркуш пошуку прилітає ефектом, а Compose кешує диспетчер головного потоку між
    // тестами одного JVM — у другому тесті ефект уже не біжить. Тому пошук іде першим.
    @Test fun aaSearch() {
        shot("search-first-night", AmberNight) { search("") }
        shot("search-first-day", SunriseWarm) { search("") }
        shot("search-results-night", AmberNight) { search("аптека") }
        shot("search-results-day", SunriseWarm) { search("аптека") }
    }
    @Test fun routeNight() = shot("route-night", AmberNight) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)) {
                MapCommandSheet(
                    info = local.phantom.companion.feature.stream.ui.map.MapViewModel.RouteInfo(
                        distanceMeters = 2350.0,
                        durationSeconds = 1740.0,
                        profile = local.phantom.companion.core.net.map.RouteProfile.FOOT,
                        destLat = point.first,
                        destLon = point.second,
                        destName = "Аптека «Подорожник»",
                    ),
                    busy = false,
                    options = emptyList(),
                    selectedIndex = 0,
                    deadlineLine = null,
                    onSelectVariant = {},
                    onProfile = {},
                    onClear = {},
                    onStartGuidance = {},
                    onFlyover = {},
                )
            }
        }
    }
    @Test fun guidanceNight() = shot("guidance-night", AmberNight) {
        val progress = local.phantom.companion.core.nav.GuidanceProgress(
            snappedLat = here.first,
            snappedLon = here.second,
            bearingDeg = 40.0,
            maneuverType = "turn",
            maneuverModifier = "left",
            roadName = "вулиця Городоцька",
            distanceToManeuverM = 180.0,
            remainingDistanceM = 2100.0,
            remainingDurationS = 1560.0,
            speedKmh = 5,
            limitKmh = null,
            offRoute = false,
            nextManeuverType = "turn",
            nextManeuverModifier = "right",
            nextManeuverRoadName = "вулиця Шевченка",
            distanceBetweenManeuversM = 90.0,
            stepFraction = 0.4f,
            routeFraction = 0.2f,
            landmark = local.phantom.companion.core.nav.StepHint(
                local.phantom.companion.core.nav.LandmarkKind.SUPERMARKET,
                "АТБ",
                local.phantom.companion.core.nav.LandmarkRelation.AFTER,
            ),
        )
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.align(Alignment.TopCenter).padding(top = 16.dp)) {
                local.phantom.companion.feature.stream.ui.map.nav.ManeuverBanner(progress = progress)
            }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)) {
                local.phantom.companion.feature.stream.ui.map.nav.EtaStrip(progress = progress, onStop = {})
            }
        }
    }
    @Test fun dossierNight() = shot("dossier-night", AmberNight) { dossier() }
    @Test fun dossierDay() = shot("dossier-day", SunriseWarm) { dossier() }

    @Composable
    private fun dossier() = SheetFrame {
        val target = local.phantom.companion.feature.stream.ui.map.dossier.DossierTarget(
            lat = point.first, lon = point.second,
            nameHint = "Подорожник", categoryHint = "amenity", subcategoryHint = "pharmacy",
        )
        val base = local.phantom.companion.feature.stream.ui.map.dossier.instantDossier(target)
        local.phantom.companion.feature.stream.ui.map.dossier.PlaceDossierContent(
            dossier = base.copy(
                address = "вулиця Городоцька, 12, Львів",
                tags = mapOf(
                    "amenity" to "pharmacy",
                    "name" to "Подорожник",
                    "opening_hours" to "Mo-Su 08:00-22:00",
                    "phone" to "+380 32 000 00 00",
                    "wheelchair" to "yes",
                ),
                rawHours = "Mo-Su 08:00-22:00",
                openStatus = local.phantom.companion.core.net.map.OpeningHours.Status.Open(22 * 60),
                pending = emptySet(),
            ),
            hereNow = HereNowState(
                reach = HereReach.ON,
                rows = listOf(HereRow(FactKind.PHARMACY, verdict = null, held = false, sent = false)),
            ),
            accentColor = LocalPhantomTheme.current.primary,
            briefing = null,
            onBrief = {}, onBriefRetry = {}, onRoute = {}, onSave = {}, onShare = {},
            onOpenExternal = {}, onOpenUrl = {}, onDial = {}, onShowLine = {}, onRetarget = {},
            target = target,
            origin = here,
        )
    }

    @Composable
    private fun search(query: String) = MapSearchSheet(
        query = query,
        onQueryChange = {}, onSubmit = {}, onClose = {},
        inFlight = false,
        submitted = query.isNotEmpty(),
        gap = null,
        hereCountry = null,
        results = emptyList(),
        offlineHits = if (query.isEmpty()) emptyList() else listOf(
            local.phantom.companion.core.data.poi.PoiHit(1, 49.8421, 24.0301, "Подорожник", "amenity", "pharmacy", 210.0),
            local.phantom.companion.core.data.poi.PoiHit(2, 49.8388, 24.0265, "Аптека Доброго Дня", "amenity", "pharmacy", 480.0),
            local.phantom.companion.core.data.poi.PoiHit(3, 49.8455, 24.0350, "D.S. Аптека", "amenity", "pharmacy", 910.0),
        ),
        recent = listOf("укриття", "Нова пошта"),
        saved = listOf(
            local.phantom.companion.core.data.poi.PoiEntity(-1, 49.84, 24.02, "Дім", "home", null, emptyMap(), 0L),
            local.phantom.companion.core.data.poi.PoiEntity(-2, 49.83, 24.01, "Кавʼярня біля парку", "custom", null, emptyMap(), 0L),
        ),
        here = here,
        alongRoute = false,
        onPickCategory = { _, _ -> }, onSearchHere = { _, _ -> }, onFloodByName = {},
        onResultTap = {}, onOfflineTap = {}, onSavedTap = {}, onAskAi = {}, onPickRecent = {},
        onClearRecent = {}, onSetAnchor = { _, _ -> },
    )

    private fun peer(precision: String?, near: String?) = TappedPeer(
        nodeId = "n1",
        threadId = "t1",
        name = "Оксана",
        lat = point.first,
        lon = point.second,
        atMs = nowMs - 7 * 60_000L,
        verified = true,
        fresh = false,
        precision = precision,
        near = near,
    )

    @Composable
    private fun pointSheet() = SheetFrame {
        PointActionsSheet(
            lat = point.first,
            lon = point.second,
            address = "вулиця Городоцька, 12, Львів, Львівська область, 79000, Україна",
            here = here,
            accentColor = LocalPhantomTheme.current.primary,
            onWhatHere = {},
            onRoute = {},
            onRouteFrom = {},
            onVia = null,
            onMark = {},
            onIsochrone = {},
            onDangerField = {},
            onSaveMark = { _, _ -> },
            onShare = {},
            onOpenExternal = {},
            hereNow = HereNowState(
                reach = HereReach.ON,
                rows = listOf(
                    HereRow(FactKind.POWER, verdict = null, held = false, sent = false),
                    HereRow(FactKind.ROAD, verdict = null, held = false, sent = false),
                ),
            ),
        )
    }

    private fun shot(name: String, spec: PhantomThemeSpec, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        MapText.bind(activity)
        activity.setContent {
            PhantomTheme(spec = spec, fontScale = fontScale) {
                val theme = LocalPhantomTheme.current
                // Стоп-кадр: прильоти стають на місце одразу.
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
        // PixelCopy малює через HardwareRenderer — інакше шари graphicsLayer не потрапляють у кадр.
        android.view.PixelCopy.request(activity.window, full, {}, android.os.Handler(Looper.getMainLooper()))
        shadowOf(Looper.getMainLooper()).idle()
        val at = IntArray(2).also { root.getLocationInWindow(it) }
        val bitmap = Bitmap.createBitmap(full, at[0], at[1], root.width, root.height)
        val out = File("build/map-look").apply { mkdirs() }
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

/** Те, що ModalBottomSheet мапи дає аркушу: пелена, верхні кути, ручка. */
@Composable
private fun SheetFrame(content: @Composable () -> Unit) {
    val theme = LocalPhantomTheme.current
    Box(Modifier.fillMaxSize().background(theme.surfaceDeep.copy(alpha = 0.55f))) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(PhantomRadii.SheetTop)
                .background(theme.surfaceBase),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .padding(top = 22.dp, bottom = 0.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .clip(PhantomRadii.Pill)
                    .background(theme.inkSecondary.copy(alpha = 0.4f)),
            )
            content()
        }
    }
}
