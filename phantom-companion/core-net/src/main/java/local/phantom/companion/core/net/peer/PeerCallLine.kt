package local.phantom.companion.core.net.peer

/**
 * Що телефон знає про живий дзвінок — і рівно стільки, скільки йому треба.
 *
 * **Це не друга машина станів WebRTC.** Вона вже є, вона повна й вона в
 * зібраному веб-месенджері: там `RTCPeerConnection`, обмін ICE, сторож
 * затримки, відступ на резервну доріжку й екран із «взяти/відхилити». Друга
 * така в котліні була б двома правдами про один дзвінок, і розійшлися б вони
 * на першому ж перезбиранні бандла.
 *
 * Телефон знає інше, і цього веб знати не може: WebView живе, лише поки
 * месенджер на екрані. Дзвінок приходить тоді, коли застосунок закритий або на
 * мапі, — і тоді ПІДІЙМАТИ АПАРАТ нема кому. Ось що тут і живе: хто в кімнаті,
 * у якому стані кожна нога, і чи вільна лінія взагалі. Плюс два рішення, яких
 * веб теж не зробить без екрана: відповісти «зайнято» і згаснути, коли ніхто
 * не підійшов.
 *
 * ## Кімната, а не лінія
 *
 * До 16.09 тут стояв `sealed interface Line` з ОДНИМ активним станом, і в
 * ньому одне поле `peerId`. Наслідок був не в смузі пропускання, а в коді:
 * `apply` звіряла `peerOf(line) == fromId`, тож ДРУГИЙ учасник тієї самої
 * розмови отримував «зайнято» тим самим рядком, яким його отримував чужий
 * номер. Конференція була неможлива за побудовою, і жодне збільшення каналу
 * цього не лікувало.
 *
 * Тепер правда — [Room]: ОДИН номер дзвінка і СПИСОК ніг, по нозі на кожного
 * співрозмовника. Кадр приставляється до НОГИ, а не до кімнати: той, хто
 * поклав слухавку, забирає свою ногу й лишає решту розмови живою. `Line`
 * лишився, але він більше не правда, а ПРОЄКЦІЯ ([face]) — рівно те одне, що
 * здатна показати шторка сповіщень, якій нема куди покласти список.
 *
 * ## Чому стеля в коді, а не «безмежно»
 *
 * Повна сітка коштує (N-1) зʼєднань на КОЖНОМУ апараті: кожен шле кожному.
 * [MAX_LEGS] — не оздоба й не обережність: він порахований від ЗАМІРЯНОЇ ціни
 * однієї ноги НА ДРОТІ (83,4 кбіт/с на повній сходинці, прогін 17.09) і одного
 * названого вголос припущення про аплінк. Обидва — в `docs/MEETS_SCALE.md`.
 * Кімната, яка пускає більше, ніж витримує канал, не стає більшою — вона стає
 * тихою.
 */
object PeerCallLine {

    /**
     * Стеля ОДНОЧАСНИХ ніг у кімнаті на цьому апараті. Вісім ніг — девʼять
     * людей у розмові.
     *
     * ## Звідки саме вісім
     *
     * `scripts/measure-mesh-legs.mjs` підіймає N справжніх `RTCPeerConnection`
     * із живим кодеком Opus і міряє ДВІ половини ціни ноги. Прогін 17.09
     * (стенд linux x64, 12 ядер, 32 ноги) дав таке:
     *
     * * **дріт**: 83,4 кбіт/с на ногу на повній сходинці, 27,5 на економній,
     *   14,2 на вузькій — разом із міткою SRTP, заголовком RTP і 28 Б
     *   IPv4/UDP на пакет. Тобто «64 кбіт/с» із драбини аплінк НЕ платить;
     * * **процесор**: стіною НЕ став. 64 кодери й 64 декодери зʼїли 1,36 ядра,
     *   і жоден пакет не запізнився.
     *
     * Тому вісім — це НЕ те число, на якому щось зламалось: до тридцяти двох
     * не зламалось нічого. Тут раніше стояло, що на цьому N «кадри перестають
     * виходити вчасно», — вимір це спростував, і рядок прибрано: коментар,
     * який суперечить приладу, згниває в брехню.
     *
     * Вісім — це те, що девʼять учасників коштують 0,67 Мбіт/с ВГОРУ з
     * кожного апарата на повній якості (плюс стільки ж вниз), і драбині є
     * куди спускатись: 0,22 і 0,11.
     *
     * Що тут ПРИПУЩЕННЯ, а не вимір: що мобільний аплінк у полі витримує
     * 0,7 Мбіт/с. Ні телефон власника, ні поле не заміряні — заміряна петля
     * 127.0.0.1 на стенді. Стелю МОЖНА підняти, і код цьому не заважає, але
     * спершу треба заміряти апарат. Таблиця й те, що дав би SFU, —
     * у `docs/MEETS_SCALE.md`.
     *
     * Це стеля РОЗМОВИ, і вона свідомо не дорівнює [PeerGroup.MAX_MEMBERS] —
     * стелі СКЛАДУ гілки. Написати в групу можна тридцяти двом; говорити
     * одночасно, тримаючи (N-1) живих ніг на кожному апараті, — ні, і два
     * різні числа тут чесніші за одне красиве.
     */
    const val MAX_LEGS = 8

