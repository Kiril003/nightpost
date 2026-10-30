package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Test

/** Спарений ПК стає моїм пристроєм, а не людиною — хоч би як він потрапив у реєстр раніше. */
class PeerRosterOwnDeviceTest {

    private val pc = PeerIdentity(
        id = "8c5635f15af741eb",
        name = "Мій компʼютер",
        kind = PeerIdentity.Kind.DEVICE,
        pubKey = "E9mQinCSWZLtVGAH0n9Q2mi6chfvYqw8ynhFKf8QRxw",
        dhPubKey = "B6N8vBQgk8i3VdwbEOhstCY3StFqqFPtC9/AsrhtHHw",
        keySource = PeerIdentity.KeySource.REMOTE,
    )

    @Test fun `новий ПК лягає пристроєм під моїм імʼям`() {
        val row = PeerRoster.adoptOwnDevice(emptyList(), pc, nowMs = 1L).single()
        assertEquals(PeerIdentity.Kind.DEVICE, row.kind)
        assertEquals("Мій компʼютер", row.name)
    }

    @Test fun `ПК, що прийшов запрошенням як людина, після спарювання — мій пристрій`() {
        val stranger = pc.copy(name = "ПК · PHANTOM OS", kind = PeerIdentity.Kind.PERSON)
        val row = PeerRoster.adoptOwnDevice(listOf(stranger), pc.copy(kind = PeerIdentity.Kind.PERSON), 1L).single()
        assertEquals(PeerIdentity.Kind.DEVICE, row.kind)
        assertEquals("Мій компʼютер", row.name)
    }

    @Test fun `імʼя, яке дав власник, повторне спарювання не перебиває`() {
        val named = pc.copy(name = "Робочий", nameByOwner = true)
        val row = PeerRoster.adoptOwnDevice(listOf(named), pc, 1L).single()
        assertEquals("Робочий", row.name)
        assertEquals(PeerIdentity.Kind.DEVICE, row.kind)
    }

    @Test fun `звичайне запрошення не робить людину моїм пристроєм`() {
        val person = pc.copy(kind = PeerIdentity.Kind.PERSON)
        assertEquals(PeerIdentity.Kind.PERSON, PeerRoster.upsert(emptyList(), person, 1L).single().kind)
    }
}
