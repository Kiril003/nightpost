package local.phantom.companion.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.phantom.companion.PhantomApp
import local.phantom.companion.R
import local.phantom.companion.core.design.component.PhantomDialog
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolRisk
import local.phantom.companion.core.tool.diary.DecisionEntry
import local.phantom.companion.core.tool.permission.Decision
import local.phantom.companion.core.tool.permission.ToolPermissionStore
import local.phantom.companion.ui.home.RowDivider
import local.phantom.companion.ui.home.RowGroup
import local.phantom.companion.ui.home.Segmented
import local.phantom.companion.ui.home.SubScreen

/**
 * «Налаштування → Інструменти» — екран, який до 12.09 не існував, хоча текст
 * відмови воріт посилав оператора саме сюди.
 *
 * Тут же єдиний читач журналу викликів: `DecisionDiary.recent()` не мав жодного
 * споживача в продакшені, тобто слід писався в порожнечу.
 *
 * Вигляд — той самий, що підекрани «Профілю» (мова форми 1.2): верхня смуга зі
 * стрілкою назад замість «←» внизу списку, піднята група на кожен рівень ризику,
 * рішення — перемикач на три положення замість трьох голих TextButton.
 */
@Composable
fun ToolsSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = (context.applicationContext as PhantomApp).container
    val c = LocalPhantomColors.current
    val scope = rememberCoroutineScope()

    val tools = remember(container) { ToolLabels.decidable(container.toolRegistry.list()) }
    var decisions by remember { mutableStateOf(emptyMap<String, Decision>()) }
    var journal by remember { mutableStateOf(emptyList<DecisionEntry>()) }
    var bulk by remember { mutableStateOf<Decision?>(null) }

    LaunchedEffect(tools) {
        val (loadedDecisions, loadedJournal) = withContext(Dispatchers.IO) {
            val map = tools.associate { it.id to container.toolPermissionStore.decision(it.id) }
            val recent = container.toolDecisionDiary.recent(50)
            map to recent
        }
        decisions = loadedDecisions
        journal = loadedJournal
    }

    val groups = listOf(
        R.string.tools_group_outward to tools.filter { it.risk == ToolRisk.OUTWARD },
        R.string.tools_group_device to tools.filter { it.risk == ToolRisk.DEVICE },
    )

    val labels = decisionLabels()
    SubScreen(title = stringResource(R.string.tools_screen_title), onBack = onBack) {
        if (tools.isNotEmpty()) {
            RowGroup(caption = stringResource(R.string.tools_all_caption)) {
                Segmented<Decision?>(
                    options = labels,
                    selected = ToolBulk.common(decisions, tools.map { it.id }),
                    onSelect = { bulk = it },
                )
            }
        }
        groups.forEach { (titleRes, group) ->
            val rows = group.mapNotNull { tool ->
                val label = toolLabel(tool) ?: return@mapNotNull null
                tool to label
            }
            if (rows.isEmpty()) return@forEach
            RowGroup(caption = stringResource(titleRes)) {
                rows.forEachIndexed { i, (tool, label) ->
                    if (i > 0) RowDivider()
                    ToolRow(
                        label = label,
                        decision = decisions[tool.id] ?: Decision.ASK,
                        onSet = { d ->
                            decisions = decisions + (tool.id to d)
                            scope.launch(Dispatchers.IO) {
                                container.toolPermissionStore.setDecision(tool.id, d)
                            }
                        },
                    )
                }
            }
        }

        RowGroup(caption = stringResource(R.string.tools_journal_title)) {
            if (journal.isEmpty()) {
                Text(
                    text = stringResource(R.string.tools_journal_empty),
                    style = PhantomText.callout,
                    color = c.text3,
                    modifier = Modifier.padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s3),
                )
            } else {
                journal.forEachIndexed { i, entry ->
                    if (i > 0) RowDivider()
                    JournalRow(entry)
                }
            }
        }
    }

    bulk?.let { d ->
        PhantomDialog(
            title = stringResource(R.string.tools_all_confirm_title, labels.first { it.first == d }.second),
            confirmLabel = stringResource(R.string.tools_all_apply),
            dismissLabel = stringResource(R.string.action_cancel),
            onDismiss = { bulk = null },
            onConfirm = {
                bulk = null
                decisions = tools.associate { it.id to d }
                scope.launch(Dispatchers.IO) { ToolBulk.apply(container.toolPermissionStore, tools.map { it.id }, d) }
            },
        )
    }
}

internal object ToolBulk {
    fun common(decisions: Map<String, Decision>, ids: List<String>): Decision? =
        ids.map { decisions[it] ?: Decision.ASK }.distinct().singleOrNull()

    fun apply(store: ToolPermissionStore, ids: List<String>, decision: Decision) {
        if (decision == Decision.ASK) store.clearAll() else ids.forEach { store.setDecision(it, decision) }
    }
}

@Composable
private fun decisionLabels(): List<Pair<Decision, String>> = listOf(
    Decision.ASK to stringResource(R.string.tools_decision_ask),
    Decision.ALWAYS to stringResource(R.string.tools_decision_always),
    Decision.NEVER to stringResource(R.string.tools_decision_never),
)

@Composable
private fun toolLabel(tool: Tool): String? {
    val res = ToolLabels.resId(tool.id)
    val raw = if (res != null) stringResource(res) else ToolLabels.fallbackLabel(tool)
    return raw?.takeIf { it.isNotBlank() }
}

@Composable
internal fun ToolRow(label: String, decision: Decision, onSet: (Decision) -> Unit) {
    val c = LocalPhantomColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = PhantomText.body,
            color = c.text,
            modifier = Modifier.padding(start = PhantomSpace.s4, end = PhantomSpace.s4, top = PhantomSpace.s3),
        )
        Segmented(
            options = decisionLabels(),
            selected = decision,
            onSelect = onSet,
        )
    }
}

private val JOURNAL_CLOCK = SimpleDateFormat("dd.MM HH:mm", Locale("uk"))

@Composable
private fun JournalRow(entry: DecisionEntry) {
    val c = LocalPhantomColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.rowV),
    ) {
        Text(
            text = stringResource(
                R.string.tools_journal_entry,
                JOURNAL_CLOCK.format(Date(entry.timestamp)),
                entry.toolId,
            ),
            style = PhantomText.callout.copy(fontFeatureSettings = "tnum"),
            color = c.text,
        )
        Text(
            // «за чиїм словом» — без цього рядок каже, що спрацювало, і не каже,
            // чи хтось дозволяв.
            text = listOfNotNull(entry.risk, entry.decision, entry.outcome)
                .joinToString(" · ") + " · " + entry.outputDigest.take(60),
            style = PhantomText.footnote,
            color = c.text3,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
