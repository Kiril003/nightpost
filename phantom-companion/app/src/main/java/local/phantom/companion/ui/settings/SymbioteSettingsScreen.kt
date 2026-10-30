package local.phantom.companion.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow
import local.phantom.companion.PhantomApp
import local.phantom.companion.R
import local.phantom.companion.core.design.component.GlassCard
import local.phantom.companion.core.design.component.GlassLevel
import local.phantom.companion.core.design.component.PhantomBackPill
import local.phantom.companion.core.design.theme.LocalBottomChromeInset
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomCaption
import local.phantom.companion.core.design.theme.LocalStateAccent
import local.phantom.companion.core.design.theme.PhantomKicker
import local.phantom.companion.core.design.theme.PhantomMono
import local.phantom.companion.data.SymbiotePreferences

@Composable
fun SymbioteSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalPhantomTheme.current
    val accent = LocalStateAccent.current
    val context = LocalContext.current
    val container = (context.applicationContext as PhantomApp).container
    val prefs = container.symbiotePrefs

    Column(
        modifier = modifier
            .fillMaxWidth()
            .systemBarsPadding()
            .padding(bottom = LocalBottomChromeInset.current)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhantomBackPill(
                label = stringResource(R.string.settings_back),
                onBack = onBack,
            )
            Spacer(Modifier.weight(1f))
            PhantomKicker(
                text = stringResource(R.string.symbiote_kicker),
                color = accent.color,
            )
        }

        Spacer(Modifier.height(20.dp))

        // 30.08.2026 — сім із девʼяти перемикачів цього екрана НЕ МАЛИ ЖОДНОГО
        // ЧИТАЧА: posture, hrvTempo, stressGhost, baseline, doubtAmplifier,
        // conviction, park. Людина клацала, значення чесно лягало в
        // SharedPreferences — і на цьому все закінчувалось. Перемикач, що
        // нічого не змінює, гірший за його відсутність: порожній екран нічого
        // не обіцяє, а перемикач обіцяє, що щойно відбулась зміна.
        //
        // hrvTempo стоїть тут окремо, бо він найповчальніший: читач у нього Є
        // (SymbioteRuntime.applyHrv), і на нього навіть написаний тест. Але
        // applyHrv не кличе ніхто, крім того самого тесту. Зелений тест
        // доводив, що функція працює, і не доводив, що до неї доходять.
        //
        // Стан у SymbiotePreferences лишається цілим — жодне збережене
        // значення не втрачене. Зникає лише вхід.
        // ПОВЕРНЕННЯ: перемикач вертається на екран у тому ж коміті, в якому
        // зʼявляється його читач у робочому шляху, а не раніше.
        SymbioteSection(title = R.string.symbiote_group_body) {
            SymbioteToggle(
                label = R.string.symbiote_haptic,
                hint = R.string.symbiote_haptic_hint,
                flow = prefs.hapticEnabled, onChange = prefs::setHaptic,
            )
        }

        Spacer(Modifier.height(14.dp))
        SymbioteSection(title = R.string.symbiote_group_self) {
            SymbioteToggle(
                label = R.string.symbiote_audit,
                hint = R.string.symbiote_audit_hint,
                flow = prefs.auditEnabled, onChange = prefs::setAudit,
            )
        }
    }
}

@Composable
private fun SymbioteSection(
    @StringRes title: Int,
    content: @Composable () -> Unit,
) {
    val accent = LocalStateAccent.current
    PhantomKicker(
        text = stringResource(title),
        color = accent.color,
    )
    Spacer(Modifier.height(8.dp))
    GlassCard(level = GlassLevel.Card, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun SymbioteToggle(
    @StringRes label: Int,
    @StringRes hint: Int,
    flow: StateFlow<Boolean>,
    onChange: (Boolean) -> Unit,
) {
    val theme = LocalPhantomTheme.current
    val accent = LocalStateAccent.current
    val value by flow.collectAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(label),
                color = theme.inkStrong,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            PhantomCaption(
                text = stringResource(hint),
                color = theme.inkSecondary,
            )
        }
        Switch(
            checked = value,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = accent.color),
        )
    }
}
