package local.phantom.companion.ui.messenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class AbuseReportTest {

    @Test fun `лист у підтримку несе ідентифікатори, а не текст`() {
        val body = AbuseReport.body("Скаржуся", peerNodeId = "node-7f3a", messageId = "msg-42", build = "0.1.0")
        assertEquals("Скаржуся\n\npeer: node-7f3a\nmessage: msg-42\nbuild: 0.1.0", body)
        assertFalse("message:" in AbuseReport.body("x", "node-7f3a", null, "0.1.0"))
    }

    @Test fun `адреса — підтримка з сайту, пробіли не стають плюсами`() {
        val mail = AbuseReport.mailto("PHANTOM — скарга", "a b+c")
        assertTrue(mail.startsWith("mailto:support@phantom-os.dev?subject="))
        assertFalse(mail.substringAfter('?').contains('+'))
        val body = mail.substringAfter("&body=")
        assertEquals("a b+c", URLDecoder.decode(body.replace("+", "%2B"), "UTF-8"))
    }
}
