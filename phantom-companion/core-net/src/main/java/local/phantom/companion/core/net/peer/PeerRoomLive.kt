package local.phantom.companion.core.net.peer

/**
 * «У групі зараз говорять» — кадр для інших учасників гілки, не для історії.
 *
 * Їде під тим самим префіксом, що й дзвінок ([PeerCall.SIGNAL]), і саме тому
 * без жодної нової дороги: сітка вже везе такі кадри лише напряму, не пише
 * їх у журнал і не показує шторкою. Позначка роду `l` не входить у
 * [PeerCall.Signalling], тож старий апарат розбирає кадр у `null` і мовчить,
 * а рушій дзвінка його не бачить узагалі.
 *
 * Обличчя без поновлення живе [FACE_LIFE_MS]: той, у кого сів телефон,
 * «вийшов» уже не скаже.
 */
object PeerRoomLive {

    private const val MARK = "l"
    private const val SEP = ';'
    private const val FIELD = '|'

    const val FACE_LIFE_MS = 90_000L
    const val BEAT_MS = 30_000L

    enum class Act(val word: String) { IN("in"), OUT("out") }

    data class Note(val callId: String, val groupId: String, val act: Act, val startedAtMs: Long)

    data class Room(
        val callId: String,
        val groupId: String,
        val startedAtMs: Long,
        /** Хто в розмові → коли його бачили востаннє. */
        val faces: Map<String, Long>,
    )

    fun isLive(body: String): Boolean = body.startsWith(PeerCall.SIGNAL) &&
        body.removePrefix(PeerCall.SIGNAL).split(SEP).getOrNull(1) == MARK

    fun encode(note: Note): String? {
        if (!PeerCall.isCallRef(note.callId) || !isGroupRef(note.groupId)) return null
        val raw = listOf(note.groupId, note.act.word, note.startedAtMs.toString()).joinToString(FIELD.toString())
        val body = java.util.Base64.getEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
        return PeerCall.SIGNAL + note.callId + SEP + MARK + SEP + body
    }

    fun decode(body: String): Note? {
        if (!isLive(body)) return null
        val parts = body.removePrefix(PeerCall.SIGNAL).split(SEP)
        if (parts.size != 3) return null
        val callId = parts[0].takeIf(PeerCall::isCallRef) ?: return null
        val raw = parts[2].takeIf { it.length in 1..256 } ?: return null
        val fields = runCatching {
            String(java.util.Base64.getDecoder().decode(raw), Charsets.UTF_8)
        }.getOrNull()?.split(FIELD) ?: return null
        if (fields.size != 3) return null
        val groupId = fields[0].takeIf(::isGroupRef) ?: return null
        val act = Act.entries.firstOrNull { it.word == fields[1] } ?: return null
        val started = fields[2].toLongOrNull()?.takeIf { it > 0 } ?: return null
        return Note(callId, groupId, act, started)
    }

    /**
     * Кімнати за номером дзвінка. [members] — склад гілки, як його знає ЦЕЙ
     * телефон: чужий гілці кадр не малює облич, навіть якщо знає її номер.
     */
    fun absorb(
        board: Map<String, Room>,
        fromId: String,
        note: Note,
        members: List<String>?,
        nowMs: Long,
    ): Map<String, Room> {
        if (members == null || fromId !in members) return board
        val had = board[note.callId]
        if (had != null && had.groupId != note.groupId) return board
        return when (note.act) {
            Act.IN -> {
                // Час старту міряє кожен своїм годинником; раніший — ближчий до
                // правди, а майбутнє не приймається зовсім.
                val started = minOf(had?.startedAtMs ?: note.startedAtMs, note.startedAtMs, nowMs)
                board + (
                    note.callId to Room(
                        callId = note.callId,
                        groupId = note.groupId,
                        startedAtMs = started,
                        faces = had?.faces.orEmpty() + (fromId to nowMs),
                    )
                    )
            }
            Act.OUT -> {
                had ?: return board
                val faces = had.faces - fromId
                if (faces.isEmpty()) board - note.callId else board + (note.callId to had.copy(faces = faces))
            }
        }
    }

    fun expire(board: Map<String, Room>, nowMs: Long): Map<String, Room> {
        var changed = false
        val out = board.mapNotNull { (id, room) ->
            val faces = room.faces.filterValues { nowMs - it < FACE_LIFE_MS }
            if (faces.size != room.faces.size) changed = true
            if (faces.isEmpty()) null else id to room.copy(faces = faces)
        }.toMap()
        return if (changed) out else board
    }

    /** Розмова гілки просто зараз: та, де людей більше, при рівних — давніша. */
    fun view(board: Map<String, Room>, groupId: String, nowMs: Long): Room? =
        expire(board, nowMs).values
            .filter { it.groupId == groupId }
            .maxWithOrNull(compareBy<Room> { it.faces.size }.thenByDescending { it.startedAtMs })

    // ── що кажу я сам ────────────────────────────────────────────────────

    /** Що цей телефон востаннє сказав гілці про себе. */
    data class Mine(val callId: String, val groupId: String, val startedAtMs: Long, val beatAtMs: Long)

    data class Step(val mine: Mine?, val say: List<Note>)

    /**
     * «Я в розмові гілки» — це кімната з гілкою і хоч одна нога, що ГОВОРИТЬ.
     * Дзвонить, але слухавку не взяли — ще не в розмові.
     */
    fun step(mine: Mine?, room: PeerCallLine.Room?, board: Map<String, Room>, nowMs: Long): Step {
        val groupId = room?.groupId
        val talking = room != null && groupId != null &&
            PeerCallLine.liveLegs(room).any { it.foot == PeerCallLine.Foot.TALKING }
        val say = mutableListOf<Note>()
        var next = mine
        if (next != null && (!talking || room?.callId != next.callId)) {
            say += Note(next.callId, next.groupId, Act.OUT, next.startedAtMs)
            next = null
        }
        if (talking && next == null) {
            val started = board[room!!.callId]?.startedAtMs ?: nowMs
            next = Mine(room.callId, groupId!!, started, nowMs)
            say += Note(next.callId, next.groupId, Act.IN, started)
        } else if (next != null && nowMs - next.beatAtMs >= BEAT_MS) {
            next = next.copy(beatAtMs = nowMs)
            say += Note(next.callId, next.groupId, Act.IN, next.startedAtMs)
        }
        return Step(next, say)
    }

    private val CONTACT = Regex("\"contact_id\"\\s*:\\s*\"([0-9A-Za-z_-]{1,64})\"")

    /**
     * Гілка, у яку кличе пропозиція: рушій того, хто набрав, кладе номер гілки
     * в `contact_id`. Без цього в того, кого покликали, кімната без гілки, і
     * «я в розмові» він гілці не скаже ніколи.
     */
    fun groupOfOffer(payload: String): String? = CONTACT.find(payload)?.groupValues?.get(1)

    private fun isGroupRef(value: String): Boolean =
        value.length in 1..64 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}
