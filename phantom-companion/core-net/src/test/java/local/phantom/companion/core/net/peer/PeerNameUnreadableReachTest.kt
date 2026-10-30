package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * Вимір на вимогу штабу: чи справді «нечитане імʼя» піднімає прапорець, і де
 * дорога його губить.
 *
 * Точний рядок зі стенда 04.09: триста символів U+200B (ZERO WIDTH SPACE,
 * категорія Cf), жодного видимого. Він НЕ порожній і НЕ blank за Kotlin
 * (`Character.isWhitespace(0x200B)` = false), тож правило «порожній вхід
 * прапорця не піднімає» його не стосується.
 */
class PeerNameUnreadableReachTest {

    private val invisible = "​".repeat(300)

    @Test
    fun `three hundred zero-width spaces are an unreadable name, not an empty one`() {
        assertFalse("рядок вважається порожнім — тоді правило про порожнє його й ловить", invisible.isBlank())
        val accepted = PeerName.accept(invisible)
        assertEquals("після вичищення нічого не лишилось", "", accepted.name)
        assertTrue("PeerName.accept НЕ підняв unreadable — передумова хибна", accepted.unreadable)
    }

    @Test
    fun `an empty name stays honestly not-unreadable`() {
        // Зустрічний: імені не надсилали — вигадувати причину нічим.
        assertFalse(PeerName.accept("").unreadable)
        assertFalse(PeerName.accept("   ").unreadable)
        assertFalse(PeerName.accept(null).unreadable)
    }

    @Test
    fun `the invite road carries the flag to the roster`() {
        val code = PeerInvite.encode(
            PeerInvite.Invite(
                peerId = "1234567890abcdef",
                name = invisible,
                pubKey = "AA".repeat(16),
                dhPubKey = "BB".repeat(16),
                host = "10.0.0.7",
                port = 47100,
            ),
        )
        val decoded = PeerInvite.decode(code)
        assertNotNull("код не розібрався — вимір не про те", decoded)
        val invite = (decoded as? PeerInvite.Decoded.Ph2)?.invite
        assertNotNull("це має бути PH2", invite)
        // У рядку стоїть коротка форма ключа — читаного імені не лишилось…
        assertEquals(PeerRoster.shortId("1234567890abcdef"), invite!!.name)
        // …і факт, ЧОМУ воно таке, їде разом із ним. Саме цього бракувало:
        // повторне приймання його вже не відновить.
        assertTrue("дорога запрошення знову губить факт", invite.nameUnreadable)
        assertFalse(
            "повторне приймання не може відновити факт — і не мусить",
            PeerName.accept(invite.name).unreadable,
        )
    }

    @Test
    fun `the wire format is untouched — the flag never leaves the phone`() {
        // Дротовий рядок PH2 за десять днів до бети не міняємо: нове поле
        // розсинхронило б телефони різних збірок. Прапорець локальний, і
        // `encode` його не бачить — рядок мусить бути байт-у-байт тим самим.
        val base = PeerInvite.Invite(
            peerId = "1234567890abcdef",
            name = "Марта",
            pubKey = "AA".repeat(16),
            dhPubKey = "BB".repeat(16),
            host = "10.0.0.7",
            port = 47100,
        )
        assertEquals(
            "прапорець просочився у дротовий рядок",
            PeerInvite.encode(base),
            PeerInvite.encode(base.copy(nameUnreadable = true)),
        )
    }

    @Test
    fun `a readable name carries no accusation`() {
        // Зустрічний до головного випадку: звичайне імʼя не має піднімати
        // прапорець ніде на дорозі.
        val code = PeerInvite.encode(
            PeerInvite.Invite(
                peerId = "abcdef1234567890",
                name = "Марта",
                pubKey = "CC".repeat(16),
                dhPubKey = "DD".repeat(16),
                host = "10.0.0.8",
                port = 47101,
            ),
        )
        val invite = (PeerInvite.decode(code) as? PeerInvite.Decoded.Ph2)?.invite
        assertNotNull(invite)
        assertEquals("Марта", invite!!.name)
        assertFalse("на читаному імені прапорець піднявся", invite.nameUnreadable)
    }

    // ── Ланка, якої не видно з core-net: чи несе її далі застосунок і картка.
    //    Штаб 04.09: сторож на КОЖНІЙ ланці, інакше полагодимо кінці й лишимо
    //    дірку посередині — саме це й сталося минулого разу. ──

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core-net").isDirectory) dir = dir.parentFile
        return dir ?: throw AssertionError("корінь репозиторію не знайдено")
    }

    private fun source(path: String): String {
        val f = File(repoRoot(), path)
        assertTrue("$path не знайдено — сторож нічого не боронить", f.isFile)
        return f.readText()
    }

    @Test
    fun `the app and the card carry the flag onward`() {
        val mesh = source("app/src/main/java/local/phantom/companion/data/PeerMesh.kt")
        assertTrue(
            "PeerMesh губить прапорець дорогою запрошення",
            mesh.contains("nameUnreadable = invite.nameUnreadable"),
        )
        val card = source(
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/people/PeerCardScreen.kt",
        )
        assertTrue("картка не читає прапорець", card.contains("if (peer.nameUnreadable)"))
        assertTrue(
            "картка не каже речення про нечитане імʼя",
            card.contains("R.string.peer_card_name_unreadable"),
        )
    }

    @Test
    fun `the roster keeps a flag it is handed, and invents none`() {
        // Ланка PeerIdentity → ростер. Вона й була ціла — обидва доданки просто
        // виходили нулями, бо зверху нічого не передавали. `named` приватний і
        // тягне за собою сховище, тож ланку стережемо на джерелі: важливо, що
        // переданий факт додається (АБО), а не заміщується результатом
        // повторного приймання.
        val roster = source("core-net/src/main/java/local/phantom/companion/core/net/peer/PeerRoster.kt")
        assertTrue(
            "ростер більше не додає переданий факт до свого — переклав би все на повторне приймання",
            roster.contains("nameUnreadable = accepted.unreadable || peer.nameUnreadable"),
        )
    }
}
