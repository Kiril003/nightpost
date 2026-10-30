package local.phantom.companion.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Заявка на «першу оплачену частку» між мостом (веб просить) і Профілем (людина
 * підтверджує відбитком). Доводиться: поки людина не натисне, сервера не питають
 * і нічого не підписують; після натискання — рівно один виклик із підписом
 * гаманця; зараховане не питає відбитка вдруге; остаточна відмова не повторюється,
 * а тимчасова лишає заявку.
 */
class PointsClaimsTest {

    private val claim = PointsClaims.Claim("devnet", "5".repeat(88), "A".repeat(44), "7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d")

    private class Api : PointsApi {
        val asked = mutableListOf<String>()
        var signed: ByteArray? = null
        var answer: VenueClient.Answer = VenueClient.Answer.Ok("""{"status":"confirmed","awarded":[{"kind":"payment.first","ref":"first","points":5,"at":1}]}""")
        override suspend fun checkin(meetupId: String, venueId: String, cell: String, meetupAtMs: Long): VenueClient.Answer =
            fail("заявка на оплату не відмічає на місці") as Nothing

        override suspend fun settled(cluster: String, tx: String, address: String, ref: String, walletSign: (ByteArray) -> ByteArray): VenueClient.Answer {
            asked += "$cluster:$tx:$address:$ref"
            signed = walletSign("речення".toByteArray())
            return answer
        }

        override suspend fun me(): VenueClient.Answer = fail("заявка не питає балів") as Nothing
    }

    private class Disk : PointsClaims.Memory {
        val tx = linkedSetOf<String>()
        override fun counted(): Set<String> = tx
        override fun remember(tx: String) { this.tx += tx }
    }

    private val sign: (ByteArray) -> ByteArray = { it.reversedArray() }

    @Test
    fun `заявку лише приймають — сервера не питають і нічого не підписують`() {
        val api = Api()
        val claims = PointsClaims(api)
        assertEquals(PointsClaims.Offered.Waiting, claims.offer(claim))
        assertEquals(claim, claims.pending.value)
        assertTrue("до відбитка сервер уже спитали: ${api.asked}", api.asked.isEmpty())
        assertNull(api.signed)
        // Веб повторює заявку — стан той самий, а не друга заявка.
        assertEquals(PointsClaims.Offered.Waiting, claims.offer(claim))
        assertEquals(claim, claims.pending.value)
    }

    @Test
    fun `нова заявка заміняє стару — веб повторює останню`() {
        val claims = PointsClaims(Api())
        claims.offer(claim)
        val newer = claim.copy(tx = "6".repeat(88))
        claims.offer(newer)
        assertEquals(newer, claims.pending.value)
    }

    @Test
    fun `«Зарахувати» — один виклик із підписом гаманця, бали рахує відповідь сервера`() = runTest {
        val api = Api()
        val disk = Disk()
        val claims = PointsClaims(api, disk)
        claims.offer(claim)
        val result = claims.settle(claim.address, sign)
        assertEquals(PointsClaims.Settled.Counted(5), result)
        assertEquals(listOf("devnet:${claim.tx}:${claim.address}:${claim.ref}"), api.asked)
        assertTrue("підпис гаманця не дійшов до клієнта", api.signed!!.contentEquals("речення".toByteArray().reversedArray()))
        assertNull(claims.pending.value)
        assertEquals(setOf(claim.tx), disk.tx)
        // Те саме просить веб ще раз — відбитка вдруге не буде.
        assertEquals(PointsClaims.Offered.AlreadyCounted, claims.offer(claim))
        assertNull(claims.pending.value)
    }

    @Test
    fun `уже зараховану раніше оплату сервер повертає без нових балів — це теж зараховано`() = runTest {
        val api = Api().apply { answer = VenueClient.Answer.Ok("""{"status":"confirmed","awarded":[]}""") }
        val claims = PointsClaims(api, Disk())
        claims.offer(claim)
        assertEquals(PointsClaims.Settled.Counted(0), claims.settle(claim.address, sign))
        assertNull(claims.pending.value)
    }

