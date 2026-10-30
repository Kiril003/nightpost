package local.phantom.companion.ui.messenger

import local.phantom.companion.core.data.country.RegionProfile
import org.json.JSONObject

internal object WebMessengerRegion {

    fun claims(method: String, seg: List<String>): Boolean =
        method == "GET" && seg.size == 2 && seg[1] == "region"

    fun body(p: RegionProfile): JSONObject =
        JSONObject()
            .put("country", p.country ?: JSONObject.NULL)
            .put("currency", p.currency)
            .put("emergency", p.emergency)
            .put("metric", p.metric)
}
