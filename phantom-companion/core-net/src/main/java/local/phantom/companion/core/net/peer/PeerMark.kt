package local.phantom.companion.core.net.peer

/**
 * Позначка, яку одна людина кладе на ОДИН чужий рядок — і може зняти.
 *
 * Три роди з однією механікою, і саме тому вони тут разом, а не в трьох файлах:
 * серце в усіх одне — «хто, на що, увімкнув чи зняв». Різні в них лише назва
 * значення (смайл, варіант опитування, стан людини на картці зустрічі) і
 * правило згортання.
 *
 * Навіщо взагалі дріт. До 13.09 обидва жили ЛИШЕ в памʼяті вкладки:
 * `reactionState.ts` — чистий перетворювач масиву без жодного мережевого
 * виклику, `messengerStore.votePoll` — те саме над `pollData`. Співрозмовник
 * не бачив ні смайла, ні голосу НІКОЛИ, а на самому апараті вони зникали з
 * першим же оновленням стрічки: `loadMessagesForChat` перечитує розмову з
 * мосту цілком і заступає нею місцевий масив, а кличе його `__phantomMeshChanged`
 * на кожну зміну журналу. Тобто смайл жив до наступного листа. Це не «не
 * синхронізується» — це намальована спроможність, якої немає.
 *
 * Чому позначка НЕ рядок розмови ([PeerFrames.Kind.reachesConversation] = false):
 * людина поставила смайл, а не написала листа. Рядок «❤️» у стрічці, плашка в
 * шторці й «1 чекає» у смузі — це три різні неправди про одну дію. Тому кадр
 * їде журналом (щоб пережити перезапуск і чергу), але стрічкою його не видно:
 * його згортають у поля того рядка, на який він указує.
 *
 * Чому не окреме поле в [PeerMessage]: поле треба було б підписати й довезти
 * разом із листом, а позначку кладе ІНША людина й пізніше. Кадр же успадковує
 * від дороги `send → seal → envelope → open` підпис, шифрування, чергу й
 * повтори — і додає рівно нуль нових слухачів.
 */
object PeerMark {

    /** Смайл під чужим рядком. */
    internal const val REACT = "PH-REA:"

    /** Голос за варіант опитування. */
    internal const val VOTE = "PH-VOT:"

    /**
     * Стан людини на картці зустрічі чи рахунку (`docs/scene/MEETUP_CONTRACT.md`
     * §2): `rsvp:yes`, `eta:<хвилини Unix>`, `dish:<id страви>`, `keep:1`,
     * `here:1`. Сенс значень знає лише веб; дріт везе їх так само німо, як
     * смайл, і так само без правила «один вибір» — людина їла дві страви, і
     * обидві її. Зміну «іду» на «не йду» веб робить двома кадрами: зняв старе,
     * поставив нове.
     *
     * ЗМІШАНІ ВЕРСІЇ. Збірки 15 і 17 цього тегу не знають і показали б кадр
     * бульбашкою з сирим дротом, непрочитаним і плашкою (`PeerFramesFutureTest`
     * тримає це замороженим реєстром збірки 15). Версію співрозмовника дріт НЕ
     * несе, тож міст її не знає й не може відсіяти: кадр їде кожному учаснику з
     * ключем, і учасник на збірці 17 побачить його сирим, з сповіщенням на кожен
     * дотик. Це відома ціна; закрити її можна лише ознакою збірки в профілі
     * контакту. Від збірки 18 будь-який незнайомий тег такої форми ховає
     * [PeerFrames.Kind.FUTURE].
     */
    internal const val CARD_STATE_TAG = "PH-CST:"

    /**
     * Закріплення рядка в розмові удвох (P05). Значення — завжди [PIN_VALUE];
     * останній дотик будь-кого з двох вирішує ([pinned]). Від збірки 18 старий
     * апарат ховає незнайомий тег як [PeerFrames.Kind.FUTURE].
     */
    internal const val PIN_TAG = "PH-PIN:"

    const val PIN_VALUE = "1"

    private const val SEP = ';'
    private const val ON = "+"
    private const val OFF = "-"

    /**
     * Стеля сирого значення в байтах.
     *
     * Смайл — це до 4 байтів, послідовність із модифікаторами (прапор, колір
     * шкіри, ZWJ-сімʼя) — десятки. Ідентифікатор варіанта опитування, який
     * складає [PeerFrames]-сумісний кодувальник карток, — `opt_0`…`opt_N`.
     * 64 байти лишають запас обом і при цьому відмовляють ДО сокета: кадр,
     * зрізаний на дроті, не відмовляється — він мовчки не доїжджає.
     */
    const val MAX_VALUE_BYTES = 64

    enum class What(val mark: String) {
        REACTION(REACT),
        VOTE(PeerMark.VOTE),
        CARD_STATE(CARD_STATE_TAG),
        PIN(PIN_TAG),
    }

