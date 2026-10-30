package local.phantom.companion.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill as PaintFill
import androidx.compose.ui.graphics.drawscope.Stroke as PaintStroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import local.phantom.companion.R
import local.phantom.companion.core.design.R as DesignR
import local.phantom.companion.core.design.kit.SheetFrame
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.net.peer.PeerCard
import local.phantom.companion.core.net.peer.PeerMessage
import org.json.JSONObject

/** Отримані подарунки з того самого логу листів, що й веб-полиця «Я»: без мережі, без балів. */
internal object ProfileGifts {

    data class Got(val id: String, val gift: String, val note: String?, val fromId: String, val atMs: Long)

    private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val KIND = Regex("^[a-z][a-z0-9-]{0,23}$")

    /** Свої й криві — ні, повтор доставки — раз, найновіші першими. */
    fun received(log: List<PeerMessage>, me: String): List<Got> {
        if (me.isBlank()) return emptyList()
        val seen = HashSet<String>()
        return log.asSequence()
            .filter { it.fromId != me }
            .mapNotNull { m -> parse(m) }
            .filter { seen.add(it.id) }
            .sortedByDescending { it.atMs }
            .toList()
    }

    private fun parse(m: PeerMessage): Got? {
        val card = PeerCard.decode(m.body) ?: return null
        if (card.what != PeerCard.Card.GIFT) return null
        val o = runCatching { JSONObject(card.payload) }.getOrNull() ?: return null
        val id = o.optString("id")
        val gift = o.optString("gift")
        if (!ID.matches(id) || !KIND.matches(gift)) return null
        val note = o.optString("note").trim().take(140).takeIf { it.isNotEmpty() }
        return Got(id, gift, note, m.fromId, m.sentAtMs)
    }

    internal enum class Fill { None, Soft, Solid }
    internal class Glyph(val d: String, val fill: Fill)

    /** Тон — індекс у тонах людей, як `TONE` у вебі; невідоме — запаковане. */
    internal val TONE = mapOf(
        "flowers" to 4, "coffee" to 0, "cake" to 3, "star" to 3, "balloon" to 2, "tea" to 1,
        "plant" to 1, "book" to 5, "candle" to 0, "sun" to 3, "cookie" to 0, "heart" to 4,
    )

    internal val NAME = mapOf(
        "flowers" to R.string.gift_kind_flowers, "coffee" to R.string.gift_kind_coffee, "cake" to R.string.gift_kind_cake,
        "star" to R.string.gift_kind_star, "balloon" to R.string.gift_kind_balloon, "tea" to R.string.gift_kind_tea,
        "plant" to R.string.gift_kind_plant, "book" to R.string.gift_kind_book, "candle" to R.string.gift_kind_candle,
        "sun" to R.string.gift_kind_sun, "cookie" to R.string.gift_kind_cookie, "heart" to R.string.gift_kind_heart,
    )

