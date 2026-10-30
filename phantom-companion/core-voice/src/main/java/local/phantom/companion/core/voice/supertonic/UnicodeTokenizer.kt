package local.phantom.companion.core.voice.supertonic

import java.text.Normalizer

/**
 * Supertonic 3 text front-end: text → int64 token ids.
 *
 * The model has NO phonemizer — this is what makes it wireable where
 * StyleTTS2 was not. The vocabulary is a flat 65 536-entry array
 * (`unicode_indexer.json`) mapping a Unicode codepoint directly to an
 * embedding id, with -1 for unmapped codepoints.
 *
 * The indexer was built over **NFKD-decomposed** text (the reference
 * Python impl calls `unicodedata.normalize("NFKD", text)`). This is
 * load-bearing for Ukrainian: raw «ї» (U+0457) and «й» (U+0439) map to
 * -1, but their NFKD forms («і»+U+0308, «и»+U+0306) are fully mapped.
 * The C++ reference port skips NFKD for Cyrillic and would mangle those
 * letters — we follow the Python reference, not the C++ one.
 *
 * Input text is wrapped in `<uk>…</uk>`-style language tags exactly as
 * the reference does; `<`, `>`, `/` and ASCII letters are all in-vocab.
 */
class UnicodeTokenizer(private val indexer: IntArray) {

    /**
     * Encode [text] for language [lang] (bare ISO code, e.g. "uk" — the
     * model knows nothing of BCP-47 regions). Unmapped codepoints are
     * dropped rather than crashing mid-utterance: after NFKD they are
     * rare (stray emoji fragments, exotic symbols) and silence is the
     * honest behaviour for a glyph the model cannot say.
     */
    fun encode(text: String, lang: String): LongArray {
        val cleaned = preprocess(text)
        if (cleaned.isBlank()) return LongArray(0)
        val tagged = "<$lang>$cleaned</$lang>"
        val decomposed = Normalizer.normalize(tagged, Normalizer.Form.NFKD)
        val ids = ArrayList<Long>(decomposed.length)
        var i = 0
        while (i < decomposed.length) {
            val cp = decomposed.codePointAt(i)
            i += Character.charCount(cp)
            if (cp < indexer.size) {
                val id = indexer[cp]
                if (id >= 0) ids.add(id.toLong())
            }
        }
        return ids.toLongArray()
    }

    private fun preprocess(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                // Supplementary plane (emoji et al.) cannot exist in a
                // 65 536-entry vocab — drop before NFKD scatters it.
                cp > 0xFFFF -> Unit
                else -> sb.append(SYMBOL_REPLACEMENTS[cp.toChar()] ?: cp.toChar().toString())
            }
        }
        // Collapse whitespace runs — the model was trained on single spaces.
        return sb.toString().replace(WHITESPACE_RUN, " ").trim()
    }

    private companion object {
        val WHITESPACE_RUN = Regex("\\s+")

        /** Typographic → plain, mirroring the reference preprocessing. */
        val SYMBOL_REPLACEMENTS: Map<Char, String> = mapOf(
            '–' to "-", '—' to "-", '―' to "-",
            '…' to "...",
            '“' to "\"", '”' to "\"", '„' to "\"", '«' to "\"", '»' to "\"",
            '‘' to "'", '’' to "'", '‚' to "'",
            ' ' to " ",
        )
    }
}
