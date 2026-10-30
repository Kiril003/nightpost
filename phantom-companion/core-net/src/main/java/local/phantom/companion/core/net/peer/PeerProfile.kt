package local.phantom.companion.core.net.peer

/**
 * Профіль людини на дроті.
 *
 * **Вада, заради якої це існує.** Про людину дротом їхало ТІЛЬКИ ІМʼЯ
 * ([PeerName], у складі [PeerInvite]/[PeerIntro] при знайомстві). Аватар,
 * статус, емодзі статусу й біо жили лише на диску відправника
 * (`storagePersistence.saveUserProfile`) і не передавались НІКОЛИ. Людина
 * редагувала себе, бачила «збережено» — а співрозмовник далі бачив старе імʼя
 * і порожнечу там, де інтерфейс намалював аватар. Форма ще й казала вголос
 * «Зміни транслюються всім підключеним пірам» — про поле, яке нікуди не їде.
 *
 * Контракт складений веб-стороною й лежить поруч у продукті:
 * `phantom-os-messenger/docs/PROFILE_WIRE_CONTRACT.md`. Тут — його дротова
 * половина, рівно за ним, без самодіяльності: два переліки полів у двох
 * місцях розійшлися б на першому ж перезбиранні бандла.
 *
 * **Чому мініатюра їде В КАДРІ, а не відбитком із довантаженням.** Вкладення
 * в цьому продукті привʼязані до повідомлення в розмові, а профіль розмові НЕ
 * належить: знайомство трапляється ДО того, як розмова існує, і оновлення має
 * дійти до КОЖНОГО контакту з реєстру. Відбиток+довантаження вимагали б
 * нового загального протоколу «дай байти на хеш X», якого немає. Плюс сітка
 * без сервера: не всі онлайн одночасно, а мініатюра доїжджає тим самим
 * шляхом і в ту саму мить, що й імʼя. Ціна названа вголос: оригіналу аватара
 * співрозмовник не побачить ніколи — тільки мініатюру.
 *
 * **Чому TLV, а не жорстка структура.** Поля зʼявлятимуться далі, а старий
 * приймач мусить читати те, що знає, і мовчки переступати те, чого не знає.
 * Жорсткий порядок полів зробив би кожне розширення несумісним.
 */
object PeerProfile {

    /** Спільний початок кадру. Рід один — поля їдуть усередині. */
    internal const val TAG = "PH-PRF:"

    // ── стелі з контракту, у байтах ──────────────────────────────────────
    //
    // Не з ощадливості: кадр возиться сіткою й переказується далі, тож його
    // розмір мусить бути передбачуваним ДО відправки. Разом — до ~31 КіБ у
    // найгіршому випадку, що з добрим запасом лягає в
    // [PeerWire.MAX_BODY_BYTES] (64 КіБ) навіть після конверта й підпису.

    /** WebP/JPEG, квадрат, не більш як 96×96 px ДО кодування. Ріже ВІДПРАВНИК. */
    const val MAX_AVATAR_BYTES = 6144

    /**
     * Фото для списку й постера — до 512 px, WebP/JPEG. Мініатюра [T_AVATAR]
     * лишається поруч для старих збірок, які тег [T_PHOTO] переступають.
     * Стеля — один шматок файла ([PeerFile.CHUNK_BYTES]): кадр із усіма полями
     * під стелею ще влазить у [PeerWire.MAX_BODY_BYTES] після двох base64 і
     * конверта (доводить `PeerProfilePhotoTest`).
     */
    const val MAX_PHOTO_BYTES = 24 * 1024

    /** Один графемний кластер — прапор чи ZWJ-послідовність теж один символ. */
    const val MAX_STATUS_EMOJI_BYTES = 16

    const val MAX_STATUS_TEXT_BYTES = 140

    const val MAX_BIO_BYTES = 512

    /**
     * [PeerName.MAX] графем кирилицею — 128 байт, емодзі — 256. Довше ріже
     * [fitName] по графемі, а не відмовою всього кадру.
     */
    const val MAX_NAME_BYTES = 256

