package local.phantom.companion.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catalogue invariants — the honesty rules that keep Settings → Голос
 * truthful (see the wired-flag history: 5 of 6 URLs were once dead).
 */
class ModelCatalogTest {

    /**
     * The hub states this number before the operator spends it. It lived only
     * in a KDoc comment until the hub needed to show it, and a number in a
     * comment cannot be shown to anyone.
     */
    @Test
    fun `the self-provisioned ear knows what it costs`() {
        // Точний розмір із роздачі (SHA256SUMS.txt), а не округлені 137 МіБ:
        // саме це число тепер звіряється при звантаженні.
        assertEquals(143_914_407L, ModelType.VOSK.downloadBytes)
    }

    @Test
    fun `multi-part models must not carry a single downloadUrl`() {
        ModelType.values().filter { it.parts.isNotEmpty() }.forEach {
            assertNull("${it.id}: parts and downloadUrl are mutually exclusive", it.downloadUrl)
        }
    }

    @Test
    fun `supertonic is wired and complete`() {
        val t = ModelType.SUPERTONIC
        assertTrue(t.wired)
        assertEquals(7, t.parts.size)
        val names = t.parts.map { it.relPath }
        // The exact file set OrtSupertonicSynth loads (SupertonicAssets.ALL_FILES)
        assertEquals(
            listOf(
                "duration_predictor.onnx", "text_encoder.onnx",
                "vector_estimator.onnx", "vocoder.onnx",
                "tts.json", "unicode_indexer.json", "voice.json",
            ),
            names,
        )
        // 12.09: сім файлів переїхали з huggingface у наш бакет — доведено на
        // A52 саме ЦІ байти, а чуже сховище вільне перезібрати файл під тим
        // самим іменем.
        //
        // Ключ відносний до КОРЕНЯ САЙТУ, тобто несе `models/` у собі:
        // MODELS_BASE_URL — це корінь (`https://try.phantom-os.dev`), а не
        // `…/models`. Ключі ж SHA256SUMS.txt відносні до самої теки `models/`,
        // бо маніфест у ній лежить. Дві системи відліку, і сплутати їх — це
        // або `/models/models/…`, або 404.
        assertTrue(t.parts.all { it.isOurs })
        assertTrue(t.parts.all { it.url.startsWith("models/supertonic/") })
        assertTrue("частина не сміє нести схему", t.parts.none { it.url.contains("://") })
    }

    @Test
    fun `the catalogue carries no on-device LLM`() {
        val llm = ModelType.values().filter { it.fileName.endsWith(".task") || it.id.contains("llm") || it.id.contains("gemma") }
        assertTrue("розум — ПК або хмара; моделі мови на телефоні не тягнемо: $llm", llm.isEmpty())
    }

    @Test
    fun `every single-file wired model still has a url or manual import`() {
        ModelType.values().filter { it.wired && it.parts.isEmpty() }.forEach {
            // downloadUrl may be null (manual import path) but never blank
            assertTrue(it.downloadUrl?.isNotBlank() ?: true)
        }
    }
}
