@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package local.phantom.companion.core.solana

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import local.phantom.companion.core.design.component.PhantomChip
import local.phantom.companion.core.design.haptics.PhantomHaptics
import local.phantom.companion.core.design.kit.AmountSign
import local.phantom.companion.core.design.kit.AmountText
import local.phantom.companion.core.design.kit.ListRow
import local.phantom.companion.core.design.kit.ListRowHeight
import local.phantom.companion.core.design.kit.LookKit
import local.phantom.companion.core.design.kit.StatusChip
import local.phantom.companion.core.design.motion.LocalMotionScale
import local.phantom.companion.core.design.motion.MotionAccess
import local.phantom.companion.core.design.theme.PhantomText
import kotlin.math.abs
import kotlin.math.min
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomColors
import local.phantom.companion.core.design.tokens.PhantomMotion
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace

/** Що показує історія картки зараз. */
internal sealed interface FeedView {
    data object Loading : FeedView
    data object Failed : FeedView
    data class Lines(val lines: List<ActivityLine>) : FeedView
}

internal fun cardScale(offset: Float): Float = 1f - 0.06f * min(1f, abs(offset))

internal class SettleTicker(private val tick: () -> Unit) {
    private var lastPage: Int = Int.MIN_VALUE

    fun onSettled(page: Int) {
        if (lastPage == Int.MIN_VALUE) {
            lastPage = page
        } else if (lastPage != page) {
            lastPage = page
            tick()
        }
    }
}

/**
 * Картки активів гортаються вбік; під поточною — її дії й її історія.
 * [activity] читає історію монети ([fresh] — минуло прохання «оновити»).
 */
@Composable
internal fun WalletCarousel(
    state: WalletState,
    cards: List<WalletCard>,
    points: WalletPoints?,
    names: (SolanaKey) -> String?,
    refresh: Int,
    activity: suspend (Coin, Boolean) -> List<ActivityLine>?,
    known: (Coin) -> List<ActivityLine>?,
    onSend: (Coin) -> Unit,
    onRequest: (Coin) -> Unit,
    onSplit: (() -> Unit)?,
    focus: Pair<String, Int>? = null,
    onAdd: () -> Unit = {},
    onArrange: (WalletCard) -> Unit = {},
    pending: (String) -> Boolean = { false },
    rows: @Composable () -> Unit = {},
) {
    if (cards.isEmpty()) return
    val reduced = MotionAccess.reduced(LocalMotionScale.current)
    val pager = rememberPagerState { cards.size }
    LaunchedEffect(focus) {
        val at = focus?.let { f -> cards.indexOfFirst { it.key == f.first } } ?: -1
        if (at >= 0) if (reduced) pager.scrollToPage(at) else pager.animateScrollToPage(at)
    }
    var flipped by remember { mutableStateOf<String?>(null) }
    val page = pager.currentPage.coerceIn(0, cards.lastIndex)
    val current = cards[page]
    LaunchedEffect(page) { if (flipped != current.key) flipped = null }
    val view = LocalView.current
    val ticker = remember { SettleTicker { PhantomHaptics.tick(view) } }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { ticker.onSettled(it) } }

    Column(verticalArrangement = Arrangement.spacedBy(PhantomSpace.s4)) {
        HorizontalPager(
            state = pager,
            key = { cards[it].key },
            contentPadding = PaddingValues(horizontal = PhantomSpace.gutterPhone + PhantomSpace.s2),
            pageSpacing = PhantomSpace.s3,
            flingBehavior = PagerDefaults.flingBehavior(
                state = pager,
                snapAnimationSpec = if (reduced) snap() else spring(
                    dampingRatio = PhantomMotion.LAYER_DAMPING,
                    stiffness = PhantomMotion.LAYER_STIFFNESS,
                ),
            ),
            modifier = Modifier.bleed(PhantomSpace.gutterPhone),
        ) { i ->
            val card = cards[i]
            Box(
                Modifier.graphicsLayer {
                    val s = if (reduced) 1f else cardScale((pager.currentPage - i) + pager.currentPageOffsetFraction)
                    scaleX = s
                    scaleY = s
                },
            ) {
                PhysicalCard(
                    card = card,
                    kind = state.kind,
                    flipped = flipped == card.key,
                    reduced = reduced,
                    onFlip = { flipped = if (flipped == card.key) null else card.key },
                    onAdd = onAdd,
                    onArrange = { onArrange(card) },
                )
            }
        }
        if (cards.size > 1) PageDots(cards.size, page)
        if (current is WalletCard.Money) {
            CardActions(
                onSend = { onSend(current.coin) },
                onReceive = { flipped = current.key },
                onRequest = { onRequest(current.coin) },
                onSplit = onSplit,
            )
        }
        rows()
        when (current) {
            is WalletCard.Money -> MoneyActivity(current, names, refresh, activity, known, pending, onReceive = { flipped = current.key })
            is WalletCard.Points -> PointsActivity(points)
            is WalletCard.Token, is WalletCard.Watch -> WalletLine(stringResource(R.string.sol_card_balance_only))
            WalletCard.Add -> Unit
        }
    }
}

