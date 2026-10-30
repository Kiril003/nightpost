package local.phantom.companion.core.solana

internal object FrameNote {
    const val MAX = 120

    fun isValid(note: String): Boolean =
        note.length in 1..MAX && note.none { it.isISOControl() }

    fun encode(note: String): String {
        require(isValid(note)) { "bad note" }
        return note.replace("%", "%25").replace(";", "%3B")
    }

    fun decode(field: String): String? {
        if (field.isEmpty() || field.length > MAX * 3) return null
        val sb = StringBuilder(field.length)
        var i = 0
        while (i < field.length) {
            val ch = field[i]
            if (ch == ';' || ch.isISOControl()) return null
            if (ch == '%') {
                if (i + 2 >= field.length) return null
                when (field.substring(i + 1, i + 3)) {
                    "25" -> sb.append('%')
                    "3B", "3b" -> sb.append(';')
                    else -> return null
                }
                i += 3
            } else {
                sb.append(ch)
                i += 1
            }
        }
        return sb.toString().takeIf(::isValid)
    }
}
