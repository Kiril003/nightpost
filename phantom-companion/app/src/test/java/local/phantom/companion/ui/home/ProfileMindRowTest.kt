package local.phantom.companion.ui.home

import java.io.File
import local.phantom.companion.R
import local.phantom.companion.core.ai.route.NightpostBrainRoute.Standing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A52, 10.10: рядок «Nightpost» казав «не налаштовано», хоча сервер Nightpost
 * відповідав і помічник через нього думав. Рядок дивився лише на ПК і власний
 * ключ; тепер читає той самий маршрут, що й розділ «Розум».
 */
class ProfileMindRowTest {

    @Test
    fun `сервер, що відповідає, — це налаштовано`() {
        assertEquals(R.string.profile_value_server, mindValue(pc = false, ownKey = false, server = Standing.UP))
        assertEquals(R.string.profile_value_server_spent, mindValue(false, false, Standing.SPENT))
        assertEquals(R.string.profile_value_server_quiet, mindValue(false, false, Standing.UNKNOWN))
        assertEquals(R.string.profile_value_not_set, mindValue(false, false, Standing.NONE))
        assertEquals(R.string.profile_value_not_set, mindValue(false, false, Standing.OFF))
        assertNull("поки питаємо сервер — не вигадуємо", mindValue(false, false, null))
    }

    @Test
    fun `ПК і свій ключ ідуть першими, як у роутері`() {
        assertEquals(R.string.profile_value_on_pc, mindValue(pc = true, ownKey = true, server = Standing.UP))
        assertEquals(R.string.profile_value_cloud, mindValue(pc = false, ownKey = true, server = null))
    }

    @Test
    fun `рядок питає справжній маршрут`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "app/src/main/java").isDirectory }
        val src = File(root, "app/src/main/java/local/phantom/companion/ui/home/ProfileSettings.kt").readText()
        assertTrue("рядок не питає сервер Nightpost", "container.nightpostBrain.standing()" in src)
        assertTrue("рядок не малює відповіді маршруту", "mindValue(state.paired != null, !geminiKey.isNullOrBlank(), serverMind)" in src)
    }
}
