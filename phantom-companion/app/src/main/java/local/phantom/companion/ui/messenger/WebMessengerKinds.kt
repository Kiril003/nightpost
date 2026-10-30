package local.phantom.companion.ui.messenger

import local.phantom.companion.core.net.peer.PeerCard
import local.phantom.companion.core.net.peer.PeerDisappear
import local.phantom.companion.core.net.peer.PeerFile
import local.phantom.companion.core.net.peer.PeerFrames
import local.phantom.companion.core.net.peer.PeerLive
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerMoment
import local.phantom.companion.core.net.peer.PeerOutbox
import local.phantom.companion.core.net.peer.PeerPayload
import local.phantom.companion.core.solana.CashVoucher
import local.phantom.companion.core.solana.PaymentFrame
import local.phantom.companion.core.solana.SealFrame
import local.phantom.companion.core.solana.WalletAddressFrame

/**
 * Рід листа й розібрані поля для веба.
 *
 * Веб діставав маршрут родом «text» із сирим тілом `PH-RTE:1788…;337;258;FOOT;…`
 * і показував протокол людині. Рід тут вирішується один раз, за тими самими
 * розбирачами, якими його читає нативний екран: [PeerLive.decodeRoute] і
 * [PeerPayload.decodePlace]. Другого розбирача немає й бути не може — розійшлись
 * би дві правди про той самий дріт.
 *
 * Окремо від [WebMessengerBridge] з однієї причини: у модулі `app` юніт-тест
 * бачить від `org.json` заглушку, яка кидає на першому виклику. Тут немає жодного
 * `JSONObject`, тож рід перевіряється ЗНАЧЕННЯМ, а не читанням джерела.
 */
internal object WebMessengerKinds {

    const val TEXT = "text"
    const val IMAGE = "image"
    const val FILE = "file"

    /** Службовий рядок строку зникнення ([PeerDisappear]); [TIMER_S] — секунди, 0 — вимкнено. */
    const val TIMER = "timer"
    const val TIMER_S = "timer_s"

    /** Голосове й кружок — вкладення з родом в описі ([PeerFile.kindOf]). */
    const val VOICE = PeerFile.VOICE
    const val VIDEO_NOTE = PeerFile.VIDEO_NOTE
    const val ROUTE = "route"
    const val PLACE = "place"

    /**
     * Рід, яким веб ОГОЛОШУЄ надсилання місця.
     *
     * Не те саме, що [PLACE]: [PLACE] — рід, яким міст віддає вебові вже
     * розібраний кадр, а цей — слово, яким веб просить місце НАДІСЛАТИ. Тіло
     * під ним — JSON композера (`lat`, `lon`, `at`, `acc`, `label`), а не кадр;
     * кадр із нього складає міст.
     */
    const val GEO_POINT = "geo:point"
    const val BROKEN = "broken"

    /** Поле картки переказу; див. docs/solana/PAYMENT_CARD_SPEC.md. */
    const val PAYMENT = "payment"
    const val SEAL = "seal"

    /**
     * Вміст картки композера — уже розібраний, у полях, які малює веб.
     *
     * Тіло листа для таких родів — це JSON картки, а не слова людини: веб
     * читає його `JSON.parse` і віддає готовому компоненту.
     */
    const val CARD = "card"

    /**
     * Замість картки — чесне речення, складене мостом із ресурсів.
     *
     * Рід, спроможності якого в цій збірці немає (голос без звуку, вкладення
     * без байтів), не отримує картки взагалі: рамка з написом «Опитування» і
     * порожнечею всередині — це вигляд спроможності без спроможності.
     */
    const val NOTICE = "notice"

    /** Чим доповнити речення: тривалість, назва, позначка. */
    const val NOTICE_ARGS = "notice_args"

    /**
     * Кадр, якому в стрічці веба немає місця взагалі.
     *
     * Не «зіпсутий» і не «інший»: це службовий вантаж, який мешу потрібен, а
     * людині — ні (тік положення, відклик, шматок файла, конверт групи, дія над
     * складом). Веб їх і так не малює; рід називає це вголос, щоб міст міг на
     * ньому ж будувати лічильник і передогляд.
     */
    const val HIDDEN = "hidden"

