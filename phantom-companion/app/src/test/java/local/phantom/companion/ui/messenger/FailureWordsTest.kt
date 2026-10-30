package local.phantom.companion.ui.messenger

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import local.phantom.companion.R
import local.phantom.companion.core.ai.AIError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Огляд 14.10, C9: наш власний збій ([AIError.Broken]) не показує людині рядок винятку. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FailureWordsTest {

    private fun inLanguage(tag: String): Context {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val conf = Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return app.createConfigurationContext(conf)
    }

    @Test
    fun `an internal fault reads as a plain sentence in both languages`() {
        assertNotEquals(inLanguage("uk").getString(R.string.ai_fail_unknown), inLanguage("en").getString(R.string.ai_fail_unknown))
        for (tag in listOf("uk", "en")) {
            val ctx = inLanguage(tag)
            val inner = "buildRequest: IllegalStateException: signer key slot 3 empty"

            val said = FailureWords.of(AIError.Broken(inner)) { id, args -> ctx.getString(id, *args) }

            assertEquals(tag, ctx.getString(R.string.ai_fail_unknown), said)
            assertFalse("$tag: $said", "IllegalStateException" in said || "signer" in said)
        }
    }
}
