package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.sensor.nav.MockPosition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomAlpha
import local.phantom.companion.core.design.theme.PhantomBody
import local.phantom.companion.core.design.theme.PhantomCaption
import local.phantom.companion.core.design.theme.PhantomRadii
import local.phantom.companion.core.design.theme.PhantomSpacings
import local.phantom.companion.core.design.theme.PhantomTouchFloor
import local.phantom.companion.core.design.theme.onAccentInk
import local.phantom.companion.core.nav.CoastBadge
import local.phantom.companion.core.sensor.nav.LocationTrust
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.atlas.LensEngine
import local.phantom.companion.feature.stream.ui.map.atlas.LensRules
import local.phantom.companion.feature.stream.ui.map.atlas.MotionClass
import local.phantom.companion.feature.stream.ui.map.atlas.SceneMode
import local.phantom.companion.feature.stream.ui.map.atlas.TrustClass
import local.phantom.companion.feature.stream.ui.map.atlas.TrustVoiceInput
import local.phantom.companion.feature.stream.ui.map.atlas.TrustVolume
import local.phantom.companion.feature.stream.ui.map.nav.EwTrail

/**
 * What the sky is worth, said in every build.
 *
 * A store build used to say it by shifting the doubt circle from #38BDF8 to
 * #EF4444 and nothing else: the only worded warning that the coordinates were
 * being spoofed lived behind ContinuumHost.kt:698's debug gate, and the words
 * themselves existed only where the assistant could speak them
 * (MapAnalyzeTool.kt:102-105). A hue is not a warning. Someone reading red as
 * amber, or holding the phone in daylight, got no warning at all.
 *
 * So the claim is made three ways at once — word, drawn shape, colour — and
 * the shape is the same texture the halo draws on the ground around the fix
 * ([trustHaloDash]), so the chip and the ring are one sentence, not two.
 * Silence is the TRUSTED claim: a clear sky composes nothing, because chrome
 * that is always there is chrome nobody reads. [showConfirmed] is the opt-in
 * for the operator who wants the good news stated.
 *
 * Гучність — окреме питання від чесності, і його вирішує Лінза, не цей файл.
 * Власник дивився на дві повнорядкові плашки хвилинами, поки телефон лежав у
 * приміщенні: сказане було правдою, розмір — ні. Тому речення тепер має три
 * розміри ([TrustVolume]), і платить за увагу лише там, де щось міняється.
 */
