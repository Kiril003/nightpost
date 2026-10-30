package local.phantom.companion.core.solana

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "uk")
class WalletReturnTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `after reinstall with no wallet the card asks for the words`() {
        assertEquals(WalletReturn.State.ABSENT, WalletReturn.state("A", null))
        assertEquals("Відновіть гаманець", ctx.getString(R.string.sol_return_title))
        assertEquals("AbCd…WxYz · додайте слова відновлення", ctx.getString(R.string.sol_return_absent, "AbCd…WxYz"))
    }

    @Test
    fun `a different new wallet is named as such and can be kept`() {
        assertEquals(WalletReturn.State.REPLACED, WalletReturn.state("A", "B"))
        assertEquals("AbCd…WxYz · замінено новим гаманцем", ctx.getString(R.string.sol_return_replaced, "AbCd…WxYz"))
    }

    @Test
    fun `the same wallet back or nothing expected shows no card`() {
        assertEquals(WalletReturn.State.NONE, WalletReturn.state("A", "A"))
        assertEquals(WalletReturn.State.NONE, WalletReturn.state(null, "B"))
        assertEquals(WalletReturn.State.NONE, WalletReturn.state(null, null))
    }
}
