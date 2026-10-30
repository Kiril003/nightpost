package local.phantom.companion.ui.messenger

import java.time.Instant
import java.util.UUID
import local.phantom.companion.core.net.peer.PeerCard
import local.phantom.companion.core.net.peer.PeerFile
import local.phantom.companion.core.net.peer.PeerFrames
import local.phantom.companion.core.net.peer.PeerMark
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.data.SavedNotes

/**
 * Усі шляхи `/messenger/conversations/chat_saved_notes/…`.
 *
 * Жодної залежності від сітки, окрім [Journal] для одноразового переносу: лист
 * сюди не може поїхати дротом, бо дроту в цього класу немає. Міст віддає сюди
 * шлях ДО будь-якої іншої гілки — див. [claims].
 *
 * Без org.json: у юніт-тестах `:app` він заглушка, а вирок про форму відповіді
 * мусить бути перевірним.
 */
class WebMessengerSaved(
    private val notes: SavedNotes,
    private val me: () -> String,
    private val journal: Journal,
    private val placeFrame: (String) -> String?,
    private val isJson: (String) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** Журнал сітки — лише щоб забрати з нього те, що належить нотаткам. */
    interface Journal {
        fun lines(): List<PeerMessage>
        fun forget(lines: List<PeerMessage>)
    }

    data class Input(
        val kind: String,
        val text: String,
        val clientId: String = "",
        val replyToId: String = "",
        val sentAtMs: Long = 0L,
        val emoji: String = "",
    )

    sealed interface Reply {
        data class Row(val fields: Map<String, Any?>) : Reply
        data class Feed(val lines: List<Pair<Int, PeerMessage>>) : Reply
        data class Line(val seq: Int, val note: PeerMessage, val frame: String? = null) : Reply
        data class Done(val fields: Map<String, Any?>) : Reply
        data class Gone(val note: PeerMessage) : Reply
        data class Cleared(val count: Int) : Reply
        data class Refused(val status: Int, val code: String, val why: Why, val arg: String = "") : Reply
    }

    enum class Why { BAD_BODY, EMPTY, MEDIA, SERVICE_PREFIX, PLACE, CARD, CARD_TOO_BIG, KIND, NO_MESSAGE, NOT_WORDS, PERMANENT }

    @Volatile
    private var adopted = false

    fun handle(method: String, seg: List<String>, afterSeq: Int, input: Input?, imports: List<Input>?, title: String): Reply {
        adoptOnce()
        val tail = seg.drop(3)
        return when {
            tail.isEmpty() && method == "GET" -> Reply.Row(row(title))
            tail.isEmpty() -> Reply.Refused(409, "saved_is_permanent", Why.PERMANENT)
            tail == listOf("messages") && method == "GET" -> Reply.Feed(feed().filter { it.first > afterSeq })
            tail == listOf("messages") && method == "POST" -> append(input)
            tail == listOf("import") && method == "POST" -> import(imports)
            tail == listOf("read") -> Reply.Done(mapOf("unread_count" to 0))
            tail == listOf("clear") -> Reply.Cleared(notes.clear())
            tail.size == 2 && tail[0] == "messages" && method == "PATCH" -> edit(tail[1], input)
            tail.size == 3 && tail[0] == "messages" && tail[2] == "reactions" && method == "POST" -> react(tail[1], input)
            tail.size == 2 && tail[0] == "messages" && method == "DELETE" -> {
                notes.delete(tail[1])?.let { Reply.Gone(it) } ?: Reply.Refused(404, "no_message", Why.NO_MESSAGE)
            }
            else -> Reply.Refused(409, "saved_is_permanent", Why.PERMANENT)
        }
    }

    fun row(title: String): Map<String, Any?> {
        val all = notes.all()
        val last = WebMessengerKinds.feed(all).lastOrNull()
        val row = last?.let { WebMessengerKinds.rowFields(it.body) } ?: emptyMap()
        val att = last?.let { PeerFile.describe(it.body) }
        val snippet = when {
            last == null || !WebMessengerKinds.isSpokenKind(row["last_kind"]) -> null
            att != null -> att.caption.ifBlank { att.name }
            else -> last.body.trim().replace(SPACES, " ")
        }?.takeIf { it.isNotEmpty() }
        val fields = linkedMapOf<String, Any?>(
            "id" to SavedNotes.ID,
            "title" to title,
            "kind" to KIND,
            "circle" to CIRCLE,
            "handle" to null,
            "avatar" to null,
            "pinned" to true,
            "archived" to false,
            "created_at" to iso(all.firstOrNull()?.sentAtMs ?: 0L),
            "updated_at" to iso(last?.let { maxOf(it.sentAtMs, it.editedAtMs) } ?: 0L),
            "contact_id" to null,
            "peer_node_id" to null,
            "is_demo" to false,
            "contact_verified" to null,
            "contact_key_changed" to false,
            "unread_count" to 0,
            "last_kind" to row["last_kind"],
        )
        row.forEach { (key, value) -> if (key != "last_kind") fields[key] = value }
        fields["last_snippet"] = snippet
        fields["last_author"] = last?.let { "me" }
        fields["last_at"] = last?.let { iso(it.sentAtMs) }
        return fields
    }

    fun feed(): List<Pair<Int, PeerMessage>> =
        WebMessengerKinds.feed(notes.all()).mapIndexed { i, m -> i + 1 to mine(m) }

    /** Смайли під нотатками — той самий згортач позначок, що й у розмові з людиною. */
    fun reactions(): Map<String, Map<String, List<String>>> =
        PeerMark.foldAll(notes.all().map(::mine), PeerMark.What.REACTION)

    private fun react(id: String, input: Input?): Reply {
        val emoji = input?.emoji?.trim().orEmpty()
        if (emoji.isEmpty()) return Reply.Refused(400, "empty", Why.EMPTY)
        val target = notes.find(id)?.takeIf { WebMessengerKinds.feed(listOf(it)).isNotEmpty() }
            ?: return Reply.Refused(404, "no_message", Why.NO_MESSAGE)
        val on = !PeerMark.holds(notes.all().map(::mine), PeerMark.What.REACTION, id, emoji, mine(target).fromId)
        val frame = PeerMark.encode(PeerMark.Mark(PeerMark.What.REACTION, id, on, emoji))
            ?: return Reply.Refused(422, "bad_mark", Why.KIND, emoji)
        notes.add(
            PeerMessage(
                id = "note_mark_" + UUID.randomUUID().toString(),
                fromId = me(),
                toId = SavedNotes.ID,
                body = frame,
                sentAtMs = now(),
                state = PeerMessage.State.DELIVERED,
            ),
        )
        return Reply.Line(seqOf(id), mine(target))
    }

    private fun append(input: Input?): Reply {
        input ?: return Reply.Refused(400, "bad_body", Why.BAD_BODY)
        val note = compose(input) { it } ?: return refusal(input)
        val kept = notes.add(note)
        return Reply.Line(seqOf(kept.id), mine(kept))
    }

    private fun import(batch: List<Input>?): Reply {
        batch ?: return Reply.Refused(400, "bad_body", Why.BAD_BODY)
        val refused = mutableListOf<Map<String, Any?>>()
        val ready = batch.mapNotNull { input ->
            compose(input) { if (input.sentAtMs > 0L) input.sentAtMs else it } ?: run {
                refused += mapOf("client_id" to input.clientId, "code" to refusal(input).code)
                null
            }
        }
        val fresh = notes.adopt(ready)
        return Reply.Done(
            mapOf(
                "imported" to fresh.size,
                "skipped" to ready.size - fresh.size,
                "refused" to refused,
            ),
        )
    }

    private fun edit(id: String, input: Input?): Reply {
        val text = input?.text.orEmpty()
        if (text.isBlank()) return Reply.Refused(400, "empty", Why.EMPTY)
        val note = notes.find(id) ?: return Reply.Refused(404, "no_message", Why.NO_MESSAGE)
        if (!WebMessengerKinds.isSpokenKind(WebMessengerKinds.fields(note.body)[WebMessengerKinds.KIND])) {
            return Reply.Refused(409, "not_words", Why.NOT_WORDS)
        }
        if (PeerFrames.refuseTyped(text) != null || WebMessengerAi.isAi(text)) {
            return Reply.Refused(422, "service_prefix", Why.SERVICE_PREFIX)
        }
        val edited = notes.edit(id, text, now()) ?: return Reply.Refused(404, "no_message", Why.NO_MESSAGE)
        return Reply.Line(seqOf(id), mine(edited), frame = "local")
    }

    /**
     * Ті самі правила складання, що й для листа людині: інакше нотатка з картою
     * чи карткою лягла б сирим JSON, і стрічка малювала б її текстом.
     */
    private inline fun compose(input: Input, at: (Long) -> Long): PeerMessage? {
        val text = input.text
        if (text.isBlank()) return null
        val body = bodyOf(input) ?: return null
        val id = input.clientId.trim().ifEmpty { "note_" + UUID.randomUUID().toString() }
        return PeerMessage(
            id = id,
            fromId = me(),
            toId = SavedNotes.ID,
            body = body,
            sentAtMs = at(now()),
            replyToId = input.replyToId.takeIf { r -> r.isNotBlank() && notes.find(r) != null }.orEmpty(),
            state = PeerMessage.State.DELIVERED,
        )
    }

    private fun bodyOf(input: Input): String? {
        val text = input.text
        if (input.kind.trim() in MEDIA_KINDS) return keptMedia(text)
        return when (val carry = WebMessengerKinds.carry(input.kind.ifBlank { WebMessengerKinds.TEXT })) {
            WebMessengerKinds.Carry.Typed ->
                text.takeIf { PeerFrames.refuseTyped(it) == null && !WebMessengerAi.isAi(it) }
            WebMessengerKinds.Carry.Place -> placeFrame(text)
            is WebMessengerKinds.Carry.Card ->
                if (isJson(text)) PeerCard.encode(PeerCard.Body(carry.what, text)) else null
            is WebMessengerKinds.Carry.Unknown -> null
        }
    }

    /**
     * Голосове й кружок — це той самий опис вкладення, що й у фото: байти вже
     * зашифрував веб і поклав у сховок файлів під [SavedNotes.ID], а в тіло
     * ліг ключ. Людині такий лист не їде ніколи (дроту тут немає), тож
     * заслін «невідомий рід не їде голим тілом» ([WebMessengerKinds.carry])
     * тут не про що: тіло, що не є описом вкладення, однаково спиняється.
     */
    private fun keptMedia(text: String): String? = text.takeIf { PeerFile.describe(it.trim()) != null }?.trim()

    private fun refusal(input: Input): Reply.Refused {
        if (input.text.isBlank()) return Reply.Refused(400, "empty", Why.EMPTY)
        if (input.kind.trim() in MEDIA_KINDS) return Reply.Refused(422, "bad_media", Why.MEDIA)
        return when (val carry = WebMessengerKinds.carry(input.kind.ifBlank { WebMessengerKinds.TEXT })) {
            WebMessengerKinds.Carry.Typed -> Reply.Refused(422, "service_prefix", Why.SERVICE_PREFIX)
            WebMessengerKinds.Carry.Place -> Reply.Refused(422, "bad_place", Why.PLACE)
            is WebMessengerKinds.Carry.Card ->
                if (isJson(input.text)) Reply.Refused(413, "card_too_big", Why.CARD_TOO_BIG)
                else Reply.Refused(422, "bad_card", Why.CARD)
            is WebMessengerKinds.Carry.Unknown -> Reply.Refused(422, "kind_not_carried", Why.KIND, carry.kind)
        }
    }

    /**
     * Лист, що колись ліг у журнал сітки на адресу нотаток, переходить сюди. У
     * журналі він чекав би на дріт, якого для нього немає. Стирається з журналу
     * лише після запису сюди.
     */
    private fun adoptOnce() {
        if (adopted) return
        synchronized(this) {
            if (adopted) return
            val stray = journal.lines().filter { it.toId == SavedNotes.ID || it.fromId == SavedNotes.ID }
            if (stray.isNotEmpty()) {
                notes.adopt(stray)
                val kept = notes.all().mapTo(HashSet()) { it.id }
                journal.forget(stray.filter { it.id in kept })
            }
            adopted = true
        }
    }

    private fun mine(m: PeerMessage): PeerMessage = me().takeIf { it.isNotBlank() }?.let { m.copy(fromId = it) } ?: m

    private fun seqOf(id: String): Int =
        WebMessengerKinds.feed(notes.all()).indexOfFirst { it.id == id }.let { if (it < 0) 1 else it + 1 }

    companion object {
        const val KIND = "saved"

        /**
         * Роди, які «Збережене» тримає як вкладення, хоч людині вони не
         * їдуть (див. [WebMessengerKinds.carry]): записане самому собі
         * голосове чи кружок — та сама нотатка, що й фото.
         */
        val MEDIA_KINDS = setOf("voice", "video-note")
        const val CIRCLE = "saved"

        /** Лист не покидав апарата — те саме слово, що й [WebMessengerKinds.RELAY_LOCAL]. */
        const val ROAD = WebMessengerKinds.RELAY_LOCAL

        fun claims(seg: List<String>): Boolean =
            seg.size >= 3 && seg[1] == "conversations" && seg[2] == SavedNotes.ID

        private val SPACES = Regex("\\s+")

        private fun iso(ms: Long): String = Instant.ofEpochMilli(if (ms > 0) ms else 0).toString()
    }
}
