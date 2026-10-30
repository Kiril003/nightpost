package local.phantom.companion.data.erase

import android.app.ActivityManager
import android.content.Context
import java.io.File
import local.phantom.companion.data.AppContainer

fun AppContainer.eraseEverything(context: Context): EraseEverything {
    val app = context.applicationContext ?: context
    return EraseEverything(
        journal = PrefsEraseJournal(app),
        server = EraseClient(
            claims = claims,
            rootSigner = EraseSigner(identity),
        ),
        licence = { license.release() },
        wipe = { keep ->
            val dataDir = app.applicationInfo?.dataDir?.let { File(it) } ?: app.filesDir?.parentFile
            if (dataDir != null) {
                File(dataDir, "shared_prefs").listFiles().orEmpty()
                    .filter { f ->
                        val rel = "shared_prefs/${f.name}"
                        f.extension == "xml" && rel !in keep && f.name !in keep && f.nameWithoutExtension !in keep
                    }
                    .forEach { f -> runCatching { app.deleteSharedPreferences(f.nameWithoutExtension) } }
                wipeDirectoryExcept(dataDir, keep)
            }
        },
        wipeKey = {
            app.getSystemService(ActivityManager::class.java)?.clearApplicationUserData()
        },
    )
}

internal fun wipeDirectoryExcept(root: File, keep: Set<String>) {
    if (!root.exists()) return
    fun walk(dir: File): Boolean {
        var keptAny = false
        for (child in dir.listFiles().orEmpty()) {
            val rel = child.relativeTo(root).invariantSeparatorsPath
            if (rel == "lib" || rel in keep || child.name in keep || child.name.removeSuffix(".xml") in keep) {
                keptAny = true
                continue
            }
            if (child.isDirectory) {
                if (walk(child)) {
                    keptAny = true
                } else {
                    child.delete()
                }
            } else {
                child.delete()
            }
        }
        return keptAny
    }
    walk(root)
}
