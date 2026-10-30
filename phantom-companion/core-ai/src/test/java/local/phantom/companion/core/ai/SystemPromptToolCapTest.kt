package local.phantom.companion.core.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Огляд 14.10, C3: припис каже моделі ту саму стелю ланцюга, на якій розмова його зупиняє. */
class SystemPromptToolCapTest {

    @Test
    fun `the prompt states the cap the dialogue enforces`() {
        val prompt = SystemPrompts.DEFAULT_PHANTOM
        assertTrue("До ${SystemPrompts.MAX_TOOL_ROUNDS} послідовних звертань до інструментів" in prompt)
        assertFalse("24 послідовних" in prompt)
    }
}
