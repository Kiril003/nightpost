package local.phantom.companion.core.voice.moonshine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves the REAL decode rules from the model's own `tokenizer.json`
 * (Replace ▁→" ", ByteFallback, Fuse, Strip leading space) — not the
 * fabricated per-codepoint mapping in the deleted `WhisperSttEngine`'s
 * `WhisperDecoder`, which was never the model's actual vocabulary for any
 * token id. Той рушій знято 12.09: він і токенів ніколи не отримував.
 */
class MoonshineVocabTest {

    /** id 3.. are ordinary vocab pieces here; 0/1/2 keep their real
     *  unk/bos/eos meaning so BOS/EOS-skipping is exercised too. */
    private fun vocabOf(vararg pieces: Pair<Int, String>): MoonshineVocab =
        MoonshineVocab(mapOf(0 to "<unk>", 1 to "<s>", 2 to "</s>") + pieces.toMap())

    @Test
    fun `plain pieces join with space markers turned into spaces`() {
        // "▁привіт" + "▁світ" -> "привіт світ" — first ▁ becomes a leading
        // space that Strip(1,0) then removes, matching the real pipeline.
        val vocab = vocabOf(10 to "▁привіт", 11 to "▁світ")
        assertEquals(
            "привіт світ",
            vocab.decode(listOf(MoonshineVocab.BOS_ID, 10, 11, MoonshineVocab.EOS_ID)),
        )
    }

    @Test
    fun `bos and eos never appear in the text`() {
        val vocab = vocabOf(10 to "гей")
        assertEquals("гей", vocab.decode(listOf(MoonshineVocab.BOS_ID, 10, MoonshineVocab.EOS_ID)))
    }

    @Test
    fun `byte-fallback tokens fuse into one multi-byte UTF-8 character`() {
        // 'ї' (U+0457) is 2 bytes in UTF-8: 0xD1 0x97. A vocab that only
        // knows the two raw bytes (never learned 'ї' as a merge) must still
        // decode them as ONE character, not two mojibake bytes.
        val bytes = "ї".toByteArray(Charsets.UTF_8)
        assertEquals(2, bytes.size)
        val hi = "<0x%02X>".format(bytes[0])
        val lo = "<0x%02X>".format(bytes[1])
        val vocab = vocabOf(20 to hi, 21 to lo)
        assertEquals("ї", vocab.decode(listOf(MoonshineVocab.BOS_ID, 20, 21, MoonshineVocab.EOS_ID)))
    }

    @Test
    fun `byte-fallback token next to a plain piece does not swallow it`() {
        val bytes = "ї".toByteArray(Charsets.UTF_8)
        val hi = "<0x%02X>".format(bytes[0])
        val lo = "<0x%02X>".format(bytes[1])
        val vocab = vocabOf(20 to hi, 21 to lo, 30 to "жак")
        assertEquals("їжак", vocab.decode(listOf(MoonshineVocab.BOS_ID, 20, 21, 30, MoonshineVocab.EOS_ID)))
    }

    @Test
    fun `unknown ids are skipped rather than crashing`() {
        val vocab = vocabOf(10 to "▁привіт")
        assertEquals("привіт", vocab.decode(listOf(MoonshineVocab.BOS_ID, 10, 999, MoonshineVocab.EOS_ID)))
    }

    @Test
    fun `real tokenizer json shape parses`() {
        val json = """
            {
              "model": {
                "type": "BPE",
                "vocab": {"<unk>": 0, "<s>": 1, "</s>": 2, "▁привіт": 10},
                "merges": []
              }
            }
        """.trimIndent()
        val vocab = MoonshineVocab.fromTokenizerJson(json)
        assertEquals("привіт", vocab.decode(listOf(1, 10, 2)))
    }
}
