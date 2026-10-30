package local.phantom.companion.feature.stream.ui.map.around

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Піни мапи — лише місця Nightpost (опубліковані сторінки з точкою), не місця з мапи пристрою. */
fun venuePinsOf(places: AroundPlaces?): List<AroundPlace> =
    (places as? AroundPlaces.Shown)?.places.orEmpty().filter { !it.venueId.isNullOrBlank() }

fun venuePinsGeoJson(pins: List<AroundPlace>): String = buildJsonObject {
    put("type", "FeatureCollection")
    put(
        "features",
        buildJsonArray {
            pins.forEach { p ->
                add(
                    buildJsonObject {
                        put("type", "Feature")
                        put(
                            "properties",
                            buildJsonObject {
                                put("venueId", p.venueId)
                                put("name", p.name)
                            },
                        )
                        put(
                            "geometry",
                            buildJsonObject {
                                put("type", "Point")
                                put("coordinates", JsonArray(listOf(JsonPrimitive(p.lon), JsonPrimitive(p.lat))))
                            },
                        )
                    },
                )
            }
        },
    )
}.toString()
