package local.phantom.companion.core.net.peer

/**
 * Хто в гілці що МОЖЕ — і чому саме не може той, хто не може.
 *
 * **Вада, заради якої це існує.** Ролей у групі на дроті не було взагалі:
 * чотири дії [PeerGroup.Act] застосовувались від будь-кого, чий кадр
 * розшифрувався. «Власник», «адмін», «модератор» існували лише як бейджі на
 * екрані, під якими не стояло нічого, — і комміт `98a90397` їх прибрав саме
 * тому, що вони були намальовані. Цей файл кладе під них те, чого бракувало:
 * приймач, що ВІДМОВЛЯЄ.
 *
 * **Чому перевірка можлива без жодного нового підпису.** Гілка — це не
 * кімната на сервері, а віяр попарних листів ([PeerGroup.fanOut]): кожну копію
 * склав, запечатав і підписав саме той, хто діє, і саме для мене. Тож
 * [PeerMessage.fromId] на вже відкритому листі — не твердження відправника, а
 * те, чий ключ його відкрив. Ланцюг «власник підвищив X, X вигнав Y» приїжджає
 * до мене двома листами, кожен автентичний сам по собі. Додавати підпис на акт
 * не треба — він там уже є, просто його ніхто не питав.
 *
 * **Що з цього НЕ випливає.** Нечесна збірка фізично може надіслати
 * `Act.Remove` без права: дріт ніхто не затуляє. Обіцяне тут інше — жодна
 * чесна збірка такого акту НЕ ЗАСТОСУЄ, і всі чесні збірки не застосують його
 * однаково, бо рішення — чиста функція від журналу ([PeerGroup.states]).
 * Enforcement тут завжди на приймачі й у кожного свій; серверного немає, бо
 * сервера немає.
 *
 * **Ціна сумісності, названа вголос.** Збірка, що не знає дієслів `grant`,
 * `own`, `pin`, `about`, `face`, відкине їх у [PeerGroup.decodeAct] як null і
 * не побачить ієрархії взагалі — тобто лишиться ДОЗВОЛЬНІШОЮ за цю: у неї
 * будь-хто зі складу й далі перейменує гілку та вижене кого завгодно.
 * Розходження без дня переходу неминуче; важливо, у який бік воно падає, і
 * тут воно падає в безпечний: нова збірка не застосує чужої дії, на яку в
 * того немає права, навіть якщо стара її застосувала. Єдиний напрямок, у
 * якому це могло б КОГОСЬ ЗАГУБИТИ, — `add`, і саме тому `add` лишився
 * дозволеним усім (див. [refuse]).
 */
object PeerGroupRoles {

    /**
     * Чотири щаблі, і жодного зайвого.
     *
     * [rank] — це і є правило: старший може те, що молодший, і може над
     * молодшим. Пʼять титулів старої `RoleScopesModal` (Тімлід, Спостерігач)
     * не мали ЖОДНОЇ дії, якою б відрізнялись; тут кожен щабель відрізняється
     * від сусіднього конкретним рядком таблиці [may].
     *
     * [wire] їде на дроті й мінятись не може ніколи — це частина формату.
     */
    enum class Role(val wire: String, val rank: Int) {
        MEMBER("member", 0),
        MODERATOR("mod", 1),
        ADMIN("admin", 2),
        OWNER("owner", 3),
        ;

        companion object {
            fun of(wire: String?): Role? = entries.firstOrNull { it.wire == wire?.trim() }
        }
    }

    /**
     * Що взагалі можна робити з гілкою.
     *
     * Перелік навмисно НЕ ширший за те, що вміє дріт: кожен елемент тут
     * відповідає дієслову [PeerGroup.Act], яке справді летить і справді
     * застосовується. Додати сюди «право на дзвінок» чи «лише читання»
     * означало б повернути матрицю дозволів, під якою нічого не стоїть.
     */
    enum class Power {
        /** Додати людину до складу. Може кожен — див. [refuse]. */
        ADD,

        /** Прибрати людину зі складу — лише того, хто стоїть НИЖЧЕ. */
        REMOVE,

        /** Перейменувати гілку. */
        RENAME,

        /** Опис і картинка гілки. */
        CARD,

        /** Підвищити або понизити когось. */
        GRANT,

        /** Передати гілку іншому. */
        TRANSFER,

        /** Закріпити рядок угорі. */
        PIN,

        /** Посилання-запрошення і заявки. */
        INVITE,

