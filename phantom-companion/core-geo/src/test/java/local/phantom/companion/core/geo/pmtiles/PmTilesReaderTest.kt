package local.phantom.companion.core.geo.pmtiles

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

/**
 * Наскрізний розбір PMTiles: заголовок → каталог → байти тайлу.
 *
 * [PmTilesTest] уже перевіряє окремі цеглини (крива Гільберта, varint,
 * серіалізація каталогу). Тут інше й головніше: беремо ці цеглини, СКЛАДАЄМО В
 * ПАМʼЯТІ справжній архів — 127-байтний заголовок, кореневий каталог, за потреби
 * листовий, і секцію тайлів, — записуємо його на диск і читаємо назад через
 * [FileRangeSource] рівно тим кодом, яким PHANTOM читатиме реальний пакет міста.
 * Вимога — той самий блоб байт-у-байт. Це єдиний чесний доказ, що арифметика
 * зміщень (leafDirectoryOffset, tileDataOffset, offset запису) зведена правильно;
 * помилка на один байт тут інакше спливла б аж на архіві в десятки гігабайт, де
 * її вже не локалізувати.
 *
 * Ідентифікатор тайлу дає [TileId] — він доведено оборотний у [PmTilesTest], тож
 * ми можемо будувати архів і адресувати його одним і тим самим `z/x/y`, і
 * зустрічний збіг перевіряє саме читача, а не адресацію.
 */
class PmTilesReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // Кілька сусідніх тайлів Києва на зумі 14. Конкретні x/y не важливі — важливо,
    // що вони різні, лягають на різні зміщення й повертаються без спотворення.
    private val tileA = TileCoord(14, 9583, 5476)
    private val tileB = TileCoord(14, 9584, 5476)
    private val tileC = TileCoord(14, 9583, 5477)

    private val blobA = "MVT-A: вулиця Хрещатик".encodeToByteArray()
    private val blobB = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, -1, -2, -128, 127)
    private val blobC = "C".encodeToByteArray()

    // ————————————————————————————————————————————————————————————————
    // Щасливий шлях: плаский архів, усі тайли в кореневому каталозі
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `тайли з файлу повертаються байт-у-байт`() = runTest {
        val bytes = flatArchive(
            listOf(tileA to blobA, tileB to blobB, tileC to blobC),
            minZoom = 0, maxZoom = 14,
        )
        val file = tmp.newFile("kyiv.pmtiles").apply { writeBytes(bytes) }
        val reader = PmTilesReader.open(FileRangeSource(file))

        assertEquals("заголовок мусив розібрати мінімальний зум", 0, reader.header.minZoom)
        assertEquals("заголовок мусив розібрати максимальний зум", 14, reader.header.maxZoom)
        assertEquals("тип тайлу з заголовка", PmTilesTileType.MVT, reader.header.tileType)
        assertEquals("у кореневому каталозі рівно три записи", 3, reader.rootEntryCount)

        assertArrayEquals("тайл A має вернутись тими самими байтами", blobA, reader.tile(tileA.z, tileA.x, tileA.y))
        assertArrayEquals("тайл B (з невидимими байтами) не має спотворитись", blobB, reader.tile(tileB.z, tileB.x, tileB.y))
        assertArrayEquals("тайл C має вернутись тими самими байтами", blobC, reader.tile(tileC.z, tileC.x, tileC.y))

        reader.close()
    }

    @Test
    fun `відсутній тайл — це null, а не виняток`() = runTest {
        // Розріджена піраміда — норма: над морем чи за межею пакета тайлу просто
        // немає, і це має бути тихий null, а не збій, який гасить усю карту.
        val bytes = flatArchive(listOf(tileA to blobA), minZoom = 0, maxZoom = 14)
        val file = tmp.newFile("sparse.pmtiles").apply { writeBytes(bytes) }
        val reader = PmTilesReader.open(FileRangeSource(file))

        assertNull("тайлу, якого нема в каталозі, не існує", reader.tile(14, 1, 1))
        reader.close()
    }

    @Test
    fun `зум поза покриттям архіву не читається`() = runTest {
        // coversZoom відсіює запит ще до каталогу: просити зум 19 у пакета 0..14 —
        // не помилка, а «цього рівня деталізації тут нема».
        val bytes = flatArchive(listOf(tileA to blobA), minZoom = 10, maxZoom = 14)
        val file = tmp.newFile("zoomed.pmtiles").apply { writeBytes(bytes) }
        val reader = PmTilesReader.open(FileRangeSource(file))

        assertNull("зум 19 вище maxZoom=14", reader.tile(19, 0, 0))
        assertNull("зум 5 нижче minZoom=10", reader.tile(5, 0, 0))
        reader.close()
    }

    @Test
    fun `порожній тайл повертається як нуль байтів, а не як відсутній`() = runTest {
        // Запис довжини 0 — легальний тайл (наприклад, навмисно порожній квадрат):
        // читач має віддати порожній масив, а не null, інакше «є, але порожньо» і
        // «немає взагалі» злипаються.
        val bytes = flatArchive(
            listOf(tileA to blobA, tileB to ByteArray(0)),
            minZoom = 0, maxZoom = 14,
        )
        val file = tmp.newFile("empty-tile.pmtiles").apply { writeBytes(bytes) }
        val reader = PmTilesReader.open(FileRangeSource(file))

        val empty = reader.tile(tileB.z, tileB.x, tileB.y)
        assertNotNull("порожній тайл існує", empty)
        assertEquals("порожній тайл має нуль байтів", 0, empty!!.size)
        reader.close()
    }

    @Test
    fun `стиснений тайл розпаковується лише на запит`() = runTest {
        // tile() віддає СИРІ байти (рендерер MapLibre чекає саме gzip), а
        // tileDecoded() — розпаковані. Плутанина тут дала б або подвійний gzip у
        // рендерер, або зайвий цикл розпакування на кожному кадрі.
        val payload = "справжній MVT-вміст плитки".encodeToByteArray()
        val gz = PmTilesCompression.GZIP.encode(payload)
        val bytes = flatArchive(
            listOf(tileA to gz),
            minZoom = 0, maxZoom = 14,
            tileCompression = PmTilesCompression.GZIP,
        )
        val file = tmp.newFile("gz.pmtiles").apply { writeBytes(bytes) }
        val reader = PmTilesReader.open(FileRangeSource(file))

        assertArrayEquals("tile() віддає стиснені байти як є", gz, reader.tile(tileA.z, tileA.x, tileA.y))
        assertArrayEquals("tileDecoded() розпаковує їх у вихідний вміст", payload, reader.tileDecoded(tileA.z, tileA.x, tileA.y))
        reader.close()
    }

    @Test
    fun `каталог, стиснений gzip, розпаковується читачем`() = runTest {
        // Реальні PMTiles майже завжди тримають каталоги в gzip — це десятки
        // кілобайтів замість сотень. Читач мусить розпакувати їх сам; інакше
        // дельти каталогу читаються зі стисненого сміття й дають хибні зміщення.
        val bytes = flatArchive(
            listOf(tileA to blobA, tileB to blobB),
            minZoom = 0, maxZoom = 14,
            internal = PmTilesCompression.GZIP,
        )
        val file = tmp.newFile("gzdir.pmtiles").apply { writeBytes(bytes) }
        val reader = PmTilesReader.open(FileRangeSource(file))

        assertEquals("каталог розпакувався у правильну кількість записів", 2, reader.rootEntryCount)
        assertArrayEquals("тайл із gzip-каталогу читається без спотворення", blobA, reader.tile(tileA.z, tileA.x, tileA.y))
        assertArrayEquals(blobB, reader.tile(tileB.z, tileB.x, tileB.y))
        reader.close()
    }

    // ————————————————————————————————————————————————————————————————
    // Листовий каталог і його кеш
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `тайл за листовим каталогом читається, а каталог не перечитується`() = runTest {
        // Другий рівень індексу: кореневий каталог містить лише вказівник на
        // листовий, а вже той — записи тайлів. Перший тайл тягне листовий каталог
        // з диска; другий тайл із того самого каталогу мусить узяти його з кешу —
        // інакше щільний архів робив би зайвий range-запит на кожен тайл екрана.
        val built = leafArchive(
            listOf(tileA to blobA, tileB to blobB),
            minZoom = 0, maxZoom = 14,
        )
        val counting = CountingRangeSource(ByteArrayRangeSource(built.bytes))
        val reader = PmTilesReader.open(counting)

        assertArrayEquals("тайл A з листового каталогу", blobA, reader.tile(tileA.z, tileA.x, tileA.y))
        assertEquals(
            "листовий каталог мав бути прочитаний рівно раз",
            1, counting.readsAt(built.leafDirOffset),
        )

        assertArrayEquals("тайл B з того самого листового каталогу", blobB, reader.tile(tileB.z, tileB.x, tileB.y))
        assertEquals(
            "повторне звернення до того ж каталогу не має тягнути його вдруге",
            1, counting.readsAt(built.leafDirOffset),
        )
        reader.close()
    }

    // ————————————————————————————————————————————————————————————————
    // Зловмисні / биті входи: читач падає гучно, а не блукає по сміттю
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `бита сигнатура ловиться як формат-помилка`() = runTest {
        // Читач працює з файлом, отриманим по мережі. Підмінений файл має падати
        // одразу на сигнатурі, а не інтерпретувати чужі байти як зміщення.
        val bytes = flatArchive(listOf(tileA to blobA), minZoom = 0, maxZoom = 14)
        bytes[0] = 'X'.code.toByte() // «PMTiles» → «XMTiles»
        val file = tmp.newFile("spoofed.pmtiles").apply { writeBytes(bytes) }

        val ex = runCatching { PmTilesReader.open(FileRangeSource(file)) }.exceptionOrNull()
        assertTrue("підмінену сигнатуру треба ловити як PmTilesFormatException, отримали $ex", ex is PmTilesFormatException)
    }

    @Test
    fun `непідтримуване стиснення каталогів відхиляється явно`() = runTest {
        // brotli/zstd вимагали б нативної бібліотеки; клієнт свідомо їх не має.
        // Мовчазне повернення сміттєвих байтів дало б порожню карту без причини —
        // тому це гучна UnsupportedCompressionException.
        val bytes = flatArchive(listOf(tileA to blobA), minZoom = 0, maxZoom = 14)
        // Байт 97 заголовка — internalCompression (специфікація v3); 3 = BROTLI.
        bytes[97] = 3
        val file = tmp.newFile("brotli.pmtiles").apply { writeBytes(bytes) }

        val ex = runCatching { PmTilesReader.open(FileRangeSource(file)) }.exceptionOrNull()
        assertTrue("brotli-каталог має відхилятись, отримали $ex", ex is UnsupportedCompressionException)
    }

    @Test
    fun `обрізаний каталог падає, а не читається наполовину`() = runTest {
        // Обрубана відповідь CDN чи неповний файл на картці: контракт RangeSource
        // жорсткий — або повний блок, або виняток. Частково прочитаний каталог
        // означав би тихо неправильні тайли замість гучної відмови.
        val bytes = flatArchive(listOf(tileA to blobA, tileB to blobB), minZoom = 0, maxZoom = 14)
        // Лишаємо заголовок і кілька байтів кореневого каталогу — решту зрізаємо.
        val truncated = bytes.copyOf(PmTilesHeader.LENGTH + 2)
        val file = tmp.newFile("truncated.pmtiles").apply { writeBytes(truncated) }

        val ex = runCatching { PmTilesReader.open(FileRangeSource(file)) }.exceptionOrNull()
        assertTrue("обрізаний каталог має дати ShortRangeReadException, отримали $ex", ex is ShortRangeReadException)
    }

    // ————————————————————————————————————————————————————————————————
    // FileRangeSource напряму: межі файлу
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `зміщення за межами файлу дає чесну відмову`() = runTest {
        val file = tmp.newFile("tiny.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val src = FileRangeSource(file)
        assertEquals("розмір файлу відомий", 4L, src.sizeBytes)

        val beyond = runCatching { src.read(10, 4) }.exceptionOrNull()
        assertTrue("читання цілком за EOF — ShortRangeReadException, отримали $beyond", beyond is ShortRangeReadException)

        val overrun = runCatching { src.read(2, 10) }.exceptionOrNull() as? ShortRangeReadException
        assertNotNull("часткове перекриття кінця файлу теж має падати", overrun)
        assertEquals("від зміщення 2 доступно лише 2 байти", 2, overrun!!.actual)
        src.close()
    }

    @Test
    fun `нульова довжина читається без доступу до файлу`() = runTest {
        // Порожній діапазон (наприклад, метадані довжини 0) не має чіпати диск і
        // не має падати на межі — це легальний вироджений випадок.
        val file = tmp.newFile("z.bin").apply { writeBytes(byteArrayOf(9)) }
        val src = FileRangeSource(file)
        assertEquals("нуль байтів — це порожній масив", 0, src.read(0, 0).size)
        assertEquals("нуль байтів навіть за межею файлу", 0, src.read(1000, 0).size)
        src.close()
    }

    // ————————————————————————————————————————————————————————————————
    // Складання архіву в памʼяті
    // ————————————————————————————————————————————————————————————————

    /** Плаский архів: усі тайли адресуються прямо з кореневого каталогу. */
    private fun flatArchive(
        tiles: List<Pair<TileCoord, ByteArray>>,
        minZoom: Int,
        maxZoom: Int,
        internal: PmTilesCompression = PmTilesCompression.NONE,
        tileCompression: PmTilesCompression = PmTilesCompression.NONE,
        tileType: PmTilesTileType = PmTilesTileType.MVT,
    ): ByteArray {
        val (entries, tileSection) = layoutTiles(tiles)
        val rootBytes = internal.encode(PmTilesDirectory.serialize(entries))

        val rootOff = PmTilesHeader.LENGTH.toLong()
        val tileOff = rootOff + rootBytes.size
        val header = header(
            rootOff = rootOff, rootLen = rootBytes.size.toLong(),
            leafOff = tileOff, leafLen = 0,
            tileOff = tileOff, tileLen = tileSection.size.toLong(),
            addressed = tiles.size.toLong(), entries = entries.size.toLong(),
            minZoom = minZoom, maxZoom = maxZoom,
            internal = internal, tileCompression = tileCompression, tileType = tileType,
        )
        return concat(header, rootBytes, tileSection)
    }

    private class Built(val bytes: ByteArray, val leafDirOffset: Long)

    /**
     * Дворівневий архів: кореневий каталог — один вказівник на єдиний листовий
     * каталог, що вже тримає записи тайлів.
     */
    private fun leafArchive(
        tiles: List<Pair<TileCoord, ByteArray>>,
        minZoom: Int,
        maxZoom: Int,
        internal: PmTilesCompression = PmTilesCompression.NONE,
    ): Built {
        val (leafEntries, tileSection) = layoutTiles(tiles)
        val leafBytes = internal.encode(PmTilesDirectory.serialize(leafEntries))

        // Вказівник на листовий каталог: runLength=0, tileId = найменший у листі,
        // зміщення 0 — від початку секції листових каталогів.
        val pointer = DirectoryEntry(
            tileId = leafEntries.first().tileId,
            offset = 0L,
            length = leafBytes.size,
            runLength = 0,
        )
        val rootBytes = internal.encode(PmTilesDirectory.serialize(listOf(pointer)))

        val rootOff = PmTilesHeader.LENGTH.toLong()
        val leafOff = rootOff + rootBytes.size
        val tileOff = leafOff + leafBytes.size
        val header = header(
            rootOff = rootOff, rootLen = rootBytes.size.toLong(),
            leafOff = leafOff, leafLen = leafBytes.size.toLong(),
            tileOff = tileOff, tileLen = tileSection.size.toLong(),
            addressed = tiles.size.toLong(), entries = (leafEntries.size + 1).toLong(),
            minZoom = minZoom, maxZoom = maxZoom,
            internal = internal, tileCompression = PmTilesCompression.NONE, tileType = PmTilesTileType.MVT,
        )
        return Built(concat(header, rootBytes, leafBytes, tileSection), leafOff)
    }

    /** Розкласти тайли в секцію байтів і побудувати відсортовані записи каталогу. */
    private fun layoutTiles(tiles: List<Pair<TileCoord, ByteArray>>): Pair<List<DirectoryEntry>, ByteArray> {
        val byId = tiles
            .map { TileId.fromCoord(it.first) to it.second }
            .sortedBy { it.first }
        val section = ByteArrayOutputStream()
        val entries = ArrayList<DirectoryEntry>(byId.size)
        var off = 0L
        for ((id, blob) in byId) {
            entries += DirectoryEntry(tileId = id, offset = off, length = blob.size, runLength = 1)
            section.write(blob)
            off += blob.size
        }
        return entries to section.toByteArray()
    }

    @Suppress("LongParameterList")
    private fun header(
        rootOff: Long, rootLen: Long,
        leafOff: Long, leafLen: Long,
        tileOff: Long, tileLen: Long,
        addressed: Long, entries: Long,
        minZoom: Int, maxZoom: Int,
        internal: PmTilesCompression, tileCompression: PmTilesCompression, tileType: PmTilesTileType,
    ): ByteArray = PmTilesHeader(
        rootDirectoryOffset = rootOff,
        rootDirectoryLength = rootLen,
        metadataOffset = rootOff,
        metadataLength = 0,
        leafDirectoryOffset = leafOff,
        leafDirectoryLength = leafLen,
        tileDataOffset = tileOff,
        tileDataLength = tileLen,
        addressedTilesCount = addressed,
        tileEntriesCount = entries,
        tileContentsCount = addressed,
        clustered = true,
        internalCompression = internal,
        tileCompression = tileCompression,
        tileType = tileType,
        minZoom = minZoom,
        maxZoom = maxZoom,
        minLonDeg = 30.2,
        minLatDeg = 50.2,
        maxLonDeg = 30.9,
        maxLatDeg = 50.6,
        centerZoom = (minZoom + maxZoom) / 2,
        centerLonDeg = 30.52,
        centerLatDeg = 50.45,
    ).encode()

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in parts) out.write(p)
        return out.toByteArray()
    }

    /** Джерело, що рахує кожен range-запит — щоб довести роботу кешу каталогів. */
    private class CountingRangeSource(private val delegate: RangeSource) : RangeSource {
        private val reads = mutableListOf<Long>()
        override suspend fun read(offset: Long, length: Int): ByteArray {
            reads += offset
            return delegate.read(offset, length)
        }
        override fun close() = delegate.close()
        fun readsAt(offset: Long): Int = reads.count { it == offset }
    }
}
