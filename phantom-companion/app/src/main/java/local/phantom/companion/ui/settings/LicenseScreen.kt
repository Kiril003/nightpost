package local.phantom.companion.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import local.phantom.companion.PhantomApp
import local.phantom.companion.R
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.license.AvailableBuild
import local.phantom.companion.core.license.LicenseFeature
import local.phantom.companion.core.license.LicenseReason
import local.phantom.companion.core.license.LicenseTier
import local.phantom.companion.license.Checkout
import local.phantom.companion.license.CheckoutStatus
import local.phantom.companion.license.IntegritySentry
import local.phantom.companion.license.LicenseService
import local.phantom.companion.ui.home.Explainer
import local.phantom.companion.ui.home.RowDivider
import local.phantom.companion.ui.home.RowGroup
import local.phantom.companion.ui.home.SubScreen
import local.phantom.companion.core.design.R as DesignR

/** Рівні цифри для відбитків і сум: 64 знаки звіряють очима, рядок за рядком. */
private val Hex = PhantomText.footnote.copy(fontFeatureSettings = "tnum")

/**
 * Ліцензія — підекран тієї самої мови, що й «Профіль» (мова форми 1.2): верхня
 * смуга зі стрілкою, підняті групи замість скляних карток, бурштин — рівно на
 * одній дії. У store-збірці це «Купити» (Google Play), у польовій купівлі немає
 * (checkout == null), і головна дія — «Активувати» ключ.
 *
 * `imePadding` над усім підекраном: тут вводять ключ, а застосунок малює під
 * системними смугами, тож без нього поле лишалось під клавіатурою.
 */
