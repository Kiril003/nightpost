package local.phantom.companion.core.data.memory

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Tier 2 — one durable, deduplicated, reinforced fact about the operator,
 *  recalled into the prompt every turn. Embedding is int8 (~512 B, ¼ of fp32). */
@Entity(
    tableName = "memory_facts",
    indices = [Index("category"), Index("last_confirmed_ms"), Index("entity_id")],
)
data class MemoryFactEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "text") val text: String,
    /** preference · vehicle · person · place · routine · fact · … */
    @ColumnInfo(name = "category") val category: String,
    @ColumnInfo(name = "embedding", typeAffinity = ColumnInfo.BLOB) val embedding: ByteArray,
    @ColumnInfo(name = "confidence") val confidence: Float,
    @ColumnInfo(name = "first_seen_ms") val firstSeenMs: Long,
    @ColumnInfo(name = "last_confirmed_ms") val lastConfirmedMs: Long,
    @ColumnInfo(name = "reinforce_count") val reinforceCount: Int,
    /** Звідки взявся: `operator` / `tool.memory.save` — сам сказав; решта — виведено. */
    @ColumnInfo(name = "source") val source: String,
    /**
     * Чи людина це підтвердила. Не оздоба: підтверджене їде в підказку щоразу
     * ([MemoryFactStore.recall] тримає його якорем), решта — лише коли її
     * згадали. Тому хибний здогад коштує один дотик, а не кожну розмову.
     */
    @ColumnInfo(name = "confirmed", defaultValue = "0") val confirmed: Int = NOT_ASKED,
    /** [MemoryEntityEntity.id] людини або речі, якої факт стосується. */
    @ColumnInfo(name = "entity_id") val entityId: String? = null,
) {
    val statedBySelf: Boolean get() = statedBySelf(source)

    companion object {
        const val NOT_ASKED: Int = 0
        const val CONFIRMED: Int = 1

        /** Джерела, де говорила сама людина, — підтверджувати нема чого. */
        fun statedBySelf(source: String): Boolean =
            source == "operator" || source == "tool.memory.save"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MemoryFactEntity
        return id == other.id &&
            text == other.text &&
            category == other.category &&
            embedding.contentEquals(other.embedding) &&
            confidence == other.confidence &&
            firstSeenMs == other.firstSeenMs &&
            lastConfirmedMs == other.lastConfirmedMs &&
            reinforceCount == other.reinforceCount &&
            source == other.source &&
            confirmed == other.confirmed &&
            entityId == other.entityId
    }

    override fun hashCode(): Int {
        var r = id.hashCode()
        r = 31 * r + text.hashCode()
        r = 31 * r + category.hashCode()
        r = 31 * r + embedding.contentHashCode()
        r = 31 * r + confidence.hashCode()
        r = 31 * r + firstSeenMs.hashCode()
        r = 31 * r + lastConfirmedMs.hashCode()
        r = 31 * r + reinforceCount
        r = 31 * r + source.hashCode()
        r = 31 * r + confirmed
        r = 31 * r + (entityId?.hashCode() ?: 0)
        return r
    }
}
