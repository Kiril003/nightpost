package local.phantom.companion.core.voice.moonshine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import local.phantom.companion.core.voice.SttEngine
import local.phantom.companion.core.voice.SttNotice
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * moonshine-tiny-uk ONNX inference — encoder + autoregressive decoder,
 * greedy, no KV cache.
 *
 * Moonshine's encoder takes the raw 16 kHz waveform directly
 * (`preprocessor_config.json`: `Wav2Vec2FeatureExtractor`, `do_normalize:
 * false`, `feature_size: 1`) — no mel filterbank anywhere in this class or
 * the graph it calls. That is the one property that makes this engine buildable
 * where the Whisper engine never was: that one needed an 80-bin log-mel front
 * end which no code in this build has ever had. 12.09 його видалено — served
 * файл був лише енкодером (`input_features [1,80,3000]`), а рушій подавав
 * `input_values` і чекав токенів, тож повертав `null` завжди.
 *
 * No KV cache: every step re-feeds the WHOLE `input_ids` prefix to
 * `decoder_model_quantized.onnx` (the no-past export) and reads logits at the
 * last position. Quadratic in output length, fine for the short utterances
 * PTT produces (tens of tokens against a 6-layer/288-hidden model); the
 * `present.*` KV outputs the graph also emits are simply never read. Wiring
 * `decoder_with_past` is a real speed win left for later, not a correctness
 * requirement — see [maxTokens] for the loop's own ceiling regardless.
 *
 * Real ONNX graph contract (verified against the exported model, not assumed):
 *   encoder:  input_values [1,S] f32, attention_mask [1,S] i64
 *          -> last_hidden_state [1,H,288] f32
 *   decoder:  input_ids [1,L] i64, encoder_hidden_states [1,H,288] f32,
 *             encoder_attention_mask [1,H] i64  (H = encoder's OUTPUT length,
 *             not S — the two dims share a dim_param name in the exported
 *             graphs, but cross-attention masks the hidden states, not the
 *             raw samples)
 *          -> logits [1,L,32768] f32 (+ present.* cache, unread)
 *
 * Text comes out through [MoonshineVocab] — the model's own published
 * vocabulary, not a guessed codepoint table.
 */
