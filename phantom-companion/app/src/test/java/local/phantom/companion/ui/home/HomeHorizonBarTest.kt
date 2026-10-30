package local.phantom.companion.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Нижня навігація — горизонт на всю ширину під вмістом, не плаваюча капсула:
 * капсула — мова чужого месенджера, і власник її відкинув. `:app` нічого не
 * рендерить (немає compose-ui-test), тож форму звіряємо по джерелу.
 */
class HomeHorizonBarTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    private fun src(path: String) = File(root, "app/src/main/java/local/phantom/companion/$path").readText()

    @Test
    fun `панель стоїть під вмістом, а WebView сам підбиває низ, коли її сховано`() {
        val home = src("ui/home/HomeScreen.kt")
        val body = home.substringAfter("val barVisible")
        assertTrue("вміст і панель більше не в одному стовпці", body.indexOf("Column(") in 0 until body.indexOf("HomeBottomBar("))
        assertTrue("сховані панелі більше не підбивають низ WebView", "padBottom = !barVisible" in home)
    }

    @Test
    fun `рідний бік не обіцяє вебу плаваючого дока`() {
        listOf("ui/home/HomeScreen.kt", "ui/home/HomeBottomBar.kt", "ui/messenger/WebMessengerHost.kt").forEach {
            assertFalse("$it ставить --ph-dock-h, а дока немає", "--ph-dock-h" in src(it))
        }
    }

    @Test
    fun `таббар np - волосина 0,5, вибране пігулкою, жодного бурштину`() {
        val bar = src("ui/home/HomeBottomBar.kt")
        assertTrue("немає волосини згори", ".height(0.5.dp).background(c.line)" in bar)
        assertEquals("бурштин у таббарі лише на крапці непрочитаного", 1, Regex("""c\.accent\w*""").findAll(bar).count())
        assertTrue("нема пігулки вибраної вкладки", "SelectedPill(on = on)" in bar)
        assertTrue("невибрані не text2", "if (on) c.text else c.text2" in bar)
        assertTrue("зміна вкладки без тіку", "HapticFeedbackConstants.CLOCK_TICK" in bar)
    }
}
