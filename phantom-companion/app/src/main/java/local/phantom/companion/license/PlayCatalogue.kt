package local.phantom.companion.license

/**
 * Каталог товарів Play, як його задає збірка: `personal_licence:inapp,crew_yearly:subs`.
 * Живе в main, а не в src/store, щоб розбір доводився тим самим тестом в обох
 * збірках; Play Billing він не знає.
 */
object PlayCatalogue {

    data class Item(val productId: String, val kind: String)

    private val ID = Regex("^[a-z0-9][a-z0-9._]*$")

    fun parse(raw: String): List<Item> = raw.split(',')
        .mapNotNull { part ->
            val (id, kind) = part.trim().split(':', limit = 2).let {
                it[0].trim() to (it.getOrNull(1)?.trim()?.ifEmpty { null } ?: "inapp")
            }
            // Ідентифікатор, якого Play не прийме, або невідомий вид — не товар.
            if (!ID.matches(id) || kind !in setOf("inapp", "subs")) null else Item(id, kind)
        }
        .distinctBy { it.productId }
}
