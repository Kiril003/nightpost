package local.phantom.companion.ui.backup

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts

internal object AppRestart {
    // Живі сховища в памʼяті (вихідні, ростер) інакше перепишуть відновлене своїм порожнім станом.
    fun relaunch(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        context.startActivity(Intent.makeRestartActivityTask(launch.component))
        Runtime.getRuntime().exit(0)
    }
}

internal class OpenBackupDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
}
