package local.phantom.companion.ui.settings

import local.phantom.companion.core.ai.ModelState
import local.phantom.companion.core.ai.ModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hub used to state «слух — моделі нема» and offer nothing. The only
 * download control was two screens down in the voice lab, and the only other
 * way to a model was a PTT press — the gesture an operator hunting for live
 * dialogue never makes. These pin the remedy to the diagnosis.
 */
class HearingActionTest {

    private fun states(vararg pairs: Pair<ModelType, ModelState>) = pairs.toMap()

    @Test
    fun `absent model offers a fetch carrying the size`() {
        val action = hearingAction(emptyMap())
        assertTrue(action is HearingAction.Fetch)
        // A size the UI can state before the operator commits — 137 MiB over a
        // field connection is a decision, and it must not be spent unannounced.
        assertEquals(ModelType.VOSK.downloadBytes, (action as HearingAction.Fetch).bytes)
        assertTrue("the catalogue must know the size", action.bytes > 0)
    }

    @Test
    fun `a present vosk model needs no remedy`() {
        val s = states(ModelType.VOSK to ModelState(ModelType.VOSK, isPresent = true))
        assertNull(hearingAction(s))
    }

    /**
     * Було «whisper alone also counts as an ear», і воно закріплювало ваду:
     * файл рушія, якого в збірці нема, водночас казав, що слух є, і ховав
     * єдину дорогу звантажити справжнє вухо. 12.09 той рушій прибрано, тож
     * приклад тут — інша незібрана модель; закріплюється та сама поведінка.
     */
    @Test
    fun `a non-ear model alone is not an ear`() {
        val s = states(ModelType.YAMNET to ModelState(ModelType.YAMNET, isPresent = true))
        assertEquals(HearingAction.Fetch(ModelType.VOSK.downloadBytes), hearingAction(s))
    }

    /**
     * Ліки мусять пропонувати те вухо, яке цей апарат справді візьме. Інакше
     * кнопка тягне 137 МіБ Vosk на апарат, де він не підніметься.
     */
    @Test
    fun `the remedy offers the ear this device was given`() {
        val action = hearingAction(emptyMap(), ear = ModelType.MOONSHINE_UK)
        assertEquals(HearingAction.Fetch(ModelType.MOONSHINE_UK.downloadBytes), action)
    }

    @Test
    fun `a present moonshine model needs no remedy either`() {
        val s = states(ModelType.MOONSHINE_UK to ModelState(ModelType.MOONSHINE_UK, isPresent = true))
        assertNull(hearingAction(s, ear = ModelType.MOONSHINE_UK))
    }

    @Test
    fun `a running transfer reports progress rather than offering a second one`() {
        val s = states(
            ModelType.VOSK to ModelState(ModelType.VOSK, isDownloading = true, progress = 0.42f),
        )
        assertEquals(HearingAction.Fetching(0.42f), hearingAction(s))
    }

    @Test
    fun `a failure is surfaced with its reason, not swallowed`() {
        val s = states(
            ModelType.VOSK to ModelState(ModelType.VOSK, error = "no route to host"),
        )
        assertEquals(HearingAction.Failed("no route to host"), hearingAction(s))
    }

    /** A download in flight outranks a stale error from the previous attempt. */
    @Test
    fun `downloading wins over a leftover error`() {
        val s = states(
            ModelType.VOSK to ModelState(
                ModelType.VOSK, isDownloading = true, progress = 0.1f, error = "stale",
            ),
        )
        assertTrue(hearingAction(s) is HearingAction.Fetching)
    }
}
