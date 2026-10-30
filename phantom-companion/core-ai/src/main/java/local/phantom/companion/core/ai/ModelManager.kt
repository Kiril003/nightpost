package local.phantom.companion.core.ai

import io.ktor.client.HttpClient
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry of essential AI models for standalone PHANTOM operation.
 *
 * Every [downloadUrl] here is HEAD-verified to resolve. The previous
 * catalogue shipped five dead links (repos that never existed —
 * `Gemma4-E2B-IT`, `styletts2-ukrainian`, `onnx-community/all-MiniLM-L6-v2`
 * — plus two wrong file paths), so the operator could not install a single
 * model except Vosk.
 *
 * [wired] is the honesty flag: it says whether an engine in this build can
 * actually *consume* the downloaded file. Only ever set it true when a real
 * engine loads the file — a downloadable model with no engine behind it is
 * a 40 MB lie, and [download] refuses those outright rather than leaving
 * the operator wondering why nothing changed.
 */
/**
 * One file of a multi-file model. [relPath] is relative to the model's
 * [ModelType.fileName] directory.
 *
 * [url], що НЕ починається з `http`, — наш: це ключ під `MODELS_BASE_URL`, і
 * такий рядок ЗОБОВʼЯЗАНИЙ нести [sha256] та [bytes]. Ці двоє і є маніфест, і
 * живе він УСЕРЕДИНІ підписаного APK, а не поруч із файлами: маніфест, узятий
 * із того самого бакета, підписаний тим, хто той бакет тримає, — тобто саме
 * тією стороною, від якої сума й мала б боронити.
 *
 * Чужим URL (`http…`) суми лишаються порожні: ті байти не наші, а вигадана
 * сума — це брехня, яку ще й звіряють.
 */
data class ModelPart(
    val relPath: String,
    val url: String,
    val sha256: String? = null,
    val bytes: Long? = null,
) {
    val isOurs: Boolean get() = !url.startsWith("http")
}

private const val SUPERTONIC_DIR = "models/supertonic"
private const val MOONSHINE_DIR = "models/moonshine"


private const val MULTI_MINILM_BASE =
    "https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/resolve/main"

/**
 * Тека, в яку розпаковується ЧИННА модель слуху.
 *
 * Не косметика. Розпізнавач може почути лише те слово, що є в його
 * словнику; решту він підмінить найсхожішим, що знає. Попередня модель
 * (`vosk-model-small-uk-v3-nano`, словник 213 300 форм, зібрана в лютому
 * 2021) не має ні «дрон», ні «бпла», ні «трьохсотий», ні «позивний», ні
 * «азимут», ні «Бахмут» — жодного з них не можна вимовити так, щоб вона їх
 * видала. Чинна (`vosk-model-small-uk-v3-small`, 1 690 718 форм) має всі.
 * Акустична модель у них побайтово однакового розміру (am/final.mdl,
 * 22 163 394 Б) — різниця лише в мові, не в слуху.
 *
 * Назва живе тут, бо саме за нею [ModelManager.checkPresence] відрізняє
 * стару модель від нової. Без цього зміна URL не дала б нічого нікому, хто
 * вже має файли: тека `models/vosk` непорожня, «наявно» — і нову ніхто
 * ніколи не завантажив би.
 */
const val VOSK_MODEL_DIR = "vosk-model-small-uk-v3-small"