        /** Повільний режим, історія, рід гілки. */
        SETTINGS,
    }

    /**
     * Права окремого адміна чи модератора, як у Telegram. Без акту `rights`
     * щабель має всі — так стара гілка не втрачає жодного права.
     */
    enum class Right(val wire: String) {
        INFO("info"),
        PIN("pin"),
        BAN("ban"),
        INVITE("invite"),
        ADMINS("admins"),
        ;

        companion object {
            fun of(wire: String?): Right? = entries.firstOrNull { it.wire == wire?.trim() }

            fun parse(csv: String): Set<Right>? {
                val parts = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val rights = parts.mapNotNull(::of).toSet()
                return if (rights.size == parts.toSet().size) rights else null
            }

            fun wire(rights: Set<Right>): String = entries.filter { it in rights }.joinToString(",") { it.wire }
        }
    }

    /**
     * Чому відмовлено. Саме перелік, а не рядок: речення складе поверхня
     * своєю мовою, а «не вдалось» не скаже ніхто — це те слово, заради
     * відсутності якого цей enum і написаний.
     */
    enum class Why {
        /** Того, хто діє, у складі немає — жодної влади він не має взагалі. */
        NOT_IN_GROUP,

        /** Того, над ким діють, у складі немає. */
        SUBJECT_NOT_IN_GROUP,

        /** Треба бути щонайменше модератором. */
        NEEDS_MODERATOR,

        /** Треба бути щонайменше адміністратором. */
        NEEDS_ADMIN,

        /** Може лише власник гілки. */
        NEEDS_OWNER,

        /** Той, над ким діють, стоїть не нижче за того, хто діє. */
        SUBJECT_OUTRANKS,

        /** Видати щабель, не нижчий за власний, не можна. */
        ROLE_TOO_HIGH,

        /** Людина вже має рівно цей щабель — акт нічого не змінює. */
        NOT_A_CHANGE,

        /** Склад уже впертий у [PeerGroup.MAX_MEMBERS]. */
        GROUP_FULL,

        /** Людина вже у складі. */
        ALREADY_IN,

        /** Над собою цього не роблять: вийти — окрема дія. */
        SELF,

        /** Щабель є, а цього права власник йому не дав. */
        NO_RIGHT,
    }

    /**
     * Розклад ролей гілки на певну мить журналу.
     *
     * [ownerId] окремо від [byId] не з ощадливості: власник — це не «запис у
     * таблиці», а спадкоємна посада. Хто нею володіє, вирішує [succeed], і
     * тримати це в тій самій мапі означало б, що її можна переписати
     * `grant`-ом, чого дріт не дозволяє.
     *
     * [grantedAt] — порядковий номер акту, яким щабель видано. Потрібен саме
     * для спадкоємності: «найдавніший ще присутній адмін» мусить обчислюватись
     * ОДНАКОВО на кожному пристрої, а годинник для цього не годиться — він
     * розходиться. Номер у журналі не розходиться.
     */
    data class Table(
        val ownerId: String,
        val byId: Map<String, Role> = emptyMap(),
        val grantedAt: Map<String, Int> = emptyMap(),
        val rights: Map<String, Set<Right>> = emptyMap(),
    ) {
        fun of(id: String): Role = when {
            id == ownerId -> Role.OWNER
            else -> byId[id] ?: Role.MEMBER
        }

        fun has(id: String, right: Right): Boolean = isOwner(id) || rights[id]?.contains(right) ?: true

        fun isOwner(id: String): Boolean = id.isNotBlank() && id == ownerId

        /** Усі, хто стоїть вище за звичайного учасника, — власник перший. */
        fun staff(memberIds: List<String>): List<String> =
            memberIds.filter { of(it) != Role.MEMBER }.sortedByDescending { of(it).rank }
    }

