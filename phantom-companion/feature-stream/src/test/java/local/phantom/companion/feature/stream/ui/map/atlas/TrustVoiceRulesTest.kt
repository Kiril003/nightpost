package local.phantom.companion.feature.stream.ui.map.atlas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Вердикт власника, 23.08: «інфопанелі з підміною і GPS — ну ок, розумію що
 * щось є, але вони гігантські і постійно ними спамить, особливо коли нічого не
 * відбувається — це злить».
 *
 * Це таблиця, якою те роздратування виміряне. Кожен рядок нижче — момент, і
 * питання до нього одне: скільки уваги цей момент має право взяти.
 */
class TrustVoiceRulesTest {

    private val engine = LensEngine()

    private fun voice(
        trust: TrustClass,
        scene: SceneMode = SceneMode.CALM,
        motion: MotionClass = MotionClass.STILL,
        heldMs: Long = 0L,
        alarmLoud: Boolean = false,
        announcing: Boolean = false,
    ) = engine.voice(
        TrustVoiceInput(trust, scene, motion, heldMs, alarmLoud),
        announcing = announcing,
    )

    @Test
    fun `чисте небо не композить нічого`() {
        MotionClass.entries.forEach { motion ->
            SceneMode.entries.forEach { scene ->
                assertEquals(
                    "$scene/$motion заговорив про чисте небо",
                    TrustVolume.SILENT,
                    voice(TrustClass.CLEAR, scene = scene, motion = motion),
                )
            }
        }
    }

    /** Сцена власника: телефон лежить, ведення немає, стан не міняється. */
    @Test
    fun `на паркінгу слово стишується до значка`() {
        assertEquals(TrustVolume.WORDED, voice(TrustClass.JAMMED, heldMs = 0))
        assertEquals(
            TrustVolume.WORDED,
            voice(TrustClass.JAMMED, heldMs = LensRules.QUIET_DIM_MS - 1),
        )
        assertEquals(
            TrustVolume.ICON,
            voice(TrustClass.JAMMED, heldMs = 5 * LensRules.QUIET_DIM_MS),
        )
    }

    @Test
    fun `водієві слово лишається — і на ходу, і під веденням`() {
        val long = 60 * LensRules.QUIET_DIM_MS
        listOf(MotionClass.WALK, MotionClass.DRIVE, MotionClass.TRANSIT).forEach { motion ->
            assertEquals(
                "$motion втратив слово",
                TrustVolume.WORDED,
                voice(TrustClass.JAMMED, motion = motion, heldMs = long),
            )
        }
        assertEquals(
            TrustVolume.WORDED,
            voice(TrustClass.JAMMED, scene = SceneMode.GUIDE, motion = MotionClass.STILL, heldMs = long),
        )
    }

    /** Ціна пропущеної підміни за кермом — не роздратування. */
    @Test
    fun `підміна під час ведення лишається гучною завжди`() {
        assertEquals(
            TrustVolume.EXPANDED,
            voice(TrustClass.SPOOFED, scene = SceneMode.GUIDE, heldMs = 60 * LensRules.QUIET_DIM_MS),
        )
        // Навіть сирена не стишує її: тривога каже про небо, підміна — про те,
        // що сама мапа зараз бреше про землю під ногами.
        assertEquals(
            TrustVolume.EXPANDED,
            voice(TrustClass.SPOOFED, scene = SceneMode.GUIDE, alarmLoud = true),
        )
    }

    /** Власник 26.09: «Небо під сумнівом ±20 м» на мапі без маршруту лише лякало. */
    @Test
    fun `приблизне місце мовчить, доки не веде маршрут`() {
        listOf(TrustClass.DEGRADED, TrustClass.BLIND).forEach { cls ->
            listOf(SceneMode.CALM, SceneMode.DRIVE).forEach { scene ->
                MotionClass.entries.forEach { motion ->
                    assertEquals(TrustVolume.SILENT, voice(cls, scene = scene, motion = motion))
                    assertEquals(TrustVolume.SILENT, voice(cls, scene = scene, motion = motion, announcing = true))
                }
            }
        }
        assertEquals(TrustVolume.WORDED, voice(TrustClass.DEGRADED, scene = SceneMode.GUIDE))
    }

    @Test
    fun `підміна не шепоче навіть на паркінгу`() {
        assertEquals(
            TrustVolume.WORDED,
            voice(TrustClass.SPOOFED, heldMs = 60 * LensRules.QUIET_DIM_MS),
        )
    }

    @Test
    fun `оголошення розгортає напис, і рівно на час оголошення`() {
        assertEquals(TrustVolume.EXPANDED, voice(TrustClass.JAMMED, announcing = true))
        assertEquals(TrustVolume.WORDED, voice(TrustClass.JAMMED, announcing = false))
    }

    /** Бюджет хрому: розгорнутий напис на екрані рівно один. */
    @Test
    fun `поки говорить сирена, довіра лишається в короткій формі`() {
        assertNotEquals(
            TrustVolume.EXPANDED,
            voice(TrustClass.JAMMED, announcing = true, alarmLoud = true),
        )
        assertNotEquals(
            TrustVolume.EXPANDED,
            voice(TrustClass.COASTING, scene = SceneMode.GUIDE, announcing = true, alarmLoud = true),
        )
    }

    /** Повернення неба після епізоду має право на слово; тиша ж — стан. */
    @Test
    fun `оголошене повернення видно навіть у класі тиші`() {
        assertEquals(TrustVolume.EXPANDED, voice(TrustClass.CLEAR, announcing = true))
        assertEquals(TrustVolume.SILENT, voice(TrustClass.CLEAR, announcing = false))
    }

    /**
     * Стан без рядка зник би з екрана мовчки — це найгірший з можливих
     * наслідків, бо виглядає як «все добре».
     */
    @Test
    fun `кожен стан має голос`() {
        TrustClass.entries.forEach { cls ->
            SceneMode.entries.forEach { scene ->
                MotionClass.entries.forEach { motion ->
                    listOf(0L, LensRules.QUIET_DIM_MS, Long.MAX_VALUE / 2).forEach { held ->
                        voice(cls, scene = scene, motion = motion, heldMs = held)
                    }
                }
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `таблиця без замикаючого рядка не будується`() {
        LensRules(
            tierRules = MapTier.entries.map { TierRule(it) },
            chromeRules = ChromeSlot.entries.map { ChromeRule(it) },
            budgetRules = emptyList(),
            voiceRules = listOf(TrustVoiceRule(TrustVolume.SILENT, classes = setOf(TrustClass.CLEAR))),
        )
    }
}