/** Пейджер на всю ширину екрана, хоч колонка гаманця має поля: сусідня картка виглядає з-за краю. */
private fun Modifier.bleed(side: Dp) = layout { measurable, constraints ->
    val extra = (side * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
}

/** Кожен актив — свій спокійний колір поверхонь та чорнила, без фактур і градієнтів. */
internal data class CardLook(val base: Color, val ink: Color, val ink2: Color, val dark: Boolean, val line: Color = ink2, val engraving: Engraving? = null) {
    companion object {
        /** Гроші — папір теми з гравіюванням власним чорнилом (W4): кожна монета свого тону й візерунка. */
        fun of(card: WalletCard, c: PhantomColors): CardLook = when (card) {
            is WalletCard.Money -> when (card.coin) {
                Coin.EURC -> c.person[2].let { (fill, ink) -> CardLook(fill, ink, ink.copy(alpha = 0.92f), dark = true, ink, Engraving.RINGS_TOP) }
                Coin.USDC -> c.person[1].let { (fill, ink) -> CardLook(fill, ink, ink.copy(alpha = 0.92f), dark = true, ink, Engraving.WAVES) }
                Coin.SOL -> CardLook(c.cover, c.text, c.text2, c.isDark, c.coverLine, Engraving.RINGS_BOTTOM)
                Coin.SKR -> c.person[3].let { (fill, ink) -> CardLook(fill, ink, ink.copy(alpha = 0.92f), dark = true, ink, Engraving.RINGS_BOTTOM) }
            }
            else -> of(card)
        }

        private fun of(card: WalletCard): CardLook = when (card) {
            is WalletCard.Points -> CardLook(Color(0xFFE9E0CE), Color(0xFF2A241C), Color(0xFF6B604F), dark = false)
            is WalletCard.Token -> CardLook(Color(0xFF2B2F33), Color(0xFFF0F2F4), Color(0xFFB3B9BF), dark = true)
            is WalletCard.Watch -> CardLook(Color(0xFFDCD8D0), Color(0xFF24221E), Color(0xFF5F5A52), dark = false)
            WalletCard.Add -> CardLook(Color.Transparent, Color.Unspecified, Color.Unspecified, dark = false)
            is WalletCard.Money -> when (card.coin) {
                Coin.SOL -> CardLook(Color(0xFF2A2433), Color(0xFFF2EEF7), Color(0xFFB9B0C6), dark = true)
                Coin.USDC -> CardLook(Color(0xFF17304A), Color(0xFFEEF4FA), Color(0xFFA3B8CD), dark = true)
                Coin.EURC -> CardLook(Color(0xFF1E2F2B), Color(0xFFEDF4F1), Color(0xFFA6BCB5), dark = true)
                Coin.SKR -> CardLook(Color(0xFF33291F), Color(0xFFF5EEE4), Color(0xFFC3B6A4), dark = true)
            }
        }
    }
}

private val CARD_SHAPE = RoundedCornerShape(PhantomRadius.card)
private const val CARD_RATIO = 1.586f

/** Сума на картці — як у рендері W4: display, збільшений до 40/48. */
private val CARD_AMOUNT = PhantomText.display.copy(fontSize = 40.sp, lineHeight = 48.sp, letterSpacing = (-0.02).em)

@Composable
private fun PhysicalCard(
    card: WalletCard,
    kind: WalletKind,
    flipped: Boolean,
    reduced: Boolean,
    onFlip: () -> Unit,
    onAdd: () -> Unit,
    onArrange: () -> Unit,
) {
    if (card == WalletCard.Add) return AddCard(onAdd)
    val c = LocalPhantomColors.current
    val look = CardLook.of(card, c)
    val angle by animateFloatAsState(
        if (flipped) 180f else 0f,
        if (reduced) snap() else spring(
            dampingRatio = PhantomMotion.LAYER_DAMPING,
            stiffness = PhantomMotion.LAYER_STIFFNESS,
        ),
        label = "flip",
    )
    val density = LocalDensity.current.density
    val back = CardFace.back(card)
    val unknown = stringResource(R.string.sol_card_balance_unknown)
    val desc = when (card) {
        is WalletCard.Money -> stringResource(R.string.sol_card_desc, card.coin.symbol, card.units?.let { WalletLook.money(it, card.coin) } ?: unknown)
        is WalletCard.Points -> stringResource(R.string.sol_card_points) + " " + (card.total?.toString() ?: "")
        is WalletCard.Token -> stringResource(R.string.sol_card_desc, CardFace.shortKey(card.mint), CardFace.tokenSum(card) ?: unknown)
        is WalletCard.Watch -> stringResource(R.string.sol_card_watch_desc, CardFace.shortKey(card.address), card.lamports?.let { WalletLook.money(it, Coin.SOL) } ?: unknown)
        WalletCard.Add -> ""
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(CARD_RATIO)
            .graphicsLayer {
                rotationY = angle
                cameraDistance = 16f * density
                shape = CARD_SHAPE
                clip = true
            }
            .background(look.base, CARD_SHAPE)
            .then(if (look.engraving == null) Modifier.border(1.dp, c.lineStrong, CARD_SHAPE) else Modifier)
            .combinedClickable(role = Role.Button, onClick = { if (back != null) onFlip() }, onLongClick = onArrange)
            .semantics { contentDescription = desc },
    ) {
        if (angle <= 90f) {
            look.engraving?.let { CardEngraving(it, look.line) }
            when (card) {
                is WalletCard.Money -> MoneyFront(card, kind, look)
                is WalletCard.Points -> PointsFront(card, look)
                is WalletCard.Token -> TokenFront(card, look)
                is WalletCard.Watch -> WatchFront(card, look)
                WalletCard.Add -> Unit
            }
        } else if (back != null) {
            Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) { CardBack(back, look) }
        }
    }
}

