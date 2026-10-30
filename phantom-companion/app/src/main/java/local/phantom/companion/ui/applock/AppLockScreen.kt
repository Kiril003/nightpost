package local.phantom.companion.ui.applock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import local.phantom.companion.R
import local.phantom.companion.core.design.R as DesignR
import local.phantom.companion.core.design.layout.HideBottomChrome
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.data.applock.AppLock

/**
 * Пелена поверх усього застосунку, поки [AppLock.locked]. Непрозора й ловить
 * дотики: під нею живе граф (веб-месенджер, мапа), але ні бачити, ні торкатись
 * його не можна.
 */
@Composable
fun AppLockGate(lock: AppLock) {
    val locked by lock.locked.collectAsStateWithLifecycle()
    if (!locked) return
    // Пелена бере весь екран: острівця навігації над нею бути не може.
    HideBottomChrome()
    val c = LocalPhantomColors.current
    val context = LocalContext.current
    var note by remember { mutableStateOf<String?>(null) }
    val bio = rememberBiometric(lock)
    val bioOn = lock.settings.value.biometric && bio != null

    LaunchedEffect(Unit) { if (bioOn) bio?.invoke() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(c.canvas)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        PinPad(
            title = stringResource(R.string.applock_title),
            note = note,
            onBiometric = if (bioOn) bio else null,
            onDone = { code ->
                note = when (val r = lock.unlock(code)) {
                    AppLock.Attempt.Opened -> null
                    AppLock.Attempt.Wrong -> context.getString(R.string.applock_wrong)
                    is AppLock.Attempt.Wait -> context.getString(
                        R.string.applock_wait,
                        ((r.untilMs - System.currentTimeMillis()) / 1000).coerceAtLeast(1),
                    )
                }
                note == null
            },
        )
    }
}

/**
 * Поле коду з цифровою клавіатурою. [onDone] повертає true — код прийнято
 * (поле очищати не треба); false — очистити й показати [note].
 */
@Composable
fun PinPad(
    title: String,
    note: String?,
    onDone: (String) -> Boolean,
    onBiometric: (() -> Unit)? = null,
    minDigits: Int = AppLock.MIN_DIGITS,
    confirmLabel: String? = null,
) {
    val c = LocalPhantomColors.current
    var code by remember(title) { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PhantomSpace.gutterPhone),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(DesignR.drawable.lc_lock),
            contentDescription = null,
            tint = c.text2,
            modifier = Modifier.size(PhantomSize.iconL),
        )
        Spacer(Modifier.height(PhantomSpace.s4))
        Text(text = title, style = PhantomText.title2, color = c.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(PhantomSpace.s5))
        Row(horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s3)) {
            repeat(maxOf(minDigits, code.length)) { i ->
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (i < code.length) c.text else c.overlay),
                )
            }
        }
        Spacer(Modifier.height(PhantomSpace.s3))
        Text(
            text = note ?: " ",
            style = PhantomText.footnote,
            color = if (note != null) c.danger else c.text3,
            textAlign = TextAlign.Center,
            modifier = Modifier.height(36.dp),
        )
        Spacer(Modifier.height(PhantomSpace.s3))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "bio", "0", "del")
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s5)) {
                row.forEach { key ->
                    Key(key, onBiometric) {
                        when (key) {
                            "del" -> code = code.dropLast(1)
                            "bio" -> onBiometric?.invoke()
                            else -> if (code.length < AppLock.MAX_DIGITS) code += key
                        }
                    }
                }
            }
            Spacer(Modifier.height(PhantomSpace.s3))
        }
        Spacer(Modifier.height(PhantomSpace.s2))
        Box(
            modifier = Modifier
                .size(width = 200.dp, height = PhantomSpace.touchMinAndroid)
                .clip(CircleShape)
                .background(if (code.length >= minDigits) c.accent else c.overlay)
                .clickable(enabled = code.length >= minDigits, role = Role.Button) {
                    if (!onDone(code)) code = ""
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = confirmLabel ?: stringResource(R.string.applock_open),
                style = PhantomText.headline,
                color = if (code.length >= minDigits) c.onAccent else c.text3,
            )
        }
    }
}

@Composable
private fun Key(key: String, onBiometric: (() -> Unit)?, onTap: () -> Unit) {
    val c = LocalPhantomColors.current
    val empty = key == "bio" && onBiometric == null
    val label = when (key) {
        "del" -> stringResource(R.string.applock_delete)
        "bio" -> stringResource(R.string.applock_biometric)
        else -> key
    }
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(if (empty || key == "del" || key == "bio") c.canvas else c.raised)
            .then(if (empty) Modifier else Modifier.clickable(role = Role.Button, onClick = onTap))
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        when {
            empty -> Unit
            key == "del" -> Icon(painterResource(DesignR.drawable.lc_back), null, tint = c.text2, modifier = Modifier.size(PhantomSize.iconM))
            key == "bio" -> Icon(painterResource(DesignR.drawable.lc_lock), null, tint = c.text2, modifier = Modifier.size(PhantomSize.iconM))
            else -> Text(text = key, style = PhantomText.title1, color = c.text)
        }
    }
}

/** Системний запит відбитка; null — апарат не вміє (тоді лише код). */
@Composable
private fun rememberBiometric(lock: AppLock): (() -> Unit)? {
    val context = LocalContext.current
    val activity = context as? FragmentActivity ?: return null
    val can = remember(context) {
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }
    if (!can) return null
    val title = stringResource(R.string.applock_title)
    val cancel = stringResource(R.string.applock_use_code)
    return remember(activity, title, cancel) {
        {
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setNegativeButtonText(cancel)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .build()
            BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        lock.unlockByBiometric()
                    }
                },
            ).authenticate(info)
        }
    }
}
