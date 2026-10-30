package local.phantom.companion.core.net.peer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.net.crypto.bytesToHex
import local.phantom.companion.core.net.crypto.sha256

/**
 * Фото й файл між двома телефонами.
 *
 * Вкладення їде тими самими дверима, що й слово: підпис, конверт, черга,
 * повтори й квитанція — усе вже доведене на тексті. Окремий канал для байтів
 * довелося б будувати вчетверте, і саме він мовчки губив би те, що людина
 * вважає надісланим. Тож файл — це купка звичайних листів: один опис і N
 * шматків, кожен зі своїм підписом і своїм рядком у черзі.
 *
 * **Опис — це JSON веба, слово в слово.** Веб уже сам шифрує файл (AES-GCM
 * власним ключем) і кладе ключ у тіло листа; тіло запечатує вже меш. Тому тут
 * НЕ вигадано власного формату опису: `body` листа-вкладення — той самий
 * рядок, який веб кладе в `body` на вузлі, і той самий, який його `JSON.parse`
 * читає назад. Своя обгортка навколо нього означала б переклад у два боки й
 * два місця, де він розійдеться.
 *
 * Байти в шматках непрозорі: це вже шифротекст веба. Меш його не читає й не
 * вміє прочитати — він його возить.
 */
object PeerFile {

    /** Шматок-кадр. Веб його не бачить ніколи — тож тег, а не JSON. */
    internal const val CHUNK = "PH-FCH:"

    private const val SEP = ':'

    /**
     * Скільки сирих байтів в одному шматку.
     *
     * Стеля кадру — [PeerWire.MAX_BODY_BYTES] = 64 КіБ, і до неї байт іде через
     * ДВА base64: спершу шматок у тіло листа, потім увесь запечатаний лист у
     * конверт. Це ×(4/3)² ≈ 1,78, плюс підпис, ідентифікатори й назви полів.
     * 24 КіБ дають ≈44 КБ кадру — третина стелі в запасі. Не арифметика на
     * віру: `PeerFileTest.найбільший шматок пролізає у справжній конверт`
     * запечатує граничний шматок справжніми ключами й вимагає кадр.
     */
    const val CHUNK_BYTES = 24 * 1024

    /**
     * Стеля на файл — 32 МіБ, тобто 1366 шматків.
     *
     * **Було 4 МіБ, і чому саме стільки.** Межу ставив не дріт і не
     * ввічливість, а те, ЯК черга лежить на диску: `PeerOutboxStore.save`
     * серіалізує ВЕСЬ журнал в один рядок і переписує його цілком на кожну
     * зміну. Поки байти шматків лежали В ЖУРНАЛІ, файл важив у ньому ×1,37
     * (base64) — і телефон тримав його в купі ДВІЧІ: самі шматки й їхню копію
     * в одному рядку, перш ніж дійде до шифрування. Виміряно
     * ([PeerFileCeilingTest]): 4 МіБ = 171 шматок = 5,6-6,2 млн знаків.
     *
     * **Що змінилось.** Байти пішли з журналу у файлове сховище, а в журналі
     * лишилось посилання ([vaultedChunk]). Байти там і так лежали — і у
     * відправника, і в отримувача, — тож їхня копія в журналі була чистою
     * платою. Рядок подешевшав більш ніж у 80 разів.
     *
     * **Звідки 32 МіБ, а не «безмежно».** Три межі, усі виміряні
     * ([PeerFileVaultTest]):
     *
     *  1. ВАГА ЖУРНАЛУ. Рядок із байтами важить 33 033 знаки, рядок із
     *     посиланням — 267, тобто в 123 рази менше. Журнал на новій стелі —
     *     366 345 знаків проти 5 648 705 на старій: у 15 разів легше, при
     *     ввосьмеро більшому файлі. Ця межа вже не тисне.
     *  2. ПЕРЕПИСУВАННЯ. Журнал переписується цілком на кожен [PeerOutbox.markSent]
     *     і [PeerOutbox.markDelivered] — двічі на шматок, — тож ціна росте
     *     КВАДРАТОМ від числа шматків. Виміряно: стара стеля коштувала
     *     1 931 835 906 знаків переписування, нова коштує 1 000 152 416 —
     *     тобто 32 МіБ дешевші за колишні 4 МіБ у 1,9 раза. Саме ця межа й
     *     вирішує число: 64 МіБ коштували б уже вчетверо більше за стару ціну.
     *  3. РЯДКИ ЧЕРГИ. [PeerOutbox.MAX_PER_PEER] підняте до 2000 разом із цим:
     *     1366 шматків уміщуються й лишають 634 рядки самій розмові. Черга,
     *     зайнята одним файлом під завʼязку, — це розмова, у якій не лишилось
     *     слів.
     *
     * «Безмежно» тут не написано і не буде, поки його нема чим довести: 100 МБ
     * — це 4267 рядків проти 2000, тобто файл виштовхнув би сам себе, і частина
     * шматків зникла б, не поїхавши. Щоб зняти й цю межу, чергу треба вести
     * посторінково, а не одним рядком на диску.
     *
     * **ЧЕТВЕРТА МЕЖА, якої це число не знає: збірка ТОГО БОКУ.** [head] і
     * [decodeChunk] відкидають кадр, у якому `total` більший за
     * `chunkCount(MAX_BYTES)` ВЛАСНОЇ збірки. Тому файл понад 4 МіБ, надісланий
     * на телефон зі старою стелею, там не збереться: шматок не розбереться,
     * `PeerFileStore.absorb` мовчки вийде, і вкладення лишиться «очікує
     * передачі» назавжди. Воно НЕ вдає цілого — і це єдине, що тут зараз
     * правильно, — але й не доїде ніколи, а відправник бачитиме галочки, бо
     * конверти справді перетнули дріт.
     *
     * Полагодити це числом не можна: стелю різальника мусить називати ТОЙ бік,
     * а не цей. Поки такого рукостискання немає (вузол оголошує
     * `max_attachment_bytes` лише СВОЄМУ вебу, не співрозмовнику), 32 МіБ
     * безпечні тільки між двома оновленими телефонами.
     *
     * Перевищення однаково кажуть уголос через [Refusal.TOO_BIG], а не
     * мовчазною пропажею.
     */
    const val MAX_BYTES = 32 * 1024 * 1024

