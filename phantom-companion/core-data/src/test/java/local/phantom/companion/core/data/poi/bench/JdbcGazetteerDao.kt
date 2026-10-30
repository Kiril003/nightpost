package local.phantom.companion.core.data.poi.bench

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import local.phantom.companion.core.data.poi.PoiConverters
import local.phantom.companion.core.data.poi.PoiFtsEntity
import local.phantom.companion.core.data.poi.PoiGazetteerDao
import local.phantom.companion.core.data.poi.PoiGazetteerEntity

/**
 * Той самий SQL, що в Room-DAO, над справжньою SQLite з FTS4/unicode61 —
 * замість підробки, яка вдає префіксний збіг у Kotlin.
 */
class JdbcGazetteerDao(file: File) : PoiGazetteerDao, AutoCloseable {

    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
    private val converters = PoiConverters()

    init {
        connection.createStatement().use { st ->
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS poi_gazetteer (id INTEGER PRIMARY KEY NOT NULL, lat REAL NOT NULL, " +
                    "lon REAL NOT NULL, name TEXT NOT NULL, category TEXT NOT NULL, subcategory TEXT, " +
                    "seenAt INTEGER NOT NULL, keywords TEXT NOT NULL, tags TEXT NOT NULL)",
            )
            st.executeUpdate("CREATE INDEX IF NOT EXISTS i_ll ON poi_gazetteer(lat, lon)")
            st.executeUpdate("CREATE INDEX IF NOT EXISTS i_cat ON poi_gazetteer(category)")
            st.executeUpdate("CREATE INDEX IF NOT EXISTS i_sub ON poi_gazetteer(subcategory, lat, lon)")
            st.executeUpdate(
                "CREATE VIRTUAL TABLE IF NOT EXISTS poi_fts USING fts4(name, keywords, " +
                    "content=`poi_gazetteer`, tokenize=unicode61)",
            )
        }
    }

    fun bulk(rows: Sequence<List<PoiGazetteerEntity>>): Int {
        var n = 0
        connection.autoCommit = false
        val place = connection.prepareStatement("INSERT OR REPLACE INTO poi_gazetteer VALUES (?,?,?,?,?,?,?,?,?)")
        val term = connection.prepareStatement("INSERT OR REPLACE INTO poi_fts(rowid, name, keywords) VALUES (?,?,?)")
        for (chunk in rows) {
            for (e in chunk) {
                bindPlace(place, e)
                place.addBatch()
                term.setLong(1, e.id)
                term.setString(2, e.name)
                term.setString(3, e.keywords)
                term.addBatch()
                n++
            }
            place.executeBatch()
            term.executeBatch()
        }
        connection.commit()
        connection.autoCommit = true
        place.close()
        term.close()
        return n
    }

    private fun bindPlace(st: PreparedStatement, e: PoiGazetteerEntity) {
        st.setLong(1, e.id)
        st.setDouble(2, e.lat)
        st.setDouble(3, e.lon)
        st.setString(4, e.name)
        st.setString(5, e.category)
        st.setString(6, e.subcategory)
        st.setLong(7, e.seenAt)
        st.setString(8, e.keywords)
        st.setString(9, converters.mapToString(e.tags))
    }

    override suspend fun insertPlaces(places: List<PoiGazetteerEntity>) {
        bulk(sequenceOf(places))
    }

    override suspend fun insertTerms(terms: List<PoiFtsEntity>) = Unit

    override suspend fun upsert(places: List<PoiGazetteerEntity>) {
        if (places.isNotEmpty()) bulk(sequenceOf(places))
    }

    override suspend fun searchInBox(
        query: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double,
        atLat: Double, atLon: Double, lonScaleSq: Double, limit: Int,
    ): List<PoiGazetteerEntity> = rows(
        "SELECT g.* FROM poi_gazetteer g JOIN poi_fts f ON f.rowid = g.id WHERE poi_fts MATCH ? " +
            "AND g.lat BETWEEN ? AND ? AND g.lon BETWEEN ? AND ? $ORDER_G LIMIT ?",
        query, minLat, maxLat, minLon, maxLon, atLat, atLat, atLon, atLon, lonScaleSq, limit,
    )