@Composable
private fun BoxScope.MoneyFront(card: WalletCard.Money, kind: WalletKind, look: CardLook) {
    Column(Modifier.fillMaxSize().padding(horizontal = PhantomSpace.s4 + PhantomSpace.s0_5, vertical = PhantomSpace.s4)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(card.coin.symbol, style = PhantomText.callout.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.04.em), color = look.ink)
                val name = if (kind == WalletKind.BUILT_IN) coinName(card.coin) else stringResource(R.string.sol_external)
                if (name != null) Text(name, style = PhantomText.footnote, color = look.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.weight(1f))
        if (card.units == null) {
            Text(stringResource(R.string.sol_card_balance_unknown), style = PhantomText.callout, color = look.ink2)
        } else {
            val cash = WalletLook.cash(card.units, card.coin)
            Row {
                Text(
                    cash ?: WalletLook.shown(card.units, card.coin),
                    style = CARD_AMOUNT.tnum(),
                    color = look.ink,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
                if (cash == null) {
                    Spacer(Modifier.width(PhantomSpace.s2))
                    Text(card.coin.symbol, style = PhantomText.title2, color = look.ink2, modifier = Modifier.alignByBaseline())
                }
            }
        }
        Spacer(Modifier.height(PhantomSpace.s2))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                CardFace.shortKey(card.address),
                style = PhantomText.footnote.tnum(),
                color = look.ink2,
                modifier = Modifier.weight(1f),
            )
            Icon(painterResource(R.drawable.ic_nightpost_symbol), null, tint = look.ink, modifier = Modifier.size(PhantomSize.iconS))
            Spacer(Modifier.width(PhantomSpace.s1 + PhantomSpace.s0_5))
            Text("Nightpost", style = PhantomText.footnote.copy(fontWeight = FontWeight.SemiBold), color = look.ink)
        }
    }
}

@Composable
private fun coinName(coin: Coin): String? = when (coin) {
    Coin.EURC -> stringResource(R.string.sol_coin_eurc)
    Coin.USDC -> stringResource(R.string.sol_coin_usdc)
    Coin.SOL -> stringResource(R.string.sol_coin_sol)
    Coin.SKR -> null
}

/** Гравіювання — тонкі кільця чи хвилі чорнилом самої картки: матеріал, а не ефект. */
internal enum class Engraving { RINGS_TOP, WAVES, RINGS_BOTTOM }

@Composable
private fun CardEngraving(kind: Engraving, ink: Color) {
    val tone = ink.copy(alpha = 0.32f)
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val sx = size.width / 318f
        val sy = size.height / 200f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
        if (kind == Engraving.WAVES) {
            for (k in 0 until 16) {
                val y0 = 20f + k * 12f
                val path = androidx.compose.ui.graphics.Path()
                var x = 0f
                while (x <= 318f) {
                    val y = y0 + kotlin.math.sin(x / 318f * Math.PI.toFloat() * 2f + k * 0.35f) * 9f
                    if (x == 0f) path.moveTo(0f, y * sy) else path.lineTo(x * sx, y * sy)
                    x += 8f
                }
                drawPath(path, tone, style = stroke)
            }
        } else {
            val center = if (kind == Engraving.RINGS_TOP) Offset(278f * sx, 30f * sy) else Offset(30f * sx, 180f * sy)
            var r = 24f
            while (r < 360f) {
                drawCircle(tone, radius = r * sx, center = center, style = stroke)
                r += 11f
            }
        }
    }
}