    /** Ключ роду. Він є у відповіді завжди. */
    const val KIND = "kind"

    /** Рід, який тіло вдавало, коли розібрати його не вдалось. */
    const val BROKEN_KIND = "broken_kind"

    /**
     * Імʼя поля, з якого стрічка веба бере вміст картки, і саме тіло — окремо.
     *
     * Обидва [INTERNAL]: далі їх складає міст в ОДНЕ поле з іменем [CARD_FIELD]
     * і значенням-обʼєктом. Тут вони лишаються рядком, бо `org.json` у цьому
     * файлі немає свідомо ([fields] має перевірятись значенням, а не читанням
     * джерела), а тіло картки — це JSON, який склав і який прочитає ВЕБ.
     */
    const val CARD_FIELD = "card_field"

    const val CARD_JSON = "card_json"

    /** Який саме службовий вантаж сховано. Для журналу, не для стрічки. */
    const val HIDDEN_KIND = "hidden_kind"

    /**
     * Як виглядає МАШИННА позначка, а не речення, що з неї починається.
     *
     * Усі тринадцять родів композера й усі вісім дротових мають той самий вигляд:
     * спільний початок, далі великі латинські літери й цифри, далі двокрапка.
     * Без цієї суворості речення «PH-3 котушки, забери» вважалось би карткою і
     * поїхало б до веба з порожнім тілом — тобто зникло б у людини з очей.
     * Кирилиця сюди не входить свідомо: нею людина пише, а не позначає.
     */
    private val TAG = Regex("^" + PeerFrames.MARK + "[A-Z0-9_-]{1,20}:")

    /** Роди, у яких тіло — це слова людини й має доїхати як є. */
    private val SPOKEN = setOf(TEXT, IMAGE, FILE, VOICE, VIDEO_NOTE)

    /**
     * Ключі, з яких міст СКЛАДАЄ тіло, а не ті, що їдуть полями відповіді.
     *
     * Поле, якого ніхто не читає, — запрошення наступному заповнити його
     * правдоподібним. Ці троє потрібні рівно доти, доки міст не перетворив їх
     * на `body`, і далі не їдуть нікуди.
     */
    private val INTERNAL = setOf(CARD, NOTICE, NOTICE_ARGS, CARD_FIELD, CARD_JSON)

    /** Поля цього листа такими, якими їх побачить веб. */
    fun shipped(fields: Map<String, Any?>): Map<String, Any?> = fields.filterKeys { it !in INTERNAL }

    /**
     * Чого не буває в РЯДКУ переліку розмов.
     *
     * Перелік малює слово, а не мініатюру. Возити шістсот вершин ламаної на
     * кожен рядок при кожному оновленні — це той самий протокол, тільки дорожчий.
     */
    private val HEAVY = setOf("route_waypoints", "route_shape") + INTERNAL

