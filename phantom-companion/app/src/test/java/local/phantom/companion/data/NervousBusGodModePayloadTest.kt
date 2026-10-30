package local.phantom.companion.data

import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.feature.godmode.engine.GodModeEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Нервова шина тримає останні 64 події до кінця життя процесу, а споживає з
 * неї хтось лише «proactive_card». Тож усе, що сюди кладеться, — це вміст, який
 * ніхто не читає, але який лежить у памʼяті відкритим текстом.
 */
class NervousBusGodModePayloadTest {

    private val screenText =
        "Ольга: буду о шостій. Баланс картки 12 400 грн. Пароль від вайфаю rivne2019"

    private val burstText = "Ганна: везу документи"

    @Test
    fun `the screen dump does not reach the replay buffer`() {
        val payload = godModeNervousPayload(
            GodModeEvent.ScreenSnapshot("com.bank.app", screenText),
        )

        assertFalse(payload.toString().contains("12 400"))
        assertFalse(payload.toString().contains("rivne2019"))
        assertNull(payload["textContent"])
    }

    @Test
    fun `the screen dump still counts as an event that happened`() {
        val payload = godModeNervousPayload(
            GodModeEvent.ScreenSnapshot("com.bank.app", screenText),
        )

        assertEquals("com.bank.app", payload["pkg"]?.jsonPrimitive?.content)
        assertEquals(screenText.length.toString(), payload["textChars"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the body of a foreign notification does not reach the replay buffer`() {
        val payload = godModeNervousPayload(
            GodModeEvent.Notification("com.viber.voip", "Ганна", "везу документи на Січових"),
        )

        assertFalse(payload.toString().contains("Січових"))
        assertNull(payload["text"])
        assertEquals("Ганна", payload["redactedTitle"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a code in a notification title does not reach the replay buffer`() {
        val payload = godModeNervousPayload(
            GodModeEvent.Notification("com.bank.app", "Код 4821", "підтвердіть вхід"),
        )

        assertFalse(payload.toString().contains("4821"))
    }

    @Test
    fun `a burst carries its size, not the messages it merged`() {
        val payload = godModeNervousPayload(
            GodModeEvent.NotificationBurst("com.viber.voip", burstText),
        )

        assertFalse(payload.toString().contains("документи"))
        assertEquals(burstText.length.toString(), payload["textChars"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a screen change still names the app it changed to`() {
        val payload = godModeNervousPayload(
            GodModeEvent.ScreenChanged("com.bank.app", "MainActivity"),
        )

        assertEquals("MainActivity", payload["className"]?.jsonPrimitive?.content)
    }
}
