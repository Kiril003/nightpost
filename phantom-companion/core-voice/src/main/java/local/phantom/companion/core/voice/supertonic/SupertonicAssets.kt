package local.phantom.companion.core.voice.supertonic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File

/**
 * On-disk layout and parsers for the Supertonic 3 model directory
 * (`filesDir/models/supertonic/`). Сім файлів кладе туди `ModelManager`,
 * звіривши sha256 з каталогу всередині підписаного APK. До 12.09 вони лежали
 * ще й в `assets/` і розпаковувались сюди на старті — 381 МБ у пакунку плюс
 * ті самі 381 МБ на диску; джерело тепер одне:
 *
 *  - `duration_predictor.onnx`, `text_encoder.onnx`,
 *    `vector_estimator.onnx`, `vocoder.onnx` — the four sessions
 *  - `tts.json` — runtime constants (sample rate, latent geometry)
 *  - `unicode_indexer.json` — flat 65 536-entry codepoint→id vocab
 *  - `voice.json` — one speaker preset (style_ttl / style_dp tensors)
 */
object SupertonicAssets {

    const val DIR = "supertonic"
    /** Optional per-style presets next to [VOICE_STYLE]; absence ≠ broken model. */
    const val STYLES_DIR = "voice_styles"
    const val DURATION_PREDICTOR = "duration_predictor.onnx"
    const val TEXT_ENCODER = "text_encoder.onnx"
    const val VECTOR_ESTIMATOR = "vector_estimator.onnx"
    const val VOCODER = "vocoder.onnx"
    const val CONFIG = "tts.json"
    const val UNICODE_INDEXER = "unicode_indexer.json"
    const val VOICE_STYLE = "voice.json"

    val ALL_FILES = listOf(
        DURATION_PREDICTOR, TEXT_ENCODER, VECTOR_ESTIMATOR, VOCODER,
        CONFIG, UNICODE_INDEXER, VOICE_STYLE,
    )

    fun allPresent(dir: File): Boolean =
        ALL_FILES.all { File(dir, it).let { f -> f.exists() && f.length() > 0 } }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The subset of `tts.json` the engine actually needs. Latent tensor
     * geometry follows the reference implementation:
     * `latentDim = ae.ldim × ttl.chunk_compress_factor` (24×6 = 144) and
     * one latent frame covers `ae.base_chunk_size × ttl.chunk_compress_factor`
     * (512×6 = 3072) output samples.
     */
    data class Config(
        val sampleRate: Int,
        val baseChunkSize: Int,
        val chunkCompressFactor: Int,
        val aeLatentDim: Int,
    ) {
        val samplesPerLatentFrame: Int get() = baseChunkSize * chunkCompressFactor
        val latentDim: Int get() = aeLatentDim * chunkCompressFactor
    }

    fun parseConfig(text: String): Config {
        val root = json.parseToJsonElement(text).jsonObject
        val ae = root.require("ae").jsonObject
        val ttl = root.require("ttl").jsonObject
        return Config(
            sampleRate = ae.require("sample_rate").jsonPrimitive.int,
            baseChunkSize = ae.require("base_chunk_size").jsonPrimitive.int,
            chunkCompressFactor = ttl.require("chunk_compress_factor").jsonPrimitive.int,
            aeLatentDim = ae.require("ldim").jsonPrimitive.int,
        )
    }

    /** `unicode_indexer.json` — a flat int array, -1 = unmapped. */
    fun parseIndexer(text: String): IntArray {
        val arr = json.parseToJsonElement(text).jsonArray
        return IntArray(arr.size) { i -> arr[i].jsonPrimitive.int }
    }

    /**
     * One style tensor from a voice preset: `dims` (e.g. `[1, 50, 256]`)
     * plus row-major flattened `data`.
     */
    data class StyleTensor(val dims: LongArray, val data: FloatArray)

    data class VoiceStyle(val ttl: StyleTensor, val dp: StyleTensor)

    fun parseVoiceStyle(text: String): VoiceStyle {
        val root = json.parseToJsonElement(text).jsonObject
        return VoiceStyle(
            ttl = parseStyleTensor(root.require("style_ttl").jsonObject),
            dp = parseStyleTensor(root.require("style_dp").jsonObject),
        )
    }

    private fun parseStyleTensor(obj: JsonObject): StyleTensor {
        val dims = obj.require("dims").jsonArray.map { it.jsonPrimitive.long }.toLongArray()
        val flat = ArrayList<Float>()
        flatten(obj.require("data"), flat)
        val expected = dims.fold(1L) { a, b -> a * b }
        require(flat.size.toLong() == expected) {
            "style tensor data size ${flat.size} does not match dims ${dims.toList()}"
        }
        return StyleTensor(dims, flat.toFloatArray())
    }

    private fun flatten(element: kotlinx.serialization.json.JsonElement, out: MutableList<Float>) {
        when (element) {
            is JsonArray -> element.forEach { flatten(it, out) }
            is JsonPrimitive -> out.add(element.float)
            else -> throw IllegalArgumentException("unexpected node in style data: $element")
        }
    }

    private fun JsonObject.require(key: String) =
        this[key] ?: throw IllegalArgumentException("missing \"$key\" in Supertonic asset JSON")
}
