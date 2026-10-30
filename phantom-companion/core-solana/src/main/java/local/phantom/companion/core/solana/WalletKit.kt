package local.phantom.companion.core.solana

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import java.text.DecimalFormatSymbols
import java.util.Locale
import local.phantom.companion.core.design.component.Explainer
import local.phantom.companion.core.design.component.GhostButton
import local.phantom.companion.core.design.component.PrimaryButton
import local.phantom.companion.core.design.kit.ListRowHeight
import local.phantom.companion.core.design.kit.LookKit
import local.phantom.companion.core.design.kit.SheetFrame
import local.phantom.companion.core.design.kit.StatusChip
import local.phantom.companion.core.design.theme.AmberNight
import local.phantom.companion.core.design.theme.FONT_SCALE_DEFAULT
import local.phantom.companion.core.design.theme.PhantomAlpha
import local.phantom.companion.core.design.theme.PhantomKicker
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.theme.PhantomThemeSpec
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.design.R as DesignR

/*
 * Гаманець у мові форми Nightpost: чорнило, плити `raised`, одне бурштинове
 * світло на екран. Тут — лише складники, з яких зібрано екран і його вікна;
 * кольори, шрифт, радіуси й відступи — токени v1.2, власної палітри немає.
 * Material 3 тут дає лише Text та Icon: кнопки, плити, поля й діалоги — з
 * набору core-design або з цього файла, на тих самих токенах.
 */

/** Вигляд, який застосунок обрав для себе (тема й циферблат розміру тексту); гаманець бере той самий. */
data class WalletSkin(
    val spec: PhantomThemeSpec,
    val fontScale: Float = FONT_SCALE_DEFAULT,
    val accent: String = local.phantom.companion.core.design.theme.ACCENT_DEFAULT,
)

/** Чого екрану гаманця треба від вікна, у якому він живе: відбиток/PIN, вікно гаманця і захист від знімків. */
internal interface WalletDoor {
    val hasScreenLock: Boolean

    /** [detail] — що саме підписується («Олена · 5 USDC»): системне вікно відбитка показує це під заголовком. */
    fun host(titleRes: Int, detail: String? = null): WalletHost

    /** Слова відновлення на екрані: знімки й «Нещодавні» мають бачити чорне. */
    fun secure(on: Boolean)
}

/** Рішення вигляду без композиції — їх доводять на JVM. */
internal object WalletLook {

    /** Яка дія екрана дістає єдину бурштинову заливку. Решта — тихі. */
    enum class Primary { BACKUP, FILL_POCKET, NONE }

    fun cashReady(state: WalletState): Boolean =
        state.kind == WalletKind.BUILT_IN && state.cash.notes.any { it.value != null && it.voucherSig == null }

    /**
     * Спершу — щоб гроші не пропали (слова не записано), потім — щоб купюру було з чого дати
     * (кишеня порожня). Коли купюри є, головна дія живе в рядку людини й у вікні суми, а не на екрані.
     */
    fun primary(state: WalletState): Primary = when {
        state.kind != WalletKind.BUILT_IN -> Primary.NONE
        !state.backedUp && state.builtIn != null -> Primary.BACKUP
        state.address != null && !cashReady(state) -> Primary.FILL_POCKET
        else -> Primary.NONE
    }

    /**
     * Знак у шапці світиться бурштином лише тоді, коли на екрані немає бурштинової кнопки:
     * BRAND §5, правило 12 — знак не буває другим бурштиновим у регіоні.
     */
    fun markLit(state: WalletState): Boolean = primary(state) == Primary.NONE

    /** Чорнило знака в шапці: бурштин (`accentLine`, на світлому — темніший) або колір тексту. */
    fun markInk(c: PhantomColors, lit: Boolean): Color = if (lit) c.accentLine else c.text

    /** Велика сума — перша монета з грошима (у порядку [Coin]); решта — рядком під нею. */
    fun split(balances: Map<Coin, Long>): Pair<Pair<Coin, Long>, List<Pair<Coin, Long>>>? {
        val ordered = Coin.entries.mapNotNull { c -> balances[c]?.let { c to it } }
        if (ordered.isEmpty()) return null
        val lead = ordered.firstOrNull { it.second > 0 } ?: ordered.first()
        return lead to ordered.filter { it.first != lead.first }
    }

    /** Сума так, як її пише людина цієї мови: «12,5», не «12.5»; нуль — просто «0». */
    fun shown(units: Long, coin: Coin, locale: Locale = Locale.getDefault()): String {
        if (units == 0L) return "0"
        val sep = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        return Amounts.format(units, coin).replace('.', sep)
    }

