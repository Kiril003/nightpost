package local.phantom.companion.feature.stream.vault

import androidx.annotation.StringRes
import local.phantom.companion.core.data.vault.VaultEntity
import local.phantom.companion.feature.stream.R

// One table holds four unrelated things: a credential, a note kept out of a
// conversation, a place pinned on the map, and the map's own search history.
enum class VaultKind {
    Unopenable,
    Secret,
    Note,
    Trace,
    ;

    @get:StringRes
    val title: Int
        get() = when (this) {
            Unopenable -> R.string.vault_group_broken
            Secret -> R.string.vault_group_secret
            Note -> R.string.vault_group_note
            Trace -> R.string.vault_group_trace
        }

    @get:StringRes
    val blurb: Int
        get() = when (this) {
            Unopenable -> R.string.vault_group_broken_why
            Secret -> R.string.vault_group_secret_why
            Note -> R.string.vault_group_note_why
            Trace -> R.string.vault_group_trace_why
        }

    /** Only a credential earns a mask; a saved note is content, not a secret. */
    val masksValue: Boolean get() = this == Secret

    val readable: Boolean get() = this != Unopenable
}

data class VaultGroup(val kind: VaultKind, val entries: List<VaultEntity>)

private val SECRET_KINDS = setOf("token", "wallet", "pgp", "cert", "key", "password")

// map-poi rows are no longer a Vault kind: SavedPlaceMigration moves them into
// `saved_places` and deletes the Vault row on every load, so one that survives
// here is unmigrated leftover, not a place — it falls through to Note below.
fun classify(entity: VaultEntity): VaultKind = when {
    entity.value.isEmpty() -> VaultKind.Unopenable
    entity.metadata == MAP_RECENT_META -> VaultKind.Trace
    entity.type == "secret" || entity.type == "credential" -> VaultKind.Secret
    kindTagOf(entity) in SECRET_KINDS -> VaultKind.Secret
    entity.key.startsWith("secrets.") -> VaultKind.Secret
    else -> VaultKind.Note
}

// Not alphabetical: a broken row is the only one that needs a decision, and a
// search trace nobody chose to store is the last thing the operator came for.
private val READING_ORDER = listOf(
    VaultKind.Unopenable,
    VaultKind.Secret,
    VaultKind.Note,
    VaultKind.Trace,
)

fun shelve(entries: List<VaultEntity>): List<VaultGroup> {
    val byKind = entries.groupBy(::classify)
    return READING_ORDER.mapNotNull { kind ->
        val rows = byKind[kind] ?: return@mapNotNull null
        VaultGroup(kind, rows.sortedByDescending { it.updatedAt })
    }
}

// The schema has no column for the fine-grained kinds, so they ride metadata.
fun kindTagOf(entity: VaultEntity): String = metaToken(entity, "kind=") ?: entity.type

/**
 * Null means «no operator-supplied hint», and the caller draws the fixed mask
 * from strings.xml. Never a suffix of the value: «••••${value.takeLast(4)}» put
 * a 4-digit PIN on screen whole and gave up two thirds of a six-character one.
 */
fun previewOf(entity: VaultEntity): String? = metaToken(entity, "preview=")

private fun metaToken(entity: VaultEntity, prefix: String): String? =
    entity.metadata.orEmpty()
        .split(';')
        .firstOrNull { it.startsWith(prefix) }
        ?.removePrefix(prefix)
        ?.takeIf { it.isNotBlank() }

const val MAP_RECENT_META = "map-recent"
const val MAP_POI_META = "map-poi"
