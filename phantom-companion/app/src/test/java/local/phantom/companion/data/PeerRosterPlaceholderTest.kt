package local.phantom.companion.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerProfile
import local.phantom.companion.core.net.peer.PeerRoster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контакти, що застрягли «Новим контактом».
 *
 * Колишній веб записував заглушку «Новий контакт» у реєстр як імʼя ВЛАСНИКА
 * ([PeerIdentity.nameByOwner] = true), і кадр профілю з іменем, яке людина
 * дала собі сама, після цього не перебивав його ніколи (A52, 02.10).
 *
 * «Перезапуск» тут справжній: НОВИЙ [PeerRosterStore] над тими самими байтами.
 */
class PeerRosterPlaceholderTest {

    private class FakeSlot : PeerLogSlot {
        val cells = HashMap<String, String>()
        override fun read(key: String): String? = cells[key]
        override fun write(key: String, value: String?) {
            if (value == null) cells.remove(key) else cells[key] = value
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun person(id: String, name: String, byOwner: Boolean, rev: Long = 0L) = PeerIdentity(
        id = id,
        name = name,
        kind = PeerIdentity.Kind.PERSON,
        pubKey = "pk_$id",
        nameByOwner = byOwner,
        profileRev = rev,
    )

    /** Диск у тому вигляді, у якому його лишила стара збірка. */
    private fun oldDisk(vararg people: PeerIdentity) = FakeSlot().apply {
        cells["peers"] = json.encodeToString(people.toList())
    }

    private fun frame(rev: Long, name: String): String {
        val body = PeerProfile.encode(PeerProfile.Body(rev = rev, name = PeerProfile.Field.Set(name)))
        assertNotNull("кадр профілю не склався", body)
        return body!!
    }

    @Test
    fun `заглушка «Новий контакт» поступається імені з профілю`() {
        val slot = oldDisk(
            person("a", "Новий контакт", byOwner = true, rev = 3),
            person("b", "Новий контакт · 27 вер., 16:04", byOwner = true, rev = 5),
        )
        val store = PeerRosterStore(slot)

        assertTrue(store.peers.value.none { it.nameByOwner })
        // Кадр, у якого імʼя колись відкинули, — той самий rev — тепер застосовується.
        assertTrue(store.absorbProfile("a", frame(3, "Оксана")))
        assertTrue(store.absorbProfile("b", frame(5, "Петро")))
        assertEquals(listOf("Оксана", "Петро"), store.peers.value.map { it.name })
    }

    /**
     * Негативний контроль: імʼя, яке власник ДАВ сам, і далі не перебивається.
     * Без цього «лікування» могло б просто стерти прапорець усім.
     */
    @Test
    fun `імʼя, яке дав власник, кадр профілю не перебиває`() {
        val store = PeerRosterStore(oldDisk(person("c", "Мама", byOwner = true, rev = 1)))

        assertTrue(store.peers.value.single().nameByOwner)
        store.absorbProfile("c", frame(2, "Ольга Іванівна"))
        assertEquals("Мама", store.peers.value.single().name)
    }

    @Test
    fun `лікування одноразове і переживає перезапуск`() {
        val slot = oldDisk(person("a", "Новий контакт", byOwner = true))
        PeerRosterStore(slot)
        assertFalse(PeerRosterStore(slot).peers.value.single().nameByOwner)

        // Власник пізніше сам назвав людину так само — це вже ЙОГО імʼя, і
        // наступний старт не має права зняти прапорець удруге.
        PeerRosterStore(slot).rename("a", "Новий контакт")
        assertTrue(PeerRosterStore(slot).peers.value.single().nameByOwner)
    }

    @Test
    fun `нічого лікувати — той самий список`() {
        val roster = listOf(person("a", "Оксана", byOwner = true), person("b", "Новий контакт", byOwner = false))
        assertSame(roster, PeerRoster.releaseLegacyPlaceholders(roster))
        assertFalse(PeerRoster.isLegacyPlaceholder("Новий контакт Іван"))
        assertTrue(PeerRoster.isLegacyPlaceholder(" новий КОНТАКТ "))
    }
}
