package local.phantom.companion.ui.messenger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Геолокацію вебу чесно відповідає лише наш бандл: чуже походження отримує
 * відмову, а не вікно «Моє місце».
 */
class WebPermissionAnswerTest {

    @Test
    fun `довіряємо лише походженню бандла`() {
        assertTrue(geoOriginTrusted(WebMessengerAssets.ORIGIN))
        assertTrue("зайва скісна риска не міняє джерела", geoOriginTrusted(WebMessengerAssets.ORIGIN + "/"))
        assertFalse(geoOriginTrusted("https://evil.example"))
        assertFalse(geoOriginTrusted(null))
    }
}
