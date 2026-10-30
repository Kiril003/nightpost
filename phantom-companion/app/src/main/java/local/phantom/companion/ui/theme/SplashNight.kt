package local.phantom.companion.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import local.phantom.companion.core.design.theme.THEME_PREFS_FILE
import local.phantom.companion.core.design.theme.ThemeMode

/**
 * Системна заставка Android 12+ — у тій палітрі, у якій застосунок закрився.
 *
 * Заставку малює система ще до коду застосунку, із ресурсів values/values-night
 * за нічним режимом. Living іде за сонцем, а не за темою телефона: увечері на
 * світлому телефоні застосунок чорний, а заставка була кремовою — і кожен
 * холодний старт починався з ~3 с світлого екрана, що потім чорнів (A52,
 * 04.10: 3,2 с кадрів яскравістю 153–225 перед чорнилом, у Living і в Ночі).
 *
 * `UiModeManager.setApplicationNightMode` (API 31) задає нічний режим саме
 * цього застосунку, і система бере його для заставки наступного старту.
 * Тримаємо його рівним розвʼязаній полярності: Living/Light/Dark — так чи ні,
 * System — «як телефон» (тоді `isSystemInDarkTheme` у MainActivity і далі
 * читає тему телефона, а не власну). MainActivity оголошує `uiMode` у
 * configChanges, тож зміна режиму не перестворює активність.
 *
 * Межа чесна: між закриттям і наступним стартом сонце могло перейти межу —
 * тоді заставка (коротка, до коду застосунку) у минулій фазі, а перший кадр
 * застосунку вже в новій.
 */
internal object SplashNight {
    private const val KEY = "app_night_mode_set"

    /** Режим `UiModeManager.MODE_NIGHT_*` для заставки за вибором і розвʼязаною полярністю. */
    fun modeFor(mode: ThemeMode, resolvedDark: Boolean): Int = when (mode) {
        ThemeMode.System -> UiModeManager.MODE_NIGHT_AUTO
        else -> if (resolvedDark) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
    }

    fun sync(context: Context, mode: ThemeMode, resolvedDark: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val want = modeFor(mode, resolvedDark)
        val prefs = context.getSharedPreferences(THEME_PREFS_FILE, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY, Int.MIN_VALUE) == want) return
        val ui = context.getSystemService(UiModeManager::class.java) ?: return
        runCatching { ui.setApplicationNightMode(want) }
            .onSuccess { prefs.edit().putInt(KEY, want).apply() }
    }
}
