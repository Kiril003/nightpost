package local.phantom.companion.ui.messenger

import local.phantom.companion.core.net.peer.PeerFile
import local.phantom.companion.core.net.peer.PeerFrames
import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerRevise

/**
 * Правка й стирання «для всіх» з боку веба: що вирішити і що чесно відповісти.
 *
 * `frame` = "sent" лише коли хоч один кадр справді ліг у чергу сітки; інакше
 * "local", і веб каже людині, що змінилось тільки тут.
 */
internal class WebMessengerRevise(
    private val log: () -> List<PeerMessage>,
    private val me: () -> String,
    private val revise: (copies: List<PeerMessage>, act: (String) -> PeerRevise.Act) -> Int?,
    private val keepLocal: (List<PeerMessage>) -> Unit,
    /** Стерти лише тут; повертає, скільки файлів вкладення пішло з диска. */
    private val forget: (PeerMessage) -> Int,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    sealed interface Outcome {
        data class Edited(val line: PeerMessage, val frame: String) : Outcome

        data class Deleted(val forEveryone: Boolean, val blobs: Int, val frame: String) : Outcome

        data class Refused(val status: Int, val code: String) : Outcome
    }

    /** [copies] — усі копії одного рядка: у розмові вдвох одна, у групі по одній на учасника. */
    fun edit(copies: List<PeerMessage>, text: String): Outcome {
        val line = copies.first()
        if (line.fromId != self()) return Outcome.Refused(403, "not_mine")
        val said = PeerGroup.decode(line.body)?.text ?: line.body
        if (!PeerRevise.captionable(line) &&
            (!WebMessengerKinds.isSpokenKind(WebMessengerKinds.fields(said)[WebMessengerKinds.KIND]) || !PeerRevise.editable(line))
        ) {
            return Outcome.Refused(409, "not_words")
        }
        if (!PeerRevise.inWindow(line, nowMs())) return Outcome.Refused(409, "edit_window")
        if (PeerFrames.refuseTyped(text) != null) return Outcome.Refused(422, "service_prefix")
        if (PeerRevise.encode(PeerRevise.Edit(line.id, text)) == null) return Outcome.Refused(413, "too_long")
        val sent = revise(copies) { PeerRevise.Edit(it, text) }
        if (sent == null) {
            val at = nowMs()
            keepLocal(
                copies.flatMap { c ->
                    listOfNotNull(PeerRevise.edited(c, text, at), PeerRevise.origin(c).takeIf { c.editedAtMs == 0L })
                },
            )
        }
        val now = log().firstOrNull { it.id == line.id } ?: PeerRevise.edited(line, text, nowMs())
        return Outcome.Edited(now, frameOf(sent))
    }

    fun history(line: PeerMessage): List<PeerRevise.Version> = PeerRevise.history(log(), line)

    /** Чужий рядок, дія над складом або відсутній ключ — лише тут, як і було. */
    fun delete(copies: List<PeerMessage>, forEveryone: Boolean): Outcome {
        val line = copies.first()
        if (forEveryone && line.fromId == self() && PeerRevise.unsendable(line)) {
            val sent = revise(copies) { PeerRevise.Unsend(it) }
            // Байти вкладення прибирає сама сітка, коли рядок стає надгробком.
            val blobs = if (PeerFile.describe(line.body) != null) 1 else 0
            if (sent != null) return Outcome.Deleted(sent > 0, blobs, frameOf(sent))
        }
        return Outcome.Deleted(false, forget(line), "local")
    }

    private fun self(): String = me().ifBlank { "operator_me" }

    private fun frameOf(sent: Int?): String = if (sent != null && sent > 0) "sent" else "local"

    companion object {
        fun forEveryone(query: String): Boolean = query.split('&').any { it == "for_everyone=true" }
    }
}