/** Що на звороті й як картку назвати: свій рахунок — «Отримати», чужа адреса — «лише дивитись». */
internal object CardFace {
    data class Back(val symbol: String, val address: SolanaKey, val cluster: Cluster, val watch: Boolean)

    fun back(card: WalletCard): Back? = when (card) {
        is WalletCard.Money -> Back(card.coin.symbol, card.address, card.cluster, watch = false)
        is WalletCard.Token -> Back(shortKey(card.mint), card.address, card.cluster, watch = false)
        is WalletCard.Watch -> Back("SOL", card.address, card.cluster, watch = true)
        is WalletCard.Points, WalletCard.Add -> null
    }

    fun shortKey(key: SolanaKey): String = key.base58.let { it.take(4) + "…" + it.takeLast(4) }

    fun tokenSum(card: WalletCard.Token): String? {
        val units = card.units ?: return null
        return if (units == 0L) "0" else card.decimals?.let { tokenAmount(units, it) }
    }
}

@Composable
private fun AddCard(onAdd: () -> Unit) {
    val c = LocalPhantomColors.current
    val label = stringResource(R.string.sol_add_card)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(CARD_RATIO)
            .clip(CARD_SHAPE)
            .border(1.dp, c.line, CARD_SHAPE)
            .background(c.raised)
            .clickable(role = Role.Button, onClick = onAdd)
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2)) {
            Box(Modifier.size(PhantomSize.buttonL).clip(CircleShape).background(c.overlay), contentAlignment = Alignment.Center) {
                Text("+", style = PhantomText.title1, color = c.text)
            }
            Text(label, style = PhantomText.callout, color = c.text)
        }
    }
}

@Composable
private fun BoxScope.TokenFront(card: WalletCard.Token, look: CardLook) {
    Column(Modifier.fillMaxSize().padding(PhantomSpace.s5)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sol_card_token), style = PhantomText.headline, color = look.ink, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.weight(1f))
        val sum = CardFace.tokenSum(card)
        if (sum == null) {
            Text(stringResource(R.string.sol_card_balance_unknown), style = PhantomText.callout, color = look.ink2)
        } else {
            Text(sum, style = PhantomText.display.tnum(), color = look.ink, maxLines = 1)
        }
        Spacer(Modifier.height(PhantomSpace.s3))
        Text(CardFace.shortKey(card.mint), style = PhantomText.subhead.tnum(), color = look.ink2)
    }
}

@Composable
private fun BoxScope.WatchFront(card: WalletCard.Watch, look: CardLook) {
    Column(Modifier.fillMaxSize().padding(PhantomSpace.s5)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sol_card_watch), style = PhantomText.headline, color = look.ink, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.weight(1f))
        if (card.lamports == null) {
            Text(stringResource(R.string.sol_card_balance_unknown), style = PhantomText.callout, color = look.ink2)
        } else {
            Row {
                Text(WalletLook.shown(card.lamports, Coin.SOL), style = PhantomText.display.tnum(), color = look.ink, maxLines = 1, modifier = Modifier.alignByBaseline())
                Spacer(Modifier.width(PhantomSpace.s2))
                Text("SOL", style = PhantomText.title2, color = look.ink2, modifier = Modifier.alignByBaseline())
            }
        }
        Spacer(Modifier.height(PhantomSpace.s3))
        Text(CardNumber.of(card.address), style = PhantomText.subhead.tnum(), color = look.ink2)
    }
}

/** Адреса як номер картки: справжні перші й останні знаки, середина — крапками. */
internal object CardNumber {
    fun of(address: SolanaKey): String = address.base58.let { it.take(4) + "  ····  ····  " + it.takeLast(4) }
}

@Composable
private fun BoxScope.PointsFront(card: WalletCard.Points, look: CardLook) {
    Column(Modifier.fillMaxSize().padding(PhantomSpace.s5)) {
        Text(stringResource(R.string.sol_card_points), style = PhantomText.headline, color = look.ink)
        Spacer(Modifier.weight(1f))
        if (card.total == null) {
            Text(stringResource(R.string.sol_card_points_unreachable), style = PhantomText.callout, color = look.ink2)
        } else {
            Text(card.total.toString(), style = PhantomText.display.tnum(), color = look.ink)
        }
        Spacer(Modifier.height(PhantomSpace.s3))
        Text("Nightpost", style = PhantomText.caption.copy(fontWeight = FontWeight.SemiBold), color = look.ink2)
    }
}

