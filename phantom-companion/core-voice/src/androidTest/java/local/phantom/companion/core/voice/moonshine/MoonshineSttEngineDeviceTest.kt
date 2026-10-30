package local.phantom.companion.core.voice.moonshine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * DEVICE PROOF, not a CI gate — never runs from `./scripts/verify.sh`
 * (that only ever calls `test` / `testFieldDebugUnitTest`, JVM unit tests;
 * this is `connectedAndroidTest`).
 *
 * ONNX Runtime's Android `.so` cannot load in a plain JVM unit test — it is
 * built for the device ABI, not the host — so the only way to prove
 * [MoonshineSttEngine] actually transcribes on real hardware is to run it
 * as instrumentation on a real device, which is what this is.
 *
 * Model files (`encoder.onnx`, `decoder.onnx`, `tokenizer.json`) and one
 * real 16 kHz mono PCM16 WAV are expected already pushed to
 * `<externalFilesDir>/moonshine_proof/` by whoever runs this — `adb push`,
 * not the download pipeline, so the proof is hermetic and does not depend
 * on the network being up during a gate run. Missing fixtures => the test
 * SKIPS (assumeTrue), never fails: a clean checkout with nothing pushed is
 * not a regression.
 */
@RunWith(AndroidJUnit4::class)
class MoonshineSttEngineDeviceTest {

    @Test
    fun transcribesARealUkrainianUtterance() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val proofDir = File(ctx.getExternalFilesDir(null), "moonshine_proof")
        val wav = File(proofDir, "sample.wav")

        val ready = proofDir.isDirectory &&
            File(proofDir, MoonshineSttEngine.ENCODER_FILE).exists() &&
            File(proofDir, MoonshineSttEngine.DECODER_FILE).exists() &&
            File(proofDir, MoonshineSttEngine.TOKENIZER_FILE).exists() &&
            wav.exists()
        assumeTrue(
            "no fixtures at $proofDir — push encoder.onnx/decoder.onnx/tokenizer.json/sample.wav to run this proof",
            ready,
        )

        val engine = MoonshineSttEngine(proofDir)
        try {
            val pcm = readWavPcm16(wav)
            val text = runBlocking { engine.transcribe(pcm) }
            Log.i("MoonshineProof", "transcribed: \"$text\"")
            assertTrue("engine returned null/blank — no real transcription happened", !text.isNullOrBlank())
        } finally {
            engine.release()
        }
    }

    /** Minimal RIFF/WAVE reader: locates the `data` chunk and returns its
     *  raw bytes. No format negotiation — the fixture is a known-good 16 kHz
     *  mono PCM16 WAV (the same format every WER measurement of this model
     *  used), and a mismatch should fail loudly, not silently resample. */
    private fun readWavPcm16(file: File): ByteArray {
        val bytes = file.readBytes()
        require(bytes.size > 44 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "not a RIFF file" }
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = (bytes[offset + 4].toInt() and 0xFF) or
                ((bytes[offset + 5].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 6].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 7].toInt() and 0xFF) shl 24)
            val dataStart = offset + 8
            if (id == "data") return bytes.copyOfRange(dataStart, minOf(dataStart + size, bytes.size))
            offset = dataStart + size + (size and 1) // chunks are word-aligned
        }
        error("no data chunk in ${file.path}")
    }
}
