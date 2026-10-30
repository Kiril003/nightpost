package local.phantom.companion.core.data.country

object UkraineAlerts {

    fun available(profile: RegionProfile): Boolean = profile.alertsUa

    fun on(choice: Boolean?, profile: RegionProfile): Boolean =
        choice == true && available(profile)
}