    /**
     * [targetId] — id рядка, на який указує позначка; [on] — поставили чи зняли.
     *
     * [value] возиться в base64 не з обережності, а тому, що сам роздільник
     * кадру (`;`) і перевід рядка законні всередині смайлової послідовності
     * рівно так само, як і в SDP: [PeerCall] возить тіло base64 з тієї самої
     * причини.
     */
    data class Mark(
        val what: What,
        val targetId: String,
        val on: Boolean,
        val value: String,
    )

    // ── складання ────────────────────────────────────────────────────────

    /**
     * Null — коли позначку нема на що класти, нема чим або вона не влазить.
     * Кожна з трьох відмов видима викликачеві ДО сокета.
     */
    fun encode(mark: Mark): String? {
        if (!isRef(mark.targetId)) return null
        val raw = mark.value.toByteArray(Charsets.UTF_8)
        if (raw.isEmpty() || raw.size > MAX_VALUE_BYTES) return null
        val body = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        return mark.what.mark + mark.targetId + SEP + (if (mark.on) ON else OFF) + SEP + body
    }

    // ── читання ──────────────────────────────────────────────────────────

    fun isReaction(body: String): Boolean = body.startsWith(REACT)

    fun isVote(body: String): Boolean = body.startsWith(VOTE)

    fun isCardState(body: String): Boolean = body.startsWith(CARD_STATE_TAG)

    fun isPin(body: String): Boolean = body.startsWith(PIN_TAG)

    /**
     * Будь-яка з трьох. Тим, хто питає «це позначка?», а не «яка саме»: на цьому
     * питанні стоять заслони стрічки й смуги «чекає» ([PeerOutbox.isSpoken],
     * [PeerOutbox.waiting]), тож новий рід позначки потрапляє під них сам.
     */
    fun isMark(body: String): Boolean = isReaction(body) || isVote(body) || isCardState(body) || isPin(body)

    /**
     * Тіло розбирається САМЕ ТУТ, а не в того, хто його показує: base64 з дроту
     * може бути чим завгодно, і місце, де він стає рядком, має бути одне.
     */
    fun decode(body: String): Mark? {
        val what = What.entries.firstOrNull { body.startsWith(it.mark) } ?: return null
        val parts = body.removePrefix(what.mark).split(SEP)
        if (parts.size != 3) return null
        val targetId = parts[0].takeIf(::isRef) ?: return null
        val on = when (parts[1]) {
            ON -> true
            OFF -> false
            else -> return null
        }
        val raw = runCatching {
            java.util.Base64.getUrlDecoder().decode(parts[2])
        }.getOrNull() ?: return null
        if (raw.isEmpty() || raw.size > MAX_VALUE_BYTES) return null
        val value = String(raw, Charsets.UTF_8).takeIf { it.isNotEmpty() } ?: return null
        return Mark(what, targetId, on, value)
    }

    // ── згортання ────────────────────────────────────────────────────────

    /**
     * Хто що поставив на [targetId] — з журналу, за часом.
     *
     * Порядок вирішує все: людина може поставити смайл, зняти й поставити
     * знову, а кадри доїжджають чергою й не обовʼязково в тому ж порядку, у
     * якому їх поклали. Сортування за [PeerMessage.sentAtMs] — годинником
     * АВТОРА позначки — і є тим, що робить «останнє слово автора» однаковим на
     * обох апаратах. Свого стану тут немає: та сама вхідна купа завжди дає той
     * самий вихід, і перечитати її можна будь-коли.
     *
     * [single] — правило опитування з одним вибором: новий голос автора знімає
     * його ж попередній. Без нього людина, що передумала, порахувалась би
     * двічі, і сума голосів перевищила б кількість тих, хто голосував.
     */
    fun fold(
        log: List<PeerMessage>,
        what: What,
        targetId: String,
        single: Boolean = false,
    ): Map<String, List<String>> = foldAll(log, what, single)[targetId].orEmpty()

