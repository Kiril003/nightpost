package local.phantom.companion.core.geo.place

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Як зветься клапоть землі, збережений «як бачу».
 *
 * Досі — ніяк. Імʼя складалося з слова «регіон» і години збереження:
 * «регіон · 03.07 12:56». Це не назва землі, це відбиток часу, коли натиснули
 * кнопку. Що це за місце, скільки його і де воно — не сказано жодним словом, і
 * два записи поспіль виглядали однаково, навіть коли лежали за сто кілометрів
 * один від одного.
 *
 * Назва береться з [PlaceGazetteer] — тієї самої таблиці на 315 українських
 * місць, якою вже качають іменовані території. Беремо НАЙМЕНШЕ місце, що
 * містить центр клаптя: район точніший за область, місто точніше за район.
 * Поруч — власний розмір, бо «Київ» без розміру не відрізнити від «Київ і сто
 * кілометрів навколо».
 *
 * За межами України найменше, що відоме, — країна ([PlaceGazetteer.world]);
 * тоді назва це «Польща · 12 × 9 км». Коли й країни немає (океан, Антарктида),
 * назвою стають координати центру. Погана назва краще за фальшиву.
 */
object PlaceNaming {

    /**
     * Імʼя для прямокутника. Без часу: коли саме натиснули кнопку, видно з
     * порядку в списку, а місце з часу не виводиться ніяк.
     */
    fun forBox(northLat: Double, eastLon: Double, southLat: Double, westLon: Double): String {
        val lat = (northLat + southLat) / 2.0
        val lon = (eastLon + westLon) / 2.0
        val covering = PlaceGazetteer.coveringAnywhere(lat, lon)
        val where = covering.firstOrNull { it.kind != PlaceKind.Country }?.nameUa
            ?: covering.firstOrNull()?.nameUa
        val span = spanText(northLat, eastLon, southLat, westLon)
        return if (where != null) "$where · $span" else "${fmt(lat)}, ${fmt(lon)} · $span"
    }

    /**
     * Найменше назване місце, що містить точку, — слово, а не рамка.
     *
     * Саме це стає міткою маршруту («до Львова»): коридор мусить зватися своєю
     * ціллю, бо за нею його впізнають і за нею його дедуплікують. `null` —
     * назви немає, і тоді коридор лишається просто «коридором маршруту».
     */
    fun placeAt(lat: Double, lon: Double): String? =
        PlaceGazetteer.coveringAnywhere(lat, lon).firstOrNull()?.nameUa

    /**
     * Назва, яку можна поставити метою маршруту.
     *
     * [placeAt] віддає НАЙМЕНШЕ відоме місце, а довідник знає далеко не кожне
     * село: для точки під Кукільнею найменшим відомим виявиться «Житомирська
     * область». Підписати нею двадцятиметрову прогулянку означало б збрехати
     * упевненим тоном — рівно те, чого тут не можна.
     *
     * Тому мета приймає лише населений пункт. Немає його в довіднику — немає й
     * назви, і тоді чесніше показати координати, ніж область.
     */
    fun settlementAt(lat: Double, lon: Double): String? =
        PlaceGazetteer.coveringAnywhere(lat, lon)
            .firstOrNull { it.kind == PlaceKind.City }
            ?.nameUa

    /**
     * Скільки землі, у кілометрах по сторонах.
     *
     * Саме сторони, а не площа: людина вибирає, чи влізе це в телефон, і «12 ×
     * 9 км» відповідає на це одразу, а «108 км²» треба спершу уявити.
     */
    fun spanText(northLat: Double, eastLon: Double, southLat: Double, westLon: Double): String {
        val midLat = Math.toRadians((northLat + southLat) / 2.0)
        val h = abs(northLat - southLat) * KM_PER_DEG_LAT
        val w = abs(eastLon - westLon) * KM_PER_DEG_LAT * cos(midLat)
        return "${km(w)} × ${km(h)} км"
    }

    private fun km(v: Double): String = when {
        v < 1.0 -> "%.1f".format(java.util.Locale("uk", "UA"), v)
        else -> v.roundToInt().toString()
    }

    /**
     * Крапка, а не кома — і це не недогляд локалі.
     *
     * Українська кома дала б «49,9720, 28,7957»: чотири числа, три коми, і жодна
     * не каже, де кінчається широта. Такий рядок читають уголос у рацію, і там
     * ця двозначність коштує. Координати в світі пишуть крапкою.
     */
    private fun fmt(v: Double) = "%.4f".format(java.util.Locale.US, v)

    private const val KM_PER_DEG_LAT = 111.32
}
