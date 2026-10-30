package local.phantom.companion.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import local.phantom.companion.core.design.component.MessengerSkeleton
import local.phantom.companion.core.design.tokens.LocalPhantomColors

/**
 * Перший кадр холодного старту, коли минулий старт вів на домівку: ескіз
 * месенджера в тих самих межах, що й WebView у [HomeScreen] (поле під рядком
 * стану, над смугою вкладок), і справжня смуга вкладок під ним.
 *
 * Домівка, що компонується наступною, кладе поверх WebView той самий ескіз
 * ([MessengerSkeleton]), а веб — свій `#boot` тими самими числами, тож людина
 * бачить один спокійний кадр, поки під ним по черзі змінюються власники.
 * Смуга — справжня, а не намальована копія: інакше на передачі вона б
 * зсунулась на піксель. Лічильника непрочитаних тут ще немає (сітка не
 * піднялась), тож смуга чесно без нього.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeSkeleton() {
    val colors = LocalPhantomColors.current
    Column(Modifier.fillMaxSize().background(colors.canvas)) {
        Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.navigationBars)) {
            MessengerSkeleton(
                Modifier.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            )
        }
        HomeBottomBar(selected = HomeTab.CHATS, unreadChats = 0, onSelect = {})
    }
}