@Composable
fun LicenseScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    val service = remember { PhantomApp.container().license }
    val verdict by service.state.collectAsStateWithLifecycle()
    val announced by service.announced.collectAsStateWithLifecycle()
    val signature = remember(service) { SignatureFacts.of(service.integrity) }
    val checkout = remember { PhantomApp.container().checkout }
    val scope = rememberCoroutineScope()

    var keyInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val errNetwork = stringResource(R.string.license_err_network)
    val errMalformed = stringResource(R.string.license_err_malformed)
    val errNotFound = stringResource(R.string.license_err_notfound)
    val errLimit = stringResource(R.string.license_err_limit)
    val errRevoked = stringResource(R.string.license_err_revoked)

    Box(modifier = modifier.fillMaxSize().imePadding()) {
        SubScreen(title = stringResource(R.string.license_title), onBack = onBack) {
            Explainer(stringResource(R.string.license_kicker).sentence())

            signature?.let { facts ->
                RowGroup(caption = stringResource(R.string.license_signature_section)) {
                    Padded {
                        Text(
                            text = stringResource(R.string.license_signature_foreign),
                            style = PhantomText.headline,
                            color = c.danger,
                        )
                        Spacer(modifier = Modifier.height(PhantomSpace.s1))
                        Text(
                            text = stringResource(R.string.license_signature_meaning),
                            style = PhantomText.footnote,
                            color = c.text2,
                        )
                        Spacer(modifier = Modifier.height(PhantomSpace.s3))
                        Text(
                            text = stringResource(R.string.license_signature_found),
                            style = PhantomText.footnote,
                            color = c.text3,
                        )
                        // 64 шістнадцяткових знаки не стають в один рядок за жодного
                        // масштабу шрифту, а обрізаний відбиток нема з чим звіряти.
                        Text(text = facts.found, style = Hex, color = c.text, maxLines = 4)
                        Spacer(modifier = Modifier.height(PhantomSpace.s2))
                        Text(
                            text = stringResource(R.string.license_signature_expected),
                            style = PhantomText.footnote,
                            color = c.text3,
                        )
                        Text(text = facts.expected, style = Hex, color = c.text, maxLines = 4)
                    }
                }
            }

            RowGroup {
                Padded {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(tierLabel(verdict.tier)),
                            style = PhantomText.title1,
                            color = c.text,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(PhantomSpace.s3))
                        val good = verdict.reason == LicenseReason.Licensed ||
                            verdict.reason == LicenseReason.Trial
                        Text(
                            text = stringResource(LicenseService.explain(verdict.reason)),
                            style = PhantomText.footnote,
                            fontWeight = FontWeight.SemiBold,
                            color = if (good) c.success else c.text3,
                        )
                    }
                    Spacer(modifier = Modifier.height(PhantomSpace.s2))
                    Text(
                        text = stringResource(R.string.license_safety_note),
                        style = PhantomText.footnote,
                        color = c.text2,
                    )
                    verdict.trialDaysLeft?.let { left ->
                        Spacer(modifier = Modifier.height(PhantomSpace.s2))
                        Text(
                            text = stringResource(R.string.license_trial_left, left),
                            style = PhantomText.footnote,
                            color = c.text3,
                        )
                    }
                    verdict.certificate?.let { cert ->
                        Spacer(modifier = Modifier.height(PhantomSpace.s2))
                        Text(
                            text = stringResource(R.string.license_updates_until, cert.updatesUntil),
                            style = PhantomText.footnote,
                            color = c.text3,
                        )
                    }
                    verdict.graceDaysLeft?.let { left ->
                        Spacer(modifier = Modifier.height(PhantomSpace.s1))
                        Text(
                            text = stringResource(R.string.license_grace_left, left),
                            style = PhantomText.footnote,
                            color = c.text3,
                        )
                    }
                    verdict.notice?.let { notice ->
                        Spacer(modifier = Modifier.height(PhantomSpace.s2))
                        Text(text = notice, style = PhantomText.callout, color = c.text)
                    }
                }
            }

            RowGroup(caption = stringResource(R.string.update_section)) {
                Padded {
                    when (
                        val section = UpdateSection.of(
                            developerBuild = service.developerBuild,
                            announced = announced,
                            licensed = verdict.certificate != null,
                            viaDappStore = local.phantom.companion.Distribution.viaDappStore,
                            viaPlay = local.phantom.companion.Distribution.viaPlay,
                        )
                    ) {
                        UpdateSection.ViaDappStore -> Text(
                            text = stringResource(R.string.update_via_dapp_store),
                            style = PhantomText.callout,
                            color = c.text2,
                        )
                        UpdateSection.ViaPlay -> Text(
                            text = stringResource(R.string.update_via_play),
                            style = PhantomText.callout,
                            color = c.text2,
                        )
                        UpdateSection.DeveloperBuild -> Text(
                            text = stringResource(R.string.update_developer),
                            style = PhantomText.callout,
                            color = c.text2,
                        )
                        UpdateSection.Newest -> Text(
                            text = stringResource(R.string.update_none),
                            style = PhantomText.callout,
                            color = c.text2,
                        )
                        is UpdateSection.Offer ->
                            local.phantom.companion.ui.UpdateDetails(build = section.build)
                        is UpdateSection.Locked -> Text(
                            text = stringResource(R.string.update_locked, section.build.version),
                            style = PhantomText.callout,
                            color = c.text2,
                        )
                    }
                }
            }

            // Купівля в застосунку — лише store-збірка (Google Play Billing).
            // У польовій checkout == null, і секції немає зовсім.
            checkout?.let { CheckoutSection(it) }

            RowGroup(caption = stringResource(R.string.license_key_label)) {
                Padded {
                    KeyField(value = keyInput, onValueChange = { keyInput = it.uppercase() })
                    Spacer(modifier = Modifier.height(PhantomSpace.s3))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Бурштин — у головної дії екрана. У польовій збірці це
                        // «Активувати»; у store головна — «Купити», а ключ іде другим.
                        Pill(
                            label = stringResource(R.string.license_activate),
                            kind = if (checkout == null) PillKind.Primary else PillKind.Soft,
                            enabled = !busy && keyInput.isNotBlank(),
                        ) {
                            busy = true
                            message = null
                            scope.launch {
                                val result = service.activate(keyInput)
                                busy = false
                                message = result.fold(
                                    onSuccess = { keyInput = ""; null },
                                    onFailure = { failure ->
                                        when {
                                            failure is LicenseService.LicenseError.Malformed ->
                                                errMalformed
                                            failure is LicenseService.LicenseError.Server &&
                                                failure.code == 404 -> errNotFound
                                            failure is LicenseService.LicenseError.Server &&
                                                failure.code == 409 -> errLimit
                                            failure is LicenseService.LicenseError.Server &&
                                                failure.code == 403 -> errRevoked
                                            else -> errNetwork
                                        }
                                    },
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(PhantomSpace.s2))
                        Pill(
                            label = stringResource(R.string.license_recheck),
                            kind = PillKind.Quiet,
                            enabled = !busy,
                        ) {
                            busy = true
                            scope.launch {
                                service.checkIn(force = true)
                                busy = false
                            }
                        }
                    }
                    // Гейт саме на сертифікаті, а не на `paid`: у пробі рівень уже
                    // «Особистий», тож руйнівна кнопка стояла поруч із головною
                    // дією й не робила нічого — звільняти не було чого.
                    if (verdict.certificate != null) {
                        Spacer(modifier = Modifier.height(PhantomSpace.s2))
                        ConfirmPill(
                            label = stringResource(R.string.license_release),
                            confirmLabel = stringResource(R.string.license_release_confirm),
                            onConfirm = { scope.launch { service.release() } },
                        )
                    }
                    message?.let { text ->
                        Spacer(modifier = Modifier.height(PhantomSpace.s2))
                        Text(text = text, style = PhantomText.footnote, color = c.danger)
                    }
                }
            }

            RowGroup(caption = stringResource(R.string.license_features_title)) {
                LicenseFeature.entries.forEachIndexed { i, feature ->
                    if (i > 0) RowDivider()
                    FeatureRow(
                        label = stringResource(featureLabel(feature)),
                        open = verdict.has(feature),
                        // У пробі відкрито ВСЕ, і суцільний стовпчик галочок нічого
                        // не каже про ключ. Позначка показує, що саме зникне.
                        endsWithTrial = verdict.trialOnly(feature),
                    )
                }
            }

            Explainer(
                stringResource(
                    R.string.license_device_id,
                    service.deviceFingerprint.take(12),
                ),
            )
        }
    }
}

