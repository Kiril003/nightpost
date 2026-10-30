package local.phantom.companion.core.net.peer

/**
 * «Друкую» / «перестав». Живе лише в дорозі: ні журналу, ні черги, ні сховка —
 * доїхав пізніше за секунди, то вже неправда.
 */
object PeerTyping {

    internal const val TAG = "PH-TYP:"

    private const val SEP = ';'

    data class Typing(val on: Boolean, val groupId: String = "")

    fun encode(typing: Typing): String? {
        if (typing.groupId.isNotEmpty() && !isGroupId(typing.groupId)) return null
        return TAG + (if (typing.on) "1" else "0") + (if (typing.groupId.isEmpty()) "" else SEP + typing.groupId)
    }

    fun isTyping(body: String): Boolean = body.startsWith(TAG)

    fun decode(body: String): Typing? {
        if (!isTyping(body)) return null
        val parts = body.removePrefix(TAG).split(SEP)
        if (parts.size > 2) return null
        val on = when (parts[0]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        val group = parts.getOrNull(1)?.also { if (!isGroupId(it)) return null }.orEmpty()
        return Typing(on, group)
    }

    private fun isGroupId(value: String): Boolean =
        value.length in 1..64 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }
}
