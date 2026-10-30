package local.phantom.companion.core.net.peer

/**
 * Що в тілі листа склав ПРИСТРІЙ, а не людина.
 *
 * Рід службового кадру визначався єдино першими літерами тіла, а [PeerMessage]
 * не має і не матиме поля про походження — воно не підписане й не перевірне.
 * Тож рядок, набраний пальцями, ставав керівним кадром: співрозмовник бачив
 * намальований маршрут або чужу позначку на мапі як ВИМІРЯНІ пристроєм. Це не
 * витік і не обхід шифрування — писати може лише той, хто вже в розмові, — це
 * підробка походження, і лікується вона на вході, а не на показі.
 *
 * Свого переліку початків тут немає: [kindOf] — це «або» над предикатами тих
 * самих обʼєктів, що ці кадри й складають, а позначки в [Kind] беруться звідти
 * ж. Тому рід не можна ані додати в дріт повз цей реєстр, ані описати тут
 * інакше, ніж він насправді летить.
 *
 * Опис вкладення ([PeerFile.describe]) сюди НЕ входить, і це рішення, а не
 * недогляд: веб кладе файл тим самим набраним входом, яким шле слово, тож
 * відмова йому зупинила б надсилання файлів з веб-месенджера. Опис вкладення й
 * не вдає вимір — він описує байти, які їдуть слідом.
 */
object PeerFrames {

    /**
     * Спільний початок УСІХ машинних позначок продукту.
     *
     * Ширший за реєстр нижче, і навмисно: композер складає ще десяток карток
     * (голос, опитування, рахунок, подія…), яких дріт не знає, а людина бачить
     * у тій самій стрічці. Нативний екран має заслін за замовчуванням і сирим
     * їх не показує ніколи; будь-яка інша поверхня мусить мати такий самий.
     *
     * Що кожна позначка реєстру починається саме цим, звіряє сторож — інакше
     * це була б друга правда про той самий дріт.
     */
    const val MARK = "PH-"

    /**
     * Кожен рід, який продукт складає сам.
     *
     * [reachesConversation] — оголошений факт про [PeerOutbox.isSpoken], а не
     * друга його реалізація: рівність звіряє сторож. Він і є тим, що не дасть
     * мовчки впустити в розмову новий рід, лишивши його без відмови.
     */
    enum class Kind(val mark: String, val reachesConversation: Boolean) {
        ROUTE(PeerLive.ROUTE, reachesConversation = true),
        FIX(PeerLive.FIX, reachesConversation = false),
        STOP(PeerLive.STOP, reachesConversation = false),
        PLACE(PeerPayload.LOC, reachesConversation = true),
        CHUNK(PeerFile.CHUNK, reachesConversation = false),
        GROUP(PeerGroup.TAG, reachesConversation = false),
        SYSTEM(PeerGroup.SYS, reachesConversation = true),

        // Рядок каналу простору живе лише всередині листа гілки; голим тілом
        // розмови вдвох він нічого не значить. Склав його пристрій, тож
        // набране з таким початком не їде — інакше людина обирала б канал,
        // минаючи замок, яким пристрій рахує читачів.
        CHANNEL(PeerGroup.CHANNEL, reachesConversation = false),

        // Картка композера. ДОХОДИТЬ до розмови, на відміну від решти вантажу:
        // вона і є те, що людина надіслала, — канва, дошка, голосування. До
        // цього роду не існувало взагалі, і всі тринадцять карток лягали у
        // власну стрічку відправника, нікуди не їдучи; тепер вони їдуть тією
        // самою дорогою, що й слово.
        CARD(PeerCard.TAG, reachesConversation = true),

        // Дзвінок — увесь, ОДНИМ родом. До розмови він не доходить, і це
        // рішення: пропозиція має підняти апарат, а не лягти рядком у стрічку.
        // Рядок про дзвінок, який уже відбувся, — окрема річ, і складе його
        // той, хто його покаже, а не дріт. Чотирьох сусідів (`PH-CAL:`,
        // `PH-CAA:`, `PH-CAX:`, `PH-CAE:`) тут більше немає: їх заступив
        // [PeerCall.SIGNAL] один в один, а мертвий рід у реєстрі — це
        // запрошення наступному в нього вписатись.
        CALL_SIGNAL(PeerCall.SIGNAL, reachesConversation = false),