@Composable
internal fun TrustChip(
    verdict: LocationTrust?,
    modifier: Modifier = Modifier,
    showConfirmed: Boolean = false,
    /**
     * ATLAS-2.5 — who owns the puck. While the coasting engine owns it the
     * jamming verdict is no longer the true sentence: «тримаю останню певну
     * позицію» describes free roam, and saying it during a drive that IS
     * advancing along the corridor would be the chip lying about the very
     * thing the operator is watching. So the coast speaks over the verdict —
     * and it speaks IMMEDIATELY, outside [rememberSteadyVerdict]'s calming
     * delay: which mode the drive is in is news the moment it changes.
     */
    coast: CoastBadge = CoastBadge.LIVE,
    scene: SceneMode = SceneMode.CALM,
    motion: MotionClass = MotionClass.UNKNOWN,
    /** Сирена вже говорить голосно; двох розгорнутих написів екран не має. */
    alarmLoud: Boolean = false,
) {
    val shown = rememberSteadyVerdict(verdict)
    val coasting = coast != CoastBadge.LIVE
    val cls = trustClassOf(shown, coast)
    // Голос рахується ДО будь-якого раннього виходу: інакше тиша знімала б
    // композицію разом із памʼяттю про вже сказане, і стеля переоголошення
    // обнулялась би щоразу, коли небо на мить прояснилось.
    val volume = rememberTrustVoice(cls, scene, motion, alarmLoud)

    // No verdict at all is not a verdict of TRUSTED — the EW engine simply is
    // not running, and inventing a chip for that would be the loudest lie here.
    if (shown == null && !coasting) return
    val form = if (volume == TrustVolume.SILENT && showConfirmed) TrustVolume.WORDED else volume
    if (form == TrustVolume.SILENT) return

    val theme = LocalPhantomTheme.current
    var explaining by remember(cls) { mutableStateOf(false) }
    val full = saysSentence(form, explaining)

    // A coast is a jamming-grade condition wearing a different sentence, so it
    // borrows the jamming tone rather than inventing a fifth visual grammar.
    val toned = if (coasting) LocationTrust.JAMMED else shown!!
    val tint = Color(android.graphics.Color.parseColor(EwTrail.colorFor(dead = false, trust = toned)))
    val spoofed = !coasting && shown == LocationTrust.SPOOFED
    // Коралова заливка на всю ширину — форма РЕЧЕННЯ, а не вироку. Стишена
    // підміна лишається кораловою по краю й по значку: колір не стишується.
    val alarm = spoofed && full
    val shape: Shape = if (alarm) PhantomRadii.MediumShape else PhantomRadii.Pill
    // On coral the ink flips — day coral is #B03718 and night coral is
    // #FF8B6E, and one ink cannot sit on both. onAccentInk picks by measured
    // contrast, not by a linear-luminance guess.
    val ink = if (alarm) theme.coral.onAccentInk() else theme.ink
    val sentence = stringResource(if (coasting) coastRes(coast) else verdictRes(shown!!))
    val word = stringResource(if (coasting) coastWordRes(coast) else verdictWordRes(shown!!))
    val explanation = stringResource(if (coasting) coastExplainRes(coast) else explainRes(shown!!))
    // Озвучення не стишується разом із написом: TalkBack завжди чує повне.
    val spokenName = "$sentence. $explanation"

    Column(
        modifier = modifier
            .then(if (alarm) Modifier.fillMaxWidth() else Modifier)
            .clip(shape)
            .background(if (alarm) theme.coral else theme.glassPanel.copy(alpha = MapChromeMetrics.PANEL_ALPHA))
            .then(
                when {
                    alarm -> Modifier
                    // Заглушений сигнал і стишена підміна — єдині краї, що
                    // йдуть тоном, а не товщиною: наголос кажемо кольором.
                    toned == LocationTrust.JAMMED || spoofed ->
                        Modifier.border(MapChromeMetrics.Hairline, tint, shape)
                    else -> Modifier.border(MapChromeMetrics.Hairline, theme.glassBorder, shape)
                },
            )
            .clickable(role = Role.Button) { explaining = !explaining }
            .semantics(mergeDescendants = true) { contentDescription = spokenName }
            .heightIn(min = PhantomTouchFloor)
            .padding(horizontal = PhantomSpacings.Base, vertical = PhantomSpacings.Small),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PhantomSpacings.Small),
        ) {
            TrustGlyph(trust = toned, tint = if (alarm) ink else tint)
            if (saysAnything(form, explaining)) {
                PhantomBody(
                    text = if (full) sentence else word,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        if (explaining) {
            PhantomCaption(
                text = explanation,
                color = ink.copy(alpha = PhantomAlpha.Emphasis),
                modifier = Modifier.padding(top = PhantomSpacings.Micro),
            )
        }
    }
}

/**
 * Граматика форми, двома рядками.
 *
 * Шепіт лишається зрозумілим лише поки з нього є вихід: дотик по значку дає
 * повне речення, інакше стишений чип — це кольорова цятка без пояснення.
 */
internal fun saysAnything(form: TrustVolume, explaining: Boolean): Boolean =
    form != TrustVolume.ICON || explaining

internal fun saysSentence(form: TrustVolume, explaining: Boolean): Boolean =
    explaining || form == TrustVolume.EXPANDED

/**
 * Один вирок і один режим коустингу — одне речення, отже один клас.
 *
 * Клас, а не вирок, вирішує, коли чип має право заговорити на повний голос:
 * два вироки з однаковим текстом — це одна новина, скільки б разів движок не
 * перемкнувся між ними.
 */
internal fun trustClassOf(verdict: LocationTrust?, coast: CoastBadge): TrustClass = when (coast) {
    CoastBadge.COASTING -> TrustClass.COASTING
    CoastBadge.MERGING -> TrustClass.REJOINING
    CoastBadge.WAITING -> TrustClass.BLIND
    CoastBadge.LIVE -> when (verdict) {
        null, LocationTrust.TRUSTED -> TrustClass.CLEAR
        // «Небо ще не відповіло» — це відсутність позиції, а не поганий сигнал
        // і не напад. Тому родина BLIND, а не DEGRADED і не JAMMED.
        LocationTrust.SEARCHING -> TrustClass.BLIND
        LocationTrust.DEGRADED -> TrustClass.DEGRADED
        LocationTrust.JAMMED -> TrustClass.JAMMED
        LocationTrust.SPOOFED -> TrustClass.SPOOFED
    }
}

/** Скільки живе розгорнутий напис, перш ніж згорнутись до слова. */
internal const val ANNOUNCE_MS = 4_000L

/** Скільки той самий клас не має права оголошувати себе вдруге. */
internal const val REANNOUNCE_FLOOR_MS = 30_000L

/**
 * Памʼять про вже сказане.
 *
 * Тут живе єдина причина, чому мигання вироку не стає миготінням напису:
 * вікно оголошення відкриває тільки зміна КЛАСУ, і кожен клас має власну
 * стелю в [REANNOUNCE_FLOOR_MS]. Той самий урок, що камера вже вивчила з
 * зумом — стан, який дихає, не має права смикати екран.
 */
internal data class TrustVoiceState(
    val cls: TrustClass = TrustClass.CLEAR,
    val since: Long = 0L,
    val announcedUntil: Long = 0L,
    val lastAnnounce: Map<TrustClass, Long> = emptyMap(),
) {
    fun announcing(now: Long): Boolean = now < announcedUntil

    fun heldMs(now: Long): Long = now - since

    fun observe(next: TrustClass, now: Long): TrustVoiceState {
        if (next == cls) return this
        val last = lastAnnounce[next]
        val floorClear = last == null || now - last >= REANNOUNCE_FLOOR_MS
        // Повернення неба варте слова лише після справжнього епізоду. Шлях
        // DEGRADED→CLEAR — це і є дихання вироку, і його оголошувати нічим.
        val worthSaying = next != TrustClass.CLEAR || cls in TrustClass.HARD
        val fire = worthSaying && floorClear
        return TrustVoiceState(
            cls = next,
            since = now,
            announcedUntil = if (fire) now + ANNOUNCE_MS else 0L,
            lastAnnounce = if (fire) lastAnnounce + (next to now) else lastAnnounce,
        )
    }
}

/**
 * Дві затримки на зміну класу — і жодного кадрового циклу: напис розгортається,
 * згортається, стишується, після чого композиція не прокидається взагалі.
 * Перша поява чипа не оголошення: те, що мапу відкрили, не новина про небо.
 */
@Composable
private fun rememberTrustVoice(
    cls: TrustClass,
    scene: SceneMode,
    motion: MotionClass,
    alarmLoud: Boolean,
): TrustVolume {
    val lens = remember { LensEngine() }
    var state by remember { mutableStateOf(TrustVoiceState(cls, android.os.SystemClock.elapsedRealtime())) }
    var stamp by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }

    LaunchedEffect(cls) {
        state = state.observe(cls, android.os.SystemClock.elapsedRealtime())
        stamp = android.os.SystemClock.elapsedRealtime()
        if (state.announcing(stamp)) {
            delay(ANNOUNCE_MS)
            stamp = android.os.SystemClock.elapsedRealtime()
        }
        val quiet = LensRules.QUIET_DIM_MS - state.heldMs(android.os.SystemClock.elapsedRealtime())
        if (quiet > 0) delay(quiet)
        stamp = android.os.SystemClock.elapsedRealtime()
    }

    return lens.voice(
        TrustVoiceInput(
            trust = cls,
            scene = scene,
            motion = motion,
            heldMs = state.heldMs(stamp),
            alarmLoud = alarmLoud,
        ),
        announcing = state.announcing(stamp),
    )
}

