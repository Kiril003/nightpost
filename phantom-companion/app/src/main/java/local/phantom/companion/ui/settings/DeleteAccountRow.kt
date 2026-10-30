package local.phantom.companion.ui.settings

import android.content.Intent
import android.net.Uri

internal object DeleteAccount {
    const val URL = "https://phantom-os.dev/delete-account"

    fun intent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(URL)).addCategory(Intent.CATEGORY_BROWSABLE)
}
