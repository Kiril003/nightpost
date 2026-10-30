package local.phantom.companion.feature.stream.ui.map.indoor

import androidx.annotation.StringRes
import local.phantom.companion.feature.stream.R

/**
 * Слово на кімнаті внутрішнього плану — або жодного.
 *
 * Кадр власника 01.09 02:18: «parking» шість разів на одному екрані,
 * англійською. Це були шість БЕЗІМЕННИХ паркінгів застряглого плану: кімната
 * без `name` підписувалась сирим тегом OSM (`shop`/`amenity`/`building:part`),
 * тобто словником мапера, а не людини. Той самий закон, що й у
 * `poiDisplayName`: **ніколи не заглушка** — вузол без імені не належить на
 * мапу словом.
 *
 * Виняток один і закритий: орієнтири, які людина шукає ПО РОДУ, а не по імені
 * (туалет, банкомат, довідка) — їм слово дає цей словник, у мові оператора:
 * ресурсом зі `strings_indoor.xml` (uk + en), а не літералом у коді, тож англійський
 * телефон читає «ATM», а не «Банкомат». Решта безіменного — крамниця, склад,
 * коридор, паркінг — форма на плані, не місце.
 *
 * Функція чиста й без Context: вона каже, ЩО показати (власне імʼя або
 * ідентифікатор ресурсу), а саме слово з ресурсу дістає накладка через
 * `stringResource` у композиції.
 */
internal sealed interface IndoorRoomLabel {
    /** Власне імʼя кімнати з плану — показується як є, обрізане. */
    data class Named(val text: String) : IndoorRoomLabel

    /** Орієнтир за родом — слово береться з ресурсів у мові оператора. */
    data class Landmark(@StringRes val word: Int) : IndoorRoomLabel
}

internal fun indoorRoomLabel(name: String?, category: String?): IndoorRoomLabel? {
    val explicit = name?.trim()
    if (!explicit.isNullOrEmpty()) return IndoorRoomLabel.Named(explicit)
    val word = LANDMARK_WORDS[category?.trim()?.lowercase()] ?: return null
    return IndoorRoomLabel.Landmark(word)
}

/**
 * Закритий словник: додати сюди — свідоме рішення, не втеча сирого тега.
 * Значення — `R.string.indoor_landmark_*` з `strings_indoor.xml` (uk + en), не слово в коді.
 */
internal val LANDMARK_WORDS: Map<String, Int> = mapOf(
    "toilets" to R.string.indoor_landmark_toilets,
    "atm" to R.string.indoor_landmark_atm,
    "information" to R.string.indoor_landmark_information,
    "pharmacy" to R.string.indoor_landmark_pharmacy,
    "post_office" to R.string.indoor_landmark_post_office,
    "police" to R.string.indoor_landmark_police,
    "first_aid" to R.string.indoor_landmark_first_aid,
    "lost_and_found" to R.string.indoor_landmark_lost_and_found,
    "locker" to R.string.indoor_landmark_lockers,
    "luggage_locker" to R.string.indoor_landmark_lockers,
    "left_luggage" to R.string.indoor_landmark_lockers,
    "prayer_room" to R.string.indoor_landmark_prayer_room,
    "baby_hatch" to R.string.indoor_landmark_nursing_room,
    "childcare" to R.string.indoor_landmark_childcare,
    "smoking_area" to R.string.indoor_landmark_smoking_area,
    "waiting_room" to R.string.indoor_landmark_waiting_room,
    "ticket" to R.string.indoor_landmark_ticket_office,
    "ticket_office" to R.string.indoor_landmark_ticket_office,
    "reception" to R.string.indoor_landmark_reception,
)
