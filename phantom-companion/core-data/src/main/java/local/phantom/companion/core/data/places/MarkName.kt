package local.phantom.companion.core.data.places

/**
 * Брама імені. Мітка живе роками, тому вона мусить мати імʼя; усе безіменне
 * лишається робочою точкою, а точки прибирають себе самі. Це і є відповідь на
 * «щоб не засмічувалось»: не прибирання постфактум, а брама на вході.
 */
object MarkName {

    const val MIN_LENGTH = 2

    /** Імена, які насправді означають «я не назвав»: сорок таких — це осад, не памʼять. */
    private val PLACEHOLDERS = setOf(
        "точка на мапі",
        "точка",
        "місце",
        "місце без назви",
        "нове місце",
        "мітка",
        "лінія",
        "ламана",
        "маршрут",
        "point on map",
        "point",
        "place",
        "unnamed place",
        "new place",
        "marker",
        "line",
        "polyline",
        "route",
    )

    private val COORDINATES = Regex("""^-?\d{1,3}[.,]\d+\s*[,;]\s*-?\d{1,3}[.,]\d+$""")

    private val WHITESPACE = Regex("\\s+")

    fun clean(raw: String?): String = raw?.trim()?.replace(WHITESPACE, " ").orEmpty()

    /** true — це імʼя, під яким мітку варто памʼятати роками. */
    fun isNamed(raw: String?): Boolean {
        val name = clean(raw)
        if (name.length < MIN_LENGTH) return false
        if (name.lowercase() in PLACEHOLDERS) return false
        if (COORDINATES.matches(name)) return false
        return name.any { it.isLetterOrDigit() }
    }

    /** Передзаповнення поля імені: назва місця → перша частина адреси → порожньо. */
    fun candidate(poiName: String?, address: String?): String {
        if (isNamed(poiName)) return clean(poiName)
        val head = address?.substringBefore(",")
        if (isNamed(head)) return clean(head)
        return ""
    }

    /**
     * «Дім» → «Дім (2)» → «Дім (3)». Label — ключ оновлення в `saved_places`,
     * тому зіткнення двох РІЗНИХ місць мусить дати два записи, а не тихий
     * перезапис: переписаний «Дім» не відкотиш.
     */
    fun deduplicate(label: String, takenLowercase: Set<String>): String {
        val base = clean(label)
        if (base.lowercase() !in takenLowercase) return base
        var n = 2
        while (true) {
            val candidate = "$base ($n)"
            if (candidate.lowercase() !in takenLowercase) return candidate
            n++
        }
    }
}
