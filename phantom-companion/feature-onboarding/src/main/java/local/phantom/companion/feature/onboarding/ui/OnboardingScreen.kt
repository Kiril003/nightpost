package local.phantom.companion.feature.onboarding.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.feature.onboarding.OnboardingViewModel
import local.phantom.companion.feature.onboarding.R
import local.phantom.companion.feature.onboarding.domain.OnboardingEvent
import local.phantom.companion.feature.onboarding.domain.OnboardingState

/** [onNarrate] receives the Familiar's TTS line on each state advance. */
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onComplete: (profileId: String) -> Unit,
    onNarrate: suspend (String) -> Unit = {},
    restoreAvailable: Boolean = false,
    onRestore: () -> Unit = {},
    onPhotoPicked: (Uri) -> Unit = {},
    initialPhoto: Bitmap? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Keyed on the resolved line so draft-name keystrokes don't re-narrate.
    val currentLine = remember(state) { narrationLineFor(state) }
    LaunchedEffect(currentLine) {
        currentLine?.let { onNarrate(it) }
    }

    when (val s = state) {
        is OnboardingState.NameStep -> NameStep(
            initialDisplayName = s.draftName,
            restoreAvailable = restoreAvailable,
            onRestore = onRestore,
            onPhotoPicked = onPhotoPicked,
            initialPhoto = initialPhoto,
            onSubmit = {
                viewModel.onEvent(
                    OnboardingEvent.NameSubmitted(submittedName(it)),
                )
            },
        )

        is OnboardingState.CreatingProfile -> CreatingProfileIndicator()

        is OnboardingState.Complete -> {
            LaunchedEffect(s.profileId) { onComplete(s.profileId) }
        }

        is OnboardingState.Error -> ErrorCard(
            message = s.message,
            onRestart = { viewModel.onEvent(OnboardingEvent.Restart) },
        )
    }
}

internal fun submittedName(typed: String): String = typed.trim()

@Composable
private fun CreatingProfileIndicator() {
    val c = LocalPhantomColors.current

    // Тут крутився лічильник на 1200 мс кроками по 60, і його ЄДИНИМ споживачем
    // були чотири підписи — «зчитую сенсори», «звʼязую памʼять», «настроюю
    // голос», «майже готово». Жодної з цих робіт не відбувається: ViewModel
    // рахує Argon2id від PIN і пише профіль, і все. Коментар автора сам це
    // визнавав («paced… even when the Argon2id hash finishes early») — тобто
    // темп заради темпу. Перший екран продукту звітував про чотири роботи, з
    // яких не виконується жодна; лишається одне речення про ту єдину, що є.
    //
    // Кільце-крутійка теж пішло: прогресу йому ніхто не передавав, а запис
    // профілю триває мить. Лишається знак заставки й одне речення — стан
    // сказаний словами, а не рухом.

    OnboardingScaffold {
        OnboardingMark()
        Spacer(modifier = Modifier.height(PhantomSpace.s5))
        Text(
            text = stringResource(R.string.onb_creating_profile).asSentence(),
            style = PhantomText.callout,
            color = c.text2,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ErrorCard(
    message: String,
    onRestart: () -> Unit,
) {
    val c = LocalPhantomColors.current
    OnboardingScaffold(
        actions = {
            OnboardingPrimaryButton(
                label = stringResource(R.string.onb_retry),
                enabled = true,
                onClick = onRestart,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        OnboardingMark()
        Spacer(modifier = Modifier.height(PhantomSpace.s5))
        // Що сталося — заголовком мови форми; причина — звичайним абзацом під ним.
        Text(
            text = stringResource(R.string.onb_error_title),
            style = PhantomText.title1,
            color = c.text,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(PhantomSpace.s2))
        Text(
            text = message,
            style = PhantomText.callout,
            color = c.text2,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = ReadingWidth),
        )
    }
}
