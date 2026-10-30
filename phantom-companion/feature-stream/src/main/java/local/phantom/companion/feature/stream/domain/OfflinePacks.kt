package local.phantom.companion.feature.stream.domain

object OfflinePacks {

    enum class State { Downloading, Ready, OtherMap }

    enum class Offer { Save, SaveRest, SaveForThisMap, AlreadyHave }

    fun stateOf(complete: Boolean, packStyleUrl: String?, currentStyleUrl: String): State = when {
        !complete -> State.Downloading
        packStyleUrl != null && packStyleUrl != currentStyleUrl -> State.OtherMap
        else -> State.Ready
    }

    fun offer(verdict: OfflineCoverage.Verdict): Offer = when (verdict) {
        OfflineCoverage.Verdict.Covered -> Offer.AlreadyHave
        OfflineCoverage.Verdict.Partial -> Offer.SaveRest
        OfflineCoverage.Verdict.WrongStyle -> Offer.SaveForThisMap
        OfflineCoverage.Verdict.None -> Offer.Save
    }

    fun megabytes(bytes: Long): Double = bytes / (1024.0 * 1024.0)

    data class Painted(
        val id: Long,
        val area: OfflineCoverage.Area,
        val colorHex: String,
        val selected: Boolean = false,
    )

    fun geoJson(painted: List<Painted>): String {
        val features = painted.filter { it.area.valid }.joinToString(",") { p ->
            val a = p.area
            val ring = listOf(
                a.westLon to a.southLat,
                a.eastLon to a.southLat,
                a.eastLon to a.northLat,
                a.westLon to a.northLat,
                a.westLon to a.southLat,
            ).joinToString(",") { (lon, lat) -> "[$lon,$lat]" }
            "{\"type\":\"Feature\",\"properties\":{" +
                "\"pack_id\":\"${p.id}\"," +
                "\"color\":\"${p.colorHex}\"," +
                "\"selected\":${p.selected}}," +
                "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[$ring]]}}"
        }
        return "{\"type\":\"FeatureCollection\",\"features\":[$features]}"
    }
}