@Serializable
enum class ModelType(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String?,
    val infoUrl: String,
    /**
     * PHANTOM offers this model at all.
     *
     * Was «an engine in this build can load and run this file», and that
     * definition set a trap that sprang on 07.09: the flag was read as the
     * operator's protection, so raising tasks-genai to 0.10.27 — which made
     * both LLM bundles loadable — would have unlocked a 521 MB download by
     * itself, with nobody deciding anything.
     *
     * So false now has TWO grounds, and «the engine can load it» refutes
     * only the first:
     *   1. no engine in this build consumes the file;
     *   2. an engine runs it fine, and what it produces is not fit to be
     *      shown to a person asking for help (GEMMA, MICRO_LLM — measured
     *      07.09, see each).
     * Never flip one to true on a load alone. A model that loads and then
     * answers a shelter question in the wrong language is worse than no
     * model: silence sends the operator somewhere useful.
     */
    val wired: Boolean = true,
    /** Operator-facing reason shown when [wired] is false. */
    val unwiredNote: String? = null,
    /**
     * Multi-file models: when non-empty, [fileName] names a directory
     * and [downloadUrl] must be null — [ModelManager.download] fetches
     * every part into it instead.
     */
    val parts: List<ModelPart> = emptyList(),
    /**
     * Enrichment files, deliberately OUTSIDE [parts]: fetched alongside a
     * download and by [ModelManager.startFetchExtras], but never counted by
     * presence — a model whose core files are on disk must not flip to
     * «absent» (demoting the live voice route) over a 292 KB style preset.
     */
    val extraParts: List<ModelPart> = emptyList(),
    /**
     * Bytes over the wire, for screens that must state the cost before the
     * operator commits. 0 = not knowable ahead of time (unsuffixed bundles),
     * and a screen must then say nothing rather than «0 МіБ».
     */
    val downloadBytes: Long = 0L,
    /**
     * Сума однофайлового звантаження. Багатофайлові моделі носять її в
     * [ModelPart.sha256]; Vosk — один zip, і до 12.09 його ніхто не звіряв,
     * бо він приїжджав із чужого хоста, де звіряти не було з чим.
     */
    val downloadSha256: String? = null,
) {
    /** Consumed by [local.phantom.companion.core.voice.moonshine.MoonshineSttEngine]
     *  — the ear for devices that cannot hold Vosk.
     *
     *  12.09 that switch was closed: [hearingModelForRam] gives Vosk to a
     *  device with the memory for it and this to one without — Vosk is the
     *  more accurate of the two, and the choice is made on RSS, not taste.
     *  The numbers behind it were measured on CV17 uk test (300 clips, same
     *  corpus for both, so they are comparable): Vosk 21.9% WER at 827 MB RSS,
     *  this 28.5% at 248 MB. They disagree with the FLEURS figures below,
     *  which were a different corpus — both are kept, neither is the whole
     *  truth, and the memory column is what the choice actually turns on.
     *
     *  Measured 05.09 on one corpus (FLEURS uk_ua dev, 325 clips, 5852 words,
     *  gain-normalized) so the two numbers are comparable: Vosk 18.37% WER at
     *  137 MiB; moonshine-tiny-uk 14.37% WER (a quarter fewer word errors,
     *  relatively). 27M parameters is not a weight in itself — the real
     *  `Content-Length` is **~61.4 MiB**: encoder 29.5 + decoder 28.3 +
     *  tokenizer 3.6 MiB. The int8 encoder (7.7 MiB) measured smaller but
     *  does not run: onnxruntime-android 1.19.0 on the test device (Samsung
     *  A52, arm64) has no `ConvInteger` kernel, and the encoder's front end
     *  is convolutional — `ORT_NOT_IMPLEMENTED` at `/conv1/Conv_quant`. The
     *  fp32 encoder paired with the int8 decoder (pure attention/matmul,
     *  which quantizes fine) is the combination that actually loads.
     *
     *  Buildable where the Whisper path never was: `preprocessor_config.json`
     *  on the model card is `Wav2Vec2FeatureExtractor` with `feature_size: 1` — raw
     *  16 kHz waveform in, no mel filterbank anywhere. onnxruntime-android is
     *  already in this build (MiniLM embeddings, Supertonic, YAMNet all run
     *  on it), so no new runtime either. The one real gap was the BPE/byte-
     *  fallback decoder, which [MoonshineSttEngine] now does for real against
     *  the model's own `tokenizer.json` — see [MoonshineVocab], which exists
     *  because the deleted `WhisperSttEngine`'s decoder mapped token ids to
     *  Unicode codepoints directly, which is not what a BPE vocab is.
     *
     *  `wired = true` on real proof, not on faith: 06.09, this exact build
     *  transcribed a real FLEURS clip on the A52 —
     *  "Головна релігія – Молдови. Православне християнство." against a
     *  reference of "Головна релігія Молдови — православне християнство."
     *  (every word right; only punctuation differs) — via a
     *  `connectedAndroidTest`, logcat captured, not a human ear.
     *
     *  The model's own licence (registry tag "other") is moonshine.ai's
     *  Community License — not MIT, and not simply non-commercial either:
     *  free for research/personal use, and free commercially only under
     *  US$1M/yr revenue, which additionally requires registering with
     *  Moonshine AI and shipping a "Powered by Moonshine AI" notice; above
     *  that revenue the licence lapses and a new one is Moonshine AI's to
     *  grant or refuse. That is a product/business call for the owner, not
     *  a reason to mislabel a working engine as unwired — see [wired]'s own
     *  definition above. Read it before offering this as anyone's default. */
    MOONSHINE_UK(
        id = "moonshine_tiny_uk",
        displayName = "Moonshine · Tiny UK",
        fileName = "models/moonshine",
        downloadUrl = null,
        infoUrl = "https://huggingface.co/moonshine-ai/moonshine-tiny-uk",
        // Байти НАШІ, і саме ті, що 06.09 доведено на A52: у бакет покладено
        // копію з APK, а не свіжо взяте з HF. Чуже сховище вільне перезібрати
        // файл під тим самим іменем, і «слух працює» перестало б бути тим
        // виміром, який ми робили.
        parts = listOf(
            ModelPart(
                "encoder.onnx",
                "$MOONSHINE_DIR/encoder.onnx",
                sha256 = "19371e03f37f79b71918406c608b74f2a857a98647acc200152df7c6e1fb949e",
                bytes = 30_962_848L,
            ),
            ModelPart(
                "decoder.onnx",
                "$MOONSHINE_DIR/decoder.onnx",
                sha256 = "af91dc610b64e815d0e293da39d0d32a990c1af52cc728b839d56037dcf75088",
                bytes = 29_629_277L,
            ),
            ModelPart(
                "tokenizer.json",
                "$MOONSHINE_DIR/tokenizer.json",
                sha256 = "ad3a2ceb0e84e4da57451d86fa337f8116dcff5f5d106434f8aa0b0de89718b9",
                bytes = 3_761_751L,
            ),
        ),
        downloadBytes = 64_353_876L,
    ),

    /** Consumed by [VoskSttEngine] (org.vosk Model + Recognizer) — a real
     *  streaming UA recogniser. This is the STT that actually works.
     *
     *  137 МіБ звантаження, 416 МіБ на диску. Попередня «nano» коштувала
     *  74 МіБ / 127 МіБ і не знала слів, якими цим застосунком говорять —
     *  див. [VOSK_MODEL_DIR]. Модель НЕ їде в APK: її тягне
     *  [HearingProvisioner] при першому дотику до мікрофона. */
    VOSK(
        id = "vosk_ua",
        displayName = "Vosk · UA small",
        fileName = "models/vosk",
        // 12.09: до цього рядок вів на alphacephei.com, і реліз тихо тягнув
        // звідти 143,9 МБ на холодному старті. Тепер байти НАШІ й звірені сумою.
        downloadUrl = "models/vosk/$VOSK_MODEL_DIR.zip",
        infoUrl = "alphacephei.com/vosk/models",
        downloadSha256 = "ee05b6a8c796c53c61cd1c821d38e897e5bd67b4d691d8797ec8f9e5100d4247",
        downloadBytes = 143_914_407L,
    ),

    /** Consumed by [OrtSupertonicSynth] via SupertonicRoute — the streaming
     *  premium voice. Supertonic 3 needs no phonemizer (unicode-codepoint
     *  vocab covers uk via NFKD), which is why it is wireable where
     *  StyleTTS2 (IPA + separate stress model) never was. Seven files,
     *  ~398 MB; `voice.json` is the M1 preset — PHANTOM is male («він»). */
    SUPERTONIC(
        id = "supertonic_3",
        displayName = "Supertonic 3 · стрім-голос",
        fileName = "models/supertonic",
        downloadUrl = null,
        infoUrl = "https://huggingface.co/Supertone/supertonic-3",
        parts = listOf(
            ModelPart(
                "duration_predictor.onnx",
                "$SUPERTONIC_DIR/duration_predictor.onnx",
                sha256 = "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db",
                bytes = 3_700_147L,
            ),
            ModelPart(
                "text_encoder.onnx",
                "$SUPERTONIC_DIR/text_encoder.onnx",
                sha256 = "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff",
                bytes = 36_416_150L,
            ),
            ModelPart(
                "vector_estimator.onnx",
                "$SUPERTONIC_DIR/vector_estimator.onnx",
                sha256 = "883ac868ea0275ef0e991524dc64f16b3c0376efd7c320af6b53f5b780d7c61c",
                bytes = 256_534_781L,
            ),
            ModelPart(
                "vocoder.onnx",
                "$SUPERTONIC_DIR/vocoder.onnx",
                sha256 = "085de76dd8e8d5836d6ca66826601f615939218f90e519f70ee8a36ed2a4c4ba",
                bytes = 101_424_195L,
            ),
            ModelPart(
                "tts.json",
                "$SUPERTONIC_DIR/tts.json",
                sha256 = "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09",
                bytes = 8_253L,
            ),
            ModelPart(
                "unicode_indexer.json",
                "$SUPERTONIC_DIR/unicode_indexer.json",
                sha256 = "9bf7346e43883a81f8645c81224f786d43c5b57f3641f6e7671a7d6c493cb24f",
                bytes = 277_676L,
            ),
            ModelPart(
                "voice.json",
                "$SUPERTONIC_DIR/voice.json",
                sha256 = "e35604687f5d23694b8e91593a93eec0e4eca6c0b02bb8ed69139ab2ea6b0a5b",
                bytes = 291_748L,
            ),
        ),
        // M1 is already on disk as voice.json, so it is not fetched twice.
        // Ці девʼять лишаються чужими: у APK їх ніколи не було, звіряти нема з
        // чим, і вигадана сума була б гіршою за відсутню.
        downloadBytes = 398_652_950L,
    ),

    /**
     * Той самий Supertonic 3, з ОДНІЄЮ заміною: vector_estimator динамічно
     * квантований у беззнаковий int8 (QUInt8, onnxruntime.quantization,
     * зроблено й перевірено на phantom-oracle 14.09.2026). Виміряно:
     * RTF 1,07→0,66 (×1,6 швидше), памʼять під час синтезу 473→290 МБ, файл
     * 256,5→65,4 МБ; якість (MCD) у межах шумової підлоги — на слух не гірше.
     *
     * Сусідній QInt8-варіант (грудень 12.09) НЕ ВІДВАНТАЖЕНО НАВМИСНО: він не
     * вантажиться на ONNX Runtime 1.19.0 (та сама версія, що в APK) —
     * `NOT_IMPLEMENTED ConvInteger` на Conv_quant. Ліцензія — та сама, що на
     * fp32-оригінал (OpenRAIL-M, похідна квантизована модель, ті самі умови).
     *
     * Решта шести файлів БАЙТ-У-БАЙТ ті самі, що в [SUPERTONIC] — окрема
     * копія тут означала б якщо людина вже має fp32-голос, качати їх удруге.
     */
    SUPERTONIC_LITE(
        id = "supertonic_3_lite",
        displayName = "Supertonic 3 · економний голос (×1,6 швидше, менше памʼяті)",
        fileName = "models/supertonic-int8u8",
        downloadUrl = null,
        infoUrl = "https://huggingface.co/Supertone/supertonic-3",
        parts = listOf(
            ModelPart(
                "duration_predictor.onnx",
                "$SUPERTONIC_DIR/duration_predictor.onnx",
                sha256 = "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db",
                bytes = 3_700_147L,
            ),
            ModelPart(
                "text_encoder.onnx",
                "$SUPERTONIC_DIR/text_encoder.onnx",
                sha256 = "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff",
                bytes = 36_416_150L,
            ),
            ModelPart(
                "vector_estimator.onnx",
                "models/supertonic-int8u8/vector_estimator.onnx",
                sha256 = "061d47fd056c60991661b076c914e895a074640b04314ff32cdbcd21326fb4c4",
                bytes = 65_360_879L,
            ),
            ModelPart(
                "vocoder.onnx",
                "$SUPERTONIC_DIR/vocoder.onnx",
                sha256 = "085de76dd8e8d5836d6ca66826601f615939218f90e519f70ee8a36ed2a4c4ba",
                bytes = 101_424_195L,
            ),
            ModelPart(
                "tts.json",
                "$SUPERTONIC_DIR/tts.json",
                sha256 = "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09",
                bytes = 8_253L,
            ),
            ModelPart(
                "unicode_indexer.json",
                "$SUPERTONIC_DIR/unicode_indexer.json",
                sha256 = "9bf7346e43883a81f8645c81224f786d43c5b57f3641f6e7671a7d6c493cb24f",
                bytes = 277_676L,
            ),
            ModelPart(
                "voice.json",
                "$SUPERTONIC_DIR/voice.json",
                sha256 = "e35604687f5d23694b8e91593a93eec0e4eca6c0b02bb8ed69139ab2ea6b0a5b",
                bytes = 291_748L,
            ),
        ),
        downloadBytes = 207_479_048L,
    ),

    /** Consumed by [YamnetAudioClassifier] — real ONNX inference on a
     *  `waveform` input. */
    YAMNET(
        id = "yamnet",
        displayName = "YAMNet · Audio Classify",
        fileName = "models/yamnet.onnx",
        // 12.09: байти переїхали в нашу роздачу, як Moonshine і Supertonic.
        downloadUrl = "models/yamnet/yamnet.onnx",
        infoUrl = "huggingface.co/zeropointnine/yamnet-onnx",
        downloadSha256 = "1510041dce24a2e9e84ec546807ac408ae496da6d1ed41bc3ccba649623f8e19",
        downloadBytes = 16_093_603L,
    ),

    /** Consumed by OnnxEmbeddingHelper. MiniLM (384-dim, English); its WordPiece
     *  vocab cannot see Ukrainian at all, which is what [EMBEDDING_MULTILINGUAL]
     *  is for — that one is 384-dim too, not 512. */
    EMBEDDING(
        id = "all_minilm_l6_v2",
        displayName = "MiniLM · Embedding L6-v2",
        fileName = "models/minilm.onnx",
        downloadUrl = "models/embedding/all-MiniLM-L6-v2.onnx",
        infoUrl = "huggingface.co/sentence-transformers/all-MiniLM-L6-v2",
        downloadSha256 = "6fd5d72fe4589f189f8ebc006442dbb529bb7ce38f8082112682524616046452",
        downloadBytes = 90_405_214L,
    ),

    /** Consumed by a second `OnnxEmbeddingHelper` instance the app wires up for
     *  needs-search — see `AppContainer`. Ships its own `tokenizer.json` because
     *  it is SentencePiece Unigram ([UnigramTokenizer]), not WordPiece. */
    EMBEDDING_MULTILINGUAL(
        id = "paraphrase_multilingual_minilm_l12_v2",
        displayName = "MiniLM · Multilingual L12 (int8) — пошук за потребою",
        fileName = "models/minilm-multi",
        downloadUrl = null,
        infoUrl = "https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2",
        parts = listOf(
            ModelPart("onnx/model_qint8_arm64.onnx", "$MULTI_MINILM_BASE/onnx/model_qint8_arm64.onnx"),
            ModelPart("tokenizer.json", "$MULTI_MINILM_BASE/tokenizer.json"),
        ),
        downloadBytes = 127_493_916L,
    );
}

