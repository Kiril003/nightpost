package local.phantom.companion.core.sensor.nav

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Транспортний скелет у постійній мережі: запис, читання і — головне — межа,
 * якої колія не переходить.
 */
@RunWith(RobolectricTestRunner::class)
class RoadMeshTransitTest {

    private fun ctx() = ApplicationProvider.getApplicationContext<Context>()

    private fun store(name: String) = RoadMeshStore(ctx(), name)

    private val stop = TransitStop(
        id = 7024197696L,
        lat = 50.51231,
        lon = 30.42545,
        name = "Медичний центр",
        tags = mapOf("public_transport" to "stop_position", "trolleybus" to "yes"),
        routes = TransitCodec.encodeRoutes(listOf("trolleybus" to "24", "bus" to "72")),
    )

    private val rail = RailLine(
        id = 4331838L,
        points = listOf(50.412981 to 30.645086, 50.413004 to 30.645152, 50.413100 to 30.645400),
        name = null,
        tags = mapOf("railway" to "tram", "usage" to "main", "gauge" to "1524"),
    )

    @Test
    fun `a stop round-trips through the store with its routes`() {
        val s = store("transit-stop.db")
        s.putStops(listOf(stop))
        val read = s.stopsIn(50.51, 30.42, 50.52, 30.43)
        assertEquals(1, read.size)
        assertEquals(stop.name, read[0].name)
        assertEquals(stop.id, read[0].id)
        assertEquals(50.51231, read[0].lat, 1e-9)
        assertEquals("stop_position", read[0].tags["public_transport"])
        assertEquals(listOf("trolleybus" to "24", "bus" to "72"), read[0].serving)
        s.close()
    }

    @Test
    fun `a stop outside the queried bbox is not returned`() {
        val s = store("transit-bbox.db")
        s.putStops(listOf(stop))
        assertTrue(s.stopsIn(49.0, 29.0, 49.1, 29.1).isEmpty())
        s.close()
    }

    /**
     * ЦЕ ГОЛОВНИЙ ТЕСТ ЦЬОГО ФАЙЛА, і він про межу, а не про запис.
     *
     * Колія — це лінія з `railway=*` і БЕЗ `highway`. Кожне правило
     * [RoadProfile.blocked] питає `highway`, тож для такої лінії жодне не
     * спрацьовує (див. другий тест нижче — він міряє саме це). Отже, якби
     * колії лягли в ту саму таблицю `ways`, вони стали б звичайними ребрами
     * графа ціною голої довжини: [LocalRouter] повів би людину коліями, а
     * [RoadMatcher] приліпив би до них її позицію.
     *
     * Тому межу тримає СХЕМА, а не правило, яке комусь треба памʼятати:
     * `railsIn` бачить колію, `waysIn` — ні, і граф, зібраний із `waysIn`,
     * не має до чого приліпитись за метр від рейки.
     */
    @Test
    fun `rails never enter the routing graph`() {
        val s = store("transit-guard.db")
        s.putRails(listOf(rail))

        assertEquals(1, s.railsIn(50.41, 30.64, 50.42, 30.65).size)
        assertTrue(
            "колія потрапила у таблицю доріг — маршрутизатор поведе нею людину",
            s.waysIn(50.41, 30.64, 50.42, 30.65).isEmpty(),
        )

        val graph = RoadGraph(s.waysIn(50.41, 30.64, 50.42, 30.65))
        assertTrue(
            "граф знайшов кандидата на рейці",
            graph.near(50.412990, 30.645100, radiusM = 50.0).isEmpty(),
        )
        s.close()
    }

    /**
     * Вимір, на якому тримається тест вище. Якщо це колись стане `true`,
     * поділ таблиць перестане бути єдиною межею — і тоді цей тест почервоніє
     * першим, змусивши перечитати рішення, а не мовчки його пережити.
     */
    @Test
    fun `a rail line is not blocked by any road rule — which is why it must not be a way`() {
        val railTags = mapOf("railway" to "rail", "usage" to "main")
        assertFalse(RoadProfile.blocked(railTags, foot = true))
        assertFalse(RoadProfile.blocked(railTags, foot = false))
    }

    @Test
    fun `rails round-trip with their tags`() {
        val s = store("transit-rail.db")
        s.putRails(listOf(rail))
        val read = s.railsIn(50.41, 30.64, 50.42, 30.65)
        assertEquals(1, read.size)
        assertEquals("main", read[0].tags["usage"])
        assertEquals("1524", read[0].tags["gauge"])
        assertEquals(rail.points.size, read[0].points.size)
        s.close()
    }

