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
 * Вектор `seeker_badge_vector.json` підписано СЕРВЕРНИМ кодом (seeker.badge +
 * license_crypto.sign_certificate) тестовим ключем — тож тест доводить, що телефон
 * перевіряє саме те, що видає сервер, а не власну копію формату.
 */
class SeekerBadgeTest {

    private val vector = SolanaRpc.JSON.parseToJsonElement(javaClass.getResource("/solana/seeker_badge_vector.json")!!.readText()).jsonObject
    private val key = vector["public_key_hex"]!!.jsonPrimitive.content
    private val badge = vector["badge"]!!.jsonObject
    private val json = badge.toString()
    private val issued = 1_790_000_000L
    private val sender = "peer-olena-01"

    private fun with(field: String, value: JsonPrimitive) = JsonObject(badge + (field to value)).toString()

    @Test
    fun serverBadgeVerifiesOfflineForItsOwnSender() {
        val v = SeekerBadge.verify(json, key, sender, issued + 60)
        assertTrue(v is SeekerBadge.Verdict.Valid)
        assertEquals("4vJ9JU1bJJE96FWSJKvHsmmFADCg4gpZQff4P3bkLKi", (v as SeekerBadge.Verdict.Valid).badge.wallet)
    }

    @Test
    fun forgedBadgeIsRejected() {
        // Особу підмінено після підпису.
        assertEquals(SeekerBadge.Verdict.Forged, SeekerBadge.verify(with("identity", JsonPrimitive("peer-thief-02")), key, "peer-thief-02", issued + 60))
        // Строк «подовжено» руками.
        assertEquals(SeekerBadge.Verdict.Forged, SeekerBadge.verify(with("expires", JsonPrimitive(issued + 10 * 365 * 86400L)), key, sender, issued + 60))
        // Підпис іншим ключем (чужий «сервер»).
        assertEquals(SeekerBadge.Verdict.Forged, SeekerBadge.verify(json, "11".repeat(32), sender, issued + 60))
        // Застосунок без ключа нічого не вважає перевіреним.
        assertEquals(SeekerBadge.Verdict.Forged, SeekerBadge.verify(json, "", sender, issued + 60))
    }

    @Test
    fun expiredBadgeIsRejected() {
        val expires = badge["expires"]!!.jsonPrimitive.content.toLong()
        assertEquals(SeekerBadge.Verdict.Expired, SeekerBadge.verify(json, key, sender, expires))
        assertEquals(SeekerBadge.Verdict.Expired, SeekerBadge.verify(json, key, sender, issued - 3600))
        assertTrue(SeekerBadge.fresh(json, expires - 1))
        assertTrue(!SeekerBadge.fresh(json, expires))
    }

    @Test
    fun honestBadgeOfSomeoneElseIsNotTheSendersBadge() {
        assertEquals(SeekerBadge.Verdict.NotSender, SeekerBadge.verify(json, key, "peer-thief-02", issued + 60))
        assertEquals(SeekerBadge.Verdict.NotSender, SeekerBadge.verify(json, key, null, issued + 60))
    }

    @Test
    fun licenceCertificateCannotPassForABadge() {
        assertEquals(SeekerBadge.Verdict.Malformed, SeekerBadge.verify(with("kind", JsonPrimitive("activation")), key, sender, issued + 60))
        assertEquals(SeekerBadge.Verdict.Malformed, SeekerBadge.verify(with("sgt", JsonPrimitive(false)), key, sender, issued + 60))
        assertEquals(SeekerBadge.Verdict.Malformed, SeekerBadge.verify("not json", key, sender, issued + 60))
    }

    @Test
    fun badgeRidesOnTheCashVoucherAndOldVouchersStillDecode() {
        val pocket = Ed25519Keypair(ByteArray(32) { 5 })
        val to = SolanaKey.of(ByteArray(32) { 42 })!!
        val nonce = Programs.seededAddress(pocket.publicKey, "phantom-cash-0", Programs.SYSTEM)
        val msg = CashVoucher.message(pocket.publicKey, nonce, ByteArray(32) { 9 }, to, Coin.USDC, Cluster.DEVNET, 5_000_000)
        val plain = CashVoucher.of(Cluster.DEVNET, Coin.USDC, 5_000_000, to, SolanaTx.sign(msg, listOf(pocket)))
        val old = CashVoucher.decode(plain.encode())!!
        assertNull(old.badge)
        val carried = CashVoucher.decode(plain.copy(badge = json).encode())!!
        assertEquals(json, carried.badge)
        assertEquals(plain.payment, carried.payment)
        assertTrue(SeekerBadge.verify(carried.badge!!, key, sender, issued + 60) is SeekerBadge.Verdict.Valid)
        assertNull(CashVoucher.decode(plain.encode() + ";%%%"))
    }

    @Test
    fun claimReplyHandsTheBadgeToThePhone() {
        val r = SeekerClaimClient.parseClaim(200, """{"license_key":"PHTM-A","pro_until":"2026-11-01","mint":"m","badge":$json}""")
        assertEquals(json, (r as SeekerClaimResult.Granted).badge)
        val used = SeekerClaimClient.parseClaim(409, """{"reason":"month_used","pro_until":"2026-11-01","badge":$json}""")
        assertEquals(json, (used as SeekerClaimResult.AlreadyUsed).badge)
    }
}
