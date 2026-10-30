package local.phantom.companion.core.geo.pmtiles

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Читач PMTiles v3: заголовок → кореневий каталог → (за потреби) листовий → байти тайлу.
 *
 * Ціна одного тайлу — один range-запит у типовому випадку (кореневий каталог
 * лишається в памʼяті після відкриття) і два, коли ключ живе в листовому
 * каталозі. Для порівняння, тайловий сервер потребує самого сервера.
 *
 * Клас безпечний для конкурентного використання: кеш каталогів захищено
 * мʼютексом корутин, а не блокуванням потоку — читання тайлів іде паралельно
 * з UI, і блокувати головний потік на мережевому запиті не можна.
 */
class PmTilesReader private constructor(
    private val source: RangeSource,
    /** Розібраний заголовок архіву — покриття, межі, типи, стиснення. */
    val header: PmTilesHeader,
    private val rootDirectory: List<DirectoryEntry>,
    directoryCacheBytes: Int,
) {

    private val leafCache = DirectoryCache(directoryCacheBytes)
    private val cacheLock = Mutex()

    /**
     * Байти тайлу РІВНО в тому вигляді, в якому вони лежать в архіві.
     *
     * Тайли не розпаковуються: MVT майже завжди зберігається gzip'ом, і
     * рендерер (MapLibre) чекає саме стиснений буфер — зайвий цикл
     * розпакувати/запакувати коштував би відчутного часу на кожному кадрі.
     * Коли байти потрібні «як дані», є [tileDecoded].
     *
     * @return `null`, якщо такого тайлу в архіві немає — нормальний результат
     *   для розрідженої піраміди, а не помилка.
     */
    suspend fun tile(z: Int, x: Int, y: Int): ByteArray? {
        if (!header.coversZoom(z)) return null
        val id = TileId.fromZxy(z, x, y)
        return tileById(id)
    }

    /** Те саме, що [tile], але з застосованим [PmTilesHeader.tileCompression]. */
    suspend fun tileDecoded(z: Int, x: Int, y: Int): ByteArray? =
        tile(z, x, y)?.let { header.tileCompression.decode(it) }

    /**
     * Метадані архіву — сирий JSON (`vector_layers`, атрибуція, назва).
     *
     * Не розбираємо на типи тут: склад полів визначає той, хто пік архів, і
     * жорстка схема зробила б читач несумісним з чужими файлами без потреби.
     */
    suspend fun metadataJson(): String {
        if (header.metadataLength == 0L) return ""
        if (header.metadataLength > MAX_METADATA_BYTES) {
            throw PmTilesFormatException("метадані ${header.metadataLength} Б — це не метадані")
        }
        val raw = source.read(header.metadataOffset, header.metadataLength.toInt())
        return header.internalCompression.decode(raw).decodeToString()
    }

    /** Кількість записів кореневого каталогу — діагностика, а не API карти. */
    val rootEntryCount: Int get() = rootDirectory.size

    /** Закрити джерело. Після цього читач непридатний. */
    fun close() = source.close()

    private suspend fun tileById(id: Long): ByteArray? {
        var entries = rootDirectory
        // Специфікація допускає вкладені листові каталоги. На практиці глибина
        // не перевищує двох, але цикл із жорсткою стелею дешевший за рекурсію
        // і не зациклюється на файлі, де листи посилаються самі на себе.
        var depth = 0
        while (depth < MAX_DIRECTORY_DEPTH) {
            val entry = PmTilesDirectory.find(entries, id) ?: return null
            if (!entry.isLeafPointer) {
                if (entry.length == 0) return ByteArray(0)
                return source.read(header.tileDataOffset + entry.offset, entry.length)
            }
            entries = leafDirectory(header.leafDirectoryOffset + entry.offset, entry.length)
            depth++
        }
        throw PmTilesFormatException(
            "каталоги вкладені глибше за $MAX_DIRECTORY_DEPTH рівнів — архів зациклений",
        )
    }

    private suspend fun leafDirectory(offset: Long, length: Int): List<DirectoryEntry> {
        cacheLock.withLock { leafCache.get(offset) }?.let { return it }
        val raw = source.read(offset, length)
        val entries = PmTilesDirectory.deserialize(header.internalCompression.decode(raw))
        cacheLock.withLock { leafCache.put(offset, entries, length) }
        return entries
    }

    companion object {
        /** Стеля глибини вкладеності каталогів. */
        const val MAX_DIRECTORY_DEPTH: Int = 4

        /** Типовий бюджет кешу листових каталогів. */
        const val DEFAULT_DIRECTORY_CACHE_BYTES: Int = 4 * 1024 * 1024

        private const val MAX_METADATA_BYTES: Long = 32L * 1024 * 1024
        private const val MAX_ROOT_DIRECTORY_BYTES: Long = 64L * 1024 * 1024

        /**
         * Відкрити архів: читає заголовок і кореневий каталог одразу.
         *
         * Кореневий каталог тягнеться при відкритті навмисно — він малий
         * (десятки кілобайт навіть для континенту) і після цього більшість
         * тайлів коштує рівно один запит.
         *
         * @throws PmTilesFormatException якщо файл не PMTiles v3 або обрізаний.
         * @throws UnsupportedCompressionException якщо каталоги стиснено brotli/zstd.
         */
        suspend fun open(
            source: RangeSource,
            directoryCacheBytes: Int = DEFAULT_DIRECTORY_CACHE_BYTES,
        ): PmTilesReader {
            val headerBytes = source.read(0, PmTilesHeader.LENGTH)
            val header = PmTilesHeader.parse(headerBytes)

            if (!header.internalCompression.supported) {
                throw UnsupportedCompressionException(
                    "каталоги архіву стиснено ${header.internalCompression.name} — " +
                        "клієнт підтримує лише none та gzip",
                )
            }
            if (header.rootDirectoryLength > MAX_ROOT_DIRECTORY_BYTES) {
                throw PmTilesFormatException(
                    "кореневий каталог ${header.rootDirectoryLength} Б — заголовок зіпсовано",
                )
            }
            val rootRaw = source.read(header.rootDirectoryOffset, header.rootDirectoryLength.toInt())
            val root = PmTilesDirectory.deserialize(header.internalCompression.decode(rootRaw))
            return PmTilesReader(source, header, root, directoryCacheBytes)
        }
    }
}