/** Зворот: справжня адреса кодом і знаками, і «скопіювати». */
@Composable
private fun CardBack(card: CardFace.Back, look: CardLook) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().padding(PhantomSpace.s4), verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2)) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s4)) {
            AddressQr(card.address.base58, Modifier.fillMaxHeight().aspectRatio(1f))
            Column(Modifier.weight(1f)) {
                Text(
                    if (card.watch) stringResource(R.string.sol_card_watch) else stringResource(R.string.sol_card_back_title, card.symbol),
                    style = PhantomText.headline,
                    color = look.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (card.cluster == Cluster.DEVNET) Text(stringResource(R.string.sol_card_test_money), style = PhantomText.caption, color = look.ink2)
                Spacer(Modifier.height(PhantomSpace.s2))
                Text(
                    card.address.base58.chunked(4).joinToString(" "),
                    style = PhantomText.footnote.tnum(),
                    color = look.ink,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { contentDescription = card.address.base58 },
                )
            }
        }
        Row(
            modifier = Modifier
                .heightIn(min = PhantomSpace.touchMinAndroid)
                .clip(RoundedCornerShape(PhantomRadius.control))
                .background(look.ink.copy(alpha = 0.12f))
                .clickable(role = Role.Button) { copy(ctx, card.address.base58, R.string.sol_copied) }
                .padding(horizontal = PhantomSpace.s3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
        ) {
            Icon(painterResource(R.drawable.lc_copy), null, tint = look.ink, modifier = Modifier.size(PhantomSize.iconS))
            Text(stringResource(R.string.sol_copy), style = PhantomText.footnote.copy(fontWeight = FontWeight.SemiBold), color = look.ink, maxLines = 1)
        }
    }
}

internal fun copy(ctx: Context, text: String, done: Int) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("solana", text))
    Toast.makeText(ctx, done, Toast.LENGTH_SHORT).show()
}

/** QR квадратами на Canvas, з тихою зоною в 4 модулі — як InviteQr у людях. */
@Composable
internal fun AddressQr(text: String, modifier: Modifier) {
    val matrix = remember(text) {
        runCatching { Encoder.encode(text, ErrorCorrectionLevel.M, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")).matrix }.getOrNull()
    }
    Canvas(modifier.clip(RoundedCornerShape(PhantomRadius.control)).background(Color.White)) {
        val m = matrix ?: return@Canvas
        val grid = m.width + 8
        val cell = kotlin.math.floor(size.minDimension / grid)
        val inset = (size.minDimension - cell * grid) / 2f + cell * 4
        for (y in 0 until m.height) {
            var x = 0
            while (x < m.width) {
                if (m.get(x, y).toInt() != 1) { x++; continue }
                val start = x
                while (x < m.width && m.get(x, y).toInt() == 1) x++
                drawRect(Color.Black, Offset(inset + start * cell, inset + y * cell), androidx.compose.ui.geometry.Size((x - start) * cell, cell))
            }
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    val c = LocalPhantomColors.current
    val label = stringResource(R.string.sol_card_page, current + 1, count)
    Row(
        Modifier.fillMaxWidth().semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s1, Alignment.CenterHorizontally),
    ) {
        repeat(count) { i ->
            Box(
                Modifier
                    .height(6.dp)
                    .width(if (i == current) 18.dp else 6.dp)
                    .clip(CircleShape)
                    .background(if (i == current) c.text else c.text3.copy(alpha = 0.5f)),
            )
        }
    }
}

@Composable
private fun CardActions(onSend: () -> Unit, onReceive: () -> Unit, onRequest: () -> Unit, onSplit: (() -> Unit)?) {
    val c = LocalPhantomColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s3), verticalAlignment = Alignment.Top) {
        val label = stringResource(R.string.sol_card_send)
        Row(
            modifier = Modifier
                .weight(1f)
                .height(PhantomSize.buttonL)
                .clip(CircleShape)
                .background(c.accent)
                .clickable(role = Role.Button, onClick = onSend)
                .testTag("card-send"),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.lc_arrow_up_right), null, tint = c.onAccent, modifier = Modifier.size(PhantomSize.iconM))
            Spacer(Modifier.width(PhantomSpace.s2))
            Text(label, style = PhantomText.subhead.copy(fontWeight = FontWeight.Medium), color = c.onAccent, maxLines = 1)
        }
        CardAction(R.drawable.lc_arrow_down_left, stringResource(R.string.sol_card_receive), onReceive)
        CardAction(R.drawable.lc_hand, stringResource(R.string.sol_card_request), onRequest)
        if (onSplit != null) CardAction(R.drawable.lc_split, stringResource(R.string.sol_card_split), onSplit)
    }
}

