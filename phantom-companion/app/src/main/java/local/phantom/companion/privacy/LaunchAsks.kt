package local.phantom.companion.privacy

import android.Manifest

/**
 * Що застосунок питає сам, без дотику людини. Лише сповіщення: мікрофон, місце
 * й камеру питає функція, якій вони потрібні, у мить використання.
 */
object LaunchAsks {

    fun permissions(sdk: Int): List<String> =
        if (sdk >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
}
