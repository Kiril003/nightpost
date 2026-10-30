package local.phantom.companion.core.nav

import local.phantom.companion.core.data.evac.ShelterTruth

/**
 * OSM Spatial Universe — Complete taxonomy of OpenStreetMap features for wartime,
 * survival, tactical transit, and civil resilience in PHANTOM OS / PHANTOM Companion.
 */
object OsmSpatialUniverse {

    enum class Domain(val titleUk: String, val icon: String) {
        ENERGY("Енергія та живлення", "⚡"),
        WATER("Водна безпека", "💧"),
        CIVIL_DEFENSE("Укриття та захист", "🛡️"),
        MEDICAL("Медицина та допомога", "🏥"),
        BARRIERS_TRANSIT("Барʼєри та перешкоди", "🚧"),
        COMMS("Звʼязок та логістика", "📡"),
        FUEL_AUTO("Пальне та сервіс", "⛽"),
        PROVISIONS("Продовольство", "🍞"),
        STATE_CIVIC("Держава та волонтери", "🏛️"),
        TERRAIN_LANDMARKS("Рельєф та орієнтири", "🌲"),
    }

    data class SpatialCategory(
        val id: String,
        val labelUk: String,
        val emoji: String,
        val domain: Domain,
        val selector: String,
        val tacticalUsageReasonUk: String,
        val isWartimeCritical: Boolean = false,
        val keywords: List<String> = emptyList(),
        val quickFilter: Boolean = false,
    )

