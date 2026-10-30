package local.phantom.companion.core.data.pending

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_intents")
data class PendingIntentEntity(
    @PrimaryKey val id: String,
    val threadId: String,
    val payload: String,           // PendingIntentPayload serialised JSON
    val createdAtMs: Long,
    val attempts: Int = 0,
    val lastErrorMessage: String? = null,
)
