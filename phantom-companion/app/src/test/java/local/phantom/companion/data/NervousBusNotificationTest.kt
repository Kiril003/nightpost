package local.phantom.companion.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.feature.godmode.engine.NotificationClassifier
import local.phantom.companion.feature.godmode.engine.NotificationImportant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NervousBusNotificationTest {

    private fun payloadOf(title: String, redactedText: String = "тіло") =
        Json.parseToJsonElement(
            notificationImportantEvent(
                NotificationImportant(
                    packageName = "com.bank.app",
                    redactedTitle = title,
                    redactedText = redactedText,
                    deliveredAtMs = 1_700_000_000_000L,
                ),
                deviceId = "test-device",
            ).payloadJson,
        ).jsonObject

    @Test
    fun `one-time code in the title never reaches the bus`() {
        val title = "Your code is 4821"
        val payload = payloadOf(title)

        assertFalse(payload.toString().contains("4821"))
        assertNull(payload["title"])
    }

    @Test
    fun `bank code in a ukrainian title never reaches the bus`() {
        val title = "Код підтвердження 731209"
        val payload = payloadOf(title)

        assertFalse(payload.toString().contains("731209"))
    }

    @Test
    fun `masked title is the shared rule, not a local copy`() {
        val title = "Your code is 4821"
        val expected = NotificationClassifier.classify(
            packageName = "com.bank.app",
            channelId = null,
            title = title,
            text = "тіло",
        ).redactedTitle

        assertEquals(expected, payloadOf(title)["redactedTitle"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a title with no secret keeps its words`() {
        val title = "Ганна: побачимось завтра"

        assertEquals(title, payloadOf(title)["redactedTitle"]?.jsonPrimitive?.content)
    }
}
