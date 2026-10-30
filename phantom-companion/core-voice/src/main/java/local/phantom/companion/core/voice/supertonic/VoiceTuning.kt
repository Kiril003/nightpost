package local.phantom.companion.core.voice.supertonic

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Обраний характер голосу: котрий із десяти стилів моделі говорить і в
 * якому темпі.
 *
 * Тримач на процес, а не аргумент конструктора: синтез збирається в
 * контейнері один раз і знає лише теку моделі, тож екран пише сюди, а
 * [OrtSupertonicSynth] читає [current] на кожній репліці. Вибір
 * зберігається файлом усередині теки моделі — зникає разом із нею, і це
 * правильний строк життя для налаштування, що без моделі беззмістовне.
 */
/**
 * НЕ ПЛУТАТИ з `…voice.route.VoiceTuning`: той — data class про просодію
 * СИСТЕМНОГО TTS (pitch/rate). Цей — про рушій **Supertonic**: який зі
 * стилів говорить і в якому темпі. Різні рушії, різні угоди, спільне лише імʼя.
 */
object VoiceTuning {

    data class Tuning(val style: String, val durationScale: Float)

    /**
     * M* — чоловічі, F* — жіночі; M1 — це сам `voice.json`, він є завжди.
     *
     * Перелік **не дублюється тут літералами**: єдине джерело — [VoiceStyle],
     * бо там до кожного стилю привʼязана ще й людська мітка й імʼя файла.
     * Дві копії одного переліку в різних файлах розходяться тихо: додати
     * стиль в одному місці й забути про інше — це рівно той спосіб, яким
     * вибір голосу перестав би збігатися з тим, що лежить на диску.
     */
    val STYLES: List<String> = VoiceStyle.entries.map { it.id }

    const val DEFAULT_STYLE = "M1"
    const val MIN_DURATION_SCALE = 0.6f
    const val MAX_DURATION_SCALE = 1.25f

    @Volatile
    var current: Tuning = Tuning(DEFAULT_STYLE, OrtSupertonicSynth.DEFAULT_DURATION_SCALE)
        private set

    @Volatile
    private var loadedDir: String? = null

    /** Ідемпотентне; синтез кличе в load(), щоб перезапуск не губив вибір. */
    fun ensureLoaded(modelDir: File) {
        if (loadedDir == modelDir.absolutePath) return
        val file = File(modelDir, FILE_NAME)
        if (file.exists()) runCatching {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            current = sanitized(
                Tuning(
                    style = root["style"]?.jsonPrimitive?.content ?: DEFAULT_STYLE,
                    durationScale = root["durationScale"]?.jsonPrimitive?.float
                        ?: OrtSupertonicSynth.DEFAULT_DURATION_SCALE,
                ),
            )
        }.onFailure { Log.w(TAG, "tuning file unreadable, defaults stand: ${it.message}") }
        loadedDir = modelDir.absolutePath
    }

    /** Діє з наступного синтезованого сегмента. Пише на диск — не з main. */
    fun update(modelDir: File, tuning: Tuning) {
        val clean = sanitized(tuning)
        current = clean
        loadedDir = modelDir.absolutePath
        runCatching {
            File(modelDir, FILE_NAME)
                .writeText("""{"style":"${clean.style}","durationScale":${clean.durationScale}}""")
        }.onFailure { Log.w(TAG, "tuning not persisted: ${it.message}") }
    }

    private fun sanitized(t: Tuning) = Tuning(
        style = if (t.style in STYLES) t.style else DEFAULT_STYLE,
        durationScale = t.durationScale.coerceIn(MIN_DURATION_SCALE, MAX_DURATION_SCALE),
    )

    private const val FILE_NAME = "voice_tuning.json"
    private const val TAG = "VoiceTuning"
}
