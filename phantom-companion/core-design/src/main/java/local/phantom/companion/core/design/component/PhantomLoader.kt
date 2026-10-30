package local.phantom.companion.core.design.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import local.phantom.companion.core.design.tokens.LocalPhantomColors

/** Ворота запуску: той самий знак, що й на заставці, у темі застосунку. Без руху — тут нічого не триває довго. */
@Composable
fun PhantomLoaderScreen(modifier: Modifier = Modifier) {
    val colors = LocalPhantomColors.current
    PhantomMarkFrame(canvas = colors.canvas, ink = colors.text, modifier = modifier)
}
