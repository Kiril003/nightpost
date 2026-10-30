package local.phantom.companion.service

import android.app.AlarmManager
import java.io.File
import local.phantom.companion.data.scheduled.ScheduledLetters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ScheduledLetterAlarmsTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private var disk: String? = null
    private val book = ScheduledLetters(read = { disk }, write = { disk = it })

    @Test
    fun `one exact wake-up alarm on the earliest waiting letter, cancelled when none is left`() {
        val shadowAm = shadowOf(app.getSystemService(AlarmManager::class.java))
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val late = book.add("peer_1", "пізніше", 1_800_000_900_000L, false, 1_800_000_000_000L)!!
        val soon = book.add("peer_1", "раніше", 1_800_000_300_000L, false, 1_800_000_000_000L)!!
        ScheduledLetterAlarms.rearm(app, book, nowMs = 1_800_000_000_000L)
        val alarm = shadowAm.peekNextScheduledAlarm()
        assertEquals(soon.dueAtMs, alarm.triggerAtTime)
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertTrue(alarm.allowWhileIdle)
        val intent = shadowOf(alarm.operation).savedIntent
        assertEquals(ScheduledLetterReceiver::class.java.name, intent.component?.className)
        assertEquals(ScheduledLetterAlarms.ACTION_DUE, intent.action)

        book.remove(soon.id)
        ScheduledLetterAlarms.rearm(app, book, nowMs = 1_800_000_000_000L)
        assertEquals(1, shadowAm.scheduledAlarms.size)
        assertEquals(late.dueAtMs, shadowAm.peekNextScheduledAlarm().triggerAtTime)

        book.remove(late.id)
        ScheduledLetterAlarms.rearm(app, book, nowMs = 1_800_000_000_000L)
        assertNull(shadowAm.peekNextScheduledAlarm())
    }

    @Test
    fun `a letter that fell due while the phone was off goes a second after boot`() {
        val l = book.add("peer_1", "раз", 2_000, false, 1_000)!!
        assertEquals(10_000L + 1_000L, ScheduledLetterAlarms.fireAt(l, 10_000L))
        assertEquals(l.dueAtMs, ScheduledLetterAlarms.fireAt(l, 500L))
    }

    @Test
    fun `the letter rides the composer path with its id and the quiet bit`() {
        val l = ScheduledLetters.Letter("id_1", "peer_1", "тихо", 2_000, 1_000, quiet = true)
        val o = org.json.JSONObject(ScheduledLetterSend.body(l))
        assertEquals("тихо", o.getString("body"))
        assertEquals("id_1", o.getString("client_id"))
        assertTrue(o.getBoolean("quiet"))
        assertNull(ScheduledLetterSend.outcome("""{"ok":true,"status":200,"data":{}}"""))
        assertEquals("ключ змінився", ScheduledLetterSend.outcome("""{"ok":false,"status":409,"code":"key_hold","detail":"ключ змінився"}"""))
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root not found") as Nothing
    }

    private fun src(path: String) = File(repoRoot(), path).readText().lineSequence()
        .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
        .joinToString("\n")

    @Test
    fun `reach - bridge routes, receiver in the manifest, re-armed after boot`() {
        val bridge = src("app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt")
        listOf(
            """seg[1] == "scheduled" && seg.size == 2 && method == "GET" -> ok(JSONArray(scheduled.list().map(::letterJson)))""",
            """seg[1] == "scheduled" && seg.size == 2 && method == "POST" -> scheduledAdd(body)""",
            """seg[1] == "scheduled" && seg.size == 3 && method == "DELETE" -> scheduledOut(scheduled.cancel(seg[2]))""",
            """seg[1] == "scheduled" && seg.size == 4 && seg[3] == "send" && method == "POST" -> scheduledOut(scheduled.sendNow(seg[2]))""",
            "letters = container.scheduledLetters",
            "ScheduledLetterSend.via(::dispatch, letter)",
        ).forEach { assertTrue("bridge lacks: $it", bridge.contains(it)) }
        val send = src("app/src/main/java/local/phantom/companion/service/ScheduledLetterAlarms.kt")
        assertTrue(send.contains("dispatch(\"POST\", \"/messenger/conversations/\${letter.conversationId}/messages\", body(letter))"))
        val manifest = File(repoRoot(), "app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:name=\".service.ScheduledLetterReceiver\""))
        val boot = src("app/src/main/java/local/phantom/companion/service/BootReceiver.kt")
        assertTrue(boot.contains("ScheduledLetterAlarms.rearm(context, container.scheduledLetters)"))
    }
}
