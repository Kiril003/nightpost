package local.phantom.companion.core.net.peer

/**
 * Минуле гілки, переказане тим, хто в ній уже був.
 *
 * Підпис листа накриває `id|fromId|toId|sentAtMs|body` ([PeerSealing]), тобто
 * АДРЕСАТА. Копія, переказана комусь третьому, підпису автора нести не може за
 * побудовою — і жодна хитрість цього не обійде. Тому переказ тут чесний рівно
 * настільки, наскільки чесний той, хто переказує: він свідчить «я бачив ось це»
 * власним підписом на пакеті, і більше нічого.
 *
 * З цього випливає все інше в цьому файлі:
 *
 *  * переказаний рядок — це [Told], а не [PeerMessage], і різниця не в назві.
 *    [PeerMessage] має [PeerMessage.State], у якому є `DELIVERED`; варто було б
 *    покласти переказ у чергу — і найближчий екран сказав би «доставлено» про
 *    рядок, якого ця пара телефонів ніколи одне одному не посилала. Стану тут
 *    немає не тому, що його забули, а тому, що сказати про нього нічого;
 *  * хто переказав, береться з КОНВЕРТА ([PeerMessage.fromId]), а не з поля
 *    пакета: поле пакета склав би той самий телефон, що й пакет, і приписав би
 *    переказ комусь іншому;
 *  * сам пакет приходить звичайним листом і лежить у журналі листом. Тож
 *    переказане переживає перезапуск, не стаючи при цьому реплікою.
 */
object PeerHistory {

    internal const val PACK = "PH-HIST:"

    /**
     * Прохання про минуле їде ТІЛОМ групового листа, як і дії над складом:
     * інакше його нікуди адресувати — гілка не має місця, є лише люди в ній.
     * Дією ([PeerGroup.Act]) воно не є й не стане: склад ним не міняється, а
     * `decodeAct` на `hist` дає null, тобто [PeerGroup.isMachineLine] ховає це
     * від стрічки й від лічильника без жодного окремого правила.
     */
    internal const val ASK = PeerGroup.SYS + "hist"

    private const val SEP = ':'
    private const val FIELD = '|'

    /**
     * Стеля пакета виводиться зі стелі журналу, а не пишеться руками: переказ
     * на 600 рядків приїхав би й тут-таки поліг би під [PeerOutbox.prune], і
     * побачити це було б ніде.
     */
    const val MAX_LINES = PeerOutbox.MAX_PER_PEER

    /**
     * Скільки важить ОДИН пакет. Середня корзина сховка ([PeerRelay.BUCKETS]),
     * а не [PeerWire.MAX_BODY_BYTES]: пакет, що влазить у дріт, але не влазить
     * у сховок, доносив би минуле лише тим, хто стоїть у тій самій кімнаті.
     */
    const val PACK_MAX_BYTES = 16_384

    /** Рядок так, як він летить: без стану, без підпису, без адресата. */
    data class Line(val id: String, val fromId: String, val sentAtMs: Long, val text: String)

    /**
     * Той самий рядок на телефоні, що його прийняв: через кого й коли.
     *
     * [viaAtMs] — мить ПЕРЕКАЗУ, не мить сказаного. Свідчення старіє: «Оля
     * переказала це вчора» і «місяць тому» — різні за вагою, а [Line.sentAtMs]
     * про це не каже нічого, бо він про сам рядок.
     */
    data class Told(val line: Line, val viaId: String, val viaAtMs: Long)

    data class Pack(val groupId: String, val lines: List<Line>)

    fun isPack(body: String): Boolean = body.startsWith(PACK)

    fun encodeAsk(sinceMs: Long): String = ASK + SEP + sinceMs.coerceAtLeast(0L)

    /** Мить, раніше за яку минуле не просять, або null — це не прохання. */
    fun decodeAsk(text: String): Long? {
        if (!text.startsWith(ASK + SEP)) return null
        return text.removePrefix(ASK + SEP).trim().toLongOrNull()?.takeIf { it >= 0L }
    }

    fun isAsk(body: String): Boolean = PeerGroup.decode(body)?.text?.let { decodeAsk(it) } != null

    /**
     * Що взагалі можна переказати: згорнуті рядки гілки, сказані людьми.
     *
     * Службові рядки не переказуються навмисно. «Оля додала Тараса» в переказі
     * стало б подією без часу й без наслідку — склад новачок однаково перечитає
     * із заголовка кожного листа, а стрічка отримала б рядки, яких на жодному
     * іншому телефоні в цьому місці немає.
     */
    fun linesOf(log: List<PeerMessage>, groupId: String, sinceMs: Long = 0L): List<Line> =
        PeerGroup.collapse(log, groupId)
            .filterNot { PeerGroup.isSystem(PeerGroup.decode(it.body)?.text.orEmpty()) }
            // Канал може бути замкненим, а хто його читає, знає лише простір.
            .filterNot { PeerGroup.isChannelLine(PeerGroup.decode(it.body)?.text.orEmpty()) }
            .filter { it.sentAtMs >= sinceMs }
            .mapNotNull { m ->
                PeerGroup.decode(m.body)?.text
                    ?.takeIf { it.isNotBlank() }
                    ?.let { Line(m.id, m.fromId, m.sentAtMs, it) }
            }
            .sortedBy { it.sentAtMs }
            .takeLast(MAX_LINES)

