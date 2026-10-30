package local.phantom.companion.core.geo.license

/**
 * Ліцензійний контур геоданих.
 *
 * Це не юридична примітка, а робоче обмеження архітектури. ODbL вимагає
 * відкривати ПОХІДНУ БАЗУ ДАНИХ — але не «створений твір» (відрендерений тайл,
 * картинка, результат аналізу) і не незалежний горизонтальний шар, який не
 * звʼязаний з OSM топологічно чи аналітично.
 *
 * Отже межа між тим, що можна продавати, і тим, що доведеться відкрити, проходить
 * не по файлах і не по модулях, а по тому, ЧИ ВІДБУВАЄТЬСЯ обʼєднання власних
 * даних з даними OSM. Якщо власний шар POI просто лежить поверх базової карти і
 * звʼязується з нею лише на рівні рендерингу — це збірка незалежних баз. Якщо ж
 * власні дані заповнюють прогалини в геометрії OSM або покращують її дорожній
 * граф — це вже похідна база, і комерційний шар доводиться відкривати.
 *
 * Тому статус ліцензії тут — властивість шару в коді, а [LicenseFirewall]
 * забороняє саме ту операцію, яка створює зобовʼязання.
 */
enum class LicenseClass {
    /** ODbL v1.0 — OSM і все, що з нього похідне. Share-Alike на рівні бази даних. */
    ODBL,

    /** CDLA-Permissive v2.0 — власні напрацювання Overture. Вільне комерційне використання. */
    CDLA_PERMISSIVE,

    /** CC-BY 4.0 та подібні: вільно, але з обовʼязковою атрибуцією. */
    CC_BY,

    /** Суспільне надбання / CC0 — без умов. */
    PUBLIC_DOMAIN,

    /** Власні дані PHANTOM. Закриті. */
    PROPRIETARY,

    /**
     * Джерело з прямою забороною комерційного або оборонного використання.
     * Такі шари не мають потрапляти у збірку взагалі — див. [LicenseFirewall].
     */
    RESTRICTED_NONCOMMERCIAL,
}

/**
 * Роль шару в конвеєрі — визначає, які операції над ним взагалі дозволені.
 */
enum class LayerRole {
    /** Базова підкладка: дороги, будівлі, межі. Рендериться у «створений твір». */
    BASEMAP,

    /** Незалежний горизонтальний шар поверх підкладки. */
    OVERLAY,

    /** Власні комерційні дані PHANTOM. */
    PROPRIETARY_DATA,

    /** Похідна база: результат зміни або доповнення геометрії базового джерела. */
    DERIVATIVE,
}

/**
 * Опис одного шару даних: звідки, під чим, як оновлюється, що треба показати.
 *
 * @param attribution текст, який зобовʼязані показати користувачу. Порожній
 *   рядок дозволений лише для [LicenseClass.PUBLIC_DOMAIN] і [LicenseClass.PROPRIETARY].
 * @param joinsOsmTopology чи виконує цей шар топологічне або аналітичне
 *   обʼєднання з даними OSM. Саме цей прапорець, а не походження даних, вирішує,
 *   чи виникає зобовʼязання Share-Alike.
 */
data class GeoLayer(
    val id: String,
    val title: String,
    val source: String,
    val license: LicenseClass,
    val role: LayerRole,
    val attribution: String,
    val joinsOsmTopology: Boolean = false,
    val updateMethod: String = "",
)

/** Порушення, знайдене [LicenseFirewall]. */
data class LicenseViolation(val layerId: String, val rule: String, val detail: String)

/**
 * Перевірка набору шарів на юридичну чистоту комерційної збірки.
 *
 * Викликається тестом, а не в рантаймі: порушення має ламати збірку в CI, а не
 * спливати претензією через рік після релізу.
 */
object LicenseFirewall {

    fun audit(layers: List<GeoLayer>): List<LicenseViolation> {
        val out = mutableListOf<LicenseViolation>()

        for (l in layers) {
            if (l.license == LicenseClass.RESTRICTED_NONCOMMERCIAL) {
                out += LicenseViolation(
                    l.id,
                    "restricted-source",
                    "Джерело «${l.source}» забороняє комерційне використання — шар не може бути у збірці.",
                )
            }

            val needsAttribution = l.license in setOf(
                LicenseClass.ODBL, LicenseClass.CC_BY, LicenseClass.CDLA_PERMISSIVE,
            )
            if (needsAttribution && l.attribution.isBlank()) {
                out += LicenseViolation(
                    l.id,
                    "missing-attribution",
                    "Ліцензія ${l.license} вимагає атрибуції, а її не задано.",
                )
            }

            // Найдорожча помилка: власний шар, що топологічно спирається на OSM,
            // перетворює комерційні дані на похідну базу з обовʼязковим відкриттям.
            if (l.role == LayerRole.PROPRIETARY_DATA && l.joinsOsmTopology) {
                out += LicenseViolation(
                    l.id,
                    "share-alike-contamination",
                    "Комерційний шар топологічно обʼєднується з OSM — це похідна база (ODbL), " +
                        "її доведеться відкрити. Звʼязуйте лише за стабільним ідентифікатором на рівні рендерингу.",
                )
            }

            if (l.role == LayerRole.DERIVATIVE && l.license == LicenseClass.PROPRIETARY) {
                out += LicenseViolation(
                    l.id,
                    "derivative-cannot-be-proprietary",
                    "Похідна база не може лишатись закритою, якщо походить з ODbL-джерела.",
                )
            }
        }
        return out
    }

    /** Зведений текст атрибуції для екрана «Джерела даних». */
    fun attributionLines(layers: List<GeoLayer>): List<String> =
        layers.filter { it.attribution.isNotBlank() }
            .map { it.attribution }
            .distinct()
            .sorted()
}