    override suspend fun searchAnywhere(
        query: String, atLat: Double, atLon: Double, lonScaleSq: Double, limit: Int,
    ): List<PoiGazetteerEntity> = rows(
        "SELECT g.* FROM poi_gazetteer g JOIN poi_fts f ON f.rowid = g.id WHERE poi_fts MATCH ? $ORDER_G LIMIT ?",
        query, atLat, atLat, atLon, atLon, lonScaleSq, limit,
    )

    override suspend fun byCategoryInBox(
        subcategories: List<String>, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double,
        atLat: Double, atLon: Double, lonScaleSq: Double, limit: Int,
    ): List<PoiGazetteerEntity> = rows(
        "SELECT * FROM poi_gazetteer WHERE subcategory IN (${subcategories.joinToString(",") { "?" }}) " +
            "AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? $ORDER LIMIT ?",
        *subcategories.toTypedArray(), minLat, maxLat, minLon, maxLon, atLat, atLat, atLon, atLon, lonScaleSq, limit,
    )

    override suspend fun nearestInBox(
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double,
        atLat: Double, atLon: Double, lonScaleSq: Double, limit: Int,
    ): List<PoiGazetteerEntity> = rows(
        "SELECT * FROM poi_gazetteer WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? $ORDER LIMIT ?",
        minLat, maxLat, minLon, maxLon, atLat, atLat, atLon, atLon, lonScaleSq, limit,
    )

    override suspend fun nearestPlacesInBox(
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double,
        atLat: Double, atLon: Double, lonScaleSq: Double, limit: Int,
    ): List<PoiGazetteerEntity> = rows(
        "SELECT * FROM poi_gazetteer WHERE category NOT IN ('street', 'place') " +
            "AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? $ORDER LIMIT ?",
        minLat, maxLat, minLon, maxLon, atLat, atLat, atLon, atLon, lonScaleSq, limit,
    )

    override suspend fun inBox(
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, limit: Int,
    ): List<PoiGazetteerEntity> = rows(
        "SELECT * FROM poi_gazetteer WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? ORDER BY seenAt DESC LIMIT ?",
        minLat, maxLat, minLon, maxLon, limit,
    )

    override suspend fun byIds(ids: List<Long>): List<PoiGazetteerEntity> =
        if (ids.isEmpty()) emptyList() else rows(
            "SELECT * FROM poi_gazetteer WHERE id IN (${ids.joinToString(",") { "?" }})",
            *ids.toTypedArray(),
        )

    override suspend fun size(): Int =
        connection.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM poi_gazetteer").use { it.next(); it.getInt(1) }
        }

    private fun rows(sql: String, vararg args: Any): List<PoiGazetteerEntity> {
        connection.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a ->
                when (a) {
                    is String -> st.setString(i + 1, a)
                    is Double -> st.setDouble(i + 1, a)
                    is Int -> st.setInt(i + 1, a)
                    is Long -> st.setLong(i + 1, a)
                    else -> error("невідомий тип $a")
                }
            }
            st.executeQuery().use { rs ->
                val out = ArrayList<PoiGazetteerEntity>()
                while (rs.next()) out += rs.entity()
                return out
            }
        }
    }

    private fun ResultSet.entity() = PoiGazetteerEntity(
        id = getLong("id"),
        lat = getDouble("lat"),
        lon = getDouble("lon"),
        name = getString("name"),
        category = getString("category"),
        subcategory = getString("subcategory"),
        seenAt = getLong("seenAt"),
        keywords = getString("keywords"),
        tags = converters.stringToMap(getString("tags")),
    )

    override fun close() = connection.close()

    private companion object {
        const val ORDER = "ORDER BY ((lat - ?) * (lat - ?)) + ((lon - ?) * (lon - ?) * ?)"
        const val ORDER_G = "ORDER BY ((g.lat - ?) * (g.lat - ?)) + ((g.lon - ?) * (g.lon - ?) * ?)"
    }
}
