package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.feature.stream.R

/**
 * The four-state confidence vocabulary the map speaks about its own ground
 * truth (map-realism.md / map-register-schema.md): how a feature's shape
 * came to be known. [severity] orders them worst-wins for route
 * degradation — REMEMBERED is not "less confident than GUESSED", it means
 * the source itself may be frozen or wrong (occupation, staleness), which
 * downgrade() rules treat as strictly worse than an honest guess.
 *
 * This enum does NOT compute REMEMBERED anywhere in feature-stream today —
 * that needs occupation status_flags, which live in the tile manifest
 * schema, not in any per-feature tag this module reads. It exists here so
 * the vocabulary is complete and the day that signal lands nothing has to
 * be renamed.
 */
enum class GroundRegister(val severity: Int) {
    MEASURED(0),
    DERIVED(1),
    GUESSED(2),
    REMEMBERED(3),
}

/** worst-register-wins, per map-realism.md's downgrade rule. */
fun GroundRegister.worstWith(other: GroundRegister): GroundRegister =
    if (severity >= other.severity) this else other

fun Iterable<GroundRegister>.worstOrNull(): GroundRegister? =
    fold(null as GroundRegister?) { acc, r -> acc?.worstWith(r) ?: r }

/**
 * Regions whose presence in the data no longer testifies to today. Mariupol
 * is the case this exists for: 96.9% storey coverage, 97.1% roof shapes,
 * median edit year 2020, only 2.9% touched since the war reached it in 2022 —
 * a presence-only register reads it as richly DERIVED and draws a city that
 * hasn't existed since spring 2022 (map-oblast-census.md).
 *
 * ATLAS-3: the names are the country pack's table, not this file's literal —
 * they have to match the pack's own region polygons exactly, so only the pack
 * can keep both sides honest. Read from every INSTALLED pack rather than the
 * active one: occupied ground stays occupied whatever country the operator
 * is standing in.
 */
val OCCUPIED_OBLASTS: Set<String>
    get() = local.phantom.companion.core.data.country.CountryPackRegistry.rememberedRegions()

/** REMEMBERED overrides any tag-presence reading once ground is known-occupied — undercount, never overclaim. */
fun GroundRegister.downgradeIfOccupied(oblastName: String?): GroundRegister =
    if (oblastName in OCCUPIED_OBLASTS) GroundRegister.REMEMBERED else this

/**
 * How a register-bearing edge is DRAWN, apart from its colour. Colour alone
 * carries nothing to an operator with a red filter over the screen, a
 * colour-blind crew member, or low sun on the glass — the texture has to say
 * it too, and a legend nobody reads is not an answer.
 */
enum class RegisterEdge { SOLID, LONG_DASH, SHORT_DASH, DOTTED }

/**
 * The one place a register turns into a colour. Light REMEMBERED is #64748B
 * and not the #475569 the tint table used to reach for: #475569 measures
 * 2.39:1 against the #14161B route casing it is drawn beside, i.e. gone in
 * daylight (MapSymbolContrastTest holds the floor).
 */
fun GroundRegister.coreColor(isDark: Boolean): Int = when (this) {
    GroundRegister.MEASURED -> if (isDark) 0xFF22C55E.toInt() else 0xFF15803D.toInt()
    GroundRegister.DERIVED -> if (isDark) 0xFF3FD9C4.toInt() else 0xFF0F766E.toInt()
    GroundRegister.GUESSED -> if (isDark) 0xFFF59E0B.toInt() else 0xFFB45309.toInt()
    GroundRegister.REMEMBERED -> if (isDark) 0xFF94A3B8.toInt() else 0xFF64748B.toInt()
}

fun GroundRegister.edge(): RegisterEdge = when (this) {
    GroundRegister.MEASURED -> RegisterEdge.SOLID
    GroundRegister.DERIVED -> RegisterEdge.LONG_DASH
    GroundRegister.GUESSED -> RegisterEdge.SHORT_DASH
    GroundRegister.REMEMBERED -> RegisterEdge.DOTTED
}

/** null = draw it unbroken; MapLibre's dash units are multiples of line width. */
fun RegisterEdge.dashArray(): FloatArray? = when (this) {
    RegisterEdge.SOLID -> null
    RegisterEdge.LONG_DASH -> floatArrayOf(16f, 4f)
    RegisterEdge.SHORT_DASH -> floatArrayOf(8f, 6f)
    RegisterEdge.DOTTED -> floatArrayOf(2f, 6f)
}

/**
 * Людина не бачить слів регістру. Лише давня земля міняє рішення — решта
 * мовчить: ~85 % країни GUESSED, і підпис на кожному маршруті став би шумом.
 */
fun GroundRegister.staleNoteRes(): Int? =
    if (this == GroundRegister.REMEMBERED) R.string.nav_route_stale else null

/**
 * What fraction of an observed set of features (a viewport, a route
 * corridor) falls in each register. Fractions sum to ~1f when [total] > 0;
 * [EMPTY] (all zero, total 0) means "nothing observed", not "everything
 * measured" — callers must treat it as absence, never as a good score.
 */
data class RegisterComposition(
    val measured: Float,
    val derived: Float,
    val guessed: Float,
    val remembered: Float,
    val total: Int,
) {
    companion object {
        val EMPTY = RegisterComposition(0f, 0f, 0f, 0f, 0)

        fun of(registers: Collection<GroundRegister>): RegisterComposition {
            if (registers.isEmpty()) return EMPTY
            var measured = 0
            var derived = 0
            var guessed = 0
            var remembered = 0
            for (r in registers) when (r) {
                GroundRegister.MEASURED -> measured++
                GroundRegister.DERIVED -> derived++
                GroundRegister.GUESSED -> guessed++
                GroundRegister.REMEMBERED -> remembered++
            }
            val total = registers.size.toFloat()
            return RegisterComposition(
                measured = measured / total,
                derived = derived / total,
                guessed = guessed / total,
                remembered = remembered / total,
                total = registers.size,
            )
        }
    }
}