    /**
     * Те саме, але за ОДИН прохід журналу — на всі рядки одразу.
     *
     * Існує з причини, яку видно лише на місці вжитку: міст малює стрічку
     * розмови, і [fold] на кожен рядок перечитував би весь журнал ще раз. На
     * пів тисячі листів це чверть мільйона спроб розбору на одне відкриття
     * розмови — рівно тієї форми повільність, яку потім шукають профайлером.
     *
     * Згортач при цьому лишається ОДИН: [fold] тепер питає саме тут і бере
     * свій рядок. Друга, «швидка» копія правила розійшлася б із першою на
     * першому ж уточненні — а уточнення тут уже є ([single]).
     */
    fun foldAll(
        log: List<PeerMessage>,
        what: What,
        single: Boolean = false,
    ): Map<String, Map<String, List<String>>> {
        val casts = log.asSequence()
            .filter { isMark(it.body) }
            .mapNotNull { m -> decode(m.body)?.let { m to it } }
            .filter { (_, mark) -> mark.what == what }
            .sortedWith(compareBy({ it.first.sentAtMs }, { it.first.id }))
        val byTarget = LinkedHashMap<String, LinkedHashMap<String, MutableList<String>>>()
        for ((message, mark) in casts) {
            val author = message.fromId
            val byValue = byTarget.getOrPut(mark.targetId) { LinkedHashMap() }
            if (mark.on) {
                if (single) byValue.values.forEach { it.remove(author) }
                val holders = byValue.getOrPut(mark.value) { mutableListOf() }
                if (author !in holders) holders += author
            } else {
                byValue[mark.value]?.remove(author)
            }
        }
        // Значення, яке лишилось без жодного автора, зникає цілком: смайл із
        // нулем — це рамка, під якою нікого немає. Рядок, у якого не лишилось
        // жодного значення, зникає так само.
        return byTarget
            .mapValues { (_, byValue) ->
                byValue.filterValues { it.isNotEmpty() }.mapValues { (_, v) -> v.toList() }
            }
            .filterValues { it.isNotEmpty() }
    }

    /**
     * Що закріплено в розмові [a] з [b]: лише кадри між ними двома, за часом
     * автора; останнє слово будь-кого з двох вирішує — як у гілці
     * ([PeerGroup.Act.Pin]), лише без прав: у розмові удвох обоє рівні.
     */
    fun pinned(log: List<PeerMessage>, a: String, b: String): Set<String> {
        val state = LinkedHashMap<String, Boolean>()
        log.asSequence()
            .filter { isPin(it.body) && ((it.fromId == a && it.toId == b) || (it.fromId == b && it.toId == a)) }
            .mapNotNull { m -> decode(m.body)?.let { m to it } }
            .sortedWith(compareBy({ it.first.sentAtMs }, { it.first.id }))
            .forEach { (_, mark) -> state[mark.targetId] = mark.on }
        return state.filterValues { it }.keys
    }

    /**
     * КАДР, який поставив цю позначку — щоб спитати його про ДОРОГУ.
     *
     * Згортач ([foldAll]) навмисно не дивиться на [PeerMessage.state]: він
     * відповідає на питання «хто що тримає», і відповідь мусить бути однаковою
     * на обох апаратах, а стан черги — річ місцева. Через це позначка, яка
     * лежить у черзі, згорталась у рядок точно так само, як доставлена: смайл
     * зʼявлявся під рядком і нічим не відрізнявся від того, що вже в людини.
     * Власник називає це «начебто поставилось».
     *
     * Тому тут — ДРУГЕ питання, а не друге правило згортання: «яким саме кадром
     * я це поставив». Питати його має сенс лише про власну позначку і лише
     * тоді, коли [foldAll] уже сказав, що вона тримається: тоді найсвіжіший
     * кадр цього автора з `on = true` і є той, що вирішив. Якщо найсвіжіший
     * кадр автора знімав позначку, згортач автора не назве — і питання не
     * виникне.
     *
     * Порядок той самий, що у [foldAll] — `sentAtMs`, потім `id`: інакше два
     * місця читали б ту саму купу по-різному.
     */
    fun latestCast(
        log: List<PeerMessage>,
        what: What,
        targetId: String,
        value: String,
        authorId: String,
    ): PeerMessage? = log.asSequence()
        .filter { it.fromId == authorId && isMark(it.body) }
        .mapNotNull { m -> decode(m.body)?.let { m to it } }
        .filter { (_, mark) ->
            mark.what == what && mark.on && mark.targetId == targetId && mark.value == value
        }
        .sortedWith(compareBy({ it.first.sentAtMs }, { it.first.id }))
        .lastOrNull()
        ?.first

    /**
     * Чи цей автор уже поставив це значення — тобто чи наступний дотик знімає.
     *
     * Рішення ухвалює ТОЙ САМИЙ згортач, яким малюється результат: друге,
     * простіше правило («подивись у поле веба») розійшлося б із ним рівно тоді,
     * коли поле застаріло на один кадр.
     */
    fun holds(
        log: List<PeerMessage>,
        what: What,
        targetId: String,
        value: String,
        authorId: String,
    ): Boolean = fold(log, what, targetId)[value]?.contains(authorId) == true

    /**
     * Id рядка — ЧУЖИЙ: його мінить той, хто складав лист ([PeerMesh.send] дає
     * UUID, міст агенції — `msg_…`), тож тут не звірка з власним алфавітом, а
     * межа набору й довжини: усе, крім роздільника й того, що зламало б кадр.
     */
    private fun isRef(value: String): Boolean =
        value.length in 1..64 &&
            value.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' || it == '-' || it == '_' }
}