/**
 * Чи має застосунок ЩО завантажити для цієї моделі.
 *
 * Не `downloadUrl != null`: багатофайлові моделі (Supertonic, Moonshine,
 * багатомовний MiniLM) тримають адреси в [ModelType.parts], а `downloadUrl` у
 * них порожній — і Налаштування, питаючи саме `downloadUrl`, не малювали
 * кнопки завантаження РІВНО для тих моделей, які 12.09 виїхали з пакунка.
 * Шлях існував, тест на нього був, а дотягнутись до нього з екрана було
 * неможливо.
 */
val ModelType.isFetchable: Boolean
    get() = downloadUrl != null || parts.isNotEmpty()

@Serializable
data class ModelState(
    val type: ModelType,
    val isPresent: Boolean = false,
    val isDownloading: Boolean = false,
    val progress: Float = 0f, // 0.0 to 1.0
    val error: String? = null
)

/**
 * QH3 — explicit lifecycle event for the download pipeline.
 *
 * The original [ModelState] StateFlow conflates "I just finished" with
 * "I finished an hour ago" — every consumer who subscribes after the
 * fact sees the latest state and can't distinguish a fresh completion
 * from a stale one. AppContainer needs the *transition* to fire
 * VoiceManager.refreshModels / RoutedTts.refreshPremiumStatus exactly
 * once per successful download, so we surface it as an event flow.
 */
