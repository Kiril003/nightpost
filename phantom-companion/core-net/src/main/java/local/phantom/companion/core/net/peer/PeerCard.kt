package local.phantom.companion.core.net.peer

/**
 * Картка композера на дроті.
 *
 * **Вада, заради якої це існує.** Композер веба складає тринадцять карток —
 * Canvas, Kanban, голосування, RACI, Gantt, Code Runner, Mermaid, git-diff,
 * сімейний список, спільний бюджет, навчальні картки, SVG-макет, async-огляд.
 * Усі тринадцять ішли `messengerNetworkEngine.sendMessage`, тобто або
 * WebSocket, або канал даних WebRTC. У WebView телефона мертві ОБИДВА: сокет
 * глушить заглушка ще до першого скрипта бандла, а каналу даних нема кому
 * звести — сигнальної служби для нього не існує. Функція при цьому мовчки
 * повертала `'server'`, ніби надіслала. Картка лягала у ВЛАСНУ стрічку і не
 * їхала нікуди й ніколи; співрозмовник не бачив її взагалі, а відправник не
 * бачив жодної ознаки, що чогось не сталось.
 *
 * Дроту ж такого роду не існувало зовсім: [PeerFrames] знав маршрут, місце,
 * шматок файла, склад групи, дзвінок, позначку й пачку історії — і жодного
 * способу повезти структуровану картку. Тому це НОВИЙ рід, а не переклад
 * старого: `PH-CRD:`.
 *
 * **Чому тіло возиться цілим і непрочитаним.** Усередині — JSON, який склав і
 * який намалює ВЕБ. Телефон його не розбирає й не складає назад: розбирач тут
 * був би другою правдою про форму картки, і розійшовся б із вебом на першому ж
 * перезбиранні бандла — рівно так, як це вже описано для [PeerCall]. Дріт знає
 * лише РІД картки, бо від нього залежить, чи намалює її той бік.
 *
 * **Чому словник закритий.** Рід, якого приймач не вміє намалювати, не має
 * їхати «чимось схожим»: рамка з написом і порожнечею всередині — це вигляд
 * спроможності без спроможності, і саме проти цього стоїть
 * [local.phantom.companion.ui.messenger.WebMessengerKinds.NOTICE]. Тому
 * [Card.of] відмовляє невідомому роду ДО сокета, а [decode] на невідомій
 * позначці віддає null — не «картка невідомого роду», а нічого, і тоді лист
 * стає видимо зламаним, а не тихо порожнім.
 */
object PeerCard {

    /** Спільний початок. Один на всі тринадцять родів: рід їде полем. */
    internal const val TAG = "PH-CRD:"

    private const val SEP = ';'

    /**
     * Стеля тіла картки в base64.
     *
     * [PeerWire.MAX_BODY_BYTES] — 64 КіБ на ВЕСЬ кадр, і навколо цього тіла
     * стане ще конверт (`+33 %` від шифротексту в base64) та підпис. Найбільша
     * зі справжніх карток — Kanban із трьома колонками задач — це одиниці
     * кілобайтів; 24 576 знаків (18 КіБ сирих) лишають кратний запас і при
     * цьому відмовляють ДО сокета. Кадр, зрізаний на дроті, не відмовляється —
     * він мовчки не доїжджає, і картка «просто не зʼявляється».
     */
    const val MAX_CARD_CHARS = 24_576

    /**
     * Рід картки — і словами ВЕБА, а не своїми.
     *
     * Перелік не вигаданий: це рівно ті тринадцять значень `Message['type']`,
     * які складає композер (`types/messenger.ts`) і які читає його ж стрічка.
     * Своя назва поруч із його назвою була б другою правдою про ту саму
     * картку. На дроті при цьому їде КОРОТКА позначка, а не довге імʼя: рід
     * возиться в кожному кадрі, а вгадати з двох літер нічого не можна.
     *
     * [webField] — імʼя поля, з якого стрічка бере ВМІСТ картки. Класти тіло в
     * `body` не можна: бандл малює віджет лише коли поруч із `type` лежить його
     * власне поле (`type==="widget:kanban"&&x.kanbanData`), а без нього рядок
     * стає порожньою бульбашкою з годинником — тобто карткою, яка доїхала й не
     * видно. Тринадцять імен зчитано з самого бандла
     * (`assets/webmsgr/assets/MessengerLayout-*.js`), а не з наміру, і живуть
     * вони ТУТ, поруч із [webType]: два переліки слів веба про ту саму картку в
     * двох місцях розійшлися б на першому ж перезбиранні.
     */
    enum class Card(val mark: String, val webType: String, val webField: String) {
        CANVAS("cv", "widget:canvas", "canvasData"),
        KANBAN("kb", "widget:kanban", "kanbanData"),
        VOTING("vo", "widget:voting", "votingData"),
        RACI("rc", "widget:raci", "raciData"),
        TIMELINE("tl", "widget:timeline", "timelineData"),
        CODE_RUNNER("cr", "widget:code-runner", "codeRunnerData"),
        FAMILY_CHECKLIST("fc", "widget:family-checklist", "familyChecklistData"),
        FAMILY_EXPENSE("fe", "widget:family-expense", "familyExpenseData"),
        STUDY_FLASHCARDS("sf", "widget:study-flashcards", "studyFlashcardsData"),
        ASYNC_SNIPPET("as", "snippet:async", "asyncSnippetData"),
        MERMAID("mm", "embed:mermaid", "mermaidData"),
        DIFF("df", "embed:diff", "codeDiffData"),
        SVG("sv", "embed:svg", "svgPreviewData"),
        /**
         * Таблиця з «Що надіслати». Рід `table` веб уже малює (той самий, яким
         * міст віддає рідну `PH-TABLE:`), тож поле `tableData` — не нове слово.
         */
        TABLE("tb", "table", "tableData"),

