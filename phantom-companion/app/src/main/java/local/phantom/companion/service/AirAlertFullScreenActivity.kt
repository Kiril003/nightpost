package local.phantom.companion.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import local.phantom.companion.MainActivity
import local.phantom.companion.R
import local.phantom.companion.core.data.locale.AppLanguage
import local.phantom.companion.core.design.layout.HideBottomChrome
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomBody
import local.phantom.companion.core.design.theme.PhantomCaption
import local.phantom.companion.core.design.theme.PhantomDisplay
import local.phantom.companion.core.design.theme.PhantomKicker
import local.phantom.companion.core.design.theme.PhantomProse
import local.phantom.companion.core.design.theme.PhantomRadii
import local.phantom.companion.core.design.theme.PhantomSpacings
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.core.design.theme.PhantomTouchFloor
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Тривога на погаслому екрані.
 *
 * Сповіщення в шторці припускає, що хтось у неї подивиться. О третій ночі
 * телефон лежить екраном донизу, а різниця між «почув сирену» і «прокинувся
 * від телефона» — це хвилини до укриття. Повноекранний намір вмикає екран сам
 * і малює одну область, одну загрозу й один час — з відстані витягнутої руки,
 * не розблоковуючи.
 *
 * Показується ЛИШЕ на оголошення тривоги і ЛИШЕ коли екран замкнений або
 * погашений — рішення живе в [AirAlertNotifications.fullScreenIntent]. Відбій
 * гасить цей екран ([close]), а не малює над ним другий.
 *
 * Свідомо без жодної нескінченної анімації: цей екран може простояти годину, і
 * кадр за кадром він гріє саме ту батарею, від якої залежить наступна тривога.
 */
class AirAlertFullScreenActivity : ComponentActivity() {

    // Мова Nightpost до Android 13: тривога на весь екран має власний стос і
    // не проходить через MainActivity, тож без цього говорила б мовою телефона.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Те саме сказано в маніфесті; частина оболонок читає лише виклик.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        live = WeakReference(this)

        val where = intent.getStringExtra(EXTRA_WHERE).orEmpty()
        val phrase = intent.getStringExtra(EXTRA_PHRASE).orEmpty()
        val at = intent.getLongExtra(EXTRA_AT, 0L)
        val clock = if (at > 0L) {
            runCatching { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at)) }
                .getOrNull()
        } else {
            null
        }

        setContent {
            PhantomTheme {
                AirAlertFullScreen(
                    where = where,
                    phrase = phrase,
                    clock = clock,
                    onMap = {
                        startActivity(
                            Intent(this, MainActivity::class.java).apply {
                                action = Intent.ACTION_VIEW
                                putExtra(AirAlertNotifications.EXTRA_OPEN_MAP, true)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                            },
                        )
                        finish()
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }

    override fun onDestroy() {
        if (live?.get() === this) live = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_WHERE = "phantom.alert.where"
        const val EXTRA_PHRASE = "phantom.alert.phrase"
        const val EXTRA_AT = "phantom.alert.at"

        @Volatile
        private var live: WeakReference<AirAlertFullScreenActivity>? = null

        /** Відбій знімає екран. Викликається зі збирача корутин служби. */
        fun close() {
            val activity = live?.get() ?: return
            activity.runOnUiThread { activity.finish() }
            live = null
        }
    }
}

@Composable
private fun AirAlertFullScreen(
    where: String,
    phrase: String,
    clock: String?,
    onMap: () -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = LocalPhantomTheme.current
    // Тривога — окрема Activity поверх усього; острівця тут немає і бути не
    // повинно, тож поверхня забирає екран собі, а не резервує під нього смугу.
    HideBottomChrome()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.surfaceBase)
            .systemBarsPadding()
            .padding(PhantomSpacings.Large),
        verticalArrangement = Arrangement.Center,
    ) {
        PhantomKicker(
            text = stringResource(R.string.air_alert_full_kicker),
            color = theme.coral,
        )
        Spacer(Modifier.height(PhantomSpacings.Small))
        PhantomDisplay(text = where, maxLines = 2)
        if (phrase.isNotBlank()) {
            Spacer(Modifier.height(PhantomSpacings.Base))
            PhantomProse(text = phrase)
        }
        if (clock != null) {
            Spacer(Modifier.height(PhantomSpacings.Small))
            PhantomCaption(text = stringResource(R.string.air_alert_full_since, clock))
        }
        Spacer(Modifier.height(PhantomSpacings.XLarge))
        SirenAction(
            label = stringResource(R.string.air_alert_full_map),
            primary = true,
            onClick = onMap,
        )
        Spacer(Modifier.height(PhantomSpacings.Small))
        SirenAction(
            label = stringResource(R.string.air_alert_full_dismiss),
            primary = false,
            onClick = onDismiss,
        )
    }
}

@Composable
private fun SirenAction(label: String, primary: Boolean, onClick: () -> Unit) {
    val theme = LocalPhantomTheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PhantomTouchFloor)
            .clip(PhantomRadii.MediumShape)
            .background(if (primary) theme.primary else theme.glassPanel)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .padding(PhantomSpacings.Base),
        contentAlignment = Alignment.Center,
    ) {
        PhantomBody(
            text = label,
            color = if (primary) MaterialTheme.colorScheme.onPrimary else theme.ink,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