@Composable
private fun CardAction(icon: Int, label: String, onClick: () -> Unit) {
    val c = LocalPhantomColors.current
    Column(
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s1),
    ) {
        Box(
            Modifier.size(PhantomSize.buttonL).clip(CircleShape).background(c.raised),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), null, tint = c.text, modifier = Modifier.size(PhantomSize.iconM))
        }
        Text(label, style = PhantomText.caption, color = c.text2, maxLines = 1)
    }
}

@Composable
private fun MoneyActivity(
    card: WalletCard.Money,
    names: (SolanaKey) -> String?,
    refresh: Int,
    activity: suspend (Coin, Boolean) -> List<ActivityLine>?,
    known: (Coin) -> List<ActivityLine>?,
    pending: (String) -> Boolean = { false },
    onReceive: () -> Unit = {},
) {
    val views = remember { mutableStateMapOf<String, FeedView>() }
    val read = remember { mutableStateMapOf<String, Pair<Int, Long?>>() }
    val key = card.cluster.wire + card.address.base58 + card.coin.wire
    LaunchedEffect(key, refresh, card.units) {
        val mark = refresh to card.units
        val had = views[key] ?: known(card.coin)?.let { FeedView.Lines(it) }
        val last = read[key]
        if (had is FeedView.Lines && (last == null || last == mark)) {
            views[key] = had
            read[key] = mark
            return@LaunchedEffect
        }
        // Свіже читання — лише коли щось справді змінилось: «оновити» чи гроші на картці.
        val fresh = last != null && (last.first != refresh || (last.second != null && last.second != card.units))
        views[key] = had ?: FeedView.Loading
        val got = activity(card.coin, fresh)
        read[key] = mark
        views[key] = got?.let { FeedView.Lines(it) } ?: (had as? FeedView.Lines ?: FeedView.Failed)
    }
    val c = LocalPhantomColors.current
    when (val v = views[key] ?: FeedView.Loading) {
        FeedView.Loading -> {
            Column(verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2)) {
                repeat(2) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(LookKit.rowHeight(ListRowHeight.Two))
                            .clip(RoundedCornerShape(PhantomRadius.card))
                            .background(c.raised)
                            .testTag("activity-loading"),
                    )
                }
            }
        }
        FeedView.Failed -> {
            WalletGroup(title = stringResource(R.string.sol_recent_title), padded = false) {
                WalletLine(stringResource(R.string.sol_activity_failed), modifier = Modifier.padding(PhantomSpace.s4), quiet = false)
            }
        }
        is FeedView.Lines -> {
            var all by remember(key) { mutableStateOf(false) }
            var filter by remember(key) { mutableStateOf(ActivityFilter.All) }
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.sol_recent_title),
                    style = PhantomText.subhead,
                    color = c.text3,
                    modifier = Modifier.padding(bottom = PhantomSpace.s1).semantics { heading() },
                )
                if (v.lines.isEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = PhantomSpace.s3).testTag("activity-empty"),
                        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
                    ) {
                        WalletLine(stringResource(R.string.sol_activity_empty, card.coin.symbol))
                        if (card.cluster != Cluster.DEVNET) WalletAction(stringResource(R.string.sol_activity_empty_receive, card.coin.symbol), onClick = onReceive)
                    }
                } else {
                    val locale = LocalConfiguration.current.locales.get(0) ?: Locale.getDefault()
                    val zone = ZoneId.systemDefault()
                    val now = System.currentTimeMillis()
                    val today = stringResource(R.string.sol_day_today)
                    val yesterday = stringResource(R.string.sol_day_yesterday)
                    val failedLabel = stringResource(R.string.sol_activity_failed_row)
                    if (v.lines.any { it.units > 0 } && v.lines.any { it.units <= 0 }) {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = PhantomSpace.s2),
                            horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
                        ) {
                            ActivityFilter.entries.forEach { f ->
                                PhantomChip(
                                    label = stringResource(f.label),
                                    selected = filter == f,
                                    onClick = { filter = f; all = false },
                                    modifier = Modifier.testTag("activity-filter-${f.tag}"),
                                )
                            }
                        }
                    }
                    val lines = v.lines.filter(filter::keep)
                    val shown = if (all) lines else lines.take(RECENT_ROWS)
                    shown.forEachIndexed { i, line ->
                        val incoming = line.units > 0
                        val name = line.counterparty?.let(names)
                        val title = name ?: stringResource(if (incoming) R.string.sol_activity_received else R.string.sol_activity_sent)
                        val address = if (name == null) line.counterparty?.short() else null
                        val time = line.atSec?.let {
                            val day = when (val h = ActivityDay.header(it, now, zone, locale)) {
                                DayHeader.Today -> today
                                DayHeader.Yesterday -> yesterday
                                is DayHeader.On -> h.text
                            }
                            "$day, ${ActivityDay.time(it, zone, locale)}"
                        }
                        ActivityRow(
                            icon = if (incoming) R.drawable.lc_arrow_down_left else R.drawable.lc_arrow_up_right,
                            person = name?.let { (line.counterparty?.base58 ?: it) to it },
                            title = title,
                            sub = listOfNotNull(failedLabel.takeIf { line.failed }, address, time).joinToString(" · ").ifEmpty { null },
                            failed = line.failed,
                            digits = WalletLook.money(kotlin.math.abs(line.units), card.coin),
                            sign = if (incoming) AmountSign.In else AmountSign.Out,
                            pending = !line.failed && pending(line.signature),
                            divider = i < shown.lastIndex || shown.size < lines.size,
                            padded = false,
                        )
                    }
                    if (shown.size < lines.size) {
                        ListRow(
                            title = stringResource(R.string.sol_activity_all, lines.size),
                            height = ListRowHeight.One,
                            modifier = Modifier.testTag("activity-all"),
                            onClick = { all = true },
                            padded = false,
                        )
                    }
                }
            }
        }
    }
}

