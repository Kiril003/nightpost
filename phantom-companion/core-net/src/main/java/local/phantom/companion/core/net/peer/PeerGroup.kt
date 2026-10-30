package local.phantom.companion.core.net.peer

/**
 * A group is a label on ordinary one-to-one messages, not a place: sending to
 * five people sends five signed messages, so signing, the outbox and retries
 * are the ones already proved, and delivery stays per person. The member list
 * rides in every message, so any single message reconstructs the group on a
 * device that has never heard of it.
 */
object PeerGroup {

    internal const val TAG = "PH-GRP:"
    internal const val SYS = "PH-SYS:"
    private const val SEP = ':'

    /**
     * Скільки людей у гілці. НЕ наслідок формату кадру — перевірено рахунком,
     * не здогадом: [encode] кладе весь склад у заголовок ([Roster] на кожен
     * лист), і при 32 членах (16-символьний hex-відбиток кожного, кома між
     * ними) це ~543 байти. Заголовок разом з іменем (64 графеми [PeerName.MAX]
     * у base64 — ще ~170 байтів) важить менше кілобайта, а
     * [PeerWire.MAX_BODY_BYTES] лишає під тіло листа десятки кілобайтів.
     * Формат витримав би СКЛАД щонайменше на порядок більший, перш ніж
     * зачепити стелю кадру.
     *
     * Отже 32 — рішення про масштаб гілки, а не про розмір рядка. Справжня
     * ціна росте деінде: [fanOut] дублює КОЖЕН лист у стільки копій, скільки
     * членів, — 32-й учасник означає 31 окремо зашифрований запис в черзі на
     * кожне сказане слово, а не довший заголовок.
     */
    const val MAX_MEMBERS = 32

    /**
     * Простір (11-spaces) — гілка з деревом каналів, і її стеля інша: 50.
     * Класична гілка лишається на [MAX_MEMBERS], доки навантажувальний тест
     * не доведе більше. Заголовок приймає до цієї межі для будь-якої гілки:
     * чи гілка — простір, вирішує акт [Setting.KIND], а він приїжджає ПІСЛЯ
     * першого листа. Збірки до цієї не знають 50 і листа з довшим складом не
     * покажуть — простір без нової збірки однаково не відкрити.
     */
    const val MAX_SPACE_MEMBERS = 50

    fun capOf(kind: String): Int = if (kind == KIND_SPACE) MAX_SPACE_MEMBERS else MAX_MEMBERS

    data class Group(
        val id: String,
        val name: String,
        /** Everyone in it, the operator included. */
        val memberIds: List<String>,
        /**
         * Назва в заголовку була довша за [PeerName.MAX] — у [name] скорочена.
         *
         * Заголовок групи складає ЧУЖИЙ телефон, тож назва тут така сама чужа,
         * як імʼя людини, і мовчазний обріз бреше так само.
         */
        val nameShortened: Boolean = false,
        /**
         * Опис і обличчя гілки.
         *
         * У заголовку їх немає й не буде: [decode] відкидає голову, в якій не
         * рівно три поля, тож четверте зробило б КОЖЕН лист гілки невидимим
         * для вже зібраних версій. Вони приїжджають окремими актами
         * ([Act.About], [Act.Face]) і тому відомі лише тому, хто ці акти чув.
         *
         * Ціна цього названа, а не захована: хто приєднався пізніше, бачить
         * гілку без обличчя, доки адмін не надішле його знову. Саме тому
         * `PeerMesh.sendGroupAct` після кожного [Act.Add] докидає картку
         * новачкові — інакше «поставив аватар» тихо означало б «поставив собі».
         */
        val about: String = "",
        val face: String = "",
    ) {
        fun others(meId: String): List<String> = memberIds.filterNot { it == meId }
    }

    data class Message(val group: Group, val text: String)

    /**
     * Зміна складу або назви.
     *
     * Їде в ТІЛІ, не в заголовку: [decode] відкидає голову, в якій не рівно три
     * поля, тож четверте поле зробило б лист невидимим для кожної вже зібраної
     * версії — не гіршим, а відсутнім. У тілі стара збірка покаже сирий рядок,
     * а склад однаково перечитає з заголовка, який везе кожен лист.
     */
    sealed interface Act {
        /**
         * [invite] — безадресне запрошення PH2 того, кого додають, або порожньо.
         *
         * Без нього «додати» лишалось обіцянкою: доданий не має ключа жодного
         * члена, а вони — його, тож кожна копія вмирає в черзі, а екран показує
         * склад, у якому ніхто нікому не пише. Конверт їде ЧЕТВЕРТИМ полем саме
         * тому, що [decodeAct] його доти відкидав: стара збірка прочитає перші
         * три так само, як читала.
         */
        data class Add(val id: String, val invite: String = "") : Act