    fun sum(units: Long, coin: Coin, locale: Locale = Locale.getDefault()): String = "${shown(units, coin, locale)} ${coin.symbol}"

    /** Долар і євро читаються грошима: «$12.50», «€3.00» — два знаки, без хвоста. Решта монет — null. */
    fun cash(units: Long, coin: Coin, locale: Locale = Locale.getDefault()): String? {
        val sign = when (coin) {
            Coin.USDC -> "$"
            Coin.EURC -> "€"
            else -> return null
        }
        val value = java.math.BigDecimal.valueOf(units).movePointLeft(coin.decimals).setScale(2, java.math.RoundingMode.DOWN)
        val f = java.text.DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(locale))
        return sign + f.format(value)
    }

    /** Сума для людини: гроші — знаком валюти, решта — числом і монетою. */
    fun money(units: Long, coin: Coin, locale: Locale = Locale.getDefault()): String = cash(units, coin, locale) ?: sum(units, coin, locale)

    /** Вільне в кишені — те, що ще не пішло купюрами. */
    fun freeUnits(cash: CashPocket): Long = (cash.units - cash.notes.sumOf { it.voucherUnits }).coerceAtLeast(0)

    /** Купюри, які ще можна дати: є номінал і ще не підписано. */
    fun freeNotes(cash: CashPocket): Int = cash.notes.count { it.value != null && it.voucherSig == null }

    /** Ніч — за замовчуванням: поки застосунок не сказав інакше, гаманець темний, як і бренд. */
    fun skin(fromApp: WalletSkin?): WalletSkin = fromApp ?: WalletSkin(AmberNight)

    /** Той самий FNV-1a, що `toneIndex` у вебі й PersonTone на мапі: одна людина — один тон скрізь. */
    fun toneIndex(key: String, count: Int): Int {
        var h = 0x811c9dc5.toInt()
        for (ch in key) {
            h = h xor ch.code
            h *= 0x01000193
        }
        return ((h.toLong() and 0xFFFFFFFFL) % count).toInt()
    }
}

internal const val Tnum = "tnum"

/** Цифри однакової ширини: суми й адреси не «танцюють», коли міняються. */
internal fun TextStyle.tnum(): TextStyle = copy(fontFeatureSettings = Tnum)

/** Шапка: назад, знак Естафети й назва екрана. Знак — не кнопка й не напис, TalkBack його мовчить. */
@Composable
internal fun WalletHeader(title: String, lit: Boolean, onBack: (() -> Unit)?, test: Boolean = false, end: (@Composable () -> Unit)? = null) {
    val c = LocalPhantomColors.current
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = PhantomSize.topbarPhone),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Вкладка «Гаманець» — корінь, назад звідти нікуди: стрілки немає, знак стає на поле.
        if (onBack != null) {
            Box(
                modifier = Modifier
                    .size(PhantomSpace.touchMinAndroid)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(DesignR.drawable.lc_back),
                    contentDescription = stringResource(R.string.sol_back),
                    tint = c.text,
                    modifier = Modifier.size(PhantomSize.iconL),
                )
            }
            Spacer(Modifier.width(PhantomSpace.s1))
        }
        if (onBack == null) {
            Spacer(Modifier.width(PhantomSpace.gutterPhone))
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_nightpost_symbol),
                contentDescription = null,
                tint = WalletLook.markInk(c, lit),
                modifier = Modifier.size(PhantomSize.iconL),
            )
            Spacer(Modifier.width(PhantomSpace.s3))
        }
        Text(
            text = title,
            style = if (onBack == null) PhantomText.display else PhantomText.title1,
            color = c.text,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (test) TestNetworkChip()
        end?.invoke()
        if (onBack == null) Spacer(Modifier.width(PhantomSpace.s2))
    }
}

@Composable
internal fun TestNetworkChip() {
    val c = LocalPhantomColors.current
    Box(
        modifier = Modifier
            .padding(end = PhantomSpace.s1)
            .heightIn(min = PhantomSpace.s8 - PhantomSpace.s1)
            .border(1.dp, c.lineStrong, CircleShape)
            .padding(horizontal = PhantomSpace.s3)
            .testTag("test-network"),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.sol_test_network), style = PhantomText.footnote, color = c.text2, maxLines = 1)
    }
}

@Composable
internal fun WalletScanButton(onClick: () -> Unit) {
    val c = LocalPhantomColors.current
    Box(
        modifier = Modifier
            .size(PhantomSpace.touchMinAndroid)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(DesignR.drawable.lc_qr),
            contentDescription = stringResource(R.string.sol_scan),
            tint = c.text,
            modifier = Modifier.size(PhantomSize.iconL),
        )
    }
}

