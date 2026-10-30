package local.phantom.companion.data.applock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockTest {

    private class Mem(var v: String? = null) : AppLock.Slot {
        override fun read() = v
        override fun write(value: String) { v = value }
    }

    private var now = 1_790_000_000_000L
    private fun lock(slot: Mem = Mem()) = AppLock(slot, { now })

    @Test fun `без коду замка немає і сповіщення з текстом`() {
        val l = lock()
        assertFalse(l.enabled)
        assertFalse(l.locked.value)
        assertFalse(l.hidesNotificationContent())
        l.onBackground()
        now += 3_600_000
        l.onForeground()
        assertFalse(l.locked.value)
    }

    @Test fun `T6 - код вмикає замок, але текст сповіщень ховає лише перемикач`() {
        val l = lock()
        assertFalse("три цифри — не код", l.setPasscode("123"))
        assertFalse("літери — не код", l.setPasscode("12a4"))
        assertTrue(l.setPasscode("2580"))
        assertTrue(l.enabled)
        l.setHideNotificationText(false)
        assertFalse(l.hidesNotificationContent())
        l.setHideNotificationText(true)
        assertTrue(l.hidesNotificationContent())
    }

    @Test fun `T6 - старий замок без вибору лишається схованим, явне «ні» показує текст і після перезапуску`() {
        val slot = Mem()
        val l = lock(slot)
        assertTrue(l.setPasscode("2580"))
        assertTrue("ключа не писали — як до оновлення", l.hidesNotificationContent())
        l.setHideNotificationText(false)
        assertFalse(lock(slot).hidesNotificationContent())
    }

    @Test fun `ховати текст сповіщень можна і без замка`() {
        val l = lock()
        l.setHideNotificationText(true)
        assertTrue(l.hidesNotificationContent())
    }

    @Test fun `код не лежить відкрито, і після перезапуску замок зачинений`() {
        val slot = Mem()
        lock(slot).setPasscode("2580")
        assertFalse("код лежить відкритим текстом", slot.v!!.contains("2580"))
        val again = lock(slot)
        assertTrue("процес з нуля — зачинено", again.locked.value)
        assertEquals(AppLock.Attempt.Wrong, again.unlock("0000"))
        assertTrue(again.locked.value)
        assertEquals(AppLock.Attempt.Opened, again.unlock("2580"))
        assertFalse(again.locked.value)
    }

    @Test fun `автозамок — одразу або через N хвилин після виходу, не раніше`() {
        val l = lock()
        l.setPasscode("2580")
        l.onBackground()
        assertTrue("типово — одразу", l.locked.value)
        l.unlock("2580")

        l.setAutoLock(5 * 60_000L)
        l.onBackground()
        now += 4 * 60_000L
        l.onForeground()
        assertFalse("4 хв < 5 хв", l.locked.value)
        l.onBackground()
        now += 5 * 60_000L
        l.onForeground()
        assertTrue(l.locked.value)

        l.setAutoLock(7_000L)
        assertEquals("вигаданий строк не приймається", 5 * 60_000L, l.settings.value.autoLockMs)
    }

    @Test fun `пʼять промахів — пауза, що росте, а правильний код у паузу не відчиняє`() {
        val l = lock()
        l.setPasscode("2580")
        l.lockNow()
        repeat(AppLock.FREE_FAILURES - 1) { assertEquals(AppLock.Attempt.Wrong, l.unlock("1111")) }
        val first = l.unlock("1111") as AppLock.Attempt.Wait
        assertEquals(now + AppLock.PAUSE_MS, first.untilMs)
        assertTrue("у паузу й правильний код не рахується", l.unlock("2580") is AppLock.Attempt.Wait)
        assertTrue(l.locked.value)
        now = first.untilMs
        val second = l.unlock("1111") as AppLock.Attempt.Wait
        assertEquals(now + 2 * AppLock.PAUSE_MS, second.untilMs)
        now = second.untilMs
        assertEquals(AppLock.Attempt.Opened, l.unlock("2580"))
        assertEquals(0, l.settings.value.failures)
    }

    @Test fun `відбиток відчиняє лише коли дозволений, вимкнути — лише з кодом`() {
        val l = lock()
        l.setPasscode("2580")
        l.lockNow()
        assertFalse(l.unlockByBiometric())
        l.setBiometric(true)
        assertTrue(l.unlockByBiometric())
        assertFalse(l.disable("0000"))
        assertTrue(l.enabled)
        assertTrue(l.disable("2580"))
        assertFalse(l.enabled)
        assertFalse(l.settings.value.biometric)
    }
}
