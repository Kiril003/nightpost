package local.phantom.companion.core.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.LongBuffer
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Sentence embeddings on device. Handles two ONNX shapes with one code path:
 *  - a self-contained sentence graph that already pools + projects and outputs
 *    `(1, dim)` (the distiluse pipeline) — used as-is;
 *  - a feature-extraction graph that outputs `(1, seq, hidden)` (MiniLM) — mean
 *    pooled over the attention mask here.
 * `token_type_ids` is fed only when the loaded model declares that input, so the
 * same helper drives DistilBERT (no type ids) and BERT (needs them).
 *
 * Without a real [tokenizer] or ONNX model, [getEmbedding] returns a deterministic
 * hashing vector — a legible fallback for history search. [getInt8Embedding] does
 * NOT fall back: a KNN query must never be answered with a hash in the wrong space.
 */
class OnnxEmbeddingHelper(
    private val modelFile: File,
    private val tokenizer: SentenceTokenizer? = null,
    private val expectedDim: Int = 512,
) {
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputNames: Set<String> = emptySet()

    /** Dimension of the last real embedding produced, or [expectedDim] until one is. */
    @Volatile var dim: Int = expectedDim
        private set

    init {
        loadModel()
    }

    private fun loadModel() {
        if (modelFile.exists() && modelFile.length() > 0) {
            runCatching {
                env = OrtEnvironment.getEnvironment()
                session = env?.createSession(modelFile.absolutePath)
                inputNames = session?.inputNames ?: emptySet()
            }.onFailure { e ->
                android.util.Log.e("OnnxEmbeddingHelper", "Failed to load ONNX model, using fallback", e)
            }
        }
    }

    /** Normalized float embedding; L2 unit length. Real dim follows the model. */
    fun getEmbedding(text: String): FloatArray {
        if (session == null) {
            synchronized(this) { if (session == null) loadModel() }
        }
        val sess = session
        val environment = env
        val tok = tokenizer
        if (sess == null || environment == null || tok == null) return getFallbackEmbedding(text)

        return try {
            val encoded = tok.encode(text)
            val seq = encoded.inputIds.size
            val shape = longArrayOf(1, seq.toLong())
            val ids = OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(seq) { encoded.inputIds[it].toLong() }), shape)
            val mask = OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(seq) { encoded.attentionMask[it].toLong() }), shape)
            val inputs = HashMap<String, OnnxTensor>(3)
            inputs["input_ids"] = ids
            inputs["attention_mask"] = mask
            // Only BERT-style graphs declare this input; DistilBERT/distiluse do not.
            val types = if ("token_type_ids" in inputNames)
                OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(seq) { 0L }), shape).also { inputs["token_type_ids"] = it }
            else null

            try {
                sess.run(inputs).use { results ->
                    val out = (results[0] as OnnxTensor).value
                    val vec = when (out) {
                        // (1, dim): sentence graph already pooled + projected.
                        is Array<*> -> {
                            val first = out[0]
                            if (first is FloatArray) first.copyOf()
                            // (1, seq, hidden): mean-pool over the attention mask.
                            else meanPool(first as Array<FloatArray>, encoded.attentionMask)
                        }
                        else -> return getFallbackEmbedding(text)
                    }
                    dim = vec.size
                    normalize(vec)
                }
            } finally {
                ids.close(); mask.close(); types?.close()
            }
        } catch (e: Exception) {
            android.util.Log.w("OnnxEmbeddingHelper", "ONNX execution failed, falling back to hashing", e)
            getFallbackEmbedding(text)
        }
    }

    /**
     * int8 query vector for [OfflineVectorIndex] KNN: L2-normalize then
     * `round(v*127)` clamped to ±127 — the exact contract the offline pack was
     * baked with. Returns null when no real model/tokenizer is loaded, so a
     * hashing fallback is never mixed into the quantized space.
     */
    fun getInt8Embedding(text: String): IntArray? {
        if (session == null || tokenizer == null) return null
        return quantize(getEmbedding(text))
    }

    /** Same int8 contract as [getInt8Embedding], reusing an already-computed vector. */
    fun quantize(v: FloatArray): IntArray = quantize(v, 127)

    /** [quantize] against a pack baked with a scale other than 127. */
    fun quantize(v: FloatArray, scale: Int): IntArray =
        IntArray(v.size) { (v[it] * scale).roundToInt().coerceIn(-127, 127) }

    /** True once a real model+tokenizer are loaded — check after [getEmbedding] to know it wasn't the hash fallback. */
    fun isModelReady(): Boolean = session != null && tokenizer != null

    private fun meanPool(tokens: Array<FloatArray>, mask: IntArray): FloatArray {
        val d = tokens[0].size
        val acc = FloatArray(d)
        var active = 0
        for (t in tokens.indices) {
            if (t < mask.size && mask[t] == 1) {
                active++
                val row = tokens[t]
                for (i in 0 until d) acc[i] += row[i]
            }
        }
        if (active > 0) for (i in 0 until d) acc[i] /= active
        return acc
    }

    private fun normalize(vector: FloatArray): FloatArray {
        var sq = 0f
        for (v in vector) sq += v * v
        val norm = sqrt(sq.toDouble()).toFloat()
        if (norm > 0f) for (i in vector.indices) vector[i] /= norm
        return vector
    }

    /** Deterministic hashing vector — only for callers that tolerate a fallback. */
    fun getFallbackEmbedding(text: String): FloatArray {
        val vector = FloatArray(expectedDim)
        val words = text.lowercase()
            .replace(Regex("[^a-zа-яєіїґ0-9\\s]"), "")
            .split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) { vector[0] = 1f; return vector }
        for (word in words) {
            val h = abs(word.hashCode())
            vector[h % expectedDim] += 1f
            vector[abs(word.hashCode() * 31) % expectedDim] += 1f
            vector[abs(word.hashCode() * 109) % expectedDim] += 1f
            vector[abs(word.hashCode() * 541) % expectedDim] += 1f
        }
        return normalize(vector)
    }

    fun close() {
        runCatching { session?.close(); env?.close() }
    }
}
