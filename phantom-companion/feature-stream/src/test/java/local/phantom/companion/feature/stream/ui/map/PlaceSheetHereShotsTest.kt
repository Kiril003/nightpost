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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import java.io.File
import local.phantom.companion.core.design.motion.LocalMotionScale
import local.phantom.companion.core.design.theme.AmberNight
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomRadii
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.core.design.theme.PhantomThemeSpec
import local.phantom.companion.core.design.theme.SunriseWarm
import local.phantom.companion.core.net.map.OpeningHours
import local.phantom.companion.core.truth.CellId
import local.phantom.companion.core.truth.FactKey
import local.phantom.companion.core.truth.FactKind
import local.phantom.companion.core.truth.Place
import local.phantom.companion.core.truth.Reading
import local.phantom.companion.core.truth.TruthState
import local.phantom.companion.core.truth.Verdict
import local.phantom.companion.feature.stream.ui.map.dossier.DossierTarget
import local.phantom.companion.feature.stream.ui.map.dossier.PlaceDossierContent
import local.phantom.companion.feature.stream.ui.map.dossier.instantDossier
import local.phantom.companion.feature.stream.ui.map.here.HereNowState
import local.phantom.companion.feature.stream.ui.map.here.HereReach
import local.phantom.companion.feature.stream.ui.map.here.HereRow
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "uk-w390dp-h844dp-xhdpi")
class PlaceSheetHereShotsTest {

    private val cell = CellId("c")
    private val target = DossierTarget(
        lat = 50.0870, lon = 14.4210,
        nameHint = "Lékárna U Anděla", categoryHint = "amenity", subcategoryHint = "pharmacy",
    )

    private fun said(kind: FactKind, reading: Reading, agoMin: Int) = Verdict(
        key = FactKey(Place(cell), kind), state = TruthState.LIKELY, reading = reading,
        probability = 0.9, mass = 3.0, supporters = 3,
        lastSupportAtMs = System.currentTimeMillis() - agoMin * 60_000L,
        newestAtMs = System.currentTimeMillis() - agoMin * 60_000L, dissent = false,
    )

    private fun reported() = HereNowState(
        cell, HereReach.ON,
        listOf(
            HereRow(FactKind.PHARMACY, said(FactKind.PHARMACY, Reading.WORKING, 12), held = false, sent = false),
            HereRow(FactKind.QUEUE, said(FactKind.QUEUE, Reading.QUEUE_SHORT, 12), held = false, sent = false),
            HereRow(FactKind.POWER, null, held = false, sent = false),
        ),
    )

    private fun silent() = HereNowState(
        cell, HereReach.ON,
        FactKind.values().map { HereRow(it, null, held = false, sent = false) },
    )

    @Test fun reportedDay() = shot("place-here-day", SunriseWarm) { sheet(reported()) }
    @Test fun reportedNight() = shot("place-here-night", AmberNight) { sheet(reported()) }
    @Test fun silentDay() = shot("place-quiet-day", SunriseWarm) { sheet(silent()) }
    @Test fun silentNight() = shot("place-quiet-night", AmberNight) { sheet(silent()) }
    @Test fun offlineNight() = shot("place-offline-night", AmberNight) { sheet(reported().copy(reach = HereReach.UNREACHABLE)) }

    @Composable
    private fun sheet(here: HereNowState) {
        val theme = LocalPhantomTheme.current
        Box(Modifier.fillMaxSize().background(theme.surfaceDeep.copy(alpha = 0.55f))) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(PhantomRadii.SheetTop).background(theme.surfaceBase),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.padding(top = 22.dp).size(32.dp, 4.dp).clip(PhantomRadii.Pill).background(theme.inkSecondary.copy(alpha = 0.4f)))
                PlaceDossierContent(
                    dossier = instantDossier(target).copy(
                        address = "Nádražní 1, Praha 5",
                        tags = mapOf("amenity" to "pharmacy", "name" to "Lékárna U Anděla", "opening_hours" to "Mo-Su 08:00-22:00"),
                        rawHours = "Mo-Su 08:00-22:00",
                        openStatus = OpeningHours.Status.Open(22 * 60),
                        pending = emptySet(),
                    ),
                    hereNow = here,
                    accentColor = theme.primary,
                    briefing = null,
                    onBrief = {}, onBriefRetry = {}, onRoute = {}, onSave = {}, onShare = {},
                    onOpenExternal = {}, onOpenUrl = {}, onDial = {}, onShowLine = {}, onRetarget = {},
                    target = target,
                    origin = 50.0860 to 14.4190,
                )
            }
        }
    }

    private fun shot(name: String, spec: PhantomThemeSpec, content: @Composable () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        MapText.bind(activity)
        activity.setContent {
            PhantomTheme(spec = spec) {
                CompositionLocalProvider(LocalMotionScale provides 0f) { content() }
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
        assertTrue("$name: аркуш не намалювався", inked > 50)
        controller.pause().stop().destroy()
    }
}