@Composable
private fun CheckoutSection(checkout: Checkout) {
    val c = LocalPhantomColors.current
    val context = LocalContext.current
    val offers by checkout.offers.collectAsStateWithLifecycle()
    val status by checkout.status.collectAsStateWithLifecycle()
    LaunchedEffect(checkout) { checkout.start() }
    val working = status == CheckoutStatus.Working

    RowGroup(caption = stringResource(R.string.checkout_section)) {
        Padded {
            Text(text = stringResource(R.string.checkout_note), style = PhantomText.footnote, color = c.text2)
            if (offers.isEmpty() && status != CheckoutStatus.Unavailable) {
                Spacer(modifier = Modifier.height(PhantomSpace.s2))
                Text(text = stringResource(R.string.checkout_loading), style = PhantomText.footnote, color = c.text3)
            }
        }
        offers.forEach { offer ->
            RowDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.rowV),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = offer.title, style = PhantomText.headline, color = c.text)
                    Text(
                        text = offer.price,
                        style = PhantomText.footnote.copy(fontFeatureSettings = "tnum"),
                        color = c.text2,
                    )
                }
                Spacer(modifier = Modifier.width(PhantomSpace.s2))
                // Store: купівля — головна дія екрана, єдина бурштинова заливка.
                Pill(
                    label = stringResource(R.string.checkout_buy),
                    kind = PillKind.Primary,
                    enabled = !working,
                ) {
                    context.findActivity()?.let { checkout.buy(it, offer.productId) }
                }
            }
        }
        RowDivider()
        Padded {
            Pill(
                label = stringResource(R.string.checkout_restore),
                kind = PillKind.Quiet,
                enabled = !working,
            ) { checkout.restore() }
            checkoutMessage(status)?.let { text ->
                Spacer(modifier = Modifier.height(PhantomSpace.s2))
                Text(
                    text = text,
                    style = PhantomText.footnote,
                    color = if (status is CheckoutStatus.Failed) c.danger else c.text2,
                )
            }
        }
    }
}

@Composable
private fun Padded(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s3),
        content = content,
    )
}

