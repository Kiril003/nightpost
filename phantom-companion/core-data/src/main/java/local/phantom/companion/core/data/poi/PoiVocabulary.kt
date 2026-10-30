package local.phantom.companion.core.data.poi

import local.phantom.companion.core.data.evac.ShelterTruth

/**
 * OSM tags → the words a person actually types.
 *
 * The gazetteer used to index one column: `name`. That is why «аптека» found
 * nothing — a pharmacy is called «Подорожник», and the word «аптека» is
 * nowhere in its row. OSM knows it is a pharmacy (`amenity=pharmacy`), the tag
 * was simply never turned into text anybody could search for.
 *
 * So every place carries a second indexed column built here: its kind in both
 * languages, its cuisine, its brand and operator, its street and number, and
 * the handful of attributes people ask by («цілодобово», «дизель», «wifi»).
 * The table is data, not code — a new category is one line.
 */
object PoiVocabulary {

    /** Words to index alongside the name. Empty when the tags say nothing useful. */
    fun keywords(
        tags: Map<String, String>,
        category: String? = null,
        subcategory: String? = null,
    ): String {
        val out = LinkedHashSet<String>()

        fun add(text: String?) {
            if (text.isNullOrBlank()) return
            text.split(SPLIT).forEach { w -> if (w.length > 1) out += w.lowercase() }
        }

        subcategory?.let { add(kindWords[it]) }
        for (key in PLACE_KEYS) tags[key]?.let { add(kindWords[it] ?: it) }
        if (category == "highway" && tags["highway"] == "bus_stop") add(kindWords["bus_stop"])

        tags["cuisine"]?.split(';')?.forEach { add(cuisineWords[it.trim()] ?: it.trim()) }
        tags["sport"]?.split(';')?.forEach { add(kindWords[it.trim()] ?: it.trim()) }
        tags["healthcare:speciality"]?.split(';')?.forEach { add(kindWords[it.trim()]) }

        // «Укриття» чіпляється лише до захисного. Слово стояло в рядку
        // `shelter`, тобто на КОЖНОМУ навісі: офлайновий пошук «укриття»
        // повертав «Альтанку» першою.
        if (ShelterTruth.isProtective(tags)) add(SHELTER_WORDS)

        for (key in NAME_KEYS) add(tags[key])
        tags["addr:street"]?.let { add(it); add(tags["addr:housenumber"]) }

        for ((key, value) in tags) {
            val flag = flagWords["$key=$value"] ?: continue
            add(flag)
        }
        if (tags["opening_hours"] == "24/7") add(flagWords["opening_hours=24/7"])

        return out.joinToString(" ")
    }

    /**
     * What a typed word *means* as an OSM value — «апте» → `pharmacy`.
     *
     * This is the half a name index can never do: the operator is naming a
     * kind of place, not a place. Prefix-matched from three characters, because
     * search runs on every keystroke and «запр» is already unambiguous.
     * Returns every value that matches, so «магазин» browses supermarket AND
     * convenience AND greengrocer rather than picking one for him.
     */
    fun intent(raw: String): List<String> = kindOf(raw).kinds

    /** Вид місця і те, що лишилось від запиту поза ним: «Оперний театр» → theatre + «оперний». */
    data class Kind(val kinds: List<String>, val rest: String)

    fun kindOf(raw: String): Kind {
        val q = normalize(raw)
        if (q.length < MIN_INTENT_CHARS) return NO_KIND
        exactIntent(q).let { if (it.isNotEmpty()) return Kind(it, "") }
        val words = q.split(SPLIT).filter { it.isNotBlank() }
        if (words.size > 1) {
            var out: Set<String> = emptySet()
            val rest = ArrayList<String>()
            for (w in words) {
                val kinds = synonymIndex[w]
                when {
                    kinds == null -> if (w.length >= MIN_INTENT_CHARS && w !in FILLER) rest += w
                    out.isEmpty() -> out = LinkedHashSet(kinds)
                    // «railway station» — вокзал, а не будь-яка «station» (АЗС теж «petrol station»).
                    else -> out = out.intersect(kinds.toSet()).ifEmpty { out + kinds }
                }
            }
            return if (out.isEmpty()) NO_KIND else Kind(out.toList(), rest.joinToString(" "))
        }
        return Kind(singleWordIntent(q), "")
    }

