package local.phantom.companion.ui.home

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import local.phantom.companion.R
import local.phantom.companion.data.ProfilesMirror
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.R as DesignR

private data class TabSpec(val tab: HomeTab, @DrawableRes val icon: Int, @StringRes val label: Int)

private val TABS = listOf(
    // Рішення власника 05.10: продукт — «месенджер, мапа й гаманець», звʼязок
    // першим. Перша вкладка — список розмов (Nightpost у ньому — закріплена
    // розмова з полем питання над списком), гаманець — своя вкладка, а не
    // рядок у Профілі. Домівка помічника 02.10 (PhantomHome) знята.
    TabSpec(HomeTab.CHATS, DesignR.drawable.lc_chats, R.string.home_tab_chats),
    TabSpec(HomeTab.MAP, DesignR.drawable.lc_map, R.string.home_tab_map),
    TabSpec(HomeTab.WALLET, DesignR.drawable.lc_wallet, R.string.home_tab_wallet),
    TabSpec(HomeTab.PROFILE, DesignR.drawable.lc_profile, R.string.home_tab_profile),
)

/** Розмови й «Я» є завжди; мапа й гаманець — лише якщо профіль їх тримає (`bar` null — усі). */
internal fun visibleTabs(bar: List<String>?): List<HomeTab> = HomeTab.entries.filter {
    when (it) {
        HomeTab.MAP -> bar == null || "map" in bar
        HomeTab.WALLET -> bar == null || "wallet" in bar
        else -> true
    }
}

/** Вкладки напряму, що мають значок у збірці; невідомий id із моста не малюється. */
internal val SLOT_ICONS: Map<String, Int> = mapOf(
    "tasks" to DesignR.drawable.lc_tasks,
    "shifts" to DesignR.drawable.lc_calendar,
    "inbox" to DesignR.drawable.lc_inbox,
    "orders" to DesignR.drawable.lc_orders,
    "services" to DesignR.drawable.lc_tag,
    "classes" to DesignR.drawable.lc_classes,
)

internal data class BarEntry(val tab: HomeTab?, val slot: ProfilesMirror.Slot?)

/** Розмови, [мапа], [гаманець], власні вкладки напряму за порядком моста, «Я». */
internal fun barEntries(bar: List<String>?, slots: List<ProfilesMirror.Slot>?): List<BarEntry> {
    val tabs = visibleTabs(bar)
    val own = slots.orEmpty().filter { it.id in SLOT_ICONS }.distinctBy { it.id }.map { BarEntry(null, it) }
    return tabs.filter { it != HomeTab.PROFILE }.map { BarEntry(it, null) } + own + BarEntry(HomeTab.PROFILE, null)
}

private val PillWidth = 56.dp
private val PillHeight = 30.dp
private val PillTop = 8.dp

@Composable
internal fun HomeBottomBar(
    selected: HomeTab,
    unreadChats: Int,
    bar: List<String>? = null,
    slots: List<ProfilesMirror.Slot>? = null,
    selectedSlot: String? = null,
    onSlot: (String) -> Unit = {},
    onSelect: (HomeTab) -> Unit,
    onProfileDoubleTap: () -> Unit = {},
) {
    val c = LocalPhantomColors.current
    val view = LocalView.current
    val profileTaps = remember { DoubleTap() }
    Column(modifier = Modifier.fillMaxWidth().background(c.canvas).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.line))
        Row(modifier = Modifier.fillMaxWidth().height(PhantomSize.bottomnavPhone).selectableGroup()) {
            val specs = barEntries(bar, slots).map { e ->
                e.tab?.let { t -> TABS.first { it.tab == t } to null } ?: (null to e.slot)
            }
            specs.forEach { (spec, slot) ->
                val on = if (slot != null) selected == HomeTab.CHATS && selectedSlot == slot.id
                else spec!!.tab == selected && !(spec.tab == HomeTab.CHATS && selectedSlot != null)
                val tint = if (on) c.text else c.text2
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = on,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                            onClick = {
                                if (!on) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                if (slot != null) onSlot(slot.id) else onSelect(spec!!.tab)
                                if (spec?.tab == HomeTab.PROFILE && profileTaps.tap(SystemClock.uptimeMillis())) {
                                    view.performHapticFeedback(
                                        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY,
                                    )
                                    onProfileDoubleTap()
                                }
                            },
                        )
                        .padding(top = PillTop),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top,
                ) {
                    SelectedPill(on = on) {
                        Icon(
                            painter = painterResource(slot?.let { SLOT_ICONS.getValue(it.id) } ?: spec!!.icon),
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(PhantomSize.iconL),
                        )
                        if (spec?.tab == HomeTab.CHATS && unreadChats > 0 && !on) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = (-14).dp, y = 3.dp)
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(c.accent),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = slot?.label ?: stringResource(spec!!.label),
                        style = PhantomText.caption,
                        fontWeight = FontWeight.Medium,
                        color = tint,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedPill(on: Boolean, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val c = LocalPhantomColors.current
    Box(
        Modifier
            .size(PillWidth, PillHeight)
            .clip(CircleShape)
            .background(if (on) c.overlay else Color.Transparent),
        contentAlignment = Alignment.Center,
        content = content,
    )
}