    val catalog: List<SpatialCategory> = listOf(
        // ── 1. Енергія та живлення
        SpatialCategory(
            id = "energy_generator",
            labelUk = "Генератори",
            emoji = "⚡",
            domain = Domain.ENERGY,
            selector = "generator:source~\"diesel|gas|petrol|solar\"|power=generator",
            tacticalUsageReasonUk = "Автономне живлення під час відключень.",
            isWartimeCritical = true,
            keywords = listOf("генератор", "дизель", "живлення", "світло", "електрика", "power", "generator", "блекаут"),
            quickFilter = true,
        ),
        SpatialCategory(
            id = "energy_ev_charging",
            labelUk = "Зарядні станції (EV/Power)",
            emoji = "🔋",
            domain = Domain.ENERGY,
            selector = "amenity=charging_station",
            tacticalUsageReasonUk = "Заряджання електромобілів, портативних станцій EcoFlow та павербанків.",
            isWartimeCritical = true,
            keywords = listOf("зарядка", "електрозаправка", "ev", "charging", "ecoflow", "павербанк", "станція зарядки"),
            quickFilter = true,
        ),
        SpatialCategory(
            id = "energy_substation",
            labelUk = "Трансформаторні підстанції",
            emoji = "⚡",
            domain = Domain.ENERGY,
            selector = "power~\"substation|transformer\"",
            tacticalUsageReasonUk = "Критична енергетична інфраструктура; враховується як зона підвищеного ризику під час повітряних ударів.",
            isWartimeCritical = false,
            keywords = listOf("підстанція", "трансформатор", "леп", "substation", "transformer"),
        ),

        // ── 2. Водна безпека
        SpatialCategory(
            id = "water_drinking",
            labelUk = "Питна вода / Бювети",
            emoji = "🚰",
            domain = Domain.WATER,
            selector = "amenity=drinking_water",
            tacticalUsageReasonUk = "Безперервний доступ до перевіреної питної води при знеструмленні насосних станцій.",
            isWartimeCritical = true,
            keywords = listOf("вода", "питна", "бювет", "колонка", "водозабір", "drinking water", "water"),
            quickFilter = true,
        ),
        SpatialCategory(
            id = "water_well",
            labelUk = "Свердловини та криниці",
            emoji = "💧",
            domain = Domain.WATER,
            selector = "man_made=water_well",
            tacticalUsageReasonUk = "Глибокі артезіанські свердловини та громадські криниці для автономного водопостачання.",
            isWartimeCritical = true,
            keywords = listOf("свердловина", "криниця", "колодязь", "артезіанська", "well"),
        ),
        SpatialCategory(
            id = "water_spring",
            labelUk = "Природні джерела",
            emoji = "⛲",
            domain = Domain.WATER,
            selector = "natural=spring",
            tacticalUsageReasonUk = "Природні виходи підземних вод у міських парках та за містом для надзвичайного забору.",
            isWartimeCritical = true,
            keywords = listOf("джерело", "родник", "джерельце", "spring", "потік"),
        ),

        // ── 3. Укриття та захист
        SpatialCategory(
            id = "defense_bomb_shelter",
            labelUk = "Бомбосховища цивільного захисту",
            emoji = "🛡️",
            domain = Domain.CIVIL_DEFENSE,
            selector = ShelterTruth.BOMB_SHELTER_SELECTOR,
            tacticalUsageReasonUk = "Укриття цивільного захисту.",
            isWartimeCritical = true,
            keywords = listOf("укриття", "бомбосховище", "сховище", "бункер", "shelter", "bomb shelter", "захист"),
            quickFilter = true,
        ),
        SpatialCategory(
            id = "defense_subway_shelter",
            labelUk = "Станції метро (Укриття)",
            emoji = "Ⓜ️",
            domain = Domain.CIVIL_DEFENSE,
            selector = "railway=subway_entrance",
            tacticalUsageReasonUk = "Глибокі підземні станції метрополітену з автономною вентиляцією та захистом від балістики.",
            isWartimeCritical = true,
            keywords = listOf("метро", "станція метро", "підземка", "metro", "subway"),
            quickFilter = true,
        ),
        SpatialCategory(
            id = "defense_defibrillator",
            labelUk = "Дефібрилятори (AED)",
            emoji = "❤️",
            domain = Domain.CIVIL_DEFENSE,
            selector = "emergency=defibrillator",
            tacticalUsageReasonUk = "Автоматичні зовнішні дефібрилятори в публічних місцях для реанімації при зупинці серця.",
            isWartimeCritical = true,
            keywords = listOf("дефібрилятор", "aed", "серце", "реанімація", "defibrillator"),
        ),
        SpatialCategory(
            id = "defense_fire_station",
            labelUk = "Пожежно-рятувальні частини ДСНС",
            emoji = "🚒",
            domain = Domain.CIVIL_DEFENSE,
            selector = "amenity=fire_station",
            tacticalUsageReasonUk = "Пункти базування рятувальної техніки ДСНС для ліквідації наслідків прильотів і пожеж.",
            isWartimeCritical = true,
            keywords = listOf("пожежна", "дснс", "рятувальники", "мнс", "пожежники", "fire station", "fire"),
        ),
        SpatialCategory(
            id = "defense_police",
            labelUk = "Поліція",
            emoji = "🚓",
            domain = Domain.CIVIL_DEFENSE,
            selector = "amenity=police",
            tacticalUsageReasonUk = "Відділки національної поліції та пункти цілодобового чергування правоохоронців.",
            isWartimeCritical = false,
            keywords = listOf("поліція", "райвідділок", "патруль", "police", "відділ поліції"),
        ),

        // ── 4. Медицина та допомога
        SpatialCategory(
            id = "med_pharmacy",
            labelUk = "Аптеки",
            emoji = "💊",
            domain = Domain.MEDICAL,
            selector = "amenity=pharmacy",
            tacticalUsageReasonUk = "Поповнення запасів медикаментів, індивідуальних аптечок IFAK та перевʼязувальних матеріалів.",
            isWartimeCritical = true,
            keywords = listOf("аптека", "ліки", "медикаменти", "бинт", "турнікет", "pharmacy"),
            quickFilter = true,
        ),
        SpatialCategory(
            id = "med_hospital",
            labelUk = "Лікарні та реанімації",
            emoji = "🏥",
            domain = Domain.MEDICAL,
            selector = "amenity~\"hospital|clinic\"",
            tacticalUsageReasonUk = "Стаціонарна медична допомога, травматологія та екстрена хірургія.",
            isWartimeCritical = true,
            keywords = listOf("лікарня", "шпиталь", "клініка", "травмпункт", "приймальне", "hospital", "clinic"),
            quickFilter = true,
        ),
        SpatialCategory(
            id = "med_ambulance",
            labelUk = "Станції швидкої допомоги",
            emoji = "🚑",
            domain = Domain.MEDICAL,
            selector = "emergency=ambulance_station",
            tacticalUsageReasonUk = "Пункти базування бригад екстреної медичної допомоги.",
            isWartimeCritical = true,
            keywords = listOf("швидка", "станція швидкої", "екстрена медицина", "ambulance"),
        ),

        // ── 5. Барʼєри та перешкоди
        SpatialCategory(
            id = "barrier_lift_gate",
            labelUk = "Шлагбауми та ворота",
            emoji = "🚧",
            domain = Domain.BARRIERS_TRANSIT,
            selector = "barrier~\"lift_gate|gate|sliding_gate\"",
            tacticalUsageReasonUk = "Врахування закритих дворів, приватних територій та обмежень проїзду автотранспорту.",
            isWartimeCritical = true,
            keywords = listOf("шлагбаум", "ворота", "пропуск", "вʼїзд", "gate", "lift gate"),
        ),
        SpatialCategory(
            id = "barrier_bollard_block",
            labelUk = "Боларди та бетонні блоки",
            emoji = "🧱",
            domain = Domain.BARRIERS_TRANSIT,
            selector = "barrier~\"bollard|block\"",
            tacticalUsageReasonUk = "Перешкоди для авто, прохідні пішки.",
            isWartimeCritical = false,
            keywords = listOf("болард", "бетонний блок", "блокпост", "стовпчики", "bollard", "block"),
        ),
        SpatialCategory(
            id = "barrier_speed_camera",
            labelUk = "Камери контролю швидкості",
            emoji = "📷",
            domain = Domain.BARRIERS_TRANSIT,
            selector = "highway=speed_camera|surveillance:type=speed_camera",
            tacticalUsageReasonUk = "Попередження про фіксацію швидкості на магістралях та оптимізація прихованого руху.",
            isWartimeCritical = false,
            keywords = listOf("камера", "фіксація", "швидкість", "радар", "speed camera", "camera"),
        ),

        // ── 6. Звʼязок та логістика
        SpatialCategory(
            id = "comms_post_office",
            labelUk = "Поштові відділення (Нова Пошта / Укрпошта)",
            emoji = "📮",
            domain = Domain.COMMS,
            selector = "amenity=post_office",
            tacticalUsageReasonUk = "Отримання критичних вантажів, техніки, спорядження та гуманітарної допомоги.",
            isWartimeCritical = false,
            keywords = listOf("пошта", "нова пошта", "нп", "укрпошта", "посилка", "відділення", "post"),
            quickFilter = true,
        ),
        SpatialCategory(
            id = "comms_parcel_locker",
            labelUk = "Поштомати 24/7",
            emoji = "📦",
            domain = Domain.COMMS,
            selector = "amenity=parcel_locker",
            tacticalUsageReasonUk = "Безконтактна цілодобова видача посилок без черг.",
            isWartimeCritical = false,
            keywords = listOf("поштомат", "нова пошта", "нп", "посилка", "locker"),
            quickFilter = true,
        ),
        SpatialCategory(
            id = "comms_cell_tower",
            labelUk = "Вишки мобільного звʼязку",
            emoji = "📡",
            domain = Domain.COMMS,
            selector = "man_made~\"mast|tower\"[tower:type=communication]",
            tacticalUsageReasonUk = "Орієнтири для підключення до LTE-веж та аналізу покриття базових станцій.",
            isWartimeCritical = false,
            keywords = listOf("вишка", "базова станція", "звʼязок", "антена", "lte", "cell tower", "mast"),
        ),

        // ── 7. Пальне та сервіс
        SpatialCategory(
            id = "fuel_gas_station",
            labelUk = "АЗС (Бензин / Дизель / Газ)",
            emoji = "⛽",
            domain = Domain.FUEL_AUTO,
            selector = "amenity=fuel",
            tacticalUsageReasonUk = "Заправка паливом для автотранспорту та дизель-генераторів.",
            isWartimeCritical = true,
            keywords = listOf("заправка", "азс", "паливо", "бензин", "дизель", "газ", "wog", "окко", "соккар", "укрнафта", "fuel", "gas"),
            quickFilter = true,
        ),
        SpatialCategory(
            id = "auto_repair",
            labelUk = "СТО та ремонт авто",
            emoji = "🔧",
            domain = Domain.FUEL_AUTO,
            selector = "shop=car_repair",
            tacticalUsageReasonUk = "Терміновий ремонт підвіски, двигунів та бронезахисту колісних засобів.",
            isWartimeCritical = false,
            keywords = listOf("сто", "автосервіс", "ремонт авто", "механік", "repair", "car service"),
        ),
        SpatialCategory(
            id = "auto_tyres",
            labelUk = "Шиномонтаж",
            emoji = "🛞",
            domain = Domain.FUEL_AUTO,
            selector = "shop=tyres",
            tacticalUsageReasonUk = "Заміна пошкоджених коліс, балансування та накачування шин.",
            isWartimeCritical = false,
            keywords = listOf("шиномонтаж", "шини", "гума", "вулканізація", "tyres"),
        ),
        SpatialCategory(
            id = "auto_parking_underground",
            labelUk = "Підземні паркінги",
            emoji = "🅿️",
            domain = Domain.FUEL_AUTO,
            selector = "amenity=parking[parking=underground]",
            tacticalUsageReasonUk = "Крита стоянка для авто.",
            isWartimeCritical = true,
            keywords = listOf("підземний паркінг", "паркінг", "парковка", "parking underground", "укриття авто"),
        ),

        // ── 8. Продовольство
        SpatialCategory(
            id = "food_supermarket",
            labelUk = "Супермаркети",
            emoji = "🛒",
            domain = Domain.PROVISIONS,
            selector = "shop~\"supermarket|convenience\"",
            tacticalUsageReasonUk = "Поповнення запасів довготривалого зберігання: консерви, крупи, вода.",
            isWartimeCritical = true,
            keywords = listOf("супермаркет", "магазин", "сільпо", "атб", "новус", "варус", "ашан", "фора", "supermarket", "shop"),
            quickFilter = true,
        ),
        SpatialCategory(
            id = "food_market",
            labelUk = "Продовольчі ринки",
            emoji = "🥕",
            domain = Domain.PROVISIONS,
            selector = "amenity=marketplace",
            tacticalUsageReasonUk = "Закупівля фермерських продуктів та готівковий товарообмін.",
            isWartimeCritical = false,
            keywords = listOf("ринок", "базар", "ярмарок", "marketplace", "market"),
        ),
        SpatialCategory(
            id = "food_bakery",
            labelUk = "Пекарні",
            emoji = "🥐",
            domain = Domain.PROVISIONS,
            selector = "shop=bakery",
            tacticalUsageReasonUk = "Точки свіжого випікання хліба з власними печами.",
            isWartimeCritical = false,
            keywords = listOf("пекарня", "хліб", "булочна", "випічка", "bakery"),
        ),

        // ── 9. Держава та волонтери
        SpatialCategory(
            id = "state_cnap",
            labelUk = "ЦНАП та адмінпослуги",
            emoji = "🏛️",
            domain = Domain.STATE_CIVIC,
            selector = "office=government",
            tacticalUsageReasonUk = "Оформлення документів, реєстрація ВПО, державні довідки.",
            isWartimeCritical = false,
            keywords = listOf("цнап", "адмінпослуги", "паспорт", "держава", "government", "registry"),
        ),
        SpatialCategory(
            id = "state_community_invincible",
            labelUk = "Пункти незламності / Хаби",
            emoji = "🕯️",
            domain = Domain.STATE_CIVIC,
            selector = "amenity=community_centre",
            tacticalUsageReasonUk = "Громадські центри з теплом, звʼязком і живленням.",
            isWartimeCritical = true,
            keywords = listOf("пункт незламності", "незламність", "волонтери", "хаб", "community centre", "обігрів"),
        ),

        // ── 10. Рельєф та орієнтири
        SpatialCategory(
            id = "terrain_peak",
            labelUk = "Панівні висоти та пагорби",
            emoji = "⛰️",
            domain = Domain.TERRAIN_LANDMARKS,
            selector = "natural=peak",
            tacticalUsageReasonUk = "Висоти й оглядові точки.",
            isWartimeCritical = false,
            keywords = listOf("гора", "висота", "пагорб", "вершина", "оглядовий майданчик", "peak", "hill"),
        ),
        SpatialCategory(
            id = "terrain_bridge",
            labelUk = "Мости та переправи",
            emoji = "🌉",
            domain = Domain.TERRAIN_LANDMARKS,
            selector = "bridge=yes[highway=*]",
            tacticalUsageReasonUk = "Критичні логістичні вузли перетину водяних та рельєфних перешкод.",
            isWartimeCritical = true,
            keywords = listOf("міст", "переправа", "шляхопровід", "естакада", "bridge"),
        ),
    )

    val quickFilters: List<SpatialCategory> = catalog.filter { it.quickFilter }

    fun findByKeyword(query: String): SpatialCategory? {
        val q = normalize(query)
        if (q.length < 2) return null
        return catalog.firstOrNull { cat ->
            normalize(cat.labelUk) == q || cat.keywords.any { normalize(it) == q }
        } ?: catalog.firstOrNull { cat ->
            normalize(cat.labelUk).startsWith(q) || cat.keywords.any { normalize(it).startsWith(q) }
        }
    }

    fun findMatchingCategories(query: String): List<SpatialCategory> {
        val q = normalize(query)
        if (q.length < 2) return emptyList()
        return catalog.filter { cat ->
            normalize(cat.labelUk).contains(q) || cat.keywords.any { normalize(it).contains(q) }
        }
    }

    internal fun normalize(s: String): String =
        s.trim().lowercase()
            .replace('\'', '\u02BC')
            .replace('\u2019', '\u02BC')
            .replace('`', '\u02BC')
}
