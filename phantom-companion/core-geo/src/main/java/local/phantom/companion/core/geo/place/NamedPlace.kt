package local.phantom.companion.core.geo.place

/** Що це за місце — визначає і порядок у списку, і слово поруч із назвою. */
enum class PlaceKind { Country, Oblast, Raion, City }

/**
 * Іменована територія з реальною рамкою.
 *
 * Рамка — не сама межа. Для області це прямокутник, описаний навколо полігону,
 * тому пакет завжди захоплює трохи сусідів; [landAreaKm2] існує саме щоб це
 * можна було сказати вголос, а не приховати.
 */
data class NamedPlace(
    val id: String,
    val nameUa: String,
    val nameEn: String,
    /** Область для району й міста; `null` для країни та самої області. */
    val parentUa: String?,
    val kind: PlaceKind,
    val northLat: Double,
    val eastLon: Double,
    val southLat: Double,
    val westLon: Double,
    /**
     * Площа самого полігону, км². Заповнена лише там, де її порахували з
     * геометрії — для районів і міст рамка прийшла з Nominatim без полігону,
     * і тут чесно `null`, а не площа прямокутника під виглядом площі місця.
     */
    val landAreaKm2: Double?,
    /** OSM-обʼєкт, з якого взято рамку. Порожньо для країни (обʼєднання областей). */
    val osmRef: String?,
) {
    val valid: Boolean
        get() = northLat > southLat && eastLon > westLon

    /** Площа прямокутника, км² — те, що насправді завантажується. */
    val boxAreaKm2: Double
        get() {
            val midLat = Math.toRadians((northLat + southLat) / 2.0)
            val h = (northLat - southLat) * KM_PER_DEG_LAT
            val w = (eastLon - westLon) * KM_PER_DEG_LAT * kotlin.math.cos(midLat)
            return h * w
        }

    fun contains(lat: Double, lon: Double): Boolean =
        lat in southLat..northLat && lon in westLon..eastLon

    private companion object {
        const val KM_PER_DEG_LAT = 111.32
    }
}
