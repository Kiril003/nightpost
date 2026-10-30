package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Профіль на дроті — за контрактом, а не за здогадом.
 *
 * Контракт лежить у продукті поруч:
 * `phantom-os-messenger/docs/PROFILE_WIRE_CONTRACT.md`. Тести перевіряють саме
 * те, що в ньому названо окремими пунктами, бо кожен із них лікує конкретну
 * можливу неправду на екрані.
 */
class PeerProfileTest {

    private fun body(
        rev: Long = 1,
        avatar: PeerProfile.Field<ByteArray> = PeerProfile.Field.Absent,
        emoji: PeerProfile.Field<String> = PeerProfile.Field.Absent,
        text: PeerProfile.Field<String> = PeerProfile.Field.Absent,
        bio: PeerProfile.Field<String> = PeerProfile.Field.Absent,
    ) = PeerProfile.Body(rev, avatar, emoji, text, bio)

    @Test
    fun `кадр із усіма полями доїжджає туди й назад без втрат`() {
        val avatar = ByteArray(1024) { (it % 251).toByte() }
        val src = body(
            rev = 42,
            avatar = PeerProfile.Field.Set(avatar),
            emoji = PeerProfile.Field.Set("🇺🇦"),
            text = PeerProfile.Field.Set("на звʼязку"),
            bio = PeerProfile.Field.Set("Кирило. Роблю PHANTOM."),
        )
        val wire = PeerProfile.encode(src)
        assertNotNull(wire)
        assertEquals(src, PeerProfile.decode(wire!!))
    }

    /**
     * ТРИ СТАНИ ПОЛЯ — головне розрізнення контракту.
     *
     * «Ще не приїхало» і «людина стерла це в себе» на екрані виглядають
     * однаково, якщо дріт їх не розрізняє: обидва — порожньо. Тоді картка не
     * може сказати правду про жодне з них.
     */
    @Test
    fun `відсутнє поле і стерте поле — це різні речі`() {
        val absent = PeerProfile.decode(PeerProfile.encode(body(rev = 1))!!)!!
        assertTrue(absent.bio is PeerProfile.Field.Absent)

        val cleared = PeerProfile.decode(
            PeerProfile.encode(body(rev = 2, bio = PeerProfile.Field.Cleared))!!,
        )!!
        assertTrue(cleared.bio is PeerProfile.Field.Cleared)
    }

    @Test
    fun `поле над стелею не їде взагалі — і відмова видима до сокета`() {
        assertNull(PeerProfile.encode(body(bio = PeerProfile.Field.Set("я".repeat(600)))))
        assertNull(
            PeerProfile.encode(
                body(avatar = PeerProfile.Field.Set(ByteArray(PeerProfile.MAX_AVATAR_BYTES + 1))),
            ),
        )
        assertNull(PeerProfile.encode(body(emoji = PeerProfile.Field.Set("🏴󠁧󠁢󠁳󠁣󠁴󠁿🏴󠁧󠁢󠁳󠁣󠁴󠁿"))))
    }

    @Test
    fun `рівно на стелі — ще їде`() {
        val exact = ByteArray(PeerProfile.MAX_AVATAR_BYTES)
        assertNotNull(PeerProfile.encode(body(avatar = PeerProfile.Field.Set(exact))))
    }

    /**
     * НЕВІДОМИЙ ТЕГ переступається, а не валить кадр.
     *
     * Без цього старий приймач відкидав би кадр, який новіший клієнт наситив
     * полем, якого старий не знає, — і людина втратила б навіть ті поля, що
     * розуміє її збірка.
     */
    @Test
    fun `невідоме поле переступається, а відоме поруч читається`() {
        val known = PeerProfile.encode(body(rev = 7, bio = PeerProfile.Field.Set("тут")))!!
        val raw = java.util.Base64.getDecoder().decode(known.removePrefix("PH-PRF:"))
        // Дописуємо тег 99 з трьома байтами — рід, якого ця збірка не знає.
        val withUnknown = raw + byteArrayOf(99, 3, 1, 2, 3)
        val wire = "PH-PRF:" + java.util.Base64.getEncoder().withoutPadding()
            .encodeToString(withUnknown)
        val got = PeerProfile.decode(wire)
        assertNotNull(got)
        assertEquals(7L, got!!.rev)
        assertEquals(PeerProfile.Field.Set("тут"), got.bio)
    }

