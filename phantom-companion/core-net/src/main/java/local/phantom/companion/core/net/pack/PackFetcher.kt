package local.phantom.companion.core.net.pack

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import local.phantom.companion.core.net.crypto.bytesToHex
import local.phantom.companion.core.net.geosrc.GeoHttp
import local.phantom.companion.core.net.geosrc.GeoResult
import local.phantom.companion.core.net.geosrc.SourceUnavailable

/**
 * ATLAS-3 — те, чим пак країни приїжджає на телефон: `<база>/<id>/manifest.json`
 * плюс названі манiфестом файли. Цілісність тримає не транспорт, а підпис
 * манiфеста і sha256 на кожен файл.
 *
 * База (`phantom.packsBaseUrl` → BuildConfig) ПОРОЖНЯ за замовчуванням і
 * вшитого дефолту не має ніколи: збірка, що мовчки ходить на чужу адресу по
 * геометрію, — та сама релізна ліцензійна бомба, що й Nominatim у фолбеку.
 *
 * ЗАКОН ЛІЧИЛЬНИКА: [install] вимагає `allowMetered` явно, без типового
 * значення, бо пак важить десятки мегабайт, а телефон свідомо буває на
 * платному Wi-Fi. Сам фетчер стану мережі не питає й не має права — політика
 * викликача, механіка тут; прапорець лише йде в журнал.
 *
 * Порядок кроків несе весь сенс: манiфест → ПІДПИС → різниця версій → асети у
 * вистоювання зі звіркою sha256 → і лише тоді манiфест стає видимим.
 */
