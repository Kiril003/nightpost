package local.phantom.companion.data

import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.solana.AddressSharing
import local.phantom.companion.core.solana.SolanaKey
import local.phantom.companion.core.solana.WalletAddressFrame
import org.junit.Assert.assertEquals
import org.junit.Test

class SolanaAddressPolicyTest {

    private fun m(from: String, to: String, body: String) = PeerMessage("$from-$to-${body.hashCode()}", from, to, body, 1L)

    @Test
    fun theAddressGoesOnlyToPeopleWeActuallyTalkTo() {
        val me = "me"
        val log = listOf(
            m(me, "ann", "привіт"),
            m("bob", me, "як справи?"),
            m(me, "eve", WalletAddressFrame.encode(SolanaKey.of(ByteArray(32) { 1 })!!)),
            m("gus", "grp1", "PH-GRP:1;grp1;слово"),
        )
        val talked = talkedWith(log, me)
        assertEquals(setOf("ann", "bob"), talked)
        val people = listOf("ann", "bob", "eve", "gus", "stranger")
        assertEquals(listOf("ann", "bob"), AddressSharing.recipients(people, talked, withAll = false))
        assertEquals(people, AddressSharing.recipients(people, talked, withAll = true))
    }
}
