package local.phantom.companion.core.net.pack

import kotlinx.serialization.Serializable
import local.phantom.companion.core.net.geosrc.GeoHttp

/** Two kinds today; a third slots in without touching the wire below. */
@Serializable
enum class WorldPackKind { BASEMAP, ROAD_MESH }

/**
 * One row of `<base>/world-catalog.json` — a browsable index read BEFORE
 * any download, so size and freshness are visible ahead of the tap. Not the
 * authoritative source for a transfer: [WorldPackGate.inspect] re-fetches
 * `<base>/<id>/manifest.json` for that — the same index-vs-truth split
 * [PackFetcher] already uses for country packs.
 */
@Serializable
data class WorldPackCatalogEntry(
    val id: String,
    val kind: WorldPackKind,
    val country: String,
    val labelUa: String,
    val bytes: Long,
    val bakedAt: String = "",
    val minAppBuild: Int = 0,
) {
    val wellFormed: Boolean get() = id.isNotBlank() && labelUa.isNotBlank() && bytes > 0L
}

@Serializable
private data class WorldCatalogDoc(val packs: List<WorldPackCatalogEntry> = emptyList())

/**
 * `<base>/<id>/manifest.json` — the file [WorldPackGate.inspect] actually
 * trusts. [bytes] and [sha256] are both required, never defaulted: a pack
 * without a declared size is a pack whose download bar would have to lie
 * about what it's counting against, and a pack without a hash is a pack
 * nothing can prove arrived intact.
 *
 * Поля `sig` тут немає навмисно: підпис звіряється над СИРИМ текстом
 * ([WorldPackTrust]), бо канонізація має бачити документ таким, яким його
 * підписав сервер, а не таким, яким його переклала ця схема.
 */
@Serializable
data class WorldPackManifestDoc(
    val id: String,
    val version: Int = 1,
    val bytes: Long = 0L,
    val sha256: String = "",
    val minAppBuild: Int = 0,
) {
    val wellFormed: Boolean
        get() = id.isNotBlank() && bytes > 0L &&
            sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' }
}

object WorldPackWire {
    fun parseCatalog(text: String): List<WorldPackCatalogEntry>? =
        runCatching { GeoHttp.json.decodeFromString(WorldCatalogDoc.serializer(), text).packs }
            .getOrNull()

    fun parseManifest(text: String): WorldPackManifestDoc? =
        runCatching { GeoHttp.json.decodeFromString(WorldPackManifestDoc.serializer(), text) }
            .getOrNull()
}