    /**
     * Від скількох ніг людині вже треба сказати, що кімната близько до стелі.
     *
     * Попередження за крок до межі, а не в мить відмови: людина, яка дізналась
     * про стелю рівно тоді, коли девʼятий не зміг увійти, дізналась запізно.
     */
    const val CROWDED_LEGS = 6

    /** Стан ОДНІЄЇ ноги. Кімната їх тримає стільки, скільки співрозмовників. */
    enum class Foot {
        /** Ми набрали цього; він ще не відповів. */
        DIALING,

        /** Він набрав нас; слухавку ще не взяли. */
        RINGING,

        /** Ця нога в розмові. */
        TALKING,

        /** Ноги більше немає. [Leg.why] каже, чому саме. */
        GONE,
    }

    /**
     * Одна нога кімнати — один співрозмовник.
     *
     * [sinceMs] — коли нога стала такою, якою є: від нього рахує [expire], і
     * рахує ЛИШЕ для [Foot.DIALING] та [Foot.RINGING]. Розмова стелі часу не
     * має, і це доведено тестом, а не обіцяно коментарем.
     */
    data class Leg(
        val peerId: String,
        val foot: Foot,
        val sinceMs: Long,
        /** Слово рушія, не наше: «declined», «busy», «no-answer». */
        val why: String? = null,
    )

    /**
     * Кімната: один номер дзвінка, багато ніг.
     *
     * [groupId] не null — розмова заведена в гілці, і саме її назву має
     * показати шторка замість обрізаного хеша однієї людини. Null — розмова
     * двох, і тоді імʼя бере єдина нога.
     *
     * Кімната на апараті ОДНА. Це межа, і вона названа: другий номер дзвінка,
     * поки цей живий, отримує «зайнято» — так само, як його отримував би
     * другий вхідний за старої лінії. Людина веде одну розмову за раз; це не
     * технічне обмеження сітки, а те, як розмовляють люди.
     */
    data class Room(
        val callId: String,
        val groupId: String?,
        val legs: List<Leg>,
    )

    // ── читання кімнати ──────────────────────────────────────────────────

    fun isLive(leg: Leg): Boolean = leg.foot != Foot.GONE

    fun legsOf(room: Room?): List<Leg> = room?.legs.orEmpty()

    fun liveLegs(room: Room?): List<Leg> = legsOf(room).filter(::isLive)

    /** Кімната ще щось тримає — на відміну від тієї, де всі ноги забрали. */
    fun isLive(room: Room?): Boolean = liveLegs(room).isNotEmpty()

    fun legOf(room: Room?, peerId: String): Leg? =
        room?.legs?.firstOrNull { it.peerId == peerId }

    /** Скільки ніг ще можна взяти, перш ніж упертись у [MAX_LEGS]. */
    fun roomLeft(room: Room?): Int = (MAX_LEGS - liveLegs(room).size).coerceAtLeast(0)

    /** Кімната підійшла до стелі впритул — людині час це прочитати. */
    fun isCrowded(room: Room?): Boolean = liveLegs(room).size >= CROWDED_LEGS

    // ── проєкція для шторки ──────────────────────────────────────────────

    /**
     * Чим лінія є просто зараз — ОДНИМ станом.
     *
     * Це НЕ правда про дзвінок: правда — [Room] зі списком ніг. Це те єдине,
     * що вміє показати шторка сповіщень і плитка на замкненому екрані, куди
     * список не влізе ні фізично, ні за змістом. Хто хоче знати, хто саме в
     * кімнаті, дивиться в [Room], а не сюди.
     *
     * [Over] — не «нічого»: плашку треба погасити, а того, хто дзвонив,
     * лишити в спокої. Хто його показав, той його й гасить у [Idle].
     */
    sealed interface Line {
        data object Idle : Line

