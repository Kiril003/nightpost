package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Кожен приймальний кінець імʼя міряє САМ.
 *
 * Межа при кодуванні не захищає нікого: рядок складає чужий пристрій. Доказ —
 * коди нижче: їх склав НАШ власний `scripts/phantom_peer.py` (`invite_code`),
 * у якому жодної межі немає й не буде — він саме для того й потрібен, щоб
 * подавати на стенд будь-які, у тому числі навмисні, рядки. Саме таким кодом
 * Сесія 6 04.09 привела на A52 імʼя на 45 знаків, і телефон прийняв його без
 * заперечень.
 */
class PeerNameIntakeTest {

    // scripts/phantom_peer.py invite_code(pid, «Володимир-Мирослав
    // Кравченко-Наливайко Петрів» — 45 знаків, pub, dh, "10.0.2.2", 47921).
    private val fortyFive =
        "PH2:YTFiMmMzZDRlNWY2MDcxOHzQktC-0LvQvtC00LjQvNC40YAt0JzQuNGA0L7RgdC70LDQsi" +
            "DQmtGA0LDQstGH0LXQvdC60L4t0J3QsNC70LjQstCw0LnQutC-INCf0LXRgtGA0ZbQsnxV" +
            "RWhCVGxSUFRTMTBaWE4wTFhOcFoyNXBibWN0YTJWNUxUQXdNREF3TURBd01EQT18VUVoQl" +
            "RsUlBUUzEwWlhOMExXUm9MV3RsZVMwd01EQXdNREF3TURBd01EQXdNREE9fDEwLjAuMi4y" +
            "fDQ3OTIx:577de1"

    // Те саме, але імʼя «Пет<U+0000>ро\nІванов<U+007F>» — керівні символи всередині.
    private val controls =
        "PH2:YTFiMmMzZDRlNWY2MDcxOHzQn9C10YIA0YDQvgrQhtCy0LDQvdC-0LJ_fFVFaEJUbFJQVF" +
            "MxMFpYTjBMWE5wWjI1cGJtY3RhMlY1TFRBd01EQXdNREF3TURBPXxVRWhCVGxSUFRTMTBa" +
            "WE4wTFdSb0xXdGxlUzB3TURBd01EQXdNREF3TURBd01EQT18MTAuMC4yLjJ8NDc5MjE:674219"

    // Імʼя з U+202E (перевертач рядка), U+200B і U+200E — «Петро» у ньому
    private val bidi =
        "PH2:YTFiMmMzZDRlNWY2MDcxOHzQn9C10YLRgNC-4oCudm9uYXZJ4oCL4oCOfFVFaEJUbFJQVF" +
            "MxMFpYTjBMWE5wWjI1cGJtY3RhMlY1TFRBd01EQXdNREF3TURBPXxVRWhCVGxSUFRTMTBa" +
            "WE4wTFdSb0xXdGxlUzB3TURBd01EQXdNREF3TURBd01EQT18MTAuMC4yLjJ8NDc5MjE:9d8a12"

    // Імʼя «Петро|Іванов|zzz» — роздільник формату всередині самого імені.
    private val separatorInName =
        "PH2:YTFiMmMzZDRlNWY2MDcxOHzQn9C10YLRgNC-fNCG0LLQsNC90L7Qsnx6enp8VUVoQlRsUl" +
            "BUUzEwWlhOMExYTnBaMjVwYm1jdGEyVjVMVEF3TURBd01EQXdNREE9fFVFaEJUbFJQVFMx" +
            "MFpYTjBMV1JvTFd0bGVTMHdNREF3TURBd01EQXdNREF3TURBPXwxMC4wLjIuMnw0NzkyMQ:4ccb76"

    @Test
    fun `розбір запрошення сам ріже імʼя, а не лишає це ростерові`() {
        // Раніше межа стояла лише в [PeerInvite.encode] — тобто обмежувала
        // тільки нас, а рядок складає той бік.
        val long = wireInvite("я".repeat(4000 / 4))
        val invite = ph2(long)
        assertTrue(invite.nameShortened)
        assertTrue(invite.name.endsWith(PeerName.ELLIPSIS))
        assertEquals(PeerName.MAX, PeerName.length(invite.name))
    }

    @Test
    fun `код, довший за стелю, відкидається до розбору`() {
        val huge = wireInvite("я".repeat(4000))
        assertTrue(huge.length > PeerInvite.MAX_CODE)
        assertTrue(PeerInvite.decode(huge) is PeerInvite.Decoded.NotInvite)
    }