    /**
     * Поля цього листа поверх спільних.
     *
     * Тіло, що починається як кадр пристрою, НІКОЛИ не віддається родом [TEXT]:
     * саме так сирий дріт опинявся в бульбашці. Не розібралось — [BROKEN] з
     * назвою роду, який рядок удавав, і без самого рядка.
     *
     * Судиться обтяте тіло, бо обтятим його читає й [PeerPayload.decodePlace];
     * пробіл попереду інакше повертав би кадр у «текст» разом із дротом.
     */
    fun fields(body: String): Map<String, Any?> {
        val wire = body.trim()
        val frame = PeerFrames.kindOf(wire) ?: return attachment(body, wire)
        return when (frame) {
            PeerFrames.Kind.ROUTE -> PeerLive.decodeRoute(wire)?.let { route ->
                mapOf(
                    KIND to ROUTE,
                    "route_profile" to route.profile,
                    "route_distance_m" to route.distanceM,
                    "route_duration_s" to route.durationS,
                    "route_expires_at_ms" to route.expiresAtMs,
                    "route_waypoints" to route.waypoints.map { listOf(it.first, it.second) },
                    "route_shape" to route.shape.map { listOf(it.first, it.second) },
                )
            } ?: broken(ROUTE)

            PeerFrames.Kind.PLACE -> PeerPayload.decodePlace(wire)?.let { place ->
                mapOf(
                    KIND to PLACE,
                    "place_lat" to place.lat,
                    "place_lon" to place.lon,
                    // Null — точності не сказали. Нуль тут був би виміром.
                    "place_accuracy_m" to place.accuracyM,
                )
            } ?: broken(PLACE)

            // Картка композера. Рід їде СЛОВОМ ВЕБА (`widget:kanban`), а не
            // власним: стрічка малює віджет лише коли бачить своє слово поруч
            // зі своїм полем, і будь-яка назва від себе була б рамкою з
            // написом і порожнечею всередині.
            PeerFrames.Kind.CARD -> PeerCard.decode(wire)?.let { card ->
                mapOf(
                    KIND to card.what.webType,
                    CARD_FIELD to card.what.webField,
                    CARD_JSON to card.payload,
                )
            } ?: broken(CARD)

            // Дзвінок сюди потрапив би й сам, гілкою `else`. Названий окремо,
            // бо це РІШЕННЯ, а не наслідок: стрічка розмови не малює ані
            // пропозиції, ані відповіді — їх показує екран дзвінка у вебі, і
            // тіло кадру йде туди напряму ([PeerCallHub.WEB_SINK]), а не через
            // стрічку. Рядок «пропущений дзвінок» колись складе той, хто його
            // показує, зі стану лінії, а не з сирого тіла кадру.
            // Строк зникнення — службовий рядок стрічки («повідомлення зникають
            // через 1 день»), а не репліка: ні непрочитаним, ні рядком переліку.
            PeerFrames.Kind.TTL -> PeerDisappear.decode(wire)?.let { (seconds, _) ->
                mapOf(KIND to TIMER, TIMER_S to seconds)
            } ?: mapOf(KIND to HIDDEN, HIDDEN_KIND to "ttl")

            PeerFrames.Kind.CALL_SIGNAL ->
                mapOf(KIND to HIDDEN, HIDDEN_KIND to frame.name.lowercase(java.util.Locale.US))

            // Решта — службовий вантаж. Більшість із них [PeerOutbox.isSpoken]
            // до розмови не пускає, але дію над складом (`PH-SYS:`) пускає, бо
            // на нативному екрані вона змістовна: хтось вийшов, когось додали.
            // Веб її не малює — отже міст не має ані рахувати її в непрочитані,
            // ані показувати як «останнє сказане».
            else -> mapOf(KIND to HIDDEN, HIDDEN_KIND to frame.name.lowercase(java.util.Locale.US))
        }
    }

    /** Чи тіло цього роду — слова людини, а не картка, складена машиною. */
    fun isSpokenKind(kind: Any?): Boolean = kind is String && kind in SPOKEN

    // ── що міст уміє покласти в дріт ─────────────────────────────────────

    /**
     * Чим стане тіло, оголошене родом [kind], — або чому не стане нічим.
     *
     * **Вада, заради якої це існує.** Міст складав справжній кадр рівно для
     * ОДНОГО роду (`geo:point`), а все інше падало в гілку `else` і їхало
     * `PeerMesh.sendTyped` — тобто як слово, набране пальцями. Веб при цьому
     * шле щонайменше ще один рід: кнопка «ділитися живим положенням» кладе в
     * `body` JSON вигляду `{"session_id":…,"point":{…},"expires_at":…}`, і
     * співрозмовник діставав цей JSON у бульбашку цілим. Це рівно та вада, яку
     * коментар над `geo:point` описує як ВИПРАВЛЕНУ — «протокол в очі тому, для
     * кого його ховають», — виправлену для одного роду з трьох.
     *
     * Гілки `else` тут більше немає, і це головне: саме вона була механізмом.
     * Рід, якого цей перелік не знає, отримує [Unknown] — тобто чесну відмову
     * з причиною, а не тихий пропуск у чужу стрічку. Наступний рід, доданий у
     * веб, спиниться тут видимо.
     */
    sealed interface Carry {
        /** Слова людини (і опис вкладення) — їдуть як є, входом набраного. */
        data object Typed : Carry

