package local.phantom.companion.core.data.country

import kotlinx.serialization.json.Json
import local.phantom.companion.core.license.SignedDocument

/** Чому пак не встановлено. Мовчазної відмови не буває — причина завжди є. */
enum class PackReject {
    MALFORMED,
    /** Документ без поля `sig` або з підписом, який не сходиться. */
    BAD_SIGNATURE,
    /** Ключа звірки нема — довіряти нічому, як і в ліцензійних воротах. */
    NO_TRUST_ANCHOR,
    APP_TOO_OLD,
    /** Старша версія поверх новішої: оновлення не їде назад. */
    OLDER_VERSION,
    /** Id вбудованого пака: підлогу збірки не підміняють дротом. */
    SHADOWS_BUNDLED,
    LICENSE_VIOLATION,
    /** Файли пака не доїхали або каталог не підмінився — манiфест цілий. */
    INCOMPLETE,
}

sealed interface PackInstall {
    data class Accepted(val pack: InstalledPack) : PackInstall
    data class Rejected(val reason: PackReject, val detail: String) : PackInstall
}

/**
 * Єдиний вхід, яким пак стає паком.
 *
 * Криптографії тут не винаходять: підпис звіряється тим самим
 * [SignedDocument], яким живуть ліцензійний сертифікат і політика — Ed25519
 * над канонічними байтами документа (ключі відсортовані, поле `sig`
 * викинуте), публічний ключ шістнадцятковим рядком зі збірки. Приватний ключ
 * на пристрої не буває ніколи: клієнт уміє лише перевіряти.
 *
 * Порожній ключ звірки означає «не приймається жоден пак», а не «приймаються
 * всі»: та сама постава, що й `LICENSE_PUBLIC_KEY=""` → кожен сертифікат
 * BadSignature. Збірка без якоря довіри не має тихо ковтати чужу геометрію.
 */
object PackInstaller {

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    /**
     * @param appBuild номер поточної збірки — проти `minAppBuild` пака.
     * @param installedVersion версія вже встановленого пака з тим самим id.
     * @param audit ліцензійний аудит джерел. Параметр, а не типове значення:
     *   `core-data` не бачить `LicenseFirewall` (ребро йде в інший бік), і
     *   мовчазний «порушень нема» був би саме тією дірою, заради якої аудит і
     *   писався. Викликач із `core-geo` подає справжній.
     */
    fun read(
        text: String,
        publicKeyHex: String,
        appBuild: Int,
        assetSource: PackAssetSource,
        audit: (List<PackSource>) -> List<String>,
        installedVersion: Int? = null,
        bundledIds: Set<String> = setOf(UkrainePack.id),
    ): PackInstall {
        if (publicKeyHex.isBlank()) {
            return reject(PackReject.NO_TRUST_ANCHOR, "ключ звірки пакiв не задано у збірці")
        }
        val document = SignedDocument.parse(text)
            ?: return reject(PackReject.MALFORMED, "не JSON-обʼєкт")
        if (!SignedDocument.verify(document, publicKeyHex)) {
            return reject(PackReject.BAD_SIGNATURE, "підпис не сходиться з ключем збірки")
        }
        val manifest = runCatching { json.decodeFromString(PackManifest.serializer(), text) }
            .getOrElse { return reject(PackReject.MALFORMED, it.message ?: "поля не читаються") }

        structuralFault(manifest)?.let { return reject(PackReject.MALFORMED, it) }

        if (manifest.id in bundledIds) {
            return reject(PackReject.SHADOWS_BUNDLED, "«${manifest.id}» — вбудований пак збірки")
        }
        if (appBuild < manifest.minAppBuild) {
            return reject(
                PackReject.APP_TOO_OLD,
                "пак просить збірку ${manifest.minAppBuild}, ця — $appBuild",
            )
        }
        if (installedVersion != null && manifest.version <= installedVersion) {
            return reject(
                PackReject.OLDER_VERSION,
                "версія ${manifest.version} не новіша за встановлену $installedVersion",
            )
        }
        audit(manifest.sources).takeIf { it.isNotEmpty() }?.let {
            return reject(PackReject.LICENSE_VIOLATION, it.joinToString("; "))
        }
        return PackInstall.Accepted(InstalledPack(manifest, assetSource))
    }

    /**
     * Дефекти, після яких пак існував би, але брехав: рамка навиворіт, дім за
     * власною рамкою, транзитний фід чужого міста, правило комендантської,
     * якого ніхто не знайде за id.
     */
    private fun structuralFault(m: PackManifest): String? {
        if (m.id.length != 2) return "id «${m.id}» не схожий на ISO-3166-1 alpha-2"
        if (!m.bounds.valid) return "рамка навиворіт або поза межами світу"
        val bounds = m.bounds.toBounds()
        if (!bounds.contains(m.home.lat, m.home.lon)) return "дім пака лежить за його рамкою"
        m.transit.firstOrNull { !bounds.contains(it.anchorLat, it.anchorLon) }
            ?.let { return "фід «${it.city}» поза рамкою пака" }
        m.curfew.firstOrNull { it.toRule() == null }
            ?.let { return "правило комендантської «${it.regionId}» не читається" }
        m.alarmFeeds.firstOrNull { !it.endpoint.startsWith("https://") }
            ?.let { return "стрічка «${it.role}» не по https" }
        // Імʼя асета підставляється у шлях каталогу пака. Підпис і є межею
        // довіри, але «..» у ньому не має жодної законної причини бути.
        m.assetNames().firstOrNull { ".." in it || it.startsWith("/") }
            ?.let { return "асет «$it» виходить за каталог пака" }
        m.files.firstOrNull { ".." in it.name || it.name.startsWith("/") }
            ?.let { return "файл «${it.name}» виходить за каталог пака" }
        m.files.firstOrNull { !it.wellFormed }
            ?.let { return "файл «${it.name}»: хеш або розмір не читаються" }
        return null
    }

    /**
     * Асети, оголошені паком, але не покриті хешем. Порожньо — пак можна
     * везти дротом; непорожньо — ні, бо ці файли нічим звірити після
     * завантаження.
     *
     * Закон живе тут, а не в [structuralFault], бо стосується лише ДОРОГИ:
     * манiфест, покладений на диск руками (розробницький пак, sideload),
     * лишається валідним — його доказ походження той самий підпис.
     */
    fun unhashedAssets(m: PackManifest): List<String> =
        m.assetNames().filter { m.fileFor(it) == null }

    private fun reject(reason: PackReject, detail: String) = PackInstall.Rejected(reason, detail)
}
