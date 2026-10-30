package local.phantom.companion.core.data.places

import kotlinx.coroutines.flow.first
import local.phantom.companion.core.data.vault.VaultEntity
import local.phantom.companion.core.data.vault.VaultStore

/**
 * Одноразове зведення двох памʼятей місць в одну.
 *
 * Довго жили дві: Vault-рядки `metadata="map-poi"` (їх малювала мапа) і
 * таблиця `saved_places` (в неї писав чат). Тому «збережи дім» у чаті давало
 * порожню мапу, а зірка з мапи — «збережених місць немає». Обидва шляхи
 * «працювали»; разом — ні.
 *
 * Носій Vault був ще й крихкий: `"імʼя · кат · підкат · lat, lon"`. Імʼя з
 * « · » ламало парсер, а `mapNotNull` викидав рядок мовчки — мітка зникала
 * без сліду. Тому нечитабельні рядки тут НЕ конвертуються і НЕ видаляються:
 * без координат мітка стала б піном у нулі широти й довготи, а це гірше за
 * втрату. Вони лишаються в сховищі й перелічені у звіті.
 */
class SavedPlaceMigration(
    private val readVault: suspend () -> List<VaultEntity>,
    private val deleteVaultEntry: suspend (String) -> Unit,
    private val places: SavedPlaceStore,
) {

    constructor(vault: VaultStore, places: SavedPlaceStore) : this(
        readVault = { vault.getEntries().first() },
        deleteVaultEntry = { id -> vault.deleteEntry(id) },
        places = places,
    )

    data class Report(
        val found: Int,
        val moved: Int,
        /** Перейменовані через зіткнення label-ів: «Дім» → «Дім (2)». */
        val renamed: List<String>,
        /** Рядки, які не вдалося прочитати — лишились у сховищі недоторканими. */
        val unreadable: List<String>,
        /** Скільки з перенесених прочиталось назад із таблиці. */
        val verified: Int,
    ) {
        val clean: Boolean get() = moved == verified && unreadable.isEmpty()
    }

    suspend fun run(nowMs: Long = System.currentTimeMillis()): Report {
        val entries = runCatching { readVault() }.getOrNull()
            ?: return Report(0, 0, emptyList(), emptyList(), 0)
        val poiEntries = entries.filter { it.metadata == MAP_POI_META }
        if (poiEntries.isEmpty()) return Report(0, 0, emptyList(), emptyList(), 0)

        val taken = places.list().map { it.label.lowercase() }.toMutableSet()
        val renamed = mutableListOf<String>()
        val unreadable = mutableListOf<String>()
        val movedLabels = mutableListOf<String>()

        for (entry in poiEntries) {
            val parsed = parse(entry.value)
            if (parsed == null) {
                unreadable += entry.id
                continue
            }
            val label = MarkName.deduplicate(parsed.label, taken)
            if (!label.equals(parsed.label, ignoreCase = true)) renamed += label
            val result = places.saveMark(
                label = label,
                lat = parsed.lat,
                lon = parsed.lon,
                category = SavedPlaceCategory.FAVOURITE,
                nowMs = nowMs,
            )
            if (result is SavedPlaceStore.MarkResult.Saved) {
                taken += label.lowercase()
                movedLabels += label
                runCatching { deleteVaultEntry(entry.id) }
            } else {
                unreadable += entry.id
            }
        }

        // Доказ — читання назад, а не відсутність винятку.
        val afterwards = places.list().map { it.label.lowercase() }.toSet()
        val verified = movedLabels.count { it.lowercase() in afterwards }

        return Report(
            found = poiEntries.size,
            moved = movedLabels.size,
            renamed = renamed,
            unreadable = unreadable,
            verified = verified,
        )
    }

    private data class Parsed(val label: String, val lat: Double, val lon: Double)

    /** `"Silpo · shop · supermarket · 50.45000, 30.52000"` — імʼя завжди перше. */
    private fun parse(raw: String): Parsed? {
        val parts = raw.split(SEPARATOR)
        if (parts.size < 2) return null
        val coords = parts.last().split(",")
        if (coords.size != 2) return null
        val lat = coords[0].trim().toDoubleOrNull() ?: return null
        val lon = coords[1].trim().toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        // Брама імені не діє заднім числом: рядок з координатами — це вже
        // збережене місце, і викинути його за безіменність було б втратою.
        // Імʼя з координат людина перепише; пін у нулі широти вона б не знайшла.
        val head = parts.first().trim()
        val label = if (MarkName.isNamed(head)) {
            head
        } else {
            String.format(java.util.Locale.US, "Мітка %.4f, %.4f", lat, lon)
        }
        return Parsed(label, lat, lon)
    }

    private companion object {
        const val MAP_POI_META = "map-poi"
        const val SEPARATOR = " · "
    }
}
