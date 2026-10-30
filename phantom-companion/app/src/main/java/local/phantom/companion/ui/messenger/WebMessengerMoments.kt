package local.phantom.companion.ui.messenger

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.net.peer.MomentBook
import local.phantom.companion.core.net.peer.MomentWire
import local.phantom.companion.core.net.peer.PeerFile
import local.phantom.companion.core.net.peer.PeerMessage

/**
 * `/messenger/moments` — «мій день» як власна сутність ([MomentWire], [MomentBook]):
 * створити, правити підпис, видалити, аудиторія, доба або «залишити», хто бачив, смайли.
 */
class WebMessengerMoments(private val host: Host, private val now: () -> Long = System::currentTimeMillis) {

    interface Host {
        fun me(): String
        fun people(): List<Person>
        fun book(): MomentBook
        fun save(book: MomentBook)
        fun send(toId: String, body: String): PeerMessage?
        fun sendBytes(toId: String, attachment: PeerFile.Attachment)
        fun assemble(fromId: String, media: JsonObject)
        fun state(conversationId: String, attachment: PeerFile.Attachment): String
        fun dropBytes(conversationId: String, blobId: String)
        fun log(): List<PeerMessage>
        fun forget(ids: Set<String>)
        fun seen(): Set<String>
        fun markSeen(id: String)
        fun prefs(): String?
        fun savePrefs(raw: String)
        fun muted(): Set<String>
        fun setMuted(ids: Set<String>)
        fun trace(line: String)
        fun reaches(peerId: String): Boolean = true
        fun newId(): String = "m" + PeerFile.newBlobId().take(16)
    }

    data class Person(val id: String, val name: String)

    private val lock = Any()

    private inline fun <T> edit(f: (MomentBook) -> Pair<MomentBook, T>): T = synchronized(lock) {
        val (next, out) = f(host.book())
        host.save(next)
        out
    }

    fun claims(seg: List<String>): Boolean = seg.size >= 2 && seg[1] == "moments"

    suspend fun handle(method: String, seg: List<String>, body: String?): String = runCatching {
        val id = seg.getOrNull(2).orEmpty()
        when {
            seg.size == 2 && method == "GET" -> ok(list())
            seg.size == 2 && method == "POST" -> create(body)
            seg.size == 3 && id == "prefs" && method == "GET" -> ok(prefs())
            seg.size == 3 && id == "prefs" && method == "PUT" -> putPrefs(body)
            seg.size == 4 && id == "highlights" && method == "GET" -> ok(highlightRows(seg[3]))
            seg.size == 4 && id == "mute" && method == "POST" -> mute(seg[3], body)
            seg.size == 4 && id == "revoke" && method == "POST" -> revoke(seg[3])
            seg.size == 3 && method == "PATCH" -> editMoment(id, body)
            seg.size == 3 && method == "DELETE" -> delete(id)
            seg.size == 4 && seg[3] == "seen" && method == "POST" -> seen(id)
            seg.size == 4 && seg[3] == "react" && method == "POST" -> react(id, body)
            else -> fail(404, "not_found")
        }
    }.getOrElse { fail(500, "moments_error") }

    // ── читання ──────────────────────────────────────────────────────────

    private fun list(): JsonElement {
        val me = host.me()
        val names = host.people().associate { it.id to it.name }
        val seen = host.seen()
        val muted = host.muted()
        val live = host.book().live(now()).filter { it.moment.author == me || it.moment.author !in muted }
        host.trace("list live=${live.size} mine=${live.count { it.moment.author == me }}")
        return buildJsonArray { live.forEach { add(row(it, me, names, seen)) } }
    }

    private fun highlightRows(who: String): JsonElement {
        val me = host.me()
        val author = if (who == "me") me else who
        val names = host.people().associate { it.id to it.name }
        val book = host.book()
        return buildJsonArray {
            book.highlights(author).forEach { m -> book.get(m.author, m.id)?.let { add(row(it, me, names, host.seen())) } }
        }
    }

    fun highlights(author: String): List<MomentWire.Moment> = host.book().highlights(author)

    fun liveMoments(author: String): List<MomentWire.Moment> =
        host.book().live(now()).map { it.moment }.filter { it.author == author && !it.deleted }