    /**
     * ОБІРВАНИЙ кадр відкидається ЦІЛКОМ.
     *
     * Половина чужого біо, показана як ціле, — це неправда про людину. Довжина
     * каже «ще N байт», а їх немає: переказ обірвав передачу, і застосовувати
     * те, що встигло розпарситись, не можна.
     */
    @Test
    fun `обірваний кадр не застосовується частиною`() {
        val full = PeerProfile.encode(body(rev = 3, bio = PeerProfile.Field.Set("довге біо тут")))!!
        val raw = java.util.Base64.getDecoder().decode(full.removePrefix("PH-PRF:"))
        val cut = raw.copyOfRange(0, raw.size - 4)
        val wire = "PH-PRF:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(cut)
        assertNull(PeerProfile.decode(wire))
    }

    @Test
    fun `кадр без rev — не кадр`() {
        val wire = "PH-PRF:" + java.util.Base64.getEncoder().withoutPadding()
            .encodeToString(byteArrayOf(5, 2, 'н'.code.toByte(), 'і'.code.toByte()))
        assertNull(PeerProfile.decode(wire))
    }

    @Test
    fun `чуже тіло не вдає профілю`() {
        assertNull(PeerProfile.decode("просто слова людини"))
        assertNull(PeerProfile.decode("PH-CAS:щось;o;abc"))
    }

    /**
     * `rev` — лічильник, а не годинник. Повтор переказу мусить відкидатись,
     * інакше стара копія кадру, яка блукала сіткою, перезаписала б свіжу.
     */
    @Test
    fun `застосовуємо лише строго новіший rev`() {
        assertTrue(PeerProfile.isNewer(1, null))
        assertTrue(PeerProfile.isNewer(5, 4))
        assertTrue(!PeerProfile.isNewer(4, 4))
        assertTrue(!PeerProfile.isNewer(3, 4))
    }

    /** Профіль — не репліка: у стрічку й у лічильник непрочитаного не йде. */
    @Test
    fun `профіль не доходить до розмови`() {
        val wire = PeerProfile.encode(body(rev = 1, bio = PeerProfile.Field.Set("х")))!!
        assertEquals(PeerFrames.Kind.PROFILE, PeerFrames.kindOf(wire))
        assertTrue(!PeerFrames.Kind.PROFILE.reachesConversation)
    }

    /** Набране людиною не сміє вдавати кадр пристрою. */
    @Test
    fun `набраний PH-PRF відмовляється`() {
        assertEquals(PeerFrames.Kind.PROFILE, PeerFrames.refuseTyped("PH-PRF:щось"))
    }

    /**
     * ДЗЕРКАЛО КОНТРАКТУ. Той самий рядок стоїть у веб-тесті
     * (`src/frontend/src/__tests__/profileWireCodec.test.ts`, `GOLDEN`), і
     * саме він ловить те, що два кодувальники розійшлись.
     *
     * Вони вже були розійшлись, і мовчки: веб нумерував теги з нуля (rev=0,
     * avatar=1, …) проти одиниці тут, ніс `rev` варінтом проти твердих
     * чотирьох байтів тут, і віддавав голі байти без `PH-PRF:` і без base64.
     * Кожна половина була зелена сама проти себе; жоден кадр не був би
     * прочитаний іншою стороною — а на екрані це виглядало б як «профіль
     * просто не оновлюється».
     *
     * Рядок — не «результат, який вийшов»: він порахований третьою рукою
     * (python над описом формату з контракту), тож збіг доводить, що обидва
     * кодувальники читають ОДИН опис, а не однаково помиляються.
     */
    @Test
    fun `золотий вектор — той самий рядок, що у веб-тесті`() {
        val wire = PeerProfile.encode(
            body(
                rev = 7,
                avatar = PeerProfile.Field.Set(byteArrayOf(1, 2, 3)),
                emoji = PeerProfile.Field.Set("🇺🇦"),
                text = PeerProfile.Field.Set("привіт"),
                bio = PeerProfile.Field.Set("біо"),
            ),
            pad = false,
        )
        assertEquals(
            "PH-PRF:AQQAAAAHAgMBAgMDCPCfh7rwn4emBAzQv9GA0LjQstGW0YIFBtCx0ZbQvg",
            wire,
        )
    }

    @Test
    fun `золотий вектор з тегами 6-8 — той самий рядок, що у веб-тесті`() {
        val wire = PeerProfile.encode(
            PeerProfile.Body(
                rev = 7,
                avatar = PeerProfile.Field.Set(byteArrayOf(1, 2, 3)),
                statusEmoji = PeerProfile.Field.Set("🇺🇦"),
                statusText = PeerProfile.Field.Set("привіт"),
                bio = PeerProfile.Field.Set("біо"),
                name = PeerProfile.Field.Set("Соня"),
                handle = PeerProfile.Field.Set("sonia"),
                photo = PeerProfile.Field.Set(byteArrayOf(9, 9)),
            ),
            pad = false,
        )
        assertEquals(
            "PH-PRF:AQQAAAAHAgMBAgMDCPCfh7rwn4emBAzQv9GA0LjQstGW0YIFBtCx0ZbQvgYI0KHQvtC90Y8HBXNvbmlhCAIJCQ",
            wire,
        )
    }

