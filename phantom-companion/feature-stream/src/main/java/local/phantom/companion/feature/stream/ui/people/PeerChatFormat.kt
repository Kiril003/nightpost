package local.phantom.companion.feature.stream.ui.people

import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerName
import local.phantom.companion.core.net.peer.PeerOutbox
import local.phantom.companion.core.net.peer.PeerLive
import local.phantom.companion.core.net.peer.PeerPayload
import java.util.Calendar

/**
 * The shape of a conversation, worked out before anything is drawn.
 *
 * A chat is not a list of messages: it is days, and inside a day, runs of
 * things one person said together. Getting that grouping wrong is what makes
 * a homemade messenger feel homemade — a timestamp under every line, a name
 * repeated six times, no idea which day «14:20» belongs to.
 *
 * Pure, so the rules are provable without a screen.
 */
internal object PeerChatFormat {

    /** Messages closer than this from the same author read as one breath. */
    const val GROUP_WINDOW_MS = 4 * 60_000L

    sealed interface Row {
        data class DayBreak(val atMs: Long) : Row

        /**
         * «Нове» — where the operator stopped reading last time.
         *
         * Without it, opening a chat with eleven unread messages drops you at
         * the bottom with no idea where your eyes should start. It is drawn
         * once, above the first thing said after [PeerIdentity.readUpToMs],
         * and only when that boundary is inside the conversation.
         */
        data object NewMark : Row
        data class Bubble(
            val message: PeerMessage,
            val mine: Boolean,
            /** First of a run — the one that carries the corner and the name. */
            val startsRun: Boolean,
            /** Last of a run — the only one that shows time and state. */
            val endsRun: Boolean,
        ) : Row

        data class Note(val note: ChatNote) : Row
    }

    /**
     * What happened TO the conversation, as opposed to in it.
     *
     * Two facts had nowhere to be said. A history that opens mid-air gives the
     * operator no ground: they cannot tell a chat that has been quiet for a
     * month from one that started this morning. And a key that changed under a
     * known name — the single event the six digits exist to catch — was written
     * down by the roster and shown by nobody.
     */
    sealed interface ChatNote {
        val atMs: Long

        /** Where the history begins, and what the key was worth at that moment. */
        data class Added(override val atMs: Long, val verifiedAtMs: Long) : ChatNote

        data class KeyChanged(override val atMs: Long) : ChatNote
    }

    fun rows(
        messages: List<PeerMessage>,
        meId: String,
        /** Nothing is marked new when this is 0 — a first-ever chat has no «нове». */
        readUpToMs: Long = 0L,
        notes: List<ChatNote> = emptyList(),
    ): List<Row> {
        // «Додано» grounds the whole history, so it sits above even the first
        // day break; everything else is an event with a place in the flow.
        val out = mutableListOf<Row>()
        notes.filterIsInstance<ChatNote.Added>().forEach { out += Row.Note(it) }
        val timed = notes.filterNot { it is ChatNote.Added }.sortedBy { it.atMs }
        if (messages.isEmpty()) {
            timed.forEach { out += Row.Note(it) }
            return out
        }
        val sorted = messages.sortedBy { it.sentAtMs }
        // A note lands ABOVE the first message not older than it, so the index
        // it precedes is known before the run grouping is decided — otherwise a
        // key-change line would be drawn through the middle of one block.
        val noteAt = timed.groupBy { note ->
            sorted.indexOfFirst { it.sentAtMs >= note.atMs }.takeIf { it >= 0 } ?: sorted.size
        }
        // The mark belongs above the first thing THEY said past the boundary:
        // one's own messages are read by definition, and a «нове» line sitting
        // over your own sentence is the tell of a messenger nobody trusts.
        val firstUnread = sorted.firstOrNull {
            it.fromId != meId && readUpToMs > 0L && it.sentAtMs > readUpToMs
        }
        sorted.forEachIndexed { i, message ->
            val earlier = sorted.getOrNull(i - 1)
            val after = sorted.getOrNull(i + 1)
            if (earlier == null || !sameDay(earlier.sentAtMs, message.sentAtMs)) {
                out += Row.DayBreak(message.sentAtMs)
            }
            noteAt[i]?.forEach { out += Row.Note(it) }
            val prev = earlier?.takeIf { noteAt[i] == null }
            if (message.id == firstUnread?.id) out += Row.NewMark
            val mine = message.fromId == meId
            out += Row.Bubble(
                message = message,
                mine = mine,
                startsRun = prev == null || !continues(prev, message),
                // A state that changed must be shown even mid-run: «не
                // вдалось» hidden because another line followed it is the
                // one thing this screen exists to never do.
                endsRun = after == null || noteAt[i + 1] != null ||
                    !continues(message, after) || after.state != message.state,
            )
        }
        noteAt[sorted.size]?.forEach { out += Row.Note(it) }
        return out
    }

