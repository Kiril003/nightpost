package local.phantom.companion.core.solana

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Рівень стейку SKR усередині знака. Вектори `badge_skr` і `badge_staker_only` підписано
 * серверним кодом (seeker.badge з рівнем + license_crypto.sign_certificate) тестовим ключем.
 */
class SeekerBadgeSkrTest {

    private val vector = SolanaRpc.JSON.parseToJsonElement(javaClass.getResource("/solana/seeker_badge_vector.json")!!.readText()).jsonObject
    private val key = vector["public_key_hex"]!!.jsonPrimitive.content
    private val skr = vector["badge_skr"]!!.jsonObject
    private val stakerOnly = vector["badge_staker_only"]!!.jsonObject
    private val now = 1_790_000_060L
    private val sender = "peer-olena-01"

    private fun JsonObject.with(field: String, value: JsonPrimitive) = JsonObject(this + (field to value)).toString()
    private fun valid(json: String) = (SeekerBadge.verify(json, key, sender, now) as SeekerBadge.Verdict.Valid).badge

    @Test
    fun seekerWhoStakesShowsBothOffline() {
        val b = valid(skr.toString())
        assertTrue(b.sgt)
        assertEquals(SeekerBadge.SkrTier.K10, b.skrTier)
        assertEquals(listOf(SeekerBadge.Part.SEEKER, SeekerBadge.Part.SKR_10K), b.parts)
    }

    @Test
    fun stakerWithoutSgtIsASupporterNotASeeker() {
        val b = valid(stakerOnly.toString())
        assertEquals(false, b.sgt)
        assertEquals(listOf(SeekerBadge.Part.SKR_1K), b.parts)
    }

    @Test
    fun forgingTheTierInsideTheBadgeBreaksTheSignature() {
        assertEquals(SeekerBadge.Verdict.Forged, SeekerBadge.verify(stakerOnly.with("skr_tier", JsonPrimitive("10k")), key, sender, now))
        assertEquals(SeekerBadge.Verdict.Forged, SeekerBadge.verify(skr.with("skr_tier", JsonPrimitive("staker")), key, sender, now))
        // Стейкер «дописав» собі SGT.
        assertEquals(SeekerBadge.Verdict.Forged, SeekerBadge.verify(stakerOnly.with("sgt", JsonPrimitive(true)), key, sender, now))
        // Рівень дописано у старий знак без рівня — теж поза підписом.
        val old = vector["badge"]!!.jsonObject
        val grafted = JsonObject(old + mapOf("skr_tier" to JsonPrimitive("10k"), "skr_checked_at" to JsonPrimitive(1_790_000_000L))).toString()
        assertEquals(SeekerBadge.Verdict.Forged, SeekerBadge.verify(grafted, key, sender, now))
    }

    @Test
    fun badgeWithNeitherSgtNorStakeOrAnUnknownTierIsNotABadge() {
        assertNull(SeekerBadge.parse(stakerOnly.with("skr_tier", JsonPrimitive("none"))))
        assertNull(SeekerBadge.parse(skr.with("skr_tier", JsonPrimitive("whale"))))
        assertNull(SeekerBadge.parse(JsonObject(skr - "skr_checked_at").toString()))
        assertEquals(SeekerBadge.Verdict.Malformed, SeekerBadge.verify(stakerOnly.with("skr_tier", JsonPrimitive("none")), key, sender, now))
    }

    @Test
    fun oldBadgeWithoutStakeStillVerifiesAndSaysOnlySeeker() {
        val b = valid(vector["badge"]!!.jsonObject.toString())
        assertNull(b.skrTier)
        assertEquals(listOf(SeekerBadge.Part.SEEKER), b.parts)
    }

    @Test
    fun badgeRefreshReplyIsReadHonestly() {
        val json = skr.toString()
        assertEquals(BadgeRefresh.Fresh(json), SeekerClaimClient.parseBadge(200, """{"badge":$json}"""))
        assertEquals(BadgeRefresh.Nothing, SeekerClaimClient.parseBadge(404, """{"reason":"not_seeker"}"""))
        assertEquals(BadgeRefresh.Unavailable, SeekerClaimClient.parseBadge(503, """{"reason":"chain_unreachable"}"""))
        assertEquals(BadgeRefresh.Refused("bad_reply"), SeekerClaimClient.parseBadge(200, """{"badge":{"kind":"x"}}"""))
        assertEquals(BadgeRefresh.Refused("Not Found"), SeekerClaimClient.parseBadge(404, """{"detail":"Not Found"}"""))
    }
}
