package local.phantom.companion.core.geo.pmtiles

import java.io.ByteArrayOutputStream

/**
 * Один запис каталогу PMTiles.
 *
 * @param tileId ідентифікатор першого тайлу запису (крива Гільберта, див. [TileId]).
 * @param offset зміщення блоба — від початку секції тайлів або секції листових каталогів.
 * @param length довжина блоба в байтах.
 * @param runLength скільки ПІДРЯД ідучих tileId покриває цей запис. `1` — звичайний
 *   тайл; `> 1` — дедуплікація (наприклад, сотні однакових порожніх морських тайлів
 *   зберігаються один раз); `0` — не тайл, а посилання на листовий каталог.
 */
data class DirectoryEntry(
    val tileId: Long,
    val offset: Long,
    val length: Int,
    val runLength: Long,
) {
    /** Запис указує на листовий каталог, а не на тайл. */
    val isLeafPointer: Boolean get() = runLength == 0L

    /** Чи покриває цей запис [id]. Для листового вказівника завжди `false`. */
    fun covers(id: Long): Boolean = !isLeafPointer && id >= tileId && id - tileId < runLength
}

/**
 * Серіалізація каталогу тайлів.
 *
 * Формат — чотири послідовні масиви varint'ів, а не масив структур. Це не
 * стилістика: однорідні колонки дельта-кодуються майже до нуля, і кореневий
 * каталог на мільйон тайлів вміщується в кілька десятків кілобайт, які клієнт
 * тягне одним запитом разом із заголовком. Масив структур такого не дає.
 *
 * Колонки:
 *  1. `tileId` — дельти до попереднього (перша — абсолютна);
 *  2. `runLength`;
 *  3. `length`;
 *  4. `offset` — `0` означає «одразу за попереднім блобом» (найчастіший випадок
 *     у кластеризованому архіві), інакше записано `offset + 1`.
 */
object PmTilesDirectory {

    /**
     * Розібрати вже РОЗПАКОВАНИЙ каталог.
     *
     * @throws PmTilesFormatException на обрізаному буфері, спадному порядку
     *   tileId або нульовому зміщенні в першому записі (він не має попередника,
     *   тож «продовження попереднього блоба» там неможливе).
     */
    fun deserialize(bytes: ByteArray): List<DirectoryEntry> {
        val r = VarintReader(bytes)
        val count = r.next()
        if (count < 0 || count > MAX_ENTRIES) {
            throw PmTilesFormatException("каталог оголошує $count записів — це не схоже на дані")
        }
        val n = count.toInt()
        if (n == 0) return emptyList()

        val ids = LongArray(n)
        var last = 0L
        for (i in 0 until n) {
            val delta = r.next()
            last += delta
            ids[i] = last
        }
        val runs = LongArray(n) { r.next() }
        val lengths = LongArray(n) {
            val v = r.next()
            if (v > Int.MAX_VALUE) {
                throw PmTilesFormatException("тайл довжиною $v Б не вміщується в памʼять одним блоком")
            }
            v
        }

        val out = ArrayList<DirectoryEntry>(n)
        var prevOffset = 0L
        var prevLength = 0L
        for (i in 0 until n) {
            val raw = r.next()
            val offset = if (raw == 0L) {
                if (i == 0) {
                    throw PmTilesFormatException("перший запис каталогу не може продовжувати попередній блоб")
                }
                prevOffset + prevLength
            } else {
                raw - 1
            }
            out += DirectoryEntry(ids[i], offset, lengths[i].toInt(), runs[i])
            prevOffset = offset
            prevLength = lengths[i]
        }
        return out
    }

    /**
     * Зібрати каталог у байти. Записи мають бути відсортовані за [DirectoryEntry.tileId]
     * за зростанням — інакше дельти стануть відʼємними, а бінарний пошук — недійсним.
     */
    fun serialize(entries: List<DirectoryEntry>): ByteArray {
        val out = ByteArrayOutputStream(entries.size * 4 + 8)
        Varint.write(out, entries.size.toLong())

        var last = 0L
        for (e in entries) {
            val delta = e.tileId - last
            require(delta >= 0) { "каталог не відсортовано: ${e.tileId} після $last" }
            Varint.write(out, delta)
            last = e.tileId
        }
        for (e in entries) Varint.write(out, e.runLength)
        for (e in entries) Varint.write(out, e.length.toLong())

        var prevOffset = -1L
        var prevLength = 0L
        for (e in entries) {
            if (prevOffset >= 0 && e.offset == prevOffset + prevLength) {
                Varint.write(out, 0L)
            } else {
                Varint.write(out, e.offset + 1)
            }
            prevOffset = e.offset
            prevLength = e.length.toLong()
        }
        return out.toByteArray()
    }

    /**
     * Знайти запис, що обслуговує [tileId].
     *
     * Бінарний пошук по відсортованих tileId; при промаху береться найближчий
     * менший запис — він або покриває ціль своїм run-length, або є вказівником
     * на листовий каталог. `null` означає «такого тайлу в архіві немає» — це
     * нормальний, а не помилковий результат: піраміда рідко буває щільною.
     */
    fun find(entries: List<DirectoryEntry>, tileId: Long): DirectoryEntry? {
        var lo = 0
        var hi = entries.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val e = entries[mid]
            when {
                tileId > e.tileId -> lo = mid + 1
                tileId < e.tileId -> hi = mid - 1
                else -> return e
            }
        }
        // hi < lo: hi вказує на найближчий менший запис.
        if (hi >= 0) {
            val e = entries[hi]
            if (e.isLeafPointer) return e
            if (e.covers(tileId)) return e
        }
        return null
    }

    /**
     * Стеля кількості записів у каталозі — захист від оголошеного мільярда
     * записів у зіпсованому файлі, який інакше зʼїв би всю памʼять на етапі
     * виділення масиву.
     */
    private const val MAX_ENTRIES: Long = 20_000_000
}
