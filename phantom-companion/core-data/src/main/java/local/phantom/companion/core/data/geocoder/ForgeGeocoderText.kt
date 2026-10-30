package local.phantom.companion.core.data.geocoder

import java.text.Normalizer

/**
 * Нормалізація запиту — дослівний двійник `forge/geocode.py`.
 *
 * Кожне правило тут уже вбите в ІНДЕКС пакета: колонки `names` і `tri`
 * побудовані цим самим згортанням. Розійтися з ним означає шукати одним
 * алфавітом у словнику, складеному іншим.
 */
object ForgeGeocoderText {

    private val NOT_KEPT = Regex("[^0-9a-zа-яіїєґ]+")
    private val FTS_SPECIAL = Regex("[\"*():^]")

    val ADDRESS: Regex = Regex("^(.*?[^\\d\\s])[\\s,]+(\\d+[а-яa-z]?(?:/\\d+)?)\\s*$")

    fun fold(s: String): String {
        val decomposed = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD)
        val out = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
            when (ch) {
                'ʼ', '’', '\'' -> Unit
                'ё' -> out.append('е')
                else -> out.append(ch)
            }
        }
        return NOT_KEPT.replace(out, " ").trim()
    }

    fun words(s: String): List<String> = fold(s).split(' ').filter { it.isNotEmpty() }

    fun wordTrigrams(word: String): Set<String> {
        val w = "0$word" + "0"
        if (w.length < 3) return emptySet()
        val out = HashSet<String>(w.length)
        for (i in 0..w.length - 3) out += w.substring(i, i + 3)
        return out
    }

    fun trigrams(text: String): Set<String> {
        val out = HashSet<String>()
        for (word in words(text)) out += wordTrigrams(word)
        return out
    }

    /**
     * Частка ВІД ЗАПИТУ, а не від документа: рядок несе назву всіма мовами
     * одразу, і обʼєднання їх усіх топило «Кишенів» нижче порога.
     */
    fun similarity(queryWords: List<Set<String>>, names: String): Double {
        if (queryWords.isEmpty()) return 0.0
        val rowWords = words(names).map { wordTrigrams(it) }
        if (rowWords.isEmpty()) return 0.0
        var total = 0.0
        for (tq in queryWords) {
            total += rowWords.maxOf { tw ->
                val widest = maxOf(tq.size, tw.size)
                if (widest == 0) 0.0 else tq.count { it in tw }.toDouble() / widest
            }
        }
        return total / queryWords.size
    }

    fun namesExpression(text: String): String? {
        val words = words(text)
        return if (words.isEmpty()) null else words.joinToString(" ") { "names:$it*" }
    }

    fun sanitize(query: String): String = FTS_SPECIAL.replace(query, " ").trim()
}
