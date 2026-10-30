package local.phantom.companion.core.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.nio.FloatBuffer
import java.io.File

/**
 * Phase 2-B — YAMNet-based audio classifier.
 *
 * Implements [AudioClassifier] using ONNX Runtime. Ingests 16kHz mono PCM
 * and runs inference on 0.975s (15600 sample) windows.
 *
 * Visual cues (§6 Alien Elegance): when high-threat classes (Siren, Crying)
 * are detected, the system transitions to SENTINEL state with red-tinted
 * glass-glow.
 */
class YamnetAudioClassifier(
    private val modelPath: String,
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
) : AudioClassifier {

    private var session: OrtSession? = null
    private val windowSize = 15600
    private val buffer = FloatArray(windowSize)
    private var bufferPos = 0

    private val _results = MutableSharedFlow<ClassificationResult>(extraBufferCapacity = 16)
    override val results: SharedFlow<ClassificationResult> = _results.asSharedFlow()

    private fun ensureSession(): OrtSession? {
        if (!File(modelPath).exists()) return null
        val current = session
        if (current != null) return current
        
        return try {
            val options = OrtSession.SessionOptions()
            options.addNnapi() // Optimize for mobile NPU/GPU
            val s = env.createSession(modelPath, options)
            session = s
            s
        } catch (e: Exception) {
            android.util.Log.e("YamnetAudio", "Failed to init session", e)
            null
        }
    }

    override fun feed(chunk: ShortArray) {
        val sess = ensureSession() ?: return

        for (sample in chunk) {
            buffer[bufferPos++] = sample.toFloat() / 32768.0f
            if (bufferPos >= windowSize) {
                runInference(sess)
                // 50% overlap for smoother detection
                val half = windowSize / 2
                System.arraycopy(buffer, half, buffer, 0, half)
                bufferPos = half
            }
        }
    }

    /**
     * Rank of the model's waveform input, read from the graph rather than
     * assumed. Canonical YAMNet (the `waveform` input on the TF-Hub export)
     * takes a rank-1 `[num_samples]` tensor; some re-exports wrap it as
     * `[1, num_samples]`. Feeding the wrong rank fails to bind and every
     * inference throws — the classifier then silently emits nothing.
     */
    private fun inputRank(sess: OrtSession): Int {
        val name = sess.inputNames.firstOrNull() ?: return 1
        val info = sess.inputInfo[name]?.info as? ai.onnxruntime.TensorInfo
        return info?.shape?.size ?: 1
    }

    private fun runInference(sess: OrtSession) {
        try {
            val shape = if (inputRank(sess) >= 2) {
                longArrayOf(1, windowSize.toLong())
            } else {
                longArrayOf(windowSize.toLong())
            }
            val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(buffer), shape)

            tensor.use {
                val inputName = sess.inputNames.firstOrNull() ?: "waveform"
                val inputs = mapOf(inputName to it)

                sess.run(inputs).use { results ->
                    // YAMNet output_0 is per-frame scores: [frames, 521].
                    val outputValue = results[0].value as Array<FloatArray>
                    val scores = outputValue[0]

                    // Find top class
                    var maxIdx = -1
                    var maxScore = 0f
                    for (i in scores.indices) {
                        if (scores[i] > maxScore) {
                            maxScore = scores[i]
                            maxIdx = i
                        }
                    }

                    if (maxIdx != -1 && maxScore > 0.3f) {
                        val label = mapClassIdToLabel(maxIdx)
                        _results.tryEmit(ClassificationResult(label, maxScore))
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("YamnetAudio", "Inference failed", e)
        }
    }

    override fun reset() {
        bufferPos = 0
        buffer.fill(0f)
    }

    override fun release() {
        session?.close()
        session = null
    }

    /**
     * Map class ID to human-readable (and system-interpretable) labels.
     * Only the «threat» and «context» classes are mapped for Phase 2-B;
     * others fall back to generic indices.
     */
    private fun mapClassIdToLabel(id: Int): String = when (id) {
        0 -> "Speech"
        388 -> "Siren"
        389 -> "Emergency vehicle"
        391 -> "Police car (siren)"
        392 -> "Ambulance (siren)"
        393 -> "Fire engine (siren)"
        394 -> "Civil defense siren"
        432 -> "Crying"
        433 -> "Baby cry"
        434 -> "Screaming"
        494 -> "Doorbell"
        496 -> "Alarm clock"
        else -> "Class_$id"
    }
}
