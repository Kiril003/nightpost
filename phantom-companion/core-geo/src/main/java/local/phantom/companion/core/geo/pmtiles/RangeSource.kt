package local.phantom.companion.core.geo.pmtiles

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Джерело довільного діапазону байтів — уся абстракція, якої потребує PMTiles.
 *
 * Саме тут зникає тайловий сервер як клас: читачу байдуже, чи діапазон приїхав
 * з локального файлу, чи з HTTP `Range`. Один і той самий архів на SD-картці і
 * на статичному хостингу обслуговується тим самим кодом, а офлайн — це не режим
 * і не окремий кеш, а просто інша реалізація цього інтерфейсу.
 */
interface RangeSource {

    /**
     * Прочитати рівно [length] байтів, починаючи зі зміщення [offset].
     *
     * Контракт жорсткий: або повний блок, або виняток. Короткий блок означав би,
     * що каталог розібрано частково, а це — тихо неправильні тайли замість
     * гучної помилки.
     *
     * @throws ShortRangeReadException якщо доступно менше байтів, ніж запитано.
     * @throws IOException на будь-якій іншій відмові транспорту.
     */
    suspend fun read(offset: Long, length: Int): ByteArray

    /** Звільнити ресурси (дескриптор файлу). Повторний виклик безпечний. */
    fun close() {}
}

/** Джерело віддало менше байтів, ніж запитано — обрізаний файл або обрубана відповідь. */
class ShortRangeReadException(
    val offset: Long,
    val requested: Int,
    val actual: Int,
) : IOException("діапазон $offset+$requested Б недоступний: віддано лише $actual Б (файл обрізано?)")

/** Сервер відповів 416 — запитаний діапазон поза межами ресурсу. */
class RangeNotSatisfiableException(message: String) : IOException(message)

/**
 * Локальний файл через [RandomAccessFile].
 *
 * `seek + readFully` замість потоку: піраміда читається стрибками (заголовок →
 * каталог у середині → тайл у кінці), і будь-яке послідовне читання тут означає
 * прогін гігабайтів заради кілобайта.
 *
 * Дескриптор відкривається лениво і живе до [close]; доступ серіалізовано, бо
 * позиція в [RandomAccessFile] — спільний змінний стан, а тайли тягнуться
 * паралельно з кількох корутин.
 */
class FileRangeSource(private val file: File) : RangeSource {

    private val lock = Any()
    private var raf: RandomAccessFile? = null
    private var closed = false

    /** Розмір файлу в байтах — потрібен, щоб відрізнити «немає тайлу» від «файл обрізано». */
    val sizeBytes: Long get() = file.length()

    override suspend fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0) { "відʼємне зміщення $offset" }
        require(length >= 0) { "відʼємна довжина $length" }
        if (length == 0) return ByteArray(0)

        return withContext(Dispatchers.IO) {
            synchronized(lock) {
                check(!closed) { "джерело вже закрито: ${file.name}" }
                val f = raf ?: RandomAccessFile(file, "r").also { raf = it }
                val available = f.length() - offset
                if (available < length) {
                    throw ShortRangeReadException(
                        offset,
                        length,
                        available.coerceAtLeast(0).toInt(),
                    )
                }
                val buf = ByteArray(length)
                f.seek(offset)
                f.readFully(buf)
                buf
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            raf?.runCatching { close() }
            raf = null
        }
    }
}

/**
 * Віддалений архів через HTTP-заголовок `Range: bytes=…`.
 *
 * Робочий випадок — 206 Partial Content. Три інші розібрані явно, бо кожен
 * трапляється в полі:
 *  - **200 OK** — проксі або CDN проігнорували `Range` і віддали файл цілком.
 *    Байти все одно правильні, тож ми вирізаємо потрібний шматок самі, але це
 *    означає, що весь архів проліз через мережу; мовчати про це не можна —
 *    [ignoredRangeCount] лічить такі відповіді для діагностики.
 *  - **416** — діапазон за межами файлу: або архів на сервері коротший, ніж
 *    каже заголовок, або нам підсунули інший файл.
 *  - решта кодів — звичайна відмова транспорту.
 *
 * [client] передається ззовні свідомо: пул зʼєднань, таймаути й проксі — не
 * справа читача карти.
 */
class HttpRangeSource(
    private val url: String,
    private val client: HttpClient,
) : RangeSource {

    /** Скільки разів сервер зігнорував `Range` і віддав повне тіло. */
    @Volatile
    var ignoredRangeCount: Int = 0
        private set

    override suspend fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0) { "відʼємне зміщення $offset" }
        require(length >= 0) { "відʼємна довжина $length" }
        if (length == 0) return ByteArray(0)

        val last = offset + length - 1
        val response = client.get(url) {
            header(HttpHeaders.Range, "bytes=$offset-$last")
        }

        return when (response.status) {
            HttpStatusCode.PartialContent -> {
                val body = response.readBytes()
                if (body.size < length) {
                    throw ShortRangeReadException(offset, length, body.size)
                }
                // Сервер має право віддати ширший діапазон, ніж просили.
                if (body.size == length) body else body.copyOfRange(0, length)
            }

            HttpStatusCode.OK -> {
                ignoredRangeCount++
                val body = response.readBytes()
                if (body.size < offset + length) {
                    throw ShortRangeReadException(offset, length, (body.size - offset).coerceAtLeast(0).toInt())
                }
                body.copyOfRange(offset.toInt(), (offset + length).toInt())
            }

            HttpStatusCode.RequestedRangeNotSatisfiable -> throw RangeNotSatisfiableException(
                "сервер відхилив діапазон bytes=$offset-$last (416) для $url — архів коротший, ніж каже заголовок",
            )

            else -> throw IOException("range-запит до $url повернув ${response.status}")
        }
    }
}

/**
 * Джерело над масивом у памʼяті.
 *
 * Потрібне не лише тестам: невеликий архів кварталу (кілька мегабайтів) може
 * бути вкладений у ресурси застосунку і читатись без файлової системи взагалі.
 */
class ByteArrayRangeSource(private val bytes: ByteArray) : RangeSource {
    override suspend fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0) { "відʼємне зміщення $offset" }
        require(length >= 0) { "відʼємна довжина $length" }
        if (length == 0) return ByteArray(0)
        val available = bytes.size - offset
        if (available < length) {
            throw ShortRangeReadException(offset, length, available.coerceAtLeast(0).toInt())
        }
        return bytes.copyOfRange(offset.toInt(), (offset + length).toInt())
    }
}
