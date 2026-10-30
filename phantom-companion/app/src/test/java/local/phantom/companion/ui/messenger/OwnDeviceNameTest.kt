package local.phantom.companion.ui.messenger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The A52's test rows: own computers belong under «Мої пристрої», people stay people. */
class OwnDeviceNameTest {

    @Test
    fun `computers and test nodes are devices`() {
        listOf("ПК-вузол", "ПК · PHANTOM OS", "Ноутбук", "Ноутбук · 6 вер., 02:48", "Laptop", "компʼютер", "E2E-reply-from-PC")
            .forEach { assertTrue(it, looksLikeDevice(it)) }
    }

    @Test
    fun `people keep their names`() {
        listOf("Оксана", "Iquw", "Ugf", "Новий контакт", "Пкачук Іван", "Сусіди", null)
            .forEach { assertFalse(it.toString(), looksLikeDevice(it)) }
    }
}
