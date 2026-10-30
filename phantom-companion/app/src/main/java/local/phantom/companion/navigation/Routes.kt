package local.phantom.companion.navigation

sealed class Routes(val path: String) {

    /** First-frame gate: profiles → onboarding, launcher or home. Never on the back stack. */
    data object Bootstrap : Routes("bootstrap")

    /**
     * Перший запуск (збірка 18): знак, один рядок і вибір мови — раніше за
     * знайомство й розмови. Лише доки мову не обрано; далі сюди не веде ніщо.
     */
    data object Language : Routes("language")

    data object Onboarding : Routes("onboarding")

    /** Multi-profile picker, when profiles exist but none is active. */
    data object Launcher : Routes("launcher")

    /** Connect a computer. */
    data object Pair : Routes("pair")

    data object Scan : Routes("scan")

    /** Чати · Мапа · Профіль. */
    data object Home : Routes("home")

    /** Сховище: паролі, нотатки й ключі під відбитком пальця. */
    data object Vault : Routes("vault")

    /** One person: name, verification, removal. */
    data object PeerCard : Routes("peer/{peerId}/card") {
        fun of(peerId: String) = "peer/$peerId/card"
    }

    data object SettingsDiagnostics : Routes("settings/diagnostics")

    /** Satellites, jamming and spoofing verdicts from raw GNSS. */
    data object Precision : Routes("precision")

    data object SettingsVoice : Routes("settings/voice")

    data object SettingsMic : Routes("settings/mic")

    /** Map look and offline areas. */
    data object SettingsMap : Routes("settings/map")

    data object SettingsSymbiote : Routes("settings/symbiote")

    /** What PHANTOM may do on the phone: ask / allowed / never, and the log. */
    data object SettingsTools : Routes("settings/tools")

    data object SettingsWhisper : Routes("settings/whisper")

    data object SettingsDataLicenses : Routes("settings/data-licenses")

    /** This copy's rights. */
    data object SettingsLicense : Routes("settings/license")
}
