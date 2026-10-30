package local.phantom.companion.core.design.kit

import android.view.Gravity
import android.view.WindowManager
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import local.phantom.companion.core.design.R
import local.phantom.companion.core.design.component.rememberDialogRoom
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace

enum class ListRowHeight { One, Two, Chat }

enum class AmountSign { Out, In, None }

object LookKit {
    fun rowHeight(height: ListRowHeight): Dp = when (height) {
        ListRowHeight.One -> PhantomSpace.s12 + PhantomSpace.s2
        ListRowHeight.Two -> PhantomSpace.s16
        ListRowHeight.Chat -> PhantomSize.listrow
    }

    fun buttonHeight(large: Boolean): Dp = if (large) PhantomSize.buttonL else PhantomSize.buttonM
    val ChipHeight: Dp = PhantomSpace.s6
    val HandleWidth: Dp = PhantomSpace.s8
    val HandleHeight: Dp = PhantomSpace.s1
    val SheetHeader: Dp = PhantomSpace.touchMin
    val CloseSize: Dp = PhantomSize.buttonM
}

object LookAmount {
    fun signed(digits: String, sign: AmountSign): String = when (sign) {
        AmountSign.Out -> "\u2212$digits"
        AmountSign.In -> "+$digits"
        AmountSign.None -> digits
    }
}

@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    height: ListRowHeight = ListRowHeight.Two,
    sub: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    divider: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    padded: Boolean = true,
    subTone: Color = Color.Unspecified,
) {
    val c = LocalPhantomColors.current
    val side = if (padded) PhantomSpace.s4 else PhantomSpace.s0
    val inset = side + (if (leading != null) PhantomSize.avatarM + PhantomSpace.s3 else PhantomSpace.s0)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LookKit.rowHeight(height))
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                } else {
                    Modifier.semantics(mergeDescendants = true) {}
                },
            )
            .then(
                if (divider) {
                    Modifier.drawBehind {
                        drawLine(
                            color = c.line,
                            start = Offset(inset.toPx(), size.height),
                            end = Offset(size.width, size.height),
                            strokeWidth = Stroke.HairlineWidth,
                        )
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = side, vertical = PhantomSpace.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
    ) {
        leading?.invoke()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = PhantomText.headline,
                color = if (enabled) c.text else c.text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sub != null) {
                Text(
                    text = sub,
                    style = PhantomText.footnote,
                    color = subTone.takeOrElse { c.text2 },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun StatusChip(label: String, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null) {
    val c = LocalPhantomColors.current
    Row(
        modifier = modifier
            .height(LookKit.ChipHeight)
            .clip(RoundedCornerShape(PhantomRadius.chip))
            .background(c.overlay)
            .padding(horizontal = PhantomSpace.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s1),
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = c.text2,
                modifier = Modifier.size(PhantomSize.iconS),
            )
        }
        Text(
            text = label,
            style = PhantomText.caption,
            color = c.text2,
            maxLines = 1,
        )
    }
}

@Composable
fun SheetFrame(
    title: String,
    closeLabel: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    headerEnd: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    secure: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalPhantomColors.current
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            securePolicy = if (secure) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit,
        ),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        // Dialog re-applies its own window size after composition; a one-shot effect loses.
        SideEffect {
            window?.setGravity(Gravity.BOTTOM)
            window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        val room = rememberDialogRoom()
        Column(
            modifier = modifier
                .fillMaxWidth()
                .then(if (room == null) Modifier else Modifier.heightIn(max = with(LocalDensity.current) { room.visibleHeight.toDp() }))
                .clip(RoundedCornerShape(topStart = PhantomRadius.sheet, topEnd = PhantomRadius.sheet))
                .background(c.raised)
                .semantics { paneTitle = title }
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = PhantomSpace.s2)
                    .size(LookKit.HandleWidth, LookKit.HandleHeight)
                    .clip(CircleShape)
                    .background(c.lineStrong)
                    .testTag("look-sheet-handle"),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = LookKit.SheetHeader)
                    .padding(start = PhantomSpace.s4, end = PhantomSpace.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = PhantomText.title2,
                    color = c.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                headerEnd?.invoke()
                Box(
                    modifier = Modifier
                        .size(PhantomSize.buttonL)
                        .clickable(role = Role.Button, onClick = onClose)
                        .semantics { contentDescription = closeLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(LookKit.CloseSize)
                            .clip(CircleShape)
                            .background(c.overlay),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.lc_close),
                            contentDescription = null,
                            tint = c.text,
                            modifier = Modifier.size(PhantomSize.iconM),
                        )
                    }
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = PhantomSpace.s4),
                content = content,
            )
            if (footer != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s3),
                ) {
                    footer()
                }
            }
        }
    }
}

@Composable
fun AmountText(
    digits: String,
    sign: AmountSign,
    modifier: Modifier = Modifier,
    style: TextStyle = PhantomText.callout,
) {
    val c = LocalPhantomColors.current
    Text(
        text = LookAmount.signed(digits, sign),
        style = style.copy(fontFeatureSettings = "tnum"),
        color = if (sign == AmountSign.In) c.success else c.text,
        maxLines = 1,
        modifier = modifier,
    )
}
