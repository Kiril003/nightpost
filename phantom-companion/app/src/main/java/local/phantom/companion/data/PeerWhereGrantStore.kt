package local.phantom.companion.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import local.phantom.companion.core.net.peer.PeerWhere

/** Кому, з якою точністю й до коли — рядки `id=РІВЕНЬ` і `id=мс`. Жодної координати тут немає і не буде. */
class PeerWhereGrantStore(context: Context) : PeerWhereGrants {

    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun load(): Map<String, PeerWhere.Precision> =
        prefs.getStringSet(KEY, emptySet()).orEmpty().mapNotNull { row ->
            val id = row.substringBeforeLast('=', "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val p = PeerWhere.Precision.entries.firstOrNull { it.name == row.substringAfterLast('=') }
                ?: return@mapNotNull null
            id to p
        }.toMap()

    override fun save(grants: Map<String, PeerWhere.Precision>) {
        prefs.edit().putStringSet(KEY, grants.map { (id, p) -> "$id=${p.name}" }.toSet()).apply()
    }

    override fun loadEnds(): Map<String, Long> =
        prefs.getStringSet(ENDS, emptySet()).orEmpty().mapNotNull { row ->
            val id = row.substringBeforeLast('=', "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            row.substringAfterLast('=').toLongOrNull()?.let { id to it }
        }.toMap()

    override fun saveEnds(ends: Map<String, Long>) {
        prefs.edit().putStringSet(ENDS, ends.map { (id, t) -> "$id=$t" }.toSet()).apply()
    }

    override fun loadHidden(): Map<String, Long> =
        prefs.getStringSet(HIDDEN, emptySet()).orEmpty().mapNotNull { row ->
            val id = row.substringBeforeLast('=', "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            row.substringAfterLast('=').toLongOrNull()?.let { id to it }
        }.toMap()

    override fun saveHidden(hidden: Map<String, Long>) {
        prefs.edit().putStringSet(HIDDEN, hidden.map { (id, t) -> "$id=$t" }.toSet()).apply()
    }

    override fun loadLowBattery(): Boolean = prefs.getBoolean(BATTERY, false)

    override fun saveLowBattery(on: Boolean) {
        prefs.edit().putBoolean(BATTERY, on).apply()
    }

    override fun loadMeet(): Set<String> = prefs.getStringSet(MEET, emptySet()).orEmpty().toSet()

    override fun saveMeet(ids: Set<String>) {
        prefs.edit().putStringSet(MEET, ids).apply()
    }

    private companion object {
        const val MEET = "meet"
        const val HIDDEN = "hidden"
        const val BATTERY = "low_battery"
        const val FILE_NAME = "phantom_where_grants_v1"
        const val KEY = "grants"
        const val ENDS = "ends"
    }
}
