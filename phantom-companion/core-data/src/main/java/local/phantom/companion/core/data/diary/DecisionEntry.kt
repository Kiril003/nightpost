package local.phantom.companion.core.data.diary

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Phase 1.5-D — single decision recorded by the operator (or, in a future
 * phase, by PHANTOM OS when it observes a deliberate choice). Lives in
 * the SQLCipher-encrypted [PhantomDatabase] alongside profiles + vault
 * entries so a single backup envelope covers everything.
 *
 * Schema is intentionally narrow:
 *  - [id] — UUID, set client-side at create time;
 *  - [createdAt] — epoch ms, used for descending-by-time list order;
 *  - [title] — short headline («переїхати в Лхасу на зиму»), bounded
 *    softly by the editor sheet (~80 chars); database does not enforce
 *    a length cap so a future migration that allows longer titles needs
 *    no schema change;
 *  - [context] — free-form why-and-how-we-got-here body;
 *  - [status] — one of three Ukrainian lowercase tokens (see
 *    [DecisionStatus]). Stored as String, not enum, because Room would
 *    otherwise need a TypeConverter just for this and the alert UI
 *    already filters on the raw string.
 *
 * Reference: `/tmp/phantom-design-4/phantom-app2/project/surfaces.jsx#DecisionDiary`
 * (lines 601-674) — the prototype keeps the same five fields per entry.
 */
@Entity(tableName = "decision_entries")
data class DecisionEntry(
    @PrimaryKey
    val id: String,
    val createdAt: Long,
    val title: String,
    val context: String,
    val status: String,
)

/**
 * Canonical status tokens. Stored as raw strings in the entity (see
 * the rationale on [DecisionEntry.status]); this companion just gives
 * callers a typed surface that won't drift if anyone needs to read or
 * filter the column.
 */
object DecisionStatus {
    /** Decision still applies — green dot in the list. */
    const val ACTIVE: String = "актуально"

    /** Circumstances changed; ask Familiar to revisit. Coral border. */
    const val REVISIT: String = "переглянути"

    /** Decision honoured / closed out. Faded ink. */
    const val DONE: String = "завершено"

    val ALL: List<String> = listOf(ACTIVE, REVISIT, DONE)
}
