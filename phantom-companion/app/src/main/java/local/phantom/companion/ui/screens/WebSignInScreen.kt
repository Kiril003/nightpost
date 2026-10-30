package local.phantom.companion.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.navigation.NavController
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import local.phantom.companion.BuildConfig
import local.phantom.companion.PhantomApp
import local.phantom.companion.R
import local.phantom.companion.core.design.R as DesignR
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.net.auth.WebAuthQr
import local.phantom.companion.core.net.auth.WebAuthQrClient
import local.phantom.companion.feature.stream.ui.scan.LiveQrCamera
import local.phantom.companion.ui.home.ActionRow
import local.phantom.companion.ui.home.ProfileField
import local.phantom.companion.ui.home.RowDivider
import local.phantom.companion.ui.home.RowGroup
import local.phantom.companion.ui.home.SubScreen

object WebSignIn {
    const val ROUTE = "web_signin"
}

private data class PendingConfirm(
    val challenge: WebAuthQr.Challenge,
    val info: WebAuthQr.PeekInfo,
)

@Composable
fun WebSignInDestination(navController: NavController) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as PhantomApp).container }
    val client = remember { WebAuthQrClient.open(BuildConfig.LICENSE_BASE_URL) }
    var linkPayload by remember {
        mutableStateOf(
            container.events.webSignInPayload.value.also {
                container.events.webSignInPayload.value = null
            },
        )
    }
    LaunchedEffect(Unit) {
        container.events.webSignInPayload.collect { next ->
            if (next != null) {
                container.events.webSignInPayload.value = null
                linkPayload = next
            }
        }
    }
    var scanPayload by remember {
        mutableStateOf(
            container.events.webSignInScan.value.also {
                container.events.webSignInScan.value = null
            },
        )
    }
    LaunchedEffect(Unit) {
        container.events.webSignInScan.collect { next ->
            if (next != null) {
                container.events.webSignInScan.value = null
                scanPayload = next
            }
        }
    }

    WebSignInScreen(
        onBack = { navController.popBackStack() },
        linkPayload = linkPayload,
        scannedPayload = scanPayload,
        onPeek = { challenge ->
            if (client == null) {
                WebAuthQrClient.PeekOutcome.Unreachable
            } else {
                client.peek(challenge)
            }
        },
        onApprove = { challenge ->
            if (client == null) {
                WebAuthQrClient.ApproveOutcome.Unreachable
            } else {
                withContext(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    if (challenge.expiresAtSeconds * 1_000L <= now) {
                        return@withContext WebAuthQrClient.ApproveOutcome.Expired
                    }
                    // Strictly DeviceIdentityStore Ed25519 identity key — never wallet/Solana key.
                    val identity = container.identity.get()
                    val approval = WebAuthQr.signApproval(
                        challenge = challenge,
                        privateKey = identity.privateKey,
                        issuedMs = now,
                    )
                    client.approve(approval)
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebSignInScreen(
    onBack: () -> Unit,
    onPeek: suspend (WebAuthQr.Challenge) -> WebAuthQrClient.PeekOutcome,
    onApprove: suspend (WebAuthQr.Challenge) -> WebAuthQrClient.ApproveOutcome,
    linkPayload: String? = null,
    scannedPayload: String? = null,
) {
    val c = LocalPhantomColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val fromLink = linkPayload != null

    val cameraGate = local.phantom.companion.core.design.permission.rememberPermissionGate(
        permissions = listOf(Manifest.permission.CAMERA),
        reason = local.phantom.companion.core.design.permission.PermissionReason(
            DesignR.drawable.lc_camera,
            local.phantom.companion.feature.stream.R.string.perm_camera_scan_title,
            local.phantom.companion.feature.stream.R.string.perm_camera_scan_body,
        ),
    )
    var hasCamera by remember { mutableStateOf(cameraGate.granted()) }
    var scanGeneration by remember { mutableStateOf(0) }
    var manualCode by remember { mutableStateOf("") }
    var statusLine by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var networkMismatch by remember { mutableStateOf(false) }
    var linkApproved by remember { mutableStateOf(false) }
    var pendingConfirm by remember { mutableStateOf<PendingConfirm?>(null) }
    var signing by remember { mutableStateOf(false) }

    val badQrText = stringResource(R.string.web_signin_bad_qr)
    val expiredText = stringResource(R.string.web_signin_expired)
    val refusedText = stringResource(R.string.web_signin_refused)
    val unreachableText = stringResource(R.string.web_signin_unreachable)
    val signedInText = stringResource(R.string.web_signin_done)

    fun handleScanned(raw: String) {
        scope.launch {
            statusLine = null
            isError = false
            networkMismatch = false
            linkApproved = false
            when (val parsed = WebAuthQr.parse(raw, System.currentTimeMillis())) {
                WebAuthQr.ParseResult.Invalid -> {
                    statusLine = badQrText
                    isError = true
                }
                is WebAuthQr.ParseResult.Expired -> {
                    statusLine = expiredText
                    isError = true
                }
                is WebAuthQr.ParseResult.Valid -> {
                    when (val peek = onPeek(parsed.challenge)) {
                        is WebAuthQrClient.PeekOutcome.Found -> {
                            if (!WebAuthQr.Purpose.known(peek.info.purpose)) {
                                statusLine = refusedText
                                isError = true
                            } else if (fromLink && !linkApproveAllowed(peek)) {
                                networkMismatch = true
                                statusLine = refusedText
                                isError = true
                            } else {
                                pendingConfirm = PendingConfirm(parsed.challenge, peek.info)
                            }
                        }
                        WebAuthQrClient.PeekOutcome.Expired -> {
                            statusLine = expiredText
                            isError = true
                        }
                        WebAuthQrClient.PeekOutcome.Unreachable -> {
                            statusLine = unreachableText
                            isError = true
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(linkPayload) {
        if (linkPayload != null) {
            handleScanned(linkPayload)
        } else if (!hasCamera) {
            cameraGate.request { hasCamera = it }
        }
    }
    LaunchedEffect(scannedPayload) {
        if (scannedPayload != null) handleScanned(scannedPayload)
    }

    Box(Modifier.fillMaxSize()) {
        SubScreen(stringResource(R.string.web_signin_title), onBack) {
            Column(Modifier.imePadding()) {
                if (fromLink) {
                    if (linkApproved) {
                        RowGroup {
                            ConfirmLabelRow(
                                label = stringResource(R.string.web_signin_title),
                                value = signedInText,
                            )
                        }
                        Spacer(Modifier.height(PhantomSpace.s5))
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = c.accent,
                                contentColor = c.canvas,
                            ),
                            onClick = {
                                onBack()
                                val activity = context.findActivity()
                                if (activity != null && !activity.moveTaskToBack(true)) {
                                    activity.finish()
                                }
                            },
                        ) {
                            Text(
                                text = stringResource(R.string.web_signin_back_to_browser),
                                style = PhantomText.callout,
                            )
                        }
                    } else {
                        if (networkMismatch) {
                            RowGroup {
                                ConfirmLabelRow(
                                    label = stringResource(R.string.web_signin_network),
                                    value = stringResource(R.string.web_signin_other_network),
                                )
                            }
                            Spacer(Modifier.height(PhantomSpace.s3))
                        }
                        val line = statusLine
                        if (line != null) {
                            Text(
                                text = line,
                                style = PhantomText.footnote,
                                color = if (isError) c.danger else c.text2,
                                modifier = Modifier.padding(horizontal = PhantomSpace.s4),
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(PhantomRadius.sheet))
                            .background(c.raised),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (hasCamera) {
                            LiveQrCamera(
                                modifier = Modifier.fillMaxSize(),
                                resetKey = scanGeneration,
                                onScan = {
                                    handleScanned(it)
                                    true
                                },
                            )
                        } else {
                            Column(
                                modifier = Modifier.padding(PhantomSpace.s6),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(
                                    painter = painterResource(DesignR.drawable.lc_camera),
                                    contentDescription = null,
                                    tint = c.text3,
                                    modifier = Modifier.size(PhantomSpace.s10),
                                )
                                Spacer(Modifier.height(PhantomSpace.s3))
                                Text(
                                    text = stringResource(R.string.pair_camera_denied),
                                    style = PhantomText.callout,
                                    color = c.text2,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }

                    val line = statusLine
                    if (line != null) {
                        Spacer(Modifier.height(PhantomSpace.s3))
                        Text(
                            text = line,
                            style = PhantomText.footnote,
                            color = if (isError) c.danger else c.text2,
                            modifier = Modifier.padding(horizontal = PhantomSpace.s4),
                        )
                    }
                    Spacer(Modifier.height(PhantomSpace.s5))

                    RowGroup {
                        if (hasCamera) {
                            ActionRow(
                                label = stringResource(R.string.look_pair_scan_again),
                                icon = DesignR.drawable.lc_camera,
                                onClick = {
                                    statusLine = null
                                    isError = false
                                    scanGeneration += 1
                                },
                            )
                        } else {
                            ActionRow(
                                label = stringResource(R.string.look_pair_camera_allow),
                                icon = DesignR.drawable.lc_camera,
                                action = stringResource(R.string.look_grant),
                                onClick = { cameraGate.request { hasCamera = it } },
                            )
                        }
                    }

                    if (BuildConfig.DEBUG) {
                        RowGroup(caption = stringResource(R.string.pair_manual_label)) {
                            Box(Modifier.padding(PhantomSpace.s3)) {
                                ProfileField(
                                    value = manualCode,
                                    onValueChange = { manualCode = it },
                                    hint = "NP-AUTH-v1|…",
                                )
                            }
                            RowDivider()
                            ActionRow(
                                label = stringResource(R.string.web_signin_confirm),
                                onClick = if (manualCode.isBlank()) {
                                    null
                                } else {
                                    { handleScanned(manualCode.trim()) }
                                },
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(PhantomSpace.s4),
        )
    }

    val confirm = pendingConfirm
    if (confirm != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val formattedTime = remember(confirm.info.createdAt) { formatPeekTime(confirm.info.createdAt) }

        ModalBottomSheet(
            onDismissRequest = {
                if (!signing) {
                    pendingConfirm = null
                    scanGeneration += 1
                }
            },
            sheetState = sheetState,
            containerColor = c.surface,
            contentColor = c.text,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = PhantomSpace.s5, vertical = PhantomSpace.s4),
            ) {
                val isLink = confirm.info.purpose == WebAuthQr.Purpose.LINK
                Text(
                    text = stringResource(if (isLink) R.string.web_signin_link_title else R.string.web_signin_title),
                    style = PhantomText.headline,
                    color = c.text,
                )
                Spacer(Modifier.height(PhantomSpace.s4))

                val isErase = confirm.info.purpose == WebAuthQr.Purpose.ERASE
                RowGroup {
                    ConfirmLabelRow(
                        label = stringResource(R.string.web_signin_account),
                        value = stringResource(
                            when (confirm.info.purpose) {
                                WebAuthQr.Purpose.ERASE -> R.string.web_signin_purpose_erase
                                WebAuthQr.Purpose.LINK -> R.string.web_signin_purpose_link
                                else -> R.string.web_signin_purpose_signin
                            },
                        ),
                        valueColor = if (isErase) c.danger else c.text,
                    )
                    RowDivider()
                    ConfirmLabelRow(
                        label = stringResource(R.string.web_signin_browser),
                        value = confirm.info.browser,
                    )
                    val country = confirm.info.country
                    if (!country.isNullOrBlank()) {
                        RowDivider()
                        ConfirmLabelRow(
                            label = stringResource(R.string.web_signin_country),
                            value = country,
                        )
                    }
                    if (confirm.info.ipMismatch) {
                        RowDivider()
                        ConfirmLabelRow(
                            label = stringResource(R.string.web_signin_network),
                            value = stringResource(R.string.web_signin_other_network),
                        )
                    }
                    RowDivider()
                    ConfirmLabelRow(
                        label = stringResource(R.string.web_signin_time),
                        value = formattedTime ?: "—",
                    )
                }

                Spacer(Modifier.height(PhantomSpace.s5))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s3, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        enabled = !signing,
                        onClick = {
                            pendingConfirm = null
                            scanGeneration += 1
                        },
                    ) {
                        Text(
                            text = stringResource(R.string.web_signin_cancel),
                            style = PhantomText.callout,
                            color = c.text2,
                        )
                    }
                    if (WebAuthQr.Purpose.known(confirm.info.purpose) && (!fromLink || linkApproveAllowed(confirm.info))) {
                        Button(
                            enabled = !signing,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = c.accent,
                                contentColor = c.canvas,
                            ),
                            onClick = {
                                if (signing) return@Button
                                signing = true
                                scope.launch {
                                    val outcome = onApprove(confirm.challenge)
                                    signing = false
                                    pendingConfirm = null
                                    when (outcome) {
                                        WebAuthQrClient.ApproveOutcome.Approved -> {
                                            if (fromLink) {
                                                linkApproved = true
                                            } else {
                                                withTimeoutOrNull(900L) {
                                                    snackbarHostState.showSnackbar(
                                                        message = signedInText,
                                                        duration = SnackbarDuration.Short,
                                                    )
                                                }
                                                onBack()
                                            }
                                        }
                                        WebAuthQrClient.ApproveOutcome.Expired -> {
                                            statusLine = expiredText
                                            isError = true
                                        }
                                        WebAuthQrClient.ApproveOutcome.Refused -> {
                                            statusLine = refusedText
                                            isError = true
                                        }
                                        WebAuthQrClient.ApproveOutcome.Unreachable -> {
                                            statusLine = unreachableText
                                            isError = true
                                        }
                                    }
                                }
                            },
                        ) {
                            Text(
                                text = stringResource(if (isLink) R.string.web_signin_link_confirm else R.string.web_signin_confirm),
                                style = PhantomText.callout,
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun linkApproveAllowed(info: WebAuthQr.PeekInfo): Boolean =
    info.sameIp && !info.ipMismatch && WebAuthQr.Purpose.known(info.purpose)

internal fun linkApproveAllowed(peek: WebAuthQrClient.PeekOutcome): Boolean =
    peek is WebAuthQrClient.PeekOutcome.Found && linkApproveAllowed(peek.info)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun ConfirmLabelRow(
    label: String,
    value: String,
    valueColor: Color = LocalPhantomColors.current.text,
) {
    val c = LocalPhantomColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s3),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = PhantomText.callout, color = c.text2)
        Text(text = value, style = PhantomText.callout, color = valueColor)
    }
}

internal fun formatPeekTime(raw: String?, zone: ZoneId = ZoneId.systemDefault()): String? {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val instant = runCatching { Instant.parse(text) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
        ?: text.toLongOrNull()?.let { n ->
            if (n > 10_000_000_000L) Instant.ofEpochMilli(n) else Instant.ofEpochSecond(n)
        }
    return if (instant != null) {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone).format(instant)
    } else {
        text
    }
}