@Composable
private fun FeatureRow(label: String, open: Boolean, endsWithTrial: Boolean) {
    val c = LocalPhantomColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PhantomSpace.touchMinAndroid)
            .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(PhantomSize.iconM), contentAlignment = Alignment.Center) {
            if (open) {
                Icon(
                    painter = painterResource(DesignR.drawable.lc_check),
                    contentDescription = null,
                    tint = c.success,
                    modifier = Modifier.size(PhantomSize.iconS),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(c.lineStrong),
                )
            }
        }
        Spacer(modifier = Modifier.width(PhantomSpace.s3))
        Text(
            text = label,
            style = PhantomText.body,
            color = if (open) c.text else c.text3,
            modifier = Modifier.weight(1f),
        )
        if (endsWithTrial) {
            Spacer(modifier = Modifier.width(PhantomSpace.s2))
            Text(
                text = stringResource(R.string.license_feature_trial_only).sentence(),
                style = PhantomText.footnote,
                color = c.text3,
            )
        }
    }
}

/** Поле ключа: поверхня surface з лінією; у фокусі лінія бурштинова й товща. */
@Composable
private fun KeyField(value: String, onValueChange: (String) -> Unit) {
    val c = LocalPhantomColors.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(PhantomRadius.control)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = PhantomText.body.copy(color = c.text, fontFeatureSettings = "tnum"),
        cursorBrush = SolidColor(c.accentLine),
        interactionSource = interaction,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = PhantomSize.input)
                    .clip(shape)
                    .background(c.surface)
                    .border(if (focused) 2.dp else 1.dp, if (focused) c.accentLine else c.lineStrong, shape)
                    .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s3),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = stringResource(R.string.license_key_hint),
                        style = PhantomText.body.copy(fontFeatureSettings = "tnum"),
                        color = c.text3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                inner()
            }
        },
    )
}

private enum class PillKind { Primary, Soft, Quiet }

/**
 * Дія підекрана: [PillKind.Primary] — єдина бурштинова заливка, [PillKind.Soft]
 * — бурштин тихіше, [PillKind.Quiet] — нейтральна поверхня. Вимкнена дія не
 * зникає, а відступає в overlay з приглушеним написом.
 */
@Composable
private fun Pill(label: String, kind: PillKind, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalPhantomColors.current
    val (fill, ink) = when {
        !enabled -> c.overlay to c.text3
        kind == PillKind.Primary -> c.accent to c.onAccent
        kind == PillKind.Soft -> c.accentSoft to c.accentText
        else -> c.overlay to c.text
    }
    Box(
        modifier = Modifier
            .heightIn(min = PhantomSize.buttonM)
            .clip(CircleShape)
            .background(fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = PhantomSpace.s4),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = PhantomText.subhead, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1)
    }
}

/**
 * Руйнівна дія у два дотики на своєму ж рядку: перший дотик міняє напис на
 * [confirmLabel] і обводить капсулу кольором небезпеки, другий — виконує.
 */
@Composable
private fun ConfirmPill(label: String, confirmLabel: String, onConfirm: () -> Unit) {
    val c = LocalPhantomColors.current
    var armed by remember { mutableStateOf(false) }
    val armedState = stringResource(R.string.settings_a11y_armed)
    val restState = stringResource(R.string.settings_a11y_two_step)
    Box(
        modifier = Modifier
            .heightIn(min = PhantomSize.buttonM)
            .clip(CircleShape)
            .background(c.overlay)
            .border(1.dp, if (armed) c.danger else Color.Transparent, CircleShape)
            .clickable(role = Role.Button) {
                if (armed) {
                    onConfirm()
                    armed = false
                } else {
                    armed = true
                }
            }
            .semantics { stateDescription = if (armed) armedState else restState }
            .padding(horizontal = PhantomSpace.s4),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (armed) confirmLabel else label,
            style = PhantomText.subhead,
            fontWeight = FontWeight.SemiBold,
            color = c.danger,
        )
    }
}

/** Ресурси писались під старий капс малими; мова форми — речення з великої. */
private fun String.sentence(): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

