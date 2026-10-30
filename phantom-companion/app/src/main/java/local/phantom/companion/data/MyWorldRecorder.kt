package local.phantom.companion.data

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.data.peers.MyWorldSummary
import local.phantom.companion.core.net.peer.SelfMap

/** Де лежить «мій світ». Лише цей телефон: файл зашифровано й виключено з бекапу. */
interface MyWorldFile {
    fun load(): MyWorld.Book
    fun save(book: MyWorld.Book)
    fun erase()
}

/**
 * Записувач «мого світу» на вимірах, які застосунок уже отримує.
 *
 * Кожен вимір — лише обчислення клітинки; на диск — не частіше [SAVE_EVERY_MS],
 * підсумок для екрана — коли відкрився новий квартал або минуло стільки ж.
 */
class MyWorldRecorder(
    private val file: MyWorldFile,
    private val scope: CoroutineScope,
    private val cityOf: (Double, Double) -> String? = { _, _ -> null },
    private val labelOf: (Double, Double) -> String? = { _, _ -> null },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private var book = MyWorld.Book()
    private var savedAtMs = 0L
    private var summedAtMs = 0L
    private var dirty = false
    private val asking = HashSet<String>()
    private val lock = Any()

    private val _summary = MutableStateFlow(MyWorldSummary())
    val summary: StateFlow<MyWorldSummary> = _summary.asStateFlow()

    fun attach() {
        scope.launch(Dispatchers.IO) {
            val loaded = runCatching { file.load() }.getOrDefault(MyWorld.Book())
            synchronized(lock) {
                book = loaded.copy(cells = loaded.cells + book.cells, cities = loaded.cities + book.cities)
            }
            publish()
        }
    }

    fun onFix(lat: Double, lon: Double) {
        val at = nowMs()
        val before: Int
        val after: Int
        synchronized(lock) {
            before = book.cells.size
            book = MyWorld.onFix(book, lat, lon, at)
            after = book.cells.size
            dirty = true
        }
        val district = MyWorld.districtOf(lat, lon)
        val unknown = synchronized(lock) { district !in book.cities && asking.add(district) }
        if (unknown) {
            scope.launch(Dispatchers.IO) {
                val name = runCatching { cityOf(lat, lon) }.getOrNull()
                // Без назви район лишається «спитаним» до перезапуску: інакше кожен вимір питав би пакет знову.
                if (name != null) synchronized(lock) { book = book.copy(cities = book.cities + (district to name)) }
                if (name != null) publish()
            }
        }
        if (after != before || at - summedAtMs >= SAVE_EVERY_MS) publish()
        if (at - savedAtMs >= SAVE_EVERY_MS) save()
    }

    fun absorb(cell: SelfMap.Cell) {
        synchronized(lock) {
            book = MyWorld.absorb(book, cell)
            dirty = true
        }
        publish()
        save()
    }

    fun cells(): List<SelfMap.Cell> = synchronized(lock) { book.cells.values.toList() }

    fun forget() {
        synchronized(lock) {
            book = MyWorld.Book()
            dirty = false
        }
        scope.launch(Dispatchers.IO) { runCatching { file.erase() } }
        _summary.value = MyWorldSummary()
    }

    fun save() {
        val snapshot = synchronized(lock) {
            if (!dirty) return
            dirty = false
            savedAtMs = nowMs()
            book
        }
        scope.launch(Dispatchers.IO) { runCatching { file.save(snapshot) } }
    }

    private fun publish() {
        val snapshot = synchronized(lock) {
            summedAtMs = nowMs()
            book
        }
        scope.launch(Dispatchers.Default) { _summary.value = MyWorld.summary(snapshot, labelOf) }
    }

    companion object {
        const val SAVE_EVERY_MS = 5 * 60_000L
    }
}

class MyWorldEncryptedFile(private val context: Context) : MyWorldFile {

    private val file get() = File(context.noBackupFilesDir, "my_world_v1.json")

    private fun sealed(target: File) = EncryptedFile.Builder(
        context,
        target,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
    ).build()

    override fun load(): MyWorld.Book {
        if (!file.isFile) return MyWorld.Book()
        val root = SelfMap.parse(sealed(file).openFileInput().use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject
        val cells = (root["cells"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(SelfMap::decode) as? SelfMap.Cell }
        val cities = (root["cities"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content }
        return MyWorld.Book(cells = cells.associateBy { it.key }, cities = cities)
    }

    // EncryptedFile не перезаписує наявний файл — пишемо поруч і підміняємо.
    override fun save(book: MyWorld.Book) {
        val json = buildJsonObject {
            put("cells", JsonArray(book.cells.values.map(SelfMap::encode)))
            put("cities", JsonObject(book.cities.mapValues { JsonPrimitive(it.value) }))
        }
        val tmp = File(file.parentFile, file.name + ".tmp").apply { delete() }
        sealed(tmp).openFileOutput().use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
        if (!tmp.renameTo(file)) tmp.delete()
    }

    override fun erase() {
        file.delete()
    }
}
