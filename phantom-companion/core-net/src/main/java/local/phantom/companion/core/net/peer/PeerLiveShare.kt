package local.phantom.companion.core.net.peer

/**
 * Правила того, ХТО ділиться: скільки триває, як часто йде, і — наперед — чи
 * дійде взагалі.
 *
 * Усе тут чисте, бо це та частина, у якій помилку видно лише через годину на
 * чужому телефоні. Радіо для перевірки не потрібне.
 */
object PeerLiveShare {

    /** Скільки триває. Вісім годин — з твердою межею, як у звичних месенджерах. */
    enum class Span { QUARTER_HOUR, HOUR, EIGHT_HOURS, UNTIL_STOP }

    /** Хвилини з веба → строк. Більше восьми годин — лише «до зупинки». */
    fun spanFor(minutes: Int): Span = when {
        minutes <= 15 -> Span.QUARTER_HOUR
        minutes <= 60 -> Span.HOUR
        minutes <= 8 * 60 -> Span.EIGHT_HOURS
        else -> Span.UNTIL_STOP
    }

    /**
     * Скільки живе точка, надіслана «до зупинки».
     *
     * Строк тут КОТИТЬСЯ разом з кожним тіком, і це не дрібниця: сеанс без
     * межі означав би, що загублений відклик лишає людину на чужій мапі
     * назавжди. Телефон, який замовк, гасне сам через цю чверть години —
     * навіть якщо «Зупинити» не доїхало ніколи.
     */
    const val ROLLING_MS = 15 * 60_000L

    fun endsAtMs(span: Span, startedAtMs: Long): Long? = when (span) {
        Span.QUARTER_HOUR -> startedAtMs + 15 * 60_000L
        Span.HOUR -> startedAtMs + 60 * 60_000L
        Span.EIGHT_HOURS -> startedAtMs + 8 * 60 * 60_000L
        Span.UNTIL_STOP -> null
    }

    /** Строк САМОГО листа. Обовʼязковий завжди — «до зупинки» котиться. */
    fun expiryFor(session: Session, atMs: Long): Long =
        session.endsAtMs ?: (atMs + ROLLING_MS)

    // ── дросель ──────────────────────────────────────────────────────────

    /** Зсув, за яким варто озватись. Менше — це шум GPS, а не рух. */
    const val MIN_SHIFT_M = 60.0

    /** Частіше не буває нічого: підлога тримає і батарею, і чужий журнал. */
    const val MIN_GAP_MS = 45_000L

    /**
     * Але й тиші довшої не буває: людина, що стоїть, теж має живий вік.
     *
     * Ця стеля — обіцянка ПРИЙМАЧЕВІ, а не наша внутрішня справа: його вікно
     * свіжості (`PEER_LIVE_FRESH_WINDOW_MS` у core-data) мусить накривати
     * `MAX_GAP_MS + TICK_MS`, інакше точка живого сеансу блідне на людині, яка
     * просто стоїть і чесно озивається. `PeerLiveCadenceTest` червоніє, щойно
     * ці двоє розійдуться.
     */
    const val MAX_GAP_MS = 4 * 60_000L

    /**
     * Крок годинника сеансу. Оголошений ТУТ, бо він входить у домовленість із
     * приймачем: надсилання квантується цим кроком, тож найдовша чесна тиша —
     * це [MAX_GAP_MS] плюс один такт, а не [MAX_GAP_MS].
     */
    const val TICK_MS = 20_000L

    data class Session(
        val peerId: String,
        val span: Span,
        val startedAtMs: Long,
        /** Null лише для [Span.UNTIL_STOP]: межу листа дає [expiryFor]. */
        val endsAtMs: Long?,
        val seq: Int = 0,
        val lastSentAtMs: Long = 0L,
        val lastLat: Double = Double.NaN,
        val lastLon: Double = Double.NaN,
    )

    fun open(peerId: String, span: Span, nowMs: Long): Session =
        Session(peerId, span, nowMs, endsAtMs(span, nowMs))

    fun over(session: Session, nowMs: Long): Boolean =
        session.endsAtMs != null && session.endsAtMs <= nowMs

    /** Скільки лишилось. Null — «до зупинки»: там лишається рівно стільки, скільки схоче людина. */
    fun remainingMs(session: Session, nowMs: Long): Long? =
        session.endsAtMs?.let { (it - nowMs).coerceAtLeast(0L) }

