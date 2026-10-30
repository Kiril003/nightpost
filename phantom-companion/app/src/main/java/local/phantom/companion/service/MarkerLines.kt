package local.phantom.companion.service

import local.phantom.companion.R

/** Листи-картки несуть машинні мітки окремими рядками; у плашці їх не показуємо. */
internal object MarkerLines {
    enum class Kind(val res: Int) {
        EDU(R.string.peer_marker_edu),
        ORDER(R.string.peer_marker_order),
        ORDER_STATUS(R.string.peer_marker_order_status),
        UPDATE(R.string.peer_marker_update),
        STICKER(R.string.peer_marker_sticker),
    }

    private val MARKERS = listOf(
        Kind.EDU to Regex("^⟦edu1:.*"),
        Kind.ORDER to Regex("^PH-ORD:.*"),
        Kind.ORDER_STATUS to Regex("^PH-ORS:.*"),
        Kind.UPDATE to Regex("^\\[np:.*\\]$"),
        Kind.STICKER to Regex("^⟦st1:.*"),
    )

    private fun kindOfLine(line: String): Kind? {
        val t = line.trim()
        return MARKERS.firstOrNull { it.second.matches(t) }?.first
    }

    fun strip(body: String): String {
        val lines = body.trimEnd().split('\n')
        if (lines.none { kindOfLine(it) != null }) return body
        return lines.filter { kindOfLine(it) == null }.joinToString("\n").trim()
    }

    private val STICKER = Regex("^([^\\p{L}\\p{N}\\s]{1,16})\n⟦st1:[a-z0-9]{1,16}/[a-z0-9-]{1,32}⟧$")

    /** Стікер (емодзі + мітка набору): емодзі для плашки, інакше null. */
    fun sticker(body: String): String? = STICKER.find(body.trim())?.groupValues?.get(1)

    /** Лист без жодного людського рядка: плашці потрібна назва роду, а не порожнеча. */
    fun markerOnlyKind(body: String): Kind? {
        if (body.isBlank() || strip(body).isNotEmpty()) return null
        return body.split('\n').firstNotNullOfOrNull { kindOfLine(it) }
    }
}
