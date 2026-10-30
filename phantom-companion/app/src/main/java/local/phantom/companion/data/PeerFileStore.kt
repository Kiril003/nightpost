package local.phantom.companion.data

import java.io.File
import local.phantom.companion.core.net.peer.PeerFile
import local.phantom.companion.core.net.peer.PeerFileRoute
import local.phantom.companion.core.net.peer.PeerMessage

/**
 * Байти вкладень на цьому телефоні.
 *
 * Живуть у власному каталозі застосунку — `filesDir/msgr/<розмова>/<блоб>` —
 * і більше ніде. Не в спільній галереї: фотографія з розмови не має
 * зʼявлятись у чужих переглядачах разом із рештою знімків, і не має пережити
 * видалення застосунку. Каталог на розмову — щоб стерти розмову означало
 * стерти каталог, а не полювати на рядки.
 *
 * Опису файла тут НЕМАЄ. Він у тілі листа, разом з рештою листів: імʼя, розмір,
 * тип, відбиток і ключ веб уже кладе в `body`, і другий примірник цього на
 * диску був би другим місцем, де правда розходиться з першим.
 */
class PeerFileStore(
    private val filesDir: File,
    /** Опис за ідентифікатором блоба — читається з журналу листів. */
    private val manifestOf: (blobId: String) -> PeerFile.Attachment?,
) {

    companion object {
        /** Опис за блобом із журналу — і з листів розмови вдвох, і з групових. */
        fun manifestIn(log: List<PeerMessage>, blobId: String): PeerFile.Attachment? =
            log.asSequence().mapNotNull { PeerFile.carried(it.body) }.firstOrNull { it.blobId == blobId }

        /** Недозібрані шматки. Крапка на початку — щоб не сплутати з блобом. */
        private const val PARTS = ".part"

        /** Зіпсоване при збиранні: слід, який мусить пережити перезапуск. */
        private const val BROKEN = ".bad"

        /** Свої байти поза розмовами (петля банера): з них ріжуться шматки кожному глядачеві. */
        private const val SHELF = "_shelf"

        /**
         * Скільки шматків обіцяв кадр. Імʼя нечислове навмисне: збирання
         * перебирає саме числові імена, тож мітка йому не трапиться.
         */
        private const val TOTAL = "n"
    }

    private fun root(): File = File(filesDir, PeerFileRoute.DIR)

    private fun convDir(conversationId: String): File? =
        if (PeerFileRoute.isConversationId(conversationId)) {
            File(root(), conversationId)
        } else {
            null
        }

    /** Стан вкладення так, як його бачить людина. */
    enum class Fate { READY, RECEIVING, BROKEN, ABSENT }

    // ── свої байти ───────────────────────────────────────────────────────

    /**
     * Покласти байти, які щойно дав веб, і повернути ідентифікатор.
     *
     * Ідентифікатор народжується ТУТ, а не приходить іззовні: він стає шляхом,
     * яким веб потім проситиме байти, і рядок з чужих рук у ролі шляху — це
     * рівно та вада, від якої стереже [PeerFileRoute].
     */
    fun put(conversationId: String, bytes: ByteArray): String? {
        if (PeerFile.refuse(bytes.size) != null) return null
        val dir = convDir(conversationId) ?: return null
        val blobId = PeerFile.newBlobId()
        val target = File(dir, blobId)
        dir.mkdirs()
        return runCatching {
            target.writeBytes(bytes)
            blobId
        }.getOrNull()
    }

    /** Файл із байтами, або null. Перебір каталогів — розмов десятки. */
    fun blob(blobId: String): File? {
        if (!PeerFile.isBlobId(blobId)) return null
        val convs = root().listFiles()?.filter { it.isDirectory } ?: return null
        return convs.asSequence()
            .map { File(it, blobId) }
            .firstOrNull { it.isFile && it.length() > 0 }
    }

    fun bytes(blobId: String): ByteArray? =
        blob(blobId)?.let { runCatching { it.readBytes() }.getOrNull() }

    /**
     * Зібралось, але відбиток не збігся. Окремо від «ще немає», бо для людини
     * це протилежні речі: одне мине саме, друге не мине ніколи.
     */
    fun isBroken(blobId: String): Boolean {
        if (!PeerFile.isBlobId(blobId)) return false
        val convs = root().listFiles()?.filter { it.isDirectory } ?: return false
        return convs.any { File(it, blobId + BROKEN).isFile }
    }

    // ── відправлення ─────────────────────────────────────────────────────

    /**
     * Файл, розкладений на тіла листів.
     *
     * Повертає саме тіла, а не надсилає: класти їх у чергу мусить одна дія
     * ([PeerMesh.sendMany]), бо журнал лягає на диск на кожну зміну й
     * поодинокі виклики коштували б сотні переписувань.
     *
     * Порожній список — байтів немає. Тоді лист-опис поїде сам, і приймач
     * чесно показуватиме, що вкладення не приїхало, замість вдавати, що воно є.
     */
    fun bodiesFor(attachment: PeerFile.Attachment): List<String> {
        val bytes = bytes(attachment.blobId) ?: return emptyList()
        val total = PeerFile.chunkCount(bytes.size)
        return (0 until total).mapNotNull { i ->
            PeerFile.sliceAt(bytes, i)?.let { PeerFile.encodeChunk(attachment.blobId, i, total, it) }
        }
    }

    /**
     * Справжнє тіло шматка за посиланням із журналу.
     *
     * **Чому це можливо, і чому саме тут.** Байти вкладення лежать на диску
     * ЗАВЖДИ — і у відправника ([put], ще до першого кадру), і в отримувача
     * ([keepPart], а після складання — цілим блобом). Другий їх примірник у
     * журналі черги був чистою платою: його тримали, щоб через мить прочитати
     * назад. Саме він і ставив стелю на файл — не дріт і не ввічливість.
     *
     * Тепер журнал тримає посилання ([PeerFile.vaultedChunk]), а байти
     * дістаються звідси, за мить до запечатування конверта.
     *
     * Два джерела, і обидва потрібні: зібраний блоб (наш файл або вже
     * складений чужий) ріжеться назад тим самим [PeerFile.sliceAt] — межі
     * однакові за побудовою, — а недозібране береться з `.part/<номер>`.
     * Порядок саме такий: зібраний блоб переживає чистку `.part`.
     *
     * Null — байтів немає НІДЕ. Це не «нічого страшного»: викликач мусить
     * зробити з цього видиму поразку, а не надіслати порожнечу, яку той бік
     * прийме за шматок.
     */
    fun rehydrate(body: String): String? {
        if (!PeerFile.isVaulted(body)) return body.takeIf { PeerFile.isChunk(it) }
        val head = PeerFile.head(body) ?: return null
        val whole = bytes(head.blobId)
        val slice = if (whole != null) {
            PeerFile.sliceAt(whole, head.index)
        } else {
            partBytes(head.blobId, head.index)
        } ?: return null
        return PeerFile.encodeChunk(head.blobId, head.index, head.total, slice)
    }

    /** Один недозібраний шматок із будь-якої розмови. Перебір — розмов десятки. */
    private fun partBytes(blobId: String, index: Int): ByteArray? {
        if (!PeerFile.isBlobId(blobId)) return null
        val convs = root().listFiles()?.filter { it.isDirectory } ?: return null
        return convs.asSequence()
            .map { File(File(File(it, PARTS), blobId), index.toString()) }
            .firstOrNull { it.isFile && it.length() > 0 }
            ?.let { runCatching { it.readBytes() }.getOrNull() }
    }

    /**
     * Той самий шматок, але як його треба ЗАПАМʼЯТАТИ.
     *
     * Кладе байти на диск (якщо їх там ще немає) і віддає посилання, яким лист
     * лягає в журнал. Null — байти не лягли, і тоді в журнал мусить іти
     * справжнє тіло: посилання на те, чого немає, це мовчки втрачений шматок.
     *
     * Звірка наприкінці навмисна: посилання віддається лише тоді, коли воно
     * справді читається назад тими самими байтами.
     */
    fun vault(conversationId: String, body: String): String? {
        val chunk = PeerFile.decodeChunk(body) ?: return null
        keepPart(conversationId, chunk)
        val ref = PeerFile.vaultedChunk(chunk.blobId, chunk.index, chunk.total)
        return if (rehydrate(ref) == body) ref else null
    }

    // ── приймання ────────────────────────────────────────────────────────

    /**
     * Лист приїхав. Якщо це шматок — лягає на диск; якщо опис — це привід
     * спробувати скласти те, що вже лежить.
     *
     * Шматок може прийти РАНІШЕ за опис: черга пробує рядки незалежно, і
     * порядок тут не гарантований нічим. Тому складання пробується на кожен
     * рух, а не один раз «коли прийшов останній».
     */
    fun absorb(message: PeerMessage, meId: String) {
        val conv = if (message.fromId == meId) message.toId else message.fromId
        val chunk = PeerFile.decodeChunk(message.body)
        if (chunk != null) {
            keepPart(conv, chunk)
            tryAssemble(conv, chunk.blobId, chunk.total)
            return
        }
        val attachment = PeerFile.carried(message.body) ?: return
        // Скільки шматків — питаємо в шматка, НЕ рахуємо з розміру в описі:
        // там лежить довжина ВІДКРИТОГО файла, а їде шифротекст, довший на
        // 16 байтів тега. На кратній межі це рівно один зайвий шматок, тобто
        // файл, що ніколи не збереться.
        val total = totalOf(conv, attachment.blobId) ?: return
        tryAssemble(conv, attachment.blobId, total)
    }

    private fun keepPart(conversationId: String, chunk: PeerFile.Chunk) {
        val dir = convDir(conversationId) ?: return
        // Файл уже зібраний — запізнілий повтор кадру не має відроджувати
        // каталог недозібраного: складання його вже не прибере, бо не почнеться.
        // Із полиці — так само: 8 МБ петлі не дублюються на кожного глядача.
        if (File(dir, chunk.blobId).isFile || File(File(root(), SHELF), chunk.blobId).isFile) return
        val partDir = File(File(dir, PARTS), chunk.blobId)
        // Байти вже на диску — не переписуємо: повтор кадру це нормальний хід,
        // а не подія, і другий запис лише коштував би диску.
        val part = File(partDir, chunk.index.toString())
        if (part.isFile && part.length().toInt() == chunk.bytes.size) return
        partDir.mkdirs()
        runCatching {
            part.writeBytes(chunk.bytes)
            // Скільки їх усього — знає лише кадр. Опис цього не знає, а
            // шматки можуть прийти до нього й пережити перезапуск.
            File(partDir, TOTAL).writeText(chunk.total.toString())
        }
    }

    /** Скільки шматків обіцяв кадр, або null — жоден ще не приходив. */
    private fun totalOf(conversationId: String, blobId: String): Int? {
        val dir = convDir(conversationId) ?: return null
        val mark = File(File(File(dir, PARTS), blobId), TOTAL)
        if (!mark.isFile) return null
        return runCatching { mark.readText().trim().toIntOrNull() }.getOrNull()
    }

    /**
     * Спроба скласти. Мовчить, доки бракує шматка або опису — і саме мовчить,
     * бо «ще не все приїхало» це не помилка, це половина дороги.
     */
    private fun tryAssemble(conversationId: String, blobId: String, total: Int) {
        if (total <= 0) return
        val dir = convDir(conversationId) ?: return
        if (File(dir, blobId).isFile) return
        val partDir = File(File(dir, PARTS), blobId)
        val parts = partDir.listFiles()?.mapNotNull { f ->
            f.name.toIntOrNull()?.let { it to f }
        }?.toMap() ?: return
        if (PeerFile.missing(parts.keys, total).isNotEmpty()) return
        // Опис несе відбиток усього файла — без нього складати нема з чим
        // звіряти, а покласти незвірене означало б назвати вкладенням будь-що.
        val manifest = manifestOf(blobId) ?: return
        val joined = PeerFile.join(
            parts.mapValues { (_, f) -> runCatching { f.readBytes() }.getOrNull() ?: ByteArray(0) },
            total,
        ) ?: return
        if (!PeerFile.matches(joined, manifest.sha256)) {
            // Не мовчазна порожнеча: слід лишається на диску, і екран каже
            // «зіпсоване», а не показує вічне «отримую».
            runCatching { File(dir, blobId + BROKEN).writeBytes(ByteArray(0)) }
            partDir.deleteRecursively()
            return
        }
        runCatching {
            File(dir, blobId).writeBytes(joined)
            partDir.deleteRecursively()
        }
    }

    /**
     * Що показувати про вкладення. [ABSENT] і [RECEIVING] — різні речі: перше
     * означає «нічого не приїхало», друге — «їде», і людина має право їх
     * розрізняти.
     */
    fun fate(conversationId: String, attachment: PeerFile.Attachment): Fate {
        val dir = convDir(conversationId) ?: return Fate.ABSENT
        if (File(dir, attachment.blobId).isFile) return Fate.READY
        if (File(dir, attachment.blobId + BROKEN).isFile) return Fate.BROKEN
        val parts = File(File(dir, PARTS), attachment.blobId).listFiles()?.size ?: 0
        return if (parts > 0) Fate.RECEIVING else Fate.ABSENT
    }

    /** Копія вже наявного блоба в іншу розмову (наприклад, у «Збережене»), щоб чистка джерела не стерла збережене. */
    fun copyTo(conversationId: String, blobId: String): Boolean {
        if (!PeerFile.isBlobId(blobId)) return false
        val dir = convDir(conversationId) ?: return false
        val target = File(dir, blobId)
        if (target.isFile && target.length() > 0) return true
        val src = blob(blobId) ?: return false
        dir.mkdirs()
        return runCatching {
            src.copyTo(target, overwrite = true)
            true
        }.getOrDefault(false)
    }

    /**
     * Скласти те, що вже лежить шматками. Збірки до vc31 не впізнавали опис
     * під конвертом групи, і повні `.part` лишились на телефонах назавжди.
     */
    fun settle(blobId: String): Boolean {
        if (!PeerFile.isBlobId(blobId)) return false
        if (blob(blobId) != null) return true
        root().listFiles()?.filter { it.isDirectory }?.forEach { c ->
            totalOf(c.name, blobId)?.let { tryAssemble(c.name, blobId, it) }
        }
        return blob(blobId) != null
    }

    /** Покласти свій файл на полицю під [blobId]; решта полиці — геть (петля одна). */
    fun shelve(blobId: String, file: File): Boolean {
        if (!PeerFile.isBlobId(blobId) || !file.isFile) return false
        val dir = File(root(), SHELF)
        val target = File(dir, blobId)
        dir.mkdirs()
        dir.listFiles()?.forEach { if (it.name != blobId) it.delete() }
        if (target.isFile && target.length() == file.length()) return true
        return runCatching { file.copyTo(target, overwrite = true); true }.getOrDefault(false)
    }

    /** Стерти розмову — це стерти її каталог, разом із недозібраним. */
    fun forget(conversationId: String): Boolean =
        convDir(conversationId)?.deleteRecursively() ?: false

    /** Одне вкладення: самі байти, недозібране й слід про зіпсоване. */
    fun drop(conversationId: String, blobId: String): Boolean {
        if (!PeerFile.isBlobId(blobId)) return false
        val dir = convDir(conversationId) ?: return false
        val gone = File(dir, blobId).delete()
        File(dir, blobId + BROKEN).delete()
        File(File(dir, PARTS), blobId).deleteRecursively()
        return gone
    }
}