    @Test
    fun `керівні й bidi-символи не переживають розбору запрошення`() {
        val nasty = "Петро" + String(Character.toChars(0x202E)) + "\n" +
            String(Character.toChars(0x0000)) + "Іванов" + String(Character.toChars(0x200B))
        val invite = ph2(wireInvite(nasty))
        assertEquals("Петро Іванов", invite.name)
        assertFalse(invite.nameShortened)
    }

    @Test
    fun `порожнє імʼя в коді стає ключем`() {
        assertEquals("a1b2·c3d4", ph2(wireInvite("     ", id = "a1b2c3d4e5")).name)
    }

    @Test
    fun `сорок пʼять знаків із запрошення доїжджають цілими`() {
        val invite = ph2(fortyFive)
        assertEquals(45, invite.name.length)
        val row = PeerRoster.upsert(emptyList(), rowOf(invite))[0]
        // Саме тут телефон 04.09 віддав 40. Межа приймання одна на весь
        // продукт, і 45 під неї не підпадає взагалі.
        assertEquals(invite.name, row.name)
        assertFalse(row.nameShortened)
    }

    @Test
    fun `довше за межу лягає в ростер скороченим і позначеним`() {
        val row = PeerRoster.upsert(emptyList(), peer(name = "я".repeat(4000)))[0]
        assertTrue(row.nameShortened)
        assertTrue(row.name.endsWith(PeerName.ELLIPSIS))
        assertEquals(PeerName.MAX, PeerName.length(row.name))
    }

    @Test
    fun `керівні символи не потрапляють у ростер`() {
        val row = PeerRoster.upsert(emptyList(), rowOf(ph2(controls)))[0]
        assertEquals("Петро Іванов", row.name)
        assertFalse(row.nameShortened)
    }

    @Test
    fun `перевертач рядка не доїжджає до ростера`() {
        val row = PeerRoster.upsert(emptyList(), rowOf(ph2(bidi)))[0]
        listOf(0x202E, 0x200B, 0x200E).forEach {
            assertFalse(row.name.contains(String(Character.toChars(it))))
        }
    }

    @Test
    fun `роздільник усередині імені ламає розбір, а не ростер`() {
        // Полів стає вісім замість шести — код відкидається цілком. Межа на
        // кількість полів уже стоїть; довжина всього рядка — ще ні (PeerInvite).
        assertTrue(PeerInvite.decode(separatorInName) is PeerInvite.Decoded.NotInvite)
    }

    @Test
    fun `порожнє імʼя стає ключем, а не порожнечею`() {
        val row = PeerRoster.upsert(emptyList(), peer(id = "a1b2c3d4e5", name = "     "))[0]
        assertEquals("a1b2·c3d4", row.name)
        assertFalse(row.nameShortened)
    }

    @Test
    fun `перейменування рукою знімає позначку`() {
        val one = PeerRoster.upsert(emptyList(), peer(name = "я".repeat(200)))
        assertTrue(one[0].nameShortened)
        val two = PeerRoster.rename(one, "a", "Марта")
        assertEquals("Марта", two[0].name)
        assertFalse(two[0].nameShortened)
    }

    @Test
    fun `повторне приймання не гасить позначку`() {
        // Вже скорочене імʼя коротше за межу, тож саме по собі повернуло б
        // «не скорочували» — і картка замовкла б після першого ж маяка.
        val one = PeerRoster.upsert(emptyList(), peer(name = "я".repeat(200)))
        val again = PeerRoster.upsert(one, one[0], keepLocalName = false)
        assertTrue(again[0].nameShortened)
    }

    @Test
    fun `назва групи з дроту теж має межу`() {
        val decoded = requireNotNull(PeerGroup.decode(wireGroup("я".repeat(300))))
        assertTrue(decoded.group.nameShortened)
        assertTrue(decoded.group.name.endsWith(PeerName.ELLIPSIS))
        assertEquals(PeerName.MAX, PeerName.length(decoded.group.name))
    }

    @Test
    fun `назва групи з керівними символами не доїжджає`() {
        val nasty = "Північ" + String(Character.toChars(0x202E)) + "\n" + "загін"
        val decoded = requireNotNull(PeerGroup.decode(wireGroup(nasty)))
        assertEquals("Північ загін", decoded.group.name)
        assertFalse(decoded.group.nameShortened)
    }

