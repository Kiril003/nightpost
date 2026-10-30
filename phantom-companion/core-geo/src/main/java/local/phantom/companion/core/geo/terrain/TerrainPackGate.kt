package local.phantom.companion.core.geo.terrain

import android.content.Context
import java.io.File
import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.net.pack.WorldPackGate

/**
 * Приймання рельєфу з каталогу кузні. Перетворення PMTiles → MBTiles іде
 * ТУТ, разово, а не на кожному кадрі мапи.
 */
class TerrainPackGate(
    private val context: Context,
    rootDir: File = File(context.filesDir, "forge-pack-terrain"),
) : WorldPackGate(rootDir) {

    var lastResult: TerrainImport? = null
        private set

    override fun applyVerifiedFile(packId: String, file: File) {
        val result = runBlocking { TerrainPackImporter.importFromFile(context, file) }
        lastResult = result
        when (result) {
            is TerrainImport.Success -> TerrainPackStore(context).save(result.info)
            is TerrainImport.Failure -> error(result.message)
        }
    }
}