    private fun row(e: MomentBook.Entry, me: String, names: Map<String, String>, seen: Set<String>): JsonObject {
        val m = e.moment
        val mine = m.author == me
        val dir = if (mine) OWN_DIR else m.author
        return buildJsonObject {
            put("id", m.id)
            put("from_id", m.author)
            put("from_name", if (mine) "" else names[m.author].orEmpty())
            put("mine", mine)
            put("at_ms", m.createdAtMs)
            put("edited_ms", if (m.editedAtMs > m.createdAtMs) m.editedAtMs else 0L)
            put("until_ms", m.untilMs)
            put("keep", m.kept)
            put("audience", m.audience.toJson())
            put("text", m.text)
            put("seen", mine || m.id in seen)
            put("to_count", if (mine) e.recipients.size else 0)
            if (mine) put("to", buildJsonArray { e.recipients.sorted().forEach { add(JsonPrimitive(it)) } })
            put("media", buildJsonArray {
                m.media.forEach { media ->
                    val att = PeerFile.describe(media.toString())
                    add(JsonObject(media + ("state" to JsonPrimitive(att?.let { host.state(dir, it) } ?: "absent"))))
                }
            })
            if (mine) {
                put("viewers", buildJsonArray {
                    e.viewers.entries.sortedByDescending { it.value }.forEach { (id, at) ->
                        add(buildJsonObject {
                            put("id", id)
                            put("name", names[id].orEmpty())
                            put("at_ms", at)
                            e.reactions[id]?.let { put("emoji", it) }
                        })
                    }
                })
                put("reactions", buildJsonObject { e.reactions.values.groupingBy { it }.eachCount().forEach { (k, n) -> put(k, n) } })
            } else {
                e.reactions[me]?.let { put("my_reaction", it) }
            }
        }
    }

    // ── аудиторія й налаштування ─────────────────────────────────────────

    private fun prefs(): JsonObject {
        val saved = runCatching { Json.parseToJsonElement(host.prefs() ?: "").jsonObject }.getOrNull()
        val aud = MomentWire.Audience.read(saved?.get("audience") as? JsonObject)
            ?.takeIf { it.reach != MomentWire.Reach.EVERYONE } ?: MomentWire.Audience(MomentWire.Reach.CONTACTS)
        return buildJsonObject {
            put("audience", aud.toJson())
            put("to", (saved?.get("to") as? JsonArray) ?: JsonArray(emptyList()))
            put("keep", (saved?.get("keep") as? JsonPrimitive)?.contentOrNull == "true")
            // Публічного профілю ще немає — «Усі» чесно ховається, а не вдає більше, ніж «Усі контакти».
            put("everyone", EVERYONE_AVAILABLE)
            put("muted", buildJsonArray { host.muted().forEach { add(JsonPrimitive(it)) } })
        }
    }

    private fun putPrefs(body: String?): String {
        val o = parse(body) ?: return fail(400, "bad_body")
        val aud = MomentWire.Audience.read(o["audience"] as? JsonObject) ?: return fail(400, "bad_audience")
        remember(aud, ids(o["to"]).orEmpty(), (o["keep"] as? JsonPrimitive)?.contentOrNull == "true")
        return ok(prefs())
    }

    private fun remember(aud: MomentWire.Audience, to: Collection<String>, keep: Boolean) {
        host.savePrefs(buildJsonObject {
            put("audience", aud.toJson())
            put("to", buildJsonArray { if (aud.reach != MomentWire.Reach.CONTACTS) to.forEach { add(JsonPrimitive(it)) } })
            put("keep", keep)
        }.toString())
    }

    private fun mute(peerId: String, body: String?): String {
        val on = (parse(body)?.get("on") as? JsonPrimitive)?.contentOrNull != "false"
        val cur = host.muted()
        host.setMuted(if (on) cur + peerId else cur - peerId)
        return ok(buildJsonObject { put("muted", on) })
    }