    @Test
    fun `мережа ще не підтвердила — заявка лишається, зараховано не записано`() = runTest {
        val api = Api().apply { answer = VenueClient.Answer.Ok("""{"status":"pending"}""") }
        val disk = Disk()
        val claims = PointsClaims(api, disk)
        claims.offer(claim)
        assertEquals(PointsClaims.Settled.NotConfirmedYet, claims.settle(claim.address, sign))
        assertEquals(claim, claims.pending.value)
        assertTrue(disk.tx.isEmpty())
    }

    @Test
    fun `остаточна відмова знімає заявку і не повторюється, тимчасова заявку лишає`() = runTest {
        val api = Api()
        val claims = PointsClaims(api)
        claims.offer(claim)

        api.answer = VenueClient.Answer.Unreachable
        assertEquals(PointsClaims.Settled.Failed(VenueClient.Answer.Unreachable), claims.settle(claim.address, sign))
        api.answer = VenueClient.Answer.Refused(429, null, null)
        assertEquals(PointsClaims.Settled.Failed(VenueClient.Answer.Refused(429, null, null)), claims.settle(claim.address, sign))
        api.answer = VenueClient.Answer.Refused(401, null, null)
        assertTrue(claims.settle(claim.address, sign) is PointsClaims.Settled.Failed)
        assertEquals("тимчасова відмова зняла заявку", claim, claims.pending.value)

        api.answer = VenueClient.Answer.Refused(422, "Не ваш платіж", "Not your payment")
        assertEquals(PointsClaims.Settled.Refused("Не ваш платіж", "Not your payment"), claims.settle(claim.address, sign))
        assertNull(claims.pending.value)
        // Веб повторить — і отримає те саме «ні», а не нову заявку.
        assertEquals(PointsClaims.Offered.Refused("Не ваш платіж", "Not your payment"), claims.offer(claim))
        assertNull(claims.pending.value)
        assertEquals("відмова спитала сервер вдруге", 4, api.asked.size)
    }

    @Test
    fun `код причини від сервера доходить і з «Зарахувати», і з повторної заявки`() = runTest {
        val api = Api()
        val claims = PointsClaims(api)
        claims.offer(claim)
        api.answer = VenueClient.Answer.Refused(422, "Гаманець чужий", "Wallet taken", "wallet_taken")
        assertEquals(PointsClaims.Settled.Refused("Гаманець чужий", "Wallet taken", "wallet_taken"), claims.settle(claim.address, sign))
        // Веб розрізняє «гаманець чужий» і «не ваша оплата» по коду — повтор його не губить.
        assertEquals(PointsClaims.Offered.Refused("Гаманець чужий", "Wallet taken", "wallet_taken"), claims.offer(claim))
    }

    @Test
    fun `мережа Solana не відповіла — заявка лишається, слова сервера не губляться`() = runTest {
        val api = Api()
        val claims = PointsClaims(api)
        claims.offer(claim)
        val down = VenueClient.Answer.Refused(503, "Мережа Solana не відповіла", "Network down", "network_unavailable")
        api.answer = down
        assertEquals(PointsClaims.Settled.Failed(down), claims.settle(claim.address, sign))
        assertEquals("503 зняв заявку: людина мусила б платити знову", claim, claims.pending.value)
        assertEquals(PointsClaims.Offered.Waiting, claims.offer(claim))
    }

    @Test
    fun `гаманець змінився — заявку знято, ключем чужої адреси не підписують`() = runTest {
        val api = Api()
        val claims = PointsClaims(api)
        claims.offer(claim)
        assertEquals(PointsClaims.Settled.WalletChanged, claims.settle("B".repeat(44), sign))
        assertEquals(PointsClaims.Settled.NothingToSettle, claims.settle(claim.address, sign))
        assertNull(claims.pending.value)
        assertTrue("сервер спитали з чужим гаманцем", api.asked.isEmpty())
        assertEquals(PointsClaims.Settled.NothingToSettle, claims.settle(null, sign))
    }
}
