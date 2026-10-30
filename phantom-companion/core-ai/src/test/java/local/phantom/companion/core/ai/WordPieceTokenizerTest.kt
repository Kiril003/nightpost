package local.phantom.companion.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the WordPiece algorithm against a tiny hand-built vocab (no 30k asset).
 */
class WordPieceTokenizerTest {

    // [PAD]=0 [UNK]=1 [CLS]=2 [SEP]=3 mirrors real BERT ordering.
    private val vocab = mapOf(
        "[PAD]" to 0, "[UNK]" to 1, "[CLS]" to 2, "[SEP]" to 3,
        "hello" to 4, "world" to 5,
        "play" to 6, "##ing" to 7,
        "," to 8,
    )

    private fun tok(doLowerCase: Boolean = true, maxInputTokens: Int = 128) =
        WordPieceTokenizer(vocab, doLowerCase = doLowerCase, maxInputTokens = maxInputTokens)

    @Test
    fun `in-vocab word maps to its id wrapped by CLS and SEP`() {
        val e = tok().encode("hello")
        assertArrayEquals(intArrayOf(2, 4, 3), e.inputIds)
    }

    @Test
    fun `subword split emits hash-hash continuation pieces`() {
        val e = tok().encode("playing")
        assertArrayEquals(intArrayOf(2, 6, 7, 3), e.inputIds) // play + ##ing
    }

    @Test
    fun `out-of-vocab sequence becomes UNK`() {
        val e = tok().encode("xyz")
        assertArrayEquals(intArrayOf(2, 1, 3), e.inputIds)
    }

    @Test
    fun `lowercasing on versus off changes ids`() {
        val lowered = tok(doLowerCase = true).encode("Hello").inputIds
        val cased = tok(doLowerCase = false).encode("Hello").inputIds
        assertArrayEquals(intArrayOf(2, 4, 3), lowered) // -> hello
        assertArrayEquals(intArrayOf(2, 1, 3), cased)   // "Hello" not in vocab -> UNK
        assertTrue(!lowered.contentEquals(cased))
    }

    @Test
    fun `punctuation splits into its own token`() {
        val e = tok().encode("hello, world")
        assertArrayEquals(intArrayOf(2, 4, 8, 5, 3), e.inputIds) // hello , world
    }

    @Test
    fun `truncation to maxInputTokens keeps CLS and SEP`() {
        val e = tok(maxInputTokens = 4).encode("hello world hello world")
        assertEquals(4, e.inputIds.size)
        assertEquals(2, e.inputIds.first()) // CLS
        assertEquals(3, e.inputIds.last())  // SEP
    }

    @Test
    fun `attention mask matches token count and is all ones`() {
        val e = tok().encode("hello world")
        assertEquals(e.inputIds.size, e.attentionMask.size)
        assertEquals(e.inputIds.size, e.tokenTypeIds.size)
        assertTrue(e.attentionMask.all { it == 1 })
        assertTrue(e.tokenTypeIds.all { it == 0 })
    }

    @Test
    fun `fromVocabLines assigns ids by line index`() {
        val t = WordPieceTokenizer.fromVocabLines(
            sequenceOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "hello"),
        )
        assertArrayEquals(intArrayOf(2, 4, 3), t.encode("hello").inputIds)
    }

    private fun assertArrayEquals(expected: IntArray, actual: IntArray) =
        org.junit.Assert.assertArrayEquals(expected, actual)
}
