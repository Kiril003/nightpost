package local.phantom.companion.self

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import local.phantom.companion.MainActivity
import local.phantom.companion.R
import local.phantom.companion.data.AppEventBus
import java.util.concurrent.atomic.AtomicBoolean

object TalkEntry {
    const val ACTION = "local.phantom.companion.action.TALK"
    private const val SHORTCUT_ID = "talk"
    private val published = AtomicBoolean(false)

    fun intent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

    fun route(context: Context, intent: Intent?, events: AppEventBus): Boolean {
        if (published.compareAndSet(false, true)) {
            runCatching {
                val label = context.getString(R.string.call_phantom_title)
                val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
                    .setShortLabel(label)
                    .setLongLabel(label)
                    .setIcon(IconCompat.createWithResource(context, R.drawable.lc_phantom_mark))
                    .setIntent(intent(context))
                    .setRank(0)
                    .build()
                ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
            }
        }
        if (intent?.action != ACTION) return false
        events.phantomCallOpen.value = true
        return true
    }

    internal fun resetForTest() {
        published.set(false)
    }
}
