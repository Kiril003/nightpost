package local.phantom.companion.core.geo.basemap

import android.content.Context
import java.io.File
import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.net.pack.WorldPackGate
import local.phantom.companion.core.net.pack.WorldPackLink

/**
 * [WorldPackGate] for the offline basemap — the cloud-catalog sibling of the
 * manual-file path on the "ОФЛАЙН-ОСНОВА" card (`:feature-precision`). Once
 * `PackFetcher` hands over a sha256-verified file, this runs the same
 * acceptance test and lands it in the same [OfflineBasemapStore] slot a
 * manually-picked file goes through.
 */
class BasemapPackGate(
    private val context: Context,
    rootDir: File = File(context.filesDir, "world-pack-basemap"),
    appBuild: () -> Int = { WorldPackLink.appBuild() },
) : WorldPackGate(rootDir, appBuild = appBuild) {

    var lastResult: OfflineBasemapResult? = null
        private set

    override fun applyVerifiedFile(packId: String, file: File) {
        val result = runBlocking { OfflineBasemapImporter.importFromFile(context, file) }
        lastResult = result
        when (result) {
            is OfflineBasemapResult.Success -> OfflineBasemapStore(context).save(result.info)
            is OfflineBasemapResult.Failure -> error(result.message)
        }
    }
}