    /** Ті самі контури, що в `giftCatalogue.tsx` (поле 48, обведення 2.2). */
    internal val ART: Map<String, List<Glyph>> = mapOf(
        "flowers" to listOf(
            Glyph("M15 27 L24 44 L33 27 Z", Fill.Soft),
            Glyph("M24 30 V20 M24 30 L17 21 M24 30 L31 21", Fill.None),
            Glyph("M19.0 15.0 a5.0 5.0 0 1 0 10.0 0 a5.0 5.0 0 1 0 -10.0 0 Z", Fill.Soft),
            Glyph("M10.5 18.0 a4.5 4.5 0 1 0 9.0 0 a4.5 4.5 0 1 0 -9.0 0 Z", Fill.Soft),
            Glyph("M28.5 18.0 a4.5 4.5 0 1 0 9.0 0 a4.5 4.5 0 1 0 -9.0 0 Z", Fill.Soft),
            Glyph("M15 27 L24 44 L33 27", Fill.None),
        ),
        "coffee" to listOf(
            Glyph("M12 20 H32 V30 a8 8 0 0 1 -8 8 H20 a8 8 0 0 1 -8 -8 Z", Fill.Soft),
            Glyph("M12 20 H32 V30 a8 8 0 0 1 -8 8 H20 a8 8 0 0 1 -8 -8 Z", Fill.None),
            Glyph("M32 23 h3 a4 4 0 0 1 0 8 h-3", Fill.None),
            Glyph("M8 42 H36", Fill.None),
            Glyph("M18 9 c-2 3 2 4 0 7 M24 9 c-2 3 2 4 0 7", Fill.None),
        ),
        "cake" to listOf(
            Glyph("M10 30 H38 V41 H10 Z", Fill.Soft),
            Glyph("M14 22 H34 V30 H14 Z", Fill.Soft),
            Glyph("M10 30 H38 V41 H10 Z M14 22 H34 V30 H14 Z", Fill.None),
            Glyph("M10 35 c3 2 5 2 7 0 s4 -2 7 0 s4 2 7 0 s4 -2 7 0", Fill.None),
            Glyph("M24 22 V15", Fill.None),
            Glyph("M24 7 c3 3 3 6 0 7 c-3 -1 -3 -4 0 -7 Z", Fill.Solid),
        ),
        "star" to listOf(
            Glyph("M24.0 8.0 L28.4 18.9 L40.2 19.7 L31.1 27.3 L34.0 38.8 L24.0 32.5 L14.0 38.8 L16.9 27.3 L7.8 19.7 L19.6 18.9 Z", Fill.Soft),
            Glyph("M24.0 8.0 L28.4 18.9 L40.2 19.7 L31.1 27.3 L34.0 38.8 L24.0 32.5 L14.0 38.8 L16.9 27.3 L7.8 19.7 L19.6 18.9 Z", Fill.None),
        ),
        "balloon" to listOf(
            Glyph("M13.0 18.0 a11.0 13.0 0 1 0 22.0 0 a11.0 13.0 0 1 0 -22.0 0 Z", Fill.Soft),
            Glyph("M13.0 18.0 a11.0 13.0 0 1 0 22.0 0 a11.0 13.0 0 1 0 -22.0 0 Z", Fill.None),
            Glyph("M22 31 h4 l-2 3 Z", Fill.Solid),
            Glyph("M24 34 c-3 3 3 5 0 8 s2 3 1 4", Fill.None),
            Glyph("M19 12 a6 6 0 0 1 4 -3", Fill.None),
        ),
        "tea" to listOf(
            Glyph("M11 22 H33 V28 a11 11 0 0 1 -22 0 Z", Fill.Soft),
            Glyph("M11 22 H33 V28 a11 11 0 0 1 -22 0 Z", Fill.None),
            Glyph("M33 25 l5 -4 v4 l-5 6", Fill.None),
            Glyph("M11 26 l-4 -2", Fill.None),
            Glyph("M16 22 c0 -4 3 -6 6 -6 s6 2 6 6", Fill.None),
            Glyph("M20.2 14.0 a1.8 1.8 0 1 0 3.6 0 a1.8 1.8 0 1 0 -3.6 0 Z", Fill.Solid),
        ),
        "plant" to listOf(
            Glyph("M14 30 H34 L31 43 H17 Z", Fill.Soft),
            Glyph("M14 30 H34 L31 43 H17 Z", Fill.None),
            Glyph("M24 30 V16", Fill.None),
            Glyph("M24 22 c-8 0 -11 -5 -11 -10 c6 0 11 3 11 10 Z", Fill.Soft),
            Glyph("M24 22 c-8 0 -11 -5 -11 -10 c6 0 11 3 11 10 Z", Fill.None),
            Glyph("M24 19 c7 0 10 -5 10 -10 c-6 0 -10 3 -10 10 Z", Fill.Soft),
            Glyph("M24 19 c7 0 10 -5 10 -10 c-6 0 -10 3 -10 10 Z", Fill.None),
        ),
        "book" to listOf(
            Glyph("M24 14 c-5 -3 -10 -3 -16 -2 V38 c6 -1 11 -1 16 2 c5 -3 10 -3 16 -2 V12 c-6 -1 -11 -1 -16 2 Z", Fill.Soft),
            Glyph("M24 14 c-5 -3 -10 -3 -16 -2 V38 c6 -1 11 -1 16 2 c5 -3 10 -3 16 -2 V12 c-6 -1 -11 -1 -16 2 Z M24 14 V40", Fill.None),
            Glyph("M12 19 c3 -0.5 6 0 8 1 M12 25 c3 -0.5 6 0 8 1 M28 20 c2 -1 5 -1.5 8 -1 M28 26 c2 -1 5 -1.5 8 -1", Fill.None),
        ),
        "candle" to listOf(
            Glyph("M18 20 H30 V40 H18 Z", Fill.Soft),
            Glyph("M18 20 H30 V40 H18 Z", Fill.None),
            Glyph("M12 40 H36", Fill.None),
            Glyph("M24 20 V16", Fill.None),
            Glyph("M24 6 c4 4 4 8 0 9.5 c-4 -1.5 -4 -5.5 0 -9.5 Z", Fill.Solid),
            Glyph("M22 25 v6", Fill.None),
        ),
        "sun" to listOf(
            Glyph("M15.5 24.0 a8.5 8.5 0 1 0 17.0 0 a8.5 8.5 0 1 0 -17.0 0 Z", Fill.Soft),
            Glyph("M15.5 24.0 a8.5 8.5 0 1 0 17.0 0 a8.5 8.5 0 1 0 -17.0 0 Z", Fill.None),
            Glyph("M24 7 v5 M24 36 v5 M7 24 h5 M36 24 h5 M12 12 l3.5 3.5 M32.5 32.5 L36 36 M36 12 l-3.5 3.5 M15.5 32.5 L12 36", Fill.None),
        ),
        "cookie" to listOf(
            Glyph("M9.0 24.0 a15.0 15.0 0 1 0 30.0 0 a15.0 15.0 0 1 0 -30.0 0 Z", Fill.Soft),
            Glyph("M39 24 a15 15 0 1 1 -9 -13.8 a3.5 3.5 0 0 0 4.5 4.5 a3.5 3.5 0 0 0 4.5 4.5 a15 15 0 0 1 0 4.8 Z", Fill.None),
            Glyph("M16.2 20.0 a1.8 1.8 0 1 0 3.6 0 a1.8 1.8 0 1 0 -3.6 0 Z", Fill.Solid),
            Glyph("M24.2 29.0 a1.8 1.8 0 1 0 3.6 0 a1.8 1.8 0 1 0 -3.6 0 Z", Fill.Solid),
            Glyph("M15.5 30.0 a1.5 1.5 0 1 0 3.0 0 a1.5 1.5 0 1 0 -3.0 0 Z", Fill.Solid),
            Glyph("M27.6 19.0 a1.4 1.4 0 1 0 2.8 0 a1.4 1.4 0 1 0 -2.8 0 Z", Fill.Solid),
        ),
        "heart" to listOf(
            Glyph("M24 40 C10 31 7 23 9 17 c2 -6 10 -8 15 -2 c5 -6 13 -4 15 2 c2 6 -1 14 -15 23 Z", Fill.Soft),
            Glyph("M24 40 C10 31 7 23 9 17 c2 -6 10 -8 15 -2 c5 -6 13 -4 15 2 c2 6 -1 14 -15 23 Z", Fill.None),
        ),
        "wrap" to listOf(
            Glyph("M9 20 H39 V27 H9 Z M12 27 H36 V42 H12 Z", Fill.Soft),
            Glyph("M9 20 H39 V27 H9 Z M12 27 H36 V42 H12 Z", Fill.None),
            Glyph("M24 20 V42", Fill.None),
            Glyph("M24 20 c-4 -6 -12 -8 -12 -3 c0 3 6 3 12 3 Z M24 20 c4 -6 12 -8 12 -3 c0 3 -6 3 -12 3 Z", Fill.None),
        ),
    )
}