        /** Місце. Кадр складає МІСТ із розібраних полів, а не бере з веба. */
        data object Place : Carry

        /** Картка композера: тіло возиться цілим, рід — полем. */
        data class Card(val what: PeerCard.Card) : Carry

        /**
         * Рід оголошено, а дроту для нього немає.
         *
         * `geo:live` живе саме тут, і це РІШЕННЯ, а не недогляд. Живе положення
         * на дроті вже є, одне: [PeerLive] (`PH-LIV:`/`PH-LVX:`) зі строком у
         * кожному кадрі й лічильником такту, а сеанс йому веде `PeerLiveHub`.
         * Скласти з веб-JSON другий кадр означало б дві правди про одне живе
         * положення — і тоді строк і лічильник довелося б вигадати, бо веб
         * своїх не шле. Доки кнопка веба не заведена на той сеанс, вона чесно
         * відмовляє замість того, щоб слати JSON у чужу бульбашку.
         */
        data class Unknown(val kind: String) : Carry
    }

    /**
     * Голосове чи кружок без опису вкладення — не голосове: тіло поїхало б
     * словом у чужу бульбашку. Такий лист міст не везе ([Carry.Typed] для цих
     * родів має сенс лише разом із описом).
     */
    fun mediaWithoutDescription(kind: String, body: String): Boolean =
        kind.trim() in setOf(VOICE, VIDEO_NOTE) && PeerFile.describe(body) == null

    fun carry(kind: String): Carry {
        val word = kind.trim()
        if (word.isEmpty() || word in SPOKEN) return Carry.Typed
        if (word == GEO_POINT) return Carry.Place
        // День і погляд складає лише помічник; з веба вони не їдуть нікому — ні новими, ні пересланими.
        PeerCard.Card.ofWeb(word)?.let { return if (it in PeerCard.ASSISTANT_ONLY) Carry.Unknown(word) else Carry.Card(it) }
        return Carry.Unknown(word)
    }

    // ── що міст узагалі віддає вебові ────────────────────────────────────

    /**
     * ОДНЕ рішення про видимість, з якого живуть усі три числа.
     *
     * Плашка непрочитаних рахувала через [PeerOutbox.isSpoken], а стрічку
     * малював веб зі своїм заслоном — і дія над складом потрапляла в число, не
     * потрапивши в стрічку: людина бачила «1», відкривала розмову й не бачила
     * там нічого нового. Мовчазна поразка з боку числа.
     *
     * Тому лічильник, передогляд і сама стрічка беруться звідси, а не кожен зі
     * свого предиката. [PeerOutbox.isSpoken] лишається як був: він спільний з
     * нативним екраном, де ці рядки змістовні. Міст лише ЗВУЖУЄ його — ніколи
     * не розширює.
     */
    fun isVisible(body: String): Boolean = fields(body).let { it[KIND] != HIDDEN && !isServiceNotice(it) }

    /**
     * «Цей застосунок ще не вміє показати…» about a machine frame (a wallet address
     * swap, say). Not a word anybody said: no bubble, no preview, no unread.
     */
    private fun isServiceNotice(fields: Map<String, Any?>): Boolean =
        fields[NOTICE] == WebMessengerCards.SAY_UNKNOWN || fields[NOTICE] == WebMessengerCards.SAY_EMPTY

    /** Стрічка розмови такою, якою її побачить веб. */
    fun feed(conversation: List<PeerMessage>): List<PeerMessage> =
        conversation.filter { isVisible(it.body) }

