package local.phantom.companion.feature.stream.ui.map.around

import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import java.io.File
import local.phantom.companion.feature.stream.ui.map.dossier.dossierTarget
import local.phantom.companion.core.data.peers.PeerBox
import local.phantom.companion.core.data.peers.PeerPoint
import local.phantom.companion.core.data.peers.PeerPrecision
import local.phantom.companion.core.data.peers.PeerShare
import local.phantom.companion.core.data.peers.ShareMode
import local.phantom.companion.core.design.theme.AmberNight
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomRadii
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.core.design.theme.PhantomThemeSpec
import local.phantom.companion.core.design.theme.SunriseWarm
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Кадри аркуша «Поруч» на екрані A52 — у build/map-around/. Нейтральна місцевість, не дім власника. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w411dp-h891dp-xhdpi")
class AroundYouShotsTest {
    private val now = 1_790_000_000_000L
    private val me = 50.0 to 14.0

    private fun peer(id: String, name: String, lat: Double, minutes: Long, precision: PeerPrecision, box: PeerBox? = null) =
        PeerPoint(
            nodeId = id, threadId = id, name = name, lat = lat, lon = 14.0, atMs = now - minutes * 60_000L,
            share = PeerShare(ShareMode.WHERE), precision = precision, box = box,
        )

    private val friends get() = aroundFriends(
        listOf(
            peer("a", "Olena", 50.0027, 5, PeerPrecision.EXACT),
            peer("b", "Ana", 50.0100, 1, PeerPrecision.EXACT),
            peer("c", "Taras", 50.0300, 40, PeerPrecision.DISTRICT, PeerBox(50.01, 13.98, 50.05, 14.02)),
        ),
        me, now,
    )

    private val places = AroundPlaces.Shown(
        listOf(
            AroundPlace("Mill Lane Coffee", 50.0022, 14.001, address = "4 Mill Lane", venueId = "0123456789abcdef", rating = AroundRating(4.7, 6)),
            AroundPlace("Pho Viet", 50.0040, 13.998, address = "12 Linden Street", venueId = "fedcba9876543210"),
        ),
        venues = true,
    )

    @Test fun aroundNight() = shot("around-night", AmberNight) {
        AroundYouContent(friends, places, me, now, {}, {})
    }

    @Test fun aroundDay() = shot("around-day", SunriseWarm) {
        AroundYouContent(friends, places, me, now, {}, {})
    }

    @Test fun aroundEmptyNight() = shot("around-empty-night", AmberNight) {
        AroundYouContent(emptyList(), AroundPlaces.Nothing, me, now, {}, {})
    }

    @Test fun aroundNoFixDay() = shot("around-no-fix-day", SunriseWarm) {
        AroundYouContent(emptyList(), null, null, now, {}, {})
    }

    @Composable
    private fun Place() = local.phantom.companion.feature.stream.ui.map.dossier.PlaceDossierContent(
        dossier = null,
        target = places.places.first().copy(category = "food", subcategory = "cafe").dossierTarget(),
        origin = me,
        accentColor = local.phantom.companion.core.design.tokens.LocalPhantomColors.current.accent,
        briefing = null, onBrief = {}, onBriefRetry = {}, onRoute = {}, onSave = {}, onShare = {},
        onOpenExternal = {}, onOpenUrl = {}, onDial = {}, onShowLine = {}, onRetarget = {},
        departureBoard = { _, _, _ -> },
        onMeetHere = {}, onSendToChat = {},
    )

    @Test fun placeNight() = shot("place-night", AmberNight) { Place() }

    @Test fun placeDay() = shot("place-day", SunriseWarm) { Place() }

    @Test fun placeSendNight() = shot("place-send-night", AmberNight) {
        local.phantom.companion.feature.stream.ui.map.dossier.PlaceSendSheet("Mill Lane Coffee", listOf("b" to "Ana", "a" to "Olena")) { _, _ -> }
    }

    @Test fun placeSendEmptyDay() = shot("place-send-empty-day", SunriseWarm) {
        local.phantom.companion.feature.stream.ui.map.dossier.PlaceSendSheet("Mill Lane Coffee", emptyList()) { _, _ -> }
    }

    @Composable
    private fun Sheet(content: @Composable () -> Unit) {
        val theme = LocalPhantomTheme.current
        Box(Modifier.fillMaxSize().background(theme.surfaceDeep.copy(alpha = 0.55f))) {
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(PhantomRadii.SheetTop).background(theme.surfaceBase),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { content() }
        }
    }

    private fun shot(name: String, spec: PhantomThemeSpec, content: @Composable () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent {
            PhantomTheme(spec = spec) {
                androidx.compose.runtime.CompositionLocalProvider(local.phantom.companion.core.design.motion.LocalMotionScale provides 0f) {
                    Sheet(content)
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
        val out = File("build/map-around").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        var inked = 0
        for (y in 0 until bitmap.height step 16) for (x in 0 until bitmap.width step 16) {
            if (bitmap.getPixel(x, y) != bitmap.getPixel(0, 0)) inked++
        }
        assertTrue("$name: аркуш не намалювався", inked > 50)
        controller.pause().stop().destroy()
    }
}
