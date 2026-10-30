package local.phantom.companion.service

import android.content.Context
import local.phantom.companion.PhantomApp
import local.phantom.companion.core.data.country.RegionProfile
import local.phantom.companion.core.data.country.RegionResolver
import local.phantom.companion.core.data.country.UkraineAlerts
import local.phantom.companion.data.MapPreferences

object AirAlertGate {

    fun uiLanguage(context: Context): String? =
        runCatching { context.resources.configuration.locales.get(0).language }.getOrNull()

    fun profile(context: Context): RegionProfile {
        val prefs = runCatching { (context.applicationContext as PhantomApp).container.mapPrefs }.getOrNull()
        return profile(context, prefs)
    }

    fun profile(context: Context, prefs: MapPreferences?): RegionProfile =
        RegionProfile.of(
            signals = RegionResolver.read(context),
            overridePack = prefs?.countryOverride?.value,
            uiLanguage = uiLanguage(context),
        )

    fun available(context: Context): Boolean =
        UkraineAlerts.available(profile(context))

    fun available(context: Context, prefs: MapPreferences): Boolean =
        UkraineAlerts.available(profile(context, prefs))

    fun on(context: Context): Boolean {
        val prefs = runCatching { (context.applicationContext as PhantomApp).container.mapPrefs }.getOrNull()
            ?: return false
        return on(context, prefs)
    }

    fun on(context: Context, prefs: MapPreferences): Boolean =
        UkraineAlerts.on(prefs.ukraineAlerts.value, profile(context, prefs))

    fun skyDeclared(context: Context, prefs: MapPreferences, skyAvailable: () -> Boolean): Boolean =
        on(context, prefs) && skyAvailable()
}
