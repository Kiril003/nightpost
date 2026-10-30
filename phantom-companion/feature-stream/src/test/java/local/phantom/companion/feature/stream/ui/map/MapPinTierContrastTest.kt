package local.phantom.companion.feature.stream.ui.map

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Три яруси піна — і причина, чому вони були ладом лише на папері.
 *
 * `MapSymbolContrastTest` міряє плашку, регістри, альтернативу, штрих і текст.
 * Тони ЯРУСІВ не міряв ніхто: коментар у [MapSymbolPalette] називав числа, які
 * жоден прогін не переобчислював. Наслідок був не косметичний — аптека
 * (#DC2626, 4.20:1 проти плашки) важила МЕНШЕ за крамницю (#5A5F66, 5.60:1),
 * а крок між критичним і опорним був 1.04:1, тобто при знятому кольорі два
 * яруси зливались в один.
 *
 * Тут прибито три речі, і кожна — число, а не смак.
 *
 * 1. ПІДЛОГА. Кожен тон ≥3:1 проти плашки, на якій лежить. Та сама підлога,
 *    що в MapSymbolContrastTest, і та сама арифметика (WCAG поверх src-over).
 * 2. ЛАД. Вага = важливість: критичне > опорне > звичайне. Саме це й було
 *    перевернуто.
 * 3. КРОК. Сусідні яруси різняться ≥1.35:1 і з кольором, і БЕЗ нього —
 *    під дейтеранопією (Viénot). Той самий стандарт, який регістри маршруту
 *    вже тримають текстурою («told apart without colour at all»), лише тут
 *    його несе світлота.
 *
 * Чому це важить саме зараз: корпус місць виріс із 21 335 київських точок до
 * 175 685 по Україні. У кадрі великого міста будуть сотні пінів, і ярус — це
 * єдине, що вирішує, хто з них лишиться видимим оку.
 */
class MapPinTierContrastTest {

    /** Плашка тематично незмінна, тож ґрунт для тону — один на обидві
     *  полярності; альфа плашки складається поверх найтемнішого, що під нею
     *  буває, — щоб число не було оптимістичним. */
    private fun plateFace(): Int = composite(MapSymbolPalette.DARK.plate, 0x000000)

    private fun tiers(isDark: Boolean): List<Pair<String, Int>> {
        val p = MapSymbolPalette.resolve(isDark)
        // Порядок списку = спадання важливості. Він і перевіряється.
        return listOf(
            "критичне" to p.pinCritical,
            "опорне" to p.pinSustain,
            "звичайне" to p.pinOrdinary,
        )
    }

    @Test
    fun `every tier tone clears the plate it sits inside`() {
        val plate = plateFace()
        for (isDark in listOf(true, false)) {
            for ((name, tone) in tiers(isDark)) {
                assertTrue(
                    "$name ${hex(tone)} на плашці ${hex(plate)} — ${ratio(tone, plate)}:1",
                    contrast(tone, plate) >= 3.0,
                )
            }
        }
    }

    @Test
    fun `weight follows importance, never the other way round`() {
        val plate = plateFace()
        for (isDark in listOf(true, false)) {
            val ladder = tiers(isDark).map { (n, c) -> n to contrast(c, plate) }
            for ((louder, quieter) in ladder.zipWithNext()) {
                assertTrue(
                    "isDark=$isDark: ${louder.first} (${"%.2f".format(louder.second)}:1) не важче за " +
                        "${quieter.first} (${"%.2f".format(quieter.second)}:1) — звичайне місце " +
                        "перекрикує критичне",
                    louder.second > quieter.second,
                )
            }
        }
    }

    @Test
    fun `adjacent tiers stay apart when the colour is gone`() {
        for (isDark in listOf(true, false)) {
            for ((a, b) in tiers(isDark).zipWithNext()) {
                val withColour = contrast(a.second, b.second)
                val without = contrast(deuteranope(a.second), deuteranope(b.second))
                assertTrue(
                    "isDark=$isDark: ${a.first} і ${b.first} — ${"%.2f".format(withColour)}:1, " +
                        "тобто одна вага під червоним фільтром",
                    withColour >= 1.35,
                )
                assertTrue(
                    "isDark=$isDark: ${a.first} і ${b.first} під дейтеранопією — " +
                        "${"%.2f".format(without)}:1, для дальтоніка це один ярус",
                    without >= 1.30,
                )
            }
        }
    }

    /** Родину тону не переучують: вивчений знак лишається тим самим кольором. */
    @Test
    fun `the tier keeps the hue family it was learned by`() {
        for (isDark in listOf(true, false)) {
            val p = MapSymbolPalette.resolve(isDark)
            assertTrue("критичне перестало бути червоним", dominant(p.pinCritical) == 'r')
            assertTrue("опорне перестало бути теплим", dominant(p.pinSustain) == 'r')
            assertTrue(
                "опорне має підйом зелені ${amberLift(p.pinSustain)} — бурштин став червоним",
                amberLift(p.pinSustain) >= 32,
            )
            assertTrue(
                "критичне набрало зелені ${amberLift(p.pinCritical)} — червоне поїхало в бурштин",
                amberLift(p.pinCritical) <= 16,
            )
            assertTrue("звичайне перестало бути нейтральним", dominant(p.pinOrdinary) == 'b')
        }
    }

    /* ── Арифметика ──────────────────────────────────────────────────────── */

    /**
     * Що робить бурштин бурштином: зелень, піднята НАД синім. Червоне тримає
     * ці два канали врівень (#861B1B → 0), бурштин розводить (#90490C → 61).
     *
     * Було `red - green`, і воно цього не міряло взагалі: знебарвлене червоне
     * #8E2F2F дає 95 — між опорним 71 і критичним 107, тобто проходило як
     * «тепліше за критичне». Бурштин міг тихо померти другим червоним.
     */
    private fun amberLift(c: Int): Int = green(c) - blue(c)

    private fun dominant(c: Int): Char {
        val r = red(c)
        val g = green(c)
        val b = blue(c)
        return when (max(r, max(g, b))) {
            r -> 'r'
            g -> 'g'
            else -> 'b'
        }
    }

    /**
     * Viénot-Brettel-Mollon: як цей тон бачить дейтеранопік. Матриці —
     * стандартні, і прикладаються до ЛІНІЙНОГО каналу, не до sRGB.
     */
    private fun deuteranope(argb: Int): Int {
        val r = channel(red(argb))
        val g = channel(green(argb))
        val b = channel(blue(argb))
        val l = 17.8824 * r + 43.5161 * g + 4.11935 * b
        val s = 0.0299566 * r + 0.184309 * g + 1.46709 * b
        val m = 0.494207 * l + 1.24827 * s
        fun back(x: Double): Int {
            val v = x.coerceIn(0.0, 1.0)
            val srgb = if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055
            return Math.round(srgb * 255).toInt().coerceIn(0, 255)
        }
        return (back(0.080944 * l - 0.130504 * m + 0.116721 * s) shl 16) or
            (back(-0.0102485 * l + 0.0540194 * m - 0.113615 * s) shl 8) or
            back(-0.000365294 * l - 0.00412163 * m + 0.693513 * s)
    }

    private fun red(c: Int) = (c shr 16) and 0xFF

    private fun green(c: Int) = (c shr 8) and 0xFF

    private fun blue(c: Int) = c and 0xFF

    private fun composite(argb: Int, ground: Int): Int {
        val alpha = ((argb ushr 24) and 0xFF) / 255f
        fun mix(shift: Int): Int {
            val f = (argb shr shift) and 0xFF
            val b = (ground shr shift) and 0xFF
            return (f * alpha + b * (1 - alpha)).toInt()
        }
        return (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun channel(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Int): Double =
        0.2126 * channel(red(color)) + 0.7152 * channel(green(color)) + 0.0722 * channel(blue(color))

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun ratio(a: Int, b: Int) = "%.2f".format(contrast(a, b))

    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)
}
