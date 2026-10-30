package local.phantom.companion.ui.settings

import local.phantom.companion.R
import local.phantom.companion.data.erase.EraseEverything
import local.phantom.companion.data.erase.EraseStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CancelFailureTextTest {

    @Test
    fun `failed cancel names the reason, success shows none`() {
        assertEquals(
            R.string.delete_pending_offline,
            cancelFailureText(EraseEverything.Outcome.Failed(EraseStep.PENDING, EraseEverything.EraseServer.Result.Unreachable)),
        )
        assertEquals(
            R.string.delete_pending_failed,
            cancelFailureText(EraseEverything.Outcome.Failed(EraseStep.PENDING, EraseEverything.EraseServer.Result.Refused(500))),
        )
        assertNull(cancelFailureText(EraseEverything.Outcome.Restored))
        assertNull(cancelFailureText(EraseEverything.Outcome.NotAuthorised))
    }
}