        // Смайл і голос за варіант опитування. До розмови не доходять свідомо:
        // людина поставила позначку на ЧУЖОМУ рядку, а не написала листа —
        // окремий рядок «❤️» у стрічці був би листом, якого ніхто не писав.
        // Їх згортають у поля того рядка, на який вони вказують.
        REACTION(PeerMark.What.REACTION.mark, reachesConversation = false),
        VOTE(PeerMark.What.VOTE.mark, reachesConversation = false),
        // Стан людини на картці зустрічі чи рахунку («іду», «буду о 19:10», «я
        // їв борщ»). Той самий дотик на чужому рядку, що й смайл: не лист, не
        // плашка, не «1 чекає» — міст згортає його в `card_marks` картки.
        CARD_STATE(PeerMark.What.CARD_STATE.mark, reachesConversation = false),
        // Закріплення в розмові удвох: дотик на рядку, не лист.
        PIN(PeerMark.What.PIN.mark, reachesConversation = false),
        HIST(PeerHistory.PACK, reachesConversation = false),
        // Профіль відправника. До розмови НЕ доходить, і це рішення: людина
        // змінила свій аватар — це не репліка в стрічці. Кадр оновлює картку
        // людини, а стрічка про нього не знає нічого.
        PROFILE(PeerProfile.TAG, reachesConversation = false),
        // Фото банера профілю: байти до опису в кадрі профілю, не репліка.
        BANNER(PeerBanner.TAG, reachesConversation = false),
        // Прохання про відео банера: службове, у стрічку не йде.
        BANNER_PULL(PeerBanner.PULL, reachesConversation = false),
        WHERE(PeerWhere.TAG, reachesConversation = false),
        EDIT(PeerRevise.EDIT, reachesConversation = false),
        UNSEND(PeerRevise.UNSEND, reachesConversation = false),
        READ(PeerRead.TAG, reachesConversation = false),
        TYPING(PeerTyping.TAG, reachesConversation = false),

        // Намір до мого ПК і його відповідь (розд. 07 «Обхідний інтернет»).
        // До розмови не доходять: це запечатане прохання й запечатана
        // відповідь, а не слова. Показує їх екран «Мої прохання», не стрічка.
        INTENT(PeerIntent.TAG, reachesConversation = false),
        ANSWER(PeerIntent.ANSWER_TAG, reachesConversation = false),
        GATEWAY(PeerIntent.GATEWAY_TAG, reachesConversation = false),

        // Пачка prekey конверта v5 (docs/crypto/RELAY_FS.md): ключі, а не слова.
        PREKEYS(RelayFs.BUNDLE_TAG, reachesConversation = false),

        // Строк зникнення розмови (PeerDisappear). Не репліка: ні непрочитаним,
        // ні рядком переліку він не стає; стрічка показує його службовим рядком.
        TTL(PeerDisappear.TAG, reachesConversation = false),

        // Картка посилання, складена відправником (PeerPreview). Не репліка —
        // частина свого листа, як смайл.
        PREVIEW(PeerPreview.TAG, reachesConversation = false),

        // Момент «мого дня» ([MomentWire]): власна сутність зі своєю книгою, не репліка.
        MOMENT(MomentWire.TAG, reachesConversation = false),

        // Запит у контакти — дріт ПК, а не PH-: той самий кадр їде між телефоном
        // і вузлом. Не репліка; його показує екран запитів.
        CONTACT(PeerContact.TAG, reachesConversation = false),

        // Прохання вступити за посиланням і відповідь на нього: службове.
        GROUP_JOIN(PeerGroupJoin.TAG, reachesConversation = false),
        // Пропуск працівника бізнесу ([StaffWire]): доручені розмови, не репліка.
        STAFF(StaffWire.TAG, reachesConversation = false),

        // Кадр НОВІШОЇ збірки, якого ця не знає: будь-який незнайомий початок
        // форми `PH-<три великі>:` ([isFuture]). До розмови не доходить, і це
        // рішення з досвіду, а не обережність: збірки 15 і 17 закінчували
        // реєстр `else -> null`, і `PH-CST:` збірки 18 лягав би в них
        // бульбашкою з сирим дротом, непрочитаним і плашкою. Новий рід, який
        // людина МАЄ бачити, їде карткою (`PH-CRD:` з новим кодом), а не
        // власним тегом. [mark] тут — зразок форми, а не тег: такого тегу
        // ніхто не шле, і саме ним сторожі збирають тіло цього роду.
        FUTURE(FUTURE_SAMPLE, reachesConversation = false),
    }

    /** Зразок форми незнайомого тегу — для [Kind.FUTURE.mark] і сторожів. */
    const val FUTURE_SAMPLE = "PH-XXX:"

    /**
     * Теги поза цим модулем, які Є словами людини, хоча мають ту саму форму.
     *
     * `PH-PAY:` — переказ у розмові (`core-solana/PaymentFrame.TAG`): картка з
     * сумою, яку міст розбирає лише тоді, коли реєстр сказав «не кадр».
     * `core-net` того модуля не бачить, тож тег вписано буквально; розійтись
     * мовчки він не може — `WebMessengerSolanaKindsTest` червоніє, щойно
     * переказ стане прихованим.
     */
    private val SPOKEN_ELSEWHERE = setOf("PH-PAY:")

