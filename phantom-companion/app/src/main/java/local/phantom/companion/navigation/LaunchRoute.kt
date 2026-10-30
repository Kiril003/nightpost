package local.phantom.companion.navigation

import android.content.Context
import local.phantom.companion.core.data.profile.Profile

/**
 * Куди веде холодний старт — і що застосунок памʼятає про це з минулого разу.
 *
 * Розвилку Bootstrap вирішують профілі з Room: немає жодного — знайомство,
 * немає активного — вибір профілю, є — домівка. На A52 (debug, 04.10) Room
 * віддавав перший список через 1,9 с після першого кадру: холодний старт
 * базу ще відкриває, а ContainerBoot у ту саму мить будить решту застосунку.
 * Усі ці 1,9 с людина дивилась на знак по центру, хоча в 99 стартах зі ста
 * відповідь та сама, що й минулого разу — домівка.
 *
 * Тепер остання відповідь лежить у `phantom_launch` (той самий файл, що
 * памʼятає знайомство). Якщо минулого разу вийшла домівка — Bootstrap іде
 * туди одразу, з ескізом месенджера на першому кадрі, а Room перевіряє
 * вибір фоном: розійшлось (профілі стерто, профіль вимкнено) — граф веде
 * туди, куди насправді треба. Свіже встановлення й очищені дані мають
 * порожній файл, тож ідуть старим шляхом — чекати Room.
 */
internal object LaunchRoute {
    private const val PREFS = "phantom_launch"
    private const val LAST_WAS_HOME = "last_start_home"

    /** Куди веде старт за списком профілів. */
    fun target(profiles: List<Profile>): String = target(profiles.size, profiles.any { it.isActive })

    /** Та сама розвилка без Room — чиста функція, її й тестуємо. */
    fun target(count: Int, anyActive: Boolean): String = when {
        count == 0 -> Routes.Onboarding.path
        !anyActive -> Routes.Launcher.path
        else -> Routes.Home.path
    }

    fun lastWasHome(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(LAST_WAS_HOME, false)

    fun remember(context: Context, target: String) {
        val home = target == Routes.Home.path
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(LAST_WAS_HOME, false) != home) prefs.edit().putBoolean(LAST_WAS_HOME, home).apply()
    }
}
