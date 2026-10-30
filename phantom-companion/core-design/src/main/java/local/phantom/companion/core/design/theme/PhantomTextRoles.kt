package local.phantom.companion.core.design.theme

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import local.phantom.companion.core.design.tokens.LocalPhantomColors

/**
 * LUX-1 — the four text roles the app actually speaks in. Before this,
 * every screen hand-rolled its own kicker (four drifted variants: 8sp/1.5,
 * 9sp/2.0, 10sp/1.8, 11sp/2.2) and its own caption. One role = one voice.
 *
 *  - [PhantomKicker]  — 13sp заголовок групи звичайним регістром («Що тут зараз»)
 *  - [PhantomCaption] — 11sp quiet secondary line under content
 *  - [PhantomDataText] — mono numerals/data with tabular figures («14:32», «87 МБ»)
 *  - [PhantomProse]   — 16sp/24 Inter reading body (chat, long text)
 *
 * Colors default from the v1.2 tokens ([LocalPhantomColors]: text / text2);
 * pass `color` only when the surface demands it (e.g. on an accent fill).
 *
 * Every size here goes through [scaled]. It did not used to, and the operator's
 * text-size dial in Settings — which moves MaterialTheme's roles through
 * `phantomTypography` — moved none of these four.
 */

/**
 * The operator's dial, applied. Tracking is optical and stays unscaled.
 *
 * Identity. The dial now lives in the density every `sp` resolves against
 * (see PhantomTheme), so multiplying here as well would scale these roles
 * twice while the rest of the app scaled once. Not `@Composable`: it reads
 * nothing from the composition, so a style built outside one (the crown's
 * [editorialTitleStyle]) goes through the same door.
 */
private fun scaled(size: TextUnit): TextUnit = size

/**
 * Заголовок групи — звичайним регістром, без розрідження. Розріджений моно-капс
 * («Щ О  Т У Т») власник назвав ШІшним штампом (26.09); група тепер говорить
 * так само, як решта екрана, лише вагою.
 */
