package local.phantom.companion.core.design.kit

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LookKitTest {

    @Test fun rowsAre56And64And72() {
        assertEquals(56.dp, LookKit.rowHeight(ListRowHeight.One))
        assertEquals(64.dp, LookKit.rowHeight(ListRowHeight.Two))
        assertEquals(72.dp, LookKit.rowHeight(ListRowHeight.Chat))
    }

    @Test fun buttonsAre40And48() {
        assertEquals(40.dp, LookKit.buttonHeight(large = false))
        assertEquals(48.dp, LookKit.buttonHeight(large = true))
    }

    @Test fun chipHandleHeaderAndCloseKeepTheirSizes() {
        assertEquals(24.dp, LookKit.ChipHeight)
        assertEquals(32.dp, LookKit.HandleWidth)
        assertEquals(4.dp, LookKit.HandleHeight)
        assertEquals(44.dp, LookKit.SheetHeader)
        assertEquals(40.dp, LookKit.CloseSize)
    }

    @Test fun outgoingAmountsUseTheRealMinus() {
        val out = LookAmount.signed("1.00", AmountSign.Out)
        assertEquals("−1.00", out)
        assertFalse("ASCII hyphen in a signed amount: $out", out.contains('-'))
    }

    @Test fun incomingAmountsCarryAPlusAndNeutralOnesNothing() {
        assertEquals("+20.00", LookAmount.signed("20.00", AmountSign.In))
        assertEquals("5.00", LookAmount.signed("5.00", AmountSign.None))
        assertTrue(LookAmount.signed("\$2.00", AmountSign.Out).startsWith("−\$"))
    }
}
