package local.phantom.companion.feature.stream.ui.map

import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.File
import local.phantom.companion.core.data.transit.TransitOrigin
import local.phantom.companion.core.data.transit.TransitVehicle
import local.phantom.companion.core.data.transit.raptor.TransitMode
import local.phantom.companion.core.design.theme.AmberNight
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.core.design.theme.PhantomThemeSpec
import local.phantom.companion.core.design.theme.SunriseWarm
import local.phantom.companion.core.net.transit.VehicleOccupancy
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyph
import local.phantom.companion.feature.stream.ui.map.icons.asImageVector
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Кадри поверхні мапи: хром (пігулки, платівка, компас, поверхи) і панель
 * шарів — ті самі композабли, що на телефоні, на екрані A52. Кадр лягає в
 * build/map-look/; тест падає, лише якщо нічого не намалювалось. Поруч із
 * [MapLookShotsTest], а не в ньому: той файл ділять аркуші мапи.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "uk-w411dp-h891dp-xhdpi")
class MapSurfaceLookShotsTest {

    @Test fun chromeNight() = shot("surface-chrome-night", AmberNight) { chrome() }
    @Test fun chromeDay() = shot("surface-chrome-day", SunriseWarm) { chrome() }
    @Test fun modesNight() = shot("surface-modes-night", AmberNight) { modes() }
    @Test fun modesDay() = shot("surface-modes-day", SunriseWarm) { modes() }
    @Test fun toolsNight() = shot("surface-tools-night", AmberNight) { tools() }
    @Test fun toolsDay() = shot("surface-tools-day", SunriseWarm) { tools() }
    @Test fun offlineNight() = shot("surface-offline-night", AmberNight) { offline() }
    @Test fun offlineDay() = shot("surface-offline-day", SunriseWarm) { offline() }
    @Test fun offlineSavedNight() = shot("surface-offline-saved-night", AmberNight) { offline(route = "Київ") }
    @Test fun offlineSavedDay() = shot("surface-offline-saved-day", SunriseWarm) { offline(route = "Київ") }

    /**
     * Офлайн-панель на мапі: лише дії з цим видом (зберегти, коридор, місце
     * за назвою, шари з ПК) і двері «Усі офлайн-мапи» — без другого переліку.
     */
    @Composable
    private fun offline(route: String = "Стрий") = Box(Modifier.fillMaxSize()) {
        OfflinePanelContent(
            offer = local.phantom.companion.feature.stream.domain.OfflinePacks.Offer.Save,
            storeBytes = 1_840L * 1024 * 1024,
            groups = OfflineMapsGroups(
                places = listOf(
                    OfflinePlaceEntry(
                        placeId = "ua-46", nameUa = "Львівська область", minZoom = 5, maxZoom = 14,
                        plannedChunks = 6, doneChunks = 6, completedBytes = 1_200L * 1024 * 1024,
                        percent = 100, complete = true, downloading = false, failed = false, styleUrl = null,
                    ),
                ),
                corridors = listOf(
                    RegionInfo(
                        id = 7, name = "Київ", completedResourceCount = 40, requiredResourceCount = 100,
                        completedResourceSizeBytes = 80L * 1024 * 1024, isComplete = false, downloadState = 1,
                        kind = PackKind.Corridor, routeLabel = "Київ",
                    ),
                ),
                views = emptyList(),
            ),
            usableBytes = 0L,
            routeCorridorKm2 = 1240.0,
            routeCorridorBoxes = emptyList(),
            routeLabel = route,
            onSaveCorridor = {},
            onSaveView = {},
            onDismiss = {},
            onOpenAll = {},
            places = { OfflinePlacesDoor(otherStyle = false, onOpen = {}) },
            pcLayers = { PcLayerPacksSection(currentView = null) },
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            // Маршрут на Київ уже збережено — картка коридора стає «Показати на мапі».
            onShowPack = {},
        )
    }

