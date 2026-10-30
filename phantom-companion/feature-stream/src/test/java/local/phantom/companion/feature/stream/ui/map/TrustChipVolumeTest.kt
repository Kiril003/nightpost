package local.phantom.companion.feature.stream.ui.map

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import local.phantom.companion.core.nav.CoastBadge
import local.phantom.companion.core.sensor.nav.LocationTrust
import local.phantom.companion.feature.stream.ui.map.atlas.TrustClass
import local.phantom.companion.feature.stream.ui.map.atlas.TrustVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element

/**
 * Скільки разів чип має право заговорити на повний голос.
 *
 * Годинник тут фальшивий і навмисно: справжня перевірка — не «чи компілюється
 * анімація», а чи витримує граматика знятий епізод. Він лежить у
 * scratchpad/drift-episode.log і має власне імʼя в [PARKED_FLAP]: телефон
 * нерухомо в приміщенні, still=true, spd=0.0, і вирок дихає TRUSTED↔DEGRADED
 * кожні ~6 секунд протягом хвилини з гаком.
 */
class TrustChipVolumeTest {

    // ── класи ──────────────────────────────────────────────────────────────

    @Test
    fun `коустинг говорить поверх вироку`() {
        assertEquals(TrustClass.COASTING, trustClassOf(LocationTrust.TRUSTED, CoastBadge.COASTING))
        assertEquals(TrustClass.REJOINING, trustClassOf(LocationTrust.JAMMED, CoastBadge.MERGING))
        assertEquals(TrustClass.BLIND, trustClassOf(null, CoastBadge.WAITING))
    }

    /** Відсутній движок і чисте небо — однаково нічого не сказати. */
    @Test
    fun `тиша має один клас на всі свої причини`() {
        assertEquals(TrustClass.CLEAR, trustClassOf(null, CoastBadge.LIVE))
        assertEquals(TrustClass.CLEAR, trustClassOf(LocationTrust.TRUSTED, CoastBadge.LIVE))
    }

    // ── каденція ───────────────────────────────────────────────────────────

    @Test
    fun `зміна класу оголошується один раз і згортається`() {
        val entered = TrustVoiceState().observe(TrustClass.JAMMED, 1_000)
        assertTrue(entered.announcing(1_000))
        assertTrue(entered.announcing(1_000 + ANNOUNCE_MS - 1))
        assertFalse("напис пережив власне вікно", entered.announcing(1_000 + ANNOUNCE_MS))
    }

    @Test
    fun `той самий клас не оголошується вдруге всередині стелі`() {
        var state = TrustVoiceState().observe(TrustClass.JAMMED, 0)
        state = state.observe(TrustClass.DEGRADED, 10_000)
        state = state.observe(TrustClass.JAMMED, 20_000)
        assertFalse("глушіння переоголосилось за 20 с", state.announcing(20_000))

        state = state.observe(TrustClass.DEGRADED, 45_000)
        state = state.observe(TrustClass.JAMMED, 50_000)
        assertTrue("стеля не відпустила через 50 с", state.announcing(50_000))
    }

    @Test
    fun `стеля рахується для кожного класу окремо`() {
        var state = TrustVoiceState().observe(TrustClass.JAMMED, 0)
        state = state.observe(TrustClass.SPOOFED, 5_000)
        assertTrue("підміна замовкла через чужу стелю", state.announcing(5_000))
    }

    /**
     * Повернення неба варте слова лише після справжнього епізоду. Інакше
     * дихання вироку дало б власний спам — «відновлено» кожні шість секунд.
     */
    @Test
    fun `тиша оголошується лише після важкого класу`() {
        val afterJam = TrustVoiceState().observe(TrustClass.JAMMED, 0).observe(TrustClass.CLEAR, 60_000)
        assertTrue(afterJam.announcing(60_000))

        val afterBreath = TrustVoiceState().observe(TrustClass.DEGRADED, 0).observe(TrustClass.CLEAR, 60_000)
        assertFalse("дихання вироку вигадало собі новину", afterBreath.announcing(60_000))
    }

    /** «Сигнал повернувся — вирівнююсь» уже сказане; друге речення підряд — спам. */
    @Test
    fun `після вирівнювання тиша мовчить`() {
        val state = TrustVoiceState()
            .observe(TrustClass.COASTING, 0)
            .observe(TrustClass.REJOINING, 40_000)
            .observe(TrustClass.CLEAR, 80_000)
        assertFalse(state.announcing(80_000))
    }

    @Test
    fun `час у класі рахується від входу в нього`() {
        val state = TrustVoiceState().observe(TrustClass.DEGRADED, 7_000)
        assertEquals(0L, state.heldMs(7_000))
        assertEquals(30_000L, state.heldMs(37_000))
    }

    // ── знятий епізод ──────────────────────────────────────────────────────

