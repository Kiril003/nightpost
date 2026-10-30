package local.phantom.companion.feature.stream.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomSpacings

/**
 * Сітка картки, якою досі міряє себе хром мапи (капсула інструментів,
 * панелі). Решта набору карток стрічки (LivingCard.kt) пішла 04.10; тут —
 * лише те, що мапа справді читає.
 */
internal object CardMetrics {
    val Pad: Dp = PhantomSpacings.Base

    /** Gap between the header and the body, and between body blocks. */
    val Gap: Dp = PhantomSpacings.Small
}

/** The one hairline a card is allowed to draw inside itself. */
@Composable
internal fun CardDivider(modifier: Modifier = Modifier, alpha: Float = 1f) {
    val theme = LocalPhantomTheme.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(theme.glassBorder.copy(alpha = theme.glassBorder.alpha * alpha)),
    )
}
