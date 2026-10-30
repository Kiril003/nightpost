package local.phantom.companion.feature.precision.worldpack

import android.content.Context
import java.io.File
import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.net.pack.WorldPackGate
import local.phantom.companion.core.net.pack.WorldPackLink
import local.phantom.companion.feature.precision.RoadPackImporter

/**
 * [WorldPackGate] for road-mesh + transit packs pulled from the cloud
 * catalog rather than a paired PC or a manually-picked file. Once
 * `PackFetcher` hands over a sha256-verified file, this is the same
 * `RoadPackImporter.importFromFile` sink the PC-link card already uses —
 * a downloaded pack and a PC-pushed one are indistinguishable once verified.
 */
class RoadMeshPackGate(
    private val context: Context,
    rootDir: File = File(context.filesDir, "world-pack-road-mesh"),
    appBuild: () -> Int = { WorldPackLink.appBuild() },
) : WorldPackGate(rootDir, appBuild = appBuild) {

    var lastResult: RoadPackImporter.Result? = null
        private set

    override fun applyVerifiedFile(packId: String, file: File) {
        val result = runBlocking { RoadPackImporter.importFromFile(context, file, deleteAfter = false) }
        lastResult = result
        if (result is RoadPackImporter.Result.Failure) error(result.message)
    }
}