/**
 * LRU-кеш листових каталогів з бюджетом у байтах.
 *
 * Обмеження саме по байтах, а не по кількості: каталоги різняться на порядки, і
 * «сто останніх» на щільному архіві означає десятки мегабайтів у памʼяті
 * телефона, який і так тримає рендерер.
 */
internal class DirectoryCache(private val maxBytes: Int) {

    private class Node(val entries: List<DirectoryEntry>, val bytes: Int)

    private val map = LinkedHashMap<Long, Node>(16, 0.75f, /* accessOrder = */ true)
    private var currentBytes = 0

    fun get(offset: Long): List<DirectoryEntry>? = map[offset]?.entries

    fun put(offset: Long, entries: List<DirectoryEntry>, bytes: Int) {
        if (bytes > maxBytes) return // Один каталог більший за весь бюджет — не витісняти все заради нього.
        map.remove(offset)?.let { currentBytes -= it.bytes }
        map[offset] = Node(entries, bytes)
        currentBytes += bytes
        val it = map.entries.iterator()
        while (currentBytes > maxBytes && it.hasNext()) {
            val victim = it.next()
            currentBytes -= victim.value.bytes
            it.remove()
        }
    }

    /** Скільки байтів каталогів зараз утримується. */
    val sizeBytes: Int get() = currentBytes

    /** Скільки каталогів зараз у кеші. */
    val count: Int get() = map.size
}