/**
 * Група екрана: заголовок звичайним регістром, плита `raised` і, за потреби, речення
 * під нею — та сама граматика, що й у налаштуваннях (RowGroup + Explainer).
 */
@Composable
internal fun WalletGroup(
    title: String?,
    explainer: String? = null,
    padded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalPhantomColors.current
    Column(Modifier.fillMaxWidth()) {
        if (title != null) {
            PhantomKicker(
                text = title,
                modifier = Modifier
                    .padding(start = PhantomSpace.s4, bottom = PhantomSpace.s2)
                    .semantics { heading() },
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(PhantomRadius.sheet))
                .background(c.raised)
                .then(if (padded) Modifier.padding(PhantomSpace.s4) else Modifier),
            verticalArrangement = Arrangement.spacedBy(if (padded) PhantomSpace.s3 else PhantomSpace.s0),
            content = content,
        )
        if (explainer != null) Explainer(explainer)
    }
}

/** Волосина між рядками однієї плити. */
@Composable
internal fun WalletDivider(modifier: Modifier = Modifier) {
    val c = LocalPhantomColors.current
    Box(modifier.fillMaxWidth().height(1.dp).background(c.line))
}

/** Речення в плиті: тихе (`footnote` у `text2`) або звичайне (`callout` у `text`). */
@Composable
internal fun WalletLine(text: String, modifier: Modifier = Modifier, quiet: Boolean = true) {
    val c = LocalPhantomColors.current
    Text(
        text = text,
        style = if (quiet) PhantomText.footnote else PhantomText.callout,
        color = if (quiet) c.text2 else c.text,
        modifier = modifier,
    )
}

/**
 * Друга дія: плита без бурштину. [accent] — та сама плита у відтінку світла
 * (`accentSoft` + `accentText`), коли саме ця дія — наступний крок людини,
 * але заливка на екрані вже зайнята.
 */
@Composable
internal fun WalletAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Boolean = false,
) {
    val c = LocalPhantomColors.current
    Box(
        modifier = modifier
            .heightIn(min = PhantomSize.buttonL)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(RoundedCornerShape(PhantomRadius.control))
            .background(if (accent) c.accentSoft else c.overlay)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s2),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = PhantomText.subhead,
            fontWeight = FontWeight.SemiBold,
            color = if (accent) c.accentText else c.text,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/** Головна дія групи або тиха — за рішенням [WalletLook.primary]; ширина — уся плита. */
@Composable
internal fun WalletMainAction(label: String, primary: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    if (primary) {
        PrimaryButton(label = label, onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth())
    } else {
        WalletAction(label = label, onClick = onClick, enabled = enabled, accent = true, modifier = Modifier.fillMaxWidth())
    }
}

/** Сума великими цифрами з табличними знаками; монета — на щабель тихіше, на одній базовій лінії. */
@Composable
internal fun MoneyFigure(amount: String, symbol: String, modifier: Modifier = Modifier, big: Boolean = true) {
    val c = LocalPhantomColors.current
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
    ) {
        Text(
            text = amount,
            style = (if (big) PhantomText.display else PhantomText.title1).tnum(),
            color = c.text,
            modifier = Modifier.alignByBaseline(),
        )
        Text(
            text = symbol,
            style = PhantomText.title2,
            color = c.text2,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

/** Мережа словом біля грошей: тестова — спокійно, основна — кольором застереження. */
@Composable
internal fun NetworkLine(cluster: Cluster, modifier: Modifier = Modifier) {
    val c = LocalPhantomColors.current
    val main = cluster == Cluster.MAINNET
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
    ) {
        Box(Modifier.size(DOT).clip(CircleShape).background(if (main) c.warning else c.text3))
        Text(
            text = stringResource(if (main) R.string.sol_network_mainnet else R.string.sol_network_devnet),
            style = PhantomText.footnote,
            color = if (main) c.warning else c.text2,
        )
    }
}

/** Людина в списку — плоска пляма її тону з першою літерою, як у месенджері. */
@Composable
internal fun PersonDot(key: String, name: String) {
    val c = LocalPhantomColors.current
    val (fill, ink) = c.person[WalletLook.toneIndex(key, c.person.size)]
    Box(
        modifier = Modifier.size(PhantomSize.avatarM).clip(CircleShape).background(fill),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().take(1).uppercase(),
            style = PhantomText.headline,
            color = ink,
        )
    }
}

/** Що щойно сталося — одним реченням на плиті; TalkBack читає його сам. */
@Composable
internal fun WalletNote(text: String) {
    val c = LocalPhantomColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PhantomRadius.card))
            .background(c.raised)
            .padding(PhantomSpace.s4)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
    ) {
        Icon(
            painter = painterResource(DesignR.drawable.lc_info),
            contentDescription = null,
            tint = c.text2,
            modifier = Modifier.padding(top = PhantomSpace.s0_5).size(PhantomSize.iconM),
        )
        Text(text = text, style = PhantomText.callout, color = c.text)
    }
}

