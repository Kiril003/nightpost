package local.phantom.companion.core.design.layout

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import local.phantom.companion.core.design.ComposeRig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Острівець зникає» — це твердження про композицію, а не про прапорець.
 *
 * Тримач береться в [HideBottomChrome] через `DisposableEffect`, тож він живе
 * рівно стільки, скільки живе поверхня, яка його взяла. Тут це й перевіряється:
 * не «викликали acquire», а «поверхні не стало — і рейка повернулась сама».
 */
class BottomChromeStateTest {

    @Test
    fun `the rail stands down while the surface is composed and returns when it is gone`() {
        val rig = ComposeRig()
        val chrome = BottomChromeState()
        var open by mutableStateOf(false)

        rig.setContent {
            CompositionLocalProvider(LocalBottomChrome provides chrome) {
                if (open) HideBottomChrome()
            }
        }
        assertFalse("нічого не відкрито — рейка на місці", chrome.railHidden)

        open = true
        rig.settle(0L)
        assertTrue("поверхня в композиції — рейки бути не має", chrome.railHidden)

        open = false
        rig.settle(16_000_000L)
        assertFalse(
            "поверхні не стало, а рейка не повернулась — тримач протік",
            chrome.railHidden,
        )
        rig.dispose()
    }

    @Test
    fun `two surfaces stacked keep the rail down until the last one goes`() {
        val rig = ComposeRig()
        val chrome = BottomChromeState()
        var first by mutableStateOf(true)
        var second by mutableStateOf(true)

        rig.setContent {
            CompositionLocalProvider(LocalBottomChrome provides chrome) {
                if (first) HideBottomChrome()
                if (second) HideBottomChrome()
            }
        }
        assertTrue(chrome.railHidden)

        second = false
        rig.settle(0L)
        assertTrue(
            "закрився верхній аркуш — нижній ще стоїть, рейка не сміє повернутись",
            chrome.railHidden,
        )

        first = false
        rig.settle(16_000_000L)
        assertFalse("пішли обидва — рейка повертається", chrome.railHidden)
        rig.dispose()
    }

    @Test
    fun `hiding the rail does not by itself collapse the published reserve`() {
        val rig = ComposeRig()
        val chrome = BottomChromeState()

        rig.setContent {
            CompositionLocalProvider(LocalBottomChrome provides chrome) {
                HideBottomChrome()
            }
        }

        assertTrue(chrome.railHidden)
        assertFalse(
            "модальний аркуш ховає рейку, але світ за скримом не сміє " +
                "перебудуватись — резерв лишається опублікованим",
            chrome.reserveCollapsed,
        )
        rig.dispose()
    }

    @Test
    fun `a surface may ask for the freed band explicitly`() {
        val rig = ComposeRig()
        val chrome = BottomChromeState()
        var open by mutableStateOf(true)

        rig.setContent {
            CompositionLocalProvider(LocalBottomChrome provides chrome) {
                if (open) HideBottomChrome(collapseReserve = true)
            }
        }
        assertTrue(chrome.reserveCollapsed)

        open = false
        rig.settle(0L)
        assertFalse("резерв мусить повернутись разом із рейкою", chrome.reserveCollapsed)
        rig.dispose()
    }
}