    /**
     * Куди стати, відкриваючи розмову.
     *
     * Екран завжди їхав у самий низ — і межа «нове», заради якої [Row.NewMark]
     * взагалі рахується, лишалась над краєм. Одинадцять непрочитаних не мають
     * початку, якщо погляд починається з останнього.
     */
    fun landing(rows: List<Row>): Int {
        if (rows.isEmpty()) return 0
        val mark = rows.indexOfFirst { it is Row.NewMark }
        return if (mark >= 0) mark else rows.lastIndex
    }

    /**
     * Скільки чужих рядків лежить нижче видимого краю — те, що каже пігулка
     * замість того, щоб висмикувати читача з середини речення.
     *
     * Власні листи не рахуються: людина знає, що написала сама.
     */
    fun newBelow(rows: List<Row>, lastVisible: Int): Int =
        rows.withIndex().count { (i, row) -> i > lastVisible && row is Row.Bubble && !row.mine }

    /**
     * Що саме лягає в буфер обміну.
     *
     * Не [PeerMessage.body]: груповий рядок їде в конверті зі складом групи, і
     * копіювання сирого тіла вклало б людині в буфер «PH-GRP:…» замість
     * речення. Місце — пара координат, яку приймає будь-яка навігація, а не
     * посилання на чужу мапу.
     */
    fun copyText(message: PeerMessage): String {
        val said = PeerGroup.decode(message.body)?.text ?: message.body
        val place = PeerPayload.decodePlace(said)
        return place?.let { "%.5f, %.5f".format(java.util.Locale.US, it.lat, it.lon) }
            ?: said.trim()
    }

    private fun continues(a: PeerMessage, b: PeerMessage): Boolean =
        a.fromId == b.fromId &&
            sameDay(a.sentAtMs, b.sentAtMs) &&
            b.sentAtMs - a.sentAtMs < GROUP_WINDOW_MS

    fun sameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    /** «сьогодні» / «вчора» / a date — resolved by the caller into words. */
    enum class DayLabel { TODAY, YESTERDAY, OLDER }

    fun dayLabel(atMs: Long, nowMs: Long): DayLabel = when {
        sameDay(atMs, nowMs) -> DayLabel.TODAY
        sameDay(atMs, nowMs - 86_400_000L) -> DayLabel.YESTERDAY
        else -> DayLabel.OLDER
    }

    /**
     * What a roster row should say about a person.
     *
     * The row used to show the key fingerprint under every name forever. That
     * is the right thing to show exactly once — while two people verify each
     * other — and noise every day after: a list of hashes tells the operator
     * nothing about what happened. So the key stays until there is something
     * said, and then the last line takes its place.
     */
    data class RosterLine(
        /** Null while the conversation is empty — the row shows the key then. */
        val preview: String?,
        val mine: Boolean,
        val atMs: Long,
        val unread: Int,
        /**
         * What the last line WAS. A place is not words: rendering its wire form
         * put «PH-LOC:50.44020,30.51950;25» on the roster row, which is the
         * protocol leaking into a list of people. The row asks for a word and
         * the screen supplies a localised one.
         */
        val kind: Kind = Kind.TEXT,
    )

    enum class Kind { TEXT, PLACE, ROUTE }

