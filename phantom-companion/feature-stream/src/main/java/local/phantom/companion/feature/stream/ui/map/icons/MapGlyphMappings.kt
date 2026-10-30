package local.phantom.companion.feature.stream.ui.map.icons

import local.phantom.companion.core.data.places.SavedPlaceCategory
import local.phantom.companion.core.data.transit.raptor.TransitMode
import local.phantom.companion.core.geo.cameras.HazardType
import local.phantom.companion.feature.stream.ui.map.ThreatType

// Решта реєстрів мапи, зведена на ту саму родину. Кожне зіставлення —
// вичерпний `when` без гілки `else`: новий тип загрози чи транспорту ЗЛАМАЄ
// збірку тут, замість тихо вийти на екран порожнім місцем.

/** Значок загрози: тип каже, ЩО в небі, ще до підпису. */
internal fun ThreatType.glyph(): PhantomGlyph = when (this) {
    ThreatType.UNKNOWN -> PhantomGlyph.DANGER
    ThreatType.SHAHED -> PhantomGlyph.DRONE
    ThreatType.MISSILE -> PhantomGlyph.MISSILE
    ThreatType.MIG -> PhantomGlyph.JET
    ThreatType.KAB -> PhantomGlyph.BOMB
    ThreatType.ARTILLERY -> PhantomGlyph.BURST
    ThreatType.BALLISTIC -> PhantomGlyph.BALLISTIC
    ThreatType.URBAN -> PhantomGlyph.SWORDS
    ThreatType.CHEMICAL -> PhantomGlyph.CHEMICAL
    ThreatType.NUCLEAR -> PhantomGlyph.NUCLEAR
}

/** Подія на дорозі: камера, пункт пропуску, ворота, ДТП, укриття, небезпека. */
internal fun HazardType.glyph(): PhantomGlyph = when (this) {
    HazardType.SPEED_CAMERA -> PhantomGlyph.CAMERA
    HazardType.SPEED_BUMP -> PhantomGlyph.BUMP
    HazardType.ACCIDENT_HOTSPOT -> PhantomGlyph.ACCIDENT
    HazardType.DANGER_ZONE -> PhantomGlyph.DANGER
    HazardType.CHECKPOINT -> PhantomGlyph.CHECKPOINT
    HazardType.GATE -> PhantomGlyph.BARRIER
    HazardType.SHELTER -> PhantomGlyph.SHELTER
}

/** Вид транспорту: силует, а не літера в пігулці. */
internal fun TransitMode.glyph(): PhantomGlyph = when (this) {
    TransitMode.METRO -> PhantomGlyph.METRO
    TransitMode.TRAM -> PhantomGlyph.TRAM
    TransitMode.RAIL -> PhantomGlyph.TRAIN
    TransitMode.FERRY -> PhantomGlyph.BOAT
    // Фунікулер і тролейбус їдуть силуетами найближчих родичів: власний знак
    // на 11dp у пігулці не відрізнити, а хибна впевненість гірша за схожість.
    TransitMode.FUNICULAR -> PhantomGlyph.TRAM
    TransitMode.TROLLEYBUS -> PhantomGlyph.BUS
    TransitMode.BUS -> PhantomGlyph.BUS
    TransitMode.OTHER -> PhantomGlyph.STOP
}

/** Мітка оператора: категорію несе гліф, «це моє» — золоте кільце. */
internal fun savedPlaceGlyph(category: String): PhantomGlyph = when (category) {
    SavedPlaceCategory.HOME -> PhantomGlyph.HOME
    SavedPlaceCategory.WORK -> PhantomGlyph.WORK
    SavedPlaceCategory.SHELTER -> PhantomGlyph.SHELTER
    SavedPlaceCategory.FAVOURITE -> PhantomGlyph.STAR
    else -> PhantomGlyph.PLACE
}
