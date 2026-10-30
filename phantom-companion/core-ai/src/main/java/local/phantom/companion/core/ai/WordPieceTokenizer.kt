package local.phantom.companion.core.ai

import java.text.Normalizer

/**
 * Configurable BERT WordPiece tokenizer (BasicTokenizer → WordPiece), a faithful
 * port of HuggingFace `BertTokenizer` for the common cases. Stays vocab-agnostic
 * so one class serves an uncased English vocab now and a cased multilingual one later.
 */
class WordPieceTokenizer(
    private val vocab: Map<String, Int>,
    private val doLowerCase: Boolean = true,
    unkToken: String = "[UNK]",
    clsToken: String = "[CLS]",
    sepToken: String = "[SEP]",
    @Suppress("UNUSED_PARAMETER") padToken: String = "[PAD]",
    private val maxInputTokens: Int = 128,
) : SentenceTokenizer {
    private val unkToken = unkToken
    private val unkId = vocab[unkToken] ?: 100
    private val clsId = vocab[clsToken] ?: 101
    private val sepId = vocab[sepToken] ?: 102
    private val maxCharsPerWord = 100

    /** input_ids with [CLS] … [SEP], plus the parallel attention mask and (all-zero) token type ids. */
    override fun encode(text: String): SentenceTokenizer.Encoded {
        val pieces = tokenize(text)
        val limit = (maxInputTokens - 2).coerceAtLeast(0)
        val kept = if (pieces.size > limit) pieces.subList(0, limit) else pieces

        val ids = ArrayList<Int>(kept.size + 2)
        ids.add(clsId)
        for (p in kept) ids.add(vocab[p] ?: unkId)
        ids.add(sepId)

        val n = ids.size
        return SentenceTokenizer.Encoded(
            inputIds = IntArray(n) { ids[it] },
            attentionMask = IntArray(n) { 1 },
            tokenTypeIds = IntArray(n) { 0 },
        )
    }

    /** Full piece list (no special tokens), matching HF `BertTokenizer.tokenize`. */
    fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        for (token in basicTokenize(text)) wordpiece(token, out)
        return out
    }

    // --- BasicTokenizer ---

    private fun basicTokenize(text: String): List<String> {
        val normalized = tokenizeCJK(cleanText(text))
        val out = ArrayList<String>()
        for (token in normalized.split(WHITESPACE)) {
            if (token.isEmpty()) continue
            var t = token
            if (doLowerCase) {
                t = t.lowercase()
                t = stripAccents(t)
            }
            splitOnPunctuation(t, out)
        }
        return out
    }

    private fun cleanText(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == 0 || cp == 0xFFFD || isControl(cp)) continue
            if (isWhitespace(cp)) sb.append(' ') else sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    private fun tokenizeCJK(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (isChineseChar(cp)) {
                sb.append(' ').appendCodePoint(cp).append(' ')
            } else {
                sb.appendCodePoint(cp)
            }
        }
        return sb.toString()
    }

    private fun stripAccents(text: String): String {
        val nfd = Normalizer.normalize(text, Normalizer.Form.NFD)
        val sb = StringBuilder(nfd.length)
        var i = 0
        while (i < nfd.length) {
            val cp = nfd.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.getType(cp) == Character.NON_SPACING_MARK.toInt()) continue
            sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    private fun splitOnPunctuation(text: String, out: MutableList<String>) {
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (isPunctuation(cp)) {
                if (current.isNotEmpty()) {
                    out.add(current.toString()); current.setLength(0)
                }
                out.add(StringBuilder().appendCodePoint(cp).toString())
            } else {
                current.appendCodePoint(cp)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
    }

    // --- WordPiece greedy longest-match-first ---

    private fun wordpiece(token: String, out: MutableList<String>) {
        if (token.length > maxCharsPerWord) {
            out.add(unkToken); return
        }
        val subTokens = ArrayList<String>()
        var start = 0
        val len = token.length
        var bad = false
        while (start < len) {
            var end = len
            var match: String? = null
            while (start < end) {
                val piece = if (start > 0) "##" + token.substring(start, end) else token.substring(start, end)
                if (vocab.containsKey(piece)) {
                    match = piece; break
                }
                end--
            }
            if (match == null) {
                bad = true; break
            }
            subTokens.add(match)
            start = end
        }
        if (bad) out.add(unkToken) else out.addAll(subTokens)
    }

    // --- character classes (HF BasicTokenizer helpers) ---

    private fun isControl(cp: Int): Boolean {
        if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return false
        return when (Character.getType(cp)) {
            Character.CONTROL.toInt(),
            Character.FORMAT.toInt(),
            Character.SURROGATE.toInt(),
            Character.PRIVATE_USE.toInt(),
            Character.UNASSIGNED.toInt() -> true
            else -> false
        }
    }

    private fun isWhitespace(cp: Int): Boolean {
        if (cp == ' '.code || cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return true
        return Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()
    }

    private fun isPunctuation(cp: Int): Boolean {
        if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
        return when (Character.getType(cp)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
    }

    private fun isChineseChar(cp: Int): Boolean =
        cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x2A6DF ||
            cp in 0x2A700..0x2B73F || cp in 0x2B740..0x2B81F || cp in 0x2B820..0x2CEAF ||
            cp in 0xF900..0xFAFF || cp in 0x2F800..0x2FA1F

    companion object {
        private val WHITESPACE = Regex("\\s+")

        /** Build from a `vocab.txt` — one token per line, id = line index. */
        fun fromVocabLines(
            lines: Sequence<String>,
            doLowerCase: Boolean = true,
            maxInputTokens: Int = 128,
        ): WordPieceTokenizer {
            val vocab = HashMap<String, Int>()
            var index = 0
            for (line in lines) {
                vocab[line.trimEnd('\r', '\n')] = index
                index++
            }
            return WordPieceTokenizer(vocab, doLowerCase = doLowerCase, maxInputTokens = maxInputTokens)
        }
    }
}
