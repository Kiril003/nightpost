package local.phantom.companion.core.ai.route

/**
 * Памʼять, на яку спирається відповідь (`core-memory` через застосунок).
 * Тут — лише контракт, щоб `core-ai` не залежав від сховища.
 */
interface MemoryGround {
    /** «Не знаю…», якщо питання про власника, а доречного запису немає; інакше null. */
    suspend fun dontKnow(query: String): String?

    /** Блок памʼяті для системного промпту (бюджет 1500 токенів, мітки [m:id]) або null. */
    suspend fun block(query: String): String?

    /** Мітки [m:xxxxxxxx] → людський текст записів, на які відповідь спиралась. */
    suspend fun cite(shortIds: List<String>): List<String>

    companion object {
        /** Позначка блоку памʼяті — та сама, що `Ledger.HEADER` / `service.py`. */
        const val HEADER_MARK = "[ПАМʼЯТЬ PHANTOM"
        val CITE = Regex("\\s?\\[m:([0-9a-f]{8})]")
    }
}

/**
 * Прибирає мітки [m:xxxxxxxx] з потоку токенів, не показуючи людині службове:
 * хвіст токена, що може бути початком мітки, притримується до наступного.
 */
class CitationFilter {
    private val held = StringBuilder()
    val seen = LinkedHashSet<String>()

    fun feed(token: String): String {
        held.append(token)
        val text = held.toString()
        MemoryGround.CITE.findAll(text).forEach { seen += it.groupValues[1] }
        val clean = MemoryGround.CITE.replace(text, "")
        // можливий початок мітки в кінці: " [", "[m", "[m:3fa" …
        val tail = Regex("\\s?\\[(?:m(?::[0-9a-f]{0,8})?)?$").find(clean)
        held.setLength(0)
        return if (tail != null) {
            held.append(clean.substring(tail.range.first))
            clean.substring(0, tail.range.first)
        } else {
            clean
        }
    }

    fun flush(): String = held.toString().also { held.setLength(0) }
}
