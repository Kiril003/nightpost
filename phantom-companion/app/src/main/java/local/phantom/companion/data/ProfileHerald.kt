package local.phantom.companion.data

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import local.phantom.companion.core.net.peer.PeerBanner
import local.phantom.companion.core.net.peer.PeerFile
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerProfile

/**
 * Свій профіль — тримає телефон і доносить КОЖНОМУ контакту, зокрема тим, хто
 * зʼявився після зміни. Доти кадр ішов лише в мить зміни, і новий контакт не
 * бачив фото, доки людина не змінить його ще раз.
 *
 * Кадр завжди повний: частковий (лише імʼя) поверх черги з фото губив би фото,
 * якби новіший rev доїхав першим.
 */
class ProfileHerald(
    private val dir: File,
    private val legacyRev: () -> Long,
    private val people: () -> List<PeerIdentity>,
    private val blocked: () -> Set<String>,
    private val name: (profileId: String?) -> String?,
    private val handle: (profileId: String?) -> PeerProfile.Field<String>,
    private val send: (peerId: String, frame: String) -> Boolean,
    private val faceFor: (peerId: String) -> FaceOverride? = { null },
    private val activeProfileId: () -> String? = { null },
    private val faceProfileId: () -> String? = { null },
    /** Старий ключ папки (id профілю нативного сховища), якщо [dirKey] — головний профіль; інші профілі його не мають. */
    private val legacyProfileId: (dirKey: String) -> String? = { null },
    /** Профіль, якому належить розмова з людиною; null — активний (міст v1 або без профілів). */
    private val ownerOf: (peerId: String) -> String? = { null },
    /** Що сховати від людини: [VisibilityStore.HIDE_PHOTO], [VisibilityStore.HIDE_SEEN]. */
    private val hides: (peerId: String) -> Int = { 0 },
) {

    data class Outcome(
        val rev: Long,
        val frameBytes: Int,
        val sent: List<String>,
        val skipped: List<Pair<String, String>>,
    )

    @Serializable
    private data class State(
        val rev: Long = 0L,
        // null — ніколи не задавали, "" — прибрано.
        val emoji: String? = null,
        val text: String? = null,
        val bio: String? = null,
        val thumb: Boolean? = null,
        val photo: Boolean? = null,
        /** Лише для переходу на [Ledger]: відмітки доставки старих збірок. */
        val sent: Map<String, Long> = emptyMap(),
        /**
         * Рівень сцени, який цей телефон уже ОГОЛОСИВ контактам у профілі
         * ([PeerProfile.SCENE_V1]). Менший за поточний — оголошення ще не їхало:
         * без нього контакти на збірці 18 не знали б, що сюди можна слати
         * позначки карток, доки людина випадково не змінила б профіль.
         */
        val scene: Int = 0,
        /** Опис свого банера ([PeerProfile.Banner.spec]): null — не задавали, "" — прибрано. */
        val banner: String? = null,
        val bannerSent: Map<String, String> = emptyMap(),
        /** Банери профілів «Я» (id профілю → опис); [banner] — коли профілю нема. */
        val banners: Map<String, String> = emptyMap(),
    )

    /** Що людина востаннє отримала: профіль [p] у версії [c], на дроті як rev [w]. */
    @Serializable
    private data class Mark(val p: String, val c: Long, val w: Long, val v: Int = 0)

    /**
     * Один лічильник rev на всі профілі: приймач відкидає кадр, не новіший за
     * той, що вже має від нас, а людина, перенесена в інший профіль, могла
     * бачити rev, більший за rev нового власника.
     */
    @Serializable
    private data class Ledger(
        val rev: Long = 0L,
        val marks: Map<String, Mark> = emptyMap(),
        /** Ключ людини → опис банера, чиї фото їй уже пішли. */
        val banners: Map<String, String> = emptyMap(),
    )

    private class Owner(val key: String, val id: String?)

    private val json = Json { ignoreUnknownKeys = true }
    private val states = HashMap<String, State>()

    init {
        stateOf(currentKey())
    }

    private var ledger: Ledger = loadLedger()

    private fun currentKey(): String = dirKey(activeProfileId())

    private fun faceKey(): String = dirKey(faceProfileId())

    private fun bannerKeyOf(key: String): String = if (key == currentKey()) faceKey() else key

    private fun resolveDir(key: String): File {
        if (key.isEmpty()) return dir
        val sub = File(dir, "p_$key")
        val legacy = legacyProfileId(key)?.let(::dirKey)?.takeIf { it.isNotEmpty() && it != key }
        if (legacy != null && !File(sub, "state.json").isFile) {
            val old = File(dir, "p_$legacy")
            if (File(old, "state.json").isFile) {
                sub.mkdirs()
                for (name in listOf("state.json", "thumb.img", "photo.img")) {
                    val f = File(old, name)
                    if (f.isFile && !f.renameTo(File(sub, name))) {
                        f.copyTo(File(sub, name), overwrite = true)
                        f.delete()
                    }
                }
                states.remove(key)
                states.remove(legacy)
            }
        }
        if (!File(sub, "state.json").isFile && File(dir, "state.json").isFile &&
            dir.listFiles()?.none { it.isDirectory && it.name.startsWith("p_") } == true
        ) {
            sub.mkdirs()
            for (name in listOf("state.json", "thumb.img", "photo.img")) {
                val legacyFile = File(dir, name)
                if (legacyFile.isFile) {
                    val dst = File(sub, name)
                    if (!legacyFile.renameTo(dst)) {
                        dst.writeBytes(legacyFile.readBytes())
                        legacyFile.delete()
                    }
                }
            }
            states.remove(key)
            states.remove("")
        }
        return sub
    }

    @Synchronized
    private fun stateOf(key: String): State {
        val d = resolveDir(key)
        return states.getOrPut(key) { read(d) }
    }

    private fun active(): State = stateOf(currentKey())

    private fun put(key: String, s: State) {
        states[key] = s
        val pDir = resolveDir(key)
        pDir.mkdirs()
        writeAtomic(File(pDir, "state.json"), json.encodeToString(s))
    }

    private fun bannerDirOf(key: String): File =
        File(resolveDir(key), bannerKeyOf(key).let { if (it.isEmpty()) "banner" else "banner_$it" })

    /** Опис банера профілю; профіль без свого банера при чужому — прибрано, щоб у людей не лишився банер іншого профілю. */
    private fun bannerOf(s: State, k: String): String? {
        if (k.isEmpty()) return s.banner
        return s.banners[k] ?: if (s.banner != null || s.banners.isNotEmpty()) "" else null
    }

    /** Свій банер для «Я»; null — банера немає. */
    fun ownBanner(): PeerProfile.Banner? = bannerOf(active(), faceKey())?.let { PeerProfile.Banner.parse(it) }

    /** Фото свого банера за хешем. */
    fun ownBannerFile(hash: String): File? = bannerFileOf(currentKey(), hash)

    private fun bannerFileOf(key: String, hash: String): File? =
        if (HASH.matches(hash)) File(bannerDirOf(key), hash).takeIf { it.isFile } else null

    /** Відео свого банера: людям їде, лише коли воно в описі ([PeerProfile.Banner.video], до 8 МБ); інакше — перший кадр. */
    fun ownBannerVideo(): File? = File(bannerDirOf(currentKey()), "video").takeIf { ownBanner()?.videoStill == true && it.isFile }

    /** Петля, яку можна віддати глядачеві: опис і файл, що з ним збігається розміром. */
    fun sharedBannerVideo(peerId: String? = null): Pair<PeerProfile.BannerVideo, File>? {
        val key = peerId?.let { ownerFor(it).key } ?: currentKey()
        val banner = bannerOf(stateOf(key), bannerKeyOf(key))?.let { PeerProfile.Banner.parse(it) } ?: return null
        val v = banner.video?.takeIf { banner.videoStill } ?: return null
        return File(bannerDirOf(key), "video").takeIf { it.isFile && it.length() == v.size.toLong() }?.let { v to it }
    }

    /** Одне фото свого банера: байти вже стиснуті до [PeerBanner.MAX_BYTES], фокус 0..255. */
    class BannerPhoto(val bytes: ByteArray, val fx: Int = 128, val fy: Int = 128)

    /**
     * Новий банер: до шести фото в порядку власника; [photos] null або порожній —
     * банер прибрано. [video] — лише коли перше фото є першим кадром відео.
     * Null — фото поза контрактом (не картинка або понад стелю), нічого не змінено.
     */
    @Synchronized
    fun publishBanner(photos: List<BannerPhoto>?, video: File? = null): Outcome? {
        val list = photos.orEmpty()
        if (list.size > PeerProfile.MAX_BANNER_ITEMS || list.any { PeerBanner.encode(it.bytes) == null }) return null
        val banner = list.takeIf { it.isNotEmpty() }?.let { l ->
            PeerProfile.Banner(
                l.map { PeerProfile.BannerItem(PeerBanner.hashOf(it.bytes), it.bytes.size, it.fx.coerceIn(0, 255), it.fy.coerceIn(0, 255)) },
                videoStill = video != null,
                video = video?.let(::describeVideo),
            )
        }
        val key = currentKey()
        val d = bannerDirOf(key)
        d.mkdirs()
        val keep = banner?.items?.map { it.hash }?.toSet().orEmpty()
        list.forEach { keepBytes(PeerProfile.Field.Set(it.bytes), File(d, PeerBanner.hashOf(it.bytes))) }
        val clip = File(d, "video")
        if (video != null && video.canonicalPath != clip.canonicalPath) video.copyTo(clip, overwrite = true)
        if (video == null) clip.delete()
        d.listFiles()?.forEach { if (it.name != "video" && it.name !in keep) it.delete() }
        val spec = banner?.spec() ?: ""
        val k = faceKey()
        val s = stateOf(key)
        put(
            key,
            if (k.isEmpty()) {
                s.copy(rev = nextRev(0L, s), banner = spec, scene = PeerProfile.SCENE_V1)
            } else {
                s.copy(rev = nextRev(0L, s), banners = s.banners + (k to spec), scene = PeerProfile.SCENE_V1)
            },
        )
        return sweep()
    }

    private fun describeVideo(file: File): PeerProfile.BannerVideo? {
        val size = file.length()
        if (size <= 0 || size > PeerProfile.MAX_BANNER_VIDEO_BYTES) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return PeerProfile.BannerVideo(PeerFile.sha256Hex(bytes), bytes.size)
    }

    val rev: Long get() = active().rev

    val statusText: String get() = active().text.orEmpty()

    val bio: String get() = active().bio.orEmpty()

    val hasClearingOrPhoto: Boolean
        get() = active().let { it.photo != null || it.thumb != null }

    /** Своє фото для «Я»: велике, а як його нема — мініатюра. */
    fun ownPhotoFile(): File? {
        val s = active()
        val pDir = resolveDir(currentKey())
        return File(pDir, "photo.img").takeIf { s.photo == true && it.isFile }
            ?: File(pDir, "thumb.img").takeIf { s.thumb == true && it.isFile }
    }

    @Synchronized
    fun photoFileOf(faceName: String): File? {
        val key = faceName.removePrefix(OWN_FACE).takeIf { faceName.startsWith(OWN_FACE) && it.isNotEmpty() && !UNSAFE.containsMatchIn(it) }
            ?: return null
        if (key == currentKey()) return ownPhotoFile()
        // Чужий профіль лише читаємо: stateOf() переносить старі файли в папку, яку відкрив.
        val pDir = File(dir, "p_$key").takeIf { it.isDirectory } ?: return null
        val s = states[key] ?: read(pDir)
        return File(pDir, "photo.img").takeIf { s.photo == true && it.isFile }
            ?: File(pDir, "thumb.img").takeIf { s.thumb == true && it.isFile }
    }

    fun photoUrls(profileIds: List<String>, base: String): Map<String, String> =
        profileIds.mapNotNull { id ->
            val key = dirKey(id).takeIf { it == id } ?: return@mapNotNull null
            photoFileOf(OWN_FACE + key)?.let { id to "$base$OWN_FACE$key?v=${it.lastModified()}" }
        }.toMap()

    @Synchronized
    fun clearProfile(profileId: String) {
        val safe = dirKey(profileId)
        if (safe.isEmpty()) return
        File(dir, "p_$safe").deleteRecursively()
        states.remove(safe)
    }

    /** [fields] несе лише змінене; Absent лишає збережене. rev, імʼя й нік тут ігноруються. Змінює активний профіль. */
    @Synchronized
    fun publish(asked: Long, fields: PeerProfile.Body): Outcome? {
        val key = currentKey()
        val pDir = resolveDir(key)
        // Нова мініатюра без нового фото — старе фото вже не ця людина.
        val photo = if (fields.photo is PeerProfile.Field.Absent && fields.avatar !is PeerProfile.Field.Absent) {
            PeerProfile.Field.Cleared
        } else {
            fields.photo
        }
        if (encode(fields.copy(rev = 1, photo = photo)) == null) return null
        keepBytes(fields.avatar, File(pDir, "thumb.img"))
        keepBytes(photo, File(pDir, "photo.img"))
        val s = stateOf(key)
        put(
            key,
            s.copy(
                rev = nextRev(asked, s),
                emoji = fields.statusEmoji.merge(s.emoji),
                text = fields.statusText.merge(s.text),
                bio = fields.bio.merge(s.bio),
                thumb = fields.avatar.present(s.thumb),
                photo = photo.present(s.photo),
                scene = PeerProfile.SCENE_V1,
            ),
        )
        return sweep()
    }

    /** Змінилось спільне для всіх профілів (імʼя облікового запису, нік) — нова версія кожному обличчю, яке хтось бачить. */
    @Synchronized
    fun bump(): Outcome? {
        val keys = linkedSetOf(currentKey())
        people().forEach { keys += ownerFor(it.id).key }
        keys.forEach(::bumpKey)
        return sweep()
    }

    /** Змінилось обличчя одного профілю: нова версія лише його людям. */
    @Synchronized
    fun bumpProfile(profileId: String): Outcome? {
        val key = dirKey(profileId)
        if (key.isEmpty()) return null
        bumpKey(key)
        return sweep()
    }

    @Synchronized
    fun onMirror(change: ProfilesMirror.Change): Outcome? {
        if (change.legacy) return bump()
        change.faces.forEach { id -> dirKey(id).takeIf { it.isNotEmpty() }?.let(::bumpKey) }
        return sweep()
    }

    private fun bumpKey(key: String) {
        val s = stateOf(key)
        put(key, s.copy(rev = nextRev(0L, s), scene = PeerProfile.SCENE_V1))
    }

    private fun ownerFor(peerId: String): Owner {
        val id = ownerOf(peerId)?.takeIf { it.isNotBlank() }
        return if (id == null) Owner(currentKey(), faceProfileId()) else Owner(dirKey(id), id)
    }

    private fun isLegacy(o: Owner): Boolean =
        o.key.isEmpty() || (o.id == null && faceProfileId() == null) || legacyProfileId(o.key) != null

    private class Task(val p: PeerIdentity, val owner: Owner, val fresh: Boolean, val v: Int)

    /**
     * Дослати кожній людині обличчя профілю, якому належить розмова з нею, якщо
     * вона його ще не має. Відмітка ставиться лише тим, чий реєстр нас уже
     * тримає: чужинцю кадр не застосується, і відмічений він не отримав би його
     * ніколи.
     */
    @Synchronized
    fun sweep(): Outcome? {
        val persons = people().filter { it.kind == PeerIdentity.Kind.PERSON }
        val owners = persons.associate { it.id to ownerFor(it.id) }
        // Оголошення можливостей їде першим же кадром профілю нової збірки — навіть
        // тим, хто профілю ще не публікував: новий rev і нікому не «доставлено».
        (owners.values.map { it.key } + currentKey()).toSet().forEach { k ->
            val s = stateOf(k)
            if (s.scene < PeerProfile.SCENE_V1) put(k, s.copy(rev = nextRev(0L, s), scene = PeerProfile.SCENE_V1))
        }
        val no = blocked()
        val skipped = ArrayList<Pair<String, String>>()
        val tasks = ArrayList<Task>()
        persons.forEach { p ->
            val o = owners.getValue(p.id)
            val s = stateOf(o.key)
            val m = ledger.marks[keyOf(p)]
            val v = hides(p.id)
            val current = m != null && m.p == o.key && m.c == s.rev && m.v == v
            val spec = bannerOf(s, bannerKeyOf(o.key)).orEmpty()
            when {
                p.dhPubKey.isBlank() -> skipped += p.id to "no_key"
                p.id in no -> skipped += p.id to "blocked"
                s.rev <= 0L -> Unit
                current && (spec.isEmpty() || p.introducedAtMs <= 0L || ledger.banners[keyOf(p)] == spec) -> Unit
                else -> tasks += Task(p, o, fresh = !current && m != null && m.w >= s.rev, v)
            }
        }
        val wireRev = HashMap<String, Long>()
        tasks.groupBy { it.owner.key }.forEach { (k, group) ->
            val s = stateOf(k)
            wireRev[k] = if (group.any { it.fresh }) nextRev(0L, s) else s.rev
        }
        val frames = HashMap<Triple<String, FaceOverride?, Int>, String?>()
        val bannerCache = HashMap<String, Pair<String, List<String>>>()
        val sent = ArrayList<String>()
        var marks = ledger.marks
        var bannerMarks = ledger.banners
        var frameBytes = encode(bodyOf(active(), Owner(currentKey(), faceProfileId()), currentKey()))?.toByteArray(Charsets.UTF_8)?.size ?: 0
        for (t in tasks) {
            val p = t.p
            val k = t.owner.key
            val s = stateOf(k)
            val w = wireRev.getValue(k)
            val face = faceFor(p.id)
            val frame = frames.getOrPut(Triple(k, face, t.v)) { encode(withheld(applyFace(bodyOf(s, t.owner, k).copy(rev = w), face), t.v)) }
            if (frame == null) {
                skipped += p.id to "face"
                continue
            }
            if (!send(p.id, frame)) {
                skipped += p.id to "refused"
                continue
            }
            sent += p.id
            frameBytes = frame.toByteArray(Charsets.UTF_8).size
            if (p.introducedAtMs > 0L) marks = marks + (keyOf(p) to Mark(k, s.rev, w, t.v))
            val (spec, photos) = bannerCache.getOrPut(k) {
                val sp = bannerOf(s, bannerKeyOf(k)).orEmpty()
                sp to bannerFrames(sp, k)
            }
            val whole = photos.size == (PeerProfile.Banner.parse(spec)?.items?.size ?: 0)
            // Фото — після опису і лише знайомим: чужинець опису не застосує, і фото йому — марні 200 КБ.
            if (spec.isNotEmpty() && p.introducedAtMs > 0L && bannerMarks[keyOf(p)] != spec &&
                photos.all { send(p.id, it) } && whole
            ) {
                bannerMarks = bannerMarks + (keyOf(p) to spec)
            }
        }
        if (marks != ledger.marks || bannerMarks != ledger.banners) {
            ledger = ledger.copy(marks = marks, banners = bannerMarks)
            saveLedger()
        }
        val rev = active().rev
        if (rev <= 0L && sent.isEmpty()) return null
        return Outcome(rev, frameBytes, sent, skipped)
    }

    /** Реєстр, засув і готовність сітки — лише коли змінилось, хто має отримати. */
    fun watch(
        scope: CoroutineScope,
        roster: Flow<List<PeerIdentity>>,
        blockedFlow: Flow<Set<String>>,
        ready: Flow<Boolean>,
        visibility: Flow<Any?> = kotlinx.coroutines.flow.flowOf(null),
    ) {
        scope.launch(Dispatchers.IO) {
            combine(roster, blockedFlow, ready, visibility) { r, b, ok, vis ->
                if (!ok) {
                    null
                } else {
                    r.filter { it.kind == PeerIdentity.Kind.PERSON && it.dhPubKey.isNotBlank() }
                        .map { "${keyOf(it)}:${it.introducedAtMs > 0L}:${it.id in b}" }
                        .sorted() + vis.hashCode().toString()
                }
            }
                .distinctUntilChanged()
                .collect { if (it != null) runCatching { sweep() } }
        }
    }

    /** Не головний профіль ніколи не лишає поле Absent: інакше в людини лишилось би фото чи біо іншого профілю. */
    private fun bodyOf(s: State, o: Owner, key: String): PeerProfile.Body {
        val legacy = isLegacy(o)
        val pDir = resolveDir(key)
        fun <T> whole(f: PeerProfile.Field<T>): PeerProfile.Field<T> =
            if (!legacy && f is PeerProfile.Field.Absent) PeerProfile.Field.Cleared else f
        return PeerProfile.Body(
            rev = s.rev,
            avatar = whole(bytesField(s.thumb, File(pDir, "thumb.img"))),
            statusEmoji = whole(textField(s.emoji)),
            statusText = whole(textField(s.text)),
            bio = whole(textField(s.bio)),
            name = PeerProfile.fitName(name(o.id)),
            handle = handle(o.id),
            photo = whole(bytesField(s.photo, File(pDir, "photo.img"))),
            scene = PeerProfile.SCENE_V1,
            banner = whole(
                when (val b = bannerOf(s, bannerKeyOf(key))) {
                    null -> PeerProfile.Field.Absent
                    "" -> PeerProfile.Field.Cleared
                    else -> PeerProfile.Banner.parse(b)?.let { PeerProfile.Field.Set(it) } ?: PeerProfile.Field.Absent
                },
            ),
        )
    }

    /** Кадри фото банера; фото, якого вже нема на диску, тихо пропускається — опис його все одно назве. */
    private fun bannerFrames(spec: String?, key: String): List<String> {
        val b = spec?.let { PeerProfile.Banner.parse(it) } ?: return emptyList()
        return b.items.mapNotNull { i -> bannerFileOf(key, i.hash)?.let { f -> runCatching { PeerBanner.encode(f.readBytes()) }.getOrNull() } }
    }

    private fun applyFace(base: PeerProfile.Body, face: FaceOverride?): PeerProfile.Body {
        if (face == null) return base
        return base.copy(
            name = if (face.name != null) PeerProfile.fitName(face.name) else base.name,
            statusText = if (face.status != null) textField(face.status) else base.statusText,
            bio = if (face.bio != null) textField(face.bio) else base.bio,
        )
    }

    private fun withheld(body: PeerProfile.Body, v: Int): PeerProfile.Body = body.copy(
        avatar = if (v and VisibilityStore.HIDE_PHOTO != 0) PeerProfile.Field.Cleared else body.avatar,
        photo = if (v and VisibilityStore.HIDE_PHOTO != 0) PeerProfile.Field.Cleared else body.photo,
        hidesSeen = v and VisibilityStore.HIDE_SEEN != 0,
    )

    private fun encode(body: PeerProfile.Body): String? = PeerProfile.encode(body)

    private fun nextRev(asked: Long, s: State): Long {
        val r = maxOf(ledger.rev, s.rev, legacyRev(), asked, 0L) + 1
        ledger = ledger.copy(rev = r)
        saveLedger()
        return r
    }

    private fun keyOf(p: PeerIdentity) = p.id + ":" + p.dhPubKey.take(16)

    private fun textField(v: String?): PeerProfile.Field<String> = when {
        v == null -> PeerProfile.Field.Absent
        v.isEmpty() -> PeerProfile.Field.Cleared
        else -> PeerProfile.Field.Set(v)
    }

    private fun bytesField(present: Boolean?, file: File): PeerProfile.Field<ByteArray> = when (present) {
        null -> PeerProfile.Field.Absent
        false -> PeerProfile.Field.Cleared
        true -> runCatching { file.readBytes() }.getOrNull()?.let { PeerProfile.Field.Set(it) } ?: PeerProfile.Field.Absent
    }

    private fun PeerProfile.Field<String>.merge(held: String?): String? = when (this) {
        PeerProfile.Field.Absent -> held
        PeerProfile.Field.Cleared -> ""
        is PeerProfile.Field.Set -> value
    }

    private fun PeerProfile.Field<ByteArray>.present(held: Boolean?): Boolean? = when (this) {
        PeerProfile.Field.Absent -> held
        PeerProfile.Field.Cleared -> false
        is PeerProfile.Field.Set -> true
    }

    private fun keepBytes(field: PeerProfile.Field<ByteArray>, file: File) {
        val parent = file.parentFile ?: dir
        when (field) {
            PeerProfile.Field.Absent -> Unit
            PeerProfile.Field.Cleared -> file.delete()
            is PeerProfile.Field.Set -> {
                parent.mkdirs()
                val tmp = File(parent, file.name + ".tmp")
                tmp.writeBytes(field.value)
                if (!tmp.renameTo(file)) {
                    file.writeBytes(field.value)
                    tmp.delete()
                }
            }
        }
    }

    private fun read(targetDir: File): State =
        runCatching { json.decodeFromString<State>(File(targetDir, "state.json").readText()) }.getOrNull() ?: State()

    /** Перший старт після переходу: rev і відмітки — з того, що лишили старі збірки в папках профілів. */
    private fun loadLedger(): Ledger {
        runCatching { json.decodeFromString<Ledger>(File(dir, LEDGER).readText()) }.getOrNull()?.let { return it }
        val found = listOf("" to dir) +
            dir.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("p_") }.map { it.name.removePrefix("p_") to it }
        var rev = runCatching(legacyRev).getOrDefault(0L)
        val marks = HashMap<String, Mark>()
        val banners = HashMap<String, String>()
        for ((k, d) in found) {
            if (!File(d, "state.json").isFile) continue
            val s = read(d)
            rev = maxOf(rev, s.rev)
            for ((peer, r) in s.sent) {
                if (r <= (marks[peer]?.w ?: -1L)) continue
                marks[peer] = Mark(k, r, r)
                s.bannerSent[peer]?.let { banners[peer] = it } ?: banners.remove(peer)
            }
        }
        return Ledger(rev, marks, banners)
    }

    private fun saveLedger() {
        dir.mkdirs()
        writeAtomic(File(dir, LEDGER), json.encodeToString(ledger))
    }

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }

    companion object {
        private val UNSAFE = Regex("[^A-Za-z0-9_-]")
        private val HASH = Regex("[0-9a-f]{32}")
        private const val LEDGER = "ledger.json"
        const val OWN_FACE = "me-"

        fun dirKey(id: String?): String = id?.trim()?.takeIf { it.isNotEmpty() }?.replace(UNSAFE, "_").orEmpty()
    }
}
