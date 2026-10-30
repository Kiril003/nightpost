package local.phantom.companion.core.voice.supertonic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.EnumSet
import java.util.Random

/**
 * Supertonic 3 inference over ONNX Runtime Mobile — four sessions, the
 * exact contract of the reference implementation
 * (github.com/supertone-inc/supertonic, cpp/helper.cpp):
 *
 *  1. `duration_predictor(text_ids, style_dp, text_mask) → duration` [s]
 *  2. `text_encoder(text_ids, style_ttl, text_mask) → text_emb`
 *  3. `vector_estimator(noisy_latent, text_emb, style_ttl, text_mask,
 *     latent_mask, total_step, current_step) → denoised_latent`,
 *     iterated [totalSteps] times from N(0,1) noise (flow matching)
 *  4. `vocoder(latent) → wav_tts` — mono float PCM at 44.1 kHz
 *
 * Latent geometry from tts.json: shape [1, ae.ldim × ccf, ceil(dur·sr /
 * (base_chunk_size × ccf))] = [1, 144, L].
 *
 * Everything here is blocking and thread-confined by convention: the
 * route drives it from a single synthesis executor.
 *
 * ## Execution providers
 *
 * [Accelerator] is a ladder, not a promise. Each session is opened with
 * the most aggressive provider that will actually *take* the graph, and
 * falls back on its own: a provider that rejects an op, or that isn't
 * present in this ORT build, throws at session creation and we drop a
 * rung. Which rung won is logged per session — never assume the NPU took
 * it, read [acceleratorFor].
 *
 * The honest expectation: flow matching is a memory-bound loop over
 * dynamic-shaped tensors, which is the shape of graph NNAPI partitions
 * worst. XNNPACK (SIMD CPU kernels, own threadpool) is the likelier win.
 * We try NNAPI first anyway because the only way to know is to measure on
 * the silicon in the operator's hand.
 */
