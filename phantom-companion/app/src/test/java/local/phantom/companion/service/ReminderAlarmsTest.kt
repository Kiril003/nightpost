package local.phantom.companion.service

import android.app.AlarmManager
import local.phantom.companion.self.PhantomVisits
import local.phantom.companion.self.VisitActionReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ReminderAlarmsTest {

    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun `scheduleVisit arms exact RTC_WAKEUP allowWhileIdle alarm for VisitActionReceiver and replaces previous slot`() {
        val am = app.getSystemService(AlarmManager::class.java)
        val shadowAm = shadowOf(am)

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(ReminderAlarms.scheduleVisit(app, 1_800_000_000_000L))
        assertNull(shadowAm.nextScheduledAlarm)

        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val t1 = 1_800_000_060_000L
        assertTrue(ReminderAlarms.scheduleVisit(app, t1))

        val first = shadowAm.peekNextScheduledAlarm()
        assertNotNull(first)
        assertEquals(t1, first.triggerAtTime)
        assertEquals(AlarmManager.RTC_WAKEUP, first.type)
        assertTrue(first.allowWhileIdle)
        val intent = shadowOf(first.operation).savedIntent
        assertEquals(VisitActionReceiver::class.java.name, intent.component?.className)
        assertEquals(PhantomVisits.ACTION_DUE, intent.action)

        val t2 = t1 + 120_000L
        assertTrue(ReminderAlarms.scheduleVisit(app, t2))
        assertEquals(1, shadowAm.scheduledAlarms.size)
        assertEquals(t2, shadowAm.peekNextScheduledAlarm().triggerAtTime)

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        while (shadowAm.nextScheduledAlarm != null) Unit
        assertFalse(ReminderAlarms.scheduleVisit(app, t2 + 60_000L))
        assertNull(shadowAm.nextScheduledAlarm)
    }

    @Test
    fun `PhantomVisits arm schedules exact visit alarm`() {
        val am = app.getSystemService(AlarmManager::class.java)
        val shadowAm = shadowOf(am)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        val targetMs = System.currentTimeMillis() + 300_000L
        PhantomVisits.arm(app, targetMs)

        val scheduled = shadowAm.peekNextScheduledAlarm()
        assertNotNull(scheduled)
        assertEquals(targetMs, scheduled.triggerAtTime)
        val intent = shadowOf(scheduled.operation).savedIntent
        assertEquals(VisitActionReceiver::class.java.name, intent.component?.className)
        assertEquals(PhantomVisits.ACTION_DUE, intent.action)
    }
}
