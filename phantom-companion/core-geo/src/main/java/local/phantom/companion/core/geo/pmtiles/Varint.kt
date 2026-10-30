package local.phantom.companion.core.geo.pmtiles

import java.io.ByteArrayOutputStream

/**
 * LEB128 (varint) — єдиний примітив кодування чисел у PMTiles v3.
 *
 * Каталог тайлів складається з чотирьох паралельних масивів varint'ів, і саме
 * дельта-кодування робить індекс на мільйони тайлів придатним до range-запиту:
 * різниця сусідніх ідентифікаторів на кривій Гільберта майже завжди вміщується
 * в один байт, тож увесь кореневий каталог стискається до десятків кілобайт і
 * тягнеться одним запитом разом із заголовком.
 *
 * Реалізація свідомо працює з [Long] без знака (значення завжди невідʼємні —
 * зигзаг-кодування специфікація не використовує) і жорстко обмежує довжину
 * послідовності: биті чи ворожі дані не мають крутити цикл нескінченно.
 */
object Varint {

    /** Максимум байтів у коректному varint для 64-бітного значення. */
    const val MAX_BYTES: Int = 10

    /**
     * Закодувати невідʼємне [value] у LEB128.
     *
     * @throws IllegalArgumentException якщо [value] відʼємне — у форматі таких полів немає,
     *   і мовчазне кодування як беззнакового дало б непомітно зіпсований архів.
     */
    fun encode(value: Long): ByteArray {
        require(value >= 0) { "varint не кодує відʼємні значення: $value" }
        val out = ByteArrayOutputStream(MAX_BYTES)
        write(out, value)
        return out.toByteArray()
    }

    /** Дописати [value] у потік [out] як LEB128. */
    fun write(out: ByteArrayOutputStream, value: Long) {
        require(value >= 0) { "varint не кодує відʼємні значення: $value" }
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(b)
                return
            }
            out.write(b or 0x80)
        }
    }

    /** Скільки байтів займе [value] — потрібно, щоб порахувати зміщення до запису. */
    fun sizeOf(value: Long): Int {
        require(value >= 0) { "varint не кодує відʼємні значення: $value" }
        var v = value
        var n = 1
        while (true) {
            v = v ushr 7
            if (v == 0L) return n
            n++
        }
    }
}

/**
 * Послідовний читач varint'ів над готовим буфером.
 *
 * Каталог читається строго послідовно чотирма проходами, тож стан курсора —
 * природна форма API; кожен вихід за межі буфера підіймає [PmTilesFormatException],
 * бо для нас обрізаний або підмінений файл — очікуваний сценарій, а не «не буває».
 */
class VarintReader(private val buf: ByteArray, startPos: Int = 0) {

    /** Поточне зміщення в буфері. */
    var position: Int = startPos
        private set

    init {
        require(startPos in 0..buf.size) { "стартова позиція $startPos поза буфером ${buf.size}" }
    }

    /** Скільки байтів лишилось непрочитаними. */
    val remaining: Int get() = buf.size - position

    /**
     * Прочитати наступний varint.
     *
     * @throws PmTilesFormatException якщо буфер обірвався посеред числа або
     *   послідовність довша за 64 біти (ознака сміття, а не даних).
     */
    fun next(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (position >= buf.size) {
                throw PmTilesFormatException(
                    "varint обірвано: буфер ${buf.size} Б закінчився на зміщенні $position",
                )
            }
            if (shift > 63) {
                throw PmTilesFormatException("varint довший за 64 біти на зміщенні $position")
            }
            val b = buf[position++].toInt() and 0xFF
            result = result or ((b.toLong() and 0x7FL) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }
}

/**
 * Файл не відповідає специфікації PMTiles v3 (або обрізаний, або підмінений).
 *
 * Окремий тип, а не [IllegalArgumentException]: різниця між «карта не завантажилась»
 * і «карту підмінили» має бути видима згори без розбору тексту повідомлення.
 */
class PmTilesFormatException(message: String) : IllegalStateException(message)

/**
 * Архів використовує алгоритм стиснення, який ми свідомо не підтримуємо.
 *
 * Brotli й zstd вимагали б нативної залежності — а вартість цього рішення
 * (розмір APK, ABI-матриця, ще одна поверхня атаки) вища за виграш: власні
 * пакети ми печемо самі й завжди в gzip.
 */
class UnsupportedCompressionException(message: String) : IllegalStateException(message)
