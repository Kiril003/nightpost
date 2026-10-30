package local.phantom.companion.core.data.vault

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Encrypted vault entry for GHOST/SHADOW states.
 * Stored in SQLCipher-encrypted database.
 */
@Entity(tableName = "vault_entries")
@Serializable
data class VaultEntity(
    @PrimaryKey
    val id: String,
    val key: String,
    val value: String,
    val type: String, // "secret", "note", "credential"
    val metadata: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