    private val NO_KIND = Kind(emptyList(), "")

    private val FILLER = setOf("найближча", "найближчий", "найближче", "поруч", "тут", "хочу", "треба", "where", "near", "the")

    private fun singleWordIntent(q: String): List<String> {
        // «apteka» і «аптіка»: вид місця, набраний латиницею або з однією опискою.
        val folded = PoiTextMatch.fold(q)
        if (folded.any { it in 'a'..'z' }) {
            exactIntent(PoiTextMatch.cyrillize(folded)).let { if (it.isNotEmpty()) return it }
        }
        return if (q.length >= MIN_TYPO_CHARS) typoIntent(q) else emptyList()
    }

    private fun typoIntent(q: String): List<String> {
        val out = LinkedHashSet<String>()
        for (word in synonymWords) {
            if (kotlin.math.abs(word.length - q.length) <= 1 && withinOneEdit(word, q)) {
                synonymIndex[word]?.let { out.addAll(it) }
            }
        }
        return out.toList()
    }

    private fun withinOneEdit(a: String, b: String): Boolean {
        if (a == b) return true
        val (s, l) = if (a.length <= b.length) a to b else b to a
        var i = 0
        var j = 0
        var edits = 0
        while (i < s.length && j < l.length) {
            if (s[i] == l[j]) {
                i++
                j++
                continue
            }
            if (++edits > 1) return false
            if (s.length == l.length) i++
            j++
        }
        return edits + (l.length - j) + (s.length - i) <= 1
    }

    private fun exactIntent(q: String): List<String> {
        // Sorted word list + binary search, not a scan of two hundred rows
        // splitting strings — this runs on every keystroke.
        synonymIndex[q]?.let { return it }
        val from = synonymWords.binarySearch(q).let { if (it < 0) -it - 1 else it }
        val out = LinkedHashSet<String>()
        var i = from
        while (i < synonymWords.size && synonymWords[i].startsWith(q)) {
            synonymIndex[synonymWords[i]]?.let { out.addAll(it) }
            i++
        }
        return out.toList()
    }

    /**
     * Чи знає таблиця такий вид місця.
     *
     * Потрібно тому, що `needs_lexicon.txt` називає види місць рядками, і
     * помилка в тому рядку не впала б, а тихо шукала б категорію, якої не
     * несе жодне місце — порожня видача без жодної скарги. NeedLexiconTest
     * звіряє кожен ключ асета з цією таблицею.
     */
    fun knows(subcategory: String): Boolean = subcategory in kindWords

    /** Усі слова, якими людина називає цей вид місця. */
    fun wordsOf(subcategory: String): List<String> =
        kindWords[subcategory]?.split(SPLIT)?.map(::normalize)?.filter { it.isNotEmpty() }.orEmpty()

    /** The label to show for an OSM value, in Ukrainian. */
    fun label(subcategory: String?): String? =
        subcategory?.let { kindWords[it]?.substringBefore(' ') }

    /** [label], але з тегами: `amenity=shelter` — «навіс», доки теги не скажуть інше. */
    fun label(subcategory: String?, tags: Map<String, String>): String? = when {
        !ShelterTruth.isProtective(tags) -> label(subcategory)
        ShelterTruth.isBombShelter(tags) -> "бомбосховище"
        else -> "укриття"
    }