/**
 * How bad the sky is, for ordering only. Not a public vocabulary — it exists
 * so [rememberSteadyVerdict] can tell "it got worse" from "it got better".
 */
internal fun trustSeverity(trust: LocationTrust?): Int = when (trust) {
    null -> 0
    LocationTrust.TRUSTED -> 0
    // Нуль навмисно: пошук неба НЕ є погіршенням, тож він ніколи не вмикає
    // граматику тривоги — інакше ми повернули б ту саму смугу іншим шляхом.
    LocationTrust.SEARCHING -> 0
    LocationTrust.DEGRADED -> 1
    LocationTrust.JAMMED -> 2
    LocationTrust.SPOOFED -> 3
}

/** A worsening sky is news now; a calming one has to prove it for [CALM_HOLD_MS]. */
internal fun verdictWorsens(shown: LocationTrust?, incoming: LocationTrust?): Boolean =
    trustSeverity(incoming) > trustSeverity(shown)

internal const val CALM_HOLD_MS = 12_000L

/**
 * Скільки вирок мусить протриматись, ПЕРШ НІЖ стати словом на екрані.
 *
 * Досі погіршення ставало словом миттєво — і саме це власник назвав спамом:
 * «хай не спамить цією смугою, вона багато місця займає». Заміряно на його ж
 * записі (`_grant/records/reb-20260804`, програвач `scripts/replay-blackbox.py`):
 * **57 епізодів недовіри, 34 з них коротші за 4 секунди**, і всі ці 34 разом
 * важать **85 секунд із 3318** — 2,6% часу недовіри. Тобто шість написів із
 * десяти спалахували на те, чого людина не встигає прочитати.
 *
 * Поріг у 4 секунди прибирає 34 появи з 57 і коштує 85 секунд невисловленої
 * недовіри — у спалахах, які однаково нечитні. Колір при цьому не чекає:
 * гало й слід відповідають вироку миттєво, як і сказано вище. Чекає СЛОВО.
 */
