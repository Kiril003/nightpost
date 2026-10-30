package local.phantom.companion.core.net.peer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Вкладення, байти якого їдуть СХОВИЩЕМ, а не дротом.
 *
 * **Рішення власника 17.09** (`phantom-os-messenger/docs/BLOB_ATTACHMENTS_DECISION.md`):
 * дріт возить посилання, а не байти. Відправник шифрує файл у себе
 * (AES-256-GCM, ключ на файл), ллє ШИФРОТЕКСТ прямо в R2 одноразовим
 * підписаним посиланням, а дротом їде оцей опис — кількасот байтів незалежно
 * від того, гігабайт там чи кілобайт.
 *
 * **Чому не шматками.** Шматковий шлях ([PeerFile]) лишається й нікуди не
 * дівається, але його стеля — це стеля НЕ ТОГО рішення: поки байти їдуть між
 * пірами, розмір упирається в чергу на диску, а швидкість — у найповільнішу
 * ногу сітки. У групі на 30 осіб старий шлях дублював кожен байт 29 разів;
 * тут лягає ОДИН обʼєкт, і всі качають його з CDN на швидкості свого каналу.
 *
 * **Чому це НЕ новий рід у [PeerFrames.Kind], і це рішення, а не недогляд.**
 * З тієї самої причини, з якої там немає [PeerFile.describe], і причина
 * записана там же: веб кладе файл ТИМ САМИМ набраним входом, яким шле слово.
 * Рід, вписаний у реєстр, отримує відмову в [PeerFrames.refuseTyped] — тобто
 * реєстрація цього опису зупинила б надсилання вкладень із веб-месенджера
 * зовсім. Опис і не вдає виміру: він описує байти, які лежать у сховищі, і
 * сам є тим рядком, який людина бачить у стрічці.
 *
 * **Що бачить сервер і чого не бачить.** Він тримає шифротекст і не має
 * ключа: [Blob.keyHex] їде ТУТ, усередині наскрізно зашифрованого кадру, тож
 * ключ файла захищений тим самим, чим захищене слово. Сервер бачить, що
 * обʼєкт існує, його вагу й час — і це чесна ціна, яку названо вголос у
 * документі рішення, а не сховано.
 */
object PeerBlob {

    /**
     * Стеля одного обʼєкта — 2 ГіБ.
     *
     * **Дзеркало серверного числа**, і це його єдине джерело правди:
     * `platform-site/server/attachments.py:MAX_OBJECT_BYTES`. Два дерева, тож
     * тест, який тримав би їх рівними, тут написати нема на чому — сервера в
     * цьому репозиторії немає. Тому число стоїть з іменем файла, де лежить
     * оригінал: розійдуться — відмовить сервер, підписуючи посилання, а не
     * телефон мовчки.
     *
     * Звідки саме 2 ГіБ: обʼєкт кладеться ОДНИМ `PUT`, а простий `PUT` у
     * протоколі S3, яким говорить R2, несе не більше 5 ГіБ. 2 ГіБ лишають
     * запас і на 16 байтів тега AES-GCM, і на ще одне підняття без переходу
     * на складене завантаження.
     *
     * Це НЕ «безмежно», і слова такого тут не буде: межу тепер ставить
     * сховище, а не месенджер, але межа лишається числом.
     */
    const val MAX_OBJECT_BYTES = 2L * 1024 * 1024 * 1024

    /** Префікс ключа обʼєкта. Дзеркало `attachments.OBJECT_PREFIX`. */
    const val OBJECT_PREFIX = "msgr/"

    /** Скільки шістнадцяткових цифр в імені обʼєкта. */
    const val OBJECT_ID_CHARS = 32

    /** AES-256: рівно стільки шістнадцяткових цифр ключа. */
    const val KEY_HEX_CHARS = 64

    /**
     * AES-GCM: 12 байтів разового числа, тобто 24 шістнадцяткові цифри.
     *
     * Без нього ключ марний — розшифрувати нема чим, — і опис без нього це
     * бульбашка, яка викачає гігабайт і не відкриє його. Тому вимагається
     * нарівні з ключем, а не «як буде».
     *
     * Довжина саме та, яку складає веб (`messengerMedia.encryptForUpload`:
     * `crypto.getRandomValues(new Uint8Array(12))`).
     */
    const val NONCE_HEX_CHARS = 24

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Опис у полях ВЕБА, у його написанні — як і [PeerFile.Attachment].
     *
     * [size] і [sha256] описують ШИФРОТЕКСТ, а не відкритий файл: качається й
     * звіряється саме він, і звіряти треба те, що приїхало. Довжина відкритого
     * файла відновлюється після розшифрування й дроту не потрібна.
     */
    data class Blob(
        val objectKey: String,
        val sha256: String,
        val size: Long,
        val keyHex: String,
        val nonceHex: String,
        val name: String,
        val mime: String,
        val caption: String,
    )

    /**
     * Чи це ключ, який видав сервер, — і нічого іншого.
     *
     * Перевіряється ФОРМА, а не відсутність поганого. Опис із ключем
     * `builds/phantom-1.0.apk` змусив би приймача просити в сервера посилання
     * на ЗБІРКУ; сервер таке відмовляє сам (`attachments.is_object_key`), але
     * телефон не має й питати — кадр, складений чужими руками, спиняється там,
     * де його прочитали.
     */
    fun isObjectKey(key: String): Boolean {
        if (!key.startsWith(OBJECT_PREFIX)) return false
        val tail = key.removePrefix(OBJECT_PREFIX)
        return tail.length == OBJECT_ID_CHARS && tail.all { it in '0'..'9' || it in 'a'..'f' }
    }

