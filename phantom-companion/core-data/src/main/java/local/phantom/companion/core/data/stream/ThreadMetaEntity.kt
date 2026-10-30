package local.phantom.companion.core.data.stream

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * W2-4 — persisted thread descriptor for the ThreadRail.
 *
 * Operator switches between threads (Зараз, Оля, Сімʼя, …); each
 * needs a stable label that survives cold launch and a
 * `last_active_at` for sorting most-recent-first when the rail
 * grows past the four hardcoded defaults.
 *
 * Default «Зараз» thread is bootstrapped on first launch by the
 * StreamRepository so the rail always has at least one entry.
 */
@Entity(tableName = "threads_meta")
data class ThreadMetaEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "label")
    val label: String,

    @ColumnInfo(name = "last_active_at")
    val lastActiveAt: Long,

    /** R2.5 — space type: "task" | "person" | "device" | "project" | "system".
     *  Turns anonymous chat buckets into named spaces the UI can group. */
    @ColumnInfo(name = "kind", defaultValue = "task")
    val kind: String = "task",
)
