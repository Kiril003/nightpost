package local.phantom.companion.core.data.poi.bench

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.data.poi.OfflinePlacePack
import local.phantom.companion.core.data.poi.OfflinePoiPack
import local.phantom.companion.core.data.poi.OfflineStreetPack
import local.phantom.companion.core.data.poi.PoiDao
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.core.data.poi.PoiRepository

/**
 * Довідник, який телефон має з коробки: той самий байт у байт `country/ua`,
 * засіяний у тому ж порядку, що й `AppContainer` (пак → вулиці → місця → поселення).
 */
object UaCorpus {

    private val assets = File("src/main/assets/country/ua")

    val pack: OfflinePoiPack by lazy {
        OfflinePoiPack.merge(
            listOf("poi.json", "official_facilities.json", "invincibility_points.json")
                .map { OfflinePoiPack.parse(File(assets, it).readText()) },
        )
    }

    @Volatile private var opened: JdbcGazetteerDao? = null

    @Synchronized
    fun dao(): JdbcGazetteerDao {
        opened?.let { return it }
        val stamp = listOf("places.tsv", "streets.tsv", "settlements.tsv", "poi.json")
            .joinToString("-") { File(assets, it).length().toString() }
        val file = File("build/search-bench/ua-$stamp.sqlite")
        val fresh = !file.isFile
        if (fresh) {
            file.parentFile.mkdirs()
            File(file.path + ".part").delete()
        }
        val target = if (fresh) File(file.path + ".part") else file
        val dao = JdbcGazetteerDao(target)
        if (fresh) {
            runBlocking { repository(dao).seedGazetteerFromPack() }
            stream(dao, "streets.tsv") { OfflineStreetPack.chunkLines(it) }
            stream(dao, "places.tsv") { OfflinePlacePack.chunkLines(it) }
            stream(dao, "settlements.tsv") { OfflinePlacePack.chunkLines(it) }
            dao.close()
            target.renameTo(file)
            return JdbcGazetteerDao(file).also { opened = it }
        }
        return dao.also { opened = it }
    }

    private fun stream(
        dao: JdbcGazetteerDao,
        name: String,
        chunks: (java.io.BufferedReader) -> Sequence<List<local.phantom.companion.core.data.poi.PoiGazetteerEntity>>,
    ) {
        File(assets, name).bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readLine()
            dao.bulk(chunks(reader))
        }
    }

    fun repository(dao: JdbcGazetteerDao = dao()) = PoiRepository(
        poiDao = NoCache,
        gazetteer = dao,
        offlinePack = { pack },
    )

    object NoCache : PoiDao {
        override fun getPoisInBoundingBox(
            minLat: Double, maxLat: Double, minLon: Double, maxLon: Double,
        ): Flow<List<PoiEntity>> = flowOf(emptyList())

        override suspend fun insertPois(pois: List<PoiEntity>) = Unit
        override suspend fun clearExpired(expiryTime: Long) = Unit
    }
}