    /**
     * Український відповідник для `key=value`, коли словник значень цього
     * тега закритий і кожне значення можна назвати чесно — `barrier`,
     * `access`, `surface`, `smoothness`, `shelter_type`, `bunker_type`,
     * `heritage`. Немає в таблиці — null: картка мовчить про значення,
     * замість друкувати його як є (basement, permissive, lift_gate…).
     *
     * Той самий принцип, що [label]: одна таблиця цього файлу, а не друга
     * поруч, яка з часом розійдеться з цією.
     */
    fun tagValueLabel(key: String, value: String): String? = tagValueWords["$key=$value"]

    private val tagValueWords: Map<String, String> by lazy { parse(TAG_VALUES) }

    /**
     * `charge` пише ціну вільним текстом і часто дописує англійське слово
     * одиниці часу («10 CZK/hour», виміряно живим Overpass по Остраві
     * 15.09) — перекладається лише воно; число й код валюти (CZK, UAH…)
     * зрозумілі без перекладу.
     */
    fun translateChargeUnit(raw: String): String {
        var out = raw
        for ((en, uk) in CHARGE_UNIT_WORDS) out = out.replace(Regex("(?i)\\b$en\\b"), uk)
        return out
    }

    private val CHARGE_UNIT_WORDS = listOf(
        "hours" to "год", "hour" to "год",
        "minutes" to "хв", "minute" to "хв", "mins" to "хв", "min" to "хв",
        "days" to "дні", "day" to "день",
        "week" to "тиждень", "month" to "місяць",
    )

    private const val SHELTER_WORDS = "укриття сховище бомбосховище бункер shelter"

    /** Мережа, набрана кирилицею, а в довіднику записана латиницею: «Макдональдс» → «McDonald's». */
    fun brandSpelling(raw: String): String? = BRAND_SPELLINGS[PoiTextMatch.fold(raw)]

    private val BRAND_SPELLINGS: Map<String, String> by lazy {
        mapOf(
            "макдональдс" to "McDonald's", "макдональдз" to "McDonald's", "макдак" to "McDonald's",
            "мак дональдс" to "McDonald's", "кфс" to "KFC", "старбакс" to "Starbucks",
            "бургер кінг" to "Burger King", "вог" to "WOG", "шелл" to "Shell", "шел" to "Shell",
            "новус" to "Novus", "ашан" to "Auchan", "лідл" to "Lidl",
            "білла" to "Billa", "альберт" to "Albert", "пенні" to "Penny", "теско" to "Tesco",
        ).mapKeys { PoiTextMatch.fold(it.key) }
    }

    fun normalize(s: String): String =
        s.trim().lowercase().replace('\'', 'ʼ').replace('’', 'ʼ').replace('`', 'ʼ')

    private const val MIN_INTENT_CHARS = 3
    private const val MIN_TYPO_CHARS = 5
    private val SPLIT = Regex("[\\s,;]+")

    private val PLACE_KEYS = listOf(
        "amenity", "shop", "leisure", "tourism", "healthcare", "craft",
        "historic", "office", "emergency", "railway", "public_transport",
        "man_made", "club", "place",
    )

    private val NAME_KEYS = listOf(
        "brand", "operator", "operator:uk", "network", "official_name",
        "alt_name", "short_name", "old_name", "name:en", "int_name", "ref",
    )

    private val kindWords: Map<String, String> by lazy { parse(KINDS) }

    /** typed word → every OSM value it can mean. Built once. */
    private val synonymIndex: Map<String, List<String>> by lazy {
        val acc = LinkedHashMap<String, MutableList<String>>()
        for ((value, words) in kindWords) {
            for (word in words.split(SPLIT)) {
                val w = normalize(word)
                if (w.length < MIN_INTENT_CHARS) continue
                acc.getOrPut(w) { mutableListOf() }.let { if (value !in it) it.add(value) }
            }
        }
        acc
    }

    private val synonymWords: List<String> by lazy { synonymIndex.keys.sorted() }
    private val cuisineWords: Map<String, String> by lazy { parse(CUISINES) }
    private val flagWords: Map<String, String> by lazy { parse(FLAGS) }