    /** Ідентифікатор блоба: рівно стільки шістнадцяткових цифр. */
    const val BLOB_ID_CHARS = 32

    /**
     * Чому файл не поїде. Порожнеча й перевищення — це ВІДПОВІДЬ, а не тиша:
     * найгірший месенджер — той, що взяв файл і не сказав, що не повіз.
     */
    enum class Refusal(val code: String) {
        EMPTY("empty_file"),
        TOO_BIG("file_too_big"),
    }

    fun refuse(size: Int): Refusal? = when {
        size <= 0 -> Refusal.EMPTY
        size > MAX_BYTES -> Refusal.TOO_BIG
        else -> null
    }

    /**
     * Випадковий, не похідний від вмісту: два однакові файли — це два
     * вкладення, і однаковий ідентифікатор склеїв би їхні долі в черзі.
     *
     * Абетка — лише шістнадцяткова, і це не косметика: саме вона робить
     * [PeerFileRoute] безпечним за побудовою, бо в такому рядку не буває ані
     * скісної, ані крапки.
     */
    fun newBlobId(random: () -> Int = { (0..15).random() }): String =
        (1..BLOB_ID_CHARS).joinToString("") { random().coerceIn(0, 15).toString(16) }

    fun isBlobId(text: String): Boolean =
        text.length == BLOB_ID_CHARS && text.all { it in '0'..'9' || it in 'a'..'f' }

    fun sha256Hex(bytes: ByteArray): String = bytesToHex(sha256(bytes))

    // ── опис ─────────────────────────────────────────────────────────────

    /**
     * Опис вкладення так, як його вже пише й читає веб. Поля — його, у його
     * написанні; ключ і сіль тут не розбираються, бо мешу вони не потрібні:
     * він возить шифротекст, якого не відкриває.
     */
    data class Attachment(
        val blobId: String,
        val name: String,
        val mime: String,
        val size: Int,
        val sha256: String,
        val caption: String,
        /** Рід листа: [IMAGE] · [FILE] · [VOICE] · [VIDEO_NOTE] ([kindOf]). */
        val kind: String = FILE,
    ) {
        /** Голосове — мова людини, а не вантаж: у вузькому режимі мале їде ([PeerLane]). */
        val isVoice: Boolean get() = kind == VOICE
    }

    const val IMAGE = "image"
    const val FILE = "file"
    const val VOICE = "voice"
    const val VIDEO_NOTE = "video-note"