@Composable
fun PhantomKicker(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val c = LocalPhantomColors.current
    // Рядки писались під старий капс малими («нещодавнє»); тепер речення з великої.
    // Одиниця виміру не речення: «км/год» лишається малими.
    Text(
        text = if (UNIT_HEAD.containsMatchIn(text)) text else text.replaceFirstChar { it.uppercase() },
        color = if (color == Color.Unspecified) c.text2 else color,
        fontFamily = PhantomSans,
        fontSize = scaled(13.sp),
        fontWeight = FontWeight.SemiBold,
        lineHeight = scaled(18.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

private val UNIT_HEAD = Regex("""^(км|м|см|мм|хв|год|с|мс|МБ|ГБ|кБ|km|m|min|h|s|mph|kB|MB|GB)(/\p{L}+)?(\s|$|\.|·)""")

/*
 * 30.08.2026 — чому ролі дістали `fontWeight`.
 *
 * Дві незалежні хвилі заміни ручних розмірів на ролі вперлись в одну стіну:
 * заголовок діалогу «17sp Bold» не мав куди піти. PhantomTitle жорстко
 * SemiBold, тож заміна МОВЧКИ зʼїдала б Bold — а мовчазна зміна ваги гірша
 * за сирий розмір, бо її ніхто не помітить у дифі. Через це 49 місць
 * лишились сирими, і обидва рази агент назвав причиною саме брак ваги.
 *
 * Параметр необовʼязковий і за замовчуванням несе те, що роль несла досі, —
 * жоден наявний виклик не змінюється. Роль лишається рішенням про РОЗМІР і
 * ГАРНІТУРУ; вага стає тим, що екран має право сказати про наголос.
 */
/** Quiet 11sp secondary caption — hints, footers, empty-state lines. */
@Composable
fun PhantomCaption(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    fontWeight: FontWeight = FontWeight.Normal,
) {
    val c = LocalPhantomColors.current
    Text(
        text = text,
        color = if (color == Color.Unspecified) c.text2 else color,
        fontFamily = PhantomSans,
        fontSize = scaled(11.sp),
        fontWeight = fontWeight,
        lineHeight = scaled(15.sp),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * A pictograph standing in for an icon — the Spaces' hand-picked emoji.
 *
 * A role rather than a raw `fontSize`, because a glyph is set at the same
 * 17dp optical size as the Material icons it sits beside; when that size
 * moves, it must move for both, in one place.
 */
@Composable
fun PhantomGlyph(
    glyph: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    // NOT scaled: this is set to the optical size of the Material icons it
    // sits beside, and those do not move with the dial.
    Text(
        text = glyph,
        fontSize = 17.sp,
        maxLines = 1,
        modifier = if (contentDescription != null) {
            modifier.semantics { this.contentDescription = contentDescription }
        } else {
            modifier
        },
    )
}

/**
 * Mono data readout with tabular numerals — timestamps, counters, metrics.
 * `tnum` keeps digits fixed-width so live values don't jitter the layout.
 *
 * 26.09: моно знято й звідси — поруч із пропорційним текстом воно читалось як
 * налагоджувальний друк. Рівність цифр тримає `tnum` Manrope, не гарнітура.
 * Роль і далі для САМОСТІЙНОГО ЗНАЧЕННЯ: час, відстань, код, розмір паку.
 * Рядок зі слів або слів упереміш із числом («кавʼярня · Хрещатик, 22 · 340 м»)
 * — це [PhantomCaption]: моно поруч із пропорційним заголовком читається як
 * налагоджувальний друк, а не як дані.
 */
@Composable
fun PhantomDataText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = 12.sp,
    fontWeight: FontWeight = FontWeight.Normal,
    /** A pasted invite code is data too, and it does not fit on one line. */
    maxLines: Int = 1,
    /**
     * Обрізання БЕЗ трикрапки не відрізняється від тексту, який просто такий
     * і є: «Австрія — місця й» читається як повна назва паку, і оператор не
     * має звідки знати, що рядок продовжується. Стандарт ролі — крапки.
     */
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    val c = LocalPhantomColors.current
    Text(
        text = text,
        color = if (color == Color.Unspecified) c.text else color,
        fontFamily = PhantomSans,
        fontSize = scaled(fontSize),
        fontWeight = fontWeight,
        style = androidx.compose.ui.text.TextStyle(
            fontFeatureSettings = "tnum",
        ),
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier,
    )
}

/**
 * 13sp/18 Inter — the workhorse. The four original roles left a hole between
 * the 11sp caption and the 16sp prose, and that hole is exactly where most UI
 * text lives: a row label, a card line, a sheet's body. 300+ call sites
 * hand-rolled 12/13/14sp to fill it, which is how a type scale becomes a zoo.
 *
 * Prose is for reading AT LENGTH; this is for reading AT A GLANCE.
 */
@Composable
fun PhantomBody(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight = FontWeight.Normal,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null,
) {
    val c = LocalPhantomColors.current
    Text(
        text = text,
        color = if (color == Color.Unspecified) c.text else color,
        fontFamily = PhantomSans,
        fontSize = scaled(13.sp),
        fontWeight = fontWeight,
        lineHeight = scaled(18.sp),
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier,
    )
}

/**
 * 20sp/26 Inter — the name of a SHEET: a place, «Точка на мапі», a saved pin.
 *
 * [PhantomDisplay] used to do this job, and 26sp is a shop window: at the
 * operator's own font scale (1.15) a long Ukrainian name wrapped to two 32sp
 * lines and the peek of the sheet was mostly its own title. A sheet title is
 * read at arm's length, once — it needs to outrank the body and nothing more.
 * Display stays what it is: a number or a code checked from across a room.
 */
@Composable
fun PhantomTitle(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = 2,
    fontWeight: FontWeight = FontWeight.SemiBold,
) {
    val c = LocalPhantomColors.current
    Text(
        text = text,
        color = if (color == Color.Unspecified) c.text else color,
        fontFamily = PhantomSans,
        fontSize = scaled(20.sp),
        fontWeight = fontWeight,
        lineHeight = scaled(26.sp),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * 26sp/32 Inter — the one thing on a screen meant to be read from a distance
 * or out loud: a verification code, a monogram, a single number that IS the
 * screen. Above prose because it is not text to read, it is text to check.
 */
@Composable
fun PhantomDisplay(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
    fontWeight: FontWeight = FontWeight.SemiBold,
) {
    val c = LocalPhantomColors.current
    Text(
        text = text,
        color = if (color == Color.Unspecified) c.text else color,
        fontFamily = PhantomSans,
        fontSize = scaled(26.sp),
        fontWeight = fontWeight,
        lineHeight = scaled(32.sp),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** 16sp/24 Inter reading body — the assistant's spoken words, long prose. */
@Composable
fun PhantomProse(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    /** A name or a pasted code has to be clamped somewhere; better here,
     *  once, than by every screen re-inventing an ellipsis. */
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    fontWeight: FontWeight = FontWeight.Normal,
) {
    val c = LocalPhantomColors.current
    Text(
        text = text,
        color = if (color == Color.Unspecified) c.text else color,
        fontFamily = PhantomSans,
        fontSize = scaled(16.sp),
        fontWeight = fontWeight,
        lineHeight = scaled(24.sp),
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier,
    )
}

/**
 * Роль для полів вводу. `BasicTextField` бере не composable, а [TextStyle],
 * тож роллю тут може бути лише стиль — інакше кожен екран заново пише число,
 * і регулятор розміру тексту його не бачить.
 */
@Composable
fun phantomFieldStyle(
    color: Color = Color.Unspecified,
    letterSpacing: TextUnit = 0.sp,
): androidx.compose.ui.text.TextStyle {
    val c = LocalPhantomColors.current
    return androidx.compose.ui.text.TextStyle(
        color = if (color == Color.Unspecified) c.text else color,
        fontFamily = PhantomSans,
        fontSize = scaled(13.sp),
        letterSpacing = letterSpacing,
    )
}

/**
 * A section crown — the name of a step or a screen.
 *
 * Was Playfair Display, the serif "crown" the v1.2 identity removed (§3.1:
 * «Playfair Display, including the serif crowns»). The signature stays because
 * onboarding opens every step with it; the voice is Manrope [PhantomText.title2]
 * now. [fontSize] is still honoured — onboarding asks for a 24–28sp headline —
 * and the line box keeps title2's 24/18 proportion at whatever size it is given.
 */
@Composable
fun PhantomEditorialTitle(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = 17.sp,
    fontWeight: FontWeight = FontWeight.SemiBold,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    Text(
        text = text,
        style = editorialTitleStyle(fontSize, fontWeight),
        color = if (color == Color.Unspecified) LocalPhantomColors.current.text else color,
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier,
    )
}

/** What [PhantomEditorialTitle] draws, outside a composition so a JVM test can read it. */
internal fun editorialTitleStyle(
    fontSize: TextUnit = 17.sp,
    fontWeight: FontWeight = FontWeight.SemiBold,
): androidx.compose.ui.text.TextStyle {
    val role = PhantomText.title2
    return role.copy(
        fontSize = scaled(fontSize),
        fontWeight = fontWeight,
        lineHeight = scaled(fontSize * (role.lineHeight.value / role.fontSize.value)),
    )
}