class OrtSupertonicSynth(
    private val modelDir: File,
    private val totalSteps: Int = DEFAULT_STEPS,
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment(),
    /** Preference order; the first that accepts a graph wins it. */
    private val ladder: List<Accelerator> = DEFAULT_LADDER,
) : SupertonicSynth {

    /** Where a session's math actually ran. */
    enum class Accelerator { NNAPI, XNNPACK, CPU }

    private var sessions: Sessions? = null
    private var config: SupertonicAssets.Config? = null
    private var tokenizer: UnicodeTokenizer? = null
    private var voice: SupertonicAssets.VoiceStyle? = null
    private val random = Random()
    private val providers = mutableMapOf<String, Accelerator>()

    // Thread-confined to the synthesis executor, like everything else here.
    // Ten styles at most, ~100 KB of floats each.
    private val styleCache = mutableMapOf<String, SupertonicAssets.VoiceStyle>()

    private class Sessions(
        val durationPredictor: OrtSession,
        val textEncoder: OrtSession,
        val vectorEstimator: OrtSession,
        val vocoder: OrtSession,
    )

    override val sampleRate: Int
        get() = config?.sampleRate ?: error("Supertonic not loaded")

    override val isLoaded: Boolean get() = sessions != null

    /** Чисто перевірка наявності: нічого не розпаковує й нічого не відкриває. */
    override val canLoad: Boolean
        get() = sessions != null || SupertonicAssets.allPresent(modelDir)

    @Synchronized
    override fun load() {
        if (sessions != null) return
        require(SupertonicAssets.allPresent(modelDir)) {
            "Supertonic assets incomplete in $modelDir"
        }
        config = SupertonicAssets.parseConfig(File(modelDir, SupertonicAssets.CONFIG).readText())
        tokenizer = UnicodeTokenizer(
            SupertonicAssets.parseIndexer(File(modelDir, SupertonicAssets.UNICODE_INDEXER).readText())
        )
        voice = SupertonicAssets.parseVoiceStyle(File(modelDir, SupertonicAssets.VOICE_STYLE).readText())
        VoiceTuning.ensureLoaded(modelDir)

        sessions = Sessions(
            durationPredictor = open(SupertonicAssets.DURATION_PREDICTOR),
            textEncoder = open(SupertonicAssets.TEXT_ENCODER),
            vectorEstimator = open(SupertonicAssets.VECTOR_ESTIMATOR),
            vocoder = open(SupertonicAssets.VOCODER),
        )
    }

    /** Which provider ended up owning [modelFile], once loaded. */
    fun acceleratorFor(modelFile: String): Accelerator? = providers[modelFile]

    /**
     * Open one session on the best rung that will have it.
     *
     * Session creation is where a provider refuses a graph, so a plain
     * try/fallback IS the capability probe — there is no ahead-of-time
     * "can NNAPI run this" query worth trusting.
     */
    private fun open(name: String): OrtSession {
        val path = File(modelDir, name).absolutePath
        for (accel in ladder) {
            val opts = runCatching { optionsFor(accel) }.getOrNull() ?: continue
            val session = runCatching { env.createSession(path, opts) }.getOrElse { e ->
                opts.close()
                Log.w(TAG, "$name: $accel refused the graph (${e.message}); dropping a rung")
                null
            }
            if (session != null) {
                providers[name] = accel
                Log.i(TAG, "$name: running on $accel")
                return session
            }
        }
        error("no execution provider accepted $name")
    }

    private fun optionsFor(accel: Accelerator): OrtSession.SessionOptions {
        val opts = OrtSession.SessionOptions()
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        when (accel) {
            Accelerator.NNAPI -> {
                opts.setIntraOpNumThreads(INTRA_OP_THREADS)
                // CPU_DISABLED: if the NPU/GPU can't take a node, we do NOT
                // want NNAPI's own CPU fallback — it is slower than ORT's,
                // and it would hide the rejection behind a plausible-looking
                // success. Fail loudly, drop to XNNPACK.
                opts.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))
            }
            Accelerator.XNNPACK -> {
                // XNNPACK brings its own threadpool; ORT's docs are explicit
                // that intra-op must be 1 or the two pools fight each other.
                opts.setIntraOpNumThreads(1)
                opts.addXnnpack(mapOf("intra_op_num_threads" to INTRA_OP_THREADS.toString()))
            }
            Accelerator.CPU -> opts.setIntraOpNumThreads(INTRA_OP_THREADS)
        }
        return opts
    }

    /**
     * A style the operator picked but whose file never arrived must degrade
     * to the shipped preset, not to silence or a crash mid-sentence.
     */
    private fun styleFor(name: String): SupertonicAssets.VoiceStyle {
        val preset = voice!!
        if (name == VoiceTuning.DEFAULT_STYLE) return preset
        styleCache[name]?.let { return it }
        val file = File(modelDir, "${SupertonicAssets.STYLES_DIR}/$name.json")
        return runCatching { SupertonicAssets.parseVoiceStyle(file.readText()) }
            .onSuccess { styleCache[name] = it }
            .getOrElse { e ->
                Log.w(TAG, "style $name unavailable (${e.message}); falling back to ${VoiceTuning.DEFAULT_STYLE}")
                preset
            }
    }

    override fun synthesize(text: String, lang: String): FloatArray {
        val s = sessions ?: error("Supertonic not loaded")
        val cfg = config!!
        val tok = tokenizer!!
        // Read once per segment: a mid-utterance settings change may not
        // tear one phrase between two voices.
        val tuning = VoiceTuning.current
        val style = styleFor(tuning.style)

        val ids = tok.encode(text, lang)
        if (ids.isEmpty()) return FloatArray(0)
        val seqLen = ids.size

        val textIds = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, seqLen.toLong()))
        val textMask = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(FloatArray(seqLen) { 1f }), longArrayOf(1, 1, seqLen.toLong()),
        )
        val styleTtl = OnnxTensor.createTensor(env, FloatBuffer.wrap(style.ttl.data), style.ttl.dims)
        val styleDp = OnnxTensor.createTensor(env, FloatBuffer.wrap(style.dp.data), style.dp.dims)

        try {
            // 1 — duration (seconds), scaled for tempo. The whole downstream
            // geometry (latent length, vocoder canvas) derives from this one
            // number, so the phrase is *generated* at the faster pace rather
            // than resampled after the fact — no chipmunk, no artefacts.
            val predicted = s.durationPredictor.run(
                mapOf("text_ids" to textIds, "style_dp" to styleDp, "text_mask" to textMask)
            ).use { r -> (r[0].value as FloatArray)[0] }
            val durationSec = predicted * tuning.durationScale
            if (durationSec <= 0f) return FloatArray(0)

            // 2 — text embedding
            val textEmb = s.textEncoder.run(
                mapOf("text_ids" to textIds, "style_ttl" to styleTtl, "text_mask" to textMask)
            ).use { r -> tensorFrom3d(r[0].value) }

            // 3 — flow matching from N(0,1)
            val wavLen = (durationSec * cfg.sampleRate).toLong()
            val latentLen = ((wavLen + cfg.samplesPerLatentFrame - 1) / cfg.samplesPerLatentFrame).toInt()
            val latentDim = cfg.latentDim
            var latent = FloatArray(latentDim * latentLen) { random.nextGaussian().toFloat() }
            val latentShape = longArrayOf(1, latentDim.toLong(), latentLen.toLong())
            val latentMask = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(FloatArray(latentLen) { 1f }), longArrayOf(1, 1, latentLen.toLong()),
            )
            val totalStep = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(floatArrayOf(totalSteps.toFloat())), longArrayOf(1),
            )

            try {
                textEmb.use { emb ->
                    for (step in 0 until totalSteps) {
                        val noisy = OnnxTensor.createTensor(env, FloatBuffer.wrap(latent), latentShape)
                        val current = OnnxTensor.createTensor(
                            env, FloatBuffer.wrap(floatArrayOf(step.toFloat())), longArrayOf(1),
                        )
                        try {
                            s.vectorEstimator.run(
                                mapOf(
                                    "noisy_latent" to noisy,
                                    "text_emb" to emb,
                                    "style_ttl" to styleTtl,
                                    "text_mask" to textMask,
                                    "latent_mask" to latentMask,
                                    "total_step" to totalStep,
                                    "current_step" to current,
                                )
                            ).use { r -> latent = flatten3d(r[0].value) }
                        } finally {
                            noisy.close(); current.close()
                        }
                    }
                }

                // 4 — vocoder
                val latentTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(latent), latentShape)
                return latentTensor.use {
                    s.vocoder.run(mapOf("latent" to it)).use { r ->
                        val wav = flattenWav(r[0].value)
                        // Give duration predictor a small acoustic tail buffer (~23ms) so trailing consonants aren't cut
                        val targetLen = minOf(wav.size, (wavLen + 1024).toInt())
                        val trimmed = if (wav.size > targetLen) wav.copyOf(targetLen) else wav
                        applyAudioEnvelope(trimmed)
                        trimmed
                    }
                }
            } finally {
                latentMask.close(); totalStep.close()
            }
        } finally {
            textIds.close(); textMask.close(); styleTtl.close(); styleDp.close()
        }
    }

    /** [1][D][T] nested output → flat row-major FloatArray. */
    private fun flatten3d(value: Any?): FloatArray {
        @Suppress("UNCHECKED_CAST")
        val batch = (value as Array<Array<FloatArray>>)[0]
        val d = batch.size
        val t = if (d > 0) batch[0].size else 0
        val out = FloatArray(d * t)
        for (i in 0 until d) System.arraycopy(batch[i], 0, out, i * t, t)
        return out
    }

    /** Re-wrap a 3-D output as an owned OnnxTensor to feed the next session. */
    private fun tensorFrom3d(value: Any?): OnnxTensor {
        @Suppress("UNCHECKED_CAST")
        val batch = (value as Array<Array<FloatArray>>)[0]
        val d = batch.size.toLong()
        val t = if (batch.isNotEmpty()) batch[0].size.toLong() else 0L
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(flatten3d(value)), longArrayOf(1, d, t))
    }

    /** Vocoder emits [1][N] or [1][1][N] depending on export; accept both. */
    private fun flattenWav(value: Any?): FloatArray = when {
        value is Array<*> && value[0] is FloatArray -> value[0] as FloatArray
        value is Array<*> && value[0] is Array<*> -> (value[0] as Array<*>)[0] as FloatArray
        else -> throw IllegalStateException("unexpected vocoder output: ${value?.javaClass}")
    }

    /** Smooth micro fade-in and fade-out to eliminate clicks and pops at start/end of speech without swallowing phonemes. */
    private fun applyAudioEnvelope(samples: FloatArray) {
        val fadeLen = minOf(samples.size / 8, 96) // ~2.1ms at 44.1kHz
        if (fadeLen <= 0) return
        for (i in 0 until fadeLen) {
            val f = 0.5f - 0.5f * kotlin.math.cos((Math.PI * i / fadeLen).toFloat())
            samples[i] *= f
            val tailIdx = samples.size - 1 - i
            samples[tailIdx] *= f
        }
    }

    @Synchronized
    override fun close() {
        sessions?.let { s ->
            runCatching { s.durationPredictor.close() }
            runCatching { s.textEncoder.close() }
            runCatching { s.vectorEstimator.close() }
            runCatching { s.vocoder.close() }
        }
        sessions = null
        config = null
        tokenizer = null
        voice = null
        styleCache.clear()
        providers.clear()
    }

    companion object {
        private const val TAG = "SupertonicSynth"

        /**
         * Flow-matching steps — the QUALITY dial, not the speed dial.
         *
         * This was dropped to 5 to claw back latency and the operator heard
         * it immediately: the voice went audibly rough. Under-solving the
         * flow is not a free lunch, and the spectral check that said "still
         * speech" was measuring the wrong thing — it can tell noise from
         * voice, it cannot tell a good voice from a degraded one. Ears
         * outrank the FFT. Back to the reference 8 and it stays there;
         * tempo is bought with [durationScale] instead.
         */
        const val DEFAULT_STEPS = 8

        /**
         * ~0.85× — PHANTOM speaks briskly, without a single step of quality
         * traded away for it. The duration predictor decides how many
         * seconds a phrase becomes and the flow-matching loop fills exactly
         * that canvas, so scaling its output changes tempo at full acoustic
         * quality — the honest tempo knob, unlike cutting steps. The live
         * value is [VoiceTuning.current]; this is only its starting point.
         */
        const val DEFAULT_DURATION_SCALE = 0.85f

        /** Big cores only — the whole point is beating playback in real time. */
        const val INTRA_OP_THREADS = 4

        val DEFAULT_LADDER = listOf(Accelerator.NNAPI, Accelerator.XNNPACK, Accelerator.CPU)
    }
}