    private fun revoke(peerId: String): String {
        val target = peerId.trim()
        if (target.isEmpty()) return fail(400, "bad_body")
        val saved = runCatching { Json.parseToJsonElement(host.prefs() ?: "").jsonObject }.getOrNull()
        if (saved != null) {
            val aud = MomentWire.Audience.read(saved["audience"] as? JsonObject) ?: MomentWire.Audience(MomentWire.Reach.CONTACTS)
            val to = ids(saved["to"]).orEmpty() - target
            val keep = (saved["keep"] as? JsonPrimitive)?.contentOrNull == "true"
            remember(aud, to, keep)
        }
        val me = host.me()
        val at = now()
        val affected = edit { book ->
            var next = book
            val list = mutableListOf<MomentBook.Entry>()
            book.entries.values
                .filter { !it.moment.deleted && it.moment.author == me && target in it.recipients }
                .forEach { e ->
                    list += e
                    next = next.put(e.copy(recipients = e.recipients - target))
                }
            next to list
        }
        affected.forEach { e ->
            MomentWire.encode(MomentWire.Op.Del(e.moment.id, e.moment.rev + 1, at))?.let { host.send(target, it) }
            e.moment.media.mapNotNull { PeerFile.describe(it.toString()) }.forEach { host.dropBytes(target, it.blobId) }
        }
        return ok(buildJsonObject { put("revoked", true); put("count", affected.size) })
    }

    // ── свої моменти ─────────────────────────────────────────────────────