    /**
     * PhantomEW із 21:29:31 по 21:30:36, зсунутий у нуль. Ці числа не вигадані
     * і не округлені — це те, що робить справжнє залізо на нерухомому телефоні.
     */
    private val PARKED_FLAP = listOf(
        0L to LocationTrust.DEGRADED,
        5_124L to LocationTrust.TRUSTED,
        12_028L to LocationTrust.DEGRADED,
        14_043L to LocationTrust.TRUSTED,
        20_064L to LocationTrust.DEGRADED,
        23_112L to LocationTrust.TRUSTED,
        29_102L to LocationTrust.DEGRADED,
        32_715L to LocationTrust.TRUSTED,
        39_161L to LocationTrust.DEGRADED,
        41_536L to LocationTrust.TRUSTED,
        48_181L to LocationTrust.DEGRADED,
        49_915L to LocationTrust.TRUSTED,
        56_217L to LocationTrust.DEGRADED,
        58_354L to LocationTrust.TRUSTED,
        65_243L to LocationTrust.DEGRADED,
    )

    @Test
    fun `хвилина мигання вироку дає рівно одне оголошення`() {
        assertEquals(1, announcementsOver(PARKED_FLAP))
    }

    /** Справжня погіршення все ще новина — стелю має пробивати подія, а не шум. */
    @Test
    fun `глушіння посеред мигання оголошується попри стелю сусіда`() {
        assertEquals(2, announcementsOver(PARKED_FLAP + listOf(70_000L to LocationTrust.JAMMED)))
    }

    /**
     * Дві граматики поспіль: спершу заспокоєння мусить вистоятись
     * [CALM_HOLD_MS] (це вже було в дереві), і аж тоді те, що вистояло, має
     * право стати оголошенням. Тут вони перевіряються разом, бо саме на стику
     * і жив спам.
     */
    private fun announcementsOver(events: List<Pair<Long, LocationTrust>>): Int {
        var shown: LocationTrust? = LocationTrust.TRUSTED
        var pending: Pair<Long, LocationTrust?>? = null
        var state = TrustVoiceState(TrustClass.CLEAR, 0L)
        var spoken = 0

        fun settle(now: Long) {
            val due = pending ?: return
            if (due.first > now) return
            shown = due.second
            pending = null
            val before = state.announcedUntil
            state = state.observe(trustClassOf(shown, CoastBadge.LIVE), now)
            if (state.announcedUntil > before) spoken++
        }

        events.forEach { (at, verdict) ->
            settle(at)
            when {
                // Погіршення більше не миттєве: воно теж мусить протриматись
                // (ALARM_HOLD_MS), інакше блимання знову стає написом.
                verdict != shown -> pending = (at + steadyHoldMs(shown, verdict)) to verdict
                // Вирок повернувся до показаного — заспокоєння скасоване, і
                // саме тут мигання вмирає, ще до будь-яких оголошень.
                else -> pending = null
            }
        }
        settle(events.last().first + CALM_HOLD_MS)
        return spoken
    }

    // ── граматика форми ────────────────────────────────────────────────────

    @Test
    fun `значок мовчить, доки його не торкнулись`() {
        assertFalse(saysAnything(TrustVolume.ICON, explaining = false))
        assertTrue("шепіт без виходу — це кольорова цятка", saysAnything(TrustVolume.ICON, explaining = true))
        assertTrue(saysSentence(TrustVolume.ICON, explaining = true))
    }

    @Test
    fun `слово лишається словом, поки його не розгорнули`() {
        assertTrue(saysAnything(TrustVolume.WORDED, explaining = false))
        assertFalse(saysSentence(TrustVolume.WORDED, explaining = false))
        assertTrue(saysSentence(TrustVolume.EXPANDED, explaining = false))
    }

    // ── розмір самих слів ──────────────────────────────────────────────────

    /**
     * Стишена форма мусить бути коротшою за речення В ОБОХ локалях — інакше
     * «стишення» це перейменування, і власник побачить ту саму плашку.
     */
    @Test
    fun `коротка форма коротша за речення в обох мовах`() {
        listOf("values-uk", "values").forEach { locale ->
            val res = resources(locale)
            SHORTENED.forEach { (word, sentence) ->
                val short = res.getValue(word)
                val long = res.getValue(sentence)
                assertTrue(
                    "$locale/$word («$short») не коротше за «$long»",
                    short.length * 2 < long.length,
                )
            }
        }
    }

    @Test
    fun `два стани не діляться одним словом`() {
        listOf("values-uk", "values").forEach { locale ->
            val res = resources(locale)
            val words = SHORTENED.keys.map { res.getValue(it).lowercase() }
            assertEquals("$locale: слова станів злились", words.size, words.toSet().size)
        }
    }

    private val SHORTENED = mapOf(
        "map_trust_word_degraded" to "map_trust_degraded",
        "map_trust_word_jammed" to "map_trust_jammed",
        "map_trust_word_spoofed" to "map_trust_spoofed",
        "map_coast_word_coasting" to "map_coast_coasting",
        "map_coast_word_merging" to "map_coast_merging",
        "map_coast_word_waiting" to "map_coast_waiting",
    )

    private fun resources(locale: String): Map<String, String> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val root = dir ?: fail("repo root with feature-stream/ not found") as Nothing
        val file = File(root, "feature-stream/src/main/res/$locale/strings.xml")
        assertTrue("${file.path} is missing", file.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = LinkedHashMap<String, String>()
        val children = doc.documentElement.childNodes
        for (i in 0 until children.length) {
            val el = children.item(i) as? Element ?: continue
            val name = el.getAttribute("name").takeIf { it.isNotBlank() } ?: continue
            out[name] = el.textContent
        }
        return out
    }
}
