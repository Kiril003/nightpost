package local.phantom.companion.feature.stream.ui.map

internal object AlertRegion {
    fun here(shapes: OblastShapes, me: Pair<Double, Double>?, view: Pair<Double, Double>?): Boolean =
        me.inLocalCountry(shapes) || view.inLocalCountry(shapes)
}

internal fun SkyVerdict.onlyIf(here: Boolean): SkyVerdict = if (here) this else SkyVerdict.NO_COUNTRY_FEED
