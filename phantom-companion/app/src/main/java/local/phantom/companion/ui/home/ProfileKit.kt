package local.phantom.companion.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import local.phantom.companion.core.design.component.Explainer as KitExplainer
import local.phantom.companion.core.design.component.NavRow as KitNavRow
import local.phantom.companion.core.design.component.PaneSlot as KitPaneSlot
import local.phantom.companion.core.design.component.RowDivider as KitRowDivider
import local.phantom.companion.core.design.component.RowGroup as KitRowGroup
import local.phantom.companion.core.design.component.Segmented as KitSegmented
import local.phantom.companion.core.design.component.SubScreen as KitSubScreen
import local.phantom.companion.core.design.component.SwitchRow as KitSwitchRow

/*
 * The settings kit lives in core-design now (`component/PhantomKit.kt`), where
 * every module can reach it. These are the app's old names over it, kept so
 * ProfileSections, ProfileTab, MapSettingsScreen and AppLockSettings compile
 * untouched. Aliased imports, not same-name calls: a wrapper that called its
 * own name would recurse.
 */

@Composable
internal fun SubScreen(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) =
    KitSubScreen(title = title, onBack = onBack, content = content)

/** The one card a settings screen may have: a raised group of rows. */
@Composable
internal fun RowGroup(caption: String? = null, content: @Composable ColumnScope.() -> Unit) =
    KitRowGroup(caption = caption, content = content)

@Composable
internal fun RowDivider() = KitRowDivider()

@Composable
internal fun NavRow(
    label: String,
    @DrawableRes icon: Int? = null,
    value: String? = null,
    body: String? = null,
    onClick: () -> Unit,
) = KitNavRow(label = label, icon = icon, value = value, body = body, onClick = onClick)

/** Old settings panes keep their own rows; this only gives them the group's padding. */
@Composable
internal fun PaneSlot(content: @Composable () -> Unit) = KitPaneSlot(content = content)

@Composable
internal fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) =
    KitSegmented(options = options, selected = selected, onSelect = onSelect)

@Composable
internal fun Explainer(text: String) = KitExplainer(text = text)

/**
 * Той самий [local.phantom.companion.vm.PhantomViewModel], що й у MainActivity.
 *
 * Голий `viewModel()` у пункті навігації живе в межах цього пункту: «Вигляд →
 * Ніч» писав тему в ОКРЕМИЙ екземпляр, MainActivity її не бачила, і застосунок
 * лишався світлим, а рядок «Вигляд» у Профілі казав «за сонцем» (A52, 02.10).
 */
@Composable
internal fun activityPhantomVm(): local.phantom.companion.vm.PhantomViewModel {
    var ctx = androidx.compose.ui.platform.LocalContext.current
    while (ctx !is androidx.activity.ComponentActivity && ctx is android.content.ContextWrapper) ctx = ctx.baseContext
    val owner = ctx as? androidx.activity.ComponentActivity
    return if (owner != null) {
        androidx.lifecycle.viewmodel.compose.viewModel(
            viewModelStoreOwner = owner,
            factory = local.phantom.companion.vm.PhantomViewModel.Factory,
        )
    } else {
        androidx.lifecycle.viewmodel.compose.viewModel(factory = local.phantom.companion.vm.PhantomViewModel.Factory)
    }
}

/** Рядок-перемикач групи: усе тіло рядка — ціль дотику, сам перемикач лише показує стан. */
@Composable
internal fun SwitchRow(label: String, checked: Boolean, body: String? = null, onChange: (Boolean) -> Unit) =
    KitSwitchRow(label = label, checked = checked, body = body, onChange = onChange)