        data class Remove(val id: String) : Act

        data object Leave : Act

        data object Rename : Act

        /**
         * Підвищити або понизити. Щабель [PeerGroupRoles.Role.OWNER] цим
         * дієсловом не видається ніколи — для нього є [Transfer], і саме тому
         * їх двоє: «зроби його власником» і «стань власником замість мене» —
         * різні речення, і друге має наслідок для того, хто його каже.
         */
        data class Grant(val id: String, val role: PeerGroupRoles.Role) : Act

        /** Передати гілку. Той, хто передав, лишається адміном. */
        data class Transfer(val id: String) : Act

        /**
         * Закріпити рядок угорі гілки. [atMs] = 0 знімає закріплення.
         *
         * Адресується парою «автор + мить», а НЕ [PeerMessage.id]: id у віяра
         * свій на КОЖНУ копію, тож надісланий дротом він указував би в нікуди
         * на кожному чужому пристрої. Пара «автор+мить» — це рівно той ключ,
         * яким [collapse] уже згортає віяр у один рядок.
         */
        data class Pin(val authorId: String, val atMs: Long) : Act

        /** Опис гілки — те, що видно в картці до першого слова. */
        data class About(val text: String) : Act

        /**
         * Обличчя гілки — рядком, як його тримає сховище.
         *
         * Ціна названа числом, а не прикметником: картинка їде в тілі КОЖНОЇ
         * копії віяра, тож зміна обличчя в гілці на 32 людей коштує 31 ×
         * [FACE_FIELD_MAX] ≈ 250 КіБ черги. Дорого рівно один раз на зміну — і
         * дешевше за єдину альтернативу без сервера: протокол «дай байти на
         * хеш X», якого в продукті немає.
         */
        data class Face(val image: String) : Act

        /** Налаштування гілки: останнє чинне значення ключа перемагає. */
        data class Set(val key: Setting, val value: String) : Act

        /** Права адміна чи модератора. Видає лише власник. */
        data class Rights(val id: String, val rights: kotlin.collections.Set<PeerGroupRoles.Right>) : Act

        /**
         * Подія простору (11-spaces §10) — JSON, якого цей модуль не читає:
         * судить його рушій прав у вебі. Автором події є [PeerMessage.fromId]
         * листа, який її привіз, і жодне поле в самому JSON цього не змінить.
         */
        data class Space(val json: String) : Act
    }

    /**
     * Ключі налаштувань. [invite] — чиє це право: посилання й заявки
     * ([PeerGroupRoles.Right.INVITE]) чи решта ([PeerGroupRoles.Right.INFO]).
     */
    enum class Setting(val wire: String, val invite: Boolean = false) {
        KIND("kind"),
        SLOW("slow"),
        HISTORY("hist"),
        APPROVE("ask", invite = true),
        LINK("link", invite = true),
        COMMENTS("talk"),
        SIGN("sign"),
        ;

        fun accepts(value: String): Boolean = when (this) {
            KIND -> value == KIND_GROUP || value == KIND_CHANNEL || value == KIND_SPACE
            SLOW -> value.toIntOrNull()?.let { it in SLOW_STEPS } == true
            LINK -> value.isEmpty() || (value.length in 8..32 && value.all { it.isLetterOrDigit() })
            HISTORY, APPROVE, COMMENTS, SIGN -> value == ON || value == OFF
        }

        companion object {
            fun of(wire: String?): Setting? = entries.firstOrNull { it.wire == wire?.trim() }
        }
    }

    const val KIND_GROUP = "group"
    const val KIND_CHANNEL = "channel"
    const val KIND_SPACE = "space"
    const val ON = "on"
    const val OFF = "off"
    val SLOW_STEPS = listOf(0, 10, 30, 60, 300, 900, 3600)

    /** Налаштування за замовчуванням — те, як гілка жила до них. */
    val DEFAULTS: Map<Setting, String> = mapOf(
        Setting.KIND to KIND_GROUP,
        Setting.SLOW to "0",
        Setting.HISTORY to ON,
        Setting.APPROVE to OFF,
        Setting.LINK to "",
        Setting.COMMENTS to ON,
        Setting.SIGN to OFF,
    )

