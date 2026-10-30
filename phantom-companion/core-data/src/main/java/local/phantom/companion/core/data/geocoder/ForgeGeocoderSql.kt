package local.phantom.companion.core.data.geocoder

/**
 * Дослівний двійник запитів кузні (`forge/vectors/geocoder-md.json`, поле
 * `sql`). ForgeGeocoderContractTest звіряє ці рядки з тим файлом — індекси
 * пакета зроблені під ЦЕЙ порядок стовпців і ЦІ умови.
 */
object ForgeGeocoderSql {

    const val COLS =
        "id,kind,osm_type,osm_id,lat,lon,rank,cls,sub,cat,name,name_uk,name_en,name_ru,city,names"

    const val MATCH =
        "SELECT $COLS FROM places WHERE id IN (SELECT rowid FROM places_fts WHERE places_fts MATCH ?) LIMIT ?"

    const val MATCH_NEAR =
        "SELECT $COLS FROM places WHERE id IN (SELECT rowid FROM places_fts WHERE places_fts MATCH ?) " +
            "ORDER BY (lat-?)*(lat-?)+(lon-?)*(lon-?)*? LIMIT ?"

    const val CATEGORY =
        "SELECT $COLS FROM places WHERE kind='poi' AND cat=? AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? " +
            "ORDER BY (lat-?)*(lat-?)+(lon-?)*(lon-?)*? LIMIT ?"

    const val ADDRESS =
        "SELECT a.street_id, a.housenumber, a.lat, a.lon, a.osm_type, a.osm_id, p.name, p.city FROM addresses a " +
            "JOIN places p ON p.id = a.street_id WHERE a.street_id IN ({}) AND a.housenumber = ? LIMIT ?"

    const val REVERSE_ADDR =
        "SELECT a.street_id, a.housenumber, a.lat, a.lon, a.osm_type, a.osm_id, p.name, p.city FROM addresses a " +
            "JOIN places p ON p.id = a.street_id WHERE a.lat BETWEEN ? AND ? AND a.lon BETWEEN ? AND ? LIMIT 400"

    const val REVERSE_PLACE =
        "SELECT $COLS FROM places WHERE kind IN ('street','place') AND lat BETWEEN ? AND ? " +
            "AND lon BETWEEN ? AND ? LIMIT 400"

    const val DF = "SELECT tri, n FROM tri_df WHERE tri IN ({})"

    fun expand(template: String, count: Int): String =
        template.replace("{}", List(count) { "?" }.joinToString(","))
}
