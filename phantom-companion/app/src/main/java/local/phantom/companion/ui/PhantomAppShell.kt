package local.phantom.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import local.phantom.companion.PhantomApp
import local.phantom.companion.core.design.theme.LocalOnline
import local.phantom.companion.core.design.tokens.LocalPhantomColors

/**
 * Полотно під усім графом: тло «Ніч» (чи денне) з токенів і знання «чи є
 * мережа» для кожного екрана.
 *
 * До 04.10 тут жив «рушій настрою»: акцент стану, тон емоції з пружиною,
 * рівень мікрофона, «подих», аура голосу й читання сховищ для карток стрічки —
 * шість локалей, які читали лише орб, картки стрічки й лічильник PTT. Ті
 * поверхні пішли разом зі старим образом; локалі лишились без читачів, а
 * рушій далі анімував кольори, яких ніхто не малював. v1.2 не дихає й не
 * змінює барв за настроєм: одне полотно, бурштин лише на дії.
 */
@Composable
fun PhantomAppShell(content: @Composable () -> Unit) {
    val container = remember { PhantomApp.container() }
    val online by container.networkProbe.online.collectAsStateWithLifecycle()

    CompositionLocalProvider(LocalOnline provides online) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LocalPhantomColors.current.canvas),
        ) {
            content()
        }
    }
}
