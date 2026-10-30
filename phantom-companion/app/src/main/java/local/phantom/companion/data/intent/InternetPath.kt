package local.phantom.companion.data.intent

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Чи є в телефона дорога в інтернет зараз — так, як її бачить система:
 * мережа з INTERNET і VALIDATED (система сама перевірила, що за нею справді
 * інтернет, а не портал готелю чи Wi-Fi без виходу). Немає — питання до
 * PHANTOM їде Наміром до мого ПК (розд. 07).
 */
object InternetPath {
    fun has(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
