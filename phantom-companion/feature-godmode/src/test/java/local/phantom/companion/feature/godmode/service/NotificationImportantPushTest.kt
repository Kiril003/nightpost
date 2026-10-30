package local.phantom.companion.feature.godmode.service

import local.phantom.companion.feature.godmode.engine.NotificationClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Заголовок сповіщення теж лежить у буфері повторів, не тільки тіло.
 *
 * Месенджер і пошта проходять як важливі ДО перевірки на редагування, тож
 * пересланий код у заголовку доїжджав на шину сирим і чекав там смерті процесу.
 */
class NotificationImportantPushTest {

    private fun important(pkg: String, title: String, text: String) =
        NotificationClassifier.classify(
            packageName = pkg,
            channelId = null,
            title = title,
            text = text,
        ).toImportant(atMs = 1_700_000_000_000L)

    @Test
    fun `a code forwarded in a viber title never reaches the bus`() {
        val pushed = important("com.viber.voip", "Код 4821", "переслав тобі")

        assertFalse(pushed.redactedTitle.contains("4821"))
        assertFalse(pushed.toString().contains("4821"))
    }

    @Test
    fun `a code in a mail subject never reaches the bus`() {
        val pushed = important("com.google.android.gm", "Your code is 731209", "тіло листа")

        assertFalse(pushed.toString().contains("731209"))
    }

    @Test
    fun `a sender name with no secret stays whole`() {
        val pushed = important("org.telegram.messenger", "Оля", "буду о шостій")

        assertEquals("Оля", pushed.redactedTitle)
        assertEquals("org.telegram.messenger", pushed.packageName)
        assertEquals(1_700_000_000_000L, pushed.deliveredAtMs)
    }
}
