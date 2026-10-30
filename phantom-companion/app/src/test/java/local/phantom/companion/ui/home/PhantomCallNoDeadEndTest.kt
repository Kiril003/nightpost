package local.phantom.companion.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Дзвінок Nightpost (ніч 09.10, A52 і A56).
 *  - FR-3: мікрофон відмовлено — екран казав «дозвольте мікрофон» і не мав
 *    чим; кнопка «Мікрофон» лише вимикала звук. Глухий кут.
 *  - FR-6: «Live voice unavailable: телефон не спарений з комп'ютером; на
 *    телефоні немає ключа Gemini» — мова розробника, ще й українська посеред
 *    англійської; шапка «Turn by turn» (слово мапи), під іменем Nightpost літера «P».
 * Читаємо з джерел: `:app` не має ui-test.
 */
class PhantomCallNoDeadEndTest {

    private val app: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }.let { File(it, "app/src/main") }

    private fun src(rel: String) = File(app, "java/local/phantom/companion/$rel").readText()

    private fun string(dir: String, name: String): String =
        Regex("""<string name="$name">(.*?)</string>""").find(File(app, "res/$dir/strings_home.xml").readText())
            ?.groupValues?.get(1) ?: error("$dir/$name немає")

    @Test
    fun `refused microphone has a button that asks again`() {
        val screen = src("ui/home/PhantomCallScreen.kt")
        val branch = screen.substringAfter("if (refused) {", "").take(900)
        assertTrue("немає гілки для відмовленого мікрофона", branch.isNotEmpty())
        assertTrue("кнопка не питає мікрофон знову", "gate.requestThen" in branch && "call.begin()" in branch)
        assertTrue("кнопка без слів", "R.string.call_mic_allow" in branch)
        assertEquals("Allow microphone", string("values", "call_mic_allow"))
    }

    @Test
    fun `the person never sees the engine's reason`() {
        for (dir in listOf("values-uk", "values")) {
            for (name in listOf("call_live_unavailable", "call_live_lost")) {
                assertFalse("$dir/$name підставляє причину рушія", "%1\$s" in string(dir, name))
            }
        }
        val call = src("self/PhantomCall.kt")
        assertFalse(Regex("""getString\(R\.string\.call_live_(unavailable|lost),""").containsMatchIn(call))
    }

    @Test
    fun `header and avatar speak Nightpost`() {
        assertFalse("шапка дзвінка — слово мапи", string("values", "call_mode_turn") == "Turn by turn")
        val screen = src("ui/home/PhantomCallScreen.kt")
        assertFalse("літера P замість знака", "Text(\"P\"" in screen)
        assertTrue("R.drawable.lc_phantom_mark" in screen)
    }
}
