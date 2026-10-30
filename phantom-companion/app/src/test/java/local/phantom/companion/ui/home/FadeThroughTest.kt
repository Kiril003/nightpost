package local.phantom.companion.ui.home

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FadeThroughTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    @Test
    fun `constants match fade through spec`() {
        assertEquals(90, FadeThrough.OUT_MS)
        assertEquals(210, FadeThrough.IN_MS)
        assertEquals(90, FadeThrough.IN_DELAY_MS)
        assertEquals(0.92f, FadeThrough.SCALE_FROM, 1e-5f)
    }

    @Test
    fun `reduced motion collapses transitions to None`() {
        assertSame(EnterTransition.None, FadeThrough.enter(true))
        assertSame(ExitTransition.None, FadeThrough.exit(true))
        assertNotSame(EnterTransition.None, FadeThrough.enter(false))
        assertNotSame(ExitTransition.None, FadeThrough.exit(false))
    }

    @Test
    fun `spec never animates the container size`() {
        assertNull(FadeThrough.spec(false).sizeTransform)
        assertNull(FadeThrough.spec(true).sizeTransform)
    }

    @Test
    fun `HomeScreen keeps ChatsTab outside AnimatedContent`() {
        val src = File(root, "app/src/main/java/local/phantom/companion/ui/home/HomeScreen.kt").readText()
        assertTrue(src.contains("FadeThrough.spec(reduced)"))
        val chatsAt = src.indexOf("ChatsTab(")
        val animatedAt = src.indexOf("AnimatedContent(")
        assertTrue(chatsAt in 0 until animatedAt)
    }
}