/** На головній гаманця — лише останні рухи; решта за «Усі» (T28). */
private const val RECENT_ROWS = 3

/** Фільтр історії: прихід — додатні одиниці, решта — надіслане. */
private enum class ActivityFilter(val tag: String, val label: Int) {
    All("all", R.string.sol_activity_filter_all),
    Sent("sent", R.string.sol_activity_sent),
    Received("received", R.string.sol_activity_received);

    fun keep(line: ActivityLine): Boolean = when (this) {
        All -> true
        Sent -> line.units <= 0
        Received -> line.units > 0
    }
}

@Composable
private fun PointsActivity(points: WalletPoints?) {
    WalletGroup(title = stringResource(R.string.sol_activity_title), padded = false) {
        val inset = Modifier.padding(PhantomSpace.s4)
        when (points) {
            is WalletPoints.Shown ->
                if (points.events.isEmpty()) {
                    WalletLine(stringResource(R.string.sol_points_empty), modifier = inset)
                } else {
                    points.events.forEachIndexed { i, e ->
                        ActivityRow(
                            icon = DesignStar,
                            title = e.label,
                            sub = DateUtils.getRelativeTimeSpanString(e.atMs).toString(),
                            digits = e.points.toString(),
                            sign = AmountSign.In,
                            divider = i < points.events.lastIndex,
                        )
                    }
                }
            else -> WalletLine(stringResource(R.string.sol_card_points_unreachable), modifier = inset)
        }
    }
}

private val DesignStar = local.phantom.companion.core.design.R.drawable.lc_star

@Composable
private fun ActivityRow(
    icon: Int,
    title: String,
    sub: String?,
    digits: String,
    sign: AmountSign,
    pending: Boolean = false,
    divider: Boolean = false,
    person: Pair<String, String>? = null,
    padded: Boolean = true,
    failed: Boolean = false,
) {
    val c = LocalPhantomColors.current
    ListRow(
        title = title,
        height = ListRowHeight.Two,
        sub = sub,
        divider = divider,
        padded = padded,
        subTone = if (failed) c.danger else androidx.compose.ui.graphics.Color.Unspecified,
        leading = person?.let { (k, n) -> { PersonDot(k, n) } } ?: {
            Box(Modifier.size(PhantomSize.avatarM).clip(CircleShape).background(c.overlay), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), null, tint = c.text2, modifier = Modifier.size(PhantomSize.iconS))
            }
        },
        trailing = {
            Column(horizontalAlignment = Alignment.End) {
                if (failed) {
                    Text(
                        "\u2212$digits",
                        style = PhantomText.callout.tnum().copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough),
                        color = c.text3,
                        modifier = Modifier.testTag("activity-failed"),
                    )
                } else {
                    AmountText(digits = digits, sign = sign, style = PhantomText.callout)
                }
                if (pending) {
                    StatusChip(label = stringResource(R.string.sol_activity_pending))
                }
            }
        },
    )
}

