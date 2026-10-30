package local.phantom.companion.self

import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.ai.route.MemoryGround
import local.phantom.companion.core.data.memory.MemoryFactStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfMemoryTest {

    private var clock = 1_000L
    private val dao = FakeFactDao()
    private val memory = SelfMemory(MemoryFactStore(dao)) { clock++ }

    @Test
    fun `a fact says where it came from`() = runBlocking {
        memory.remember(SelfSpeech.Fact("Ти любиш каву"), SelfMemory.From.SELF)
        memory.remember(SelfSpeech.Fact("Ти живеш у Львові", "home"), SelfMemory.From.HEARD)
        val known = memory.known().associate { it.text to it.from }
        assertEquals(SelfMemory.From.SELF, known["Ти любиш каву"])
        assertEquals(SelfMemory.From.HEARD, known["Ти живеш у Львові"])
    }

    @Test
    fun `not true forgets the fact and it leaves every answer`() = runBlocking {
        val id = memory.remember(SelfSpeech.Fact("Ти любиш каву"), SelfMemory.From.HEARD)
        memory.remember(SelfSpeech.Fact("Тебе звати Кирило", "name"), SelfMemory.From.SELF)
        assertTrue(memory.promptBlock().contains("Ти любиш каву"))

        assertTrue(memory.forget(id))

        assertFalse(memory.known().any { it.text == "Ти любиш каву" })
        assertFalse(dao.rows.values.any { it.text == "Ти любиш каву" })
        assertFalse(memory.promptBlock().contains("каву"))
        assertTrue(memory.promptBlock().contains("Тебе звати Кирило"))
        assertFalse(memory.forget(id))
    }

    @Test
    fun `forget by words takes the closest fact only`() = runBlocking {
        memory.remember(SelfSpeech.Fact("Ти любиш каву"), SelfMemory.From.HEARD)
        memory.remember(SelfSpeech.Fact("Тебе звати Кирило", "name"), SelfMemory.From.SELF)
        assertEquals("Ти любиш каву", memory.forgetMatching("що я люблю каву")?.text)
        assertEquals(listOf("Тебе звати Кирило"), memory.known().map { it.text })
        assertNull(memory.forgetMatching("собаку"))
    }

    @Test
    fun `a slot holds one answer`() = runBlocking {
        memory.remember(SelfSpeech.Fact("Тебе звати Петро", "name"), SelfMemory.From.HEARD)
        memory.remember(SelfSpeech.Fact("Тебе звати Кирило", "name"), SelfMemory.From.SELF)
        assertEquals(listOf("Тебе звати Кирило"), memory.known().map { it.text })
    }

    /**
     * BrainChain не додає блок памʼяті вдруге, якщо системний промпт уже несе
     * [MemoryGround.HEADER_MARK]. Позначку пише `core-ai`, а сам блок — `Ledger`
     * у `core-memory`; модулі одне одного не бачать, тож розійтися вони можуть
     * мовчки (апостроф, назва), і тоді блок у промпті подвоюється.
     */
    @Test
    fun `the block the ledger writes carries the mark BrainChain looks for`() = runBlocking {
        memory.remember(SelfSpeech.Fact("Тебе звати Кирило", "name"), SelfMemory.From.SELF)
        val block = memory.promptBlock("Як мене звати?")
        assertTrue(block, block.startsWith(MemoryGround.HEADER_MARK))
    }

    @Test
    fun `the prompt block keeps to its budget`() = runBlocking {
        repeat(60) { memory.remember(SelfSpeech.Fact("Факт номер $it про людину, досить довгий рядок"), SelfMemory.From.HEARD) }
        val block = memory.promptBlock()
        assertTrue(local.phantom.companion.core.ai.route.PromptBudget.estimateTokens(block) <= SelfMemory.PROMPT_BUDGET)
        assertTrue(block.lines().size in 2..59)
        assertEquals("", SelfMemory(MemoryFactStore(FakeFactDao())).promptBlock())
    }
}
