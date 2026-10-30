package local.phantom.companion.core.net.pack

/**
 * Process-wide holder for where world packs (basemap tiles, road-mesh +
 * transit) live — same shape as
 * [local.phantom.companion.core.net.roadpack.RoadPackLink] and
 * [local.phantom.companion.core.net.map.GeoEndpoints]: `:app`'s
 * `AppContainer` installs it once at boot from `BuildConfig`, so
 * `:feature-precision`/`:core-geo` (which cannot depend on `:app`) read the
 * live address without threading it through nav-graph plumbing.
 *
 * Deliberately the SAME `PACKS_BASE_URL` already backing country packs
 * (bucket `phantom-maps` on R2, see `app/build.gradle.kts`) rather than a
 * second address: one pack kind lives at `<base>/<iso2>/manifest.json`, this
 * one at `<base>/<id>/manifest.json` with a different `id` shape (e.g.
 * `road-mesh-ua`, `basemap-ua-detailed`) — same bucket, same "empty means
 * not configured yet" honesty, one place to point at a host.
 */
object WorldPackLink {
    @Volatile
    var baseUrl: () -> String = { "" }

    /**
     * Якір довіри пакетів — `BuildConfig.PACK_PUBLIC_KEY`, 64 шістнадцяткові
     * символи публічного ключа Ed25519. Той самий ключ, що й у пакiв країн:
     * підписує їх один складач, і ліцензійний ключ ним не стає.
     *
     * Порожній (типовий стан збірки) означає «не приймається жоден
     * завантажений пакет», а не «приймаються всі» — див. [WorldPackTrust].
     */
    @Volatile
    var publicKeyHex: () -> String = { "" }

    /**
     * Поріг СХЕМИ цієї збірки — `BuildConfig.PACK_SCHEMA_BUILD` — проти
     * `minAppBuild` пака. Нуль (типовий стан, поки `:app` не назвався)
     * означає «пак із порогом не поїде», а не «поріг не рахується»: та сама
     * постава, що й у порожнього ключа вище.
     *
     * НЕ `VERSION_CODE`: той у debug навмисно лишається 1, щоб оголошення про
     * збірку 8 читалось як новіше, — і цей заслін відкидав там КОЖЕН пак із
     * живого каталогу («пак просить збірку 8, а ця — 1»). Питання тут одне:
     * «чи вміє ця збірка прочитати формат», а не «наскільки вона свіжа».
     */
    @Volatile
    var appBuild: () -> Int = { 0 }
}
