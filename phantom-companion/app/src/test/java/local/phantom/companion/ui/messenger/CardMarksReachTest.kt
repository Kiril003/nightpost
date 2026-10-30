package local.phantom.companion.ui.messenger

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Лише те, чого з JVM не викликати: підключення мосту до класів позначок.
 *
 * Логіка дотику й дорога від тіла запиту до JSON відповіді доводяться ВИКЛИКОМ
 * у [WebMessengerCardMarkDoorTest] (двері на фікстурі журналу) і
 * [SceneBridgeReachTest] (розвилка сцени); тут — що міст бере ці самі класи, а не
 * свою копію. Robolectric у `:app` немає (`app/build.gradle.kts`), тож сам
 * `WebMessengerBridge.route` з тесту не підняти.
 *
 * GATE LAW: читає файли як текст.
 */
class CardMarksReachTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь дерева не знайдено") as Nothing
    }

    private fun source(rel: String): String {
        val f = File(repoRoot(), rel)
        if (!f.isFile) fail("немає файла $rel")
        return f.readText().lineSequence()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")
    }

    private val bridge by lazy { source("app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt") }

    private fun block(code: String, start: String, end: String): String {
        assertTrue("немає «$start»", code.contains(start))
        return code.substringAfter(start).substringBefore(end)
    }

    @Test
    fun `міст бере двері дотику з класу, а не пише свою копію`() {
        assertTrue("двері дотику не зібрані з журналу мосту", bridge.contains("WebMessengerCardMarkDoor("))
        assertTrue("у мосту лишилась власна cardMark", !bridge.contains("private fun cardMark("))
        assertTrue("шлях /marks все ще окремою гілкою мосту", !bridge.contains("cardMark(seg[2], body)"))
    }

    @Test
    fun `позначки їдуть у стрічку — і вдвох, і в групі, тією ж формою, що у відповіді на дотик`() {
        val one = block(bridge, "private fun message(", "\n    private fun ")
        val many = block(bridge, "private fun groupMessage(", "\n    private ")
        assertTrue("рядок розмови вдвох не несе card_marks", one.contains("cardMarksInto("))
        assertTrue("рядок групи не несе card_marks", many.contains("cardMarksInto("))
        val into = block(bridge, "private fun cardMarksInto(", "\n    private fun ")
        assertTrue("поле зветься не так, як чекає веб", into.contains("\"card_marks\""))
        assertTrue("форму складає не WebMessengerCardMarks.onLine", into.contains("WebMessengerCardMarks.onLine("))
        assertTrue(
            "стрічка згортає журнал на кожен рядок, а не раз на розмову",
            block(bridge, "private fun messages(", "\n    private fun ").contains("WebMessengerCardMarks.fold(") &&
                block(bridge, "private fun groupMessages(", "\n    private fun ").contains("WebMessengerCardMarks.fold("),
        )
    }

    @Test
    fun `нова група називає тих, кого не взяла`() {
        val create = block(bridge, "private fun createGroup(", "\n    private fun ")
        assertTrue("склад рахує не WebMessengerGroupInvite", create.contains("WebMessengerGroupInvite.split("))
        assertTrue("відповідь не несе викинутих", create.contains("\"dropped\""))
    }

    @Test
    fun `слова відмов сцени беруться зі слів, а не з паралельної таблиці мосту`() {
        assertTrue("лишилась ручна таблиця слів закладів", !bridge.contains("private fun venueWords("))
        assertTrue("лишилась ручна таблиця слів балів", !bridge.contains("private fun pointsWords("))
        assertTrue(bridge.contains("words = { say(it.res) }"))
    }
}
