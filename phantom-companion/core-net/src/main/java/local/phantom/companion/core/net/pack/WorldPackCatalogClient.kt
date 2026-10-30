package local.phantom.companion.core.net.pack

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import local.phantom.companion.core.net.geosrc.GeoHttp
import local.phantom.companion.core.net.geosrc.GeoResult
import local.phantom.companion.core.net.geosrc.SourceUnavailable

/**
 * Browsable index of world packs — `<base>/world-catalog.json`. Reading it
 * costs one small file, never the pack itself: "is there an update, and how
 * big" must never be answered by starting the download.
 */
sealed interface WorldCatalog {
    /** [WorldPackLink.baseUrl] is empty — nothing to browse, honestly. */
    data object NotConfigured : WorldCatalog
    data class Ok(val packs: List<WorldPackCatalogEntry>) : WorldCatalog

    /**
     * Мережа, зіпсована відповідь — або [SourceUnavailable.Unproven]: каталог
     * прийшов, але не довів, що він наш. Останнє повторювати марно, і картка
     * саме тому не малює там «спробувати ще раз».
     */
    data class Unavailable(val reason: SourceUnavailable) : WorldCatalog
}

/** Той самий канал довіри, інший файл: `<base>/forge-catalog.json`. */
sealed interface ForgeCatalog {
    data object NotConfigured : ForgeCatalog
    data class Ok(val packs: List<ForgePackCatalogEntry>) : ForgeCatalog
    data class Unavailable(val reason: SourceUnavailable) : ForgeCatalog
}

class WorldPackCatalogClient(
    private val baseUrl: () -> String,
    private val publicKeyHex: () -> String = { WorldPackLink.publicKeyHex() },
    engine: HttpClientEngine? = null,
) {
    private val client: HttpClient = GeoHttp.newClient(engine)

    suspend fun catalog(): WorldCatalog = withContext(Dispatchers.IO) {
        val base = baseUrl().trim().trimEnd('/').takeIf { it.isNotEmpty() }
            ?: return@withContext WorldCatalog.NotConfigured
        val key = publicKeyHex()
        // Ключ звіряється ДО запиту: каталог, який ми однаково не приймемо,
        // не варто качати чужим мобільним трафіком.
        if (key.isBlank()) {
            return@withContext WorldCatalog.Unavailable(noAnchor)
        }
        when (val fetched = GeoHttp.getText(client, "$base/world-catalog.json", "world-catalog")) {
            is GeoResult.Ok -> {
                unproven(fetched.value, key)?.let { return@withContext WorldCatalog.Unavailable(it) }
                val parsed = WorldPackWire.parseCatalog(fetched.value)
                    ?: return@withContext WorldCatalog.Unavailable(
                        SourceUnavailable.Malformed("зіпсований каталог пакетів"),
                    )
                // Мовчки пропускаємо рядки без розміру чи назви: показати їх
                // означало б або малювати «0 Б», або дати позначку без розміру,
                // на яку людина погоджується наосліп.
                WorldCatalog.Ok(parsed.filter { it.wellFormed })
            }
            is GeoResult.Unavailable -> WorldCatalog.Unavailable(fetched.reason)
        }
    }

    /**
     * Каталог кузні — той самий підпис, той самий ключ, той самий відбір
     * порожніх рядків. Окремий лише файл і схема рядка.
     */
    suspend fun forgeCatalog(): ForgeCatalog = withContext(Dispatchers.IO) {
        val base = baseUrl().trim().trimEnd('/').takeIf { it.isNotEmpty() }
            ?: return@withContext ForgeCatalog.NotConfigured
        val key = publicKeyHex()
        if (key.isBlank()) {
            return@withContext ForgeCatalog.Unavailable(noAnchor)
        }
        val url = "$base/${ForgeCatalogWire.FILE_NAME}"
        when (val fetched = GeoHttp.getText(client, url, "forge-catalog")) {
            is GeoResult.Ok -> {
                unproven(fetched.value, key)?.let { return@withContext ForgeCatalog.Unavailable(it) }
                val parsed = ForgeCatalogWire.parseCatalog(fetched.value)
                    ?: return@withContext ForgeCatalog.Unavailable(
                        SourceUnavailable.Malformed("зіпсований каталог кузні"),
                    )
                ForgeCatalog.Ok(parsed.filter { it.wellFormed })
            }
            is GeoResult.Unavailable -> ForgeCatalog.Unavailable(fetched.reason)
        }
    }

    private fun unproven(text: String, key: String): SourceUnavailable? =
        when (WorldPackTrust.fault(text, key)) {
            null -> null
            WorldPackTrust.Fault.NO_ANCHOR -> noAnchor
            WorldPackTrust.Fault.MALFORMED ->
                SourceUnavailable.Malformed("зіпсований каталог пакетів")
            WorldPackTrust.Fault.NOT_SIGNED -> SourceUnavailable.Unproven(
                "Каталог пакетів не підписано — показати його означало б ручатися за чужий список.",
            )
            WorldPackTrust.Fault.BAD_SIGNATURE -> SourceUnavailable.Unproven(
                "Підпис каталогу не сходиться з ключем цієї збірки — списку не буде.",
            )
        }

    private val noAnchor = SourceUnavailable.Unproven(
        "Ключа звірки пакетів у цій збірці немає — жоден пакет не поїде.",
    )
}
