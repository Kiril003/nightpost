package local.phantom.companion.service

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NoticeLabelsReactionTest {

    private fun ctx(tag: String): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val conf = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return base.createConfigurationContext(conf)
    }

    @Test
    fun `the shade line names the emoji and my words in every language`() {
        assertEquals("Реакція ❤ на «завтра»", NoticeLabels.reaction(ctx("uk"), "❤", "завтра"))
        assertEquals("Reakce ❤ na „zítra“", NoticeLabels.reaction(ctx("cs"), "❤", "zítra"))
        assertEquals("Reacted ❤ to “tomorrow”", NoticeLabels.reaction(ctx("en"), "❤", "tomorrow"))
    }

    @Test
    fun `without words to quote the line still says what happened`() {
        assertEquals("Реакція 👍 на ваше повідомлення", NoticeLabels.reaction(ctx("uk"), "👍", null))
        assertEquals("Reakce 👍 na vaši zprávu", NoticeLabels.reaction(ctx("cs"), "👍", null))
    }
}
