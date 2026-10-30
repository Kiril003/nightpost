package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Кадр профілю, що лягає в реєстр.
 *
 * До цього [PeerProfile] не мав жодного споживача: кодувальник, розбирач і
 * рід у [PeerFrames.Kind] були написані, зелені — і нікуди не підʼєднані.
 * Кадр не складався ніде, не приймався ніде, і після приймання його не було
 * куди покласти. Ці тести описують ТРЕТЮ частину — місце.
 *
 * Чому реєстр, а не журнал розмови: журнал обрізається ([PeerOutbox.prune])
 * і стирається людиною цілком, тож профіль, виведений із нього, зникав би
 * разом із листами.
 */
class PeerRosterProfileTest {

    private val nowMs = 1_700_000_000_000L

    private fun peer(id: String = "p1") = PeerIdentity(
        id = id,
        name = "Кирило",
        kind = PeerIdentity.Kind.PERSON,
        pubKey = "PUB",
        dhPubKey = "DH",
    )

    private fun frame(
        rev: Long,
        avatar: PeerProfile.Field<ByteArray> = PeerProfile.Field.Absent,
        emoji: PeerProfile.Field<String> = PeerProfile.Field.Absent,
        text: PeerProfile.Field<String> = PeerProfile.Field.Absent,
        bio: PeerProfile.Field<String> = PeerProfile.Field.Absent,
    ): String = PeerProfile.encode(PeerProfile.Body(rev, avatar, emoji, text, bio))!!

    @Test
    fun `кадр кладе поля на картку людини`() {
        val roster = listOf(peer())
        val after = PeerRoster.absorbProfile(
            roster,
            "p1",
            frame(
                rev = 1,
                avatar = PeerProfile.Field.Set(byteArrayOf(1, 2, 3)),
                emoji = PeerProfile.Field.Set("🇺🇦"),
                text = PeerProfile.Field.Set("на звʼязку"),
                bio = PeerProfile.Field.Set("Роблю PHANTOM."),
            ),
            nowMs,
        )
        val p = after.single()
        assertEquals(1L, p.profileRev)
        assertEquals(nowMs, p.profileAtMs)
        assertEquals("AQID", p.avatarThumbB64)
        assertEquals("🇺🇦", p.statusEmoji)
        assertEquals("на звʼязку", p.statusText)
        assertEquals("Роблю PHANTOM.", p.bio)
    }

    /**
     * Імʼя, яке дав ОПЕРАТОР, кадр профілю не чіпає — як і [PeerRoster.upsert].
     * Інакше «перейменувати в себе» тривало б до наступного чужого кадру.
     */
    @Test
    fun `кадр не переписує імені, даного рукою`() {
        val roster = listOf(peer().copy(name = "Сусід згори"))
        val after = PeerRoster.absorbProfile(roster, "p1", frame(rev = 1), nowMs)
        assertEquals("Сусід згори", after.single().name)
    }

    @Test
    fun `повтор переказу з тим самим rev нічого не міняє`() {
        val first = PeerRoster.absorbProfile(
            listOf(peer()),
            "p1",
            frame(rev = 5, bio = PeerProfile.Field.Set("перше")),
            nowMs,
        )
        val again = PeerRoster.absorbProfile(
            first,
            "p1",
            frame(rev = 5, bio = PeerProfile.Field.Set("друге")),
            nowMs + 1000,
        )
        // Той самий список, не просто рівний: викликач відрізняє «застосовано»
        // від «повтор» саме за цим, і toast про оновлення не малюється двічі.
        assertSame(first, again)
        assertEquals("перше", again.single().bio)
    }

    @Test
    fun `старіший rev не вертає профіль назад`() {
        val first = PeerRoster.absorbProfile(
            listOf(peer()),
            "p1",
            frame(rev = 9, bio = PeerProfile.Field.Set("нове")),
            nowMs,
        )
        val stale = PeerRoster.absorbProfile(
            first,
            "p1",
            frame(rev = 2, bio = PeerProfile.Field.Set("старе")),
            nowMs + 1000,
        )
        assertSame(first, stale)
        assertEquals("нове", stale.single().bio)
    }

