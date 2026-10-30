package local.phantom.companion.core.data.places

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Намальована ламана — названий обʼєкт, який живе роками, а не один виїзд.
 *
 * Свідомо НЕ проміжні точки маршруту: ті веде `RoutePlanner.waypoints` у
 * памʼяті й переживає рівно один виїзд. Лінія ж проходить браму імені
 * ([MarkName]), а за законом тієї брами все іменоване лишається назавжди —
 * тому в неї своя таблиця, яку ніхто не підмітає за часом.
 *
 * [vertices] — вершини одним рядком: лінію завжди читають і пишуть цілою,
 * тому окрема таблиця вершин дала б зʼєднання без жодного запиту, якому воно
 * потрібне. Кодування — [DrawnLineCodec].
 *
 * Довжина тут НЕ зберігається: вершини — єдине джерело правди, а збережене
 * число після правки вершин брехало б доти, доки хтось не згадав його
 * перерахувати.
 */
@Entity(
    tableName = "drawn_lines",
    indices = [Index("created_at")],
)
data class DrawnLineEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "vertices") val vertices: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