    /**
     * Стрічка розмови вдвох: сказане плюс службовий рядок строку зникнення.
     * [PeerOutbox.conversation] пускає лише сказане, тож рядок [TIMER] не
     * доходив до вебу на жодному боці.
     */
    fun dmFeed(log: List<PeerMessage>, peerId: String): List<PeerMessage> =
        feed(
            log.filter {
                (it.toId == peerId || it.fromId == peerId) &&
                    (PeerOutbox.isSpoken(it) || PeerDisappear.isTimer(it.body))
            }.sortedBy { it.sentAtMs },
        )

    /**
     * Скільки НЕПРОЧИТАНОГО побачить людина, відкривши розмову.
     *
     * Рівно ті самі листи, що й у [feed]: число, яке обіцяє більше, ніж стрічка
     * покаже, — це плашка, яка бреше.
     */
    fun unread(log: List<PeerMessage>, peerId: String, readUpToMs: Long): Int =
        log.count {
            it.fromId == peerId && it.sentAtMs > readUpToMs &&
                PeerOutbox.isSpoken(it) && isVisible(it.body)
        }

    /**
     * Рядок переліку для помічника: останнє видиме, крім його службових
     * відмов («мій розум на цьому телефоні ще не ввімкнено»). Помічник
     * закріплений угорі, тож відмова ставала першим рядком усього списку
     * чатів (UX-аудит №1, 09.10). У самій розмові відмова лишається — там вона
     * чесна відповідь; у переліку — останнє інше, або нічого.
     */
    /** Кадри, що в розмові стоять листом: картка, місце, лист у групі. */
    private val LETTER_FRAMES = setOf(PeerFrames.Kind.GROUP, PeerFrames.Kind.CARD, PeerFrames.Kind.PLACE)

    /**
     * Скільки ЛИСТІВ чекає звʼязку — число на смузі «N повідомлень чекають».
     *
     * DL-4 (ніч 09.10, A52): після одного набраного листа смуга казала «5», а
     * на A56 — «2» після самого лише додавання людини. `/queue/status` рахував
     * кожен рядок черги: профіль, ключі скриньки, «прочитано», друк — службові
     * кадри, яких людина не писала. Тепер: слово людини — один, файл — один
     * (скільки б шматків він не мав), службовий кадр — нуль.
     */
    fun queuedLetters(outbox: List<PeerMessage>): Int {
        val queued = outbox.filter { it.state == PeerMessage.State.QUEUED && it.deletedAtMs == 0L }
        val (chunks, rest) = queued.partition { PeerFile.isChunk(it.body) }
        val words = rest.count { m -> PeerFrames.kindOf(m.body).let { it == null || it in LETTER_FRAMES } }
        return words + chunks.mapNotNull { PeerFile.head(it.body)?.blobId }.distinct().size
    }

    fun lastForList(log: List<PeerMessage>, peerId: String, notice: (String) -> Boolean): PeerMessage? =
        log.filter {
            (it.toId == peerId || it.fromId == peerId) &&
                PeerOutbox.isSpoken(it) && isVisible(it.body) &&
                !(it.fromId == peerId && notice(it.body))
        }.maxByOrNull { it.sentAtMs }

    /** Відмову порівнюємо без різниці в пробілах і краях. */
    fun noticeKey(text: String): String = text.trim().replace(Regex("\\s+"), " ")

    /** Останнє, що видно в стрічці, — і саме воно стає рядком переліку. */
    fun lastVisible(log: List<PeerMessage>, peerId: String): PeerMessage? =
        log.filter {
            (it.toId == peerId || it.fromId == peerId) &&
                PeerOutbox.isSpoken(it) && isVisible(it.body)
        }.maxByOrNull { it.sentAtMs }

    /**
     * Те саме для РЯДКА переліку розмов, з префіксом `last_`.
     *
     * Рядок списку — друге місце, де власник бачив сирий дріт: бульбашку
     * лікували, а `last_snippet` і далі віддавав `PH-RTE:1788…` цілим. Рід і
     * пласкі поля тут ті самі, що й у листі, тож розійтись їм нема на чому.
     */
    fun rowFields(body: String): Map<String, Any?> =
        fields(body).let { f -> f.filterKeys { it !in HEAVY }.mapKeys { (key, _) -> "last_" + key } + WebMessengerCardLine.row(f) }