    private fun parse(table: String): Map<String, String> =
        table.trimIndent().lineSequence()
            .mapNotNull { line ->
                val i = line.indexOf('|')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }
            .toMap()
}

private const val KINDS = """
fuel|заправка азс паливо бензин дизель пальне gas fuel petrol station
charging_station|зарядка електрозаправка зарядна charging charger ev
cafe|кава кавʼярня кафе поїсти перекусити coffee cafe espresso
restaurant|ресторан їжа поїсти обід вечеря food restaurant
fast_food|фастфуд бургер шаурма фаст поїсти перекусити fastfood burger snack
bar|бар коктейлі bar drinks
pub|паб пиво pub beer
biergarten|пивна bar beer
nightclub|клуб нічний club
ice_cream|морозиво ice cream
bakery|пекарня випічка хліб bakery bread
confectionery|солодощі цукерки торт sweets cake
pastry|десерти тістечка pastry
pharmacy|аптека ліки pharmacy drugstore
chemist|побутова хімія косметика drugstore
hospital|лікарня шпиталь hospital emergency
clinic|клініка поліклініка амбулаторія clinic
doctors|лікар терапевт doctor
dentist|стоматологія зуби дантист dentist
veterinary|ветеринар ветклініка тварини vet
laboratory|лабораторія аналізи lab
physiotherapist|фізіотерапія реабілітація
optometrist|окуліст оптика зір
bank|банк відділення bank
atm|банкомат готівка atm cash
bureau_de_change|обмін валют обмінник exchange currency
post_office|пошта поштове відділення post
parcel_locker|поштомат посилка locker
police|поліція міліція police
fire_station|пожежна ДСНС fire
townhall|міськрада ратуша адміністрація townhall
courthouse|суд court
prosecutor|прокуратура
government|держава цнап адміністрація government office
embassy|посольство консульство embassy
library|бібліотека library books
school|школа гімназія ліцей school
kindergarten|садок дитсадок дошкільний kindergarten
university|університет вуз university
college|коледж технікум college
driving_school|автошкола driving
music_school|музична школа
language_school|мовна школа курси
research_institute|інститут наука
marketplace|ринок базар market
supermarket|супермаркет магазин продукти маркет grocery
convenience|магазин продукти мінімаркет shop
greengrocer|овочі фрукти vegetables
butcher|мʼясо мясний butcher meat
seafood|риба морепродукти fish
alcohol|алкоголь вино горілка alcohol
wine|вино wine
beverages|напої вода drinks
kiosk|кіоск ларьок kiosk
department_store|універмаг
mall|тц трц торговий центр mall
clothes|одяг вбрання clothes
shoes|взуття shoes
bag|сумки рюкзаки bags
jewelry|ювелірний золото прикраси jewelry
watches|годинники watches
electronics|електроніка техніка electronics
mobile_phone|телефони мобільні звязок phones
computer|компʼютери ноутбуки computer
hifi|аудіо техніка
hardware|інструменти господарські hardware tools
doityourself|будматеріали ремонт diy
paint|фарби
furniture|меблі furniture
houseware|посуд товари для дому
florist|квіти квітковий flowers
garden_centre|садовий рослини garden
books|книги книгарня books
stationery|канцтовари канцелярія stationery
newsagent|преса газети
toys|іграшки дитячий toys
sports|спорттовари спорт sports
bicycle|велосипеди велозапчастини bike bicycle
outdoor|туризм спорядження outdoor
car|автосалон авто машини car dealer
car_repair|сто автосервіс ремонт авто repair mechanic
car_parts|автозапчастини запчастини parts
tyres|шиномонтаж шини гума tyre tires
car_wash|мийка автомийка wash
motorcycle|мотоцикли мото moto
hairdresser|перукарня стрижка барбершоп hair barber
beauty|салон краси манікюр косметологія beauty spa
massage|масаж massage
tattoo|тату татуювання tattoo
laundry|пральня прання laundry
dry_cleaning|хімчистка cleaning
tailor|ательє пошиття tailor
shoe_repair|ремонт взуття
copyshop|друк копіцентр ксерокс print copy
photo|фото фотостудія photo
travel_agency|турагенція тури travel
funeral_directors|ритуальні похоронне
pet|зоомагазин тварини корм pet
pet_grooming|грумінг тварини
optician|оптика окуляри optics glasses
medical_supply|медтехніка
tobacco|тютюн сигарети tobacco
e-cigarette|вейп електронні сигарети
gift|подарунки сувеніри gifts
second_hand|секонд хенд second
charity|благодійність
variety_store|все по одній ціні
hotel|готель ночівля переночувати ночувати житло hotel sleep
hostel|хостел переночувати ночувати житло hostel
guest_house|гостьовий дім переночувати житло apartments
apartment|апартаменти квартира посуточно
motel|мотель motel
museum|музей museum
gallery|галерея мистецтво gallery
artwork|скульптура памʼятник арт art
attraction|памʼятка визначне attraction
viewpoint|оглядовий майданчик краєвид viewpoint
theme_park|парк розваг атракціони
zoo|зоопарк тварини zoo
aquarium|океанаріум акваріум
information|туристична інформація
monument|памʼятник монумент monument
memorial|меморіал памʼятний знак
castle|замок фортеця castle
ruins|руїни
archaeological_site|розкопки археологія
place_of_worship|церква храм собор костел мечеть синагога church
monastery|монастир лавра
cinema|кінотеатр кіно cinema movie
theatre|театр theatre
arts_centre|будинок культури арт центр
community_centre|громадський центр пункт незламності незламність хаб warming point invincibility
social_facility|соцзахист допомога
music_venue|концертний майданчик клуб
casino|казино
gambling|букмекер ставки лотерея
park|парк сквер зелена зона park
garden|сад ботанічний garden
playground|дитячий майданчик гірка playground
pitch|майданчик поле спорт pitch
sports_centre|спорткомплекс спортцентр sport
fitness_centre|спортзал тренажерний фітнес gym fitness
swimming_pool|басейн pool swim
water_park|аквапарк
stadium|стадіон арена stadium
sports_hall|спортзал
dance|танці студія dance
golf_course|гольф
horse_riding|кінний спорт коні
marina|причал яхти
slipway|спуск човнів
nature_reserve|заповідник природа
dog_park|вигул собак
picnic_table|пікнік
firepit|мангал вогнище
sauna|сауна лазня баня sauna
beach_resort|пляж beach
parking|паркінг парковка стоянка parking park
parking_entrance|вʼїзд на паркінг
bicycle_parking|велопарковка bike parking
bicycle_rental|прокат велосипедів bike rental
motorcycle_parking|мотопарковка
car_rental|прокат авто оренда машини rental
car_sharing|каршерінг
taxi|таксі taxi
bus_station|автовокзал автостанція bus station
bus_stop|зупинка автобус bus stop
tram_stop|трамвайна зупинка трамвай tram
station|вокзал станція залізниця station train railway
halt|платформа зупинка потяг
subway_entrance|метро вхід у метро вихід subway metro
platform|зупинка платформа платформа stop
stop_position|зупинка
ferry_terminal|порт паром
toilets|туалет вбиральня toilet wc restroom
drinking_water|питна вода water
shower|душ
spring|джерело криниця вода water spring
water_well|криниця колодязь свердловина вода water well
water_tap|колонка водорозбірна вода water tap
water_tank|резервуар води вода water tank
water_works|водоканал водозабір вода
ford|брід переправа вбрід ford
stepping_stones|брід камені переправа
shelter|навіс альтанка зупинка canopy shelter
bench|лавка
fountain|фонтан fountain
clock|годинник
waste_basket|урна
recycling|переробка сортування
water_point|вода
telephone|телефон таксофон
internet_cafe|інтернет кафе компʼютерний клуб
coworking_space|коворкінг coworking
conference_centre|конференц центр
exhibition_centre|виставковий центр expo
studio|студія радіо
childcare|няня дитячий
animal_shelter|притулок тварини
vehicle_inspection|техогляд
driver_training|автодром
lawyer|юрист адвокат нотаріус lawyer
notary|нотаріус
accountant|бухгалтер
insurance|страхування страхова insurance
estate_agent|нерухомість ріелтор realty
employment_agency|робота вакансії
it|іт компанія software
company|офіс компанія
telecommunication|звязок оператор інтернет провайдер
newspaper|редакція газета
association|асоціація спілка
ngo|громадська організація
political_party|партія
religion|релігійна організація
diplomatic|дипломатична місія
electrician|електрик
plumber|сантехнік
carpenter|столяр
locksmith|слюсар ключі замки
shoemaker|швець
jeweller|ювелір
photographer|фотограф
brewery|броварня пиво
tower|вежа
lighthouse|маяк
water_tower|водонапірна вежа
windmill|млин
obelisk|обеліск
ambulance_station|швидка допомога
defibrillator|дефібрилятор
fire_hydrant|гідрант
square|площа майдан square
neighbourhood|мікрорайон район
suburb|район
quarter|квартал
country|країна country
oblast|область region
raion|район district
city|місто city
town|місто town
village|село village
hamlet|хутір hamlet
"""