        /**
         * Зустріч і рахунок (збірка 18, `docs/scene/MEETUP_CONTRACT.md` §1).
         * Тіло — `MeetupData` / `BillData` з `types/meetup.ts`: складає й читає
         * лише веб, тут воно їде нерозібраним, як і всі інші. Змінний стан
         * людей на цих картках — не в тілі, а позначками [PeerMark.What.CARD_STATE].
         */
        MEETUP("mu", "widget:meetup", "meetupData"),
        BILL("bl", "widget:bill", "billData"),

        /** Людина зі своїх: імʼя й вузол; отримувач шукає її серед своїх людей. */
        PERSON("pp", "widget:person", "personData"),

        /** Подарунок із власного каталогу: рід і короткий підпис, без грошей і балів. */
        GIFT("gf", "widget:gift", "giftData"),

        /** День від помічника: тіло — лише дата, пункти веб бере живими з Дня. */
        DAY("dy", "widget:day", "dayData"),

        /** Людина одним поглядом від помічника: знімок того, що між вами, на час питання. */
        GLANCE("gl", "widget:glance", "glanceData"),

        /** Гроші від помічника: баланс, перекази за тиждень і відкриті частки рахунків. */
        MONEY("mn", "widget:money", "moneyData"),
        ;

        companion object {
            fun of(mark: String): Card? = entries.firstOrNull { it.mark == mark }

            /**
             * Слово, яким це називає веб. Закритий словник: рід, якого тут
             * немає, НЕ їде — замість того, щоб поїхати чимось іншим і стати
             * не тією карткою на тому кінці.
             */
            fun ofWeb(type: String): Card? = entries.firstOrNull { it.webType == type }
        }
    }

    /**
     * [payload] — тіло картки, яке склав веб, слово в слово.
     *
     * Дріт його не читає й не має читати: рід — єдине, від чого тут залежить
     * поведінка, а зміст належить тому, хто картку малює.
     */
    data class Body(val what: Card, val payload: String)

    // ── складання ────────────────────────────────────────────────────────

    /**
     * Null — коли тіло порожнє або не влазить у [MAX_CARD_CHARS]. Обидві
     * відмови видимі викликачеві ДО сокета: інакше картка «просто не доїхала».
     */
    fun encode(body: Body): String? {
        if (body.payload.isEmpty()) return null
        val encoded = java.util.Base64.getEncoder().withoutPadding()
            .encodeToString(body.payload.toByteArray(Charsets.UTF_8))
        if (encoded.length > MAX_CARD_CHARS) return null
        return TAG + body.what.mark + SEP + encoded
    }

    // ── читання ──────────────────────────────────────────────────────────

    /** Кадр картки. Ним мовчать стрічка й лічильник непрочитаного. */
    fun isCard(body: String): Boolean = body.startsWith(TAG)

    /**
     * Тіло розбирається САМЕ ТУТ, а не в того, хто його показує: base64 з
     * дроту може бути чим завгодно, і місце, де він стає рядком, має бути одне.
     * Порожнє тіло — це не картка, а `null`, як і покалічене, як і рід, якого
     * цей бік не знає.
     */
    fun decode(body: String): Body? {
        if (!isCard(body)) return null
        val parts = body.removePrefix(TAG).split(SEP, limit = 2)
        if (parts.size != 2) return null
        val what = Card.of(parts[0]) ?: return null
        val raw = parts[1].takeIf { it.isNotEmpty() && it.length <= MAX_CARD_CHARS } ?: return null
        val payload = runCatching {
            String(java.util.Base64.getDecoder().decode(raw), Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return Body(what, payload)
    }

    /** Картки, які складає лише помічник цього апарата: від людини це підробка «фактів» про мене. */
    val ASSISTANT_ONLY: Set<Card> = setOf(Card.DAY, Card.GLANCE, Card.MONEY)

    /** Такий лист від людини (і в групі теж) не лягає в журнал. */
    fun isAssistantOnly(body: String): Boolean {
        val wire = (PeerGroup.decode(body)?.text ?: body).trim()
        if (!isCard(wire)) return false
        return Card.of(wire.removePrefix(TAG).substringBefore(SEP)) in ASSISTANT_ONLY
    }
}
