package local.phantom.companion.core.voice.moonshine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Real id→text decode for the moonshine-tiny-uk tokenizer (`tokenizer.json`,
 * `model.type == "BPE"`, `byte_fallback: true`). Measured against the actual
 * file (huggingface.co/onnx-community/moonshine-tiny-uk-ONNX), not guessed:
 *
 *  - Pieces are literal multi-byte UTF-8 strings ("ст", "▁п", …) — this is
 *    SentencePiece-style BPE, not GPT2 byte-level BPE. A word boundary is the
 *    `▁` (U+2581) marker, not a Ġ.
 *  - 256 of the 32000 base ids are `<0xHH>` byte-fallback tokens — the escape
 *    hatch for codepoints no merge ever covered. Real UA output essentially
 *    never hits these (the vocab already carries whole Cyrillic pieces), but
 *    a multi-byte fallback char only decodes right if consecutive byte tokens
 *    are fused into one UTF-8 buffer before conversion, per the tokenizer's
 *    own `decoder: Sequence[Replace ▁→" ", ByteFallback, Fuse, Strip(1,0)]`.
 *
 * This exists because the deleted `WhisperSttEngine`'s hand-rolled decoder
 * mapped raw token ids straight through `Char` ranges
 * (`token in 1040..1103 -> token.toInt().toChar()`) — plausible-looking, and
 * wrong: BPE token ids are merge indices, not codepoints, for any of these
 * model families. That decoder was never going to produce real text; this
 * one decodes from the model's own published vocab.
 */
class MoonshineVocab(private val idToPiece: Map<Int, String>) {

    fun decode(tokenIds: List<Int>): String {
        val out = StringBuilder()
        val byteBuf = ArrayList<Byte>()
        fun flushBytes() {
            if (byteBuf.isEmpty()) return
            out.append(String(byteBuf.toByteArray(), Charsets.UTF_8))
            byteBuf.clear()
        }
        for (id in tokenIds) {
            if (id == BOS_ID || id == EOS_ID) continue
            val piece = idToPiece[id] ?: continue
            val byteVal = byteFallbackValue(piece)
            if (byteVal != null) {
                byteBuf.add(byteVal.toByte())
            } else {
                flushBytes()
                out.append(piece.replace(SPACE_MARK, " "))
            }
        }
        flushBytes()
        // Prepend normalizer inserts a leading ▁ for the very first word —
        // real per the tokenizer's own `Strip(content=" ", start=1, stop=0)`,
        // not a cosmetic trim.
        val text = out.toString()
        return if (text.startsWith(" ")) text.substring(1) else text
    }

    companion object {
        const val BOS_ID = 1
        const val EOS_ID = 2
        const val UNK_ID = 0

        private const val SPACE_MARK = "▁" // '▁'
        private val BYTE_FALLBACK_RE = Regex("^<0x([0-9A-Fa-f]{2})>$")

        private fun byteFallbackValue(piece: String): Int? =
            BYTE_FALLBACK_RE.matchEntire(piece)?.groupValues?.get(1)?.toInt(16)

        /** Parses the real `tokenizer.json` shipped alongside the ONNX
         *  weights — `model.vocab` is a piece→id object; this inverts it.
         *  The 61k-entry `merges` list is ignored: decode-only never needs
         *  it, and re-tokenizing text is not a thing an STT decoder does. */
        fun fromTokenizerJson(json: String): MoonshineVocab {
            val root = Json.parseToJsonElement(json).jsonObject
            val model = root.getValue("model").jsonObject
            val vocab = model.getValue("vocab").jsonObject
            val idToPiece = HashMap<Int, String>(vocab.size * 2)
            for ((piece, idElement) in vocab) {
                idToPiece[idElement.jsonPrimitive.int] = piece
            }
            return MoonshineVocab(idToPiece)
        }
    }
}