    /**
     * Чи відмовити, і чому. `null` означає «можна» — єдина відповідь без
     * причини, бо дозвіл причини й не потребує.
     *
     * Тут і тільки тут живе таблиця прав. [PeerGroup.states] питає її на
     * ПРИЙМАННІ кожного акту, а поверхня — до надсилання, тим самим викликом:
     * дві різні відповіді на те саме питання були б другою правдою про дріт.
     */
    fun refuse(
        table: Table,
        memberIds: List<String>,
        actorId: String,
        act: PeerGroup.Act,
        cap: Int = PeerGroup.MAX_MEMBERS,
    ): Why? {
        if (actorId.isBlank() || actorId !in memberIds) return Why.NOT_IN_GROUP
        val actor = table.of(actorId)
        return when (act) {
            // Вийти може будь-хто, включно з власником: тримати людину в
            // гілці силою — не право, а пастка. Гілку підхопить [succeed].
            PeerGroup.Act.Leave -> null

            // Додати може КОЖЕН у складі, і це рішення з ціною, порахованою
            // в обидва боки. Зробити «додати» правом модератора означало б, що
            // перша ж нова збірка ВІДКИНЕ кожен старий `add` від звичайного
            // учасника — і люди, додані ними на вже роздані збірки, мовчки
            // зникнуть зі складу, лишившись у стрічці авторами реплік від
            // нікого. Це рівно та вада «видалення рухає ратчети вниз», і
            // платити нею за суворість не можна. Обмеження «додавати можуть
            // лише адміни» — це окреме дієслово на дроті, а не мовчазна зміна
            // сенсу старих кадрів; малювати перемикач під нього, доки його
            // немає, теж не можна.
            is PeerGroup.Act.Add -> when {
                act.id.isBlank() -> Why.SUBJECT_NOT_IN_GROUP
                act.id in memberIds -> Why.ALREADY_IN
                memberIds.size >= cap -> Why.GROUP_FULL
                else -> null
            }

            // Модератор — це той, хто прибирає учасника; адмін — той, хто
            // прибирає модератора. Строга нерівність рангів і є вся ієрархія:
            // рівний рівного не чіпає ніколи.
            //
            // Старе «прибрав» від звичайного учасника нова збірка ВІДКИНЕ, і
            // прибраний вернеться у склад. Це видно й виправно, на відміну від
            // зворотного напрямку, і саме тому межа тут, а не на `add`.
            is PeerGroup.Act.Remove -> when {
                act.id !in memberIds -> Why.SUBJECT_NOT_IN_GROUP
                act.id == actorId -> Why.SELF
                actor.rank < Role.MODERATOR.rank -> Why.NEEDS_MODERATOR
                table.of(act.id).rank >= actor.rank -> Why.SUBJECT_OUTRANKS
                !table.has(actorId, Right.BAN) -> Why.NO_RIGHT
                else -> null
            }

            PeerGroup.Act.Rename,
            is PeerGroup.Act.About,
            is PeerGroup.Act.Face,
            -> when {
                actor.rank < Role.ADMIN.rank -> Why.NEEDS_ADMIN
                !table.has(actorId, Right.INFO) -> Why.NO_RIGHT
                else -> null
            }

            // Закріплений рядок бачать усі — це вітрина гілки, не редагування
            // складу. Тому модератора досить, і саме цим модератор і є.
            is PeerGroup.Act.Pin -> when {
                actor.rank < Role.MODERATOR.rank -> Why.NEEDS_MODERATOR
                !table.has(actorId, Right.PIN) -> Why.NO_RIGHT
                else -> null
            }

            is PeerGroup.Act.Set -> when {
                act.key == PeerGroup.Setting.KIND -> if (table.isOwner(actorId)) null else Why.NEEDS_OWNER
                actor.rank < Role.ADMIN.rank -> Why.NEEDS_ADMIN
                act.key.invite && !table.has(actorId, Right.INVITE) -> Why.NO_RIGHT
                !act.key.invite && !table.has(actorId, Right.INFO) -> Why.NO_RIGHT
                else -> null
            }

            is PeerGroup.Act.Rights -> when {
                act.id !in memberIds -> Why.SUBJECT_NOT_IN_GROUP
                act.id == actorId -> Why.SELF
                !table.isOwner(actorId) -> Why.NEEDS_OWNER
                table.of(act.id).rank < Role.MODERATOR.rank -> Why.NOT_A_CHANGE
                else -> null
            }

            is PeerGroup.Act.Grant -> when {
                act.id !in memberIds -> Why.SUBJECT_NOT_IN_GROUP
                act.id == actorId -> Why.SELF
                act.role == Role.OWNER -> Why.ROLE_TOO_HIGH
                actor.rank < Role.ADMIN.rank -> Why.NEEDS_ADMIN
                // Над рівним і старшим влади немає — інакше двоє адмінів
                // роззброювали б одне одного по колу, і склад залежав би від
                // того, чий лист приїхав другим.
                table.of(act.id).rank >= actor.rank -> Why.SUBJECT_OUTRANKS
                act.role.rank >= actor.rank -> Why.ROLE_TOO_HIGH
                table.of(act.id) == act.role -> Why.NOT_A_CHANGE
                !table.has(actorId, Right.ADMINS) -> Why.NO_RIGHT
                else -> null
            }

            is PeerGroup.Act.Transfer -> when {
                act.id !in memberIds -> Why.SUBJECT_NOT_IN_GROUP
                act.id == actorId -> Why.NOT_A_CHANGE
                !table.isOwner(actorId) -> Why.NEEDS_OWNER
                else -> null
            }

            // Подію простору судить рушій прав простору, а не щаблі гілки:
            // тут досить того, що автор у складі.
            is PeerGroup.Act.Space -> null
        }
    }

