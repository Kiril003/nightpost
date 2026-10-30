package local.phantom.companion.core.net.peer

/**
 * Хто їде першим, коли дорога вузька.
 *
 * Слова людини — завжди попереду. Службова балачка (прочитання, місце, профіль,
 * маршрут наживо) — за ними. Шматки файлів — останні, а у вузькому режимі не
 * їдуть зовсім: лишаються в черзі «чекає» й рушать, щойно дорога розшириться.
 * Нічого тут не губиться й не позначається — це лише порядок і пауза.
 *
 * Виняток — коротке голосове (до [NARROW_VOICE_BYTES]): це мова людини, а не
 * вантаж, і на вузькій дорозі воно важить більше за фото. Його шматки їдуть
 * смугою слів. Довше голосове чекає, як і решта вкладень.
 */
object PeerLane {

    enum class Lane { TEXT, CHATTER, MEDIA }

    /** «Близько 30 КБ» — пʼятнадцять секунд мови в Opus; два шматки по 24 КіБ. */
    const val NARROW_VOICE_BYTES = 30 * 1024

    /** Блоби коротких голосових у журналі — за описами (рід і розмір беруться з опису). */
    fun speechBlobs(log: List<PeerMessage>): Set<String> = log.asSequence()
        .mapNotNull { PeerFile.describe(it.body) }
        .filter { it.isVoice && it.size <= NARROW_VOICE_BYTES }
        .map { it.blobId }
        .toSet()

    fun of(message: PeerMessage, speech: Set<String> = emptySet()): Lane = when (PeerFrames.kindOf(message.body)) {
        PeerFrames.Kind.CHUNK -> if (PeerFile.head(message.body)?.blobId in speech) Lane.TEXT else Lane.MEDIA
        PeerFrames.Kind.BANNER -> Lane.MEDIA
        PeerFrames.Kind.READ,
        PeerFrames.Kind.WHERE,
        PeerFrames.Kind.PROFILE,
        PeerFrames.Kind.HIST,
        PeerFrames.Kind.FIX,
        PeerFrames.Kind.STOP,
        -> Lane.CHATTER
        else -> Lane.TEXT
    }

    /**
     * [due] у порядку черги (старші перші) — і так само в кожній смузі.
     *
     * Вузько: балачка їде лише тоді, коли жодне слово не чекає, а прочитання —
     * не раніше [READ_HOLD_MS] від свого народження, щоб кілька поспіль злились
     * у одне ([PeerRead.compact] лишає найновіше). [direct] — адресат поруч у
     * LAN: та дорога широка, і її вузькість сховка не стосується.
     */
    fun order(
        due: List<PeerMessage>,
        narrow: Boolean,
        nowMs: Long,
        speech: Set<String> = speechBlobs(due),
        direct: (PeerMessage) -> Boolean = { false },
    ): List<PeerMessage> {
        val lanes = due.groupBy { of(it, speech) }
        val text = lanes[Lane.TEXT].orEmpty()
        val chatter = lanes[Lane.CHATTER].orEmpty()
        val media = lanes[Lane.MEDIA].orEmpty()
        if (!narrow) return text + chatter + media
        val near = chatter.filter(direct) + media.filter(direct)
        if (text.isNotEmpty()) return text + near
        val held = chatter.filterNot(direct).filter { !PeerRead.isRead(it.body) || nowMs - it.sentAtMs >= READ_HOLD_MS }
        return held + near
    }

    const val READ_HOLD_MS = 15_000L
}