    // ── імʼя ─────────────────────────────────────────────────────────────

    @Test
    fun `імʼя доїжджає туди й назад`() {
        val sent = PeerProfile.Body(rev = 3, bio = PeerProfile.Field.Set("біо"), name = PeerProfile.Field.Set("Кирило"))
        assertEquals(sent, PeerProfile.decode(PeerProfile.encode(sent)!!))
    }

    @Test
    fun `золотий вектор з імʼям — той самий, що в контракті`() {
        val wire = PeerProfile.encode(PeerProfile.Body(rev = 7, name = PeerProfile.Field.Set("Кирило")), pad = false)
        assertEquals("PH-PRF:AQQAAAAHBgzQmtC40YDQuNC70L4", wire)
    }

    @Test
    fun `кадр старої збірки без імені читається, імʼя — не приїхало`() {
        val old = PeerProfile.decode("PH-PRF:AQQAAAAHAgMBAgMDCPCfh7rwn4emBAzQv9GA0LjQstGW0YIFBtCx0ZbQvg")
        assertNotNull(old)
        assertEquals(PeerProfile.Field.Absent, old!!.name)
        assertEquals(PeerProfile.Field.Set("біо"), old.bio)
    }

    @Test
    fun `порожнє імʼя не їде і не стирає`() {
        val wire = PeerProfile.encode(PeerProfile.Body(rev = 1, name = PeerProfile.Field.Set("")))!!
        assertEquals(PeerProfile.encode(PeerProfile.Body(rev = 1)), wire)
        assertEquals(PeerProfile.Field.Absent, PeerProfile.decode(wire)!!.name)
        assertEquals(PeerProfile.Field.Absent, PeerProfile.fitName("   "))
    }

    @Test
    fun `довге імʼя вкорочується по графемі, а не валить кадр`() {
        val family = "👨‍👩‍👧‍👦"
        val fitted = PeerProfile.fitName(family.repeat(40)) as PeerProfile.Field.Set
        assertTrue(fitted.value.toByteArray(Charsets.UTF_8).size <= PeerProfile.MAX_NAME_BYTES)
        assertTrue("емодзі не розірване", PeerName.graphemes(fitted.value).all { it == family })
        assertNotNull(PeerProfile.encode(PeerProfile.Body(rev = 1, name = fitted)))
    }

    @Test
    fun `кадри доповнюються тегом 10 до кошика 4, 12 або 32 КіБ, а розбирач ігнорує доповнення`() {
        fun rawSize(wire: String) =
            java.util.Base64.getDecoder().decode(wire.removePrefix("PH-PRF:")).size

        val small = PeerProfile.Body(rev = 1, bio = PeerProfile.Field.Set("коротко"))
        val smallWire = PeerProfile.encode(small)!!
        assertEquals(4 * 1024, rawSize(smallWire))
        assertEquals(small, PeerProfile.decode(smallWire))

        val thumb = PeerProfile.Field.Set(ByteArray(2 * 1024) { (it % 251).toByte() })
        val p3k = PeerProfile.Body(
            rev = 2,
            avatar = thumb,
            photo = PeerProfile.Field.Set(ByteArray(3 * 1024) { (it % 199).toByte() }),
        )
        val p9k = PeerProfile.Body(
            rev = 2,
            avatar = thumb,
            photo = PeerProfile.Field.Set(ByteArray(9 * 1024) { (it % 199).toByte() }),
        )
        val w3k = PeerProfile.encode(p3k)!!
        val w9k = PeerProfile.encode(p9k)!!
        assertEquals(12 * 1024, rawSize(w3k))
        assertEquals(12 * 1024, rawSize(w9k))
        assertEquals(w3k.length, w9k.length)
        assertEquals(p3k, PeerProfile.decode(w3k))
        assertEquals(p9k, PeerProfile.decode(w9k))

        val p20k = PeerProfile.Body(
            rev = 3,
            avatar = thumb,
            photo = PeerProfile.Field.Set(ByteArray(20 * 1024) { (it % 173).toByte() }),
        )
        val w20k = PeerProfile.encode(p20k)!!
        assertEquals(32 * 1024, rawSize(w20k))
        assertEquals(p20k, PeerProfile.decode(w20k))
    }
}