@Composable
private fun checkoutMessage(status: CheckoutStatus): String? = when (status) {
    CheckoutStatus.Idle -> null
    CheckoutStatus.Working -> stringResource(R.string.checkout_working)
    CheckoutStatus.Pending -> stringResource(R.string.checkout_pending)
    CheckoutStatus.Activated -> stringResource(R.string.checkout_activated)
    CheckoutStatus.NothingToRestore -> stringResource(R.string.checkout_nothing)
    CheckoutStatus.Unavailable -> stringResource(R.string.checkout_unavailable)
    is CheckoutStatus.Failed -> stringResource(R.string.checkout_failed, status.code)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Чотири чесні стани секції оновлень, і жодного з них не можна замінити
 * рештою: збірка не з кабінету; нової збірки немає; є і її можна взяти; є, але
 * за ключем. Рішення живе поза композицією саме тому, що його треба доводити:
 * на склі 04.09 польова збірка бачила «Вийшла версія…», бо в ній versionCode
 * дорівнює 1, а кабінет оголошував сьому.
 */
internal sealed interface UpdateSection {

    data object DeveloperBuild : UpdateSection

    data object ViaDappStore : UpdateSection

    data object ViaPlay : UpdateSection

    data object Newest : UpdateSection

    data class Offer(val build: AvailableBuild) : UpdateSection

    data class Locked(val build: AvailableBuild) : UpdateSection

    companion object {
        fun of(
            developerBuild: Boolean,
            announced: AvailableBuild?,
            licensed: Boolean,
            viaDappStore: Boolean = false,
            viaPlay: Boolean = false,
        ): UpdateSection = when {
            viaDappStore -> ViaDappStore
            viaPlay -> ViaPlay
            // Перше, а не останнє: мовчання тут читалося б як «стоїть
            // найсвіжіша відома збірка», а це неправда про польову збірку.
            developerBuild -> DeveloperBuild
            announced == null -> Newest
            !announced.requiresLicence || licensed -> Offer(announced)
            else -> Locked(announced)
        }
    }
}

/**
 * Два відбитки, які варто показати, або нічого.
 *
 * Чужий підпис досі мав рівно один наслідок: `LicenseService.evaluate()` не
 * клав сертифікат у ворота. Копію, перепаковану й переписану ким завгодно,
 * застосунок обслуговував мовчки, і людина з телефоном не мала способу про це
 * дізнатись. Тут рішення винесене з композиції саме тому, що його треба
 * доводити запуском: показ мусить триматися на СПРАВЖНЬОМУ прапорці звірки
 * підпису, а не на будь-якій іншій ознаці. Відлагоджувальна збірка, емулятор і
 * встановлення повз Play лишаються тихими — це не підміна.
 */
internal data class SignatureFacts(val found: String, val expected: String) {

    companion object {
        fun of(report: IntegritySentry.Report): SignatureFacts? {
            if (!report.tampered) return null
            val found = report.signatureSha256 ?: return null
            return SignatureFacts(found = found, expected = report.expectedSha256)
        }
    }
}

private fun tierLabel(tier: LicenseTier): Int = when (tier) {
    LicenseTier.Free -> R.string.license_tier_free
    LicenseTier.Personal -> R.string.license_tier_personal
    LicenseTier.Crew -> R.string.license_tier_crew
    LicenseTier.Subunit -> R.string.license_tier_unit
}

private fun featureLabel(feature: LicenseFeature): Int = when (feature) {
    LicenseFeature.Chat -> R.string.license_feature_core_chat
    LicenseFeature.Map -> R.string.license_feature_core_map
    LicenseFeature.Nav -> R.string.license_feature_core_nav
    LicenseFeature.Alerts -> R.string.license_feature_core_alerts
    LicenseFeature.Vault -> R.string.license_feature_core_vault
    LicenseFeature.Voice -> R.string.license_feature_core_voice
    LicenseFeature.BridgePair -> R.string.license_feature_bridge_pair
    LicenseFeature.MemoryLongterm -> R.string.license_feature_memory_longterm
    LicenseFeature.MapTerrain3d -> R.string.license_feature_map_terrain3d
    LicenseFeature.VoicePremium -> R.string.license_feature_voice_premium
    LicenseFeature.GnssBlackbox -> R.string.license_feature_gnss_blackbox
    LicenseFeature.ExportReports -> R.string.license_feature_export_reports
    LicenseFeature.CrewSync -> R.string.license_feature_crew_sync
    LicenseFeature.FleetOnprem -> R.string.license_feature_fleet_onprem
}