        /** Ми набрали; на тому кінці ще не взяли. */
        data class Dialing(val callId: String, val peerId: String, val sinceMs: Long) : Line

        /** Дзвонять нам, і апарат має задзвонити. */
        data class Ringing(val callId: String, val peerId: String, val sinceMs: Long) : Line

        /** Хоч одна нога в розмові. Далі все веде рушій у вебі. */
        data class Talking(val callId: String, val peerId: String, val sinceMs: Long) : Line

        /** Кімнати не стало. [why] — слово рушія, не наше: «declined», «busy». */
        data class Over(val callId: String, val peerId: String, val why: String?) : Line
    }

    /**
     * Кімната → один стан для шторки.
     *
     * Порядок ваги не випадковий: TALKING > DIALING > RINGING. Розмова, яка
     * вже йде, важить більше за ногу, що тільки дзвонить, — інакше апарат
     * дзвенів би людині, яка САМЕ ЗАРАЗ говорить, щойно в кімнату заходить
     * девʼятий. А наше «набираю» важить більше за зустрічну пропозицію
     * учасника: коли ми набрали гілку, кадр від її члена — це рукостискання
     * сітки, а не другий вхідний дзвінок, і дзвеніти на нього не можна.
     */
    fun face(room: Room?): Line {
        if (room == null) return Line.Idle
        val live = room.legs.filter(::isLive)
        val who = { leg: Leg -> room.groupId ?: leg.peerId }
        live.firstOrNull { it.foot == Foot.TALKING }?.let {
            return Line.Talking(room.callId, who(it), it.sinceMs)
        }
        live.firstOrNull { it.foot == Foot.DIALING }?.let {
            return Line.Dialing(room.callId, who(it), it.sinceMs)
        }
        live.firstOrNull { it.foot == Foot.RINGING }?.let {
            return Line.Ringing(room.callId, who(it), it.sinceMs)
        }
        // Усі ноги забрали. Причину бере остання, яка відпала: саме її людина
        // й побачить у плашці — «відхилив», «не взяв».
        val last = room.legs.maxByOrNull { it.sinceMs }
        return Line.Over(room.callId, room.groupId ?: last?.peerId.orEmpty(), last?.why)
    }

    fun peerOf(line: Line): String? = when (line) {
        is Line.Dialing -> line.peerId
        is Line.Ringing -> line.peerId
        is Line.Talking -> line.peerId
        is Line.Over -> line.peerId
        Line.Idle -> null
    }

    fun callIdOf(line: Line): String? = when (line) {
        is Line.Dialing -> line.callId
        is Line.Ringing -> line.callId
        is Line.Talking -> line.callId
        is Line.Over -> line.callId
        Line.Idle -> null
    }

    /** Лінія зайнята дзвінком або його спробою — на відміну від [Line.Over]. */
    fun isLive(line: Line): Boolean =
        line is Line.Dialing || line is Line.Ringing || line is Line.Talking

    // ── вирок ────────────────────────────────────────────────────────────

    /**
     * Кадр, який треба надіслати назад, — описом, а не готовим JSON.
     *
     * Тіло складає той бік, у якого є `org.json`: сигнал мусить бути СЛОВО В
     * СЛОВО таким, як його чекає рушій у вебі, і збирати його тут, де формат
     * ніхто не перевірить, означало б третю правду про той самий кадр.
     */
    data class Reply(val callId: String, val what: PeerCall.Signalling, val reason: String)

    /** Кому саме летить [Reply]. Кімната з N ніг кладе N штук за один тик. */
    data class Out(val toPeerId: String, val reply: Reply)

    /**
     * Що сталось із кадром.
     *
     * [toWeb] — тіло, яке треба віддати рушієві ЯК Є. Null — коли кадр до
     * рушія не має дійти взагалі: сигнал на чужий або мертвий номер.
     *
     * [out] — список, а не одна відповідь: [expire] здатна за один тик
     * погасити кілька ніг одразу, і кожна з них мусить почути «не взяли»
     * окремо. Одна відповідь на всіх мовчки губила б решту.
     */
    data class Verdict(
        val room: Room?,
        val toWeb: String? = null,
        val out: List<Out> = emptyList(),
        /** Правда лише коли кадр змінив кімнату — поверхням є що перемалювати. */
        val changed: Boolean = false,
    )

    // ── що приїхало з сітки ──────────────────────────────────────────────

