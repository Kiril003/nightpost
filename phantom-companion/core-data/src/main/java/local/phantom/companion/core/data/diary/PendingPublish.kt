package local.phantom.companion.core.data.diary

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Phase 3-D3 — local queue for `decision_diary/<verb>` envelopes that
 * couldn't be sent at write time because the WS link was offline.
 *
 * Schema is intentionally narrow: rowId for FIFO ordering + the three
 * fields needed to re-serialize the wire envelope on drain. Payload
 * is the verb-specific JSON body already encoded as a string (see
 * [DiaryPublishCodec]) so the drainer doesn't need to know the
 * DecisionEntry schema. Created-at lets the operator-visible
 * «N pending events» counter sort meaningfully.
 *
 * Bound is implicit (write rate is operator-paced; thousand-entry
 * runaways are not a real risk for personal-decision diaries). If a
 * future use-case needs a hard cap, add a TRIM trigger on insert.
 */
@Entity(tableName = "pending_publish")
data class PendingPublish(
    @PrimaryKey(autoGenerate = true)
    val rowId: Long = 0L,

    /** Wire verb: `created` | `updated` | `deleted` (per CONTRACT §10.15). */
    val verb: String,

    /** Verb-specific payload, already JSON-encoded. */
    val payload: String,

    /** Epoch-ms at enqueue time — drives FIFO + operator-visible age. */
    val enqueuedAt: Long,
)

@Dao
interface PendingPublishDao {
    /** Oldest first — drain in enqueue order. */
    @Query("SELECT * FROM pending_publish ORDER BY rowId ASC")
    fun observeAll(): Flow<List<PendingPublish>>

    /** Snapshot for one-shot drain pass (no Flow churn during drain). */
    @Query("SELECT * FROM pending_publish ORDER BY rowId ASC")
    suspend fun snapshot(): List<PendingPublish>

    @Query("SELECT COUNT(*) FROM pending_publish")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: PendingPublish): Long

    @Query("DELETE FROM pending_publish WHERE rowId = :rowId")
    suspend fun deleteById(rowId: Long)

    @Query("DELETE FROM pending_publish")
    suspend fun clear()
}