private const val VIEW = 48f

@Composable
internal fun GiftGlyph(kind: String, size: Dp, modifier: Modifier = Modifier) {
    val c = LocalPhantomColors.current
    val tone = ProfileGifts.TONE[kind]
    val (bg, ink) = if (tone == null || c.person.isEmpty()) c.surface to c.text2 else c.person[Math.floorMod(tone, c.person.size)]
    val parts = ProfileGifts.ART[kind] ?: ProfileGifts.ART.getValue("wrap")
    val paths = remember(kind) { parts.map { PathParser().parsePathString(it.d).toPath() to it.fill } }
    Box(
        modifier = modifier.size(size).clip(RoundedCornerShape(size * 0.22f)).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.68f)) {
            val k = this.size.width / VIEW
            scale(k, k, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                val line = PaintStroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                paths.forEach { (p, fill) -> drawGlyph(p, fill, ink, line) }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlyph(p: Path, fill: ProfileGifts.Fill, ink: Color, line: PaintStroke) {
    when (fill) {
        ProfileGifts.Fill.Soft -> drawPath(p, ink.copy(alpha = ink.alpha * 0.2f), style = PaintFill)
        ProfileGifts.Fill.Solid -> drawPath(p, ink, style = PaintFill)
        ProfileGifts.Fill.None -> Unit
    }
    drawPath(p, ink, style = line)
}

@Composable
private fun giftName(kind: String): String = stringResource(ProfileGifts.NAME[kind] ?: R.string.gift_notification)

/** Рядок «Подарунки» у «Я»: число й три останні; без подарунків його немає. */
@Composable
internal fun GiftsCard(gifts: List<ProfileGifts.Got>, onOpen: () -> Unit) {
    if (gifts.isEmpty()) return
    val c = LocalPhantomColors.current
    Row(
        modifier = Modifier
            .testTag("you-gifts")
            .fillMaxWidth()
            .clip(RoundedCornerShape(PhantomRadius.card))
            .background(c.raised)
            .clickable(role = Role.Button, onClick = onOpen)
            .height(PhantomSpace.s12 + PhantomSpace.s2)
            .padding(horizontal = PhantomSpace.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.profile_gifts),
            style = PhantomText.headline,
            color = c.text,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s1)) {
            gifts.take(3).forEach { g -> GiftGlyph(g.gift, PhantomSize.avatarXs, Modifier.testTag("you-gift-icon")) }
        }
        Spacer(Modifier.width(PhantomSpace.s3))
        Text(
            text = gifts.size.toString(),
            style = PhantomText.footnote.copy(fontFeatureSettings = "tnum"),
            color = c.text3,
        )
        Spacer(Modifier.width(PhantomSpace.s2))
        Icon(
            painter = painterResource(DesignR.drawable.lc_chevron_right),
            contentDescription = null,
            tint = c.text3,
            modifier = Modifier.size(PhantomSize.iconM),
        )
    }
}