    /**
     * Кадр від [fromId] прикладається до кімнати.
     *
     * Кадр, чий номер дзвінка не збігається з живою кімнатою, до рушія НЕ
     * доходить, і це не строгість заради строгості: інакше будь-хто з реєстру
     * клав би чужу розмову одним «hangup» з вигаданим номером. Але звірка
     * тепер за НОМЕРОМ КІМНАТИ, а не за парою (номер, співрозмовник) — саме
     * та пара й робила конференцію неможливою.
     */
    fun apply(room: Room?, fromId: String, body: String, nowMs: Long): Verdict {
        val signal = PeerCall.decodeSignal(body) ?: return Verdict(room)
        return when (signal.what) {
            PeerCall.Signalling.OFFER -> onOffer(room, fromId, signal, nowMs)
            PeerCall.Signalling.ANSWER -> onAnswer(room, fromId, signal, nowMs)
            PeerCall.Signalling.HANGUP -> onHangUp(room, fromId, signal, nowMs)
            // Кандидат і резервна доріжка нічого не вирішують — вони потребують
            // живої НОГИ й їдуть до рушія, який один і вміє з ними щось зробити.
            PeerCall.Signalling.ICE, PeerCall.Signalling.RADIO ->
                if (room?.callId == signal.callId && legOf(room, fromId)?.let(::isLive) == true) {
                    Verdict(room, toWeb = signal.payload)
                } else {
                    Verdict(room)
                }
        }
    }

    /**
     * Пропозиція. Три різні події під одним родом, і плутати їх не можна:
     * новий дзвінок, НОВА НОГА в живій кімнаті, перезапуск ICE наявної ноги.
     */
    private fun onOffer(
        room: Room?,
        fromId: String,
        signal: PeerCall.Signal,
        nowMs: Long,
    ): Verdict {
        if (room == null || !isLive(room)) {
            return Verdict(
                room = Room(
                    callId = signal.callId,
                    groupId = null,
                    legs = listOf(Leg(fromId, Foot.RINGING, nowMs)),
                ),
                toWeb = signal.payload,
                changed = true,
            )
        }
        // Інший номер дзвінка — це справді ДРУГА розмова, і «зайнято» тут
        // правда: людина веде одну за раз. «Зайнято» — це ВІДПОВІДЬ, а не
        // тиша: інакше той бік дзвонить у порожнечу, доки не здасться сам.
        if (room.callId != signal.callId) {
            return Verdict(
                room,
                out = listOf(Out(fromId, Reply(signal.callId, PeerCall.Signalling.HANGUP, BUSY))),
            )
        }
        val had = legOf(room, fromId)
        if (had != null && isLive(had)) {
            // Повторна пропозиція тієї самої ноги — перезапуск ICE або нове
            // ptime: рушій сам розбереться, кімната лишається як була.
            return Verdict(room, toWeb = signal.payload)
        }
        if (liveLegs(room).size >= MAX_LEGS) {
            // Кімната повна. Це НЕ «зайнято»: там нікого не пускає чужа
            // розмова, а тут — заміряна стеля апарата, і слово має бути інше,
            // бо інакше людина шукатиме неіснуючий другий дзвінок.
            return Verdict(
                room,
                out = listOf(Out(fromId, Reply(signal.callId, PeerCall.Signalling.HANGUP, FULL))),
            )
        }
        // ОСЬ ВОНА, КОНФЕРЕНЦІЯ. Нова нога в живій кімнаті: той самий номер
        // дзвінка, інший співрозмовник. Старий код відповідав сюди «зайнято».
        val legs = room.legs.filterNot { it.peerId == fromId } + Leg(fromId, Foot.RINGING, nowMs)
        return Verdict(room.copy(legs = legs), toWeb = signal.payload, changed = true)
    }

    private fun onAnswer(
        room: Room?,
        fromId: String,
        signal: PeerCall.Signal,
        nowMs: Long,
    ): Verdict {
        if (room == null || room.callId != signal.callId) return Verdict(room)
        val leg = legOf(room, fromId) ?: return Verdict(room)
        if (!isLive(leg)) return Verdict(room)
        // Слухавку бере той, кому ми набрали. Відповідь на ногу, яка нам
        // дзвонить, — це переговори поверх тієї ж ноги, і рушій їх чекає.
        if (leg.foot != Foot.DIALING) return Verdict(room, toWeb = signal.payload)
        return Verdict(
            room = room.copy(
                legs = room.legs.map {
                    if (it.peerId == fromId) it.copy(foot = Foot.TALKING, sinceMs = nowMs) else it
                },
            ),
            toWeb = signal.payload,
            changed = true,
        )
    }