    private fun attachment(body: String, wire: String): Map<String, Any?> {
        if (PeerMoment.isMoment(body)) return mapOf(KIND to HIDDEN, HIDDEN_KIND to "moment")
        val att = PeerFile.describe(body)
        if (att != null) {
            // Рід — з опису: голосове лягає голосовим (з хвилею), кружок —
            // кружком, а не файлом із кнопкою «завантажити».
            return mapOf(KIND to att.kind)
        }
        // Слова моделі, переказані вузлом того, хто її покликав у групі.
        //
        // Гілка мусить стояти ВИЩЕ за [WebMessengerCards]: позначка машинна, і
        // без неї рядок доходив би до `unknown(mark)` — тобто відповідь моделі
        // підмінялась би реченням «невідома позначка PH-AI» і зникала з очей
        // усіх, включно з тим, хто її покликав.
        //
        // Рід [TEXT], бо це сказане, а не вантаж: лічильник непрочитаного,
        // рядок переліку і стрічка мусять рахувати її як репліку. А те, ЧИЇ це
        // слова, їде окремим полем — тіло під позначкою ніхто не має права
        // приписати людині, чий вузол його привіз.
        solana(wire)?.let { return it }
        WebMessengerAi.said(wire)?.let { said ->
            return mapOf(KIND to TEXT, WebMessengerAi.SAID to said)
        }
        val tag = TAG.find(wire) ?: return mapOf(KIND to TEXT)
        val mark = tag.value.dropLast(1)
        // Рід «інше» звідси прибрано: у вебі немає ЖОДНОГО читача `other_mark`
        // і жодної гілки на такий тип, тож картка приїжджала порожньою
        // бульбашкою з самим годинником. Тепер кожна позначка отримує або свій
        // вміст, або чесне речення — третього немає.
        return when (val card = WebMessengerCards.of(wire)) {
            is WebMessengerCards.Read.Card -> mapOf(KIND to card.kind, CARD to card.payload)
            is WebMessengerCards.Read.Words -> words(card)
            WebMessengerCards.Read.Silent -> mapOf(KIND to HIDDEN, HIDDEN_KIND to mark.lowercase(java.util.Locale.US))
            // Позначка, якої цей перелік не знає взагалі. Покалічена знайома
            // сюди не доходить — вона каже про себе окремо й точніше.
            null -> words(WebMessengerCards.unknown(mark))
        }
    }

    /**
     * Переказ, готівка, відбиток — рід лишається словом, щоб нинішній бандл показав речення;
     * нова картка читає [PAYMENT] або [SEAL]. Обмін адресами — службовий, у стрічку не йде.
     */
    fun solana(wire: String): Map<String, Any?>? {
        if (WalletAddressFrame.isAddress(wire)) return mapOf(KIND to HIDDEN, HIDDEN_KIND to "wallet_address")
        val said = mapOf(KIND to TEXT, NOTICE to WebMessengerCards.SAY_SOLANA, NOTICE_ARGS to listOf(wire))
        PaymentFrame.decode(wire)?.let { return said + (PAYMENT to payment(it, cash = false)) }
        CashVoucher.decode(wire)?.let { return said + (PAYMENT to payment(it.payment, cash = true)) }
        SealFrame.decode(wire)?.let { f ->
            return said + (SEAL to mapOf("sha256" to f.sha256Hex, "signature" to f.signature, "cluster" to f.cluster.wire))
        }
        return null
    }

    private fun payment(frame: PaymentFrame, cash: Boolean): Map<String, Any?> = mapOf(
        "amount" to frame.amount,
        "token" to frame.coin.symbol,
        "units" to frame.units.toString(),
        "decimals" to frame.coin.decimals,
        "cluster" to frame.cluster.wire,
        "to" to frame.to.base58,
        "signature" to frame.signature,
        "offline_cash" to cash,
        // Id рахунку з `PaymentFrame` v2; null — переказ v1, ні до чого не привʼязаний.
        "ref" to frame.ref,
        "note" to frame.note,
    )