    fun encodeAct(act: Act): String = SYS + when (act) {
        is Act.Add ->
            if (act.invite.isBlank()) "add$SEP${act.id}" else "add$SEP${act.id}$SEP${b64(act.invite.toByteArray(Charsets.UTF_8))}"
        is Act.Remove -> "rm$SEP${act.id}"
        Act.Leave -> "leave"
        Act.Rename -> "name"
        is Act.Grant -> "grant$SEP${act.id}$SEP${act.role.wire}"
        is Act.Transfer -> "own$SEP${act.id}"
        is Act.Pin -> "pin$SEP${act.authorId}$SEP${act.atMs}"
        is Act.About -> "about$SEP${b64(act.text.toByteArray(Charsets.UTF_8))}"
        is Act.Face -> "face$SEP${b64(act.image.toByteArray(Charsets.UTF_8))}"
        is Act.Set -> "set$SEP${act.key.wire}$SEP${act.value}"
        is Act.Rights -> "rights$SEP${act.id}$SEP${PeerGroupRoles.Right.wire(act.rights)}"
        is Act.Space -> "space$SEP${b64(act.json.toByteArray(Charsets.UTF_8))}"
    }

    fun decodeAct(text: String): Act? {
        if (!text.startsWith(SYS)) return null
        val f = text.removePrefix(SYS).trim().split(SEP)
        val subject = f.getOrNull(1)?.trim().orEmpty()
            .takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() } }
        return when (f[0]) {
            // Зіпсутий конверт не забирає з собою сам акт: «додай X» лишається
            // чинним і без ключів — рівно тим, чим було до цієї хвилі.
            "add" -> subject?.let { Act.Add(it, envelope(f.getOrNull(2))) }
            "rm" -> subject?.let(Act::Remove)
            "leave" -> Act.Leave
            "name" -> Act.Rename
            // Невідомий щабель — не «учасник за замовчуванням», а НЕ акт:
            // мовчазне зниження до найнижчого щабля означало б, що збірка,
            // яка не знає нового слова, тихо роззброює людину.
            "grant" -> subject?.let { id ->
                PeerGroupRoles.Role.of(f.getOrNull(2))?.let { Act.Grant(id, it) }
            }
            "own" -> subject?.let(Act::Transfer)
            "pin" -> subject?.let { id ->
                f.getOrNull(2)?.trim()?.toLongOrNull()?.takeIf { it >= 0L }?.let { Act.Pin(id, it) }
            }
            "about" -> text(f.getOrNull(1), ABOUT_MAX, ABOUT_FIELD_MAX)?.let(Act::About)
            "face" -> text(f.getOrNull(1), FACE_MAX, FACE_FIELD_MAX)?.let(Act::Face)
            "set" -> Setting.of(f.getOrNull(1))?.let { key ->
                f.getOrNull(2).orEmpty().trim().takeIf { key.accepts(it) }?.let { Act.Set(key, it) }
            }
            "rights" -> subject?.let { id ->
                PeerGroupRoles.Right.parse(f.getOrNull(2).orEmpty())?.let { Act.Rights(id, it) }
            }
            "space" -> text(f.getOrNull(1), SPACE_MAX, SPACE_FIELD_MAX)?.takeIf { it.startsWith("{") }?.let(Act::Space)
            else -> null
        }
    }

    /** Опис гілки, у символах. Складає його свій бік, а ріже — чужий. */
    const val ABOUT_MAX = 512

    /**
     * Обличчя гілки, у символах уже закодованого рядка (`data:` URL).
     *
     * Межа стоїть тут, а не на «картинці після розбору», рівно з тієї ж
     * причини, що й у [envelope]: платити розбором за рядок, надісланий чужим
     * телефоном, не можна, а вирішити, чи рядок завеликий, можна до нього.
     */
    const val FACE_MAX = 8192

    /** Подія простору, у символах JSON. Найбільша — канал із перекриттями ролей. */
    const val SPACE_MAX = 4096

    // Один символ UTF-8 — до чотирьох байтів, base64 роздуває ще на третину.
    private const val ABOUT_FIELD_MAX = (ABOUT_MAX * 4 + 2) / 3 * 4
    private const val FACE_FIELD_MAX = (FACE_MAX * 4 + 2) / 3 * 4
    private const val SPACE_FIELD_MAX = (SPACE_MAX * 4 + 2) / 3 * 4

    /**
     * Порожньо — це ЗНАЧЕННЯ: «стерли опис» і «прислали зіпсуте поле» мусять
     * розрізнятись, інакше зняти опис було б неможливо, а поламаний кадр
     * знімав би його мовчки. Тому порожній рядок вертається, а непрочитане —
     * null, і null відкидає весь акт.
     */
    private fun text(field: String?, cap: Int, fieldCap: Int): String? {
        val raw = field?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        if (raw.length > fieldCap) return null
        val decoded = runCatching { String(unB64(raw), Charsets.UTF_8) }.getOrNull() ?: return null
        return if (decoded.length <= cap) decoded else null
    }

    /**
     * Склад гілки, засвідчений тим, хто вже в ній: id члена і його безадресне
     * запрошення. Дією НЕ є й не стане — склад ним не міняється: те, хто в
     * гілці, і далі читається з заголовка кожного листа. Назвати це [Act]
     * означало б покласти його в ту саму гілку `when`, що застосовує зміни, і
     * рано чи пізно склад поїхав би з тіла.
     */
    data class Roster(val id: String, val invite: String)

    fun encodeRoster(id: String, invite: String): String =
        SYS + "roster$SEP$id$SEP" + b64(invite.toByteArray(Charsets.UTF_8))

    fun decodeRoster(text: String): Roster? {
        if (!text.startsWith(SYS)) return null
        val f = text.removePrefix(SYS).trim().split(SEP)
        if (f.getOrNull(0) != "roster") return null
        val id = f.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() } }
            ?: return null
        val invite = envelope(f.getOrNull(2)).takeIf { it.isNotBlank() } ?: return null
        return Roster(id, invite)
    }

    /**
     * Конверт із поля — або порожньо. Межа стоїть на ЗАКОДОВАНОМУ полі, до
     * розкодування: base64 роздуває вчетверо на кожні три байти, тож «512 після
     * розбору» означало б платити розбором за рядок, надісланий чужим телефоном.
     */
    private fun envelope(field: String?): String {
        val raw = field?.trim().orEmpty()
        if (raw.isEmpty() || raw.length > ENVELOPE_FIELD_MAX) return ""
        val decoded = runCatching { String(unB64(raw), Charsets.UTF_8) }.getOrNull() ?: return ""
        return if (decoded.length <= PeerInvite.ENVELOPE_MAX) decoded else ""
    }

    private const val ENVELOPE_FIELD_MAX = (PeerInvite.ENVELOPE_MAX + 2) / 3 * 4

    /**
     * Ключі з акту складу — або нічого. ЄДИНІ двері, якими чужий конверт із
     * гілки потрапляє в реєстр.
     *
     * Імʼя зрізається на ПРИЙМАННІ, а не лише при складанні конверта. Той, хто
     * складає, — чужий телефон: правило «в гілці не їде жодного людського
     * рядка» тримається рівно доти, доки його перевіряє той бік, який має що
     * втратити. Імʼя з конверта, покладене в реєстр, читалось би на екрані як
     * назване самою людиною — а назвав його той, хто додавав.
     */
    fun invited(act: Act.Add): PeerInvite.Invite? = keysIn(act.id, act.invite)

    fun invited(roster: Roster): PeerInvite.Invite? = keysIn(roster.id, roster.invite)

    private fun keysIn(id: String, code: String): PeerInvite.Invite? {
        if (code.isBlank()) return null
        val seen = PeerInvite.addressless(code, expectId = id) as? PeerInvite.Addressless.Ok ?: return null
        return seen.invite.copy(name = "", nameShortened = false, nameUnreadable = false)
    }

    fun isAct(body: String): Boolean = decode(body)?.let { decodeAct(it.text) } != null

    /**
     * Чи рядок ПОЧИНАЄТЬСЯ як дія над складом.
     *
     * [isAct] питає про цілий груповий лист і читає дію з його тіла; це питає
     * про сам рядок. Саме він і потрібен на вході: текст, набраний у групі,
     * стає тілом [encode], тож рядок з таким початком проїхав би як команда
     * «вийшов», «додав», «перейменував» — від імені того, хто його набрав.
     */
    fun isSystem(body: String): Boolean = body.startsWith(SYS)

    /**
     * Дія як операція над складом, а не як заміна складу цілим значенням: два
     * телефони, що правлять групу одночасно, тоді не зʼїдають зміну одне одного
     * тільки тому, що чийсь годинник іде попереду.
     */
    fun apply(
        group: Group,
        actorId: String,
        act: Act,
        headerName: String,
        // Заголовок, з якого взято [headerName], уже пройшов [decode], а отже й
        // [PeerName.accept]. Скорочене вдруге коротше за межу й повернуло б
        // «не скорочували», тож факт приїжджає окремо, а не перевимірюється.
        headerShortened: Boolean = false,
        cap: Int = MAX_MEMBERS,
    ): Group = when (act) {
        is Act.Add ->
            if (act.id in group.memberIds || group.memberIds.size >= cap) {
                group
            } else {
                group.copy(memberIds = group.memberIds + act.id)
            }
        is Act.Remove -> group.copy(memberIds = group.memberIds.filterNot { it == act.id })
        Act.Leave -> group.copy(memberIds = group.memberIds.filterNot { it == actorId })
        Act.Rename -> {
            val accepted = PeerName.accept(headerName)
            if (accepted.name.isBlank()) {
                group
            } else {
                group.copy(
                    name = accepted.name,
                    nameShortened = accepted.shortened || headerShortened,
                )
            }
        }
        is Act.About -> group.copy(about = act.text)
        is Act.Face -> group.copy(face = act.image)
        // Склад і назву не міняють: їх місце — [PeerGroupRoles.Table] і
        // [State.pinned]. Тихо повернути `group` тут правильніше, ніж не мати
        // гілки: `when` вичерпний, і наступний, хто додасть дієслово, побачить
        // цей рядок і мусить вирішити, куди воно застосовується.
        is Act.Grant, is Act.Transfer, is Act.Pin, is Act.Set, is Act.Rights, is Act.Space -> group
    }

    /**
     * Гілка цілком: склад, ролі й закріплене.
     *
     * Окремим типом, а не трьома обходами журналу: ролі залежать від складу
     * (спадкоємність), склад — від ролей (хто мав право додати), тож два
     * незалежні проходи розійшлися б на першому ж акті, застосованому в одному
     * і відкинутому в другому.
     */
    data class State(
        val group: Group,
        val roles: PeerGroupRoles.Table,
        val pinned: Pin? = null,
        val settings: Map<Setting, String> = DEFAULTS,
        /** Події простору від тих, хто на ту мить був у складі; копії віяра згорнуто. */
        val spaceEvents: List<SpaceEvent> = emptyList(),
    ) {
        fun setting(key: Setting): String = settings[key] ?: DEFAULTS.getValue(key)

        val isChannel: Boolean get() = setting(Setting.KIND) == KIND_CHANNEL

        val slowSeconds: Int get() = setting(Setting.SLOW).toIntOrNull() ?: 0

        val isSpace: Boolean get() = setting(Setting.KIND) == KIND_SPACE

        val cap: Int get() = capOf(setting(Setting.KIND))
    }

    /** Закріплений рядок — автор і мить, ключ віяра ([collapse]). */
    data class Pin(val authorId: String, val atMs: Long)

    /** [actorId] — підписаний автор листа, а не поле JSON. */
    data class SpaceEvent(val actorId: String, val atMs: Long, val json: String)

    /** Random, not derived from members: two groups of the same people are two. */
    fun newId(random: () -> Int = { (0..15).random() }): String =
        (1..ID_LENGTH).joinToString("") { random().coerceIn(0, 15).toString(16) }

    /** Name is base64url'd so a chosen name cannot inject a separator. */
    fun encode(group: Group, text: String): String = buildString {
        append(TAG)
        append(group.id).append(SEP)
        append(b64(PeerName.cap(group.name).toByteArray(Charsets.UTF_8))).append(SEP)
        append(group.memberIds.take(MAX_SPACE_MEMBERS).joinToString(","))
        append('\n')
        append(text)
    }

    fun decode(body: String): Message? {
        if (!body.startsWith(TAG)) return null
        val head = body.substringBefore('\n', missingDelimiterValue = "")
        if (head.isEmpty()) return null
        val text = body.substring(head.length + 1)
        val f = head.removePrefix(TAG).split(SEP)
        if (f.size != 3) return null
        val id = f[0].trim()
        if (id.isEmpty() || !id.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return null
        val raw = runCatching { String(unB64(f[1]), Charsets.UTF_8) }.getOrNull() ?: return null
        val members = f[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (members.isEmpty() || members.size > MAX_SPACE_MEMBERS) return null
        // Тут межа й вичищення, а не в [encode]: заголовок складає чужий
        // телефон, і на нього обмеження при кодуванні не поширюється взагалі.
        val name = PeerName.accept(raw)
        return Message(
            Group(id, name.name.ifBlank { id }, members, nameShortened = name.shortened),
            text,
        )
    }

    fun isGroup(body: String): Boolean = body.startsWith(TAG)

    /**
     * Що новачок не отримав віяром і чого нема в шапці: опис, обличчя й рід
     * гілки. Без роду простір на 33+ людей для новачка — класична гілка понад
     * стелю, тобто взагалі не гілка.
     */
    /**
     * Склад із заголовка як доказ — лише в межах стелі, якою гілку знаємо ми.
     * Незнайому гілку стримує [decode]; знайома класична не приймає 33-го.
     */
    fun headerFits(seen: Message, known: State?): Boolean =
        seen.group.memberIds.size <= (known?.cap ?: MAX_SPACE_MEMBERS)

    fun newcomerCard(group: Group, kind: String?): List<Act> = listOfNotNull(
        group.about.takeIf { it.isNotBlank() }?.let { Act.About(it) },
        group.face.takeIf { it.isNotBlank() }?.let { Act.Face(it) },
        kind?.takeIf { it != KIND_GROUP }?.let { Act.Set(Setting.KIND, it) },
    )

    /**
     * Кому їде лист. [only] — читачі замкненого каналу простору: копія
     * запечатується кожному окремо, тож лист, якого не послали, не відкриє
     * ніхто, — це і є замок, а не схованка на екрані. Новий склад читачів
     * рахується на кожен лист, тож зміна ролі діє з наступного слова.
     */
    fun fanOut(group: Group, meId: String, only: kotlin.collections.Set<String>? = null): List<String> =
        group.others(meId).distinct().filter { only == null || it in only }.take(MAX_SPACE_MEMBERS)

    /** Рядок каналу простору: `PH-CHN:<канал>` і з нового рядка — сказане. */
    const val CHANNEL = "PH-CHN:"

    data class ChannelLine(val channelId: String, val text: String)

    fun isChannelId(id: String): Boolean =
        id.length in 1..64 && id.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    fun inChannel(channelId: String, text: String): String? =
        if (isChannelId(channelId)) "$CHANNEL$channelId\n$text" else null

    fun isChannelLine(text: String): Boolean = text.startsWith(CHANNEL)

    /** Канал і сказане — або null, якщо рядок не канальний чи канал битий. */
    fun channelLine(text: String): ChannelLine? {
        if (!isChannelLine(text)) return null
        val head = text.substringBefore('\n', missingDelimiterValue = "")
        val id = head.removePrefix(CHANNEL)
        if (!isChannelId(id)) return null
        return ChannelLine(id, text.substring(head.length + 1))
    }

    /** Подія простору в листі гілки — у стрічку не йде. */
    fun isSpaceLine(body: String): Boolean = decode(body)?.text?.let { decodeAct(it) } is Act.Space

    /** Counts real states: with five recipients there is no single truth. */
    data class Fan(val delivered: Int, val waiting: Int, val failed: Int) {
        val total: Int get() = delivered + waiting + failed
        val allDelivered: Boolean get() = total > 0 && delivered == total
    }

    fun fanState(outbox: List<PeerMessage>, groupId: String, sentAtMs: Long): Fan {
        val siblings = outbox.filter {
            it.sentAtMs == sentAtMs && decode(it.body)?.group?.id == groupId
        }
        return Fan(
            delivered = siblings.count { it.state == PeerMessage.State.DELIVERED },
            waiting = siblings.count {
                it.state == PeerMessage.State.QUEUED ||
                    it.state == PeerMessage.State.SENT ||
                    it.state == PeerMessage.State.HELD
            },
            failed = siblings.count { it.state == PeerMessage.State.FAILED },
        )
    }

    /** Id усіх копій одного сказаного рядка, які не дійшли. */
    fun failedFan(outbox: List<PeerMessage>, groupId: String, sentAtMs: Long): List<String> =
        outbox.filter {
            it.sentAtMs == sentAtMs &&
                it.state == PeerMessage.State.FAILED &&
                decode(it.body)?.group?.id == groupId
        }.map { it.id }

    /**
     * Folds the fan back into one line per thing said. Copies are matched by
     * author, instant and words — each has its own id by construction.
     */
    fun collapse(messages: List<PeerMessage>, groupId: String): List<PeerMessage> =
        messages
            .filter { decode(it.body)?.group?.id == groupId }
            .groupBy { Triple(it.fromId, it.sentAtMs, it.body) }
            .map { (_, copies) -> copies.minByOrNull { it.state.ordinal } ?: copies.first() }
            .sortedBy { it.sentAtMs }

    fun replyForRecipient(messages: List<PeerMessage>, groupId: String, replyToId: String, toId: String): String {
        if (replyToId.isBlank()) return ""
        val origin = messages.firstOrNull { it.id == replyToId && decode(it.body)?.group?.id == groupId } ?: return replyToId
        return messages.firstOrNull {
            it.fromId == origin.fromId &&
                it.sentAtMs == origin.sentAtMs &&
                it.body == origin.body &&
                it.toId == toId
        }?.id ?: origin.id
    }

    /**
     * A group IS its messages — but склад і назву міняють ЛИШЕ [Act] від того,
     * хто на той момент був у групі, плюс найперший лист, з якого група взагалі
     * стала відома.
     *
     * Правило «найновіший лист виграє» коштувало двох мовчазних поразок, і
     * жодну з них не було видно на екрані: прибраний учасник вертав себе назад
     * першою ж реплікою, бо його заголовок і далі несе його самого, а нова
     * назва відкочувалась на стару, щойно писав той, до кого перейменування ще
     * не дійшло. Балачка більше не редагує групу.
     */
    fun known(messages: List<PeerMessage>): List<Group> = states(messages).map { it.group }

    /** Гілка з таким id — цілком, з ролями. */
    fun stateOf(messages: List<PeerMessage>, groupId: String): State? =
        states(messages).firstOrNull { it.group.id == groupId }

    /**
     * Журнал → гілки. Тут і тільки тут акт стає чинним.
     *
     * **Дві перевірки, і друга з них нова.** Перша — «той, хто діє, у складі»
     * — стояла давно. Друга — «і має на це право» ([PeerGroupRoles.refuse]) —
     * і є тим, чого бракувало: без неї будь-хто зі складу перейменовував гілку
     * й виганяв кого завгодно, а «власник» і «адмін» жили лише як бейджі на
     * екрані.
     *
     * Відмова тут МОВЧАЗНА навмисно: акт без права — це не помилка цього
     * телефона, а чужий лист, якому він не вірить; питати людину про нього
     * нічого. Бачити його як спробу мусить той, хто його надіслав, — і бачить,
     * бо [refuse] питається ДО надсилання тим самим викликом.
     *
     * **Хто власник.** Той, чий лист у цій гілці найперший з відомих цьому
     * телефону. Для того, хто гілку завів, це він сам; для запрошеного — той,
     * хто його додав, бо саме цей лист гілку йому й склав. Це засвідчено, а не
     * доведено підписом «я творець» — такого підпису на дроті немає, і вдавати
     * його тут не можна. Далі посада ходить лише через [Act.Transfer] і
     * [PeerGroupRoles.succeed].
     */
    fun states(messages: List<PeerMessage>): List<State> {
        val seen = messages
            .mapNotNull { m -> decode(m.body)?.let { m to it } }
            .sortedBy { it.first.sentAtMs }
        return seen.groupBy { it.second.group.id }.values.mapNotNull { all ->
            if (all.all { decodeAct(it.second.text) == Act.Leave }) return@mapNotNull null
            // Ширший за 32 заголовок має вагу лише в просторі: для класичної
            // гілки його немає зовсім, як до простору, інакше один зсунутий у
            // минуле лист на 33 імені стирав би гілку в усіх.
            val folded = fold(all)
            if (folded?.isSpace == true) return@mapNotNull folded
            val narrow = all.filter { it.second.group.memberIds.size <= MAX_MEMBERS }
            if (narrow.isEmpty() || narrow.all { decodeAct(it.second.text) == Act.Leave }) return@mapNotNull null
            if (narrow.size == all.size) folded else fold(narrow)
        }
    }

    private fun fold(history: List<Pair<PeerMessage, Message>>): State? {
        var current = history.first().second.group
        val firstSpeaker = history.first().first.fromId
        var roles = PeerGroupRoles.Table(
            ownerId = firstSpeaker.takeIf { it in current.memberIds }
                ?: current.memberIds.firstOrNull().orEmpty(),
        )
        var pinned: Pin? = null
        var settings = DEFAULTS
        val spaceEvents = LinkedHashSet<SpaceEvent>()
        history.forEachIndexed { seq, (message, decoded) ->
            val act = decodeAct(decoded.text) ?: return@forEachIndexed
            if (message.fromId !in current.memberIds) return@forEachIndexed
            val cap = capOf(settings[Setting.KIND] ?: KIND_GROUP)
            if (PeerGroupRoles.refuse(roles, current.memberIds, message.fromId, act, cap) != null) {
                return@forEachIndexed
            }
            when (act) {
                is Act.Grant, is Act.Transfer, is Act.Rights -> roles = PeerGroupRoles.apply(roles, act, seq)
                is Act.Set -> if (act.key != Setting.KIND || settings[Setting.KIND] != KIND_SPACE) {
                    settings = settings + (act.key to act.value)
                }
                is Act.Pin -> pinned = if (act.atMs == 0L) null else Pin(act.authorId, act.atMs)
                is Act.Space -> spaceEvents += SpaceEvent(message.fromId, message.sentAtMs, act.json)
                else -> current = apply(
                    current,
                    message.fromId,
                    act,
                    decoded.group.name,
                    decoded.group.nameShortened,
                    cap,
                )
            }
            // Після КОЖНОГО акту, а не в кінці: власник, що вийшов
            // усередині журналу, лишив би гілку без голови рівно доти,
            // доки наступний акт спадкоємця не був би за це відкинутий.
            roles = PeerGroupRoles.succeed(roles, current.memberIds)
        }
        val opened = history.first().second.group.memberIds.size
        if (settings[Setting.KIND] != KIND_SPACE && opened > MAX_MEMBERS) return null
        return State(current, roles, pinned, settings, spaceEvents.toList())
    }

    /**
     * Чи можна зробити це ЗАРАЗ — той самий суддя, якого питає приймач.
     *
     * Потрібен саме тому, що приймач мовчить: без цього поверхня показувала б
     * кнопку, натискання якої нічого не змінює ніде, — та сама вада «мовчки не
     * робить нічого», лише розсіяна на тридцять пристроїв.
     */
    fun refuse(
        messages: List<PeerMessage>,
        groupId: String,
        actorId: String,
        act: Act,
    ): PeerGroupRoles.Why? {
        val state = stateOf(messages, groupId) ?: return PeerGroupRoles.Why.NOT_IN_GROUP
        return PeerGroupRoles.refuse(state.roles, state.group.memberIds, actorId, act, state.cap)
    }

    /**
     * Де оператор стоїть щодо групи. Вийти можна без мережі, тож група, з якої
     * вийшли, лишається на телефоні читабельною — але вже не пише.
     */
    enum class Standing { IN, LEFT, REMOVED }

    fun standing(messages: List<PeerMessage>, group: Group, meId: String): Standing = when {
        meId.isBlank() || meId in group.memberIds || "user_me" in group.memberIds || "me" in group.memberIds -> Standing.IN
        myLeave(messages, group.id, meId) != null -> Standing.LEFT
        else -> Standing.REMOVED
    }

    /**
     * Власне «виходжу» — щоб було видно, скільки людей це вже почуло. Поки хоч
     * одна копія не дійшла, для когось оператор досі в групі, і це не здогад.
     */
    fun myLeave(messages: List<PeerMessage>, groupId: String, meId: String): PeerMessage? =
        messages
            .filter { m ->
                val said = if (m.fromId == meId) decode(m.body) else null
                said != null && said.group.id == groupId && decodeAct(said.text) == Act.Leave
            }
            .maxByOrNull { it.sentAtMs }

    /**
     * Blank [meId] means «ще не знаємо, хто ми» — тоді свої ж листи лічились
     * чужими. Група, з якої вийшли, теж мовчить: листи, запечатані до цього
     * телефона раніше, ще прийдуть, і рахувати їх непрочитаними означало б
     * тягти людину назад у розмову, з якої вона щойно вийшла.
     */
    fun unread(messages: List<PeerMessage>, groupId: String, meId: String, readUpToMs: Long): Int {
        if (meId.isBlank()) return 0
        val group = known(messages).firstOrNull { it.id == groupId } ?: return 0
        if (meId !in group.memberIds) return 0
        return collapse(messages, groupId)
            .filterNot { isMachineLine(it.body) }
            .count { it.fromId != meId && it.sentAtMs > readUpToMs }
    }

    /**
     * Рядок гілки, у якому людині немає нічого: службове дієслово, якому ця
     * збірка не має речення. Склад ([Act]) сюди не входить — його показують.
     *
     * Вступ у гілку на 32 людей — це 31 рядок складу ключів; без цієї відмови
     * новачок відкрив би гілку з плашкою «31» і порожньою стрічкою. Ширше за
     * самий [Roster] навмисно: дієслово з новішої збірки мовчки додасть себе в
     * те саме число, і побачити це буде ніде.
     */
    fun isMachineLine(body: String): Boolean {
        val text = decode(body)?.text ?: return false
        if (!isSystem(text)) return false
        val act = decodeAct(text)
        return act == null || act is Act.Space
    }

    /**
     * Коли в групі востаннє щось сказали — ключ, яким рядок групи стає в один
     * порядок із людьми й кімнатами. Нуль означає «ще нічого», і саме нуль, а
     * не «зараз»: інакше німа група вічно стояла б першою.
     */
    fun lastAt(messages: List<PeerMessage>, groupId: String): Long =
        collapse(messages, groupId)
            .filterNot { isMachineLine(it.body) }
            .maxOfOrNull { it.sentAtMs } ?: 0L

    /**
     * Останнє сказане в групі, без службових рядків. Рядок групи в списку не
     * ніс жодного змісту — квадратик, назва і «N учасників», число, яке не
     * змінюється тижнями; всі групи виглядали однаково і завжди однаково.
     */
    fun lastText(messages: List<PeerMessage>, groupId: String): String? =
        collapse(messages, groupId)
            // Не `isAct`, а весь службовий початок: дієслово, якого цей телефон
            // ще не знає (з новішої збірки), інакше приїхало б у рядок списку
            // сирим дротом — рівно тим шляхом, яким туди вже потрапляв PH-RTE.
            .filterNot { decode(it.body)?.text?.let(::isSystem) ?: true }
            .maxByOrNull { it.sentAtMs }
            ?.let { decode(it.body)?.text }
            ?.let { channelLine(it)?.text ?: it }
            ?.takeIf { it.isNotBlank() && !isChannelLine(it) }

    private const val ID_LENGTH = 12

    private fun b64(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun unB64(text: String): ByteArray =
        java.util.Base64.getUrlDecoder().decode(text)
}
