package local.phantom.companion.feature.stream.ui.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import local.phantom.companion.core.design.theme.AmberNight
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.core.design.theme.PhantomThemeSpec
import local.phantom.companion.core.design.theme.SunriseWarm
import local.phantom.companion.feature.stream.ui.map.MapSearchSheet
import local.phantom.companion.feature.stream.ui.vault.VaultScreen
import local.phantom.companion.feature.stream.vault.UnlockAttempt
import local.phantom.companion.feature.stream.vault.VaultUiState
import local.phantom.companion.feature.stream.voice.rememberRecordAudioPermissionGate
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * Кадри екранів, яких міст рідної теми НЕ правив, — щоб побачити, що вони
 * перейшли на нову ідентичність лише через тему: Профіль, Сховок, аркуш пошуку
 * мапи і стоковий M3 AlertDialog (сам по собі й той, що в дозволі мікрофона).
 *
 * Обгортка — та сама, що в MainActivity: `PhantomTheme { Surface(fillMaxSize) }`,
 * тобто ґрунт бере `colorScheme.surface`, як на телефоні.
 *
 * Кадри лягають у build/theme-bridge-look/; тест падає, лише якщо екран не
 * намалювався. Порівняння «до/після» — очима, не пікселями.
 *
 * Власний Recomposer і власний годинник кадрів: глобальний диспетчер Compose
 * запамʼятовує Choreographer ПЕРШОГО тесту в JVM, і в кожному наступному
 * ефекти й перекомпозиція стоять (див. коментар в MapLookShotsTest). Пакет
 * `ui.theme` іде після `ui.map` — MapLookShotsTest лишається першим, кому
 * той диспетчер належить, а цей клас від нього не залежить.
 */
@RunWith(RobolectricTestRunner::class)
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "uk-w411dp-h891dp-xhdpi")
class ThemeBridgeLookShotsTest {

    @Test fun searchNight() = shot("search-night", AmberNight) { search() }
    @Test fun searchDay() = shot("search-day", SunriseWarm) { search() }


    @Test fun vaultLockedNight() = shot("vault-locked-night", AmberNight) { vault(VaultUiState.Locked(UnlockAttempt.Idle)) }
    @Test fun vaultLockedDay() = shot("vault-locked-day", SunriseWarm) { vault(VaultUiState.Locked(UnlockAttempt.Idle)) }
    @Test fun vaultEmptyNight() = shot("vault-empty-night", AmberNight) {
        vault(VaultUiState.Open(groups = emptyList(), total = 0, unopenable = 0, trouble = null))
    }

    @Test fun stockDialogNight() = shot("stock-dialog-night", AmberNight, dialog = true) { stockDialog() }
    @Test fun stockDialogDay() = shot("stock-dialog-day", SunriseWarm, dialog = true) { stockDialog() }

    @Test fun micGateNight() = shot("mic-gate-night", AmberNight, dialog = true) { micGate() }
    @Test fun micGateDay() = shot("mic-gate-day", SunriseWarm, dialog = true) { micGate() }

    /** Те, на що мігрують стокові діалоги: PhantomDialog із core-design (після мосту). */
    @Test fun phantomDialogNight() = shot("phantom-dialog-night", AmberNight, dialog = true) { phantomDialog() }
    @Test fun phantomDialogDay() = shot("phantom-dialog-day", SunriseWarm, dialog = true) { phantomDialog() }

    @Composable
    private fun vault(state: VaultUiState) = VaultScreen(
        state = state,
        onBack = {},
        onUnlockRequested = {},
        onBiometricSuccess = {},
        onUnlockRefused = {},
        onLockSession = {},
        onErase = {},
        onDismissTrouble = {},
        onReread = {},
    )

    /** M3 AlertDialog без жодного перевизначення — те, що успадковують 11 файлів. */
    @Composable
    private fun stockDialog() = AlertDialog(
        onDismissRequest = {},
        title = { Text("Видалити мітку?") },
        text = { Text("«Кавʼярня біля парку» зникне з мапи на цьому телефоні.") },
        confirmButton = { TextButton(onClick = {}) { Text("Видалити") } },
        dismissButton = { TextButton(onClick = {}) { Text("Скасувати") } },
    )

