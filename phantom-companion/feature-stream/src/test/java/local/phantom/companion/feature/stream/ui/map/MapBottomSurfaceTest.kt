package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «НАЛЕЗЛО ПО ВЕРХ» — вердикт тестувальника 11.09.
 *
 * Аркуш пошуку відкритий, поверх нього картка відповіді. Сторож міряє саме
 * взаємовиключність, а не порядок малювання: порядок у `Box` легко відновити
 * наступною правкою й не помітити.
 */
class MapBottomSurfaceTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "feature-stream").isDirectory }
        ?: error("не знайшла кореня сховища: жоден предок не має теки feature-stream")

    @Test
    fun `при відкритому аркуші пошуку картки відповіді немає`() {
        assertFalse(MapBottomSurface.answerVisible(answerLive = true, searchOpen = true))
    }

    @Test
    fun `без аркуша картка живе`() {
        assertTrue(MapBottomSurface.answerVisible(answerLive = true, searchOpen = false))
    }

    @Test
    fun `мертва відповідь не зʼявляється від закриття аркуша`() {
        assertFalse(MapBottomSurface.answerVisible(answerLive = false, searchOpen = false))
        assertFalse(MapBottomSurface.answerVisible(answerLive = false, searchOpen = true))
    }

    @Test
    fun `екран мапи питає правило, а не лише isLive`() {
        val src = File(
            root,
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapScreen.kt",
        ).readText()
        assertTrue("екран мапи не знайдено", src.isNotBlank())
        assertTrue(
            "видимість картки відповіді мусить іти через MapBottomSurface",
            src.contains("MapBottomSurface.answerVisible("),
        )
        assertFalse(
            "гола умова `visible = mapAnswer.isLive` не знає про аркуш пошуку",
            src.contains("visible = mapAnswer.isLive"),
        )
    }
}
