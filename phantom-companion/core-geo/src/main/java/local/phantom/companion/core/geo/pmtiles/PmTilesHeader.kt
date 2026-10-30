package local.phantom.companion.core.geo.pmtiles

/**
 * 127-байтний заголовок PMTiles v3 — усе, що треба знати, щоб дістати будь-який
 * тайл з файлу розміром у десятки гігабайт.
 *
 * Порядок і зміщення полів — жорстка частина специфікації; вони перелічені
 * константами нижче, бо саме тут помилка на один байт дає «файл бʼється» на
 * чужих архівах і не ловиться раундтрипом власного запису.
 *
 * Усі числові поля — little-endian. Географія зберігається в цілих 1e-7 градуса
 * (той самий формат, що й у OSM/Mapbox): це рівно 1.1 см на екваторі, тобто
 * точніше за будь-який побутовий приймач, і при цьому без плаваючої коми.
 */
data class PmTilesHeader(
    /** Зміщення кореневого каталогу від початку файлу, байти. */
    val rootDirectoryOffset: Long,
    /** Довжина кореневого каталогу (у стисненому вигляді), байти. */
    val rootDirectoryLength: Long,
    /** Зміщення JSON-метаданих (стиснених [internalCompression]). */
    val metadataOffset: Long,
    val metadataLength: Long,
    /** Початок секції листових каталогів; зміщення в записах відлічуються від нього. */
    val leafDirectoryOffset: Long,
    val leafDirectoryLength: Long,
    /** Початок секції тайлів; зміщення в записах відлічуються від нього. */
    val tileDataOffset: Long,
    val tileDataLength: Long,
    /** Скільки `z/x/y` адресується (з урахуванням run-length повторів). */
    val addressedTilesCount: Long,
    /** Скільки записів у каталогах. */
    val tileEntriesCount: Long,
    /** Скільки унікальних блобів тайлів (дедуплікація однакових тайлів). */
    val tileContentsCount: Long,
    /** Чи впорядковані блоби тайлів за зростанням tileId — тоді сусідні тайли беруться одним запитом. */
    val clustered: Boolean,
    /** Стиснення каталогів і метаданих. */
    val internalCompression: PmTilesCompression,
    /** Стиснення самих тайлів — застосовує вже споживач байтів, не читач. */
    val tileCompression: PmTilesCompression,
    val tileType: PmTilesTileType,
    val minZoom: Int,
    val maxZoom: Int,
    val minLonDeg: Double,
    val minLatDeg: Double,
    val maxLonDeg: Double,
    val maxLatDeg: Double,
    val centerZoom: Int,
    val centerLonDeg: Double,
    val centerLatDeg: Double,
) {

    /** Чи потрапляє зум [z] у покриття архіву. */
    fun coversZoom(z: Int): Boolean = z in minZoom..maxZoom

    /** Чи потрапляє точка в декларовані межі архіву (перевірка до читання каталогу). */
    fun coversPoint(latDeg: Double, lonDeg: Double): Boolean =
        latDeg in minLatDeg..maxLatDeg && lonDeg in minLonDeg..maxLonDeg

    /**
     * Серіалізувати назад у 127 байтів.
     *
     * Потрібно не лише тестам: PHANTOM пече власні офлайн-пакети, і робити це
     * тим самим кодом, яким їх читає, — єдиний спосіб не мати двох розбіжних
     * уявлень про формат.
     */
    fun encode(): ByteArray {
        val b = ByteArray(LENGTH)
        MAGIC.forEachIndexed { i, c -> b[i] = c.code.toByte() }
        b[OFF_VERSION] = VERSION.toByte()
        putLongLe(b, OFF_ROOT_DIR_OFFSET, rootDirectoryOffset)
        putLongLe(b, OFF_ROOT_DIR_LENGTH, rootDirectoryLength)
        putLongLe(b, OFF_METADATA_OFFSET, metadataOffset)
        putLongLe(b, OFF_METADATA_LENGTH, metadataLength)
        putLongLe(b, OFF_LEAF_DIR_OFFSET, leafDirectoryOffset)
        putLongLe(b, OFF_LEAF_DIR_LENGTH, leafDirectoryLength)
        putLongLe(b, OFF_TILE_DATA_OFFSET, tileDataOffset)
        putLongLe(b, OFF_TILE_DATA_LENGTH, tileDataLength)
        putLongLe(b, OFF_ADDRESSED_TILES, addressedTilesCount)
        putLongLe(b, OFF_TILE_ENTRIES, tileEntriesCount)
        putLongLe(b, OFF_TILE_CONTENTS, tileContentsCount)
        b[OFF_CLUSTERED] = if (clustered) 1 else 0
        b[OFF_INTERNAL_COMPRESSION] = internalCompression.code.toByte()
        b[OFF_TILE_COMPRESSION] = tileCompression.code.toByte()
        b[OFF_TILE_TYPE] = tileType.code.toByte()
        b[OFF_MIN_ZOOM] = minZoom.toByte()
        b[OFF_MAX_ZOOM] = maxZoom.toByte()
        putIntLe(b, OFF_MIN_LON, degToE7(minLonDeg))
        putIntLe(b, OFF_MIN_LAT, degToE7(minLatDeg))
        putIntLe(b, OFF_MAX_LON, degToE7(maxLonDeg))
        putIntLe(b, OFF_MAX_LAT, degToE7(maxLatDeg))
        b[OFF_CENTER_ZOOM] = centerZoom.toByte()
        putIntLe(b, OFF_CENTER_LON, degToE7(centerLonDeg))
        putIntLe(b, OFF_CENTER_LAT, degToE7(centerLatDeg))
        return b
    }

    companion object {
        /** Довжина заголовка, байти. Фіксована — не залежить від версії v3. */
        const val LENGTH: Int = 127

        /** Сигнатура файлу. */
        const val MAGIC: String = "PMTiles"

        /** Єдина підтримувана версія специфікації. */
        const val VERSION: Int = 3

        // — Зміщення полів у заголовку (специфікація v3) ———————————————————
        private const val OFF_VERSION = 7
        private const val OFF_ROOT_DIR_OFFSET = 8
        private const val OFF_ROOT_DIR_LENGTH = 16
        private const val OFF_METADATA_OFFSET = 24
        private const val OFF_METADATA_LENGTH = 32
        private const val OFF_LEAF_DIR_OFFSET = 40
        private const val OFF_LEAF_DIR_LENGTH = 48
        private const val OFF_TILE_DATA_OFFSET = 56
        private const val OFF_TILE_DATA_LENGTH = 64
        private const val OFF_ADDRESSED_TILES = 72
        private const val OFF_TILE_ENTRIES = 80
        private const val OFF_TILE_CONTENTS = 88
        private const val OFF_CLUSTERED = 96
        private const val OFF_INTERNAL_COMPRESSION = 97
        private const val OFF_TILE_COMPRESSION = 98
        private const val OFF_TILE_TYPE = 99
        private const val OFF_MIN_ZOOM = 100
        private const val OFF_MAX_ZOOM = 101
        private const val OFF_MIN_LON = 102
        private const val OFF_MIN_LAT = 106
        private const val OFF_MAX_LON = 110
        private const val OFF_MAX_LAT = 114
        private const val OFF_CENTER_ZOOM = 118
        private const val OFF_CENTER_LON = 119
        private const val OFF_CENTER_LAT = 123

        /**
         * Розібрати заголовок з перших [LENGTH] байтів файлу.
         *
         * @throws PmTilesFormatException на битій сигнатурі, чужій версії,
         *   закороткому буфері або зумах поза 0..30. Перевірки навмисно суворі:
         *   читач працює з файлом, отриманим по мережі, і має падати на підміні
         *   одразу, а не блукати по зміщеннях, узятих зі сміття.
         */
        fun parse(bytes: ByteArray): PmTilesHeader {
            if (bytes.size < LENGTH) {
                throw PmTilesFormatException(
                    "заголовок PMTiles обрізано: ${bytes.size} Б замість $LENGTH — файл неповний",
                )
            }
            for (i in MAGIC.indices) {
                if (bytes[i] != MAGIC[i].code.toByte()) {
                    throw PmTilesFormatException(
                        "не PMTiles: сигнатура «${asciiPreview(bytes, MAGIC.length)}» замість «$MAGIC»",
                    )
                }
            }
            val version = bytes[OFF_VERSION].toInt() and 0xFF
            if (version != VERSION) {
                throw PmTilesFormatException("версія PMTiles $version не підтримується (потрібна $VERSION)")
            }

            val minZoom = bytes[OFF_MIN_ZOOM].toInt() and 0xFF
            val maxZoom = bytes[OFF_MAX_ZOOM].toInt() and 0xFF
            if (minZoom > maxZoom || maxZoom > 30) {
                throw PmTilesFormatException("зуми заголовка неможливі: min=$minZoom max=$maxZoom")
            }

            return PmTilesHeader(
                rootDirectoryOffset = getLongLe(bytes, OFF_ROOT_DIR_OFFSET),
                rootDirectoryLength = getLongLe(bytes, OFF_ROOT_DIR_LENGTH),
                metadataOffset = getLongLe(bytes, OFF_METADATA_OFFSET),
                metadataLength = getLongLe(bytes, OFF_METADATA_LENGTH),
                leafDirectoryOffset = getLongLe(bytes, OFF_LEAF_DIR_OFFSET),
                leafDirectoryLength = getLongLe(bytes, OFF_LEAF_DIR_LENGTH),
                tileDataOffset = getLongLe(bytes, OFF_TILE_DATA_OFFSET),
                tileDataLength = getLongLe(bytes, OFF_TILE_DATA_LENGTH),
                addressedTilesCount = getLongLe(bytes, OFF_ADDRESSED_TILES),
                tileEntriesCount = getLongLe(bytes, OFF_TILE_ENTRIES),
                tileContentsCount = getLongLe(bytes, OFF_TILE_CONTENTS),
                clustered = bytes[OFF_CLUSTERED].toInt() != 0,
                internalCompression = PmTilesCompression.fromCode(bytes[OFF_INTERNAL_COMPRESSION].toInt() and 0xFF),
                tileCompression = PmTilesCompression.fromCode(bytes[OFF_TILE_COMPRESSION].toInt() and 0xFF),
                tileType = PmTilesTileType.fromCode(bytes[OFF_TILE_TYPE].toInt() and 0xFF),
                minZoom = minZoom,
                maxZoom = maxZoom,
                minLonDeg = e7ToDeg(getIntLe(bytes, OFF_MIN_LON)),
                minLatDeg = e7ToDeg(getIntLe(bytes, OFF_MIN_LAT)),
                maxLonDeg = e7ToDeg(getIntLe(bytes, OFF_MAX_LON)),
                maxLatDeg = e7ToDeg(getIntLe(bytes, OFF_MAX_LAT)),
                centerZoom = bytes[OFF_CENTER_ZOOM].toInt() and 0xFF,
                centerLonDeg = e7ToDeg(getIntLe(bytes, OFF_CENTER_LON)),
                centerLatDeg = e7ToDeg(getIntLe(bytes, OFF_CENTER_LAT)),
            )
        }

        private fun asciiPreview(bytes: ByteArray, n: Int): String =
            (0 until minOf(n, bytes.size)).joinToString("") {
                val c = bytes[it].toInt() and 0xFF
                if (c in 0x20..0x7E) c.toChar().toString() else "."
            }

        private fun getLongLe(b: ByteArray, at: Int): Long {
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
            return v
        }

        private fun getIntLe(b: ByteArray, at: Int): Int {
            var v = 0
            for (i in 3 downTo 0) v = (v shl 8) or (b[at + i].toInt() and 0xFF)
            return v
        }

        private fun putLongLe(b: ByteArray, at: Int, value: Long) {
            for (i in 0..7) b[at + i] = ((value ushr (8 * i)) and 0xFF).toByte()
        }

        private fun putIntLe(b: ByteArray, at: Int, value: Int) {
            for (i in 0..3) b[at + i] = ((value ushr (8 * i)) and 0xFF).toByte()
        }

        private fun e7ToDeg(v: Int): Double = v / 1e7

        private fun degToE7(v: Double): Int = Math.round(v * 1e7).toInt()
    }
}
