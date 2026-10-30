package local.phantom.companion.ui.messenger

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Слова моделі під `PH-AI:` розбираються в рід «сказане»; набране не вдає модель. */
class WebMessengerAiReachTest {

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

    private val bridge = "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt"

    /**
     * Набране не сміє ВДАВАТИ модель.
     *
     * `PH-AI:` навмисно немає в [PeerFrames.Kind] — інакше `PeerOutbox.isSpoken`
     * не пустив би відповідь у розмову, — тож `refuseTyped` її не судить. Поки
     * позначка малювалась як «невідома», це нічого не коштувало; щойно вона
     * стала підписом «⚡ PHANTOM», рядок із клавіатури став підробкою особи.
     */
    @Test
    fun `typed text cannot dress itself as the model`() {
        val src = source(bridge)
        assertEquals(
            "обидві дороги набраного — і група, і розмова вдвох — мусять відмовити позначці",
            2,
            Regex("""WebMessengerAi\.isAi\(text\)""").findAll(src).count(),
        )
        assertTrue(
            "відмова не названа тим самим родом, що решта позначок",
            src.contains("\"service_prefix\""),
        )
    }

    /** Слова моделі доїжджають родом «сказане», а не «невідома позначка». */
    @Test
    fun `a marked answer is read as speech, with the words kept`() {
        val fields = WebMessengerKinds.fields(WebMessengerAi.encode("вітер 12 км/год, дощу немає"))
        assertEquals(WebMessengerKinds.TEXT, fields[WebMessengerKinds.KIND])
        assertEquals("вітер 12 км/год, дощу немає", fields[WebMessengerAi.SAID])
        assertNull(
            "відповідь моделі підмінено реченням «невідома позначка»",
            fields[WebMessengerKinds.NOTICE],
        )
    }

    /** Рядок людини лишається рядком людини: поля моделі в ньому немає. */
    @Test
    fun `plain speech carries no model field`() {
        val fields = WebMessengerKinds.fields("@фантом яка погода")
        assertEquals(WebMessengerKinds.TEXT, fields[WebMessengerKinds.KIND])
        assertNull(fields[WebMessengerAi.SAID])
    }

    /** Лічильник непрочитаного бачить відповідь так само, як стрічка. */
    @Test
    fun `a model answer counts as something said`() {
        assertTrue(
            "відповідь моделі схована від стрічки — число і вміст розійшлись",
            WebMessengerKinds.isVisible(WebMessengerAi.encode("готово")),
        )
    }

    /** Підпис під словами моделі є в обох мовах — інакше він порожній. */
    @Test
    fun `the model signature exists in both languages`() {
        listOf("app/src/main/res/values-uk/strings.xml", "app/src/main/res/values/strings.xml")
            .forEach {
                assertTrue(
                    "$it не має msgr_group_ai_author",
                    source(it).contains("name=\"msgr_group_ai_author\""),
                )
            }
    }
}