sealed interface DownloadEvent {
    val type: ModelType

    data class Started(override val type: ModelType) : DownloadEvent
    data class Progress(override val type: ModelType, val ratio: Float) : DownloadEvent
    data class Completed(override val type: ModelType) : DownloadEvent
    data class Failed(override val type: ModelType, val message: String) : DownloadEvent
}

/**
 * Autonomous model manager responsible for lazy asset delivery.
 * Moves PHANTOM from "demo/cloud" to "standalone OS".
 */
class ModelManager(
    private val client: HttpClient,
    private val rootDir: File,
    private val hfTokenProvider: () -> String? = { null },
    /**
     * База, під якою лежать НАШІ копії моделей (`MODELS_BASE_URL`). Порожня —
     * це чесне «звідки качати, не сказано»: [download] відмовляє вголос. Саме
     * тому тут немає запасної адреси на huggingface — мовчазний відкат туди
     * і був тим, що 05.09 тягнуло ~398 МБ назовні без жодного ключа.
     */
    private val modelsBaseUrl: () -> String = { "" },
    /**
     * Звірка сум після завантаження. Стандарт — увімкнено, і продакшен його
     * НЕ перекриває: `ModelHostingTest` стежить, щоб у `AppContainer` цього
     * імені не було взагалі.
     *
     * Шов існує для тестів, які міряють НЕ цілісність (час життя переносу,
     * скасування, де-дуп подвійного дотику): вони віддають синтетичні байти,
     * і зійтися з сумою 256-мегабайтного файла ті байти не можуть за
     * побудовою. Вимкнути звірку в збірці — означає лишитись без єдиного, що
     * відрізняє наш файл від того, що віддав бакет.
     */
    private val verifyDownloads: Boolean = true,
    /**
     * The scope every transfer actually runs in — application-lifetime,
     * never a caller's. A 400 MB Supertonic download must outlive the
     * screen that started it: bound to `rememberCoroutineScope()` it dies
     * the moment the operator leaves Settings, and to a `viewModelScope`
     * it dies with the ViewModel. Both are UI lifetimes; a download is not
     * a UI concern. [startDownload] is the only entry point the UI gets,
     * and it cannot bind the work to the caller.
     *
     * SupervisorJob: one failed model must not cancel the others.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /** In-flight transfers, keyed by model — the de-dupe for double taps. */
    private val jobs = ConcurrentHashMap<ModelType, Job>()

    private val _states = MutableStateFlow<Map<ModelType, ModelState>>(
        ModelType.values().associateWith { type ->
            ModelState(type = type, isPresent = checkPresence(type))
        }
    )
    val states: StateFlow<Map<ModelType, ModelState>> = _states.asStateFlow()

    // QH3 — explicit download lifecycle for one-shot wiring (refresh
    // VoiceManager / promote RoutedTts on Completed). extraBufferCapacity
    // chosen so a slow consumer doesn't block the IO download loop;
    // replay=0 because Completed is a transition, not a state.
    private val _downloadEvents = MutableSharedFlow<DownloadEvent>(
        replay = 0,
        extraBufferCapacity = 16,
    )
    val downloadEvents: SharedFlow<DownloadEvent> = _downloadEvents.asSharedFlow()

    // ── Fire-and-forget entry points (the only ones the UI may call) ──
    //
    // These are deliberately NOT suspend: a caller cannot `launch` them in
    // its own scope even by accident, so no composition, ViewModel or
    // Activity can ever own a transfer again. The suspend variants stay
    // below for tests and for callers that genuinely want to await.

    /** Start (or join) a download. Returns the running [Job]. */
    fun startDownload(type: ModelType): Job = launchExclusive(type) { download(type) }

    /**
     * Start a delete on the manager's scope.
     *
     * Cancels any in-flight download for [type] first. Without this, tapping
     * «видалити» while an unattended fetch (Supertonic at boot, Vosk on the
     * first mic touch) is already running silently JOINED that running job
     * instead of deleting anything — [launchExclusive] treats a live entry in
     * [jobs] as work to await, not as work to replace. The one control an
     * operator has to stop a download he never asked for must actually stop it.
     */
    fun startDelete(type: ModelType): Job {
        cancelDownload(type)
        return launchExclusive(type) { delete(type) }
    }

    /**
     * Start an import from an operator-picked stream. Takes ownership of
     * [input] and closes it — the picker's Activity may be gone long
     * before a multi-hundred-MB copy finishes.
     */
    fun startImport(type: ModelType, input: InputStream): Job = launchExclusive(type) {
        // Ті самі ворота чесності, що й у [download].
        //
        // Вони стояли лише на звантаженні. Імпорт їх обходив: він копіював
        // сотні мегабайтів, ставив `isPresent = true` — і цим вмикав у вітрині
        // Налаштувань зелене «розум на пристрої» для рушія, який
        // Тобто людина витрачала місце й час, а вітрина після цього брехала.
        if (!type.wired) {
            input.close()
            refuseUnwired(type)
            return@launchExclusive
        }
        input.use { importFromStream(type, it) }
    }

    /**
     * Сказати прямо, що жоден рушій цієї збірки не візьме цей файл.
     *
     * Відмова МУСИТЬ поїхати ще й подією — див. довгий запис у [download]:
     * слухач, який веде свій автомат станів по [downloadEvents], без події
     * лишається у «прокидаюсь · 0%» до кінця життя процесу.
     */
    private fun refuseUnwired(type: ModelType) {
        val reason = type.unwiredNote ?: "рушій для цієї моделі ще не зібраний"
        _states.update { it + (type to ModelState(type = type, error = reason)) }
        _downloadEvents.tryEmit(DownloadEvent.Failed(type, reason))
    }

    /**
     * Fetch missing [ModelType.extraParts]. Keyed like a download, so a tap
     * while the full model is still transferring joins that job — which
     * fetches the extras at its end anyway — instead of racing it onto the
     * same files.
     */
    fun startFetchExtras(type: ModelType): Job = launchExclusive(type) { fetchMissingExtras(type) }

    /** Abort an in-flight transfer for [type]; no-op if none is running. */
    fun cancelDownload(type: ModelType) {
        jobs.remove(type)?.cancel()
    }

    /**
     * One transfer per model at a time: a second tap joins the first
     * rather than racing it onto the same file handles.
     */
    private fun launchExclusive(type: ModelType, block: suspend () -> Unit): Job {
        // compute() is atomic, so two taps landing on different threads
        // cannot both win the check and race onto the same file handles.
        // The winner is created LAZY and started outside the map lock.
        var created: Job? = null
        val job = jobs.compute(type) { _, existing ->
            existing?.takeIf { it.isActive }
                ?: scope.launch(start = CoroutineStart.LAZY) { block() }.also { created = it }
        }!!
        created?.let { fresh ->
            fresh.invokeOnCompletion { jobs.remove(type, fresh) }
            fresh.start()
        }
        return job
    }

    private fun checkPresence(type: ModelType): Boolean {
        val file = File(rootDir, type.fileName)
        return when {
            type.parts.isNotEmpty() -> type.parts.all { part ->
                File(file, part.relPath).let { it.exists() && it.length() > 0 }
            }
            // Тека з файлами ще не означає модель. Налаштування питали «чи
            // непорожньо», а розпізнавання — «чи є всередині am/», і на
            // обірваному розпакуванні відповіді розходились: екран писав
            // «завантажено», рушій мовчки падав у FakeSttEngine. Питання
            // тепер одне на обох.
            type == ModelType.VOSK -> resolveVoskModelDir() != null
            else -> file.exists() && file.length() > 0
        }
    }

    /** Re-evaluates presence of [type] on disk and updates [states]. */
    fun recheck(type: ModelType) {
        _states.update { it + (type to ModelState(type, isPresent = checkPresence(type))) }
    }

    /**
     * Delete a model file/directory from local storage.
     */
    suspend fun delete(type: ModelType) = withContext(Dispatchers.IO) {
        val file = File(rootDir, type.fileName)
        if (file.exists()) {
            if (file.isDirectory) {
                file.deleteRecursively()
            } else {
                file.delete()
            }
        }
        _states.update { it + (type to ModelState(type, isPresent = false)) }
    }

    /**
     * Start downloading a model.
     */
    suspend fun download(type: ModelType) = withContext(Dispatchers.IO) {
        if (_states.value[type]?.isDownloading == true) return@withContext

        // Honesty gate: no engine in this build can run this file, so a
        // download would consume the operator's bandwidth and change
        // nothing. Say that plainly instead of faking progress.
        if (!type.wired) {
            // Відмова МУСИТЬ поїхати ще й подією [DownloadEvent.Failed] — і це не
            // охайність, а суть вади. Ці ворота виходять ДО першого tryEmit(Started)
            // нижче, тож раніше відмова не породжувала жодної події. Кожен слухач,
            // який веде свій автомат станів по downloadEvents, не чув нічого:
            // [AiBootstrapper] ставив waking = true й Waking(0f), кликав це,
            // діставав мовчазну відмову — і лишався на «прокидаюсь · 0%» до кінця
            // життя процесу, із засувкою waking, тож кожна наступна спроба теж
            // виходила достроково. Стан, який не вміє впасти, гірший за помилку.
            refuseUnwired(type)
            return@withContext
        }

        // Через ту саму дорогу, що й частини: інакше відносний ключ пішов би в
        // мережу як адреса, а порожня MODELS_BASE_URL нічого б не спинила.
        //
        // `runCatching`, а не голий виклик: «сховище не налаштоване» — це
        // ВІДМОВА, і вона мусить лягти в стан і подію так само, як 404. Кинутий
        // звідси виняток не доходив до `DownloadEvent.Failed`, і той, хто чекав
        // події (AiBootstrapper), лишався у Waking назавжди — стан, який не
        // вміє впасти, знову гірший за помилку.
        val relKey = type.downloadUrl
        val url = relKey?.let {
            runCatching {
                resolvePartUrl(ModelPart(type.fileName, it, type.downloadSha256, type.downloadBytes.takeIf { b -> b > 0 }))
            }.getOrNull()
        }
        if (relKey != null && url == null) {
            val reason = "сховище моделей не налаштоване — завантажити нема звідки"
            _states.update { it + (type to ModelState(type = type, error = reason)) }
            _downloadEvents.tryEmit(DownloadEvent.Failed(type, reason))
            return@withContext
        }
        if (url == null && type.parts.isEmpty()) {
            _states.update {
                it + (type to ModelState(
                    type = type,
                    error = "Цю модель треба імпортувати з файлу — тапни ⤴ або відкрий ℹ для інструкції",
                ))
            }
            return@withContext
        }

        _states.update { it + (type to ModelState(type, isDownloading = true)) }
        _downloadEvents.tryEmit(DownloadEvent.Started(type))

        try {
            val modelsDir = File(rootDir, "models")
            if (!modelsDir.exists()) modelsDir.mkdirs()

            val file = File(rootDir, type.fileName)

            if (type.parts.isNotEmpty()) {
                // Multi-file model: fetch each part into the model dir.
                // Parts already on disk are skipped, so a download that
                // died at part 5/7 resumes at part 5, not from zero —
                // this matters at 257 MB for the vector estimator.
                // Progress is per-part (completed + current ratio) /
                // count: uneven across unequal files, but monotonic.
                file.mkdirs()
                type.parts.forEachIndexed { index, part ->
                    val target = File(file, part.relPath)
                    if (target.exists() && target.length() > 0) return@forEachIndexed
                    val temp = File(file, part.relPath + ".part")
                    fetchTo(resolvePartUrl(part), temp) { ratio ->
                        val overall = (index + ratio) / type.parts.size
                        _states.update { it + (type to it[type]!!.copy(progress = overall)) }
                        _downloadEvents.tryEmit(DownloadEvent.Progress(type, overall))
                    }
                    // Звірка ДО перейменування. Обірваний `.part`, перейменований
                    // у ціль, читається як «встановлено» назавжди: присутність
                    // тут — це exists() && length() > 0, а не вміст.
                    verifyPart(part, temp)
                    if (!temp.renameTo(target)) {
                        temp.copyTo(target, overwrite = true)
                        temp.delete()
                    }
                }
            } else {
                val tempFile = if (type == ModelType.VOSK) File(rootDir, "models/${type.id}.zip") else file
                tempFile.parentFile?.mkdirs()
                fetchTo(url!!, tempFile) { ratio ->
                    _states.update { it + (type to it[type]!!.copy(progress = ratio)) }
                    _downloadEvents.tryEmit(DownloadEvent.Progress(type, ratio))
                }
                // Звірка ДО розпакування: зіпсутий або підмінений архів не
                // мусить дійти до диска моделлю.
                verifyPart(
                    ModelPart(type.fileName, type.downloadUrl!!, type.downloadSha256, type.downloadBytes.takeIf { it > 0 }),
                    tempFile,
                )
                if (type == ModelType.VOSK) {
                    // Розклад попередньої моделі вже недосяжний для
                    // resolveVoskModelDir(), але 127 МіБ на диску займає. Іде
                    // ПЕРЕД розпакуванням: інакше пік — обидві моделі разом.
                    staleVoskDirs().forEach { it.deleteRecursively() }
                    unzip(tempFile, file)
                    tempFile.delete()
                }
            }

            fetchMissingExtras(type)

            android.util.Log.i("ModelManager", "Download complete: ${type.id}")
            _states.update { it + (type to ModelState(type, isPresent = checkPresence(type), progress = 1f)) }
            _downloadEvents.tryEmit(DownloadEvent.Completed(type))
        } catch (e: CancellationException) {
            // Not a failure — the operator aborted, or the process is
            // going down. Clear the spinner (parts already on disk stay,
            // so a retry resumes) and let cancellation propagate: swallowing
            // it here would leave the parent job un-cancellable.
            android.util.Log.i("ModelManager", "Download cancelled: ${type.id}")
            _states.update { it + (type to ModelState(type, isPresent = checkPresence(type))) }
            throw e
        } catch (e: Exception) {
            android.util.Log.e("ModelManager", "Download failed for ${type.id}", e)
            val raw = e.message ?: e::class.simpleName ?: "невідома помилка"
            val helpful = when {
                "401" in raw || "403" in raw || "Unauthorized" in raw || "Forbidden" in raw ->
                    "Доступ заборонений — модель gated на HuggingFace. Додай токен у Налаштуваннях або імпортуй файл вручну (⤴)"
                "404" in raw -> "Файл не знайдено за URL. Відкрий ℹ і скачай вручну → ⤴"
                "Unable to resolve host" in raw || "ConnectException" in raw -> "Немає мережі"
                else -> raw
            }
            _states.update { it + (type to it[type]!!.copy(
                isDownloading = false,
                error = helpful,
            )) }
            _downloadEvents.tryEmit(DownloadEvent.Failed(type, helpful))
        }
    }

    /**
     * Extras never fail a download: a dead style URL must not discard a
     * 398 MB transfer that already delivered a working voice. Failures are
     * logged and the file simply stays missing — the picker shows that.
     */
    private suspend fun fetchMissingExtras(type: ModelType) = withContext(Dispatchers.IO) {
        val root = File(rootDir, type.fileName)
        type.extraParts.forEach { part ->
            val target = File(root, part.relPath)
            if (target.exists() && target.length() > 0) return@forEach
            try {
                val temp = File(root, part.relPath + ".part")
                fetchTo(resolvePartUrl(part), temp) { }
                verifyPart(part, temp)
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("ModelManager", "extra ${part.relPath} skipped: ${e.message}")
            }
        }
    }

    /**
     * Import a model file the operator already downloaded out-of-band.
     */
    suspend fun importFromStream(type: ModelType, input: InputStream): Result<Unit> = withContext(Dispatchers.IO) {
        if (type.parts.isNotEmpty()) {
            val msg = "Ця модель складається з ${type.parts.size} файлів — завантаж її кнопкою ⭳"
            _states.update { it + (type to ModelState(type, error = msg)) }
            return@withContext Result.failure(IllegalArgumentException(msg))
        }
        runCatching {
            _states.update { it + (type to ModelState(type, isDownloading = true, progress = 0f)) }
            val target = File(rootDir, type.fileName)
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { out ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                }
            }
            _states.update { it + (type to ModelState(type, isPresent = true)) }
            _downloadEvents.tryEmit(DownloadEvent.Completed(type))
            Unit  // pin Result<Unit> — tryEmit returns Boolean
        }.onFailure { e ->
            val msg = "Імпорт не вдався: ${e.message ?: e::class.simpleName}"
            _states.update {
                it + (type to ModelState(
                    type = type,
                    isDownloading = false,
                    error = msg,
                ))
            }
            _downloadEvents.tryEmit(DownloadEvent.Failed(type, msg))
        }
    }

    /**
     * One HTTP GET → file, with HF auth and streaming progress.
     *
     * `prepareGet(...).execute { }` — never `client.get(url)`. The plain
     * `get()` reads the WHOLE response body into memory before it hands
     * back an HttpResponse; `bodyAsChannel()` then merely replays what is
     * already on the heap. That is survivable for a 36 MB text encoder
     * and fatal for the 257 MB vector estimator, which OOM'd the app dead
     * against its 256 MB heap ceiling. Only the prepare/execute form keeps
     * the body a live channel, so the bytes go network → disk without ever
     * being resident whole.
     *
     * The file is therefore never bounded by RAM — only by storage.
     */
    /**
     * Наш ключ → повна адреса під [modelsBaseUrl]; чужий URL лишається собою.
     */
    internal fun resolvePartUrl(part: ModelPart): String {
        if (!part.isOurs) return part.url
        val base = modelsBaseUrl().trim().trimEnd('/')
        if (base.isEmpty()) {
            throw IllegalStateException(
                "MODELS_BASE_URL порожній — ця збірка не знає, звідки брати власні моделі. " +
                    "Модель не встановлено, і мовчазного відкоту на чуже сховище тут немає.",
            )
        }
        return "$base/${part.url}"
    }

    /**
     * Розмір, потім сума. Розмір відсікає обрив і сторінку помилки дешево;
     * сума — підміну, і вона єдина з двох щось доводить.
     */
    private fun verifyPart(part: ModelPart, file: File) {
        if (!verifyDownloads) return
        val wantBytes = part.bytes
        if (wantBytes != null && file.length() != wantBytes) {
            file.delete()
            throw IllegalStateException(
                "${part.relPath}: приїхало ${file.length()} Б замість $wantBytes Б",
            )
        }
        val wantSha = part.sha256 ?: return
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val got = digest.digest().joinToString("") { "%02x".format(it) }
        if (!got.equals(wantSha, ignoreCase = true)) {
            file.delete()
            throw IllegalStateException(
                "${part.relPath}: sha256 $got не збігається з маніфестом збірки ($wantSha)",
            )
        }
    }

    private suspend fun fetchTo(url: String, target: File, onProgress: (Float) -> Unit) {
        target.parentFile?.mkdirs()
        client.prepareGet(url) {
            header("User-Agent", "PhantomCompanion/0.1 (Android)")
            hfTokenProvider()?.takeIf { it.isNotBlank() }?.let { token ->
                if (url.contains("huggingface.co")) {
                    header("Authorization", "Bearer $token")
                }
            }
            onDownload { bytesSentTotal, contentLength ->
                if (contentLength != null && contentLength > 0) {
                    onProgress(bytesSentTotal.toFloat() / contentLength)
                }
            }
        }.execute { response ->
            if (!response.status.isSuccess()) {
                throw Exception("HTTP ${response.status.value}")
            }
            withContext(Dispatchers.IO) {
                val channel = response.bodyAsChannel()
                FileOutputStream(target).use { output ->
                    channel.copyTo(output)
                    output.flush()
                }
            }
        }
    }

    private fun unzip(zipFile: File, targetDir: File) {
        targetDir.mkdirs()
        java.util.zip.ZipInputStream(zipFile.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val file = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    fun getModelFile(type: ModelType): File = File(rootDir, type.fileName)

    /**
     * Phase 4 BW-2 — resolve the actual Vosk model root.
     *
     * The Vosk zip from alphacephei.com contains a single top-level directory
     * ([VOSK_MODEL_DIR]) holding the `am/`, `conf/`, `graph/`, `ivector/`
     * model subdirs that `org.vosk.Model(path)` expects directly. Our
     * `unzip()` preserves zip structure verbatim, so after download the layout
     * becomes `<rootDir>/models/vosk/<VOSK_MODEL_DIR>/am/…` rather than
     * `<rootDir>/models/vosk/am/…`. Passing the outer dir to `Model()`
     * silently fails to load the recogniser → VoiceManager falls back to
     * `FakeSttEngine` even though the model bytes are on disk.
     *
     * Returns the path to feed `Model()`:
     *   - `<outer>/<VOSK_MODEL_DIR>` — стандартний розклад zip;
     *   - the outer dir itself, when the operator flattened the import;
     *   - null — придатної ЧИННОЇ моделі немає.
     *
     * Тека іншої моделі — це «немає», а не «є». Раніше сюди годився будь-який
     * підкаталог із `am/`, тож застаріла nano залишалася «наявною» довіку і
     * нову ніхто не тягнув. Імʼя теки — єдине, чим два розклади на диску
     * відрізняються.
     */
    fun resolveVoskModelDir(): File? {
        val outer = getModelFile(ModelType.VOSK)
        if (!outer.exists() || !outer.isDirectory) return null
        val shipped = File(outer, VOSK_MODEL_DIR)
        if (File(shipped, "am").isDirectory) return shipped
        if (File(outer, "am").isDirectory) return outer
        return null
    }

    /**
     * Розклади попередніх моделей слуху, що лежать поруч і вже нічого не
     * важать. Місця вони займають стільки ж, скільки чинна модель.
     */
    private fun staleVoskDirs(): List<File> {
        val outer = getModelFile(ModelType.VOSK)
        if (!outer.isDirectory) return emptyList()
        val keep = resolveVoskModelDir()
        return (outer.listFiles { f -> f.isDirectory } ?: emptyArray())
            .filter { it.absolutePath != keep?.absolutePath && File(it, "am").isDirectory }
    }
}
