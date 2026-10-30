package local.phantom.companion.feature.stream.ui.map.peers

import android.graphics.Bitmap
import android.graphics.Canvas
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
import androidx.compose.ui.graphics.Color
import java.io.File
import local.phantom.companion.core.data.peers.MyWorldSummary
import local.phantom.companion.core.data.peers.PeerPrecision
import local.phantom.companion.core.data.peers.PeerSeeRow
import local.phantom.companion.core.data.peers.PeerSelfShow
import local.phantom.companion.core.design.theme.AmberNight
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomRadii
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.core.design.theme.PhantomThemeSpec
import local.phantom.companion.core.design.theme.SunriseWarm
import org.junit.Assert.assertTrue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Кадри «людей на мапі» на екрані A52 — у build/people-map/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "uk-w411dp-h891dp-xhdpi")
class PeopleMapShotsTest {

    private val now = 1_790_000_000_000L
    private val here = 49.8397 to 24.0297

    private fun peer(name: String, id: String, precision: String?, near: String?, minutes: Long = 3, motion: String? = null, spot: String? = null, low: Boolean = false) =
        TappedPeer(
            nodeId = "where:$id", threadId = id, name = name, lat = 49.8419, lon = 24.0315,
            atMs = now - minutes * 60_000L, verified = true, fresh = minutes < 15,
            precision = precision, near = near, motion = motion, spot = spot, lowBattery = low,
        )

    private val oksana get() = peer("Оксана", "0123456789abcdef", "EXACT", "NEAR", motion = "FOOT", spot = "HOME", low = true)
    private val taras get() = peer("Тарас", "fedcba9876543210", "QUARTER", "SAME", motion = "VEHICLE")
    private val mama get() = peer("Мама", "a1b2c3d4e5f60718", "DISTRICT", "FAR", minutes = 70)

    @Test fun cardNight() = shot("card-night", AmberNight) {
        Sheet { PeerWhereCard(oksana, LocalPhantomTheme.current.primary, {}, {}, now, onRoute = {}, origin = here, onWave = {}, onCallHere = {}, onMeetMiddle = {}) }
    }

    @Test fun cardDay() = shot("card-day", SunriseWarm) {
        Sheet { PeerWhereCard(taras, LocalPhantomTheme.current.primary, {}, {}, now, origin = here, onWave = {}, onCallHere = {}, onMeetMiddle = {}) }
    }

    @Test fun cardStale() = shot("card-stale-night", AmberNight) {
        Sheet { PeerWhereCard(mama, LocalPhantomTheme.current.primary, {}, {}, now, onWave = {}) }
    }

    private val rows = listOf(
        PeerSeeRow("0123456789abcdef", "Оксана", PeerPrecision.EXACT, true, hiddenUntilMs = now + 3 * 3_600_000L),
        PeerSeeRow("fedcba9876543210", "Тарас", PeerPrecision.QUARTER, true),
        PeerSeeRow("a1b2c3d4e5f60718", "Мама", null, true),
        PeerSeeRow("1111222233334444", "Андрій з роботи", null, false),
    )

    @Test fun peopleNight() = shot("people-night", AmberNight) {
        Sheet {
            WhoSeesMeSheet(
                rows, LocalPhantomTheme.current.primary, { _, _ -> }, {},
                self = PeerSelfShow(lowBattery = true),
                near = listOf(taras, oksana),
                world = MyWorldSummary(quarters = 214, cities = 3),
                nowMs = now,
            )
        }
    }

    @Test fun peopleHiddenDay() = shot("people-hidden-day", SunriseWarm) {
        Sheet {
            WhoSeesMeSheet(rows, LocalPhantomTheme.current.primary, { _, _ -> }, {}, self = PeerSelfShow(hiddenAllUntilMs = now + 3_600_000L), nowMs = now)
        }
    }

    @Test fun groupNight() = shot("group-night", AmberNight) {
        Sheet { PeerGroupSheet(listOf(oksana, taras, mama.copy(atMs = now)), now) {} }
    }