    /**
     * Хтось поклав слухавку. Забирається ЙОГО нога — решта розмови живе далі.
     *
     * Це і є «виходити не роняючи решти»: до 16.09 цей кадр клав усю лінію, бо
     * лінія була одна на всіх, і вихід одного учасника вимикав конференцію
     * всім, хто лишався.
     */
    private fun onHangUp(
        room: Room?,
        fromId: String,
        signal: PeerCall.Signal,
        nowMs: Long,
    ): Verdict {
        if (room == null || room.callId != signal.callId) return Verdict(room)
        val leg = legOf(room, fromId) ?: return Verdict(room)
        if (!isLive(leg)) return Verdict(room)
        val legs = room.legs.map {
            if (it.peerId == fromId) it.copy(foot = Foot.GONE, sinceMs = nowMs, why = null) else it
        }
        return Verdict(room.copy(legs = legs), toWeb = signal.payload, changed = true)
    }

    // ── те, що робить цей бік ────────────────────────────────────────────

    /**
     * Наш веб щойно надіслав кадр одній нозі. Кімната йде слідом за ним, а не
     * веде його: рішення там, і дублювати їх тут означало б розійтись із ним
     * у стані.
     *
     * [groupId] не null — кімнату заведено в гілці, і шторка покаже її назву.
     * Він стосується КІМНАТИ, а не ноги: нога завжди людина, бо зʼєднання
     * буває лише між двома апаратами.
     */
    fun noteSent(
        room: Room?,
        peerId: String,
        callId: String,
        what: PeerCall.Signalling,
        nowMs: Long,
        groupId: String? = null,
    ): Verdict = when (what) {
        PeerCall.Signalling.OFFER -> onSentOffer(room, peerId, callId, groupId, nowMs)
        // Ми взяли слухавку на цій нозі: далі говорить рушій, а плашка мусить
        // перестати дзвеніти.
        PeerCall.Signalling.ANSWER -> setFoot(room, peerId, callId, Foot.TALKING, nowMs)
        PeerCall.Signalling.HANGUP -> setFoot(room, peerId, callId, Foot.GONE, nowMs)
        PeerCall.Signalling.ICE, PeerCall.Signalling.RADIO -> Verdict(room)
    }

    private fun onSentOffer(
        room: Room?,
        peerId: String,
        callId: String,
        groupId: String?,
        nowMs: Long,
    ): Verdict {
        if (room == null || !isLive(room) || room.callId != callId) {
            return Verdict(
                Room(callId, groupId, listOf(Leg(peerId, Foot.DIALING, nowMs))),
                changed = true,
            )
        }
        val had = legOf(room, peerId)
        // Жива нога, якій ми знову шлемо опис, — це перезапуск ICE, а не
        // другий набір: стан лишається, час теж (інакше [expire] щоразу
        // відсувала б хвилину й «не взяли» не наставало б ніколи).
        if (had != null && isLive(had)) return Verdict(room)
        val legs = room.legs.filterNot { it.peerId == peerId } + Leg(peerId, Foot.DIALING, nowMs)
        return Verdict(
            room.copy(groupId = room.groupId ?: groupId, legs = legs),
            changed = true,
        )
    }

    private fun setFoot(
        room: Room?,
        peerId: String,
        callId: String,
        foot: Foot,
        nowMs: Long,
    ): Verdict {
        if (room == null || room.callId != callId) return Verdict(room)
        val leg = legOf(room, peerId) ?: return Verdict(room)
        if (leg.foot == foot) return Verdict(room)
        return Verdict(
            room.copy(
                legs = room.legs.map {
                    if (it.peerId == peerId) it.copy(foot = foot, sinceMs = nowMs) else it
                },
            ),
            changed = true,
        )
    }

