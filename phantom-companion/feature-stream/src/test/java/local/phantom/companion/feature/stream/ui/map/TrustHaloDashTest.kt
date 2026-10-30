package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.sensor.nav.LocationTrust
import local.phantom.companion.feature.stream.ui.map.nav.EwTrail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustHaloDashTest {

    @Test
    fun `solid is reserved for a trusted sky`() {
        assertNull(trustHaloDash(EwTrail.colorFor(dead = false, trust = LocationTrust.TRUSTED)))
        for (trust in LocationTrust.entries) {
            if (trust == LocationTrust.TRUSTED) continue
            assertNotNull(
                "$trust must not draw the solid ring — solid is the TRUSTED claim",
                trustHaloDash(EwTrail.colorFor(dead = false, trust = trust)),
            )
        }
        assertNotNull(
            "no verdict must not draw the solid ring either",
            trustHaloDash(EwTrail.colorFor(dead = false, trust = null)),
        )
    }

    @Test
    fun `each doubting state gets its own texture`() {
        val textures = listOf(
            LocationTrust.DEGRADED to listOf(6f, 2f, 1f, 2f),
            LocationTrust.JAMMED to listOf(4f, 2f, 1f, 2f, 1f, 2f),
            LocationTrust.SPOOFED to listOf(2f, 2f, 1f, 2f, 1f, 2f, 1f, 2f),
        )
        for ((trust, expected) in textures) {
            val dash = trustHaloDash(EwTrail.colorFor(dead = false, trust = trust))
            assertEquals("$trust texture", expected, dash?.toList())
        }
        val distinct = textures.map { it.second }.toSet()
        assertEquals("two states sharing a texture is one state", textures.size, distinct.size)
    }

    @Test
    fun `dead reckoning reads as jammed, not as trusted`() {
        assertEquals(
            listOf(4f, 2f, 1f, 2f, 1f, 2f),
            trustHaloDash(EwTrail.colorFor(dead = true, trust = LocationTrust.TRUSTED))?.toList(),
        )
    }

    /**
     * ВЕРДИКТ ГОНТЛЕТУ, 5 персон, серйозно: «коло сумніву читається як
     * маршрут — за кермом я б поїхав по ній»; дальтонік не відрізнив його від
     * стежки. Барва тут не рятує нікого, тож рятує ФОРМА штриха.
     *
     * Дорога, стежка, пунктир кордону — це завжди ОДНА пара «риска-проміжок»,
     * яку рушій повторює. Штрих із чотирьох і більше чисел, де довга риска
     * чергується з крапкою, не належить жодній лінійній фічі цієї мапи —
     * і саме це тут вимагається, а не конкретні числа (їх міряє тест вище).
     */
    @Test
    fun `no doubt texture can be mistaken for a road or a path`() {
        val cases = LocationTrust.entries.map { it.name to EwTrail.colorFor(false, it) } +
            listOf("вироку немає" to EwTrail.colorFor(false, null))
        for ((name, hex) in cases) {
            val dash = trustHaloDash(hex)?.toList()
                ?: continue // солід = TRUSTED, і він теж не пунктир
            assertTrue(
                "$name малюється парою «риска-проміжок» ($dash) — це клас дороги й стежки",
                dash.size >= 4,
            )
            val pairs = dash.chunked(2).toSet()
            assertTrue(
                "$name: $dash — це одна пара, повторена рушієм, тобто рівно те, " +
                    "чим намальовані дорога, стежка й пунктир кордону",
                pairs.size > 1,
            )
        }
    }

    /**
     * Друга половина відповіді на той самий вирок: у лінії немає нутра, у
     * зони є. Заливка мусить існувати — і мусить лишатись шепотом, бо диск
     * тут уже жив і фарбував третину екрана.
     */
    @Test
    fun `the zone is present but never louder than a whisper`() {
        listOf(true, false).forEach { isDark ->
            MapSymbolPalette.TRUST_RING_DAY.keys.forEach { night ->
                val zone = trustZoneAlpha(night, isDark)
                assertTrue("$night (isDark=$isDark) без заливки — кільце знову лінія", zone > 0f)
                assertTrue("$night (isDark=$isDark) заливка $zone кричить", zone <= 0.10f)
                assertTrue(
                    "$night (isDark=$isDark): заливка $zone сперечається з власною межею",
                    zone < trustRingAlpha(night, isDark) / 2f,
                )
            }
        }
    }

    /**
     * The coupling this design deliberately took on: the halo reads its state
     * out of EwTrail's hex. If that palette moves, the texture silently falls
     * to the absence pattern and a spoof would draw as "engine not running".
     */
    @Test
    fun `every hex EwTrail can emit is one this table names`() {
        val named = setOf("#38BDF8", "#F59E0B", "#F87171", "#EF4444", "#64748B")
        val emitted = buildSet {
            for (dead in listOf(false, true)) {
                add(EwTrail.colorFor(dead, null))
                for (trust in LocationTrust.entries) add(EwTrail.colorFor(dead, trust))
            }
        }
        assertTrue(
            "EwTrail emits $emitted; trustHaloDash names $named — the unnamed ones fall to absence",
            named.containsAll(emitted),
        )
    }

    /**
     * ТА САМА пастка, з другого боку — і саме вона тут найдорожча.
     *
     * Таблиця ключується на hex. Денна земля дістала власні hex, і кожен із
     * них, не названий тут, тихо провалюється у гілку «вироку немає» — а
     * денний TRUSTED, ще гірше, лишився б СОЛІДОМ, тобто вдень кожен вирок
     * малювався б суцільним кільцем «довіряю». Жоден інший тест цього не
     * бачить: компілюється, малюється, бреше.
     */
    @Test
    fun `a day hex draws the same texture as its night twin`() {
        MapSymbolPalette.TRUST_RING_DAY.forEach { (night, day) ->
            assertEquals(
                "денний $day мусить нести ту саму текстуру, що нічний $night",
                trustHaloDash(night)?.toList(),
                trustHaloDash(day)?.toList(),
            )
        }
    }

    @Test
    fun `no day hex falls through to solid or to absence by accident`() {
        val absence = listOf(1f, 2f, 1f, 6f)
        MapSymbolPalette.TRUST_RING_DAY.forEach { (night, day) ->
            val dash = trustHaloDash(day)?.toList()
            if (night == "#38BDF8") {
                assertNull("solid лишається за TRUSTED", dash)
            } else {
                assertNotNull("$day втратив штрих — це солід=«TRUSTED» на вирок $night", dash)
            }
            if (night != "#64748B") {
                assertTrue(
                    "$day провалився у гілку «вироку немає»",
                    dash != absence,
                )
            }
        }
    }

    @Test
    fun `every verdict has a day pair, and no two verdicts share one`() {
        val emitted = buildSet {
            for (dead in listOf(false, true)) {
                add(EwTrail.colorFor(dead, null))
                for (trust in LocationTrust.entries) add(EwTrail.colorFor(dead, trust))
            }
        }
        val missing = emitted - MapSymbolPalette.TRUST_RING_DAY.keys
        assertTrue(
            "без денної пари ці вироки малюються нічним кольором на бежевій землі: $missing",
            missing.isEmpty(),
        )
        assertEquals(
            "два вироки з однією денною барвою — це один вирок",
            MapSymbolPalette.TRUST_RING_DAY.size,
            MapSymbolPalette.TRUST_RING_DAY.values.toSet().size,
        )
    }

    /**
     * КОНТРАКТ ЗМІНЕНО 01.09, і сторож переписаний під новий, а не знятий.
     *
     * **Було:** барва кільця = барва вердикту, перекладена за полярністю землі.
     * **Стало:** одне СПОКІЙНЕ чорнило на всі вердикти; вердикт лишається смузі
     * довіри, яка каже його словом.
     *
     * Причина — вирок власника після прямого порівняння з Google: коло того
     * самого розміру в них читається «десь тут», у нас «аварія». Вердикт був
     * закодований на кільці **тричі** (барва, гучність, пунктир) і ще раз словом
     * у смузі. Повтор у тривожному повідомленні не додає ясності — він піднімає
     * градус. Кільце лишає собі одну величину: сумнів.
     *
     * Пунктир як розрізняч ЛИШАЄТЬСЯ — він тихий канал, і його сторожі вище
     * чинні без змін.
     */
    @Test
    fun `кільце має одне спокійне чорнило на всі вердикти`() {
        val verdicts = listOf("#38BDF8", "#F59E0B", "#F87171", "#EF4444", "#64748B")
        listOf(true, false).forEach { isDark ->
            val inks = verdicts.map { trustRingHex(it, isDark) }.toSet()
            assertEquals(
                "кільце знову фарбується за вердиктом (isDark=$isDark): $inks — " +
                    "вердикт належить смузі, кільцю належить сумнів",
                1,
                inks.size,
            )
        }
        assertTrue(
            "чорнило мусить перевертатись разом із землею",
            trustRingHex("#F87171", isDark = true) != trustRingHex("#F87171", isDark = false),
        )
    }

    /**
     * І головна межа: **спокій — це відтінок, а не прозорість.**
     *
     * Денну драбину гучності свого часу підняли саме тому, що «навіть чорне
     * чорнило на 55% бере 3.0:1». Якщо хтось вирішить зробити кільце ще
     * спокійнішим, знизивши контраст, — удень воно зникне. Тому підлога стоїть
     * тут окремим тестом, а не в коментарі.
     */
    @Test
    fun `спокійне чорнило тримає контраст проти своєї землі`() {
        fun lin(c: Double) = if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        fun luminance(hex: String): Double {
            val v = hex.removePrefix("#").toInt(16)
            val r = lin(((v shr 16) and 0xFF) / 255.0)
            val g = lin(((v shr 8) and 0xFF) / 255.0)
            val b = lin((v and 0xFF) / 255.0)
            return 0.2126 * r + 0.7152 * g + 0.0722 * b
        }
        fun ratio(a: String, b: String): Double {
            val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
            return (hi + 0.05) / (lo + 0.05)
        }
        listOf(true, false).forEach { isDark ->
            val ink = trustRingHex("#F87171", isDark)
            val land = "#%06X".format(groundPalette(isDark).land and 0xFFFFFF)
            val c = ratio(ink, land)
            assertTrue(
                "кільце $ink проти землі $land дає ${"%.1f".format(c)}:1 — нижче підлоги 3.0:1, " +
                    "удень воно просто зникне. Спокій робиться ВІДТІНКОМ, не прозорістю.",
                c >= 3.0,
            )
        }
    }
}
