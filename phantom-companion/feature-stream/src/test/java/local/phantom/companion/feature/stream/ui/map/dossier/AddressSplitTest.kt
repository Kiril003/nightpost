package local.phantom.companion.feature.stream.ui.map.dossier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Адреса з геокодера — три ланки тілом, решта дрібним. */
class AddressSplitTest {

    @Test
    fun `довгий ланцюг ділиться на адресу й хвіст`() {
        val (head, tail) = splitAddress(
            "Opavska, 114, Opawska, Nowe Zagrody, Ратибор, Рациборський повіт, Сілезьке воєводство, 47-400, Польща",
        )
        assertEquals("Opavska, 114, Opawska", head)
        assertEquals("Nowe Zagrody, Ратибор, Рациборський повіт, Сілезьке воєводство, 47-400, Польща", tail)
    }

    @Test
    fun `коротка адреса лишається цілою`() {
        val (head, tail) = splitAddress("Хрещатик, 22, Київ")
        assertEquals("Хрещатик, 22, Київ", head)
        assertNull(tail)
    }

    @Test
    fun `порожні ланки не рахуються`() {
        val (head, tail) = splitAddress("Хрещатик, , 22, Київ, , Україна")
        assertEquals("Хрещатик, 22, Київ", head)
        assertEquals("Україна", tail)
    }
}
