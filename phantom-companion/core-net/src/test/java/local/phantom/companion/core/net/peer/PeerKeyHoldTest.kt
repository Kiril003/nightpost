package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Новий ключ у звіреної людини ставить листи на паузу до «Продовжити» або нової звірки. */
class PeerKeyHoldTest {

    private val id = "kyrylo00"

    private fun row(dh: String, at: Long) =
        PeerIdentity(id, "Кирило", PeerIdentity.Kind.PERSON, "pub-kyrylo", dh, addedAtMs = at)

    private fun one(r: List<PeerIdentity>) = r.single()

    @Test fun `звірений контакт із новим ключем — тривога і пауза`() {
        var r = PeerRoster.upsert(emptyList(), row("dh-1", 1_000), nowMs = 1_000)
        r = PeerRoster.markVerified(r, id, 2_000)
        r = PeerRoster.upsert(r, row("dh-2", 3_000), nowMs = 3_000)
        assertTrue(one(r).keyAlarm)
        assertTrue("звірка обіцяла «пишу саме їй» — лист чекає", one(r).sendHeld)
        assertEquals(3_000L, one(r).keyHoldAtMs)
    }

    @Test fun `невідзвірений контакт — лише спокійна тривога, без паузи`() {
        var r = PeerRoster.upsert(emptyList(), row("dh-1", 1_000), nowMs = 1_000)
        r = PeerRoster.upsert(r, row("dh-2", 3_000), nowMs = 3_000)
        assertTrue(one(r).keyAlarm)
        assertFalse(one(r).sendHeld)
    }

    @Test fun `«Продовжити» знімає паузу, але не тривогу`() {
        var r = PeerRoster.upsert(emptyList(), row("dh-1", 1_000), nowMs = 1_000)
        r = PeerRoster.markVerified(r, id, 2_000)
        r = PeerRoster.upsert(r, row("dh-2", 3_000), nowMs = 3_000)
        r = PeerRoster.continueAfterKeyChange(r, id)
        assertFalse(one(r).sendHeld)
        assertTrue("контакт так і лишається незвіреним", one(r).keyAlarm)
        // Той самий ключ ще раз паузи не повертає.
        r = PeerRoster.upsert(r, row("dh-2", 4_000), nowMs = 4_000)
        assertFalse(one(r).sendHeld)
    }

    @Test fun `нова звірка знімає і паузу, і тривогу`() {
        var r = PeerRoster.upsert(emptyList(), row("dh-1", 1_000), nowMs = 1_000)
        r = PeerRoster.markVerified(r, id, 2_000)
        r = PeerRoster.upsert(r, row("dh-2", 3_000), nowMs = 3_000)
        r = PeerRoster.markVerified(r, id, 5_000)
        assertFalse(one(r).sendHeld)
        assertFalse(one(r).keyAlarm)
    }

    @Test fun `ще одна зміна, поки чекали на «Продовжити», паузу не знімає`() {
        var r = PeerRoster.upsert(emptyList(), row("dh-1", 1_000), nowMs = 1_000)
        r = PeerRoster.markVerified(r, id, 2_000)
        r = PeerRoster.upsert(r, row("dh-2", 3_000), nowMs = 3_000)
        r = PeerRoster.upsert(r, row("dh-3", 4_000), nowMs = 4_000)
        assertTrue(one(r).sendHeld)
        assertEquals(4_000L, one(r).keyHoldAtMs)
    }
}
