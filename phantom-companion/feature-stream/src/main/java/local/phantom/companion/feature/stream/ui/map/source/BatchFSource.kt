package local.phantom.companion.feature.stream.ui.map.source

import local.phantom.companion.feature.stream.R

internal object BatchFSource {
    const val DOSSIER_STOP_CATEGORY_HINT = "зупинка"

    val NEARBY_CAT_RES: List<Int> = listOf(
        R.string.mapl_a_cat_shelter,
        R.string.mapl_a_cat_pharmacy,
        R.string.mapl_a_cat_fuel,
        R.string.mapl_a_cat_atm,
        R.string.mapl_a_cat_supermarket,
        R.string.mapl_a_cat_cafe,
        R.string.mapl_a_cat_hospital,
        R.string.mapl_a_cat_drinking_water,
        R.string.mapl_a_cat_transit_hub,
        R.string.mapl_a_cat_post_office,
        R.string.mapl_a_cat_police,
        R.string.mapl_a_cat_underground_parking,
    )

    val UA_ONLY_CAT_RES: Set<Int> = setOf(
        R.string.mapl_a_cat_shelter,
        R.string.mapl_a_cat_post_office,
    )
}
