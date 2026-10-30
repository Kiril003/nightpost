package local.phantom.companion.feature.stream.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.net.peer.PeerSafety
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.peers.PersonTone

/**
 * Обличчя людини тоном з її ключа — тим самим, що в месенджері й на мапі
 * (`PersonTone`, ключ — id вузла): людину, яку в розмовах впізнають за кольором,
 * її власна картка мусить упізнавати так само.
 *
 * Один доказ — одне кільце. Ключ, звірений лише цифрами, отримує тьмяне: він
 * менше вартий, і виглядати мусить менше.
 */
@Composable
internal fun PeerFaceDisc(peerId: String, name: String, trust: PeerChatFormat.Trust, size: Dp) {
    val c = LocalPhantomColors.current
    val (fill, ink) = c.person[PersonTone.index(peerId) % c.person.size]
    val ring = when (trust) {
        PeerChatFormat.Trust.IN_PERSON -> Modifier.border(2.dp, c.success, CircleShape)
        PeerChatFormat.Trust.MATCHED -> Modifier.border(1.dp, c.lineStrong, CircleShape)
        else -> Modifier
    }
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(fill).then(ring),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = PeerChatFormat.monogram(name), style = PhantomText.display, color = ink)
    }
}

/**
 * Число цієї розмови — три рядки по чотири групи, щоб одним поглядом звірити
 * два екрани поруч. Null означає, що телефон ще не прочитав власного ключа, і
 * це сказано словом, а не намальовано порожнечею.
 */
@Composable
internal fun SafetyNumberLines(number: String?, modifier: Modifier = Modifier) {
    val c = LocalPhantomColors.current
    if (number == null) {
        Text(
            text = stringResource(R.string.peer_card_safety_pending).sentence(),
            style = PhantomText.footnote,
            color = c.text3,
            modifier = modifier,
        )
        return
    }
    Column(modifier = modifier) {
        PeerSafety.lines(number).forEach { line ->
            Text(text = line, style = DIGITS, color = c.text)
        }
    }
}

private val DIGITS = PhantomText.title2.copy(fontFeatureSettings = "tnum")
