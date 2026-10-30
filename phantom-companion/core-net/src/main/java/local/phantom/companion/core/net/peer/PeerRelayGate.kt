package local.phantom.companion.core.net.peer

import io.ktor.client.HttpClient

/**
 * Єдині двері до сховка — і за замовчуванням вони зачинені.
 *
 * [Settings.enabled] лишається хибним з коробки, і поки він хибний, жодного
 * запиту назовні не існує: не «запити є, але їх глушать», а нема з чого їх
 * зробити. Це охороняє клас від випадкового вжитку.
 *
 * Але в продукті сховок УВІМКНЕНО, і хто це робить — сказано тут, щоб читач
 * цього файлу не вирішив, ніби на телефоні його немає: єдине місце —
 * `AppContainer.openRelay`, адреса та сама, що й у ліцензій. Рішення власника:
 * месенджер, обмежений спільною мережею, не месенджер, тож сервери тримають
 * листи. Що при цьому сервер бачить, а чого не бачить, — у [PeerRelay].
 *
 * Конструктор [PeerRelayClient] навмисне `internal`. Без цього гілка «а тут
 * просто зробимо клієнт» лишалась би відкритою, і прапорець охороняв би не
 * шлях, а звичку. Тепер поза цим модулем скласти клієнт нема як, і обійти
 * прапорець видно тільки тут.
 */
object PeerRelayGate {

    /** З коробки — вимкнено. Це не обережність, це умова, на якій це збудовано. */
    const val DEFAULT_ENABLED = false

    /**
     * Адреса сховка не має типового значення. Порожня адреса — це «власник ще
     * не сказав, куди», а не «поки що ходімо кудись».
     */
    data class Settings(
        val enabled: Boolean = DEFAULT_ENABLED,
        val baseUrl: String = "",
        /** Старий сховок на час переїзду: копії йдуть і туди, забір питає й там. */
        val legacyUrl: String = "",
    )

    /**
     * Чому сховка зараз немає. Кожна причина мусить мати СЛОВО: «вимкнено»,
     * «немає адреси» і «адреса відкритим текстом» — це три різні розмови з
     * власником, і жодна з них не колір і не значок.
     */
    enum class Verdict {
        /** Прапорець вимкнено. Типовий стан. */
        OFF,

        /** Увімкнено, але куди ходити — не сказано. */
        NO_ADDRESS,

        /**
         * `http://`. Адреси скриньок їдуть у тілі запиту: під відкритим текстом
         * будь-хто на шляху зшиває IP з конкретною скринькою, і вся робота зі
         * схову адрес не варта нічого.
         */
        PLAINTEXT,

        /** Можна ходити. */
        READY,
    }

    fun verdict(settings: Settings): Verdict {
        val url = settings.baseUrl.trim()
        return when {
            !settings.enabled -> Verdict.OFF
            url.isBlank() -> Verdict.NO_ADDRESS
            !url.startsWith("https://") -> Verdict.PLAINTEXT
            url.length <= HTTPS.length -> Verdict.NO_ADDRESS
            else -> Verdict.READY
        }
    }

    /**
     * Транспорт до сховка, або null — і null означає, що цей телефон у бік
     * сховка не робить нічого взагалі.
     *
     * [contacts] — скриньки, які телефон стереже ([PeerRelay.pollingWanted]):
     * контакти плюс власні двері. Без особистості й без контактів — нуль, і
     * запиту немає; з особистістю двері стережуться й з порожнім реєстром,
     * бо саме туди стукає той, хто додав нас першим.
     */
    fun open(
        settings: Settings,
        contacts: Int,
        http: HttpClient? = null,
        legacyHttp: HttpClient? = http,
        legacyQueue: java.io.File? = null,
    ): PeerRelayTransport? {
        if (verdict(settings) != Verdict.READY) return null
        if (!PeerRelay.pollingWanted(contacts)) return null
        val base = settings.baseUrl.trim().trimEnd('/')
        val primary = PeerRelayClient(baseUrl = base, http = http)
        val old = settings.legacyUrl.trim().trimEnd('/')
        if (old == base || verdict(Settings(enabled = true, baseUrl = old)) != Verdict.READY) return primary
        val legacy = PeerRelayClient(
            baseUrl = old,
            http = legacyHttp,
            connectCapMs = PeerRelayFanout.LEGACY_CONNECT_MS,
        )
        return PeerRelayFanout(primary, legacy, legacyOn = { primary.legacyRelay != false }, queueFile = legacyQueue)
    }

    private const val HTTPS = "https://"
}
