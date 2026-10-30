package local.phantom.companion.ui.home

import local.phantom.companion.data.ProfilesMirror.Slot
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeVisibleTabsTest {

    @Test
    fun `без смуги видно всі вкладки, як раніше`() {
        assertEquals(listOf(HomeTab.CHATS, HomeTab.MAP, HomeTab.WALLET, HomeTab.PROFILE), visibleTabs(null))
    }

    @Test
    fun `профіль без мапи й гаманця лишає лише розмови й «Я»`() {
        assertEquals(listOf(HomeTab.CHATS, HomeTab.PROFILE), visibleTabs(emptyList()))
    }

    @Test
    fun `гаманець без мапи - бізнес і автор`() {
        assertEquals(listOf(HomeTab.CHATS, HomeTab.WALLET, HomeTab.PROFILE), visibleTabs(listOf("wallet")))
    }

    private fun ids(bar: List<String>?, slots: List<Slot>?) =
        barEntries(bar, slots).map { it.tab?.name ?: it.slot!!.id }

    @Test
    fun `Робота й Навчання - Розмови, власні вкладки, Я`() {
        assertEquals(
            listOf("CHATS", "tasks", "shifts", "PROFILE"),
            ids(emptyList(), listOf(Slot("tasks", "Завдання"), Slot("shifts", "Зміни"))),
        )
        assertEquals(listOf("CHATS", "classes", "PROFILE"), ids(emptyList(), listOf(Slot("classes", "Класи"))))
    }

    @Test
    fun `Бізнес - три вкладки, без мапи й гаманця`() {
        assertEquals(
            listOf("CHATS", "inbox", "orders", "services", "PROFILE"),
            ids(emptyList(), listOf(Slot("inbox", "Вхідні"), Slot("orders", "Замовлення"), Slot("services", "Послуги"))),
        )
    }

    @Test
    fun `старий міст без слотів дає колишню смугу, невідомий слот не малюється`() {
        assertEquals(listOf("CHATS", "MAP", "WALLET", "PROFILE"), ids(null, null))
        assertEquals(listOf("CHATS", "MAP", "WALLET", "PROFILE"), ids(listOf("map", "wallet"), listOf(Slot("hack", "X"))))
    }
}