    /**
     * Відмовити, не відкриваючи застосунку.
     *
     * Єдина дія дзвінка, яку телефон робить САМ: «взяти» вимагає мікрофона й
     * рушія, тобто екрана з вебом, а «відхилити» — рівно одного кадру. Без неї
     * єдиним способом не говорити було б дати апарату дзвеніти хвилину.
     *
     * Відхиляються ВСІ ноги, що дзвонять: людина відмовляє ДЗВІНКУ, а не
     * окремому апарату в ньому. Ноги в розмові не чіпаються — але їх тут і не
     * буває: [face] показує «дзвонить» лише коли розмови ще нема.
     */
    fun decline(room: Room?): Verdict? {
        if (room == null) return null
        if (face(room) !is Line.Ringing) return null
        val ringing = room.legs.filter { it.foot == Foot.RINGING }
        if (ringing.isEmpty()) return null
        val legs = room.legs.map {
            if (it.foot == Foot.RINGING) it.copy(foot = Foot.GONE, why = DECLINED) else it
        }
        return Verdict(
            room = room.copy(legs = legs),
            out = ringing.map {
                Out(it.peerId, Reply(room.callId, PeerCall.Signalling.HANGUP, DECLINED))
            },
            changed = true,
        )
    }

    /**
     * Ми виходимо з кімнати. Кожна жива нога отримує «поклав» окремим кадром.
     *
     * Розсилки тут немає навмисно: нога — це зʼєднання з КОНКРЕТНИМ апаратом,
     * і той, кому кадр не доїхав, мусить це побачити окремо від тих, кому
     * доїхав. Одне «так/ні» на всіх сховало б рівно те, що людині треба знати.
     */
    fun leave(room: Room?, why: String = HUNG_UP): Verdict? {
        if (room == null) return null
        val live = liveLegs(room)
        if (live.isEmpty()) return null
        return Verdict(
            room = room.copy(
                legs = room.legs.map { if (isLive(it)) it.copy(foot = Foot.GONE, why = why) else it },
            ),
            out = live.map { Out(it.peerId, Reply(room.callId, PeerCall.Signalling.HANGUP, why)) },
            changed = true,
        )
    }

    /** Забрати ОДНУ ногу, лишивши решту кімнати живою. */
    fun drop(room: Room?, peerId: String, why: String = HUNG_UP): Verdict? {
        if (room == null) return null
        val leg = legOf(room, peerId)?.takeIf(::isLive) ?: return null
        return Verdict(
            room = room.copy(
                legs = room.legs.map {
                    if (it.peerId == peerId) it.copy(foot = Foot.GONE, why = why) else it
                },
            ),
            out = listOf(Out(peerId, Reply(room.callId, PeerCall.Signalling.HANGUP, why))),
            changed = true,
        )
    }

    /**
     * Ніхто не підійшов — на КОНКРЕТНІЙ нозі.
     *
     * Без цього апарат, чий співрозмовник вимкнувся посеред виклику, дзвенів
     * би, доки хтось не натисне. Межа ОДНА на обидва кінці
     * ([PeerCall.RING_LIFE_MS]): інакше «дзвонить» у того, кому набрали, і
     * «набираю» в того, хто набрав, розійшлися б.
     *
     * Гасне НОГА, не кімната: учасник, який не взяв слухавку, не має права
     * покласти розмову тим, хто взяв. Кімната кінчається сама, коли жодної
     * живої ноги в ній не лишилось.
     *
     * [Foot.TALKING] не гасне НІКОЛИ, скільки б часу не минуло: стелі
     * ТРИВАЛОСТІ розмови тут немає, і це перевірено роком уперед, а не
     * переказано з коментаря.
     */
    fun expire(room: Room?, nowMs: Long): Verdict? {
        if (room == null) return null
        val stale = room.legs.filter {
            (it.foot == Foot.DIALING || it.foot == Foot.RINGING) &&
                nowMs - it.sinceMs >= PeerCall.RING_LIFE_MS
        }
        if (stale.isEmpty()) return null
        val dead = stale.map { it.peerId }.toSet()
        return Verdict(
            room = room.copy(
                legs = room.legs.map {
                    if (it.peerId in dead) {
                        it.copy(foot = Foot.GONE, sinceMs = nowMs, why = MISSED)
                    } else {
                        it
                    }
                },
            ),
            out = stale.map { Out(it.peerId, Reply(room.callId, PeerCall.Signalling.HANGUP, MISSED)) },
            changed = true,
        )
    }

    /** Слова рушія, не наші: він шле саме їх у полі `reason`. */
    const val BUSY = "busy"
    const val DECLINED = "declined"
    const val MISSED = "no-answer"
    const val HUNG_UP = "hangup"
    const val CALLS_OFF = "calls-off"

    /**
     * Кімната дійшла до [MAX_LEGS]. Слово СВОЄ, не рушія: такого стану в
     * рушії 1:1 не було, бо не було й кімнати. Рушій його не тлумачить —
     * показує як є, і це чесніше, ніж вдати «зайнято» там, де нікого немає.
     */
    const val FULL = "room-full"
}