    /**
     * Чи озиватись зараз.
     *
     * Підлога [MIN_GAP_MS] стоїть ПЕРЕД перевіркою зсуву свідомо: людина в
     * машині зсувається на 60 м щосекунди, і без підлоги сеанс перетворився б
     * на потік, що витрачає батарею обох телефонів на точність, якої ніхто не
     * бачить. Стеля [MAX_GAP_MS] — з іншого боку: точка, старша за чверть
     * години свіжості, мовчки б збліднула, і людина побачила б «застаріло»
     * замість «стоїть на місці».
     */
    fun shouldSend(session: Session, lat: Double, lon: Double, nowMs: Long): Boolean {
        if (over(session, nowMs)) return false
        if (session.lastSentAtMs <= 0L) return true
        val since = nowMs - session.lastSentAtMs
        if (since < MIN_GAP_MS) return false
        if (since >= MAX_GAP_MS) return true
        if (session.lastLat.isNaN() || session.lastLon.isNaN()) return true
        return metersBetween(session.lastLat, session.lastLon, lat, lon) >= MIN_SHIFT_M
    }

    fun noteSent(session: Session, lat: Double, lon: Double, atMs: Long): Session =
        session.copy(seq = session.seq + 1, lastSentAtMs = atMs, lastLat = lat, lastLon = lon)

    /** Пласка земля на десятках метрів — похибка тут менша за похибку GPS. */
    fun metersBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2))
        return 6_371_000.0 * Math.hypot(dLat, dLon)
    }

    // ── чи дійде ─────────────────────────────────────────────────────────

    /**
     * Правда про доставку НАПЕРЕД, а не після.
     *
     * «В мережі» тут не питання: питання — чи є чим запечатати лист і чи є
     * куди його віддати. Порядок відповідей — від найтвердішої вади до
     * наймʼякшої, бо саме перша з них і є причиною, яку людина мусить почути.
     */
    enum class Reach {
        /** Ключа немає: без нового запрошення не поїде НІЩО, і чекати марно. */
        NO_KEY,

        /** Ключ є, шляху зараз немає: ляже в чергу й поїде, коли відкриється. */
        QUEUED,

        /** Телефон поруч: піде просто зараз, без сервера між ними. */
        MESH,

        /** Телефона поруч немає, але сховок візьме й потримає. */
        RELAY,
    }

    /**
     * Чи є ЩО надсилати.
     *
     * [Reach] каже, чи лист ДОЇДЕ; це — чи є що в нього класти. Два різні
     * питання, і саме друге мовчало: сеанс, заведений у приміщенні або на
     * свіжій установці без дозволу на локацію, доходить чудово й не везе
     * нічого, а плашка тим часом обіцяла «телефон поруч — піде одразу».
     */
    enum class Position {
        /** Вимір є: сеансу є чим озватись. */
        HELD,

        /** Дозвіл є, виміру ще немає — приймач і супутники. */
        SEARCHING,

        /** Дозволу на локацію немає: чекати марно, поки його не дадуть. */
        NO_PERMISSION,
    }

    /**
     * Один рядок плашки — те, що людина бачить на склі, доки її видно комусь.
     *
     * Імʼя лежить тут, а не в [Session], бо сеанс — це домовленість із КЛЮЧЕМ, а
     * імʼя живе в реєстрі й міняється, не переукладаючи сеансу.
     */
    data class Banner(
        val peerId: String,
        val name: String,
        val span: Span,
        val endsAtMs: Long?,
        val reach: Reach,
        val position: Position,
        /** Чи поїхала бодай одна репліка. Доки ні — обіцяти нема чого. */
        val sent: Boolean,
    )

    /**
     * Чи сміє поверхня казати «ділюсь»? Ні, доки з сеансу не поїхало нічого й
     * положення досі немає: це не «ще не встигло», а «немає чого везти».
     */
    fun promises(banner: Banner): Boolean =
        banner.sent || banner.position == Position.HELD

    /** Кому взагалі можна щось надіслати — і чи дійде. */
    data class Target(val peerId: String, val name: String, val reach: Reach)

    /**
     * Чужий маршрут, готовий для скла: чий він і як його назвати.
     *
     * Імʼя тут з тієї самої причини, що й у [Banner]: мапа малює речення, а не
     * ходить по нього в реєстр. Сам [PeerLive.Route] лишається цілим — «Їхати
     * цим маршрутом» будує план за його точками, тож розбирати його на числа
     * означало б збирати назад.
     */
    data class RouteMark(val peerId: String, val name: String, val route: PeerLive.Route)

    fun reach(hasKey: Boolean, meshNow: Boolean, relayOpen: Boolean): Reach = when {
        !hasKey -> Reach.NO_KEY
        meshNow -> Reach.MESH
        relayOpen -> Reach.RELAY
        else -> Reach.QUEUED
    }
}
