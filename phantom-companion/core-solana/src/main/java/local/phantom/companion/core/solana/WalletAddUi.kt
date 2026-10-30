package local.phantom.companion.core.solana

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import local.phantom.companion.core.design.component.GhostButton
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.R as DesignR

/** Чим можна поповнити карусель; кожен пункт веде в наявний шлях гаманця. */
internal enum class AddWhat { TOKEN, WATCH }

/**
 * Аркуш «Додати картку»: токен за адресою, адреса «лише дивитись», свій гаманець через
 * Mobile Wallet Adapter, відновлення словами — і сховані картки, щоб їх повернути.
 */
@Composable
internal fun AddSheet(
    hidden: List<String>,
    builtIn: Boolean,
    seeker: Boolean,
    onAdd: (AddWhat, SolanaKey) -> Unit,
    onConnect: () -> Unit,
    onRestore: () -> Unit,
    onRestoreFile: () -> Unit,
    onShow: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var asking by remember { mutableStateOf<AddWhat?>(null) }
    val ask = asking
    if (ask != null) {
        AddressAsk(ask, onDone = { key -> onAdd(ask, key); onDismiss() }, onBack = { asking = null })
        return
    }
    WalletSheet(title = stringResource(R.string.sol_add_card), onDismiss = onDismiss) {
        AddRow(DesignR.drawable.lc_layers, stringResource(R.string.sol_add_token), stringResource(R.string.sol_add_token_hint)) { asking = AddWhat.TOKEN }
        AddRow(DesignR.drawable.lc_bookmark, stringResource(R.string.sol_add_watch), stringResource(R.string.sol_add_watch_hint)) { asking = AddWhat.WATCH }
        if (builtIn) {
            AddRow(
                DesignR.drawable.lc_wallet,
                stringResource(if (seeker) R.string.sol_seed_vault else R.string.sol_connect_external),
                stringResource(R.string.sol_add_connect_hint),
            ) { onDismiss(); onConnect() }
            AddRow(DesignR.drawable.lc_download, stringResource(R.string.sol_restore), stringResource(R.string.sol_restore_hint)) { onDismiss(); onRestore() }
            AddRow(DesignR.drawable.lc_download, stringResource(R.string.sol_copy_restore), stringResource(R.string.sol_copy_restore_hint)) { onDismiss(); onRestoreFile() }
        }
        if (hidden.isNotEmpty()) {
            WalletLine(stringResource(R.string.sol_add_hidden))
            hidden.forEach { key ->
                AddRow(DesignR.drawable.lc_check, stringResource(R.string.sol_add_show, CardNames.of(key)), null) { onShow(key) }
            }
        }
        GhostButton(label = stringResource(R.string.sol_cancel), onClick = onDismiss, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun AddressAsk(what: AddWhat, onDone: (SolanaKey) -> Unit, onBack: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    val title = stringResource(if (what == AddWhat.TOKEN) R.string.sol_add_token else R.string.sol_add_watch)
    WalletSheet(title = title, onDismiss = onBack) {
        WalletLine(stringResource(if (what == AddWhat.TOKEN) R.string.sol_add_token_hint else R.string.sol_add_watch_hint))
        WordsField(text, { text = it; bad = false }, stringResource(R.string.sol_add_address), if (bad) stringResource(R.string.sol_add_bad_address) else null)
        if (bad) Text(stringResource(R.string.sol_add_bad_address), style = PhantomText.footnote, color = LocalPhantomColors.current.danger)
        SheetActions(
            confirm = stringResource(R.string.sol_add_confirm),
            onConfirm = { SolanaKey.parse(text.trim())?.let(onDone) ?: run { bad = true } },
            dismiss = stringResource(R.string.sol_cancel),
            onDismiss = onBack,
            enabled = text.isNotBlank(),
        )
    }
}

@Composable
private fun AddRow(icon: Int, title: String, body: String?, onClick: () -> Unit) {
    val c = LocalPhantomColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PhantomSize.listrowCompact)
            .clip(RoundedCornerShape(PhantomRadius.control))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = PhantomSpace.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
    ) {
        Icon(painterResource(icon), null, tint = c.text2, modifier = Modifier.size(PhantomSize.iconM))
        Column(Modifier.weight(1f)) {
            Text(title, style = PhantomText.callout, color = c.text)
            if (body != null) Text(body, style = PhantomText.footnote, color = c.text3)
        }
    }
}

/** Довгий натиск на картку: зсунути раніше чи пізніше або сховати. */
@Composable
internal fun ArrangeSheet(name: String, earlier: Boolean, later: Boolean, onMove: (Int) -> Unit, onHide: () -> Unit, onDismiss: () -> Unit) {
    WalletSheet(title = name, onDismiss = onDismiss) {
        if (earlier) AddRow(DesignR.drawable.lc_back, stringResource(R.string.sol_arrange_earlier), null) { onMove(-1); onDismiss() }
        if (later) AddRow(DesignR.drawable.lc_chevron_right, stringResource(R.string.sol_arrange_later), null) { onMove(1); onDismiss() }
        AddRow(DesignR.drawable.lc_close, stringResource(R.string.sol_arrange_hide), stringResource(R.string.sol_arrange_hide_hint)) { onHide(); onDismiss() }
        GhostButton(label = stringResource(R.string.sol_cancel), onClick = onDismiss, modifier = Modifier.fillMaxWidth())
    }
}

/** Людська назва картки за її ключем — для схованих і для аркуша впорядкування. */
internal object CardNames {
    @Composable
    fun of(key: String): String = when {
        key == "points" -> stringResource(R.string.sol_card_points)
        key.startsWith("coin:") -> Coin.of(key.removePrefix("coin:"))?.symbol ?: key
        key.startsWith("mint:") -> stringResource(R.string.sol_card_token) + " " + short(key.removePrefix("mint:"))
        key.startsWith("watch:") -> stringResource(R.string.sol_card_watch) + " " + short(key.removePrefix("watch:"))
        else -> key
    }

    private fun short(b58: String) = b58.take(4) + "…" + b58.takeLast(4)
}
