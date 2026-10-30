package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Я тут» як повідомлення.
 *
 * Дві помилки коштують життя: кома замість крапки в чужій локалі (координата
 * стає іншим містом) і мовчазне прийняття сміття як точки — мітка в
 * правдоподібно неправильному місці гірша за відсутність мітки.
 */
class PeerPayloadTest {

    @Test
    fun `a place survives the round trip`() {
        val text = PeerPayload.encodePlace(50.45014, 30.52339, 12)
        val place = PeerPayload.decodePlace(text)!!
        assertEquals(50.45014, place.lat, 1e-5)
        assertEquals(30.52339, place.lon, 1e-5)
        assertEquals(12, place.accuracyM)
    }

    @Test
    fun `coordinates use a dot in every locale`() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale("uk", "UA"))
            val text = PeerPayload.encodePlace(50.45014, 30.52339)
            // Кома тут РОЗДІЛЯЄ координати, тож рахуємо саме її кількість:
            // «50,45014,30,52339» — це чотири числа й інше місто на мапі.
            assertEquals("comma decimal separator leaked: $text", 1, text.count { it == ',' })
            assertEquals(50.45014, PeerPayload.decodePlace(text)!!.lat, 1e-5)
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun `accuracy is optional and never zero`() {
        assertNull(PeerPayload.decodePlace(PeerPayload.encodePlace(1.0, 2.0))!!.accuracyM)
        assertNull(PeerPayload.decodePlace(PeerPayload.encodePlace(1.0, 2.0, 0))!!.accuracyM)
    }

    @Test
    fun `words are not a place`() {
        assertNull(PeerPayload.decodePlace("я на площі"))
        assertNull(PeerPayload.decodePlace(""))
        assertNull(PeerPayload.decodePlace("PH-LOC:"))
        assertNull(PeerPayload.decodePlace("PH-LOC:abc,def"))
        assertNull(PeerPayload.decodePlace("PH-LOC:50.45"))
    }

    @Test
    fun `an impossible coordinate is refused, not clamped`() {
        assertNull(PeerPayload.decodePlace("PH-LOC:91.0,30.0"))
        assertNull(PeerPayload.decodePlace("PH-LOC:50.0,181.0"))
        assertNull(PeerPayload.decodePlace("PH-LOC:-90.1,0.0"))
    }

    @Test
    fun `the poles and the meridian are still places`() {
        assertTrue(PeerPayload.isPlace(PeerPayload.encodePlace(90.0, 180.0)))
        assertTrue(PeerPayload.isPlace(PeerPayload.encodePlace(-90.0, -180.0)))
        assertTrue(PeerPayload.isPlace(PeerPayload.encodePlace(0.0, 0.0)))
    }

    // ── сумісність збірок ────────────────────────────────────────────────

    @Test
    fun `нова збірка читає кадр, складений старою`() {
        // Дослівні тіла з дроту, не перезібрані кодеком: якби тут стояв
        // encodePlace, тест доводив би, що кодек сумісний сам із собою.
        val bare = PeerPayload.decodePlace("PH-LOC:50.45010,30.52340")
        assertEquals(50.45010, bare!!.lat, 1e-6)
        assertEquals(30.52340, bare.lon, 1e-6)
        assertNull(bare.accuracyM)

        val withAccuracy = PeerPayload.decodePlace("PH-LOC:50.45010,30.52340;12")
        assertEquals(12, withAccuracy!!.accuracyM)
    }

    @Test
    fun `стара точність виживає, а зайвий хвіст ігнорується`() {
        // Рішення 12.09: спершу ТЕРПИМИЙ розбирач, мітка — наступним релізом.
        // Доти хвіст ніхто не шле, але збірка, яка його зустріне, мусить
        // віддати точність, а не втратити її.
        val extended = PeerPayload.decodePlace("PH-LOC:50.45010,30.52340;12;Кафе на розі")
        assertEquals(50.45010, extended!!.lat, 1e-6)
        assertEquals(30.52340, extended.lon, 1e-6)
        assertEquals(
            "точність загинула на хвості — розбирач знову строгий, і формат знову нерозширюваний",
            12,
            extended.accuracyM,
        )

        // Хвіст із `;` усередині теж не зʼїдає точності.
        assertEquals(12, PeerPayload.decodePlace("PH-LOC:50.45010,30.52340;12;Кафе; і бар")!!.accuracyM)

        // Порожня точність із хвостом лишається порожньою, а не стає нулем:
        // нуль тут був би виміром, якого ніхто не робив.
        assertNull(PeerPayload.decodePlace("PH-LOC:50.45010,30.52340;;Кафе")!!.accuracyM)

        // І хвіст не робить місцем те, що ним не є.
        assertNull(PeerPayload.decodePlace("PH-LOC:91.0,30.0;12;Кафе"))
    }
}