    @Test
    fun `routes carry where the line goes, and nothing about when`() {
        val s = store("transit-routes.db")
        val route = TransitRoute(
            id = 177091L,
            mode = "trolleybus",
            ref = "14",
            name = "Тролейбус 14",
            fromName = "Ботанічний сад",
            toName = "Залізничний вокзал",
            colour = "",
            stops = listOf(1L, stop.id, 3L),
        )
        s.putRoutes(listOf(route))
        assertEquals(listOf(route), s.routesByIds(listOf(177091L)))
        assertEquals(listOf(route), s.routesServing(stop.id))
        assertEquals(listOf(route), s.routesServing(1L))
        assertEquals(listOf(route), s.routesServing(3L))
        assertTrue(s.routesServing(999L).isEmpty())
        s.close()
    }

    /**
     * Запис транспорту мусить будити кеш коридорів так само, як запис доріг —
     * інакше повторюється вада, знайдена на `bulkImport`: дані змінились,
     * а лічильник, що про це повідомляє, ні. Див. [MeshRevisionTest].
     */
    @Test
    fun `writing transit bumps the revision`() {
        val s = store("transit-revision.db")
        val before = s.revision
        s.putStops(listOf(stop))
        assertNotEquals(before, s.revision)
        s.close()
    }

    /**
     * БІЛИЙ СПИСОК ВИРІШУЄ, ЩО ЛЯЖЕ В БАЗУ ПРИСТРОЮ — а не лише що напише піч.
     *
     * Цей тест стоїть тут через конкретну хибну міру. 5 вересня 2026 сусідня
     * сесія додала `motorroad` у піч і виміряла, що правки Kotlin не треба:
     * `RoadWayCodec.decodeTags` справді не фільтрує за білим списком, отже
     * ключ із пакета читається. Вимір правильний — і зупинився на шар вище,
     * ніж треба.
     *
     * Повний шлях ключа з пакета такий:
     *
     *   пакет → RoadPackImporter.decodeTags   (білого списку нема, ключ живий)
     *         → RoadMeshStore.bulkImport → insertWay
     *         → RoadWayCodec.encodeTags       ← БІЛИЙ СПИСОК, ключ ГИНЕ ТУТ
     *         → база пристрою вже без нього
     *         → RoadProfile читає базу і правила не бачить
     *
     * Тобто без ключа у [RoadWayCodec] правило `motorroad` не спрацювало б і
     * на пакетах — не лише на тайлах з Overpass. Сховище ПЕРЕЗАПИСУЄ теги
     * своїм кодеком, і саме це робить білий список Kotlin обовʼязковим.
     */
    @Test
    fun `a tag outside the whitelist dies on the way into the store, not on the way out`() {
        val s = store("whitelist-write.db")
        s.put(
            listOf(
                RoadWay(
                    id = 991L,
                    points = listOf(50.40 to 30.50, 50.41 to 30.51),
                    tags = mapOf(
                        "highway" to "trunk",
                        "motorroad" to "yes",
                        // Ключа, якого нема в списку, база не побачить — і це
                        // та сама механіка, що вбивала б motorroad до правки.
                        "wikidata" to "Q42",
                    ),
                ),
            ),
        )
        val back = s.waysIn(50.39, 30.49, 50.42, 30.52)
        assertEquals(1, back.size)
        assertEquals("trunk", back[0].tags["highway"])
        assertEquals(
            "motorroad не доїхав до бази — правило RoadProfile.kt:80 знову мертве",
            "yes",
            back[0].tags["motorroad"],
        )
        assertNull(
            "ключ поза білим списком не мав би зберігатись",
            back[0].tags["wikidata"],
        )
        s.close()
    }

    /** Та сама межа без бази: кодек, а не сховище, вирішує долю ключа. */
    @Test
    fun `the codec drops what the whitelist omits, and keeps what it names`() {
        val roundTrip = { tags: Map<String, String> ->
            RoadWayCodec.decodeTags(RoadWayCodec.encodeTags(tags))
        }
        assertEquals("yes", roundTrip(mapOf("motorroad" to "yes"))["motorroad"])
        assertTrue(roundTrip(mapOf("wikidata" to "Q42")).isEmpty())
    }

    @Test
    fun `an empty write does not claim the network changed`() {
        val s = store("transit-empty.db")
        val before = s.revision
        s.putStops(emptyList())
        assertEquals(before, s.revision)
        s.close()
    }
}
