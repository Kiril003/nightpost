package local.phantom.companion.core.data.geocoder

import android.content.Context
import java.io.File
import local.phantom.companion.core.net.pack.WorldPackGate

/** Приймання пакета місць із каталогу кузні — той самий підпис, та сама брама. */
class GeocoderPackGate(
    private val context: Context,
    rootDir: File = File(context.filesDir, "forge-pack-geocoder"),
) : WorldPackGate(rootDir) {

    var lastResult: ForgeGeocoderImport? = null
        private set

    override fun applyVerifiedFile(packId: String, file: File) {
        val result = ForgeGeocoderImporter.importFromFile(context, file)
        lastResult = result
        when (result) {
            is ForgeGeocoderImport.Success -> ForgeGeocoderStore(context).save(result.info)
            is ForgeGeocoderImport.Failure -> error(result.message)
        }
    }
}
