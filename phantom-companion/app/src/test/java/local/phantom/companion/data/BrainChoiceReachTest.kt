package local.phantom.companion.data

import java.io.File
import local.phantom.companion.core.ai.RoutingStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Вибір мозку мусить ДОЛІТАТИ з екрана до роутера.
 *
 * До 04.09 `RoutedAIRouter` діставав `strategyProvider = { RoutingStrategy.CloudFirst }`
 * літералом: `LocalFirst` і `LocalOnly` жили в коді й були недосяжні з жодного екрана —
 * класичне «написане й не викликане». Тому сторож перевіряє не те, що роутер поважає
 * стратегію (це вже доводить RoutedAIRouterTest), а те, що ЛАНЦЮГ цілий:
 * екран → [BrainPreferences] → провайдер контейнера → роутер.
 *
 * 04.10 (profile-hub): «екран» — це вже розділ PHANTOM у Профілі, а не старий
 * екран налаштувань за дверима «Для розробника». Ланцюг той самий.
 */
class BrainChoiceReachTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "app").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь репозиторію не знайдено") as Nothing
    }

    private fun source(path: String): String {
        val f = File(repoRoot(), path)
        assertTrue("$path не знайдено — сторож нічого не боронить", f.isFile)
        return f.readText()
    }

    @Test
    fun `the router takes the saved choice, not a literal`() {
        val src = source("app/src/main/java/local/phantom/companion/data/AppContainer.kt")
        assertTrue(
            "контейнер більше не бере вибір людини з BrainPreferences",
            src.contains("brainPrefs.routing.value == RoutingStrategy.LocalOnly"),
        )
        assertFalse(
            "повернувся літерал у лямбді — вибір знову зроблено за людину",
            src.contains("strategyProvider = { RoutingStrategy.CloudFirst }"),
        )
    }

    @Test
    fun `the screen writes the same store the router reads`() {
        val sections = source("app/src/main/java/local/phantom/companion/ui/home/ProfileSections.kt")
        val phantom = sections.substringAfter("private fun PhantomSection(", "")
            .substringBefore("internal fun openPhantomChat(")
        assertTrue("розділу PHANTOM у Профілі немає", phantom.isNotEmpty())
        assertTrue("екран не читає вибір із контейнера", phantom.contains("container.brainPrefs.routing"))
        assertTrue("екран не зберігає вибір", phantom.contains("container.brainPrefs::setRouting"))
        assertTrue("розділ PHANTOM не малює вибору", phantom.contains("BrainOrderRows("))

        val pane = source("app/src/main/java/local/phantom/companion/ui/settings/BrainPane.kt")
        assertTrue("рядок вибору не пише вибір", pane.contains("onSelect = { onRoutingChange(order) }"))
        assertTrue(
            "факт «ключа немає» не показано — людина мусила б здогадуватись",
            pane.contains("R.string.look_brain_no_key"),
        )
    }

    @Test
    fun `local only is offered, cloud only shows only when it is already the choice`() {
        val offered = local.phantom.companion.ui.settings.brainOrderChoices(RoutingStrategy.CloudFirst)
        assertTrue("у виборі немає «лише на пристрої» — стан лишився недосяжним", RoutingStrategy.LocalOnly in offered)
        assertFalse("«лише хмару» пропонують, хоча її не пропонували й раніше", RoutingStrategy.CloudOnly in offered)
        // Збережений вибір, якого нема в списку, лишив би вибір без позначки —
        // екран мовчки брехав би про поточний стан.
        listOf(
            RoutingStrategy.CloudFirst, RoutingStrategy.LocalFirst,
            RoutingStrategy.LocalOnly, RoutingStrategy.CloudOnly,
        ).forEach { current ->
            assertTrue(
                "поточний $current не показано серед варіантів",
                current in local.phantom.companion.ui.settings.brainOrderChoices(current),
            )
        }
    }

    @Test
    fun `every order has a word and a consequence, in both languages`() {
        val uk = source("app/src/main/res/values-uk/strings.xml") +
            source("app/src/main/res/values-uk/strings_look_profile_hub.xml")
        val en = source("app/src/main/res/values/strings.xml") +
            source("app/src/main/res/values/strings_look_profile_hub.xml")
        val keys = listOf(
            "look_brain_order",
            "look_brain_cloud_first", "settings_brain_order_cloud_first_body",
            "look_brain_local_first", "settings_brain_order_local_first_body",
            "look_brain_local_only", "settings_brain_order_local_only_body",
            "look_brain_cloud_only", "settings_brain_order_cloud_only_body",
            "look_brain_no_key",
        )
        keys.forEach {
            assertTrue("uk не має $it", uk.contains("name=\"$it\""))
            assertTrue("en не має $it", en.contains("name=\"$it\""))
        }
    }

    @Test
    fun `the stored tag survives a round trip for every order`() {
        // Теги, а не порядкові номери: додавання стану посередині не має мовчки
        // перемкнути чийсь збережений вибір.
        val src = source("app/src/main/java/local/phantom/companion/data/BrainPreferences.kt")
        listOf("cloud_first", "local_first", "local_only", "cloud_only").forEach {
            assertTrue("немає тега $it", src.contains("\"$it\""))
        }
        assertFalse("вибір зберігається порядковим номером", src.contains("ordinal"))
        assertEquals(
            "типове значення змінено мовчки — це окреме рішення власника",
            true,
            src.contains("val DEFAULT: RoutingStrategy = RoutingStrategy.CloudFirst"),
        )
    }
}