class MoonshineSttEngine(
    private val modelDir: File,
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment(),
    private val maxTokens: Int = MAX_TOKENS,
) : SttEngine {

    override val displayName: String = "Moonshine · Tiny UK"

    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private var vocab: MoonshineVocab? = null

    /** Native sessions: closing mid-inference crashes the process, not just
     *  throws — the trap that made the deleted Whisper engine return null forever. */
    private val sessionLock = Any()
    @Volatile private var closed = false

    private val _partialResults = MutableSharedFlow<String>(extraBufferCapacity = 1)
    override val partialResults: SharedFlow<String> = _partialResults.asSharedFlow()

    private val _finalResults = MutableSharedFlow<String>(extraBufferCapacity = 16)

    private val _notices = MutableSharedFlow<SttNotice>(extraBufferCapacity = 8)
    override val notices: SharedFlow<SttNotice> = _notices.asSharedFlow()
    override val finalResults: SharedFlow<String> = _finalResults.asSharedFlow()

    private fun ensureLoaded() {
        synchronized(sessionLock) {
            check(!closed) { "Moonshine session released" }
            if (encoder != null && decoder != null && vocab != null) return
            encoder = env.createSession(File(modelDir, ENCODER_FILE).absolutePath, OrtSession.SessionOptions())
            decoder = env.createSession(File(modelDir, DECODER_FILE).absolutePath, OrtSession.SessionOptions())
            vocab = MoonshineVocab.fromTokenizerJson(File(modelDir, TOKENIZER_FILE).readText())
        }
    }

    /** Non-streaming — segmentation happens upstream. */
    override fun feed(chunk: ByteArray) = Unit

    override suspend fun transcribe(audio: ByteArray): String? = withContext(Dispatchers.Default) {
        if (!File(modelDir, ENCODER_FILE).exists() ||
            !File(modelDir, DECODER_FILE).exists() ||
            !File(modelDir, TOKENIZER_FILE).exists()
        ) return@withContext null

        val loaded = runCatching { ensureLoaded() }.onFailure {
            android.util.Log.e(TAG, "load failed", it)
        }.isSuccess
        if (!loaded) return@withContext null

        val v = vocab ?: return@withContext null
        val enc = encoder ?: return@withContext null
        val dec = decoder ?: return@withContext null

        // PCM-16 mono 16 kHz -> float32 [-1, 1], matching the corpus every
        // WER measurement of this model used.
        val samples = FloatArray(audio.size / 2)
        for (i in samples.indices) {
            val lo = audio[i * 2].toInt() and 0xFF
            val hi = audio[i * 2 + 1].toInt()
            samples[i] = ((hi shl 8) or lo).toShort() / 32768.0f
        }
        // The encoder's first conv (kernel 127, stride 64) needs at least one
        // full window — shorter input is silence/noise, not a real utterance.
        if (samples.size < 127) return@withContext null

        // ВІКНА, а не весь кліп.
        //
        // Виміряно 12.09 голосовою лабораторією на 300 кліпах CV17 uk: served
        // `decoder.onnx` ДЕТЕРМІНОВАНО падає на кліпах, довших за ~9,4 с
        // (прихований стан ≥ 389 кадрів) — ORT broadcast у
        // `/model/decoder/layers.0/encoder_attn/Add`. Це не збій стенда: 6 із
        // 300 кліпів, усі 9,38-10,1 с.
        //
        // Тут виняток ловився й давав `null`, тобто ДОВГА РЕПЛІКА ЗНИКАЛА
        // МОВЧКИ — найгірший різновид: що довше людина говорить, то менше
        // шансів бути почутою, і жодного знаку про це.
        //
        // Девʼять секунд, а не 9,4: запас на те, що межа виміряна по кадрах
        // прихованого стану, а не по семплах, і залежить від довжини згортки.
        // Зріз, а не `toList().chunked()`: той пакує КОЖЕН семпл у Float —
        // на 25-секундній репліці це 400 тисяч зайвих обʼєктів на кожне слово.
        val pieces = mutableListOf<String>()
        var lost = 0
        synchronized(sessionLock) {
            if (closed) return@withContext null
            var start = 0
            while (start < samples.size) {
                val end = minOf(start + WINDOW_SAMPLES, samples.size)
                val window = samples.copyOfRange(start, end)
                start = end
                if (window.size < 127) continue
                val text = runCatching { runInference(enc, dec, v, window) }
                    .onFailure { android.util.Log.e(TAG, "inference failed", it) }
                    .getOrNull()
                if (text.isNullOrBlank()) lost++ else pieces += text.trim()
            }
        }
        if (lost > 0) {
            _notices.tryEmit(
                if (pieces.isEmpty()) SttNotice.NothingRecognised
                else SttNotice.PartOfUtteranceLost,
            )
        }
        if (pieces.isEmpty()) null else pieces.joinToString(" ")
    }

    private fun runInference(
        enc: OrtSession,
        dec: OrtSession,
        v: MoonshineVocab,
        samples: FloatArray,
    ): String? {
        val inputValues = OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong()))
        val encAttnMask = OnnxTensor.createTensor(
            env, LongBuffer.wrap(LongArray(samples.size) { 1L }), longArrayOf(1, samples.size.toLong()),
        )
        val encoderHidden = try {
            enc.run(mapOf("input_values" to inputValues, "attention_mask" to encAttnMask)).use { r ->
                reWrapHidden(r[0].value)
            }
        } finally {
            inputValues.close()
            encAttnMask.close()
        }

        return encoderHidden.use { hidden ->
            val hiddenLen = hidden.info.shape[1]
            val crossMask = OnnxTensor.createTensor(
                env, LongBuffer.wrap(LongArray(hiddenLen.toInt()) { 1L }), longArrayOf(1, hiddenLen),
            )
            crossMask.use {
                val tokenIds = mutableListOf(MoonshineVocab.BOS_ID)
                var next = MoonshineVocab.BOS_ID
                var steps = 0
                while (next != MoonshineVocab.EOS_ID && steps < maxTokens) {
                    val idsTensor = OnnxTensor.createTensor(
                        env,
                        LongBuffer.wrap(tokenIds.map(Int::toLong).toLongArray()),
                        longArrayOf(1, tokenIds.size.toLong()),
                    )
                    val logits = try {
                        dec.run(
                            mapOf(
                                "input_ids" to idsTensor,
                                "encoder_hidden_states" to hidden,
                                "encoder_attention_mask" to crossMask,
                            )
                        ).use { r -> lastStepLogits(r[0].value) }
                    } finally {
                        idsTensor.close()
                    }
                    next = argmax(logits)
                    steps++
                    if (next != MoonshineVocab.EOS_ID) tokenIds.add(next)
                }
                v.decode(tokenIds).takeIf { it.isNotBlank() }
            }
        }
    }

    /** [1][T][288] nested encoder output -> an owned tensor the decoder can
     *  cross-attend against, re-flattened row-major like [OrtSupertonicSynth]
     *  does for its own 3-D outputs. */
    private fun reWrapHidden(value: Any?): OnnxTensor {
        @Suppress("UNCHECKED_CAST")
        val batch = (value as Array<Array<FloatArray>>)[0]
        val t = batch.size
        val d = if (t > 0) batch[0].size else 0
        val flat = FloatArray(t * d)
        for (i in 0 until t) System.arraycopy(batch[i], 0, flat, i * d, d)
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, t.toLong(), d.toLong()))
    }

    private fun lastStepLogits(value: Any?): FloatArray {
        @Suppress("UNCHECKED_CAST")
        val batch = (value as Array<Array<FloatArray>>)[0]
        return batch.last()
    }

    private fun argmax(logits: FloatArray): Int {
        var best = 0
        var bestVal = logits[0]
        for (i in 1 until logits.size) {
            if (logits[i] > bestVal) {
                bestVal = logits[i]
                best = i
            }
        }
        return best
    }

    override fun reset() = Unit

    override fun release() {
        synchronized(sessionLock) {
            if (closed) return
            closed = true
            runCatching { encoder?.close() }
            runCatching { decoder?.close() }
            encoder = null
            decoder = null
            vocab = null
        }
    }

    companion object {
        private const val TAG = "MoonshineSTT"
        const val ENCODER_FILE = "encoder.onnx"
        const val DECODER_FILE = "decoder.onnx"
        const val TOKENIZER_FILE = "tokenizer.json"

        val ALL_FILES = listOf(ENCODER_FILE, DECODER_FILE, TOKENIZER_FILE)

        /** Config's own `max_position_embeddings` is 194; this build stops
         *  well short of it — a PTT utterance that needed 128 output tokens
         *  is already a bug somewhere upstream, and the no-cache loop is
         *  quadratic, so the ceiling bounds worst-case latency too. */
        const val MAX_TOKENS = 128

        /**
         * Найдовше вікно, яке served-декодер витримує.
         *
         * Лабораторія 12.09: падіння детерміноване від ~9,4 с (прихований
         * стан ≥ 389 кадрів). Беремо 9 с — запас у чотири десятих секунди,
         * бо межа виміряна по КАДРАХ, а скільки семплів дає кадр, залежить
         * від згортки, не від нашої арифметики.
         *
         * 16 кГц × 9 с. Коли кузня викладе декодер із KV-кешем (RTF 0,074 →
         * 0,046 без втрати WER), обмеження зникне разом зі старим файлом —
         * і цю сталу треба буде прибрати, а не збільшити.
         */
        const val WINDOW_SECONDS = 9
        const val WINDOW_SAMPLES = 16_000 * WINDOW_SECONDS

        fun allPresent(dir: File): Boolean =
            ALL_FILES.all { File(dir, it).let { f -> f.exists() && f.length() > 0 } }

    }
}