    /**
     * Теги полів. Числа — частина дроту: перейменувати поле можна, змінити
     * число не можна ніколи.
     */
    private const val T_REV = 1
    private const val T_AVATAR = 2
    private const val T_STATUS_EMOJI = 3
    private const val T_STATUS_TEXT = 4
    private const val T_BIO = 5
    private const val T_NAME = 6

    /** Лише заява: показують її після звірки з довідником, не з кадру. */
    private const val T_HANDLE = 7

    private const val T_PHOTO = 8

    /**
     * Рівень можливостей СЦЕНИ ЗУСТРІЧІ, який відправник вміє приймати: один байт.
     * Нуль (тега нема) — збірка, що не вміє, або не повідомляла. Дріт версію
     * застосунку не несе, а позначки картки (`PH-CST:`) старій збірці показалися б
     * сирим дротом і сповіщенням, тож надсилати їх можна лише тому, хто сам це
     * повідомив. Старий приймач невідомий тег переступає (див. [decode]).
     */
    private const val T_SCENE = 9
    private const val T_PAD = 10

    /**
     * Банер: тег 11 — розкладка (версія, кількість, прапорці, фокус кожного фото),
     * тег 12 — хеш і розмір кожного фото. Самі байти їдуть окремими кадрами
     * [PeerBanner] (по одному на фото): шість фото по 24 КіБ у цей кадр не влізли
     * б. Розбіжність кількості між тегами — «не повідомляв», а не чуже фото.
     */
    private const val T_BANNER = 11
    private const val T_BANNER_MEDIA = 12
    private const val BANNER_V1 = 1
    private const val BANNER_FLAG_VIDEO_STILL = 1
    private const val BANNER_MEDIA_ITEM = 16 + 3

    /** Відео банера: тег 13 — повний SHA-256 (32 байти) і розмір (4 байти). Байти тягне глядач ([PeerBanner.pull]). */
    private const val T_BANNER_VIDEO = 13

    /** Один байт 1: «не показуйте, коли я був у мережі». Тега нема — дозволено. */
    private const val T_HIDE_SEEN = 14
    private const val BANNER_VIDEO_TAG_BYTES = 32 + 4
    const val MAX_BANNER_VIDEO_BYTES = 8 * 1024 * 1024
    const val MAX_BANNER_ITEMS = 6

    /** Збірка 18: позначки стану на картках зустрічі й рахунку. */
    const val SCENE_V1 = 1

    internal val BUCKETS = intArrayOf(4 * 1024, 12 * 1024, 32 * 1024)

    /**
     * Три стани поля, і плутати їх не можна — саме на цьому тримається
     * чесний інтерфейс.
     *
     * Немає тега в кадрі — «ще не приїхало»; є тег із нульовою довжиною —
     * «людина стерла це в себе»; є тег із байтами — значення. Без цього
     * розрізнення «дані ще не дійшли» і «людина прибрала аватар» виглядали б
     * однаково, і поверхня не змогла б сказати правду про жодне з них.
     */
    sealed interface Field<out T> {
        data object Absent : Field<Nothing>
        data object Cleared : Field<Nothing>
        data class Set<T>(val value: T) : Field<T>
    }

