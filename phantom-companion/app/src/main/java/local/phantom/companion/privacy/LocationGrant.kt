package local.phantom.companion.privacy

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Що саме людина відповіла в системному діалозі місця.
 *
 * Android 12+ показує там ДВА питання одразу: точність (Precise/Approximate) і
 * тривалість («лише під час використання» / «лише цього разу» / «не дозволяти»).
 * Назад приходить лише результат по двох дозволах — тривалість не видно взагалі,
 * і питати про неї нема кого.
 *
 * [APPROXIMATE] — це не «майже точне». Це FINE ВІДМОВЛЕНО. Запит FINE після
 * такої відповіді ОС відхиляє МИТТЄВО: повторний запит з `onResume` стає
 * невидимим циклом діалогів, який 12.09 на emulator-5554 закінчувався смертю
 * процесу за 2,4 с.
 */
enum class LocationGrant { PRECISE, APPROXIMATE, NONE }

object LocationGrants {

    fun ofResult(result: Map<String, Boolean>): LocationGrant = of(
        fineHeld = result[Manifest.permission.ACCESS_FINE_LOCATION] == true,
        coarseHeld = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true,
    )

    fun of(fineHeld: Boolean, coarseHeld: Boolean): LocationGrant = when {
        fineHeld -> LocationGrant.PRECISE
        coarseHeld -> LocationGrant.APPROXIMATE
        else -> LocationGrant.NONE
    }

    /** Чи прозвучала відповідь узагалі — хай навіть «приблизне» або «ні». */
    fun answered(result: Map<String, Boolean>): Boolean =
        result.containsKey(Manifest.permission.ACCESS_FINE_LOCATION) ||
            result.containsKey(Manifest.permission.ACCESS_COARSE_LOCATION)

    /**
     * Чи показувати системний діалог місця. Питає лише той, кого ще не чули:
     * повторний запит після будь-якої відповіді — це цикл, а не наполегливість.
     */
    fun shouldAsk(grant: LocationGrant, answeredBefore: Boolean): Boolean =
        grant == LocationGrant.NONE && !answeredBefore

    /** Чи є від чого рахувати відстань до укриття й вести маршрут. */
    fun usable(grant: LocationGrant): Boolean = grant != LocationGrant.NONE

    /** Точне або приблизне: обидва дають фікс, а брама дозволів приймає будь-яке з двох. */
    fun held(context: Context): Boolean = usable(
        of(
            fineHeld = granted(context, Manifest.permission.ACCESS_FINE_LOCATION),
            coarseHeld = granted(context, Manifest.permission.ACCESS_COARSE_LOCATION),
        ),
    )

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