    /**
     * Пакетів може бути кілька, і це не «шматки»: кожен читається сам собою.
     *
     * Шматок файла ([PeerFile.CHUNK]) без сусідів — сміття; половина переказу —
     * половина переказу, і показати її чесніше, ніж не показати нічого, поки
     * другий пакет лежить у сховку.
     */
    fun packs(groupId: String, lines: List<Line>): List<String> {
        if (groupId.isBlank() || lines.isEmpty()) return emptyList()
        val head = PACK + groupId + SEP
        val out = ArrayList<String>()
        var current = StringBuilder(head)
        var count = 0
        // takeLast, не take: минуле, у якому бракує вчорашнього, — не минуле.
        for (line in lines.takeLast(MAX_LINES)) {
            val field = b64(encodeLine(line).toByteArray(Charsets.UTF_8))
            // Порожній пакет не народжується: рядок, довший за цілу корзину,
            // їде сам і буде відкинутий на прийманні — але видно, що він був.
            if (count > 0 && current.length + 1 + field.length > PACK_MAX_BYTES) {
                out += current.toString()
                current = StringBuilder(head)
                count = 0
            }
            if (count > 0) current.append(SEP)
            current.append(field)
            count++
        }
        if (count > 0) out += current.toString()
        return out
    }

    /**
     * Межі стоять тут, на ПРИЙМАННІ, бо пакет складав чужий телефон: рядків не
     * більше за стелю журналу, поле — база64url і нічого іншого, зіпсоване поле
     * викидається саме, не забираючи з собою пакет.
     */
    fun decode(body: String): Pack? {
        if (!isPack(body)) return null
        val rest = body.removePrefix(PACK)
        val cut = rest.indexOf(SEP)
        if (cut <= 0) return null
        val groupId = rest.substring(0, cut).trim()
        if (groupId.isEmpty() || !groupId.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return null
        val lines = rest.substring(cut + 1)
            .split(SEP)
            .asSequence()
            .filter { it.isNotBlank() }
            .take(MAX_LINES)
            .mapNotNull { field -> runCatching { String(unB64(field), Charsets.UTF_8) }.getOrNull() }
            .mapNotNull(::decodeLine)
            .toList()
        if (lines.isEmpty()) return null
        return Pack(groupId, lines)
    }

    /**
     * Пакет, прийнятий листом. [PeerMessage.fromId] — єдине джерело того, ЧЕРЕЗ
     * КОГО переказано: воно під підписом, а поле в тілі було б словом пакета
     * про самого себе.
     */
    fun open(message: PeerMessage): List<Told> {
        val pack = decode(message.body) ?: return emptyList()
        val via = message.fromId.takeIf { it.isNotBlank() } ?: return emptyList()
        return pack.lines.map { Told(it, via, message.sentAtMs) }
    }

    /**
     * Усе переказане про цю гілку — без того, що телефон має власним листом.
     *
     * Дедуп двоключовий, і другий ключ не окраса. Ідентифікатор рядка у групі
     * НЕ спільний: [PeerMesh.sendToGroup] дає кожній копії свій `id`, тож Оля
     * перекаже той самий сказаний рядок під іншим ідентифікатором, ніж Тарас.
     * Другий ключ — той самий, яким [PeerGroup.collapse] згортає власний віяр:
     * автор, мить і слова.
     */
    fun retold(log: List<PeerMessage>, groupId: String): List<Told> {
        val mine = PeerGroup.collapse(log, groupId)
        val haveIds = mine.map { it.id }.toHashSet()
        val haveSaid = mine.mapNotNull { m ->
            PeerGroup.decode(m.body)?.text?.let { Triple(m.fromId, m.sentAtMs, it) }
        }.toHashSet()
        val seenIds = HashSet<String>()
        val seenSaid = HashSet<Triple<String, Long, String>>()
        return log
            .filter { isPack(it.body) }
            .sortedBy { it.sentAtMs }
            .flatMap { open(it) }
            .filter { told ->
                val said = Triple(told.line.fromId, told.line.sentAtMs, told.line.text)
                told.line.id !in haveIds &&
                    said !in haveSaid &&
                    seenIds.add(told.line.id) &&
                    seenSaid.add(said)
            }
            .sortedBy { it.line.sentAtMs }
            .takeLast(MAX_LINES)
    }

    private fun encodeLine(line: Line): String =
        line.id.replace(FIELD, '-') + FIELD +
            line.fromId.replace(FIELD, '-') + FIELD +
            line.sentAtMs + FIELD +
            line.text

    private fun decodeLine(raw: String): Line? {
        val f = raw.split(FIELD, limit = 4)
        if (f.size != 4) return null
        val id = f[0].trim().takeIf { it.isNotEmpty() } ?: return null
        val fromId = f[1].trim().takeIf { it.isNotEmpty() } ?: return null
        val at = f[2].trim().toLongOrNull()?.takeIf { it > 0L } ?: return null
        val text = f[3].takeIf { it.isNotBlank() } ?: return null
        return Line(id, fromId, at, text)
    }

    private fun b64(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun unB64(text: String): ByteArray =
        java.util.Base64.getUrlDecoder().decode(text)
}