    /**
     * [rev] — монотонний лічильник ВЛАСНИКА профілю, не годинник.
     *
     * Годинник тут був би вадою: пристрої розходяться в часі, а сітка
     * переказує кадри із затримкою, тож «новіший за часом» регулярно означало
     * б «старіший насправді». Приймач застосовує кадр лише коли
     * `rev > останній відомий rev цього відправника`; рівний або менший —
     * мовчки відкидається як повтор переказу.
     */
    data class Body(
        val rev: Long,
        val avatar: Field<ByteArray> = Field.Absent,
        val statusEmoji: Field<String> = Field.Absent,
        val statusText: Field<String> = Field.Absent,
        val bio: Field<String> = Field.Absent,
        /** Людина без імені не буває: порожнє читається як [Field.Absent]. */
        val name: Field<String> = Field.Absent,
        val handle: Field<String> = Field.Absent,
        val photo: Field<ByteArray> = Field.Absent,
        /** Що відправник вміє приймати зі сцени зустрічі; 0 — не повідомляв. */
        val scene: Int = 0,
        val banner: Field<Banner> = Field.Absent,
        val hidesSeen: Boolean = false,
    ) {
        // data class із ByteArray порівнюється за посиланням — а цей тип
        // порівнюють тести й реєстр. Пишемо руками, щоб рівність означала
        // рівність ЗМІСТУ.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Body) return false
            if (rev != other.rev) return false
            if (statusEmoji != other.statusEmoji) return false
            if (statusText != other.statusText) return false
            if (bio != other.bio) return false
            if (name != other.name) return false
            if (handle != other.handle) return false
            if (scene != other.scene) return false
            if (banner != other.banner) return false
            if (hidesSeen != other.hidesSeen) return false
            return sameBytes(avatar, other.avatar) && sameBytes(photo, other.photo)
        }

        private fun sameBytes(a: Field<ByteArray>, b: Field<ByteArray>): Boolean =
            if (a is Field.Set && b is Field.Set) a.value.contentEquals(b.value) else a == b

