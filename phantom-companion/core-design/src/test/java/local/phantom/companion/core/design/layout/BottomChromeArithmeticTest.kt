package local.phantom.companion.core.design.layout

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.union
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Арифметика нижнього краю, порахована на справжніх `WindowInsets`.
 *
 * [bottomChromeInsets] не можна викликати тут — `WindowInsets.navigationBars`
 * і `.ime` читаються з композиції. Тому підставлені сталі, а самі `add` і
 * `union` — ті ж функції, що й у продукті: перевіряється дія, а не переказ.
 *
 * Чого цей тест НЕ доводить: що предок, який уже спожив `navigationBars`,
 * віднімає їх від дочірнього `imePadding()` (це ефект дерева модифікаторів —
 * потрібен пристрій). Саме на цьому стоїть аркуш рукостискання.
 */
class BottomChromeArithmeticTest {

    /** 1 dp = 1 px, щоб числа в тесті читались як dp. */
    private val density = Density(1f)

    private val navBar = WindowInsets(bottom = NAV_BAR)
    private val keyboardUp = WindowInsets(bottom = KEYBOARD)
    private val keyboardDown = WindowInsets(bottom = 0.dp)

    private fun bottomOf(reserve: androidx.compose.ui.unit.Dp, ime: WindowInsets): Int =
        navBar.add(WindowInsets(bottom = reserve)).union(ime).getBottom(density)

    @Test
    fun `keyboard down, the column clears the rail`() {
        assertEquals("navBar + island reserve", 48 + 68, bottomOf(ISLAND, keyboardDown))
    }

    /**
     * Та сама мертва смуга, тільки з іншого боку: сума лишила б 134 dp порожнечі
     * між останнім рядком і клавіатурою, яка й так накрила рейку собою.
     */
    @Test
    fun `keyboard up, the union is the keyboard alone and never the sum`() {
        val union = bottomOf(ISLAND, keyboardUp)
        assertEquals("flush above the keyboard", 330, union)
        assertEquals("the sum would have left this much dead", 116, 48 + 68 + 330 - union)
    }

    /**
     * Аркуш ховає острівець, тож його резерв дорівнює нулю — і саме тому
     * PeerHandshakeSheet бере `imePadding()`, а не `bottomChromeInsets()`.
     */
    @Test
    fun `with the island hidden the reserve contributes nothing at either state`() {
        assertEquals(48, bottomOf(0.dp, keyboardDown))
        assertEquals(330, bottomOf(0.dp, keyboardUp))
    }

    /** Превʼю, онбординг, приховані камери: провайдера немає, резерв 0.dp. */
    @Test
    fun `no provider means no reserve and no crash`() {
        assertEquals(48, bottomOf(0.dp, keyboardDown))
    }

    private companion object {
        val NAV_BAR = 48.dp
        val KEYBOARD = 330.dp

        /** Резерв колишнього острівця (8 + 48 + 12); 02.10 острівця немає, а арифметика union лишається. */
        val ISLAND = 68.dp
    }
}
