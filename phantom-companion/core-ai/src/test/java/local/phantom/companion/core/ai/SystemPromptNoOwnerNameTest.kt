package local.phantom.companion.core.ai

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Припис не сміє нести чуже імʼя і не сміє називати модель, якою працює.
 *
 * Обидві вигадки жили в одному рядку `DEFAULT_PHANTOM` і їхали в модель на
 * КОЖНОМУ ході кожному, хто поставить збірку: «допомагати оператору (Кирилу)» —
 * імʼя власника як стала; «на базі Gemma 4» — модель, якої не існує (качається
 * `Gemma3-1B-IT`).
 *
 * Це перевірка ЗНАЧЕННЯ, а не файлу: вона читає ту саму сталу, яку читає
 * `StreamTurnAssembler`, тож обійти її, переписавши припис деінде, нема як.
 */
class SystemPromptNoOwnerNameTest {

    private val prompts = listOf(
        "DEFAULT_PHANTOM" to SystemPrompts.DEFAULT_PHANTOM,
        "LOCAL_COMPACT" to SystemPrompts.LOCAL_COMPACT,
    )

    @Test
    fun `no personal name is baked into any system prompt`() {
        val names = listOf("Кирил", "Кирило", "Кирилу", "Kiril", "Kyrylo")
        for ((which, text) in prompts) {
            val hit = names.firstOrNull { it in text }
            assertFalse(
                "«$hit» у $which — імʼя людини вшите в припис, тож асистент звертатиметься " +
                    "так до кожного, хто поставить збірку. Імʼя має одне джерело — ProfileStore.",
                hit != null,
            )
        }
    }

    @Test
    fun `no model brand is claimed by the prompt itself`() {
        // Назви моделей у КАТАЛОЗІ віджетів — інша річ; тут ідеться лише про
        // заяву приписа про самого себе, тобто про перший абзац.
        val opening = SystemPrompts.DEFAULT_PHANTOM.trimStart().lineSequence().first()
        val brands = listOf("Gemma", "Gemini", "Qwen", "GPT", "Claude", "Llama")
        val hit = brands.firstOrNull { it in opening }
        assertFalse(
            "«$hit» у першому рядку припису: він заявляє, якою моделлю працює, " +
                "хоч дізнатись цього не може. Кожна вписана назва рано чи пізно " +
                "розійдеться з тим, що справді виконує хід.",
            hit != null,
        )
    }
}
