package local.phantom.companion.core.truth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class IntakeTest {

    private val place = Place(HERE, "osm:node/42")

    private val forbiddenKinds = listOf(
        "military.position", "military.movement", "air_defense", "air-defense.active", "strike.site",
        "explosion", "checkpoint", "troops", "vehicle.movement", "drone.sighting", "missile", "ппо", "приліт",
        "позиції_військ", "shelling.area", "artillery", "tcc.patrol",
    )

    private val forbiddenPhrases = listOf(
        "біля школи працює ППО", "приліт за магазином", "тут стоїть танк", "колона бтр іде на схід",
        "військові в дворі", "позиції за парком", "уламки ракети на дорозі", "прильот в соседний дом",
        "здесь пво", "ппо", "П П О працює", "п.п.о", "air defense near the mall", "troops on the bridge",
        "shahed hit the depot", "вибух на заводі", "блокпост на виїзді", "тцк на зупинці", "бпла над районом",
    )

    private fun disguise(s: String, rnd: Random): String {
        var out = if (rnd.nextBoolean()) s.uppercase() else s
        if (rnd.nextBoolean()) out = out.map { if (it == 'о' && rnd.nextBoolean()) 'o' else it }.joinToString("")
        if (rnd.nextBoolean()) out = out.map { if (it == 'р' && rnd.nextBoolean()) 'p' else it }.joinToString("")
        if (rnd.nextBoolean()) out = out.map { if (it == 'а' && rnd.nextBoolean()) 'a' else it }.joinToString("")
        val pad = listOf("", " ", "!!", "… ", "\n", "  ").random(rnd)
        return pad + out + pad
    }

    @Test
    fun `a forbidden category never passes, however it is dressed`() {
        val rnd = Random(9)
        repeat(2000) {
            val kind = disguise(forbiddenKinds.random(rnd), rnd)
            val r = IntakeGate.check(Submission(kind, "open", place))
            assertEquals(kind, Intake.Refused(Refusal.FORBIDDEN_CATEGORY), r)
        }
        repeat(2000) {
            val note = disguise(forbiddenPhrases.random(rnd), rnd)
            val kind = FactKind.entries.random(rnd)
            val r = IntakeGate.check(Submission(kind.wire, kind.readings.first().wire, place, note))
            assertTrue(note, r is Intake.Refused)
        }
    }

    @Test
    fun `only the closed vocabulary is accepted`() {
        val rnd = Random(11)
        val parts = FactKind.entries.flatMap { it.wire.split('.') } + listOf("x", ".", "_", "military", "shop")
        repeat(3000) {
            val wire = (1..rnd.nextInt(1, 4)).joinToString("") { parts.random(rnd) }
            val r = IntakeGate.check(Submission(wire, "working", place))
            if (r is Intake.Accepted) assertTrue(wire, FactKind.byWire(wire.trim().lowercase()) != null)
        }
    }

    @Test
    fun `every real kind and state passes, and the note only goes to moderation`() {
        for (kind in FactKind.entries) for (reading in kind.readings) {
            val r = IntakeGate.check(Submission(kind.wire, reading.wire, place))
            assertEquals(Intake.Accepted(kind, reading, place, null), r)
        }
        val innocent = listOf(
            "всупереч графіку відкрили раніше", "магазин Зеніт працює до 20:00", "позавчора теж було зачинено",
            "вхід з двору, ключ у консьєржа", "генератор гуде, але світло є", "черга до каси",
        )
        for (note in innocent) {
            val r = IntakeGate.check(Submission("shop.working", "working", place, note))
            assertEquals(note, note, (r as Intake.Accepted).noteForModeration)
        }
    }

    @Test
    fun `malformed submissions say why`() {
        assertEquals(Intake.Refused(Refusal.UNKNOWN_KIND), IntakeGate.check(Submission("cafe.vibe", "good", place)))
        assertEquals(Intake.Refused(Refusal.BAD_READING), IntakeGate.check(Submission("shelter.open", "on", place)))
        assertEquals(Intake.Refused(Refusal.NO_PLACE), IntakeGate.check(Submission("shelter.open", "open", null)))
        assertEquals(
            Intake.Refused(Refusal.NOTE_TOO_LONG),
            IntakeGate.check(Submission("shelter.open", "open", place, "а".repeat(IntakeGate.NOTE_MAX + 1))),
        )
    }

    @Test
    fun `light that changed during an alert is published an hour after the all-clear`() {
        val k = key(FactKind.POWER)
        val a = elder("a")
        val during = saw(a, k, Reading.OFF, NOW + 10 * MIN)
        val alert = AlertWindow(NOW, NOW + 40 * MIN)
        assertEquals(NOW + 100 * MIN, Embargo.releaseAtMs(during, listOf(alert)))
        assertTrue(Embargo.publishable(listOf(during), listOf(alert), NOW + 99 * MIN).isEmpty())
        assertEquals(1, Embargo.publishable(listOf(during), listOf(alert), NOW + 100 * MIN).size)
        assertEquals(Long.MAX_VALUE, Embargo.releaseAtMs(during, listOf(AlertWindow(NOW, null))))
        val shelter = saw(a, key(FactKind.SHELTER_OPEN), Reading.OPEN, NOW + 10 * MIN)
        assertEquals(shelter.atMs, Embargo.releaseAtMs(shelter, listOf(alert)))
    }
}