    @Composable
    private fun phantomDialog() = local.phantom.companion.core.design.component.PhantomDialog(
        title = "Видалити мітку?",
        text = "«Кавʼярня біля парку» зникне з мапи на цьому телефоні.",
        confirmLabel = "Видалити",
        dismissLabel = "Скасувати",
        danger = true,
        onConfirm = {},
        onDismiss = {},
    )

    /** Справжній вхід: натиск на мікрофон без дозволу показує діалог-пояснення. */
    @Composable
    private fun micGate() {
        val gate = rememberRecordAudioPermissionGate()
        LaunchedEffect(Unit) { gate.requestThen {} }
    }

    @Composable
    private fun search() = MapSearchSheet(
        query = "",
        onQueryChange = {}, onSubmit = {}, onClose = {},
        inFlight = false,
        submitted = false,
        gap = null,
        hereCountry = null,
        results = emptyList(),
        offlineHits = emptyList(),
        recent = listOf("укриття", "Нова пошта"),
        saved = listOf(
            local.phantom.companion.core.data.poi.PoiEntity(-1, 49.84, 24.02, "Дім", "home", null, emptyMap(), 0L),
            local.phantom.companion.core.data.poi.PoiEntity(-2, 49.83, 24.01, "Кавʼярня біля парку", "custom", null, emptyMap(), 0L),
        ),
        here = 49.8397 to 24.0297,
        alongRoute = false,
        onPickCategory = { _, _ -> }, onSearchHere = { _, _ -> }, onFloodByName = {},
        onResultTap = {}, onOfflineTap = {}, onSavedTap = {}, onAskAi = {}, onPickRecent = {},
        onClearRecent = {}, onSetAnchor = { _, _ -> },
    )

    private fun shot(
        name: String,
        spec: PhantomThemeSpec,
        dialog: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val clock = BroadcastFrameClock()
        val context = Dispatchers.Main + clock
        val recomposer = Recomposer(context)
        val scope = CoroutineScope(context + Job())
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        activity.setContent(parent = recomposer) {
            PhantomTheme(spec = spec) {
                // Стоп-кадр: прильоти стають на місце одразу.
                CompositionLocalProvider(
                    local.phantom.companion.core.design.motion.LocalMotionScale provides 0f,
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) { content() }
                }
            }
        }
        repeat(40) {
            Snapshot.sendApplyNotifications()
            clock.sendFrame(System.nanoTime())
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
        }
        val root = activity.findViewById<android.view.View>(android.R.id.content)
        val full = capture(activity.window)
        val at = IntArray(2).also { root.getLocationInWindow(it) }
        val bitmap = Bitmap.createBitmap(full, at[0], at[1], root.width, root.height)
            .copy(Bitmap.Config.ARGB_8888, true)
        if (dialog) {
            val d = ShadowDialog.getLatestDialog()
            assertTrue("$name: діалог не зʼявився", d != null && d.isShowing)
            val w = d!!.window!!
            val pane = capture(w)
            // Вікно діалогу стоїть посередині екрана; затемнення під ним малює
            // системний менеджер вікон, тому відтворюємо його пеленою теми.
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.argb(150, 5, 6, 10))
            val left = (bitmap.width - pane.width) / 2f
            val top = (bitmap.height - pane.height) / 2f
            canvas.drawBitmap(pane, left, top, null)
        }
        val out = File("build/theme-bridge-look").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        var inked = 0
        for (y in 0 until bitmap.height step 16) for (x in 0 until bitmap.width step 16) {
            if (bitmap.getPixel(x, y) != bitmap.getPixel(0, 0)) inked++
        }
        assertTrue("$name: екран не намалювався", inked > 50)
        if (dialog) ShadowDialog.getLatestDialog()?.dismiss()
        recomposer.cancel()
        scope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        controller.pause().stop().destroy()
    }

    /** PixelCopy малює через HardwareRenderer — інакше шари graphicsLayer не потрапляють у кадр. */
    private fun capture(window: android.view.Window): Bitmap {
        val decor = window.decorView
        val bmp = Bitmap.createBitmap(decor.width.coerceAtLeast(1), decor.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        android.view.PixelCopy.request(window, bmp, {}, android.os.Handler(Looper.getMainLooper()))
        shadowOf(Looper.getMainLooper()).idle()
        return bmp
    }
}