    /**
     * Заголовок групи, складений БЕЗ жодної межі — так, як його склав би чужий
     * телефон. [PeerGroup.encode] тут не годиться: воно ріже назву при
     * кодуванні, тобто обмежує лише нас, і тест на ньому доводив би, що ми
     * самі собі не шлемо задовгого.
     */
    private fun wireGroup(name: String, id: String = "abc123abc123"): String {
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(name.toByteArray(Charsets.UTF_8))
        return "PH-GRP:$id:$b64:me,olia\nпривіт"
    }

    /**
     * Код PH2, складений БЕЗ жодної межі — порт [PeerInvite.encode] зі
     * `scripts/phantom_peer.py.invite_code`. Складати такі рядки й треба вміти:
     * саме таким Сесія 6 привела на A52 імʼя на 45 знаків, і саме на такому
     * видно, що обмеження при кодуванні нікого не захищає.
     */
    private fun wireInvite(name: String, id: String = "a1b2c3d4e5f60718"): String {
        val payload = listOf(
            id,
            name,
            "UEhBTlRPTS10ZXN0LXNpZ25pbmcta2V5LTAwMDAwMDAwMDA=",
            "UEhBTlRPTS10ZXN0LWRoLWtleS0wMDAwMDAwMDAwMDAwMDA=",
            "10.0.2.2",
            "47921",
        ).joinToString("|")
        val body = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(Charsets.UTF_8))
        var hash = 0x811C9DC5L
        for (c in body) {
            hash = hash xor c.code.toLong()
            hash = (hash * 16_777_619L) and 0xFFFFFFFFL
        }
        return "PH2:$body:${hash.toString(16).padStart(8, '0').takeLast(6)}"
    }


    @Test
    fun `дорогою запрошення факт «не читається» не губиться`() {
        // Сесія 6 на склі A52 (dbe6d05d): імʼя з 300 невидимих знаків лягло в
        // ростер коротким ключем — правильно, — але картка змовчала про
        // причину. Штаб припустив, що випадок просто не є «нечитаним» за нашим
        // означенням. Цей тест вирішує спір виміром, а не міркуванням.
        //
        // U+200B — категорія Cf, а не пробіл: `isBlank()` на ньому хибний, тож
        // `accept` ПІДНІМАЄ прапорець. Далі шлях: decode → Invite → PeerMesh →
        // ростер; і якщо його рве `Invite`, у якого поля немає, ростер уже не
        // відновить факт — до нього доїде або порожній рядок, або й зовсім
        // короткий ключ (PeerInvite.kt:167 підставляє його через ifBlank).
        val invisible = "\u200B".repeat(300)
        assertTrue("вхідне імʼя не є порожнім за Kotlin", invisible.isNotBlank())
        assertTrue("accept сам факт бачить", PeerName.accept(invisible).unreadable)

        val invite = ph2(wireInvite(invisible))
        assertTrue(
            "запрошення мусить нести факт далі, інакше картка змовчить про причину",
            invite.nameUnreadable,
        )
    }

    private fun ph2(code: String): PeerInvite.Invite =
        (PeerInvite.decode(code) as PeerInvite.Decoded.Ph2).invite

    private fun rowOf(invite: PeerInvite.Invite) = PeerIdentity(
        id = invite.peerId,
        name = invite.name,
        kind = PeerIdentity.Kind.PERSON,
        pubKey = invite.pubKey,
        dhPubKey = invite.dhPubKey,
    )

    private fun peer(id: String = "a", name: String) = PeerIdentity(
        id = id,
        name = name,
        kind = PeerIdentity.Kind.PERSON,
        pubKey = "pk-$id",
    )

    @Test
    fun `нечитане імʼя лишає в рядку ключ і позначку причини`() {
        val hidden = "\u200B".repeat(5000) + "Марта"
        val one = peer(name = hidden)
        val after = PeerRoster.upsert(emptyList(), one)
        val row = after.single()
        assertEquals(PeerRoster.shortId(one.id), row.name)
        assertTrue("рядок мусить сказати, чому тут ключ", row.nameUnreadable)
        assertFalse("нічого не скорочували — не було чого", row.nameShortened)
    }

    @Test
    fun `перейменування рукою знімає й позначку причини`() {
        val hidden = "\u200B".repeat(5000) + "Марта"
        val one = peer(name = hidden)
        val after = PeerRoster.rename(PeerRoster.upsert(emptyList(), one), one.id, "Сусід")
        val row = after.single()
        assertEquals("Сусід", row.name)
        assertFalse("людина назвала запис сама", row.nameUnreadable)
    }
}