    fun rosterLine(
        messages: List<PeerMessage>,
        peerId: String,
        meId: String,
        readUpToMs: Long,
    ): RosterLine {
        val last = PeerOutbox.lastLine(messages, peerId)
        val place = last?.body?.let { PeerPayload.isPlace(it) } == true
        // Сирий кадр маршруту в рядку реєстру — це протокол, що кричить на
        // людину, і саме той рядок, який вона бачить першим.
        val route = last?.body?.let { PeerLive.isRoute(it) } == true
        return RosterLine(
            preview = if (place || route) {
                null
            } else {
                last?.body?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }
            },
            mine = last != null && last.fromId == meId,
            atMs = last?.sentAtMs ?: 0L,
            unread = PeerOutbox.unread(messages, peerId, readUpToMs),
            kind = when {
                place -> Kind.PLACE
                route -> Kind.ROUTE
                else -> Kind.TEXT
            },
        )
    }

    /**
     * Whose rows must also show their key, because the name alone does not
     * say which person they are.
     *
     * Two people with one name is not an edge case — it is families, it is
     * «Мама», it is two Олегів at work, and it is every device somebody named
     * after its model. A list that renders both identically invites the
     * operator to send the wrong person the wrong thing, and the roster HAS
     * the fact that separates them.
     *
     * Case- and space-insensitive: «Олег» and «олег » read as the same name to
     * a human, so they must count as a collision here too.
     */
    fun ambiguous(names: List<Pair<String, String>>): Set<String> {
        val counts = names.groupingBy { normalizeName(it.second) }.eachCount()
        return names.filter { counts[normalizeName(it.second)]!! > 1 }.map { it.first }.toSet()
    }

    private fun normalizeName(name: String): String =
        name.trim().replace(Regex("\\s+"), " ").lowercase()

    /**
     * The roster in one order, and it is deliberately not presence.
     *
     * Presence is weather and relationships are geography. Sorting — or
     * sectioning — on reachability moves a row under the thumb every time
     * somebody else's phone leaves a Wi-Fi, which costs muscle memory exactly
     * when it is worth most: one hand, in a hurry, in the dark. Only the two
     * people change this order.
     */
    fun ordered(
        roster: List<PeerIdentity>,
        lastActivityMs: (PeerIdentity) -> Long,
    ): List<PeerIdentity> = roster.sortedWith(
        compareByDescending<PeerIdentity> { lastActivityMs(it) }
            .thenByDescending { it.addedAtMs }
            .thenBy { it.name.lowercase() },
    )

    /** A subsequence of [ordered] — the filter never rearranges what it keeps. */
    fun reachable(ordered: List<PeerIdentity>, nowMs: Long): List<PeerIdentity> =
        ordered.filter { it.isLive(nowMs) }

    /**
     * What the row says about the key. Unverified is a state to be seen, not
     * an ornament to be missed: it used to be the mere ABSENCE of a thin ring.
     *
     * Two ticks, not one, because two different things are true. [IN_PERSON] is
     * a key the camera took off their screen and digits that then matched — a
     * channel nobody could stand in. [MATCHED] is digits that matched on a key
     * that travelled through some other app: it proves both phones hold the
     * same key, and it cannot prove whose key it is.
     */
    enum class Trust {
        CHANGED,
        UNVERIFIED,
        MATCHED,
        IN_PERSON,
        ;

        /** A person said the digits matched. Says nothing about the channel. */
        val verified: Boolean get() = this == MATCHED || this == IN_PERSON
    }

    // A key that moved outranks a tick earned by the key it replaced.
    fun trust(peer: PeerIdentity): Trust = when {
        peer.keyAlarm -> Trust.CHANGED
        peer.isProven -> Trust.IN_PERSON
        peer.isVerified -> Trust.MATCHED
        else -> Trust.UNVERIFIED
    }

    /**
     * Which sentence the verification surfaces owe this peer.
     *
     * Split off [Trust] because the tick has two tiers and the honest wording
     * has five: a key that arrived by camera but has not been read aloud yet
     * deserves good news and a nudge, while a row verified before the app
     * recorded provenance must not be told how its key travelled — nobody
     * knows, and guessing either way would be the same lie in a new place.
     */
    enum class Why { CHANGED, SCANNED, HANDED, ELSEWHERE, IN_PERSON, MATCHED, MATCHED_UNKNOWN }

    fun why(peer: PeerIdentity): Why {
        val source = peer.keySource
        return when {
            peer.keyAlarm -> Why.CHANGED
            !peer.isVerified && source == PeerIdentity.KeySource.CAMERA -> Why.SCANNED
            // A row that appeared because some other app shared a string is the
            // one intake nobody consented to line by line, so it says so once —
            // while it is still unverified and the sentence can change anything.
            !peer.isVerified && source == PeerIdentity.KeySource.SHARED -> Why.HANDED
            !peer.isVerified -> Why.ELSEWHERE
            source == PeerIdentity.KeySource.CAMERA -> Why.IN_PERSON
            source == PeerIdentity.KeySource.UNKNOWN -> Why.MATCHED_UNKNOWN
            else -> Why.MATCHED
        }
    }

    /**
     * Past this many contacts a list stops being scannable and needs a field.
     * Below it, a search box is one more thing on screen doing nothing.
     */
    const val SEARCH_THRESHOLD = 7

    /**
     * Finds a person by name OR by key. The key matters: two namesakes are told
     * apart by it everywhere else in this screen, so it must also be the thing
     * you can type. Spaces and the separator dot are ignored — nobody types
     * «a1b2·c3d4» by hand.
     */
    fun search(roster: List<PeerIdentity>, query: String): List<PeerIdentity> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return roster
        val bare = q.filter { it.isLetterOrDigit() }
        return roster.filter { peer ->
            peer.name.lowercase().contains(q) ||
                (bare.isNotEmpty() && peer.id.lowercase().contains(bare))
        }
    }

    /** Past this the pill says «99+» rather than growing the row. */
    const val UNREAD_CAP = 99

    fun unreadBadge(count: Int): String? = when {
        count <= 0 -> null
        count > UNREAD_CAP -> "$UNREAD_CAP+"
        else -> count.toString()
    }

    /**
     * Чи сказати під листом, що він їхав без свіжого ключа зустрічі.
     *
     * Рядок говорить про НАШ лист, а не про чужу збірку. Відмову від
     * рукостискання не підписано нічим, тож будь-хто на шляху може її
     * підробити й змусити нас упасти на довічний ключ пари: тоді «у них стара
     * версія» було б брехнею, яка допомагає саме тому, хто це зробив, а «цей
     * лист поїхав без свіжого ключа» лишається правдою в обох випадках. Те
     * саме правило, що й у [why]: кажемо, що знаємо про себе.
     *
     * [PeerMessage.Sealing.UNKNOWN] — мовчання: так читаються всі рядки,
     * записані до того, як ключі зустрічі зʼявились, і вигадувати їм минуле
     * не можна.
     */
    fun withoutMeetingKey(message: PeerMessage): Boolean =
        message.sealing == PeerMessage.Sealing.PAIR_KEY

    /** Одна копія віяра: кому вона йшла і що з нею сталось. */
    data class FanRow(val toId: String, val messageId: String, val state: PeerMessage.State)

    /**
     * Хто саме отримав рядок, сказаний у групі, а хто ні.
     *
     * «2 з 4» чесне, але воно не називає тих двох. У групі з чотирьох це
     * різниця між «мережа підводить» і «Тарас не бачить жодного мого слова».
     */
    fun fanDetail(
        outbox: List<PeerMessage>,
        groupId: String,
        fromId: String,
        sentAtMs: Long,
    ): List<FanRow> = outbox
        .filter {
            it.fromId == fromId &&
                it.sentAtMs == sentAtMs &&
                PeerGroup.decode(it.body)?.group?.id == groupId
        }
        .map { FanRow(toId = it.toId, messageId = it.id, state = it.state) }

    /**
     * Учасники групи, яких немає в реєстрі цього телефона.
     *
     * Виводиться зі складу групи, а не з події втрати: чужий лист гине ще на
     * [local.phantom.companion.core.net.peer.PeerEnvelope], бо ключ каналу
     * нема з чого вивести — тож із самої втрати не видно НІ групи, ні того,
     * чи це взагалі учасник. Склад групи ж лежить у кожному повідомленні й
     * відповідає точно.
     */
    fun strangersInGroup(
        group: PeerGroup.Group,
        rosterIds: Set<String>,
        meId: String,
    ): List<String> = group.memberIds.filter { it != meId && it !in rosterIds }

    /** Що показати над відповіддю: чий лист і його перший рядок. */
    data class Quote(val author: String, val line: String)

    /**
     * Цитований лист може бути стертий згасанням або ще не дійти — тоді
     * відповідь лишається звичайним листом, а не порожньою рамкою.
     */
    fun quoteOf(
        messages: List<PeerMessage>,
        replyToId: String,
        meId: String,
        mineLabel: String,
        nameOf: (String) -> String,
    ): Quote? {
        if (replyToId.isBlank()) return null
        val target = messages.firstOrNull { it.id == replyToId } ?: return null
        val body = PeerGroup.decode(target.body)?.text ?: target.body
        val line = if (PeerPayload.isPlace(body) || PeerLive.isRoute(body)) {
            ""
        } else {
            body.trim().replace(Regex("\\s+"), " ")
        }
        return Quote(
            author = if (target.fromId == meId) mineLabel else nameOf(target.fromId),
            line = line,
        )
    }

    /** Two letters at most — a monogram, not an avatar nobody uploaded. */
    fun monogram(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        // По графемі, а не по знаку: `take(1)` над іменем, що починається з
        // емодзі, віддає половину сурогатної пари — у кружечку порожній квадрат.
        fun head(word: String, count: Int) =
            PeerName.graphemes(word).take(count).joinToString("")
        return when {
            words.isEmpty() -> "?"
            words.size == 1 -> head(words[0], 2).uppercase()
            else -> (head(words[0], 1) + head(words[1], 1)).uppercase()
        }
    }
}
