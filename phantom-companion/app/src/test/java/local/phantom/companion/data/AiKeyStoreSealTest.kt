package local.phantom.companion.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AiKeyStoreSealTest {

    @Test
    fun `sealed store refuses plain keys`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AiKeyStore(context, allowPlain = false, openSealed = { error("no keystore") })
        assertFalse(store.canStore)
        assertFalse(store.saveGeminiKey("k".repeat(30)))
        assertFalse(store.saveHfToken("h".repeat(30)))
        assertNull(store.geminiKey.value)
        assertNull(store.hfToken.value)
        val fallback = context.getSharedPreferences("phantom_ai_keys_fallback", Context.MODE_PRIVATE)
        assertTrue(fallback.all.isEmpty())
    }

    @Test
    fun `field keeps the plain fallback`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AiKeyStore(context, allowPlain = true, openSealed = { error("no keystore") })
        assertTrue(store.canStore)
        val key = "k".repeat(30)
        assertTrue(store.saveGeminiKey(key))
        assertEquals(key, store.geminiKey.value)
        val fallback = context.getSharedPreferences("phantom_ai_keys_fallback", Context.MODE_PRIVATE)
        assertEquals(key, fallback.getString("gemini_api_key", null))
    }

    @Test
    fun `sealed prefs win`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AiKeyStore(
            context,
            allowPlain = false,
            openSealed = { it.getSharedPreferences("sealed_test", Context.MODE_PRIVATE) },
        )
        assertTrue(store.canStore)
        val key = "s".repeat(30)
        assertTrue(store.saveGeminiKey(key))
        assertEquals(key, store.geminiKey.value)
    }
}