/** Список отриманих: що, від кого, підпис. Рядки лише показують — відкрити лист у розмові рідний бік не вміє. */
@Composable
internal fun GiftsList(gifts: List<ProfileGifts.Got>, nameOf: (String) -> String?) {
    val c = LocalPhantomColors.current
    gifts.forEach { g ->
        Row(
            modifier = Modifier
                .testTag("gift-row")
                .fillMaxWidth()
                .heightIn(min = PhantomSize.listrowCompact)
                .padding(horizontal = PhantomSpace.gutterPhone, vertical = PhantomSpace.s2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
        ) {
            GiftGlyph(g.gift, PhantomSize.avatarM)
            Column(Modifier.weight(1f)) {
                Text(text = giftName(g.gift), style = PhantomText.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val from = nameOf(g.fromId)?.takeIf { it.isNotBlank() }
                val sub = listOfNotNull(from, g.note).joinToString(" · ")
                if (sub.isNotEmpty()) {
                    Text(text = sub, style = PhantomText.footnote, color = c.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
internal fun GiftsSheet(gifts: List<ProfileGifts.Got>, nameOf: (String) -> String?, onClose: () -> Unit) {
    SheetFrame(title = stringResource(R.string.profile_gifts), closeLabel = stringResource(R.string.profile_banner_close), onClose = onClose) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            GiftsList(gifts, nameOf)
        }
        Spacer(Modifier.height(PhantomSpace.s2))
    }
}
