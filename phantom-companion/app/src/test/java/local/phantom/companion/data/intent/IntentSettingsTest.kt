package local.phantom.companion.data.intent

import local.phantom.companion.data.PeerLogSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentSettingsTest {

    private class Mem : PeerLogSlot {
        val map = mutableMapOf<String, String>()
        override fun read(key: String) = map[key]
        override fun write(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    private val slot = Mem()
    private val settings = IntentSettings(slot)

    @Test fun `без номера шлюзу SMS-смуги немає, навіть коли збірка вміє SMS`() {
        assertFalse(settings.policy(smsCapable = true).available)
        settings.gatewayNumber = "+380 67 123 45 67"
        assertTrue(settings.policy(smsCapable = true).available)
        assertFalse(settings.policy(smsCapable = false).available)
    }

    @Test fun `номер нормалізується, а сміття не стає номером`() {
        settings.gatewayNumber = " +380 (67) 123-45-67 "
        assertEquals("+380671234567", settings.gatewayNumber)
        settings.gatewayNumber = "12345"
        assertEquals("", settings.gatewayNumber)
        assertFalse(slot.map.containsKey("gateway"))
    }

    @Test fun `шлюз упізнається за останніми девʼятьма цифрами, чужий — ні`() {
        settings.gatewayNumber = "+380671234567"
        assertTrue(settings.isGateway("0671234567"))
        assertTrue(settings.isGateway("+380671234567"))
        assertFalse(settings.isGateway("+380671234568"))
        assertFalse(settings.isGateway("Kyivstar"))
        assertFalse(settings.isGateway(null))
    }

    @Test fun `особисте лише з дозволом, Тиха людина закриває все`() {
        settings.gatewayNumber = "+380671234567"
        assertFalse(settings.policy(true).allows(2))
        assertTrue(settings.policy(true).allows(3))
        settings.personalAllowed = true
        assertTrue(settings.policy(true).allows(2))
        settings.quiet = true
        assertFalse(settings.policy(true).allows(3))
    }

    @Test fun `список номерів шлюзу — лише справжні номери, без повторів`() {
        assertFalse(settings.homeGateway)
        settings.homeGateway = true
        settings.allowed = listOf("+380 67 123 45 67", "сміття", "0671234567", "+380671234567", "")
        assertEquals(listOf("+380671234567", "0671234567"), settings.allowed)
        assertTrue(settings.homeGateway)
        settings.allowed = emptyList()
        assertEquals(emptyList<String>(), settings.allowed)
    }
}
