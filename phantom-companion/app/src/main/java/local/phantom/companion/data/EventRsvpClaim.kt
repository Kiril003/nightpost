package local.phantom.companion.data

data class EventRsvpClaim(val eventId: String, val going: Boolean) : DeviceClaim {
    override fun sentence(pub: String, nonce: String, issuedMs: Long): String =
        VenuePageClient.rsvpSentence(pub, nonce, issuedMs, eventId, going)
}
