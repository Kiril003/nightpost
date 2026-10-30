package local.phantom.companion.core.license

enum class LicenseTier(val wire: String) {
    Free("free"),
    Personal("personal"),
    Crew("crew"),
    Subunit("unit");

    companion object {
        fun fromWire(value: String?): LicenseTier =
            entries.firstOrNull { it.wire == value?.trim()?.lowercase() } ?: Free
    }
}

enum class LicenseFeature(val key: String) {
    Chat("core.chat"),
    Map("core.map"),
    Nav("core.nav"),
    Alerts("core.alerts"),
    Vault("core.vault"),
    Voice("core.voice"),
    BridgePair("bridge.pair"),
    MemoryLongterm("memory.longterm"),
    MapTerrain3d("map.terrain3d"),
    VoicePremium("voice.premium"),
    GnssBlackbox("gnss.blackbox"),
    ExportReports("export.reports"),
    CrewSync("crew.sync"),
    FleetOnprem("fleet.onprem");

    companion object {
        fun fromKey(key: String): LicenseFeature? = entries.firstOrNull { it.key == key }
    }
}

object LicenseModel {

    val SAFETY_FLOOR: Set<LicenseFeature> = setOf(
        LicenseFeature.Map,
        LicenseFeature.Nav,
        LicenseFeature.Alerts,
        LicenseFeature.Vault,
    )

    // Вільний рівень — усе, чим людина рятує себе і дістає своїх. Чат сюди
    // входить разом із шифруванням: захист листа не може бути товаром.
    // Голос теж — зайняті руки це небезпека, а не зручність. І голос — це не
    // лише «щось звучить»: власник почув системного робота «майже скрізь» і
    // сказав, що його взагалі не має бути, тож живий голос (Supertonic) теж
    // вільний. Продається запис, розбір і синхронізація — не людський тембр.
    val DEFAULT_FREE: Set<LicenseFeature> = SAFETY_FLOOR + setOf(
        LicenseFeature.Chat,
        LicenseFeature.Voice,
        LicenseFeature.VoicePremium,
    )

    // Платне — те, що робить із рятівного інструмента щоденного супутника.
    // Тривога про підміну GPS лишається у SAFETY_FLOOR (Alerts); платить
    // лише той, кому потрібен запис і розбір польотів (GnssBlackbox).
    private val PERSONAL: Set<LicenseFeature> = DEFAULT_FREE + setOf(
        LicenseFeature.BridgePair,
        LicenseFeature.MemoryLongterm,
        LicenseFeature.MapTerrain3d,
        LicenseFeature.GnssBlackbox,
        LicenseFeature.ExportReports,
    )

    fun grants(tier: LicenseTier): Set<LicenseFeature> = when (tier) {
        LicenseTier.Free -> DEFAULT_FREE
        LicenseTier.Personal -> PERSONAL
        LicenseTier.Crew -> PERSONAL + LicenseFeature.CrewSync
        LicenseTier.Subunit -> PERSONAL + LicenseFeature.CrewSync + LicenseFeature.FleetOnprem
    }

    const val OFFLINE_GRACE_DAYS: Int = 30

    // 60, не 14: рішення власника 01.09 — платний ярус на два місяці КОЖНОМУ
    // для випробування. Два тижні не встигають показати те, за що платять.
    const val TRIAL_DAYS: Int = 60

    // Скільки поспіль ПІДПИСАНИХ відповідей «такої активації нема» треба, щоб
    // вважати мовчання відмовою сервера, а не браком мережі.
    const val GONE_CONFIRMATIONS: Int = 3

    const val DAY_MS: Long = 24L * 60 * 60 * 1000
}