class PackFetcher(
    private val baseUrl: () -> String,
    private val gate: PackGate,
    engine: HttpClientEngine? = null,
) {

    private val client: HttpClient = GeoHttp.newClient(engine)

    /** Асетів не чіпає: «є оновлення на 41 МБ» має коштувати манiфест, не 41 МБ. */
    suspend fun check(packId: String): PackDownload = withContext(Dispatchers.IO) { checked(packId) }

    private suspend fun checked(packId: String): PackDownload {
        val base = base() ?: return PackDownload.NotConfigured
        val text = when (val fetched = manifest(base, packId)) {
            is GeoResult.Ok -> fetched.value
            is GeoResult.Unavailable -> return PackDownload.Failed(PackFailure.Source(fetched.reason))
        }
        val want = when (val seen = inspect(text, packId)) {
            is PackInspection.Ready -> seen.want
            is PackInspection.Refused -> return PackDownload.Failed(seen.reason)
        }
        val installed = gate.installedVersion(want.packId)
        return if (installed != null && want.version <= installed) {
            PackDownload.Installed(want.packId, installed, fresh = false)
        } else {
            PackDownload.Available(want.packId, want.version, want.bytes)
        }
    }

    /**
     * Обидва входи головно-безпечні: sha256 над десятками мегабайт зробив би
     * ANR будь-якому `viewModelScope.launch` — дисципліна потоку тут, а не в
     * обіцянці викликачеві не помилитись. Стани приходять з IO-потоку.
     *
     * @param allowMetered рішення викликача — див. закон лічильника вище.
     */
    suspend fun install(
        packId: String,
        allowMetered: Boolean,
        onState: (PackDownload) -> Unit = {},
    ): PackDownload {
        val terminal = try {
            withContext(Dispatchers.IO) { perform(packId, allowMetered, onState) }
        } catch (e: CancellationException) {
            // Скасування — не збій: вистояне лишається на диску, наступний
            // прохід докачає. Стан віддаємо ДО того, як виняток піде далі.
            onState(PackDownload.Failed(PackFailure.Cancelled))
            throw e
        }
        onState(terminal)
        return terminal
    }

    private suspend fun perform(
        packId: String,
        allowMetered: Boolean,
        onState: (PackDownload) -> Unit,
    ): PackDownload {
        val base = base() ?: return PackDownload.NotConfigured
        onState(PackDownload.Checking)
        val text = when (val fetched = manifest(base, packId)) {
            is GeoResult.Ok -> fetched.value
            is GeoResult.Unavailable -> return PackDownload.Failed(PackFailure.Source(fetched.reason))
        }
        val want = when (val seen = inspect(text, packId)) {
            is PackInspection.Ready -> seen.want
            is PackInspection.Refused -> return PackDownload.Failed(seen.reason)
        }
        val installed = gate.installedVersion(want.packId)
        if (installed != null && want.version <= installed) {
            return PackDownload.Installed(want.packId, installed, fresh = false)
        }
        want.files.firstOrNull { unsafe(it.name) }?.let {
            return PackDownload.Failed(PackFailure.Refused("файл «${it.name}» виходить за каталог пака"))
        }
        Log.i(TAG, "${want.packId} v${want.version}: ${want.bytes} байт, allowMetered=$allowMetered")

        val dir = gate.stagingDir(want.packId)
        if (!dir.isDirectory && !dir.mkdirs()) {
            return PackDownload.Failed(PackFailure.Disk("каталог вистоювання не створюється: ${dir.path}"))
        }
        var done = 0L
        var shown = -1
        for (file in want.files) {
            val target = File(dir, file.name)
            target.parentFile?.mkdirs()
            val settledBase = done
            val tick = { fileBytes: Long ->
                done = settledBase + fileBytes
                val pct = percent(done, want.bytes)
                // Кадр на кожні 64 КіБ — це 640 перемальовок на пак; смуга
                // рухається відсотками, тож і подія має бути відсотком.
                if (pct != shown) {
                    shown = pct
                    onState(PackDownload.Downloading(file.name, pct, done, want.bytes))
                }
            }
            if (settled(target, file)) {
                tick(file.bytes)
                continue
            }
            download("$base/${want.packId}/${file.name}", target, file, tick)
                ?.let { return PackDownload.Failed(it) }
            done = settledBase + file.bytes
        }

        onState(PackDownload.Verifying)
        sweep(dir, want.files.mapTo(mutableSetOf()) { it.name })
        return when (val outcome = gate.promote(want.packId, text)) {
            is PackPromotion.Installed -> PackDownload.Installed(want.packId, outcome.version, fresh = true)
            is PackPromotion.Refused -> PackDownload.Failed(outcome.reason)
        }
    }

    // ── манiфест ────────────────────────────────────────────────────────

    private suspend fun manifest(base: String, packId: String): GeoResult<String> =
        GeoHttp.getText(client, "$base/$packId/$MANIFEST", "pack:$packId")

    /**
     * Ворота не бачать адреси, тож перевірка «манiфест за `<база>/pl/` називає
     * себе PL» лишається тут: не збіглось — це збитий хост, а не пак.
     */
    private fun inspect(text: String, packId: String): PackInspection {
        val seen = gate.inspect(text)
        if (seen is PackInspection.Ready && !seen.want.packId.equals(packId, ignoreCase = true)) {
            return PackInspection.Refused(
                PackFailure.Refused("за адресою «$packId» лежить пак «${seen.want.packId}»"),
            )
        }
        return seen
    }

    // ── файли ───────────────────────────────────────────────────────────

    /**
     * Докачка по файлах: звірений файл у вистоюванні не їде вдруге — саме це
     * переживає вбивство процесу. Хвіст `.part` докачується `Range`, і це
     * безпечно рівно тому, що хеш звіряється над ЦІЛИМ файлом.
     */
    private suspend fun download(
        url: String,
        target: File,
        want: PackWantFile,
        onFileBytes: (Long) -> Unit,
    ): PackFailure? {
        val part = File(target.parentFile, target.name + PART)
        val free = (target.parentFile ?: return PackFailure.Disk("нема каталогу для «${want.name}»")).usableSpace
        if (free in 1 until want.bytes) {
            return PackFailure.Disk("бракує ${(want.bytes - free) / 1024} КіБ для «${want.name}»")
        }
        var offset = if (part.isFile && part.length() in 1 until want.bytes) part.length() else 0L
        while (true) {
            val hex = when (val outcome = transfer(url, part, offset, want, onFileBytes)) {
                is Transfer.Failed -> return outcome.reason
                is Transfer.Done -> outcome.hex
            }
            if (hex == want.sha256) {
                if (target.isFile) target.delete()
                return if (part.renameTo(target)) null
                else PackFailure.Disk("«${want.name}» не стає на місце")
            }
            part.delete()
            // Хвіст міг зійтися по довжині й не зійтись по вмісту (файл на
            // хості перезібрали між спробами) — один чесний прохід з нуля.
            if (offset == 0L) return PackFailure.Corrupt(want.name, want.sha256, hex)
            offset = 0L
        }
    }

    private sealed interface Transfer {
        data class Done(val hex: String) : Transfer
        data class Failed(val reason: PackFailure) : Transfer
    }

    /**
     * `client.get(url)` returns only after Ktor has materialised the whole
     * body (`SavedHttpCall`) so the response stays readable outside the
     * request scope — harmless for the small manifest, fatal for a pack:
     * a 178 MB `readBytes()` blew the app heap (`OutOfMemoryError` in
     * `SavedCallKt.save`) before a single byte reached [onFileBytes].
     * `prepareGet().execute { }` is Ktor's documented streaming form —
     * the block runs while the connection is still open, so
     * `bodyAsChannel()` below is a live channel, never a buffered copy.
     */
    private suspend fun transfer(
        url: String,
        part: File,
        offset: Long,
        want: PackWantFile,
        onFileBytes: (Long) -> Unit,
    ): Transfer = try {
        client.prepareGet(url) {
            // Пак живе довше за будь-який запитний бюджет; мертвий канал
            // ловить сокетний таймаут, а не загальний на 30 с.
            timeout { requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS }
            if (offset > 0L) header(HttpHeaders.Range, "bytes=$offset-")
        }.execute { response -> receive(response, part, offset, want, onFileBytes) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Transfer.Failed(PackFailure.Source(transport(e)))
    }

    private suspend fun receive(
        response: HttpResponse,
        part: File,
        offset: Long,
        want: PackWantFile,
        onFileBytes: (Long) -> Unit,
    ): Transfer {
        unavailable(response)?.let { return Transfer.Failed(PackFailure.Source(it)) }
        // Хост міг проігнорувати Range і віддати файл цілком — тоді наш хвіст
        // уже не хвіст, і писати треба з нуля.
        val append = offset > 0L && response.status.value == 206

        val digest = MessageDigest.getInstance(SHA_256)
        var written = 0L
        if (append) {
            // Наявний хвіст входить у хеш — інакше звіряли б лише докачане.
            part.inputStream().use { input ->
                val buffer = ByteArray(CHUNK)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                    written += read
                }
            }
        }
        FileOutputStream(part, append).use { out ->
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(CHUNK)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read <= 0) break
                out.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                written += read
                onFileBytes(written)
            }
        }
        if (written < want.bytes) {
            // Обрив ≠ битий файл: `.part` лишається, наступна спроба докачає.
            return Transfer.Failed(
                PackFailure.Source(SourceUnavailable.Transport("обрив: $written з ${want.bytes} байт")),
            )
        }
        return Transfer.Done(bytesToHex(digest.digest()))
    }

    /** Файл уже лежить у вистоюванні цілим — саме це переживає перезапуск. */
    private fun settled(target: File, want: PackWantFile): Boolean =
        target.isFile && target.length() == want.bytes && hexOf(target) == want.sha256

    private fun hexOf(file: File): String {
        val digest = MessageDigest.getInstance(SHA_256)
        file.inputStream().use { input ->
            val buffer = ByteArray(CHUNK)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return bytesToHex(digest.digest())
    }

    /**
     * Усе, чого манiфест не називав, з вистоювання йде геть: інакше уламок
     * попередньої (скасованої) версії поїхав би у видимий каталог пасажиром.
     */
    private fun sweep(dir: File, keep: Set<String>) {
        dir.walkBottomUp().forEach { entry ->
            when {
                entry == dir -> Unit
                entry.isFile && entry.relativeTo(dir).invariantPath() !in keep -> entry.delete()
                entry.isDirectory && entry.list()?.isEmpty() == true -> entry.delete()
            }
        }
    }

    private fun File.invariantPath(): String = path.replace(File.separatorChar, '/')

    // ── дрібне ──────────────────────────────────────────────────────────

    private fun base(): String? = baseUrl().trim().trimEnd('/').takeIf { it.isNotEmpty() }

    private fun unsafe(name: String): Boolean =
        name.isBlank() || ".." in name || name.startsWith("/") || name.startsWith("\\") || ":" in name

    private fun percent(done: Long, total: Long): Int =
        if (total <= 0L) 0 else ((done * 100L) / total).coerceIn(0L, 100L).toInt()

    private fun transport(e: Exception) =
        SourceUnavailable.Transport("${e.javaClass.simpleName}: ${e.message ?: "без деталей"}")

    private fun unavailable(response: HttpResponse): SourceUnavailable? {
        val code = response.status.value
        return when {
            code == 429 -> SourceUnavailable.RateLimited(
                (response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.takeIf { it > 0 }
                    ?: 60L) * 1_000L,
            )
            code == 401 || code == 403 -> SourceUnavailable.InvalidApiKey("HTTP $code")
            code !in 200..299 -> SourceUnavailable.HttpError(code)
            else -> null
        }
    }

    private companion object {
        const val TAG = "PackFetcher"
        const val MANIFEST = "manifest.json"
        const val PART = ".part"
        const val SHA_256 = "SHA-256"
        const val CHUNK = 64 * 1024
    }
}
