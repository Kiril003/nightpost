package local.phantom.companion.core.data.memory

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Людина або річ, про яку є що памʼятати: «Марта», «валіза», «Passat».
 *
 * Не онтологія — кілька полів і два звʼязки. [peerId] — той самий відбиток
 * ключа, що й у роспису вузлів, тож Марта в розмові і Марта в контактах — одна
 * Марта. [label] — мітка, під якою річ уже десь лежить (збережене місце,
 * позначка ЗОРУ), щоб не заводити третю назву того самого.
 */
@Entity(
    tableName = "memory_entities",
    indices = [Index("kind"), Index("name_key"), Index("peer_id")],
)
data class MemoryEntityEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    /** person · thing · place */
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "name") val name: String,
    /** [FactMention.stem] від [name] — за ним шукають, бо відмінки. */
    @ColumnInfo(name = "name_key") val nameKey: String,
    /** Інші назви, по одній на рядок: «Марточка», «сестра». */
    @ColumnInfo(name = "aliases") val aliases: String,
    /** Відбиток ключа контакту, якщо ця людина є в розписі вузлів. */
    @ColumnInfo(name = "peer_id") val peerId: String?,
    /** Мітка збереженого місця або обʼєкта, якщо річ уже десь лежить. */
    @ColumnInfo(name = "label") val label: String?,
    @ColumnInfo(name = "note") val note: String,
    @ColumnInfo(name = "first_seen_ms") val firstSeenMs: Long,
    @ColumnInfo(name = "last_seen_ms") val lastSeenMs: Long,
    @ColumnInfo(name = "source") val source: String,
) {
    /** Усі назви, під якими цю сутність можуть згадати. */
    val allNames: List<String>
        get() = (listOf(name) + aliases.lineSequence()).map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        const val PERSON = "person"
        const val THING = "thing"
        const val PLACE = "place"
    }
}