/** Застереження в групі: знак і слово кольором `warning`, без плашки. */
@Composable
internal fun WalletWarning(text: String) {
    val c = LocalPhantomColors.current
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
    ) {
        Icon(
            painter = painterResource(DesignR.drawable.lc_triangle_alert),
            contentDescription = null,
            tint = c.warning,
            modifier = Modifier.padding(top = PhantomSpace.s0_5).size(PhantomSize.iconS),
        )
        Text(text = text, style = PhantomText.footnote, color = c.text)
    }
}

@Composable
internal fun WalletSheet(
    title: String,
    onDismiss: () -> Unit,
    kicker: String? = null,
    secure: Boolean = false,
    test: Boolean = false,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalPhantomColors.current
    SheetFrame(
        title = title,
        closeLabel = stringResource(R.string.sol_close),
        onClose = onDismiss,
        headerEnd = if (test) {
            { StatusChip(stringResource(R.string.sol_card_test), Modifier.padding(end = PhantomSpace.s2)) }
        } else {
            null
        },
        footer = footer,
        secure = secure,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = PhantomSpace.s4),
            verticalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
        ) {
            if (kicker != null) Text(text = kicker, style = PhantomText.caption, color = c.text3)
            content()
        }
    }
}

/** Низ аркуша: одна бурштинова дія на всю ширину; вийти — закриттям у шапці. */
@Composable
internal fun SheetActions(
    confirm: String,
    onConfirm: () -> Unit,
    dismiss: String? = null,
    onDismiss: (() -> Unit)? = null,
    enabled: Boolean = true,
    footnote: String? = null,
) {
    val c = LocalPhantomColors.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = PhantomSpace.s2),
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PrimaryButton(label = confirm, onClick = onConfirm, enabled = enabled, modifier = Modifier.fillMaxWidth())
        if (footnote != null) Text(text = footnote, style = PhantomText.caption, color = c.text3, textAlign = TextAlign.Center)
    }
}

/**
 * Вибір монети (SOL/USDC/EURC) — радіогрупа на доріжці `overlay`, вибрана лежить на `surface`
 * і пишеться вагою 600: вибір видно не лише кольором. Це Segmented набору без його власного
 * поля s3 — в аркуші доріжка мусить стояти врівень із полем суми під нею.
 */
@Composable
internal fun CoinPicker(coins: List<Coin>, selected: Coin, onSelect: (Coin) -> Unit) {
    val c = LocalPhantomColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PhantomRadius.control))
            .background(c.overlay)
            .padding(PhantomSpace.s1)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s1),
    ) {
        coins.forEach { coin ->
            val on = coin == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = PhantomSize.buttonM)
                    .clip(RoundedCornerShape(PhantomRadius.control - PhantomSpace.s0_5))
                    .background(if (on) c.surface else c.overlay)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(coin) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = coin.symbol,
                    style = PhantomText.subhead,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (on) c.text else c.text2,
                )
            }
        }
    }
}

/**
 * Поле суми: цифри кеглем `display` з табличними знаками, монета праворуч тихіше.
 * Порожнє показує «0» кольором `text3`; хибне — рамка `danger` і `error` для TalkBack.
 */
@Composable
internal fun AmountField(
    value: String,
    onValueChange: (String) -> Unit,
    symbol: String,
    label: String,
    error: String?,
) {
    val c = LocalPhantomColors.current
    val figure = PhantomText.display.tnum().copy(textAlign = TextAlign.Center)
    val ink = if (error != null) c.danger else c.text
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = figure.copy(color = ink),
        cursorBrush = SolidColor(c.text),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = label
            if (error != null) error(error)
        },
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = PhantomSize.input)
                    .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (value.isEmpty()) Text(text = "0", style = figure, color = c.text3, modifier = Modifier.fillMaxWidth())
                    inner()
                }
                Spacer(Modifier.width(PhantomSpace.s2))
                Text(text = symbol, style = PhantomText.title2, color = c.text2)
            }
        },
    )
}

