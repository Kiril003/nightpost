package local.phantom.companion.feature.godmode.service

import local.phantom.companion.feature.godmode.engine.NotificationRecentStore
import local.phantom.companion.feature.godmode.engine.SensitiveScreens
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Межі зору симбіота — чиста логіка, без Robolectric: банк закрито, поле
 * пароля не читається, nodeRef видно в рядку, кільце сповіщень тримає недавнє.
 */
class SymbioteSeeGuardTest {

    @After
    fun tearDown() = NotificationRecentStore.resetForTest()

    @Test
    fun `банк - це банк за точним пакетом і за підказкою в імені`() {
        assertTrue(SensitiveScreens.isBankingPackage("com.ftband.mono"))
        assertTrue(SensitiveScreens.isBankingPackage("ua.oschadbank.online"))
        assertTrue(SensitiveScreens.isBankingPackage("com.any.privat24.thing"))
        assertFalse(SensitiveScreens.isBankingPackage("org.telegram.messenger"))
        assertFalse(SensitiveScreens.isBankingPackage(""))
        assertFalse(SensitiveScreens.isBankingPackage(null))
    }

    @Test
    fun `екран банку не віддає дерева - render каже чому`() {
        val snap = UiSnapshot(
            packageName = "com.ftband.mono",
            appLabel = "monobank",
            nodes = listOf(node(text = "Баланс 1000", clickable = true)),
            banking = true,
        )
        val out = snap.render()
        assertFalse("баланс банку не мусить бути в тексті", out.contains("1000"))
        assertTrue(out.contains("банку"))
    }

    @Test
    fun `поле пароля показується маскою, а не вмістом`() {
        val snap = UiSnapshot(
            packageName = "com.example.app",
            appLabel = "Застосунок",
            nodes = listOf(
                node(text = "", editable = true, isPassword = true),
                node(text = "Увійти", clickable = true),
            ),
        )
        val out = snap.render()
        assertTrue(out.contains(SensitiveScreens.PASSWORD_MASK))
        // nodeRef для phone.tap/phone.type.
        assertTrue(out.contains("[n0]"))
        assertTrue(out.contains("[n1]"))
        assertTrue(out.contains("Увійти"))
    }

    @Test
    fun `кільце сповіщень тримає найновіші спершу і не більше ліміту`() {
        repeat(5) { i ->
            NotificationRecentStore.push(
                NotificationRecentStore.Recent("p$i", "App$i", "Заголовок$i", "Тіло$i", i.toLong()),
            )
        }
        val recent = NotificationRecentStore.recent(2)
        assertEquals(2, recent.size)
        assertEquals("App4", recent.first().appLabel)
    }

    private fun node(
        text: String = "",
        clickable: Boolean = false,
        editable: Boolean = false,
        isPassword: Boolean = false,
    ) = UiSnapshotNode(
        text = text,
        resourceId = null,
        clickable = clickable,
        editable = editable,
        scrollable = false,
        focused = false,
        isPassword = isPassword,
        centerX = 10,
        centerY = 20,
    )
}