    /** Те саме питання, поставлене про право взагалі, а не про конкретний акт. */
    fun may(table: Table, memberIds: List<String>, actorId: String, power: Power, cap: Int = PeerGroup.MAX_MEMBERS): Boolean {
        if (actorId.isBlank() || actorId !in memberIds) return false
        val rank = table.of(actorId).rank
        val has = { r: Right -> table.has(actorId, r) }
        return when (power) {
            Power.ADD -> memberIds.size < cap
            Power.REMOVE -> rank >= Role.MODERATOR.rank && has(Right.BAN)
            Power.PIN -> rank >= Role.MODERATOR.rank && has(Right.PIN)
            Power.RENAME, Power.CARD, Power.SETTINGS -> rank >= Role.ADMIN.rank && has(Right.INFO)
            Power.GRANT -> rank >= Role.ADMIN.rank && has(Right.ADMINS)
            Power.INVITE -> rank >= Role.ADMIN.rank && has(Right.INVITE)
            Power.TRANSFER -> table.isOwner(actorId)
        }
    }

    /**
     * Застосувати вже дозволений акт до розкладу ролей.
     *
     * Дозвіл сюди не заглядає: спитати [refuse] мусить той, хто кличе, і
     * [PeerGroup.states] саме це й робить. Розділено навмисно — функція, що
     * і судить, і застосовує, рано чи пізно застосує щось, чого не судила.
     */
    fun apply(table: Table, act: PeerGroup.Act, seq: Int): Table = when (act) {
        is PeerGroup.Act.Grant -> table.copy(
            byId = table.byId + (act.id to act.role),
            grantedAt = table.grantedAt + (act.id to seq),
            rights = table.rights - act.id,
        )
        is PeerGroup.Act.Rights -> table.copy(rights = table.rights + (act.id to act.rights))
        // Колишній власник лишається адміном, а не падає в учасники: людина
        // передала гілку, а не зреклася її. Падіння до нуля означало б, що
        // передати комусь ключі можна лише ціною втрати доступу до складу.
        is PeerGroup.Act.Transfer -> Table(
            ownerId = act.id,
            byId = table.byId - act.id + (table.ownerId to Role.ADMIN),
            grantedAt = table.grantedAt - act.id + (table.ownerId to seq),
            rights = table.rights - act.id - table.ownerId,
        )
        else -> table
    }

    /**
     * Хто веде гілку, коли власника в ній уже немає.
     *
     * Не «ніхто»: гілка без власника — це гілка, якої не перейменувати й не
     * почистити, доки всі не вийдуть. І не «перший-ліпший»: правило мусить
     * дати ТОЙ САМИЙ результат на кожному пристрої з того самого журналу, бо
     * домовитись їм ніде — сервера немає.
     *
     * Порядок: найдавніше підвищений присутній адмін; якщо таких немає —
     * найдавніше підвищений модератор; якщо й таких немає — перший у складі,
     * а склад упорядкований самим дротом ([PeerGroup.apply] дописує в кінець).
     *
     * Розбіжні журнали дадуть розбіжних власників — так само, як вони вже
     * дають розбіжний склад. Це названо, а не заховано.
     */
    fun succeed(table: Table, memberIds: List<String>): Table {
        if (table.ownerId in memberIds) return table
        val heir = listOf(Role.ADMIN, Role.MODERATOR)
            .firstNotNullOfOrNull { rank ->
                memberIds
                    .filter { table.byId[it] == rank }
                    .minByOrNull { table.grantedAt[it] ?: Int.MAX_VALUE }
            }
            ?: memberIds.firstOrNull()
            ?: return table
        return table.copy(ownerId = heir, byId = table.byId - heir)
    }
}
