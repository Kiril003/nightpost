package local.phantom.companion.ui.home

import android.content.Context
import android.content.ContextWrapper
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import local.phantom.companion.MainActivity
import local.phantom.companion.PhantomApp
import local.phantom.companion.R
import local.phantom.companion.core.data.locale.AppLanguage
import local.phantom.companion.core.solana.SolanaLayer
import local.phantom.companion.core.solana.StatementOutcome
import local.phantom.companion.data.PointsClaims
import local.phantom.companion.data.VenueClient
import local.phantom.companion.core.design.R as DesignR

/**
 * Бали в Профілі: рядок із сумою, а за ним розділ — що саме й коли зараховано.
 *
 * Бали рахує лише сервер (`platform-site/server/points.py`); тут їх читають
 * (`POST /points/me` підписом пристрою) і показують. Кількість, яку називає
 * телефон, — ніде: ні в запиті, ні в тексті. Бали приватні — їх бачить лише
 * власник ключа; купити, передати чи вивести їх неможливо, і цей екран цього
 * не вигадує: нагород тут немає.
 *
 * Чесні порожні стани: сервера немає (збірка без нього) — рядка немає взагалі;
 * мережі немає — рядок каже «немає звʼязку», а не «0»; балів ще немає — розділ
 * каже, за що вони бувають.
 */
internal data class PointsEvent(val kind: String, val points: Int, val atMs: Long)

internal data class PointsView(val total: Int, val events: List<PointsEvent>)

internal sealed interface PointsLoad {
    data object Loading : PointsLoad
    data class Shown(val view: PointsView) : PointsLoad

    /** Сервер є, але не відповів. */
    data object Offline : PointsLoad

    /** Показувати нічого: сервера немає в цій збірці або в телефона немає ключа. */
    data object Hidden : PointsLoad
}

/** Що стоїть праворуч у рядку Профілю. */
internal sealed interface PointsRowValue {
    data object None : PointsRowValue
    data class Number(val n: Int) : PointsRowValue
    data class Word(@StringRes val res: Int) : PointsRowValue
}

/** Речення під кнопкою після «Зарахувати». */
internal data class PointsNote(@StringRes val res: Int, val arg: Int? = null, val server: String? = null)

internal object PointsPanel {

