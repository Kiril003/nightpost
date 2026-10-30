package local.phantom.companion.core.voice

import kotlinx.coroutines.flow.Flow

/**
 * Phase 2-B — audio classification contract.
 *
 * Implementations process raw PCM (typically 16kHz mono) and emit
 * [ClassificationResult]s. The primary implementation is [YamnetAudioClassifier].
 */
interface AudioClassifier {

    /** Feeds an audio chunk. Expected format depends on implementation. */
    fun feed(chunk: ShortArray)

    /** Flow of detected sound classes and their confidence scores. */
    val results: Flow<ClassificationResult>

    /** Reset internal buffers and state. */
    fun reset()

    /** Free native/model resources. */
    fun release()
}

/**
 * Result of a single classification window.
 */
data class ClassificationResult(
    val label: String,
    val score: Float,
    val timestampMs: Long = System.currentTimeMillis()
)