/** «Надіслати»: кому — з тих, хто сам дав адресу в розмові; далі той самий аркуш суми й відбиток. */
@Composable
internal fun SendPicker(
    coin: Coin,
    contacts: List<SolanaContact>,
    onPick: (SolanaContact) -> Unit,
    onDismiss: () -> Unit,
    onScan: (() -> Unit)? = null,
) {
    val c = LocalPhantomColors.current
    WalletSheet(title = stringResource(R.string.sol_send_title, coin.symbol), onDismiss = onDismiss) {
        if (onScan != null) {
            ListRow(
                title = stringResource(R.string.sol_scan),
                height = ListRowHeight.Two,
                leading = {
                    Box(
                        Modifier.size(PhantomSize.avatarM).clip(CircleShape).background(c.overlay),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(local.phantom.companion.core.design.R.drawable.lc_qr),
                            contentDescription = null,
                            tint = c.text,
                            modifier = Modifier.size(PhantomSize.iconM),
                        )
                    }
                },
                onClick = onScan,
                padded = false,
            )
        }
        val ready = contacts.filter { it.address != null }
        if (ready.isEmpty()) WalletLine(stringResource(R.string.sol_send_none), quiet = false)
        ready.forEach { p ->
            ListRow(
                title = p.name,
                sub = p.address!!.short().takeIf { ready.count { it.name == p.name } > 1 },
                height = ListRowHeight.Two,
                leading = { PersonDot(p.id.ifBlank { p.name }, p.name) },
                onClick = { onPick(p) },
                padded = false,
            )
        }
    }
}

@Composable
internal fun RequestSheet(state: WalletState, initial: Coin, onDismiss: () -> Unit) {
    val address = state.address ?: return
    val ctx = LocalContext.current
    val pc = LocalPhantomColors.current
    val coins = FeeRoute.payableCoins(state).ifEmpty { listOf(initial) }
    var coin by remember { mutableStateOf(initial.takeIf { it in coins } ?: coins.first()) }
    var text by remember { mutableStateOf("") }
    val units = Amounts.toUnits(text, coin)
    val reference = remember { PurchaseReference.fresh().key }
    val url = units?.let { TransferRequest(address, coin, state.cluster, it, reference, "Nightpost", "").url() }
    val bad = if (text.isNotBlank() && units == null) stringResource(R.string.sol_amount_bad) else null
    WalletSheet(
        title = stringResource(R.string.sol_request_title, coin.symbol),
        onDismiss = onDismiss,
        test = state.cluster == Cluster.DEVNET,
    ) {
        if (coins.size > 1) CoinPicker(coins, coin) { coin = it }
        AmountField(value = text, onValueChange = { text = it }, symbol = coin.symbol, label = stringResource(R.string.sol_amount), error = bad)
        if (bad != null) Text(bad, style = PhantomText.footnote, color = pc.danger)
        if (state.cluster != Cluster.DEVNET) NetworkLine(state.cluster)
        if (url != null) {
            AddressQr(url, Modifier.fillMaxWidth().widthIn(max = 280.dp).aspectRatio(1f).align(Alignment.CenterHorizontally))
            WalletAction(stringResource(R.string.sol_request_copy), modifier = Modifier.fillMaxWidth(), onClick = { copy(ctx, url, R.string.sol_request_copied) })
        }
        SheetActions(
            confirm = stringResource(R.string.sol_request_share),
            enabled = url != null,
            onConfirm = {
                url ?: return@SheetActions
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
                ctx.startActivity(Intent.createChooser(send, null))
            },
            dismiss = stringResource(R.string.sol_cancel),
            onDismiss = onDismiss,
        )
    }
}

/** Бали в шапці, лише коли картка балів схована в каруселі. */
@Composable
internal fun PointsPill(total: Int, onClick: () -> Unit) {
    val c = LocalPhantomColors.current
    val label = stringResource(R.string.sol_points_pill, total)
    Row(
        modifier = Modifier
            .heightIn(min = PhantomSpace.touchMinAndroid)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = PhantomSpace.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.clip(CircleShape).background(c.raised).padding(horizontal = PhantomSpace.s3, vertical = PhantomSpace.s1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s1),
        ) {
            Icon(painterResource(DesignStar), null, tint = c.text2, modifier = Modifier.size(PhantomSize.iconS))
            Text(total.toString(), style = PhantomText.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        }
    }
}

internal sealed interface DayHeader {
    data object Today : DayHeader
    data object Yesterday : DayHeader
    data class On(val text: String) : DayHeader
}

internal object ActivityDay {
    fun header(atSec: Long, nowMs: Long, zone: ZoneId, locale: Locale): DayHeader {
        val date = Instant.ofEpochSecond(atSec).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return when (date) {
            today -> DayHeader.Today
            today.minusDays(1) -> DayHeader.Yesterday
            else -> DayHeader.On(DateTimeFormatter.ofPattern("d MMM", locale).format(date))
        }
    }

    fun time(atSec: Long, zone: ZoneId, locale: Locale): String =
        DateTimeFormatter.ofPattern("HH:mm", locale).format(Instant.ofEpochSecond(atSec).atZone(zone))
}