    /** Спокій: тиха тривога, слова про стан, компас, поверхи, платівка. */
    @Composable
    private fun chrome() = Box(Modifier.fillMaxSize().padding(12.dp)) {
        Column(
            Modifier.align(Alignment.TopStart).padding(top = 72.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AlertBanner(
                ownOblast = "Львівська область",
                ownState = AlertState(active = false, changedEpochMs = null),
                activeCount = 3,
                verdict = SkyVerdict.QUIET,
                onOpen = {},
            )
            GroundNotSavedBanner(gap = BasemapGap.MAP, onSave = {})
            FallbackPlanBanner()
        }
        Box(Modifier.align(Alignment.TopEnd).padding(top = 72.dp)) {
            MapControlOrb(
                icon = PhantomGlyph.COMPASS.asImageVector(),
                contentDescription = "Північ",
                onClick = {},
            )
        }
        local.phantom.companion.feature.stream.ui.map.indoor.IndoorLevelSwitcher(
            visible = true,
            availableLevels = listOf(-1, 0, 1, 2),
            currentLevel = 0,
            onLevelSelected = {},
            modifier = Modifier.align(Alignment.CenterStart),
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NoticePill(text = "Маршрут прокладено")
            MapInstrumentPlate(
                metersPerDp = metersPerDp(49.84, 15.0),
                composition = null,
                operatorLocation = null,
                nowMs = 0L,
                clockSkew = null,
                compact = true,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }

    /** Режими, що чекають пальця, і машина, якої можна дочекатись — під тривогою. */
    @Composable
    private fun modes() = Box(Modifier.fillMaxSize().padding(12.dp)) {
        Column(
            Modifier.align(Alignment.TopStart).padding(top = 72.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AlertBanner(
                ownOblast = "Львівська область",
                ownState = AlertState(
                    active = true,
                    changedEpochMs = System.currentTimeMillis() - 12 * 60_000L,
                    threatType = ThreatType.SHAHED,
                ),
                activeCount = 9,
                verdict = SkyVerdict.ALERT,
                onOpen = {},
            )
            OfflineBasemapCoverageBanner(visible = true)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RouteOriginPill(label = "вулиця Городоцька, 12", onCancel = {})
            AnchorPickPill(label = "Дім", onCancel = {})
            DrawPill(
                vertexCount = 3,
                lengthLabel = "420 м",
                heldVertex = false,
                onUndo = {},
                onRemoveHeld = {},
                onDone = {},
                onCancel = {},
            )
            TransitVehicleCard(
                vehicle = TransitVehicle(
                    vehicleId = "v1",
                    tripId = null,
                    routeId = "6",
                    routeLabel = "6",
                    lat = 49.84,
                    lon = 24.03,
                    bearingDegrees = null,
                    occupancy = VehicleOccupancy.UNKNOWN,
                    fixEpochMs = 0L,
                    origin = TransitOrigin.LIVE_FEED,
                    mode = TransitMode.TRAM,
                ),
                surfaceFeed = { null },
                onShowLine = {},
                onDismiss = {},
            )
        }
    }

    /** Панель шарів — уміст аркуша «Шари», як його кладе MapLayersSheet. */
    @Composable
    private fun tools() = Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(local.phantom.companion.core.design.tokens.LocalPhantomColors.current.raised)
                .padding(16.dp),
        ) {
            MapToolsPanel(
                alertsVisible = true,
                onToggleAlerts = {},
                radarVisible = false,
                onToggleRadar = {},
                transitBusy = false,
                transitActive = false,
                anyBusy = false,
                onToggleTransit = {},
                worldLayers = listOf(
                    WorldLayerToggle("towers", "Опори ЛЕП", busy = false, active = true, onClick = {}),
                    WorldLayerToggle("fords", "Броди", busy = true, active = false, onClick = {}),
                ),
                nearbyActive = "Аптека",
                onPickNearby = { _, _ -> },
                onClearNearby = {},
                phantomLayers = emptyList(),
                routeInfo = MapViewModel.RouteInfo(
                    distanceMeters = 2350.0,
                    durationSeconds = 1740.0,
                    profile = local.phantom.companion.core.net.map.RouteProfile.FOOT,
                    destLat = 49.8419,
                    destLon = 24.0315,
                    destName = "Аптека «Подорожник»",
                ),
                onClearRoute = {},
                onToggleLayer = { _, _ -> },
                onClearLayer = {},
                onFocusLayer = {},
                whoSeesCount = 2,
                onOpenWhoSees = {},
            )
        }
    }

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
        val out = File("build/map-look").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        var inked = 0
        for (y in 0 until bitmap.height step 16) for (x in 0 until bitmap.width step 16) {
            if (bitmap.getPixel(x, y) != bitmap.getPixel(0, 0)) inked++
        }
        assertTrue("$name: кадр не намалювався", inked > 50)
        controller.pause().stop().destroy()
    }

    private companion object {
        // Земля Обсидіана (styles/obsidian-{night,day}.json, шар
        // obsidian-ground): хром стоїть на тому тлі, на якому його бачить людина.
        val MAP_NIGHT = Color(0xFF0A0C10)
        val MAP_DAY = Color(0xFFF8F4EE)
    }
}