    @Test fun worldDay() = shot("world-day", SunriseWarm) {
        Sheet {
            MyWorldSheet(
                MyWorldSummary(
                    quarters = 214, cities = 3,
                    frequent = listOf(
                        MyWorldSummary.Frequent("Дім", 0.0, 0.0, 41 * 3_600_000L),
                        MyWorldSummary.Frequent("Робота", 0.0, 0.0, 23 * 3_600_000L),
                        MyWorldSummary.Frequent(null, 0.0, 0.0, 45 * 60_000L),
                    ),
                ),
                onMap = false, onShowOnMap = {}, onForget = {},
            )
        }
    }

    @Test fun worldEmptyNight() = shot("world-empty-night", AmberNight) {
        Sheet { MyWorldSheet(MyWorldSummary(), onMap = false, onShowOnMap = {}, onForget = {}) }
    }

    @Test fun meetNight() = shot("meet-night", AmberNight) {
        Sheet {
            MeetSheet(
                listOf(
                    MeetRow("0123456789abcdef", "Оксана", MeetState.THERE, null, oksana),
                    MeetRow("fedcba9876543210", "Тарас", MeetState.ON_WAY, 2_300.0, taras),
                    MeetRow("a1b2c3d4e5f60718", "Мама", MeetState.FAR, null, mama),
                    MeetRow("1111222233334444", "Андрій", MeetState.UNSEEN, null, null),
                ),
                now, {}, {},
            )
        }
    }

    @Test fun meetPickDay() = shot("meet-pick-day", SunriseWarm) {
        Sheet { MeetPickSheet(rows.map { it.peerId to it.name }, { true }) { _, _ -> } }
    }

    @Test fun circleNight() = shot("circle-night", AmberNight) {
        Sheet {
            androidx.compose.foundation.layout.Column(Modifier.padding(16.dp)) {
                SafetyCircleBlock(
                    listOf(
                        CircleRow(taras, CircleSky.ALERT, "Київська"),
                        CircleRow(oksana, CircleSky.QUIET, "Львівська"),
                        CircleRow(mama, CircleSky.SILENT, "Одеська"),
                    ),
                    listeners = 2, onOpen = {}, onSafe = {},
                )
            }
        }
    }

    /** Обличчя на землі мапи, як їх малює стиль: обидві полярності, усі значки й група. */
    @Test fun marks() {
        val density = 2.75f
        listOf(true to 0xFF1D1A16.toInt(), false to 0xFFEAE3D2.toInt()).forEach { (night, ground) ->
            val tiles = listOf(
                PeerMarks.person(5, "О", null, "foot", night, density),
                PeerMarks.person(1, "Т", null, "car", night, density),
                PeerMarks.person(7, "М", null, "low", night, density),
                PeerMarks.person(3, "А", null, "", night, density),
                PeerMarks.group(5 to 1, 3, night, density),
            )
            val w = tiles.sumOf { it.width } + 24 * (tiles.size + 1)
            val h = tiles.maxOf { it.height } + 48
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawColor(ground)
            var x = 24f
            tiles.forEach {
                c.drawBitmap(it, x, 24f, null)
                x += it.width + 24
            }
            val dir = File("build/people-map").apply { mkdirs() }
            File(dir, "marks-${if (night) "night" else "day"}.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(tiles.all { it.getPixel(it.width / 2, it.height / 2) != 0 })
            // Правий край значка «пішки» — ще в кадрі: значок не обрізано.
            val o = tiles[0]
            val at = o.width / 2f + 20f * density * 0.72f
            val edge = (at + 7.5f * density).toInt()
            assertTrue("значок обрізано", edge < o.width && o.getPixel(edge, at.toInt()) != 0)
        }
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
                val theme = LocalPhantomTheme.current
                androidx.compose.runtime.CompositionLocalProvider(local.phantom.companion.core.design.motion.LocalMotionScale provides 0f) {
                    Box(Modifier.fillMaxSize().background(if (theme.isDark) Color(0xFF1D1A16) else Color(0xFFEAE3D2))) { content() }
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
        val out = File("build/people-map").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        var inked = 0
        for (y in 0 until bitmap.height step 16) for (x in 0 until bitmap.width step 16) {
            if (bitmap.getPixel(x, y) != bitmap.getPixel(0, 0)) inked++
        }
        assertTrue("$name: аркуш не намалювався", inked > 50)
        controller.pause().stop().destroy()
    }
}
