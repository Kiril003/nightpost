package local.phantom.companion.core.ai

import org.junit.Assert.assertTrue
import org.junit.Test

/** Українською помічник звертається до людини на «ви», як увесь застосунок; «ти» заборонено. */
class SystemPromptAddressTest {

    @Test
    fun `every native prompt tells the model to address the user as ви`() {
        for ((which, text) in listOf(
            "DEFAULT_PHANTOM" to SystemPrompts.DEFAULT_PHANTOM,
            "LOCAL_COMPACT" to SystemPrompts.LOCAL_COMPACT,
        )) {
            assertTrue("$which без правила «ви»", "на «ви»" in text)
            assertTrue("$which не забороняє «ти»", "ніколи «ти»" in text)
            assertTrue("$which ще велить «ти»", "на «ти»" !in text)
        }
    }
}
