package local.phantom.companion.core.data.stream

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * W2-1 — persistent storage for [StreamItem] surfaces. Phase 2.5
 * the operator's wound was «історій чатів немає» — Stream items
 * lived only in memory and disappeared on cold launch. The Room v6
 * schema mirrors `domain.StreamItem` faithfully enough that the
 * cold-launch restore path (W2-3) can rehydrate the in-memory state
 * one-to-one.
 *
 * `kind` is the discriminator: 'text' / 'widget' / 'notice' /
 * 'command'. Per-kind nullable columns hold the type-specific
 * payload — Text uses `role` + `text_content`, Widget uses
 * `widget_type` + `widget_payload`, Notice uses `text_content`
 * (and parses severity out-of-band via the parser layer), Command
 * uses `text_content` for the action verb.
 *
 * The (thread_id, seq_no) index covers the by-thread query path
 * (the renderer reads only the active thread's items, oldest →
 * newest) so a thread with thousands of items doesn't full-scan
 * the table on every cold launch.
 */
@Entity(
    tableName = "stream_items",
    indices = [
        Index(value = ["thread_id", "seq_no"], name = "idx_stream_thread_seq"),
        Index(value = ["created_at"], name = "idx_stream_created_at"),
    ],
)
data class StreamItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "thread_id")
    val threadId: String,

    /** Per-thread monotonic sequence number — older = lower. Renderer sorts by this. */
    @ColumnInfo(name = "seq_no")
    val seqNo: Long,

    /** Wall-clock epoch-ms when the item was created (used for TTL cleanup, not ordering). */
    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    /** 'text' | 'widget' | 'notice' | 'command'. Discriminator. */
    @ColumnInfo(name = "kind")
    val kind: String,

    /** 'user' | 'assistant' | 'system' — null for non-text kinds. */
    @ColumnInfo(name = "role")
    val role: String? = null,

    /** Text body (Text), notice text (Notice), command verb (Command). */
    @ColumnInfo(name = "text_content")
    val textContent: String? = null,

    /** Widget type tag — 'timer' / 'weather' / 'route' / etc. Null for non-widget kinds. */
    @ColumnInfo(name = "widget_type")
    val widgetType: String? = null,

    /** Raw JSON payload for the widget. Null for non-widget kinds. */
    @ColumnInfo(name = "widget_payload")
    val widgetPayload: String? = null,

    /** True (1) while the assistant text is still mid-stream. Final flush flips to 0. */
    @ColumnInfo(name = "streaming", defaultValue = "0")
    val streaming: Boolean = false,

    @ColumnInfo(name = "location_lat") val locationLat: Double? = null,
    @ColumnInfo(name = "location_lon") val locationLon: Double? = null,
    @ColumnInfo(name = "place_name") val placeName: String? = null,
    @ColumnInfo(name = "phantom_state") val phantomState: String? = null,
    @ColumnInfo(name = "emotion_tag") val emotionTag: String? = null,
    @ColumnInfo(name = "pinned", defaultValue = "0") val pinned: Boolean = false,
    @ColumnInfo(name = "tags") val tags: String? = null,

    /** R2.7 — 384-dim MiniLM embedding of text_content (LifeLogConverters
     *  blob format). Written at final flush; null until indexed. */
    @ColumnInfo(name = "embedding", typeAffinity = ColumnInfo.BLOB)
    val embedding: FloatArray? = null,

    /** Wave 2 §3 Proposal 2 — the Доручення this item belongs to (in-memory
     *  since Wave 0, `StreamItem.turnId`; MIGRATION_22_23 carries it across
     *  the Room boundary). Null for every pre-migration row, by design —
     *  legacy history renders flat/ungrouped rather than being back-filled
     *  with a turn it never really had. */
    @ColumnInfo(name = "turn_id")
    val turnId: String? = null,
) {
    object Kind {
        const val TEXT = "text"
        const val WIDGET = "widget"
        const val NOTICE = "notice"
        const val COMMAND = "command"
    }

    object Role {
        const val USER = "user"
        const val ASSISTANT = "assistant"
        const val SYSTEM = "system"
    }
}
