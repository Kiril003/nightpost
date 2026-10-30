package local.phantom.companion.feature.stream.ui.map

import android.content.Context
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes

object MapText {
    @Volatile private var app: Context? = null

    fun bind(context: Context) {
        app = context
    }

    fun s(@StringRes id: Int, vararg args: Any): String {
        val c = app ?: return ""
        return if (args.isEmpty()) c.getString(id) else c.getString(id, *args)
    }

    fun list(@ArrayRes id: Int): List<String> = app?.resources?.getStringArray(id)?.toList() ?: emptyList()
}
