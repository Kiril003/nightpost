package local.phantom.companion.data.backup

import android.content.Context

internal object BackupSettings {

    fun read(context: Context): Map<String, Map<String, Any?>> {
        val out = LinkedHashMap<String, Map<String, Any?>>()
        for (storeName in BackupManifest.ALLOWED_SETTINGS_STORES) {
            val prefs = runCatching { context.getSharedPreferences(storeName, Context.MODE_PRIVATE) }.getOrNull() ?: continue
            val entries = prefs.all.filterValues { it != null }
            if (entries.isNotEmpty()) out[storeName] = entries
        }
        return out
    }

    fun write(context: Context, section: BackupManifest.SettingsSection) {
        section.typed().forEach { (storeName, values) ->
            val edit = context.getSharedPreferences(storeName, Context.MODE_PRIVATE).edit()
            values.forEach { (k, v) ->
                when (v) {
                    is Boolean -> edit.putBoolean(k, v)
                    is Int -> edit.putInt(k, v)
                    is Long -> edit.putLong(k, v)
                    is Float -> edit.putFloat(k, v)
                    is Set<*> -> edit.putStringSet(k, v.filterIsInstance<String>().toSet())
                    else -> edit.putString(k, v.toString())
                }
            }
            edit.commit()
        }
    }
}