    private fun isHex(text: String, chars: Int): Boolean =
        text.length == chars && text.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * Чи це лист-вкладення СХОВИЩЕМ — і яке.
     *
     * Вимагаються всі пʼять полів, без яких файл однаково не дістати:
     * `object_key`, `sha256`, `size`, `key_hex`, `nonce_hex`. Це те саме, що не дає
     * звичайному листу, у якому людина процитувала JSON, стати «файлом».
     *
     * Null — не «зіпсоване вкладення», а «не вкладення»: лист лишається словом
     * і показується словом. Половинчастий опис, виданий за файл, дав би
     * бульбашку, яка вічно качає те, чого немає.
     */
    fun describe(body: String): Blob? {
        if (!body.startsWith("{")) return null
        val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        fun str(key: String): String? =
            runCatching { o[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }

        val objectKey = str("object_key") ?: return null
        if (!isObjectKey(objectKey)) return null
        val sha = str("sha256")?.lowercase() ?: return null
        if (!isHex(sha, 64)) return null
        val keyHex = str("key_hex")?.lowercase() ?: return null
        if (!isHex(keyHex, KEY_HEX_CHARS)) return null
        val nonceHex = str("nonce_hex")?.lowercase() ?: return null
        if (!isHex(nonceHex, NONCE_HEX_CHARS)) return null
        val size = runCatching { o["size"]?.jsonPrimitive?.content?.toLong() }.getOrNull() ?: return null
        if (size <= 0 || size > MAX_OBJECT_BYTES) return null

        return Blob(
            objectKey = objectKey,
            sha256 = sha,
            size = size,
            keyHex = keyHex,
            nonceHex = nonceHex,
            name = str("name") ?: "",
            mime = str("mime") ?: "application/octet-stream",
            caption = str("caption") ?: "",
        )
    }

    fun isBlob(body: String): Boolean = describe(body) != null

    // ── заява на посилання: рівно ті речення, які звіряє сервер ──────────

    /**
     * Рід заяви. Окремий префікс НАВМИСНО, і це межа безпеки, не охайність.
     *
     * Підпис, узятий на одному обʼєкті, не має зійти за підпис деінде: якби
     * заява на вкладення виглядала як канонічний рядок листа
     * ([PeerSealing.canonical]) чи як payload довідника
     * ([PeerDirectory.publishPayload]), підпис, виданий тут, прийняли б там.
     * Тому речення починається власним родом, якого немає більше ніде в
     * продукті, і містить версію: формат колись зміниться, і старий підпис не
     * має зійти за новий.
     *
     * Дзеркало `platform-site/server/attachments.py` — `put_message` і
     * `get_message` слово в слово.
     */
    const val CLAIM_DOMAIN = "phantom-attachment"

    /** Версія формату заяви. Змінилась — старі підписи не приймаються. */
    const val CLAIM_VERSION = "v1"

    /** Разове число заяви: 16 байтів шістнадцятково. Дзеркало `NONCE_CHARS`. */
    const val CLAIM_NONCE_CHARS = 32

    /**
     * Речення, під яким стоїть підпис заяви на ЗАПИС.
     *
     * Поля зʼєднані `|`, і жодне не може містити `|` за побудовою: ключ і
     * підпис — base64, разове число — шістнадцяткове, решта — цифри. Тому
     * речення однозначне, і дві різні заяви не дають один рядок.
     *
     * Вага входить у підпис: інакше перехоплену заяву перепишуть на 2 ГіБ і
     * витратять чужу добову квоту.
     */
    fun putClaim(pubKey: String, size: Long, nonce: String, issuedMs: Long): String =
        "$CLAIM_DOMAIN-put|$CLAIM_VERSION|$pubKey|$size|$nonce|$issuedMs"

    /**
     * Те саме для ЧИТАННЯ. Ключ обʼєкта входить у підпис, тож посилання,
     * виписане на один обʼєкт, не перевикористати на інший.
     */
    fun getClaim(pubKey: String, objectKey: String, nonce: String, issuedMs: Long): String =
        "$CLAIM_DOMAIN-get|$CLAIM_VERSION|$pubKey|$objectKey|$nonce|$issuedMs"

    /** Чи це разове число потрібної форми. */
    fun isClaimNonce(nonce: String): Boolean =
        nonce.length == CLAIM_NONCE_CHARS && nonce.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * Яким шляхом поїхав цей файл — і чи поїхав узагалі.
     *
     * Власник просив, щоб людина БАЧИЛА дорогу: від неї залежить, дійде файл
     * за секунди чи за годину. Тому це окремий перелік, а не здогад на місці
     * показу: дві поверхні, що вгадують дорогу кожна по-своєму, розійдуться на
     * першому ж новому роді.
     */
    enum class Route {
        /** Байти в сховищі, дротом поїхало посилання. Швидко, потрібна мережа. */
        STORAGE,

        /** Байти їдуть шматками між пірами. Повільно, зате без інтернету. */
        MESH,
    }

    /**
     * Дорога цього листа, або null — це не вкладення взагалі.
     *
     * Питається саме тіло, а не памʼять про те, як ми його слали: лист міг
     * приїхати від когось іншого, а показати дорогу треба однаково.
     */
    fun routeOf(body: String): Route? = when {
        isBlob(body) -> Route.STORAGE
        PeerFile.isAttachment(body) -> Route.MESH
        else -> null
    }
}