        override fun hashCode(): Int {
            var h = rev.hashCode()
            h = 31 * h + statusEmoji.hashCode()
            h = 31 * h + statusText.hashCode()
            h = 31 * h + bio.hashCode()
            h = 31 * h + name.hashCode()
            h = 31 * h + handle.hashCode()
            h = 31 * h + scene
            h = 31 * h + banner.hashCode()
            h = 31 * h + hidesSeen.hashCode()
            h = 31 * h + ((avatar as? Field.Set)?.value?.contentHashCode() ?: avatar.hashCode())
            h = 31 * h + ((photo as? Field.Set)?.value?.contentHashCode() ?: photo.hashCode())
            return h
        }
    }

    /**
     * Одне фото банера: [hash] — перші 16 байт SHA-256 його байтів (32 hex),
     * [size] — байти, [fx]/[fy] — точка фокуса 0..255 по ширині й висоті.
     */
    data class BannerItem(val hash: String, val size: Int, val fx: Int = 128, val fy: Int = 128) {
        internal fun valid(): Boolean =
            HASH.matches(hash) && size in 1..PeerBanner.MAX_BYTES && fx in 0..255 && fy in 0..255
    }

    /**
     * До [MAX_BANNER_ITEMS] фото в порядку власника. [videoStill] — перше фото є
     * першим кадром відео-банера: сама петля лишається на сторінці власника.
     */
    data class Banner(val items: List<BannerItem>, val videoStill: Boolean = false, val video: BannerVideo? = null) {
        internal fun valid(): Boolean =
            items.size in 1..MAX_BANNER_ITEMS && items.all { it.valid() } && (video == null || (videoStill && video.valid()))

        /** Рядок для реєстру й мосту: `v1;<прапорці>;hash:size:fx:fy,...[;sha256:size]`. */
        fun spec(): String =
            "v1;${if (videoStill) BANNER_FLAG_VIDEO_STILL else 0};" +
                items.joinToString(",") { "${it.hash}:${it.size}:${it.fx}:${it.fy}" } +
                (video?.let { ";${it.sha256}:${it.size}" } ?: "")

        companion object {
            /** Null — рядок не наш або кривий. */
            fun parse(spec: String): Banner? {
                val parts = spec.split(';')
                if (parts.size !in 3..4 || parts[0] != "v1") return null
                val flags = parts[1].toIntOrNull() ?: return null
                val items = parts[2].split(',').map { one ->
                    val f = one.split(':')
                    if (f.size != 4) return null
                    BannerItem(f[0], f[1].toIntOrNull() ?: return null, f[2].toIntOrNull() ?: return null, f[3].toIntOrNull() ?: return null)
                }
                val video = parts.getOrNull(3)?.let { v ->
                    val f = v.split(':')
                    BannerVideo(f.getOrNull(0) ?: return null, f.getOrNull(1)?.toIntOrNull() ?: return null)
                }
                return Banner(items, flags and BANNER_FLAG_VIDEO_STILL != 0, video).takeIf { it.valid() }
            }
        }
    }

    /** Петля банера: [sha256] — 64 hex усього файла, [blobId] — його перші 32, як у вкладень ([PeerFile]). */
    data class BannerVideo(val sha256: String, val size: Int) {
        val blobId: String get() = sha256.take(PeerFile.BLOB_ID_CHARS)

        internal fun valid(): Boolean = SHA256.matches(sha256) && size in 1..MAX_BANNER_VIDEO_BYTES
    }

    private val SHA256 = Regex("[0-9a-f]{64}")

    private val HASH = Regex("[0-9a-f]{32}")

    private fun bannerTags(banner: Banner): Pair<ByteArray, ByteArray>? {
        if (!banner.valid()) return null
        val lay = java.io.ByteArrayOutputStream()
        lay.write(BANNER_V1)
        lay.write(banner.items.size)
        lay.write(if (banner.videoStill) BANNER_FLAG_VIDEO_STILL else 0)
        val media = java.io.ByteArrayOutputStream()
        banner.items.forEach {
            lay.write(it.fx)
            lay.write(it.fy)
            media.write(hexBytes(it.hash))
            media.write((it.size shr 16) and 0xFF)
            media.write((it.size shr 8) and 0xFF)
            media.write(it.size and 0xFF)
        }
        return lay.toByteArray() to media.toByteArray()
    }

    private fun videoTag(v: BannerVideo): ByteArray =
        hexBytes(v.sha256) + byteArrayOf((v.size shr 24).toByte(), (v.size shr 16).toByte(), (v.size shr 8).toByte(), v.size.toByte())

    private fun videoOf(v: ByteArray?): BannerVideo? {
        if (v == null || v.size != BANNER_VIDEO_TAG_BYTES) return null
        val size = ((v[32].toInt() and 0xFF) shl 24) or ((v[33].toInt() and 0xFF) shl 16) or
            ((v[34].toInt() and 0xFF) shl 8) or (v[35].toInt() and 0xFF)
        return BannerVideo(v.copyOfRange(0, 32).joinToString("") { "%02x".format(it) }, size).takeIf { it.valid() }
    }

    private fun bannerOf(lay: ByteArray?, media: ByteArray?, video: ByteArray? = null): Field<Banner> {
        lay ?: return Field.Absent
        if (lay.isEmpty()) return Field.Cleared
        media ?: return Field.Absent
        if (lay.size < 3 || lay[0].toInt() != BANNER_V1) return Field.Absent
        val n = lay[1].toInt() and 0xFF
        if (n !in 1..MAX_BANNER_ITEMS || lay.size != 3 + 2 * n || media.size != n * BANNER_MEDIA_ITEM) return Field.Absent
        val flags = lay[2].toInt() and 0xFF
        val items = (0 until n).map { k ->
            val o = k * BANNER_MEDIA_ITEM
            val size = ((media[o + 16].toInt() and 0xFF) shl 16) or ((media[o + 17].toInt() and 0xFF) shl 8) or
                (media[o + 18].toInt() and 0xFF)
            BannerItem(
                media.copyOfRange(o, o + 16).joinToString("") { "%02x".format(it) },
                size,
                lay[3 + 2 * k].toInt() and 0xFF,
                lay[4 + 2 * k].toInt() and 0xFF,
            )
        }
        val still = flags and BANNER_FLAG_VIDEO_STILL != 0
        // Відео з покаліченим тегом — лише перший кадр, як у старих збірках, а не весь банер «не повідомляв».
        val b = Banner(items, still, videoOf(video)?.takeIf { still })
        return if (b.valid()) Field.Set(b) else Field.Absent
    }

    private fun hexBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // ── складання ────────────────────────────────────────────────────────

    /**
     * Null — коли якесь поле переросло свою стелю.
     *
     * Відмова видима викликачеві ДО сокета й НАЗВАНА полем: кадр, зрізаний на
     * дроті, не відмовляється — він мовчки не доїжджає, і профіль «просто не
     * оновлюється». Різати тут самим теж не можна: обрізане біо посеред слова
     * — це вже не те, що людина про себе написала.
     */
    @JvmOverloads
    fun encode(body: Body, pad: Boolean = true): String? {
        val out = java.io.ByteArrayOutputStream()
        putVar(out, T_REV, revBytes(body.rev))
        body.avatar.bytesOrNull(MAX_AVATAR_BYTES) { return null }?.let { putVar(out, T_AVATAR, it) }
        body.statusEmoji.utf8OrNull(MAX_STATUS_EMOJI_BYTES) { return null }?.let { putVar(out, T_STATUS_EMOJI, it) }
        body.statusText.utf8OrNull(MAX_STATUS_TEXT_BYTES) { return null }?.let { putVar(out, T_STATUS_TEXT, it) }
        body.bio.utf8OrNull(MAX_BIO_BYTES) { return null }?.let { putVar(out, T_BIO, it) }
        (body.name as? Field.Set)?.value?.toByteArray(Charsets.UTF_8)?.let {
            if (it.size > MAX_NAME_BYTES) return null
            if (it.isNotEmpty()) putVar(out, T_NAME, it)
        }
        when (val h = body.handle) {
            is Field.Set -> PeerDirectory.normalise(h.value)?.let { putVar(out, T_HANDLE, it.toByteArray(Charsets.UTF_8)) }
            Field.Cleared -> putVar(out, T_HANDLE, ByteArray(0))
            Field.Absent -> Unit
        }
        body.photo.bytesOrNull(MAX_PHOTO_BYTES) { return null }?.let { putVar(out, T_PHOTO, it) }
        if (body.scene in 1..0xFF) putVar(out, T_SCENE, byteArrayOf(body.scene.toByte()))
        when (val b = body.banner) {
            is Field.Set -> {
                val (lay, media) = bannerTags(b.value) ?: return null
                putVar(out, T_BANNER, lay)
                putVar(out, T_BANNER_MEDIA, media)
                b.value.video?.let { putVar(out, T_BANNER_VIDEO, videoTag(it)) }
            }
            Field.Cleared -> putVar(out, T_BANNER, ByteArray(0))
            Field.Absent -> Unit
        }
        if (body.hidesSeen) putVar(out, T_HIDE_SEEN, byteArrayOf(1))
        if (pad) padToBucket(out)
        val raw = out.toByteArray()
        return TAG + java.util.Base64.getEncoder().withoutPadding().encodeToString(raw)
    }

    private fun padToBucket(out: java.io.ByteArrayOutputStream) {
        val bucket = BUCKETS.firstOrNull { it == out.size() || it >= out.size() + 2 } ?: return
        var rem = bucket - out.size()
        if (rem == 0) return
        if (rem == 130 || rem == 16387) {
            putVar(out, T_PAD, ByteArray(0))
            rem -= 2
        }
        val padLen = when {
            rem <= 129 -> rem - 2
            rem <= 16386 -> rem - 3
            else -> rem - 4
        }
        putVar(out, T_PAD, ByteArray(padLen))
    }

    private inline fun Field<ByteArray>.bytesOrNull(cap: Int, tooBig: () -> Unit): ByteArray? =
        when (this) {
            is Field.Absent -> null
            is Field.Cleared -> ByteArray(0)
            is Field.Set -> if (value.size > cap) { tooBig(); null } else value
        }

    private inline fun Field<String>.utf8OrNull(cap: Int, tooBig: () -> Unit): ByteArray? =
        when (this) {
            is Field.Absent -> null
            is Field.Cleared -> ByteArray(0)
            is Field.Set -> {
                val b = value.toByteArray(Charsets.UTF_8)
                if (b.size > cap) { tooBig(); null } else b
            }
        }

    private fun revBytes(rev: Long): ByteArray {
        val v = rev.coerceIn(0, 0xFFFF_FFFFL)
        return byteArrayOf(
            ((v shr 24) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(),
            (v and 0xFF).toByte(),
        )
    }

    private fun putVar(out: java.io.ByteArrayOutputStream, tag: Int, value: ByteArray) {
        out.write(tag)
        var n = value.size
        // varint: сім бітів на байт, старший біт — «є продовження».
        while (true) {
            val b = n and 0x7F
            n = n ushr 7
            if (n == 0) { out.write(b); break }
            out.write(b or 0x80)
        }
        out.write(value)
    }

    // ── читання ──────────────────────────────────────────────────────────

    fun isProfile(body: String): Boolean = body.startsWith(TAG)

    /**
     * Null — кадр не наш, покалічений або обірваний.
     *
     * Обірваний відкидається ЦІЛКОМ, а не застосовується частиною: довжина
     * каже «ще N байт», а їх немає — значить переказ обірвав передачу, і
     * половина чужого біо, показана як ціле, була б неправдою про людину.
     * Невідомий тег, натомість, переступається мовчки: поля додаватимуться, і
     * старий приймач мусить читати те, що знає.
     */
    fun decode(body: String): Body? {
        if (!isProfile(body)) return null
        val raw = runCatching {
            java.util.Base64.getDecoder().decode(body.removePrefix(TAG))
        }.getOrNull() ?: return null
        var i = 0
        var rev: Long? = null
        var avatar: Field<ByteArray> = Field.Absent
        var emoji: Field<String> = Field.Absent
        var text: Field<String> = Field.Absent
        var bio: Field<String> = Field.Absent
        var name: Field<String> = Field.Absent
        var handle: Field<String> = Field.Absent
        var photo: Field<ByteArray> = Field.Absent
        var scene = 0
        var bannerLay: ByteArray? = null
        var bannerMedia: ByteArray? = null
        var bannerVideo: ByteArray? = null
        var hidesSeen = false
        while (i < raw.size) {
            val tag = raw[i].toInt() and 0xFF
            i++
            var len = 0
            var shift = 0
            while (true) {
                if (i >= raw.size) return null
                val b = raw[i].toInt() and 0xFF
                i++
                len = len or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                // Довжина, що не вміщається у розумне, — це не довжина.
                if (shift > 28) return null
            }
            if (len < 0 || i + len > raw.size) return null
            val v = raw.copyOfRange(i, i + len)
            i += len
            when (tag) {
                T_REV -> {
                    if (v.size != 4) return null
                    rev = ((v[0].toLong() and 0xFF) shl 24) or
                        ((v[1].toLong() and 0xFF) shl 16) or
                        ((v[2].toLong() and 0xFF) shl 8) or
                        (v[3].toLong() and 0xFF)
                }
                T_AVATAR -> {
                    if (v.size > MAX_AVATAR_BYTES) return null
                    avatar = if (v.isEmpty()) Field.Cleared else Field.Set(v)
                }
                T_STATUS_EMOJI -> {
                    if (v.size > MAX_STATUS_EMOJI_BYTES) return null
                    emoji = if (v.isEmpty()) Field.Cleared else Field.Set(String(v, Charsets.UTF_8))
                }
                T_STATUS_TEXT -> {
                    if (v.size > MAX_STATUS_TEXT_BYTES) return null
                    text = if (v.isEmpty()) Field.Cleared else Field.Set(String(v, Charsets.UTF_8))
                }
                T_BIO -> {
                    if (v.size > MAX_BIO_BYTES) return null
                    bio = if (v.isEmpty()) Field.Cleared else Field.Set(String(v, Charsets.UTF_8))
                }
                T_NAME -> {
                    if (v.size > MAX_NAME_BYTES) return null
                    if (v.isNotEmpty()) name = Field.Set(String(v, Charsets.UTF_8))
                }
                T_HANDLE -> handle = if (v.isEmpty()) {
                    Field.Cleared
                } else {
                    PeerDirectory.normalise(String(v, Charsets.UTF_8))?.let { Field.Set(it) } ?: Field.Absent
                }
                T_PHOTO -> {
                    if (v.size > MAX_PHOTO_BYTES) return null
                    photo = if (v.isEmpty()) Field.Cleared else Field.Set(v)
                }
                // Покалічене значення — «не повідомляв», а не відмова всього профілю.
                T_SCENE -> scene = if (v.size == 1) v[0].toInt() and 0xFF else 0
                // Банер з майбутньої версії (довший) — «не повідомляв», імʼя й фото людини лишаються.
                T_BANNER -> bannerLay = v.takeIf { it.size <= 3 + 2 * MAX_BANNER_ITEMS }
                T_BANNER_MEDIA -> bannerMedia = v.takeIf { it.size <= MAX_BANNER_ITEMS * BANNER_MEDIA_ITEM }
                T_BANNER_VIDEO -> bannerVideo = v
                T_HIDE_SEEN -> hidesSeen = v.size == 1 && v[0].toInt() == 1
                // Невідомий тег — переступаємо. Саме заради цього тут TLV.
                else -> Unit
            }
        }
        val r = rev ?: return null
        return Body(r, avatar, emoji, text, bio, name, handle, photo, scene, bannerOf(bannerLay, bannerMedia, bannerVideo), hidesSeen)
    }

    /**
     * Власне імʼя, яке влазить у [T_NAME]: спершу межа [PeerName.cap], далі
     * по графемі з кінця, доки байти не ляжуть. Порожнє — [Field.Absent].
     */
    fun fitName(raw: String?): Field<String> {
        var units = PeerName.graphemes(PeerName.cap(raw.orEmpty().trim()))
        while (units.joinToString("").toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) {
            units = units.dropLast(1)
        }
        val text = units.joinToString("").trim()
        return if (text.isEmpty()) Field.Absent else Field.Set(text)
    }

    /**
     * Чи цей кадр новіший за те, що ми вже знаємо про цього відправника.
     *
     * Винесено окремо, щоб правило можна було довести тестом: усередині
     * приймача воно доводиться лише оком, а повтор переказу виглядає на
     * екрані точно так само, як свіже оновлення.
     */
    fun isNewer(incomingRev: Long, knownRev: Long?): Boolean =
        knownRev == null || incomingRev > knownRev

    // ── що це за байти ───────────────────────────────────────────────────

    /**
     * Роди, які контракт називає для мініатюри. Більше немає жодного.
     *
     * Кадр НЕ несе типу зображення, і це не недогляд: тип, узятий зі слів
     * відправника, — це рядок, який поверхня вставить у `data:`-адресу, тобто
     * чуже слово, що керує тим, як браузер прочитає чужі байти. Тут тип
     * впізнається за самими байтами, тож підписати WebP «як завгодно» не
     * вийде.
     */
    enum class ImageKind(val mime: String) {
        WEBP("image/webp"),
        JPEG("image/jpeg"),
    }

    /**
     * Null — байти не WebP і не JPEG, тобто не те, що контракт дозволив везти.
     *
     * Це ЧЕТВЕРТИЙ стан аватара на екрані, і він мусить бути окремим: «не
     * приїхало», «людина стерла», «ось воно» і «приїхало щось, чого я не
     * вмію прочитати». Намалювати останнє як перше означало б чекати на кадр,
     * який уже прийшов.
     */
    fun imageKind(bytes: ByteArray): ImageKind? = when {
        // FF D8 FF — SOI плюс перший маркер; коротшого валідного JPEG немає.
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte() -> ImageKind.JPEG
        // RIFF....WEBP — рід лежить у байтах 8..11, за довжиною контейнера.
        bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte() -> ImageKind.WEBP
        else -> null
    }
}
