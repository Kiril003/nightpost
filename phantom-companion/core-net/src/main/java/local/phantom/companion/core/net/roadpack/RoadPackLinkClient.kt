package local.phantom.companion.core.net.roadpack

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.onDownload
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import local.phantom.companion.core.net.pair.PinnedTrust
import java.io.File
import java.io.FileOutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * Phase 2 (map-plan) — road-mesh pack wire, phone side.
 *
 * Talks to the PC's already-shipped `/api/v1/bake/road-packs*` endpoints over
 * the existing paired link: same pinned TLS ([PinnedTrust]) as [PhantomApi][
 * local.phantom.companion.net.PhantomApi], same device JWT, same bearer
 * pattern. Lives in `:core-net` (not `:app`) because `:feature-precision`
 * cannot depend on `:app` (the dependency runs the other way) — mirrors the
 * `GeoEndpoints` "process-wide holder" shape already established here for
 * exactly this kind of cross-module PC-link config.
 *
 * Downloads stream straight to disk (`bodyAsChannel()` → `DigestOutputStream`
 * → file), the same house idiom `core-ai/ModelManager.kt` uses for its own
 * multi-hundred-MB model pulls — never resident whole in memory, and the
 * SHA-256 is computed from the same streaming pass, so a 1.5 GB pack is never
 * read twice to get both the bytes on disk and the hash to verify them.
 */
class RoadPackLinkClient(pinnedCertSha256: () -> String) {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val client = HttpClient(OkHttp) {
        engine {
            preconfigured = PinnedTrust
                .apply(okhttp3.OkHttpClient.Builder(), pinnedCertSha256)
                .build()
        }
        install(ContentNegotiation) { json(this@RoadPackLinkClient.json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    }

    @Serializable
    data class RoadPackManifest(
        val pack_id: String,
        val label: String,
        val pack_type: String = "road_mesh",
        val format_version: Int,
        val format_supported: Boolean = true,
        val baked_at: String = "",
        val bytes_total: Long = 0,
        val content_hash: String = "",
        val way_count: Long? = null,
    )

    @Serializable
    private data class ListResponse(val packs: List<RoadPackManifest> = emptyList())

    /** Manifest-only survey — what the PC actually has baked, never a
     *  hardcoded string on this side either. Empty list, not a throw, when
     *  the PC genuinely has nothing baked yet (an honest "nothing to offer",
     *  distinct from "PC unreachable", which throws). */
    suspend fun listPacks(baseUrl: String, deviceJwt: String): List<RoadPackManifest> {
        val res = client.get("${baseUrl.trimEnd('/')}/api/v1/bake/road-packs") {
            bearerAuth(deviceJwt)
        }
        if (res.status != HttpStatusCode.OK) {
            throw RoadPackLinkException("список пакетів: ${res.status.value}")
        }
        return runCatching {
            json.decodeFromString(ListResponse.serializer(), res.bodyAsText()).packs
        }.getOrElse { t ->
            throw RoadPackLinkException("зіпсована відповідь ПК: ${t.message}")
        }
    }

    sealed interface DownloadResult {
        data class Success(val file: File) : DownloadResult
        data class Failure(val message: String) : DownloadResult
    }

    /**
     * Streams [manifest]'s pack to [destFile], verifying both size and
     * SHA-256 against the manifest before returning [DownloadResult.Success]
     * — the atomic-install rule from `recovered/map-register-schema.md` §2:
     * a half-downloaded or corrupt pack is deleted here and NEVER handed to
     * `RoadPackImporter`. No HTTP Range resume on a dropped connection today
     * (stubbed — a retry restarts from byte 0); that is an honest limitation
     * stated in the wire notes, not a silent one.
     */
    suspend fun downloadPack(
        baseUrl: String,
        deviceJwt: String,
        manifest: RoadPackManifest,
        destFile: File,
        onProgress: (Float) -> Unit = {},
    ): DownloadResult {
        destFile.parentFile?.mkdirs()
        destFile.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        return try {
            client.prepareGet("${baseUrl.trimEnd('/')}/api/v1/bake/road-packs/${manifest.pack_id}/download") {
                bearerAuth(deviceJwt)
                timeout { socketTimeoutMillis = DOWNLOAD_SOCKET_TIMEOUT_MS }
                onDownload { bytesSentTotal, contentLength ->
                    val total = contentLength?.takeIf { it > 0 } ?: manifest.bytes_total
                    if (total > 0) onProgress(bytesSentTotal.toFloat() / total)
                }
            }.execute { response -> receive(response, destFile, digest, manifest) }
        } catch (t: Throwable) {
            destFile.delete()
            DownloadResult.Failure(t.message ?: "Помилка завантаження.")
        }
    }

    private suspend fun receive(
        response: HttpResponse,
        destFile: File,
        digest: MessageDigest,
        manifest: RoadPackManifest,
    ): DownloadResult {
        if (!response.status.isSuccess()) {
            return DownloadResult.Failure("ПК відповів ${response.status.value}")
        }
        withContext(Dispatchers.IO) {
            val channel = response.bodyAsChannel()
            DigestOutputStream(FileOutputStream(destFile), digest).use { out ->
                channel.copyTo(out)
                out.flush()
            }
        }
        if (manifest.bytes_total > 0 && destFile.length() != manifest.bytes_total) {
            destFile.delete()
            return DownloadResult.Failure(
                "Пакет неповний: отримано ${destFile.length()} з ${manifest.bytes_total} байт.",
            )
        }
        val gotHash = digest.digest().joinToString("") { "%02x".format(it) }
        val wantHash = manifest.content_hash.removePrefix("sha256:")
        if (wantHash.isNotBlank() && !gotHash.equals(wantHash, ignoreCase = true)) {
            destFile.delete()
            return DownloadResult.Failure("Пакет пошкоджено при передачі — хеш не збігається.")
        }
        return DownloadResult.Success(destFile)
    }

    companion object {
        /** 30 хв — на повільному Wi-Fi (symbiosis §7 estimate: ~20 МБ/с)
         *  повний українcький пакет (1.5 ГБ) іде ~2 хв; глобальний
         *  60-секундний ліміт HttpTimeout обірвав би це задовго до кінця. */
        private const val DOWNLOAD_SOCKET_TIMEOUT_MS = 30 * 60_000L
    }
}

class RoadPackLinkException(message: String) : RuntimeException(message)
