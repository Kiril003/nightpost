package local.phantom.companion.core.design.component

import android.graphics.Color as AndroidColor
import android.os.Build
import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import local.phantom.companion.core.design.tokens.LocalPhantomColors

/**
 * Системні смуги вікна, у якому живе Material-аркуш (`ModalBottomSheet`).
 *
 * Аркуш відкриває ВЛАСНЕ вікно, і Material вирішує вигляд його смуг за
 * `isSystemInDarkTheme()` — за темою телефона, а не PHANTOM. Нічна тема
 * PHANTOM на світлому телефоні діставала світлі смуги: система під кнопками
 * навігації малювала білу підкладку контрасту, і внизу темного аркуша стояла
 * біла смуга (A52, 02.10). Тут смуги беруться з теми PHANTOM, а підкладку
 * знято: під кнопками — сам аркуш, він і так іде до низу екрана.
 *
 * Викликається з ВМІСТУ аркуша: саме там [LocalView] належить вікну аркуша.
 */
@Composable
fun SheetWindowBars() {
    val view = LocalView.current
    val dark = LocalPhantomColors.current.isDark
    SideEffect {
        val window = sheetWindow(view) ?: return@SideEffect
        window.navigationBarColor = AndroidColor.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightNavigationBars = !dark
            isAppearanceLightStatusBars = !dark
        }
    }
}

private fun sheetWindow(view: View): Window? {
    var parent = view.parent
    while (parent != null) {
        if (parent is DialogWindowProvider) return parent.window
        parent = parent.parent
    }
    return null
}