internal const val ALARM_HOLD_MS = 4_000L

/** Витримка для переходу [shown] → [incoming]. Погіршення швидше за
 *  поліпшення, але не миттєве: миттєвість і була спамом. */
internal fun steadyHoldMs(shown: LocationTrust?, incoming: LocationTrust?): Long =
    if (verdictWorsens(shown, incoming)) ALARM_HOLD_MS else CALM_HOLD_MS

/**
 * The word must be slower than the verdict.
 *
 * LocationTrust flaps second-to-second on single-frequency hardware — that is
 * measured, and it is the same flapping that made the follow camera breathe
 * and twitch until CameraDirector.kt:296-311 stopped gating on it. The halo
 * and the trail still answer the verdict instantly; they are colour, and
 * colour changing is not a sentence changing. A WORD that appears, clears and
 * reappears teaches the operator to stop reading it, which costs him the one
 * warning this product has that no consumer navigator can give at all.
 *
 * So: worse shows at once — a spoof is never delayed — and anything calmer
 * has to hold for [CALM_HOLD_MS] before the word softens. The delay is
 * cancelled by the next change, so a verdict that flickers back to bad never
 * clears the chip at all.
 */
@Composable
private fun rememberSteadyVerdict(verdict: LocationTrust?): LocationTrust? {
    var shown by remember { mutableStateOf(verdict) }
    LaunchedEffect(verdict) {
        // Скасування цього ефекту новим вироком — і є ліками: спалах на три
        // секунди просто не доживає до слова.
        if (verdict != shown) {
            delay(steadyHoldMs(shown, verdict))
            shown = verdict
        }
    }
    return shown
}

private fun verdictRes(trust: LocationTrust): Int = when (trust) {
    LocationTrust.TRUSTED -> R.string.map_trust_confirmed
    LocationTrust.SEARCHING -> R.string.map_trust_searching
    LocationTrust.DEGRADED -> R.string.map_trust_degraded
    LocationTrust.JAMMED -> R.string.map_trust_jammed
    LocationTrust.SPOOFED -> R.string.map_trust_spoofed
}

/**
 * Та сама правда в один-два токени. Тексти не переписані — з них узято перше,
 * найкоротше твердження; повне речення нікуди не зникло, воно живе в
 * розгорнутій формі, в дотику й в озвученні.
 */