private const val CUISINES = """
pizza|піца піцерія pizza
sushi|суші роли sushi
burger|бургер burger
coffee_shop|кава coffee
kebab|шаурма кебаб kebab
georgian|грузинська хачапурі хінкалі georgian
italian|італійська паста italian
japanese|японська japanese
chinese|китайська chinese
ukrainian|українська борщ вареники
asian|азійська asian
seafood|морепродукти риба seafood
steak_house|стейк мясо steak
vegetarian|вегетаріанська веган vegetarian
vegan|веган vegan
breakfast|сніданки breakfast
bakery|випічка bakery
ice_cream|морозиво ice cream
sandwich|сендвічі бутерброди
noodle|локшина вок noodles
chicken|курка chicken
fish_and_chips|риба
indian|індійська indian
mexican|мексиканська mexican
thai|тайська thai
turkish|турецька turkish
korean|корейська korean
american|американська american
french|французька french
greek|грецька greek
lebanese|ліванська
regional|місцева кухня
international|міжнародна
"""

private const val FLAGS = """
opening_hours=24/7|цілодобово 24 години нонстоп
atm=yes|банкомат готівка atm
wheelchair=yes|доступно візок інклюзивно wheelchair
wheelchair=limited|частково доступно
drive_through=yes|драйв авто вікно drive
takeaway=yes|навинос забрати takeaway
takeaway=only|навинос takeaway
delivery=yes|доставка delivery
outdoor_seating=yes|тераса літній майданчик надворі terrace
internet_access=wlan|wifi вайфай інтернет
internet_access=yes|wifi інтернет
self_service=yes|самообслуговування
air_conditioning=yes|кондиціонер
smoking=no|без куріння
fee=no|безкоштовно
fuel:diesel=yes|дизель солярка diesel
fuel:lpg=yes|газ пропан скраплений lpg
fuel:octane_95=yes|95 бензин
fuel:electricity=yes|електро зарядка
socket:type2=yes|type2 зарядка
diet:vegetarian=yes|вегетаріанське
access=customers|для клієнтів
parking=underground|підземний паркінг
"""

