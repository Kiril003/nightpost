package local.phantom.companion.core.ai

/**
 * What [OnnxEmbeddingHelper] needs from a tokenizer, and nothing more, so one
 * helper drives both the English WordPiece vocab and the multilingual
 * SentencePiece Unigram one.
 */
interface SentenceTokenizer {

    fun encode(text: String): Encoded

    data class Encoded(
        val inputIds: IntArray,
        val attentionMask: IntArray,
        val tokenTypeIds: IntArray,
    )
}
