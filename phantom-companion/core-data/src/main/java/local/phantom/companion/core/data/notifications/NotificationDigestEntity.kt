package local.phantom.companion.core.data.notifications

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 3 W4-5 — persistent queue for the daily 9 am digest. Routine
 * notifications classified by `NotificationClassifier` land here
 * with their redacted body (sensitive content already masked at
 * write time, so even a forensic dump of the SQLCipher database
 * never surfaces an OTP code).
 *
 * The (delivered_at, package_name) index covers the digest read
 * path: `pickRoutineSince(now - 24h)` returns yesterday's queue
 * grouped by sender. The boolean `delivered` column is flipped to
 * true once the digest Notice has been pushed into Stream — so a
 * second 9 am pass on the same day doesn't double-emit.
 */
@Entity(
    tableName = "notification_digest",
    indices = [
        Index(value = ["delivered_at", "package_name"], name = "idx_notif_digest_window"),
        Index(value = ["delivered"], name = "idx_notif_digest_delivered"),
    ],
)
data class NotificationDigestEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "package_name")
    val packageName: String,

    @ColumnInfo(name = "channel_id")
    val channelId: String? = null,

    /** Redacted sender line — a bank puts the code in the title too. */
    @ColumnInfo(name = "title")
    val title: String,

    /** Redacted body — never the raw text. */
    @ColumnInfo(name = "redacted_text")
    val redactedText: String,

    /** Wall-clock epoch-ms when the notification was posted. */
    @ColumnInfo(name = "delivered_at")
    val deliveredAt: Long,

    /** True once the digest containing this row was pushed to Stream. */
    @ColumnInfo(name = "delivered", defaultValue = "0")
    val delivered: Boolean = false,
)
