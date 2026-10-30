package local.phantom.companion.core.data.evac

import local.phantom.companion.core.net.map.TagSelector

/** Чим саме є знайдена споруда — сертифіковане сховище чи імпровізоване укриття. */
enum class ShelterAssetType { BOMB_SHELTER, SCHOOL, ADMINISTRATIVE, PLACE_OF_WORSHIP }

/** Один щабель пошуку: чим питаємо, як називаємо, і чи це взагалі сховище. */
data class ShelterTier(
    val assetType: ShelterAssetType,
    val selectors: List<String>,
    val defaultNameUk: String,
    /** `true` лише для споруд, збудованих щоб витримати удар. Решта — просто стіни. */
    val certified: Boolean,
    /**
     * Під якою парою `category`/`subcategory` знахідка лягає в довідник. Для
     * сховища це `amenity=shelter`, бо знак піна береться звідси, — але сам
     * відбір іде за [selectors], тож навіс під цю пару не потрапляє.
     */
    val pinCategory: String,
    val pinSubcategory: String,
) {
    private val clauses = selectors.flatMap { TagSelector.clauses(it) }

    fun matches(tags: Map<String, String>, cat: String? = null, sub: String? = null): Boolean =
        TagSelector.matches(clauses, tags, cat, sub)
}

/**
 * Що PHANTOM має право назвати укриттям — одне оголошення на всі шляхи.
 *
 * `amenity=shelter` в OSM означає НАВІС: альтанка, зупинка, місце для куріння,
 * платформа. У київському паку таких 32, і жодна не є сховищем — серед них
 * «Альтанки для пікніка», «місце для куріння», «Бунгало», «Бельведер».
 * Сховищем обʼєкт робить лише `shelter_type` / `bunker_type` / `civil_shelter`,
 * і без цього тега відповіді «не знаю» чесніша за альтанку: під тривогою
 * людина побіжить туди впевнено, бо застосунок сказав.
 *
 * Селектори тримаються СПИСКОМ: Overpass приймає в одних дужках один ключ, тож
 * зшитий через «|» рядок не є «або-або» — сервер відповідає HTTP 400
 * `parse error: ']' expected - '|' found`, а це доїжджає до скла як
 * «Джерело мовчить», невідрізненно від обриву мережі. Саме тому мертвий чіп
 * «Укриття» нікому не впав в око (див. [TagSelector]).
 */
object ShelterTruth {

    val BOMB_SHELTER_SELECTORS = listOf(
        "shelter_type=bomb_shelter",
        "bunker_type=bomb_shelter",
        "civil_shelter=yes",
    )

    /**
     * Порядок, у якому питаємо на кожному кроці радіуса: сертифіковані сховища,
     * далі просто міцні стіни. Щабель нижче першого — це вже імпровізація, і
     * картка мусить це сказати.
     *
     * Входів метро й підвалів тут немає навмисно: зробити їх ціллю маршруту —
     * рішення власника, а не наслідок прибирання альтанок.
     */
    val TIERS = listOf(
        ShelterTier(
            ShelterAssetType.BOMB_SHELTER, BOMB_SHELTER_SELECTORS, "Бомбосховище",
            certified = true, pinCategory = "amenity", pinSubcategory = "shelter",
        ),
        ShelterTier(
            ShelterAssetType.SCHOOL, listOf("amenity=school"), "Школа",
            certified = false, pinCategory = "amenity", pinSubcategory = "school",
        ),
        ShelterTier(
            ShelterAssetType.ADMINISTRATIVE, listOf("building=administrative"), "Адмінбудівля",
            certified = false, pinCategory = "building", pinSubcategory = "administrative",
        ),
        ShelterTier(
            ShelterAssetType.PLACE_OF_WORSHIP, listOf("amenity=place_of_worship"), "Культова споруда",
            certified = false, pinCategory = "amenity", pinSubcategory = "place_of_worship",
        ),
    )

    /** Рядкова форма для полиць пошуку — розбирається назад у клаузи, а не йде в дужки як є. */
    val BOMB_SHELTER_SELECTOR: String = BOMB_SHELTER_SELECTORS.joinToString("|")

    fun tier(assetType: ShelterAssetType): ShelterTier = TIERS.first { it.assetType == assetType }

    fun isBombShelter(tags: Map<String, String>): Boolean = tier(ShelterAssetType.BOMB_SHELTER).matches(tags)

    /**
     * Значення `shelter_type`, якими OSM прямо підписує НАВІС. Вони не роблять
     * обʼєкт сховищем — вони кажуть, що це зупинка чи альтанка.
     */
    val CANOPY_SHELTER_TYPES = setOf(
        "public_transport", "picnic_shelter", "gazebo", "pavilion", "weather_shelter",
        "sun_shelter", "lean_to", "field_shelter", "rock_shelter", "shopping_cart",
    )

    /**
     * Чи заявлено про обʼєкт хоч якийсь захист — ширше за [isBombShelter]
     * (сюди входять підвал і підземний паркінг), але навіс не входить ніколи.
     * Це поріг для картки й для значка, не для маршруту під тривогою.
     */
    fun isProtective(tags: Map<String, String>): Boolean {
        if (tags["civil_shelter"] == "yes") return true
        val kind = tags["shelter_type"] ?: tags["bunker_type"] ?: return false
        return kind !in CANOPY_SHELTER_TYPES
    }

    /** Навіс, що видає себе за сховище: `amenity=shelter` без жодної заяви про захист. */
    fun isCanopyOnly(tags: Map<String, String>): Boolean =
        tags["amenity"] == "shelter" && !isProtective(tags)
}

/**
 * Скільки сертифікованих сховищ пакет НАРАХУВАВ, коли його пекли, — спечений
 * факт із заголовка, а не висновок із наявності ключа.
 *
 * Різниця не теоретична: після перепікання з правильними тегами 32 альтанки
 * понесуть власний `shelter_type=gazebo`, і «ключ присутній» стало б зеленим
 * там, де сертифікованих нуль.
 */
enum class ShelterRegistryVerdict {
    /** Пакета місць немає — шукати нема де. */
    ABSENT,

    /** Пакет не рахував сховищ або нарахував нуль. Він не має права говорити про місто. */
    SILENT,

    /** Пакет нарахував більше нуля. Навіть тоді порожнеча — це про пакет, не про місто. */
    PRESENT,
}
