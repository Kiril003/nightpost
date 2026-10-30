package local.phantom.companion.feature.stream.ui.map.venue

import androidx.compose.runtime.staticCompositionLocalOf

interface VenuePort {
    suspend fun load(venueId: String): VenueLoad
    suspend fun rsvp(eventId: String, going: Boolean): RsvpResult

    companion object {
        val None: VenuePort = object : VenuePort {
            override suspend fun load(venueId: String): VenueLoad = VenueLoad.Off
            override suspend fun rsvp(eventId: String, going: Boolean): RsvpResult = RsvpResult.Off
        }
    }
}

sealed interface VenueLoad {
    data class Ready(val card: VenueCard) : VenueLoad
    data object Missing : VenueLoad
    data object Off : VenueLoad
    data object Failed : VenueLoad
}

sealed interface RsvpResult {
    data class Done(val going: Boolean, val count: Int, val capacity: Int?) : RsvpResult
    data object Full : RsvpResult
    data object Cancelled : RsvpResult
    data object Over : RsvpResult
    data object NoKey : RsvpResult
    data object Failed : RsvpResult
    data object Off : RsvpResult
}

val LocalVenuePort = staticCompositionLocalOf<VenuePort> { VenuePort.None }
