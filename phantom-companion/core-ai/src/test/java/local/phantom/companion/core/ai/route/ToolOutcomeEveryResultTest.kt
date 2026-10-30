package local.phantom.companion.core.ai.route

import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.AIIntent.Text.Role
import local.phantom.companion.core.ai.AIIntent.Text.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Огляд 14.10, C8: без розуму після виклику людина бачить КОЖЕН результат останнього ходу, а не лише останній. */
class ToolOutcomeEveryResultTest {

    @Test
    fun `every result of the last calls is shown, in order`() {
        val calls = listOf(AIIntent.Text.ToolCall("c1", "weather.now", "{}"), AIIntent.Text.ToolCall("c2", "geo.nearby", "{}"))
        val intent = AIIntent.Text(
            prompt = "",
            history = listOf(
                Turn(Role.USER, "яка погода і де аптека поруч?"),
                Turn(Role.ASSISTANT, "", toolCalls = listOf(AIIntent.Text.ToolCall("c0", "location.current", "{}"))),
                Turn(Role.TOOL, """{"address":"Площа Ринок"}""", toolCallId = "c0"),
                Turn(Role.ASSISTANT, "", toolCalls = calls),
                Turn(Role.TOOL, """{"temperature":"+7"}""", toolCallId = "c1"),
                Turn(Role.TOOL, """{"name":"Аптека 9"}""", toolCallId = "c2"),
            ),
        )

        val shown = OnDeviceToolRules().toolOutcome(intent).orEmpty()

        assertEquals("• Температура: +7\n\n• Назва: Аптека 9", shown)
        assertTrue("результат попереднього ходу не повторюється", "Площа Ринок" !in shown)
    }
}