    /** Теги, які реєстр уже знає, — їх судять їхні власні предикати. */
    private val KNOWN by lazy {
        Kind.entries.filter { it != Kind.FUTURE }.map { it.mark }.toSet() + SPOKEN_ELSEWHERE
    }

    /**
     * Чи тіло — кадр збірки, новішої за цю: `PH-` + рівно три великі латинські
     * + `:` на самому початку, і такого тегу тут не знають. Знайомий тег із
     * покаліченим тілом сюди НЕ падає: його доля — справа його предиката, і
     * заслін не сміє її мовчки змінити.
     */
    fun isFuture(body: String): Boolean {
        if (body.length < FUTURE_SAMPLE.length || !body.startsWith(MARK) || body[6] != ':') return false
        for (i in 3..5) if (body[i] !in 'A'..'Z') return false
        return body.substring(0, FUTURE_SAMPLE.length) !in KNOWN
    }

    /**
     * Рід, яким тіло себе оголошує, або null — це слово людини.
     *
     * Кожна гілка питає той обʼєкт, що кадр складає, тож і терпимість тут його:
     * місце читається з обтятого рядка, бо його [PeerPayload.decodePlace] так і
     * читає, а маршрут — ні, бо [PeerLive.decodeRoute] так і не читає.
     */
    fun kindOf(body: String): Kind? = when {
        PeerLive.isRoute(body) -> Kind.ROUTE
        PeerLive.isFix(body) -> Kind.FIX
        PeerLive.isStop(body) -> Kind.STOP
        PeerPayload.isPlaceFrame(body) -> Kind.PLACE
        PeerFile.isChunk(body) -> Kind.CHUNK
        PeerGroup.isGroup(body) -> Kind.GROUP
        PeerGroup.isSystem(body) -> Kind.SYSTEM
        PeerGroup.isChannelLine(body) -> Kind.CHANNEL
        PeerCard.isCard(body) -> Kind.CARD
        PeerCall.isCall(body) -> Kind.CALL_SIGNAL
        PeerMark.isReaction(body) -> Kind.REACTION
        PeerMark.isVote(body) -> Kind.VOTE
        PeerMark.isCardState(body) -> Kind.CARD_STATE
        PeerMark.isPin(body) -> Kind.PIN
        PeerHistory.isPack(body) -> Kind.HIST
        PeerProfile.isProfile(body) -> Kind.PROFILE
        PeerBanner.isBanner(body) -> Kind.BANNER
        PeerBanner.isPull(body) -> Kind.BANNER_PULL
        PeerWhere.isWhere(body) -> Kind.WHERE
        PeerRevise.isEdit(body) -> Kind.EDIT
        PeerRevise.isUnsend(body) -> Kind.UNSEND
        PeerRead.isRead(body) -> Kind.READ
        PeerTyping.isTyping(body) -> Kind.TYPING
        PeerIntent.isIntent(body) -> Kind.INTENT
        PeerIntent.isAnswer(body) -> Kind.ANSWER
        PeerIntent.isGateway(body) -> Kind.GATEWAY
        RelayFs.isBundle(body) -> Kind.PREKEYS
        PeerDisappear.isTimer(body) -> Kind.TTL
        PeerPreview.isPreview(body) -> Kind.PREVIEW
        MomentWire.isMoment(body) -> Kind.MOMENT
        PeerContact.isContact(body) -> Kind.CONTACT
        PeerGroupJoin.isJoin(body) -> Kind.GROUP_JOIN
        StaffWire.isStaff(body) -> Kind.STAFF
        isFuture(body) -> Kind.FUTURE
        else -> null
    }

    fun isServiceFrame(body: String): Boolean = kindOf(body) != null

    /**
     * Рід, який набране вдає, або null — його можна відправляти.
     *
     * Судить ОБТЯТИЙ рядок, і це не суворість заради суворості: композер, поле
     * назви й відповідь зі шторки обтинають набране перед відправкою самі, тож
     * пробіл попереду не рятує рядок від того, щоб поїхати кадром.
     */
    fun refuseTyped(typed: String): Kind? = kindOf(typed.trim())

    /**
     * Що сталось із набраним рядком.
     *
     * Порожній [Sent.messages] — власного ключа ще немає або групу не зібрати:
     * це «ще ні», а не відмова, і поверхня тримає набране далі, як тримала.
     * Відмова ж названа родом, бо людині кажуть причину, а не «не вийшло».
     */
    sealed interface Typed {
        data class Sent(val messages: List<PeerMessage>) : Typed

        data class Refused(val kind: Kind) : Typed
    }
}
