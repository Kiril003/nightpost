package local.phantom.companion.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import local.phantom.companion.feature.stream.ui.map.venue.RsvpResult
import local.phantom.companion.feature.stream.ui.map.venue.VenueLoad
import local.phantom.companion.feature.stream.ui.map.venue.VenuePort
import local.phantom.companion.feature.stream.ui.map.venue.parseVenueCard

class VenuePageClient(
    private val baseUrl: String,
    private val signer: VenueClient.DeviceSigner,
    private val http: VenueClient.Http = VenueClient.Http { _, _, _ -> null },
) : VenuePort {

    override suspend fun load(venueId: String): VenueLoad {
        if (!VENUE_ID.matches(venueId)) return VenueLoad.Missing
        val root = root() ?: return VenueLoad.Off
        val pageAnswer = VenueClient.answerOf(http.call("GET", "$root/venues/$venueId", null))
        val pageJson = when (pageAnswer) {
            is VenueClient.Answer.Ok -> pageAnswer.json
            VenueClient.Answer.Missing -> return VenueLoad.Missing
            VenueClient.Answer.NotEnabled -> return VenueLoad.Off
            else -> return VenueLoad.Failed
        }
        val factsAnswer = VenueClient.answerOf(http.call("GET", "$root/venues/$venueId/facts", null))
        val factsJson = (factsAnswer as? VenueClient.Answer.Ok)?.json
        val card = parseVenueCard(pageJson, factsJson) ?: return VenueLoad.Failed
        return VenueLoad.Ready(card)
    }

    override suspend fun rsvp(eventId: String, going: Boolean): RsvpResult {
        if (!EVENT_ID.matches(eventId)) return RsvpResult.Failed
        val root = root() ?: return RsvpResult.Off
        val proof = withContext(Dispatchers.IO) { signer.sign(EventRsvpClaim(eventId, going)) } ?: return RsvpResult.NoKey
        val body = buildJsonObject {
            put("pub_key", proof.pubKey)
            put("nonce", proof.nonce)
            put("issued_ms", proof.issuedMs)
            put("signature", proof.signature)
            put("going", going)
        }.toString()
        return when (val ans = VenueClient.answerOf(http.call("POST", "$root/events/$eventId/rsvp", body))) {
            is VenueClient.Answer.Ok -> {
                val obj = runCatching { Json.parseToJsonElement(ans.json) }.getOrNull() as? JsonObject
                    ?: return RsvpResult.Failed
                val respGoing = (obj["going"] as? JsonPrimitive)?.booleanOrNull ?: going
                val count = (obj["count"] as? JsonPrimitive)?.intOrNull ?: return RsvpResult.Failed
                val cap = (obj["capacity"] as? JsonPrimitive)?.intOrNull
                RsvpResult.Done(respGoing, count, cap)
            }
            is VenueClient.Answer.Refused -> when (ans.reason) {
                "event_full" -> RsvpResult.Full
                "event_cancelled" -> RsvpResult.Cancelled
                "event_over" -> RsvpResult.Over
                "no_such_event" -> RsvpResult.Off
                else -> RsvpResult.Failed
            }
            VenueClient.Answer.NoKey -> RsvpResult.NoKey
            VenueClient.Answer.NotEnabled, VenueClient.Answer.Missing -> RsvpResult.Off
            else -> RsvpResult.Failed
        }
    }

    private fun root(): String? = baseUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() }

    companion object {
        private val VENUE_ID = Regex("[0-9a-f]{16}")
        private val EVENT_ID = Regex("[0-9a-f]{16}\\.[A-Za-z0-9_-]{1,36}")

        fun rsvpSentence(pub: String, nonce: String, issuedMs: Long, eventId: String, going: Boolean): String =
            "phantom-event-rsvp|v1|$pub|$nonce|$issuedMs|$eventId|${if (going) 1 else 0}"
    }
}