    /**
     * Рід вкладення: оголошений в описі (`kind`, його ставить веб для голосового
     * й кружка), інакше — з імені, яке ставить сам записувач («voice_…»,
     * «video_note_…»), і з типу. Те саме правило, що `messageFromNode` у вебі й
     * `peer_frames.media_kind` на ПК: один лист — один рід на всіх трьох.
     */
    fun kindOf(declared: String?, name: String, mime: String): String {
        if (declared == IMAGE || declared == FILE || declared == VOICE || declared == VIDEO_NOTE) return declared
        val low = name.lowercase()
        return when {
            low.startsWith("video_note") || low.startsWith("video-note") -> VIDEO_NOTE
            low.startsWith("voice") || mime.startsWith("audio/") -> VOICE
            mime.startsWith("image/") -> IMAGE
            else -> FILE
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Чи це лист-вкладення — і який. Розпізнавання за тими самими полями, за
     * якими його розпізнає веб (`blob_id` + `key_hex`), плюс усе, без чого
     * файл однаково не зібрати. Вимагати всі пʼять — це те, що не дає
     * звичайному листу, у якому людина процитувала JSON, стати «фотографією».
     */
    fun describe(body: String): Attachment? {
        if (!body.startsWith("{")) return null
        val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        fun str(key: String): String? =
            runCatching { o[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }
        val blobId = str("blob_id") ?: return null
        if (!isBlobId(blobId)) return null
        if (str("key_hex") == null) return null
        val sha = str("sha256") ?: return null
        val size = runCatching { o["size"]?.jsonPrimitive?.content?.toInt() }.getOrNull() ?: return null
        if (size <= 0 || size > MAX_BYTES) return null
        val name = str("name") ?: ""
        val mime = str("mime") ?: "application/octet-stream"
        return Attachment(
            blobId = blobId,
            name = name,
            mime = mime,
            size = size,
            sha256 = sha,
            caption = str("caption") ?: "",
            kind = kindOf(str("kind"), name, mime),
        )
    }

    fun isAttachment(body: String): Boolean = describe(body) != null

    /** Опис і з-під конверта групи чи адреси каналу: там JSON не перший рядок тіла. */
    fun carried(body: String): Attachment? {
        describe(body)?.let { return it }
        val text = PeerGroup.decode(body)?.text ?: return null
        return describe(PeerGroup.channelLine(text)?.text ?: text)
    }

    /** Те саме вкладення з іншим підписом; решта полів — байт у байт за змістом. */
    fun recaption(body: String, caption: String): String? {
        if (describe(body) == null) return null
        val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        return JsonObject(o + ("caption" to JsonPrimitive(caption))).toString()
    }

    // ── шматки ───────────────────────────────────────────────────────────

    data class Chunk(val blobId: String, val index: Int, val total: Int, val bytes: ByteArray) {
        // data class із ByteArray порівнюється за посиланням; тести звіряють
        // саме вміст, тож рівність мусить бути за вмістом.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Chunk) return false
            return blobId == other.blobId && index == other.index &&
                total == other.total && bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int =
            (((blobId.hashCode() * 31) + index) * 31 + total) * 31 + bytes.contentHashCode()
    }

    fun chunkCount(size: Int): Int = if (size <= 0) 0 else (size + CHUNK_BYTES - 1) / CHUNK_BYTES

    /** Шматок №[index] цих байтів, або null коли такого немає. */
    fun sliceAt(bytes: ByteArray, index: Int): ByteArray? {
        val total = chunkCount(bytes.size)
        if (index !in 0 until total) return null
        val from = index * CHUNK_BYTES
        return bytes.copyOfRange(from, minOf(from + CHUNK_BYTES, bytes.size))
    }

    /** Кожен шматок несе [total] сам: він може прийти раніше за опис. */
    fun encodeChunk(blobId: String, index: Int, total: Int, bytes: ByteArray): String =
        CHUNK + blobId + SEP + index + SEP + total + SEP + b64(bytes)

    fun isChunk(body: String): Boolean = body.startsWith(CHUNK)

    // ── шматок БЕЗ байтів: посилання в журнал ────────────────────────────

    /**
     * Замість base64 — одна зірочка.
     *
     * Абетка base64 — це літери, цифри, `+` і `/`; зірочки в ній немає й бути
     * не може. Тому посилання не сплутати зі шматком, у якому просто короткі
     * байти: [decodeChunk] на ньому віддає null, бо `*` не розбирається, — і
     * жодне місце, яке чекало байтів, не отримає замість них порожнечі.
     */
    internal const val VAULTED = "*"

    /**
     * Шматок, чиї байти лежать у файловому сховищі, а не в журналі черги.
     *
     * **Навіщо.** Межу на файл ставив не дріт, а те, ЯК черга лежить на диску:
     * `PeerOutboxStore.save` серіалізує ВЕСЬ журнал в один рядок і переписує
     * його цілком на кожну зміну. Поки шматки в журналі, цей рядок важить
     * стільки ж, скільки файл у base64, і телефон тримає його в купі РАЗОМ із
     * самими шматками — тобто платить за файл двічі. Виміряно
     * ([PeerFileCeilingTest]): 4 МіБ = 171 шматок = 5,6-6,2 млн знаків журналу.
     *
     * Байти при цьому ВЖЕ лежать на диску: `PeerFileStore` кладе їх у
     * `filesDir/msgr/<розмова>/<блоб>` ще до того, як складено перший кадр.
     * Другий їх примірник у журналі не потрібен нікому — його довелося б
     * тримати рівно для того, щоб через мить прочитати назад.
     *
     * Тож у журнал лягає ПОСИЛАННЯ: та сама позначка, той самий блоб, номер і
     * всього, і жодного байта. [head] читає його без змін — вона й раніше не
     * дивилась на четверте поле, — а [PeerOutbox.isSpoken], [PeerOutbox.prune]
     * і [PeerOutbox.waiting] бачать рівно те, що бачили. Підпис при цьому
     * кладеться на СПРАВЖНЄ тіло (див. `PeerMesh.sendMany`), тож посилання —
     * це спосіб зберігання, а не інший лист.
     */
    fun vaultedChunk(blobId: String, index: Int, total: Int): String =
        CHUNK + blobId + SEP + index + SEP + total + SEP + VAULTED

    /** Чи це шматок, байти якого треба взяти зі сховища перед відправкою. */
    fun isVaulted(body: String): Boolean {
        if (!isChunk(body)) return false
        val f = body.removePrefix(CHUNK).split(SEP, limit = 4)
        return f.size == 4 && f[3] == VAULTED
    }

    /** Заголовок шматка без його байтів. */
    data class Head(val blobId: String, val index: Int, val total: Int)

    /**
     * Чий це шматок і котрий — БЕЗ розбору base64.
     *
     * Потрібне окремо від [decodeChunk], бо [PeerOutbox.prune] перебирає весь
     * журнал на кожен запис на диск. Розшифровувати там сотні тіл, щоб узнати
     * лише номер, означало б класти телефон рівно тим кодом, який писався,
     * щоб його не класти.
     */
    fun head(body: String): Head? {
        if (!isChunk(body)) return null
        val rest = body.removePrefix(CHUNK)
        val f = rest.split(SEP, limit = 4)
        if (f.size != 4) return null
        if (!isBlobId(f[0])) return null
        val index = f[1].toIntOrNull() ?: return null
        val total = f[2].toIntOrNull() ?: return null
        if (total !in 1..chunkCount(MAX_BYTES)) return null
        if (index !in 0 until total) return null
        return Head(f[0], index, total)
    }

    fun decodeChunk(body: String): Chunk? {
        if (!isChunk(body)) return null
        val rest = body.removePrefix(CHUNK)
        val f = rest.split(SEP, limit = 4)
        if (f.size != 4) return null
        val blobId = f[0]
        if (!isBlobId(blobId)) return null
        val index = f[1].toIntOrNull() ?: return null
        val total = f[2].toIntOrNull() ?: return null
        if (total !in 1..chunkCount(MAX_BYTES)) return null
        if (index !in 0 until total) return null
        val bytes = runCatching { unB64(f[3]) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > CHUNK_BYTES) return null
        return Chunk(blobId, index, total, bytes)
    }

    // ── збирання ─────────────────────────────────────────────────────────

    /**
     * Чого ще бракує. Порядок не має значення: номер їде в кадрі, тож шматки
     * можуть прийти як завгодно — і прийдуть, бо черга пробує їх незалежно.
     */
    fun missing(have: Set<Int>, total: Int): List<Int> = (0 until total).filter { it !in have }

    /**
     * Склеїти зібране. Null — коли бракує хоч одного: половина файла, видана
     * за файл, гірша за відсутній файл.
     */
    fun join(parts: Map<Int, ByteArray>, total: Int): ByteArray? {
        if (total <= 0) return null
        if (missing(parts.keys, total).isNotEmpty()) return null
        val size = (0 until total).sumOf { parts.getValue(it).size }
        if (size <= 0 || size > MAX_BYTES) return null
        val out = ByteArray(size)
        var at = 0
        for (i in 0 until total) {
            val part = parts.getValue(i)
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    /**
     * Той самий файл чи ні. Звірка по всьому файлу, а не по шматках: зіпсутий
     * шматок ловиться саме тут — і ловиться навіть тоді, коли зіпсуто його
     * так, що довжина збіглася.
     */
    fun matches(bytes: ByteArray, sha256Hex: String): Boolean =
        sha256Hex.isNotBlank() && sha256Hex.equals(sha256Hex(bytes), ignoreCase = true)

    private fun b64(bytes: ByteArray): String =
        java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)

    private fun unB64(text: String): ByteArray =
        java.util.Base64.getDecoder().decode(text)
}
