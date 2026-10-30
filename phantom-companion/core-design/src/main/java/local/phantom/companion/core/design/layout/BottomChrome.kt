package local.phantom.companion.core.design.layout

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import local.phantom.companion.core.design.theme.LocalBottomChromeInset

/**
 * Хто зараз просить прибрати нижнє шасі — і чи звільняти при цьому місце.
 *
 * Острівець (`app/ui/PhantomIsland`) малюється поверх усього NavHost. Це
 * навігація, а не системна панель: модальна поверхня, яка сама перекриває
 * екран, мусить мати змогу прибрати його зовсім, а не сподіватись, що всі
 * майбутні екрани памʼятатимуть про його 86 dp.
 *
 * Два біти, і вони НЕ одне й те саме:
 *
 *  - [railHidden] — рейку не компонувати взагалі. Тільки для поверхонь, які
 *    вже мають власний вихід (жест, скрим, тап поза межами).
 *  - [reserveCollapsed] — `LocalBottomChromeInset` віддає 0.dp. Потрібен лише
 *    тоді, коли екран під поверхнею має ПЕРЕбудуватись у звільнену смугу.
 *    Для модального аркуша це шкода, а не користь: світ за скримом видно, і
 *    він смикнеться.
 *
 * Лічильник, а не прапорець: два аркуші можуть стояти один на одному, і
 * закриття верхнього не сміє повернути рейку під нижній.
 */
@Stable
class BottomChromeState {

    private var hideHolds by mutableIntStateOf(0)
    private var collapseHolds by mutableIntStateOf(0)

    val railHidden: Boolean get() = hideHolds > 0
    val reserveCollapsed: Boolean get() = collapseHolds > 0

    internal fun acquire(collapseReserve: Boolean) {
        hideHolds++
        if (collapseReserve) collapseHolds++
    }

    internal fun release(collapseReserve: Boolean) {
        if (hideHolds > 0) hideHolds--
        if (collapseReserve && collapseHolds > 0) collapseHolds--
    }
}

/**
 * Один спільний примірник на процес там, де оболонка його не надала —
 * превʼю та тести читають його безпечно, бо острівця там немає.
 */
val LocalBottomChrome = staticCompositionLocalOf { BottomChromeState() }

/**
 * Прибирає острівець на весь час життя цієї композиції і повертає, коли її
 * не стало. Викликається поверхнею, яка сама перекриває екран.
 *
 * [collapseReserve] лишає `LocalBottomChromeInset` без змін за замовчуванням:
 * поверхня йде поверх світу, що лишився стояти, і той світ не має рухатись.
 */
@Composable
fun HideBottomChrome(collapseReserve: Boolean = false) {
    val chrome = LocalBottomChrome.current
    DisposableEffect(chrome, collapseReserve) {
        chrome.acquire(collapseReserve)
        onDispose { chrome.release(collapseReserve) }
    }
}

/**
 * `max(ime, navigationBars + острівець)` — ніколи не сума.
 *
 * Клавіатура ЗАКРИВАЄ рейку, тож зарезервувати обидві висоти означає лишити
 * мертву смугу заввишки з острівець між полем вводу і клавіатурою. `union`
 * бере поосьовий максимум. Резерв дорівнює 0.dp там, де острівця немає.
 *
 * Ставиться на низ екрана ОКРЕМИМ `windowInsetsPadding`, а не після
 * `systemBarsPadding()`: спожиті предком інсети віднімаються і від фіксованої
 * добавки теж, тож ланцюжок дав би `max(navBar + резерв - navBar, …)`.
 */
@Composable
fun bottomChromeInsets(): WindowInsets =
    WindowInsets.navigationBars
        .add(WindowInsets(bottom = LocalBottomChromeInset.current))
        .union(WindowInsets.ime)
