package local.phantom.companion.core.net.transit

/**
 * Номер лінії так, як його пише табличка на зупинці.
 *
 * Чеський JDF-фід кладе в `route_short_name` шестизначний ліцензійний номер
 * (915108 — це тролейбус 108 в Остраві), а живі фіди Львова несуть лише
 * `route_id` («2285» замість «А25»). Обидва — внутрішні номери, яких людина
 * ніде на вулиці не бачить.
 */
object LineLabel {

    fun of(shortName: String?, longName: String? = null, routeId: String? = null): String {
        val short = shortName?.trim().orEmpty()
        if (short.isNotEmpty()) {
            czechLicence(short)?.let { return it }
            if (!looksLikeId(short) && !short.equals(routeId?.trim(), ignoreCase = true)) return short
        }
        val long = longName?.trim()?.replace(WHITESPACE, " ").orEmpty()
        if (long.isNotEmpty() && !looksLikeId(long) && !long.equals(routeId?.trim(), ignoreCase = true)) return long
        return ""
    }

    fun looksLikeId(text: String): Boolean {
        val s = text.trim()
        if (s.isEmpty()) return false
        if ('_' in s || ID_COLON.containsMatchIn(s)) return true
        if (s.length >= 5 && s.all { it.isDigit() }) return true
        if (UUID.matches(s)) return true
        val digits = s.count { it.isDigit() }
        return s.length >= 9 && !s.contains(' ') && digits >= 4
    }

    // Три перші цифри — відомство, що видало ліцензію; публічний номер — останні три.
    private fun czechLicence(s: String): String? {
        if (s.length != 6 || !s.all { it.isDigit() }) return null
        return s.takeLast(3).trimStart('0').ifEmpty { null }
    }

    private val WHITESPACE = Regex("\\s+")
    private val ID_COLON = Regex(":\\S")
    private val UUID = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-.*")
}
