package local.phantom.companion.self

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ReminderPrecheckReachTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь дерева не знайдено") as Nothing
    }

    private fun source(rel: String): String {
        val f = File(repoRoot(), rel)
        if (!f.isFile) fail("немає файла $rel")
        return f.readText()
    }

    private fun codeOf(text: String): String = text.lineSequence()
        .filterNot { line ->
            val t = line.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        .joinToString("\n")

    @Test
    fun `precheck chain reaches from AgencySessionManager through PhantomSelfAndroid to ReminderStore in AppContainer`() {
        val agency = codeOf(source("app/src/main/java/local/phantom/companion/ui/messenger/AgencySessionManager.kt"))
        val precheckIdx = agency.indexOf("phantomSelf.answer(prompt)")
        val streamIdx = agency.indexOf("stream(turn, messageId, voice)")
        assertTrue("AgencySessionManager must call phantomSelf.answer(prompt)", precheckIdx >= 0)
        assertTrue("AgencySessionManager must pass model turns through stream(turn, messageId, voice)", streamIdx >= 0)
        assertTrue("phantomSelf.answer(prompt) must run before the model stream", precheckIdx < streamIdx)

        val selfAndroid = codeOf(source("app/src/main/java/local/phantom/companion/self/PhantomSelfAndroid.kt"))
        val buildBlock = selfAndroid.substringAfter("fun build(").substringBefore("): PhantomSelf")
        assertTrue("PhantomSelfAndroid.build must accept reminders parameter", buildBlock.contains("reminders:"))

        val container = codeOf(source("app/src/main/java/local/phantom/companion/data/AppContainer.kt"))
        val phantomSelfBlock = container.substringAfter("val phantomSelf:").substringBefore("val memoryRuntime:")
        assertTrue("AppContainer.phantomSelf must wire reminders =", phantomSelfBlock.contains("reminders ="))
        assertTrue("AppContainer.phantomSelf must call reminderStore.add(", phantomSelfBlock.contains("reminderStore.add("))
    }

    @Test
    fun `alarm chain reaches from PhantomApp and PhantomVisits arm into ReminderAlarms scheduleVisit`() {
        val app = codeOf(source("app/src/main/java/local/phantom/companion/PhantomApp.kt"))
        assertTrue("PhantomApp must start ReminderAlarmSync", app.contains("ReminderAlarmSync.start("))

        val visits = codeOf(source("app/src/main/java/local/phantom/companion/self/PhantomVisits.kt"))
        val armBody = visits.substringAfter("fun arm(").substringBefore("suspend fun run(")
        assertTrue("PhantomVisits.arm must call ReminderAlarms.scheduleVisit(", armBody.contains("ReminderAlarms.scheduleVisit("))
    }
}
