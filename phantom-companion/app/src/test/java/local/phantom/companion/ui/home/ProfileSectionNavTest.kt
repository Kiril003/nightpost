package local.phantom.companion.ui.home

import android.app.Application
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A52 (18.10): Ви › Налаштування › Вигляд › «Назад» відкривав «Ви», минаючи Налаштування. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProfileSectionNavTest {

    private fun nav() = NavHostController(ApplicationProvider.getApplicationContext()).apply {
        navigatorProvider.addNavigator(ComposeNavigator())
        graph = createGraph(startDestination = "home") {
            composable("home") {}
            composable(ProfileSection.ROUTE) {}
        }
    }

    private fun NavHostController.stack() =
        currentBackStack.value.mapNotNull { e -> e.arguments?.getString("section") ?: e.destination.route?.takeIf { it == "home" } }

    @Test
    fun `Back from Appearance returns to Settings`() {
        val nav = nav()
        nav.navigate(ProfileSection.SETTINGS.route)
        nav.openSection(ProfileSection.APPEARANCE.route)
        assertEquals(listOf("home", "settings", "appearance"), nav.stack())

        nav.popBackStack()
        assertEquals("settings", nav.currentBackStackEntry?.arguments?.getString("section"))
    }

    @Test
    fun `a second tap on the same section does not stack it twice`() {
        val nav = nav()
        nav.navigate(ProfileSection.SETTINGS.route)
        nav.openSection(ProfileSection.APPEARANCE.route)
        nav.openSection(ProfileSection.APPEARANCE.route)
        assertEquals(listOf("home", "settings", "appearance"), nav.stack())
    }
}
