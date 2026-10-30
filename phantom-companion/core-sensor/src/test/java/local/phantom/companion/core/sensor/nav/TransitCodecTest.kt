package local.phantom.companion.core.sensor.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Кодек транспортного шару — чиста половина шва v3, на JVM.
 *
 * ЩО ЦЕ СТЕРЕЖЕ. Пакет пече `mesh_bake.py`, читає [TransitCodec], і між ними
 * немає жодного спільного файла — лише домовленість про списки ключів.
 * Кодувальник обходить БІЛИЙ СПИСОК, а не вхід, тож порядок вирішує байти
 * ПРИ ЗАПИСІ: дві печі з різними списками дадуть для тієї самої дороги різні
 * рядки, і звірка «що змінилось між пакетами» стане нечитабельною. На
 * ЧИТАННЯ порядок не впливає — рядок `k=v` самоописовий.
 *
 * Тому списки тут виписані ЛІТЕРАЛОМ, а не взяті з [TransitCodec]: тест,
 * який читає те саме джерело, що й код, не має чого стерегти.
 */
class TransitCodecTest {

    @Test
    fun `stop key whitelist is exactly the one the baker writes`() {
        assertEquals(
            listOf(
                "highway", "railway", "public_transport", "station",
                "bus", "trolleybus", "tram", "train", "subway", "share_taxi",
                "light_rail", "monorail", "funicular",
                "ref", "local_ref", "operator", "network",
                "shelter", "bench", "covered", "tactile_paving", "wheelchair",
                "departures_board",
            ),
            TransitCodec.STOP_KEYS,
        )
    }

    @Test
    fun `rail key whitelist is exactly the one the baker writes`() {
        assertEquals(
            listOf(
                "railway", "usage", "service", "electrified", "gauge",
                "bridge", "tunnel", "layer", "ref", "operator",
            ),
            TransitCodec.RAIL_KEYS,
        )
    }

    @Test
    fun `route modes are exactly the relations the baker filters for`() {
        assertEquals(
            listOf(
                "subway", "train", "light_rail", "tram", "trolleybus", "bus",
                "share_taxi", "monorail", "funicular",
            ),
            TransitCodec.ROUTE_MODES,
        )
    }

    @Test
    fun `tags encode in whitelist order, not input order`() {
        val encoded = TransitCodec.encodeTags(
            linkedMapOf("wheelchair" to "yes", "railway" to "station", "name" to "Київ-Пас."),
            TransitCodec.STOP_KEYS,
        )
        // `name` живе окремою колонкою і в теги не потрапляє взагалі.
        assertEquals("railway=station|wheelchair=yes", encoded)
    }

    @Test
    fun `tags round-trip`() {
        val tags = mapOf(
            "highway" to "bus_stop", "shelter" to "yes", "operator" to "Київпастранс",
        )
        assertEquals(
            tags,
            TransitCodec.decodeTags(TransitCodec.encodeTags(tags, TransitCodec.STOP_KEYS)),
        )
    }

    @Test
    fun `route list round-trips and keeps its baked order`() {
        val pairs = listOf(
            "trolleybus" to "24", "bus" to "102А", "share_taxi" to "МТ530",
        )
        val encoded = TransitCodec.encodeRoutes(pairs)
        assertEquals("trolleybus:24|bus:102А|share_taxi:МТ530", encoded)
        assertEquals(pairs, TransitCodec.decodeRoutes(encoded))
    }

    /**
     * Той самий клас вади, що вже коштував офлайну смуг: значення з
     * роздільником у собі. `turn:lanes` розривало рядок вертикальною рискою —
     * тут те саме зробила б двокрапка в номері маршруту.
     */
    @Test
    fun `a separator inside a route number does not tear the row`() {
        val pairs = listOf("bus" to "5:A", "tram" to "1|2")
        val decoded = TransitCodec.decodeRoutes(TransitCodec.encodeRoutes(pairs))
        assertEquals(pairs, decoded)
    }

    @Test
    fun `empty encodes to empty and decodes to nothing`() {
        assertEquals("", TransitCodec.encodeRoutes(emptyList()))
        assertTrue(TransitCodec.decodeRoutes("").isEmpty())
        assertTrue(TransitCodec.decodeRoutes("bus").isEmpty())
        assertTrue(TransitCodec.decodeRoutes("bus:").isEmpty())
    }

    /**
     * Словник каже, чого пакет НЕ несе — і саме це варте уваги.
     *
     * ЩО ТУТ СТОЯЛО РАНІШЕ Й ЧОМУ ЦЕ БУЛО ХИБНО. Тут був тест, який вимагав
     * ВІДМОВИ від транспортного шару при будь-якій розбіжності словника,
     * на підставі «порядок ключів вирішує байти, тож чужий порядок
     * прочитався б як свій». Підстава не витримує перевірки: рядок
     * `k=v|k=v` самоописовий, [TransitCodec.decodeTags] бере пари за
     * іменами й порядку не знає. Тобто тест закріплював відмову через
     * ризик, якого не існує, — і ціна була б тиха й велика: варто печі
     * навчитись одного ключа раніше за телефон, і ВЕСЬ транспорт перестав
     * би вноситись.
     *
     * Тепер перевіряється корисне: які ключі пристрій уміє читати, а пакет
     * їх не привіз.
     */
    @Test
    fun `a pack baked before a key existed names that key`() {
        val older = TransitCodec.vocabulary().toMutableMap()
        older["routing_keys"] = older["routing_keys"]!!.split("|")
            .filter { it != "motorroad" }.joinToString("|")
        assertEquals(listOf("motorroad"), TransitCodec.vocabularyGap(older))
    }

    @Test
    fun `a pack that carries everything we read leaves no gap`() {
        assertTrue(TransitCodec.vocabularyGap(TransitCodec.vocabulary()).isEmpty())
    }

    /** Зайве в пакеті — не наш клопіт: ми його просто не читаємо. */
    @Test
    fun `keys we do not read are not a gap`() {
        val richer = TransitCodec.vocabulary().toMutableMap()
        richer["stop_keys"] = richer["stop_keys"] + "|surface|ele"
        assertTrue(TransitCodec.vocabularyGap(richer).isEmpty())
    }

    /** Порядок при читанні не важить — переставлений список не є пропажею. */
    @Test
    fun `a different order is not a missing key`() {
        val shuffled = TransitCodec.vocabulary().toMutableMap()
        shuffled["stop_keys"] = TransitCodec.STOP_KEYS.reversed().joinToString("|")
        assertTrue(TransitCodec.vocabularyGap(shuffled).isEmpty())
    }

    /**
     * Пакети до v3 таблиці `meta` не мають. Це не пропажа — це відсутність
     * будь-якої заяви про себе, і судити тут нема по чому.
     */
    @Test
    fun `a pack that declares no vocabulary is not accused of anything`() {
        assertTrue(TransitCodec.vocabularyGap(emptyMap()).isEmpty())
    }
}