    private fun create(body: String?): String {
        val o = parse(body) ?: return fail(400, "bad_body")
        val aud = MomentWire.Audience.read(o["audience"] as? JsonObject) ?: return fail(400, "bad_audience")
        if (aud.reach == MomentWire.Reach.EVERYONE && !EVERYONE_AVAILABLE) return fail(422, "everyone_unavailable")
        val rawMedia = (o["media"] as? JsonArray).orEmpty()
        if (rawMedia.size > MomentWire.MEDIA_MAX) return fail(400, "too_many")
        val media = rawMedia.map { (it as? JsonObject)?.let(MomentWire::mediaOf) ?: return fail(400, "bad_media") }
        val atts = media.mapNotNull { PeerFile.describe(it.toString()) }
        fun dropUploaded() = atts.forEach { host.dropBytes(OWN_DIR, it.blobId) }
        val text = (o["text"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (text.length > MomentWire.TEXT_MAX) { dropUploaded(); return fail(400, "too_long") }
        if (media.isEmpty() && text.isEmpty()) return fail(400, "empty")
        val keep = (o["keep"] as? JsonPrimitive)?.contentOrNull == "true"
        val picked = ids(o["to"])
        val to = reachOf(aud, picked)
        if (aud.reach != MomentWire.Reach.ONLY_ME && to.isEmpty()) { dropUploaded(); return fail(422, "no_one") }
        val me = host.me()
        if (me.isBlank()) { dropUploaded(); return fail(503, "no_identity") }
        val at = now()
        val moment = MomentWire.Moment(
            id = host.newId(), author = me, media = media, text = text, audience = aud,
            untilMs = if (keep) 0L else at + MomentWire.DAY_MS, createdAtMs = at, editedAtMs = at, rev = 1,
        )
        val frame = MomentWire.encode(MomentWire.Op.Put(moment)) ?: run { dropUploaded(); return fail(400, "bad_moment") }
        val reached = to.filter { id -> host.send(id, frame)?.also { atts.forEach { a -> host.sendBytes(id, a) } } != null }
        if (to.isNotEmpty() && reached.isEmpty()) { dropUploaded(); return fail(503, "no_identity") }
        val entry = MomentBook.Entry(moment, recipients = reached.toSet())
        edit { it.put(entry) to Unit }
        remember(aud, picked.orEmpty(), keep)
        host.trace("post id=${moment.id} to=${reached.size} media=${media.size}")
        return ok(row(entry, me, host.people().associate { it.id to it.name }, emptySet()))
    }

    private fun own(id: String): MomentBook.Entry? =
        host.book().get(host.me(), id)?.takeIf { !it.moment.deleted }

    /** Хто отримає момент з цією аудиторією: лише ті, з ким є чат цього профілю. */
    private fun reachOf(aud: MomentWire.Audience, picked: Set<String>?): List<String> {
        val crowd = host.people().map { it.id }.filter(host::reaches)
        return when (aud.reach) {
            MomentWire.Reach.ONLY_ME -> emptyList()
            MomentWire.Reach.PEOPLE, MomentWire.Reach.CIRCLES -> crowd.filter { it in picked.orEmpty() }
            MomentWire.Reach.CONTACTS, MomentWire.Reach.EVERYONE -> crowd
        }
    }

    /**
     * PATCH: підпис і/або аудиторія. Нова аудиторія — новим людям кадр і байти, тим, хто лишився, —
     * новий кадр, а хто випав, отримує Del і втрачає момент разом із байтами при наступному обміні.
     */
    private fun editMoment(id: String, body: String?): String {
        val o = parse(body) ?: return fail(400, "bad_body")
        val newText = if ("text" in o) (o["text"] as? JsonPrimitive)?.contentOrNull?.trim() ?: return fail(400, "bad_body") else null
        val newAud = if ("audience" in o) MomentWire.Audience.read(o["audience"] as? JsonObject) ?: return fail(400, "bad_audience") else null
        if (newText == null && newAud == null) return fail(400, "bad_body")
        if (newText != null && newText.length > MomentWire.TEXT_MAX) return fail(400, "too_long")
        if (newAud?.reach == MomentWire.Reach.EVERYONE && !EVERYONE_AVAILABLE) return fail(422, "everyone_unavailable")
        val cur = own(id) ?: return fail(404, "gone")
        val text = newText ?: cur.moment.text
        if (text.isEmpty() && cur.moment.media.isEmpty()) return fail(400, "empty")
        val to = newAud?.let { reachOf(it, ids(o["to"])).toSet() } ?: cur.recipients
        if (newAud != null && newAud.reach != MomentWire.Reach.ONLY_ME && to.isEmpty()) return fail(422, "no_one")
        val at = now()
        val textChanged = newText != null && newText != cur.moment.text
        val moment = cur.moment.copy(
            text = text,
            audience = newAud ?: cur.moment.audience,
            rev = cur.moment.rev + 1,
            editedAtMs = if (textChanged) maxOf(at, cur.moment.createdAtMs) else cur.moment.editedAtMs,
        )
        val frame = MomentWire.encode(MomentWire.Op.Put(moment)) ?: return fail(400, "bad_moment")
        val atts = moment.media.mapNotNull { PeerFile.describe(it.toString()) }
        val added = to - cur.recipients
        val removed = cur.recipients - to
        val reached = to.filter { pid ->
            host.send(pid, frame)?.also { if (pid in added) atts.forEach { a -> host.sendBytes(pid, a) } } != null
        }.toSet()
        val gone = MomentWire.encode(MomentWire.Op.Del(id, moment.rev, at))
        removed.forEach { pid ->
            gone?.let { host.send(pid, it) }
            atts.forEach { host.dropBytes(pid, it.blobId) }
        }
        val next = cur.copy(moment = moment, recipients = (to intersect cur.recipients) + reached)
        edit { it.put(next) to Unit }
        host.trace("edit id=$id to=${next.recipients.size} out=${removed.size}")
        return ok(row(next, host.me(), host.people().associate { it.id to it.name }, emptySet()))
    }

    private fun delete(id: String): String {
        val cur = own(id) ?: return fail(404, "gone")
        val at = now()
        val stone = MomentBook.tombstone(cur.moment, cur.moment.rev + 1, at)
        val frame = MomentWire.encode(MomentWire.Op.Del(id, stone.rev, at)) ?: return fail(500, "moments_error")
        edit { it.put(MomentBook.Entry(stone, cur.recipients)) to Unit }
        cur.recipients.forEach { host.send(it, frame) }
        dropOwnBytes(cur)
        host.trace("delete id=$id to=${cur.recipients.size}")
        return ok(buildJsonObject { put("deleted", true) })
    }

    private fun dropOwnBytes(e: MomentBook.Entry) {
        e.moment.media.mapNotNull { PeerFile.describe(it.toString()) }.forEach { att ->
            host.dropBytes(OWN_DIR, att.blobId)
            e.recipients.forEach { host.dropBytes(it, att.blobId) }
        }
    }

    // ── чужі моменти ─────────────────────────────────────────────────────

    private fun theirs(id: String): MomentBook.Entry? {
        val me = host.me()
        return host.book().live(now()).firstOrNull { it.moment.id == id && it.moment.author != me }
    }

    private fun seen(id: String): String {
        val e = theirs(id) ?: return fail(404, "gone")
        if (id !in host.seen()) {
            host.markSeen(id)
            MomentWire.encode(MomentWire.Op.Seen(id, now()))?.let { host.send(e.moment.author, it) }
        }
        return ok(buildJsonObject { put("seen", true) })
    }

    private fun react(id: String, body: String?): String {
        val emoji = (parse(body)?.get("emoji") as? JsonPrimitive)?.contentOrNull ?: return fail(400, "bad_body")
        val frame = MomentWire.encode(MomentWire.Op.React(id, emoji)) ?: return fail(400, "bad_emoji")
        val e = theirs(id) ?: return fail(404, "gone")
        val me = host.me()
        edit { book ->
            val cur = book.get(e.moment.author, id) ?: e
            book.put(cur.copy(reactions = if (emoji.isEmpty()) cur.reactions - me else cur.reactions + (me to emoji))) to Unit
        }
        host.send(e.moment.author, frame)
        return ok(buildJsonObject { put("emoji", emoji) })
    }

    // ── дріт ──────────────────────────────────────────────────────────────

    /** Кадр приїхав ([local.phantom.companion.data.AppContainer] onPeerMessage). Повтор нічого не змінює. */
    fun absorb(message: PeerMessage) {
        val me = host.me()
        if (me.isBlank() || message.fromId == me) return
        val op = MomentWire.decode(message.body, message.fromId) ?: return
        val (before, after) = edit { book -> val next = book.apply(op, message.fromId, me); next to (book to next) }
        if (before == after) return
        when (op) {
            is MomentWire.Op.Put -> op.moment.media.forEach { host.assemble(message.fromId, it) }
            is MomentWire.Op.Del -> before.get(message.fromId, op.id)?.moment?.media.orEmpty()
                .mapNotNull { PeerFile.describe(it.toString()) }
                .forEach { host.dropBytes(message.fromId, it.blobId) }
            else -> Unit
        }
        host.trace("arrive op=${op::class.simpleName?.lowercase()} id=${idOf(op)}")
    }

    private fun idOf(op: MomentWire.Op) = when (op) {
        is MomentWire.Op.Put -> op.moment.id
        is MomentWire.Op.Del -> op.id
        is MomentWire.Op.Seen -> op.id
        is MomentWire.Op.React -> op.id
    }

    /** Опис медіа за блобом — щоб сховище файлів могло звірити зібране. */
    fun manifest(blobId: String): PeerFile.Attachment? =
        host.book().entries.values.asSequence()
            .flatMap { it.moment.media.asSequence() }
            .mapNotNull { PeerFile.describe(it.toString()) }
            .firstOrNull { it.blobId == blobId }

    /** Щохвилини: минула доба — геть із книги й диска; відпрацьовані кадри — геть із журналу. */
    fun sweep(): Int {
        val me = host.me()
        if (me.isBlank()) return 0
        val nowMs = now()
        val frames = host.log().filter { MomentWire.isMoment(it.body) && it.deletedAtMs == 0L }
        frames.filter { it.fromId != me }.forEach(::absorb)
        val gone = edit { book -> val g = book.expired(nowMs); book.drop(g) to g.map { m -> book.get(m.author, m.id)!! } }
        gone.forEach { e ->
            if (e.moment.author == me) {
                dropOwnBytes(e)
            } else {
                e.moment.media.mapNotNull { PeerFile.describe(it.toString()) }.forEach { host.dropBytes(e.moment.author, it.blobId) }
            }
        }
        val done = frames.filter {
            it.fromId != me || it.state == PeerMessage.State.DELIVERED || it.state == PeerMessage.State.FAILED
        }.map { it.id }.toSet()
        if (done.isNotEmpty()) host.forget(done)
        return gone.size
    }

    private fun parse(body: String?): JsonObject? = runCatching { Json.parseToJsonElement(body ?: "").jsonObject }.getOrNull()

    private fun ids(el: JsonElement?): Set<String>? =
        (el as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }?.toSet()

    private fun ok(data: JsonElement): String = buildJsonObject {
        put("ok", true)
        put("status", 200)
        put("data", data)
    }.toString()

    private fun fail(status: Int, code: String): String = buildJsonObject {
        put("ok", false)
        put("status", status)
        put("code", code)
        put("detail", "")
    }.toString()

    companion object {
        /** Каталог байтів власних моментів у [local.phantom.companion.data.PeerFileStore]. */
        const val OWN_DIR = "moments"

        /** «Усі» = контакти + відвідувачі публічного профілю. Профілю немає — щабля немає. */
        const val EVERYONE_AVAILABLE = false
    }
}