    /**
     * ТРИ СТАНИ, і саме тут вони мають значення: «тега не було» лишає відоме
     * на місці, «тег нульової довжини» ставить порожнє-але-відоме.
     */
    @Test
    fun `відсутнє поле лишає відоме, очищене ставить порожнє`() {
        val set = PeerRoster.absorbProfile(
            listOf(peer()),
            "p1",
            frame(
                rev = 1,
                avatar = PeerProfile.Field.Set(byteArrayOf(7)),
                bio = PeerProfile.Field.Set("біо"),
            ),
            nowMs,
        )
        // Кадр старішої збірки: тегів немає взагалі.
        val silent = PeerRoster.absorbProfile(set, "p1", frame(rev = 2), nowMs)
        // base64 із доповненням — те, що чекає `data:`-адреса в браузері.
        assertEquals("Bw==", silent.single().avatarThumbB64)
        assertEquals("біо", silent.single().bio)

        // Людина стерла обидва в себе.
        val cleared = PeerRoster.absorbProfile(
            silent,
            "p1",
            frame(rev = 3, avatar = PeerProfile.Field.Cleared, bio = PeerProfile.Field.Cleared),
            nowMs,
        )
        assertEquals("", cleared.single().avatarThumbB64)
        assertEquals("", cleared.single().bio)
    }

    @Test
    fun `свіжа картка не знає нічого — і це не те саме, що порожнє`() {
        val p = peer()
        assertNull(p.avatarThumbB64)
        assertNull(p.bio)
        assertEquals(0L, p.profileRev)
    }

    @Test
    fun `профіль незнайомця не заводить рядка в реєстрі`() {
        val roster = listOf(peer("p1"))
        val after = PeerRoster.absorbProfile(roster, "хтось-інший", frame(rev = 1), nowMs)
        assertSame(roster, after)
    }

    @Test
    fun `порожній відправник і не-кадр відкидаються`() {
        val roster = listOf(peer())
        assertSame(roster, PeerRoster.absorbProfile(roster, "", frame(rev = 1), nowMs))
        assertSame(roster, PeerRoster.absorbProfile(roster, "p1", "просто слово", nowMs))
        assertSame(roster, PeerRoster.absorbProfile(roster, "p1", "PH-PRF:не-base64!!", nowMs))
    }

    /**
     * Обірваний кадр не застосовується ПОЛОВИНОЮ. Доводиться тут, а не лише в
     * [PeerProfileTest], бо саме тут половина чужого біо дійшла б до картки.
     */
    @Test
    fun `обірваний кадр лишає картку недоторканою`() {
        val whole = frame(rev = 4, bio = PeerProfile.Field.Set("довге біо, яке обірвали"))
        val cut = whole.substring(0, whole.length - 6)
        val roster = listOf(peer())
        assertSame(roster, PeerRoster.absorbProfile(roster, "p1", cut, nowMs))
    }

    /** Кадр одного контакту не сміє переписати картку іншого. */
    @Test
    fun `кадр торкається лише свого відправника`() {
        val roster = listOf(peer("p1"), peer("p2"))
        val after = PeerRoster.absorbProfile(
            roster,
            "p2",
            frame(rev = 1, bio = PeerProfile.Field.Set("друге біо")),
            nowMs,
        )
        assertNull(after.first { it.id == "p1" }.bio)
        assertEquals("друге біо", after.first { it.id == "p2" }.bio)
    }

    // ── імʼя ─────────────────────────────────────────────────────────────

    private fun named(rev: Long, name: String) =
        PeerProfile.encode(PeerProfile.Body(rev, name = PeerProfile.Field.Set(name)))!!

    @Test
    fun `оголошене імʼя замінює те, що було`() {
        val after = PeerRoster.absorbProfile(listOf(peer()), "p1", named(1, "Кирило Милосердов"), nowMs)
        assertEquals("Кирило Милосердов", after.single().name)
    }

    @Test
    fun `імʼя, дане власником, кадр не перебиває`() {
        val mine = PeerRoster.rename(listOf(peer()), "p1", "Брат")
        val after = PeerRoster.absorbProfile(mine, "p1", named(1, "PHANTOM-A52"), nowMs)
        assertEquals("Брат", after.single().name)
        assertEquals(1L, after.single().profileRev)
    }

    @Test
    fun `кадр без імені лишає імʼя`() {
        val after = PeerRoster.absorbProfile(listOf(peer()), "p1", frame(rev = 1, bio = PeerProfile.Field.Set("x")), nowMs)
        assertEquals("Кирило", after.single().name)
    }

    @Test
    fun `чуже імʼя проходить ті самі правила, що й у запрошенні`() {
        val after = PeerRoster.absorbProfile(listOf(peer()), "p1", named(1, "\u202Eолирик"), nowMs)
        assertEquals("олирик", after.single().name)
    }
}