    /** Відповідь `/points/me` → вигляд; `null`, якщо відповідь — не те, що обіцяв сервер. */
    fun parse(json: String): PointsView? {
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return null
        val total = (root["total"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return null
        val raw = root["events"] as? JsonArray ?: return null
        val events = raw.map { e ->
            val o = e as? JsonObject ?: return null
            PointsEvent(
                kind = (o["kind"] as? JsonPrimitive)?.contentOrNull ?: return null,
                points = (o["points"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return null,
                atMs = (o["at"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return null,
            )
        }
        // Найновіші зверху: сервер віддає за зростанням часу.
        return PointsView(total, events.reversed())
    }

    fun load(answer: VenueClient.Answer): PointsLoad = when (answer) {
        is VenueClient.Answer.Ok -> parse(answer.json)?.let { PointsLoad.Shown(it) } ?: PointsLoad.Offline
        VenueClient.Answer.NotEnabled, VenueClient.Answer.NoKey, VenueClient.Answer.BadInput, VenueClient.Answer.Missing -> PointsLoad.Hidden
        VenueClient.Answer.Unreachable, is VenueClient.Answer.Refused -> PointsLoad.Offline
    }

    @StringRes
    fun kindLabel(kind: String): Int = when (kind) {
        "meetup.happened" -> R.string.points_kind_meetup
        "payment.first" -> R.string.points_kind_payment
        "invite.joined" -> R.string.points_kind_invite
        else -> R.string.points_kind_other
    }

    fun rowValue(load: PointsLoad, hasPending: Boolean): PointsRowValue = when {
        load is PointsLoad.Hidden -> PointsRowValue.None
        hasPending -> PointsRowValue.Word(R.string.points_value_settle)
        load is PointsLoad.Shown -> PointsRowValue.Number(load.view.total)
        load is PointsLoad.Offline -> PointsRowValue.Word(R.string.points_value_offline)
        else -> PointsRowValue.None
    }

    /** Рядок відображається, лише коли є що показати: без сервера бали — вигадка. */
    fun visible(load: PointsLoad): Boolean = load !is PointsLoad.Hidden

    fun note(outcome: StatementOutcome<PointsClaims.Settled>, english: Boolean): PointsNote = when (outcome) {
        StatementOutcome.Declined -> PointsNote(R.string.points_note_declined)
        StatementOutcome.Unsupported -> PointsNote(R.string.msgr_scene_points_built_in)
        StatementOutcome.NoWallet -> PointsNote(R.string.msgr_scene_no_wallet)
        is StatementOutcome.Signed -> when (val s = outcome.value) {
            PointsClaims.Settled.NothingToSettle -> PointsNote(R.string.points_note_nothing)
            PointsClaims.Settled.WalletChanged -> PointsNote(R.string.points_note_wallet_changed)
            is PointsClaims.Settled.Counted ->
                if (s.points > 0) PointsNote(R.string.points_note_counted, s.points) else PointsNote(R.string.points_note_already)
            PointsClaims.Settled.NotConfirmedYet -> PointsNote(R.string.points_note_not_confirmed)
            is PointsClaims.Settled.Refused ->
                PointsNote(R.string.msgr_scene_refused, server = if (english) s.en else s.uk)
            is PointsClaims.Settled.Failed -> when (val a = s.answer) {
                VenueClient.Answer.NoKey -> PointsNote(R.string.msgr_scene_no_key)
                VenueClient.Answer.NotEnabled -> PointsNote(R.string.msgr_scene_not_enabled)
                is VenueClient.Answer.Refused ->
                    if (a.status == 429) PointsNote(R.string.msgr_scene_limited)
                    else PointsNote(R.string.msgr_scene_refused, server = if (english) a.en else a.uk)
                else -> PointsNote(R.string.msgr_scene_unreachable)
            }
        }
    }
}

private fun Context.mainActivity(): MainActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is MainActivity) return c
        c = c.baseContext
    }
    return null
}

/** Власні бали з сервера; перечитується, коли [tick] росте. */
@Composable
private fun rememberPointsLoad(tick: Int): PointsLoad {
    val container = remember { PhantomApp.container() }
    var load by remember { mutableStateOf<PointsLoad>(PointsLoad.Loading) }
    LaunchedEffect(tick) {
        load = withContext(Dispatchers.IO) { PointsPanel.load(container.points.me()) }
    }
    return load
}

/** Рядок «Бали» в картці Налаштувань, з роздільником над собою. Немає що показати — немає й рядка. */
@Composable
internal fun PointsRow(go: (String) -> Unit) {
    val container = remember { PhantomApp.container() }
    val pending by container.pointsClaims.pending.collectAsStateWithLifecycle()
    // Заявка зʼявилась або зникла — суму перечитуємо.
    val load = rememberPointsLoad(tick = if (pending == null) 0 else 1)
    if (!PointsPanel.visible(load)) return
    val value = when (val v = PointsPanel.rowValue(load, hasPending = pending != null)) {
        PointsRowValue.None -> null
        is PointsRowValue.Number -> stringResource(R.string.profile_value_count, v.n)
        is PointsRowValue.Word -> stringResource(v.res)
    }
    RowDivider()
    NavRow(stringResource(R.string.profile_points), DesignR.drawable.lc_star, value = value) {
        go(ProfileSection.POINTS.route)
    }
}

/** Розділ «Бали»: сума, що зараховано, і — якщо є — оплата, яка чекає підтвердження. */
@Composable
internal fun PointsSection(back: () -> Unit) {
    val context = LocalContext.current
    val container = remember { PhantomApp.container() }
    val scope = rememberCoroutineScope()
    val pending by container.pointsClaims.pending.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    val load = rememberPointsLoad(tick)
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<PointsNote?>(null) }
    val english = remember(context) { AppLanguage.current(context) != AppLanguage.UK }
    val dates = remember { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM) }

    SubScreen(stringResource(R.string.profile_points), back) {
        when (val l = load) {
            PointsLoad.Loading -> Explainer(stringResource(R.string.points_loading))
            PointsLoad.Offline -> Explainer(stringResource(R.string.points_offline_body))
            PointsLoad.Hidden -> Explainer(stringResource(R.string.msgr_scene_not_enabled))
            is PointsLoad.Shown -> {
                RowGroup {
                    ActionRow(
                        label = stringResource(R.string.points_total),
                        action = stringResource(R.string.profile_value_count, l.view.total),
                        tone = RowTone.Quiet,
                    )
                }
                if (l.view.events.isEmpty()) {
                    Explainer(stringResource(R.string.points_empty))
                } else {
                    RowGroup {
                        l.view.events.forEachIndexed { i, e ->
                            if (i > 0) RowDivider()
                            ActionRow(
                                label = stringResource(PointsPanel.kindLabel(e.kind)),
                                body = dates.format(java.util.Date(e.atMs)),
                                action = "+${e.points}",
                                tone = RowTone.Done,
                            )
                        }
                    }
                }
            }
        }

        pending?.let {
            RowGroup {
                TextRow(
                    label = stringResource(R.string.points_pending_title),
                    body = stringResource(R.string.points_pending_body),
                )
                RowDivider()
                ActionRow(
                    label = stringResource(R.string.points_settle),
                    action = if (busy) stringResource(R.string.points_busy) else null,
                    onClick = if (busy) null else {
                        {
                            val activity = context.mainActivity()
                            val title = context.getString(R.string.points_confirm_title)
                            val detail = context.getString(R.string.points_confirm_detail)
                            scope.launch {
                                busy = true
                                note = null
                                val address = SolanaLayer.state.value.address?.base58
                                val outcome = if (activity == null) {
                                    StatementOutcome.NoWallet
                                } else {
                                    SolanaLayer.signStatement(
                                        confirm = { activity.walletEmbed.confirm(title, detail) },
                                    ) { sign -> container.pointsClaims.settle(address, sign) }
                                }
                                note = PointsPanel.note(outcome, english)
                                busy = false
                                tick++
                            }
                        }
                    },
                )
            }
        }
        note?.let { n ->
            Explainer(n.server ?: if (n.arg != null) stringResource(n.res, n.arg) else stringResource(n.res))
        }
        Explainer(stringResource(R.string.points_private))
    }
}