/**
 * Значення закритого словника → один чесний український відповідник.
 *
 * Джерело значень: OSM Wiki (Key:barrier, Key:access, Key:surface,
 * Key:smoothness, Key:shelter_type, Key:bunker_type, Key:heritage) плюс те,
 * що живий Overpass справді повернув для Острави 15.09 (barrier, access,
 * fee — розподіл значень звірено; generator:output/charging_station:output
 * у вибірці не траплялись, тому їх тут нема — PlaceDossier.formatPower
 * пропускає їх окремо, за формою значення, а не за цією таблицею).
 * `shelter_type`/`bunker_type` беруть лише значення, які вже знає
 * [local.phantom.companion.core.data.evac.ShelterTruth] (канопи й
 * `bomb_shelter`) — жодного нового вигаданого слова.
 */
private const val TAG_VALUES = """
shelter_type=basement|підвал
shelter_type=bomb_shelter|бомбосховище
shelter_type=public_transport|зупинка громадського транспорту
shelter_type=picnic_shelter|альтанка для пікніка
shelter_type=gazebo|альтанка
shelter_type=pavilion|павільйон
shelter_type=weather_shelter|навіс від негоди
shelter_type=sun_shelter|навіс від сонця
shelter_type=lean_to|відкритий навіс
shelter_type=field_shelter|польовий навіс
shelter_type=rock_shelter|навіс під скелею
shelter_type=shopping_cart|накриття для візків
bunker_type=bomb_shelter|бомбосховище
bunker_type=pillbox|дот
bunker_type=blockhouse|дзот
bunker_type=missile_silo|шахта ракетної установки
bunker_type=munitions|склад боєприпасів
bunker_type=gun_emplacement|вогнева позиція
bunker_type=observation_bunker|спостережний бункер
bunker_type=technical|технічний бункер
bunker_type=other|бункер
barrier=gate|ворота
barrier=lift_gate|шлагбаум
barrier=toll_booth|КПП / пункт пропуску
barrier=bollard|стовпчик
barrier=block|бетонний блок
barrier=swing_gate|розпашні ворота
barrier=turnstile|турнікет
barrier=full-height_turnstile|турнікет на всю висоту
barrier=cycle_barrier|велобарʼєр
barrier=jersey_barrier|бетонний відбійник
barrier=fence|паркан
barrier=wall|стіна
barrier=hedge|живопліт
barrier=ditch|рів
barrier=entrance|прохід
barrier=kerb|бордюр
barrier=planter|вазон-обмежувач
barrier=kissing_gate|хвіртка-вертушка
barrier=stile|перелаз
barrier=chain|ланцюг
barrier=motorcycle_barrier|мотобарʼєр
barrier=debris|завал
access=private|приватна територія
access=no|проїзд/прохід заборонено
access=permissive|дозволено власником (може закритись)
access=customers|лише для клієнтів
access=permit|лише за перепусткою
access=destination|лише для місцевого руху
access=delivery|лише для доставки
access=agricultural|лише для сільгосптехніки
access=forestry|лише для лісової техніки
surface=asphalt|асфальт
surface=paved|тверде покриття
surface=paving_stones|бруківка (плитка)
surface=concrete|бетон
surface=cobblestone|бруківка (кругляк)
surface=sett|брукований камінь
surface=unpaved|ґрунтова дорога
surface=gravel|гравій
surface=fine_gravel|дрібний гравій
surface=compacted|утрамбоване покриття
surface=dirt|ґрунт
surface=ground|ґрунт
surface=grass|трава
surface=sand|пісок
surface=mud|багнюка
surface=wood|деревʼяний настил
surface=metal|металевий настил
surface=artificial_turf|штучне покриття
smoothness=excellent|відмінна
smoothness=good|добра
smoothness=intermediate|середня
smoothness=bad|погана
smoothness=very_bad|дуже погана
smoothness=horrible|жахлива
smoothness=very_horrible|непроїзна
smoothness=impassable|непрохідна
heritage=1|памʼятка світового значення (ЮНЕСКО)
heritage=2|памʼятка національного значення
heritage=3|памʼятка регіонального значення
heritage=4|памʼятка місцевого значення
heritage=yes|памʼятка під охороною
"""
