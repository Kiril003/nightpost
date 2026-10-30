package local.phantom.companion

/**
 * Чим Play-збірка (`store`) відрізняється від польової — в одному місці.
 *
 * Маніфест store (src/store/AndroidManifest.xml) знімає дозволи й служби, яких
 * Google Play не пропускає. Але кнопка, що веде до знятої можливості, лишалась
 * би в налаштуваннях і відкривала б системний екран, де застосунку вже немає.
 * Тому кожна така кнопка питає тут, а тест `StoreManifestContractTest`
 * стежить, щоб прапорець і маніфест не розійшлись.
 */
object Distribution {

    val isStore: Boolean = BuildConfig.FLAVOR == "store"

    // Seeker (Solana dApp Store) бере маніфест store, тож усе зняте там знято й тут.
    private val isField: Boolean = BuildConfig.FLAVOR == "field"
    val batteryAsk: Boolean get() = isField
    val airAlertWatch: Boolean get() = isField
    val alertFullScreen: Boolean get() = isField
    val blackBox: Boolean get() = isField
    val plainKeys: Boolean get() = isField

    /** Орб і стрічка шепоту поверх інших застосунків (SYSTEM_ALERT_WINDOW). */
    val overlays: Boolean get() = isField

    /** Хибна позиція для інших застосунків (ACCESS_MOCK_LOCATION). */
    val falsePosition: Boolean get() = isField

    /** Слід, коли екран вимкнено (ACCESS_BACKGROUND_LOCATION). */
    val backgroundLocation: Boolean get() = isField

    /**
     * Служба спецможливостей (ScreenAwarenessService) і слухач сповіщень
     * (NotificationReceiverService). Маніфест store їх знімає, а seeker бере
     * той самий маніфест — тож двері до їхніх системних екранів лише у field.
     * Прапорець `isStore` тут не годиться: у seeker він false, а служб нема.
     */
    val systemListeners: Boolean get() = isField

    /** Сам качає й ставить APK з кабінету (REQUEST_INSTALL_PACKAGES). */
    val selfUpdate: Boolean get() = isField

    val cabinetUpdates: Boolean get() = isField

    val viaPlay: Boolean get() = isStore

    val viaDappStore: Boolean get() = BuildConfig.FLAVOR == "seeker"
}
