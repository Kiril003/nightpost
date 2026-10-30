package local.phantom.companion.ui.scan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import local.phantom.companion.R
import local.phantom.companion.core.design.component.PrimaryButton
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.ui.home.ActionRow
import local.phantom.companion.ui.home.ProfileField
import local.phantom.companion.ui.home.RowGroup
import local.phantom.companion.ui.home.SubScreen

/**
 * «Ввести код» зі сканера: одне поле для будь-якого коду Nightpost. Розбір той
 * самий, що й у камери ([CodeRoute.classify]), і веде туди ж через [onCode].
 */
@Composable
internal fun CodeEntryScreen(
    onBack: () -> Unit,
    read: (String) -> CodeRoute?,
    onCode: (CodeRoute) -> ScanCard?,
    paste: (() -> String?)? = null,
) {
    val clipboard = LocalClipboardManager.current
    val fromClipboard = paste ?: { clipboard.getText()?.text }
    var text by remember { mutableStateOf("") }
    var card by remember { mutableStateOf<ScanCard?>(null) }

    fun submit(value: String) {
        val route = read(value)
        card = notOursCard(value, route) ?: route?.let(onCode)
    }

    SubScreen(stringResource(R.string.scan_enter_code), onBack) {
        Column {
            ProfileField(
                value = text,
                onValueChange = {
                    text = it
                    card = null
                },
                hint = stringResource(R.string.scan_code_field),
            )
            Spacer(Modifier.height(PhantomSpace.s4))
            RowGroup {
                ActionRow(
                    label = stringResource(R.string.scan_paste),
                    onClick = {
                        fromClipboard()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                            text = it
                            submit(it)
                        }
                    },
                )
            }
            card?.let {
                Spacer(Modifier.height(PhantomSpace.s4))
                RowGroup { ActionRow(label = stringResource(it.title)) }
            }
            Spacer(Modifier.height(PhantomSpace.s5))
            PrimaryButton(
                label = stringResource(R.string.scan_code_open),
                onClick = { submit(text) },
                enabled = text.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