    private fun words(said: WebMessengerCards.Read.Words): Map<String, Any?> =
        mapOf(KIND to TEXT, NOTICE to said.code, NOTICE_ARGS to said.args)

    // ── дорога ───────────────────────────────────────────────────────────

    // ── доля листа поза цим апаратом ─────────────────────────────────────

    /** Лист не покидав цього апарата: сховку його не давали або той не взяв. */
    const val RELAY_LOCAL = "local"

    /** Сховок узяв байти. Про адресата це не каже НІЧОГО. */
    const val RELAY_STORED = "stored"

    /** Квитанція була: той бік сказав, що лист у нього. */
    const val RELAY_ACKED = "acked"

    /**
     * Що телефон знає про долю листа — значенням, а не читанням JSON.
     *
     * Живе тут із тієї ж причини, що й [fields]: у модулі `app` юніт-тест
     * бачить від `org.json` заглушку, яка кидає на першому виклику, тож
     * усередині мосту це було б написане й неперевірне.
     *
     * [RELAY_STORED] не сміє згорнутись у [RELAY_LOCAL]: сховок відповів 200,
     * і саме на цьому телефон досі казав «лежить на вашому вузлі, надішлемо
     * щойно співрозмовник зʼявиться» — два позитивні твердження про те, чого
     * він знати не може.
     */
    fun relayState(m: PeerMessage): String = when {
        m.state == PeerMessage.State.DELIVERED -> RELAY_ACKED
        m.state == PeerMessage.State.HELD && m.heldNonce.isNotBlank() -> RELAY_STORED
        else -> RELAY_LOCAL
    }

    /** Лист лежав у сховку. Веб: «Зі скриньки — лист чекав, поки канал був мертвий». */
    const val MAILBOX = "mailbox"

    /** Лист пішов дротом і той кінець підтвердив. Веб: «Напряму від вузла відправника». */
    const val DIRECT = "direct"

    /**
     * Якою дорогою поїхав лист — або мовчання.
     *
     * Міст ставив `p2p` КОЖНОМУ листу, а веб малює з цього слова значок «P2P»
     * з підписом «Доставлено напряму через WebRTC P2P DataChannel» — і ставив
     * його на лист, що пролежав у сховку, і на той, що ще нікуди не поїхав.
     * Це прямо проти правила «кажи, чи дійде»: значок обіцяв дорогу, якою лист
     * не їхав.
     *
     * Знаємо лише про СВОЇ листи. [PeerMessage.heldNonce] — місцева бухгалтерія
     * сховка, і вона переживає квитанцію: [PeerOutbox.markDelivered] її не
     * чіпає, тож «дійшло» і «дійшло через сховок» лишаються різними реченнями.
     *
     * Про чужий лист дорога невідома ЗА ПОБУДОВОЮ: `PeerEnvelope.sealAs` зрізає
     * цей облік перед запечатуванням, а обидві вхідні двері лишають рядок
     * однаковим. Тоді поля немає взагалі — веб малює «—» і жодного значка.
     */
    fun road(m: PeerMessage, meId: String): String? = when {
        meId.isBlank() || m.fromId != meId -> null
        m.heldNonce.isNotBlank() -> MAILBOX
        m.state == PeerMessage.State.DELIVERED -> DIRECT
        else -> null
    }

    /**
     * Дорога віяра копій: одна на всіх або жодної.
     *
     * У групі кожна копія їде своєю дорогою. Назвати дорогою рядка ту, якою
     * поїхала перша копія, означало б сказати за решту — те саме перебільшення,
     * від якого `PeerGroup.collapse` береже стан доставки.
     */
    fun road(copies: List<PeerMessage>, meId: String): String? =
        copies.takeIf { it.isNotEmpty() }
            ?.map { road(it, meId) }
            ?.distinct()
            ?.singleOrNull()

    private fun broken(claimed: String): Map<String, Any?> =
        mapOf(KIND to BROKEN, BROKEN_KIND to claimed)
}
