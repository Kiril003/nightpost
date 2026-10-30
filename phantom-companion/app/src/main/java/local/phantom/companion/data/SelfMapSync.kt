package local.phantom.companion.data

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.jsonObject
import local.phantom.companion.core.data.places.DrawnLineCodec
import local.phantom.companion.core.data.places.DrawnLineDao
import local.phantom.companion.core.data.places.DrawnLineEntity
import local.phantom.companion.core.data.places.SavedPlaceCategory
import local.phantom.companion.core.data.places.SavedPlaceDao
import local.phantom.companion.core.data.places.SavedPlaceEntity
import local.phantom.companion.core.data.spatial.GeoPoint
import local.phantom.companion.core.net.peer.SelfMap
import local.phantom.companion.core.net.peer.SelfMapDiff

/** Книга злиття мапи: останні правки й надгробки. Сховища тримають лише живе. */
interface SelfMapLedger {
    fun load(): SelfMap.Book
    fun save(book: SelfMap.Book)
}

/** Те, куди операція з іншого мого пристрою лягає на цьому. */
interface SelfMapTarget {
    suspend fun places(): List<SelfMap.Place>
    suspend fun routes(): List<SelfMap.Route>
    fun settings(): Map<String, String>
    suspend fun put(op: SelfMap.Place)
    suspend fun put(op: SelfMap.Route)
    fun put(op: SelfMap.Setting)
    fun put(op: SelfMap.Cell)
}

/**
 * Обробник операцій мапи листа собі. Кодек PH-SLF (сесія месенджера) віддає сюди
 * чужі операції через [take] і забирає власні з [collect].
 */
class SelfMapSync(
    private val ledger: SelfMapLedger,
    private val target: SelfMapTarget,
    private val origin: () -> String,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()

    /** Правки цього пристрою від минулого разу. Книга вже знає їх, коли повертається. */
    suspend fun collect(): List<SelfMap.Op> = lock.withLock {
        val book = ledger.load()
        val ops = SelfMapDiff.local(book, target.places(), target.routes(), target.settings(), SelfMap.Stamp(nowMs(), origin()))
        if (ops.isNotEmpty()) ledger.save(SelfMap.applyAll(book, ops))
        ops
    }

    /**
     * Чужі операції — у сховища; повертає власні правки, зібрані перед тим, щоб
     * їх теж відіслати. Збираються першими: інакше старша чужа затерла б свіжу мою.
     * Книга пишеться до сховищ — обрив посередині дає повтор, а не луну.
     */
    suspend fun take(ops: List<SelfMap.Op>): List<SelfMap.Op> {
        val mine = collect()
        lock.withLock {
            var book = ledger.load()
            val won = ops.filter { op -> SelfMap.wins(book, op).also { if (it) book = SelfMap.apply(book, op) } }
            if (won.isEmpty()) return mine
            ledger.save(book)
            won.forEach { op ->
                when (op) {
                    is SelfMap.Place -> target.put(op)
                    is SelfMap.Route -> target.put(op)
                    is SelfMap.Setting -> target.put(op)
                    is SelfMap.Cell -> target.put(op)
                }
            }
        }
        return mine
    }
}

/** Місця й лінії — Room; налаштування показу — [PeerWhereHub]. */
class SelfMapStores(
    private val placeDao: SavedPlaceDao,
    private val lineDao: DrawnLineDao,
    private val mapSelf: () -> Map<String, String>,
    private val applySetting: (SelfMap.Setting) -> Unit,
    private val applyCell: (SelfMap.Cell) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : SelfMapTarget {

    override suspend fun places(): List<SelfMap.Place> = placeDao.getAll().map { e ->
        SelfMap.Place(
            e.id, ZERO, false, e.label.take(SelfMap.MAX_LABEL), SavedPlaceCategory.canonical(e.category),
            e.lat, e.lon, e.address?.take(SelfMap.MAX_TEXT), e.note?.take(SelfMap.MAX_TEXT),
        )
    }

    override suspend fun routes(): List<SelfMap.Route> = lineDao.getAll().mapNotNull { e ->
        val pts = DrawnLineCodec.decode(e.vertices)?.takeIf { it.size in 2..SelfMap.MAX_POINTS } ?: return@mapNotNull null
        SelfMap.Route(e.id, ZERO, false, e.label.take(SelfMap.MAX_LABEL), pts.map { it.lat to it.lon })
    }

    override fun settings(): Map<String, String> = mapSelf()

    override suspend fun put(op: SelfMap.Place) {
        if (op.gone) return placeDao.deleteById(op.id)
        val old = placeDao.byId(op.id)
        placeDao.insert(
            SavedPlaceEntity(
                id = op.id,
                label = op.label,
                category = op.category,
                lat = op.lat,
                lon = op.lon,
                address = op.address,
                note = op.note,
                createdAt = old?.createdAt ?: op.stamp.atMs,
                lastUsedMs = old?.lastUsedMs ?: op.stamp.atMs,
            ),
        )
    }

    override suspend fun put(op: SelfMap.Route) {
        if (op.gone) {
            lineDao.deleteById(op.id)
            return
        }
        val old = lineDao.byId(op.id)
        lineDao.upsert(
            DrawnLineEntity(
                id = op.id,
                label = op.label,
                vertices = DrawnLineCodec.encode(op.points.map { GeoPoint(it.first, it.second) }),
                createdAt = old?.createdAt ?: op.stamp.atMs,
                updatedAt = nowMs(),
            ),
        )
    }

    override fun put(op: SelfMap.Setting) = applySetting(op)

    override fun put(op: SelfMap.Cell) = applyCell(op)

    private companion object {
        val ZERO = SelfMap.Stamp(0L, "-")
    }
}

/** Книга лежить зашифрованою поруч із рештою даних застосунку; бекапу вона не потрапляє. */
class SelfMapFileLedger(private val context: Context) : SelfMapLedger {

    private val file get() = File(context.noBackupFilesDir, "self_map_v1.json")

    private val encrypted
        get() = EncryptedFile.Builder(
            context,
            file,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()

    override fun load(): SelfMap.Book = runCatching {
        if (!file.isFile) return SelfMap.Book()
        val root = SelfMap.parse(encrypted.openFileInput().use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject
        val ops = listOf("places", "routes", "settings", "world").flatMap { (root[it] as? JsonArray).orEmpty() }
            .mapNotNull { (it as? JsonObject)?.let(SelfMap::decode) }
        SelfMap.applyAll(SelfMap.Book(), ops)
    }.getOrDefault(SelfMap.Book())

    // EncryptedFile не перезаписує наявний файл — пишемо поруч і підміняємо.
    override fun save(book: SelfMap.Book) {
        val tmp = File(file.parentFile, file.name + ".tmp").apply { delete() }
        runCatching {
            EncryptedFile.Builder(
                context,
                tmp,
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
            ).build().openFileOutput().use { it.write(SelfMap.snapshot(book).toString().toByteArray(Charsets.UTF_8)) }
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }
}
