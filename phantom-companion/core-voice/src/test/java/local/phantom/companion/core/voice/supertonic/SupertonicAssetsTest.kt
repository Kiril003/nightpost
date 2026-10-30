package local.phantom.companion.core.voice.supertonic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SupertonicAssetsTest {

    @Test
    fun `parses the tts json subset the engine needs`() {
        // Shape mirrors the real Supertone/supertonic-3 onnx/tts.json.
        val cfg = SupertonicAssets.parseConfig(
            """
            {
              "tts_version": "v1.7.3",
              "ttl": {"latent_dim": 24, "chunk_compress_factor": 6, "extra": {}},
              "ae": {"sample_rate": 44100, "base_chunk_size": 512, "ldim": 24, "n_delay": 0},
              "dp": {}
            }
            """.trimIndent()
        )
        assertEquals(44100, cfg.sampleRate)
        assertEquals(3072, cfg.samplesPerLatentFrame) // 512 × 6
        assertEquals(144, cfg.latentDim)              // 24 × 6
    }

    @Test
    fun `missing key is a loud failure not a silent default`() {
        assertThrows(IllegalArgumentException::class.java) {
            SupertonicAssets.parseConfig("""{"ttl": {"chunk_compress_factor": 6}}""")
        }
    }

    @Test
    fun `parses flat indexer with negative unmapped entries`() {
        val idx = SupertonicAssets.parseIndexer("[-1, -1, 0, 5, -1]")
        assertArrayEquals(intArrayOf(-1, -1, 0, 5, -1), idx)
    }

    @Test
    fun `parses voice style nested data against dims`() {
        val style = SupertonicAssets.parseVoiceStyle(
            """
            {
              "style_ttl": {"data": [[[1.0, 2.0], [3.0, 4.0], [5.0, 6.0]]], "dims": [1, 3, 2]},
              "style_dp":  {"data": [[[0.5], [0.25]]], "dims": [1, 2, 1]}
            }
            """.trimIndent()
        )
        assertArrayEquals(longArrayOf(1, 3, 2), style.ttl.dims)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), style.ttl.data, 0f)
        assertArrayEquals(longArrayOf(1, 2, 1), style.dp.dims)
        assertArrayEquals(floatArrayOf(0.5f, 0.25f), style.dp.data, 0f)
    }

    @Test
    fun `dims mismatch is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SupertonicAssets.parseVoiceStyle(
                """
                {
                  "style_ttl": {"data": [[[1.0, 2.0]]], "dims": [1, 3, 2]},
                  "style_dp":  {"data": [[[0.5]]], "dims": [1, 1, 1]}
                }
                """.trimIndent()
            )
        }
    }
}