@Composable
internal fun NoteField(value: String, onValueChange: (String) -> Unit, label: String) {
    val c = LocalPhantomColors.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(PhantomRadius.control)
    val edge = if (focused) c.text2 else c.lineStrong
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = PhantomText.body.copy(color = c.text),
        cursorBrush = SolidColor(c.text),
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = label
        },
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = LookKit.rowHeight(ListRowHeight.One))
                    .clip(shape)
                    .background(c.surface)
                    .border(1.dp, edge, shape)
                    .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s3),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(text = label, style = PhantomText.body, color = c.text3)
                inner()
            }
        },
    )
}

/** Поле для слів відновлення: кілька рядків тексту `body`, підказка — `text3`. */
@Composable
internal fun WordsField(value: String, onValueChange: (String) -> Unit, hint: String, error: String?) {
    val c = LocalPhantomColors.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(PhantomRadius.control)
    val edge = when {
        error != null -> c.danger
        focused -> c.accentLine
        else -> c.lineStrong
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        minLines = 3,
        textStyle = PhantomText.body.copy(color = c.text),
        cursorBrush = SolidColor(c.accentLine),
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = hint
            if (error != null) error(error)
        },
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = PhantomSize.input)
                    .clip(shape)
                    .background(c.surface)
                    .border(if (focused || error != null) FOCUS_EDGE else 1.dp, edge, shape)
                    .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s3),
            ) {
                if (value.isEmpty()) Text(text = hint, style = PhantomText.body, color = c.text3)
                inner()
            }
        },
    )
}

/** Дванадцять слів сіткою по три: номер тихо й табличними цифрами, слово — `body`. */
@Composable
internal fun WordsGrid(words: List<String>) {
    val c = LocalPhantomColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PhantomRadius.card))
            .background(c.surface)
            .padding(PhantomSpace.s4),
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
    ) {
        words.chunked(WORDS_PER_ROW).forEachIndexed { row, three ->
            Row(Modifier.fillMaxWidth()) {
                three.forEachIndexed { i, word ->
                    Row(
                        modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {},
                        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s1),
                    ) {
                        Text(
                            text = "${row * WORDS_PER_ROW + i + 1}",
                            style = PhantomText.caption.tnum(),
                            color = c.text3,
                            modifier = Modifier.widthIn(min = WORD_INDEX).alignByBaseline(),
                        )
                        Text(text = word, style = PhantomText.body, color = c.text, modifier = Modifier.alignByBaseline())
                    }
                }
                repeat(WORDS_PER_ROW - three.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Стан купюри після підпису: знак у колі, головне слово й пояснення; TalkBack читає зміну сам. */
@Composable
internal fun HandoffView(h: Handoff, phase: HandoffPhase) {
    val c = LocalPhantomColors.current
    val who = "${h.name} · ${h.amount}"
    val (title, detail) = when (phase) {
        HandoffPhase.SIGNED -> stringResource(R.string.sol_cash_signed_nearby) to who
        HandoffPhase.SEARCHING -> stringResource(R.string.sol_handoff_searching) to who
        HandoffPhase.HANDED -> stringResource(R.string.sol_handoff_handed) to stringResource(R.string.sol_handoff_handed_detail, who)
        HandoffPhase.NOT_FOUND -> stringResource(R.string.sol_handoff_not_found) to stringResource(R.string.sol_handoff_not_found_detail)
        HandoffPhase.QUEUED -> stringResource(R.string.sol_handoff_signed) to stringResource(R.string.sol_cash_sent)
    }
    val handed = phase == HandoffPhase.HANDED
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PhantomRadius.sheet))
            .background(c.raised)
            .padding(PhantomSpace.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
    ) {
        Box(
            modifier = Modifier
                .size(PhantomSize.buttonL)
                .clip(CircleShape)
                .background(if (handed) c.success.copy(alpha = PhantomAlpha.Hairline) else c.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(if (handed) DesignR.drawable.lc_check else DesignR.drawable.lc_banknote),
                contentDescription = null,
                tint = when (phase) {
                    HandoffPhase.HANDED -> c.success
                    HandoffPhase.NOT_FOUND -> c.text3
                    else -> c.accentText
                },
                modifier = Modifier.size(PhantomSize.iconL),
            )
        }
        Column(
            modifier = Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(PhantomSpace.s0_5),
        ) {
            Text(text = title, style = PhantomText.headline, color = c.text)
            Text(text = detail, style = PhantomText.footnote.tnum(), color = c.text2)
        }
    }
}

/** §7.8: «Disabled: 40% opacity» — як у PrimaryButton. */
private const val DISABLED_ALPHA = 0.4f
private const val WORDS_PER_ROW = 3
private val DOT = 6.dp
private val FOCUS_EDGE = 2.dp
private val WORD_INDEX = 20.dp

/** §7.7: на широкому екрані аркуш стає діалогом шириною до 480. */
private val SHEET_MAX = 480.dp