private fun verdictWordRes(trust: LocationTrust): Int = when (trust) {
    LocationTrust.TRUSTED -> R.string.map_trust_word_trusted
    LocationTrust.SEARCHING -> R.string.map_trust_word_searching
    LocationTrust.DEGRADED -> R.string.map_trust_word_degraded
    LocationTrust.JAMMED -> R.string.map_trust_word_jammed
    // Підміну міг увімкнути сам оператор — тоді це не напад, а його ж вибір,
    // і смуга мусить сказати саме це. Інакше застосунок звинувачує невідомо
    // кого у тому, що зробила людина, і вона шукає ворога замість перемикача.
    LocationTrust.SPOOFED ->
        if (MockPosition.isShowingFalsePosition()) R.string.map_trust_word_own_false
        else R.string.map_trust_word_spoofed
}

internal fun coastWordRes(badge: CoastBadge): Int = when (badge) {
    CoastBadge.COASTING, CoastBadge.LIVE -> R.string.map_coast_word_coasting
    CoastBadge.MERGING -> R.string.map_coast_word_merging
    CoastBadge.WAITING -> R.string.map_coast_word_waiting
}

/** ATLAS-2.5 §12 — the coast's own sentences. [CoastBadge.LIVE] never gets here. */
internal fun coastRes(badge: CoastBadge): Int = when (badge) {
    CoastBadge.COASTING, CoastBadge.LIVE -> R.string.map_coast_coasting
    CoastBadge.MERGING -> R.string.map_coast_merging
    CoastBadge.WAITING -> R.string.map_coast_waiting
}

internal fun coastExplainRes(badge: CoastBadge): Int = when (badge) {
    CoastBadge.COASTING, CoastBadge.LIVE -> R.string.map_coast_explain_coasting
    CoastBadge.MERGING -> R.string.map_coast_explain_merging
    CoastBadge.WAITING -> R.string.map_coast_explain_waiting
}

private fun explainRes(trust: LocationTrust): Int = when (trust) {
    LocationTrust.TRUSTED -> R.string.map_trust_explain_trusted
    LocationTrust.SEARCHING -> R.string.map_trust_explain_searching
    LocationTrust.DEGRADED -> R.string.map_trust_explain_degraded
    LocationTrust.JAMMED -> R.string.map_trust_explain_jammed
    LocationTrust.SPOOFED -> R.string.map_trust_explain_spoofed
}

/** The tick weight of the language — twice the hairline, and never its own number. */
private val GlyphStroke: Dp = MapChromeMetrics.Hairline * 2

/**
 * The shape half of the claim. Ring textures come from [trustHaloDash] in
 * MapLibre's own unit — multiples of the stroke width — so the glyph and the
 * ring drawn around the operator on the ground cannot drift apart.
 */
@Composable
private fun TrustGlyph(trust: LocationTrust, tint: Color, modifier: Modifier = Modifier) {
    val dash = remember(trust) { trustHaloDash(EwTrail.colorFor(dead = false, trust = trust)) }
    Canvas(modifier.size(MapChromeMetrics.Glyph)) {
        val stroke = GlyphStroke.toPx()
        if (trust == LocationTrust.SPOOFED) {
            val chevron = Path().apply {
                moveTo(size.width * 0.22f, size.height * 0.64f)
                lineTo(size.width * 0.50f, size.height * 0.34f)
                lineTo(size.width * 0.78f, size.height * 0.64f)
            }
            drawPath(
                chevron,
                tint,
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            drawLine(
                tint,
                Offset(size.width * 0.14f, size.height * 0.86f),
                Offset(size.width * 0.86f, size.height * 0.14f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            return@Canvas
        }
        drawCircle(
            color = tint,
            radius = (size.minDimension - stroke) / 2f,
            style = Stroke(
                width = stroke,
                cap = StrokeCap.Round,
                pathEffect = dash?.let {
                    PathEffect.dashPathEffect(floatArrayOf(it[0] * stroke, it[1] * stroke))
                },
            ),
        )
    }
}
