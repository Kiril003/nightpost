package local.phantom.companion.core.data.poi

import local.phantom.companion.core.data.evac.ShelterTruth

/**
 * Наскільки місце важить людині, коли на екран влазить не все.
 *
 * ОДНЕ оголошення на застосунок — і це головне, що тут є. Досі їх було два, і
 * вони розійшлись рівно так, як і мали розійтись:
 *
 *   - `PoiGlyphTable` (скло) казав: джерело, криниця, питна вода, дефібрилятор,
 *     ветлікарня, пункт незламності — КРИТИЧНІ;
 *   - `PoiDensity.salience` (диск) цих родів не знав ЗОВСІМ і клав кожен із них
 *     у останнє відро, тобто викидав першими.
 *
 * Різались вони першими саме там, де диск ріже, — а ріже диск ДО скла. Заміряно
 * на національному корпусі 17.09: 13 395 критичних рядків (32% усіх) сиділи в
 * останньому відрі, з них 12 193 — вода. На типовому кадрі z13/z14 великого
 * міста до скла доходило РІВНО НУЛЬ водних точок із 3 917 і 1 350 наявних.
 *
 * Тому ярус переїхав сюди, в дані, і обидві поверхні тепер його ЧИТАЮТЬ:
 * `PoiDensity` рахує з нього чергу на виживання, `PoiGlyphTable` бере колір
 * обідка й додає до нього лише СВОЮ колонку — знак. Розійтись більше нема чому:
 * колонка одна.
 */
enum class PlaceTier { CRITICAL, SUSTAIN, ORDINARY }

object PlaceTiers {

    /**
     * Життя і здоровʼя. Вода стоїть тут поруч із лікарнею навмисно: у блекаут
     * і під обстрілом безіменне джерело важить більше за будь-яку вивіску, а
     * назви в нього немає за побудовою — 52 604 рядки корпусу йдуть під
     * родовим іменником, бо воду шукають не за назвою.
     */
    val CRITICAL_KEYS: Set<String> = setOf(
        "shelter", "hospital", "clinic", "doctors", "ambulance_station", "defibrillator",
        "pharmacy", "dentist", "veterinary", "police", "fire_station",
        "drinking_water", "water_well", "spring", "community_centre",
    )

    /** Рух, живлення, звʼязок із державою — те, чим дістаються й тримаються. */
    val SUSTAIN_KEYS: Set<String> = setOf(
        "fuel", "charging_station", "generator", "substation", "transformer",
        "car_repair", "tyres", "bus_station", "bus_stop", "platform", "tram_stop",
        "station", "halt", "subway_entrance", "subway", "ferry_terminal", "taxi",
        "atm", "bank", "post_office", "parcel_locker", "toilets",
    )

    /** Рід, коли підкатегорії нема: будь-яка колія і будь-яка зупинка — опора. */
    val SUSTAIN_CATEGORIES: Set<String> = setOf("railway", "highway", "public_transport")

    /**
     * Роди, про які PHANTOM розповідає ВЛАСНИМ шаром, — і тому суцільний шар
     * місць їх не повторює.
     *
     * Живе тут, а не на склі, з однієї причини: стелю кадру рахує
     * [PoiDensity.rank], тобто НА КРОК РАНІШЕ за оверлей. Поки правило стояло
     * лише в оверлеї, брід зʼїдав слот, а потім його ж і викидали — заміряно
     * 17.09 на кадрах із бродом: на z13 у 54 переповнених кадрах 567 слотів із
     * 1 350 (42%) пішли на піни, яких ніхто не побачив. Саме в селі, де мапа й
     * так найбідніша.
     */
    val OWN_LAYER_CATEGORIES: Set<String> = setOf("ford")

    /**
     * Підкатегорія, з якою місце йде і на скло, і в чергу.
     *
     * `amenity=shelter` в OSM — це НАВІС. Порожня підкатегорія роняє альтанку
     * на рід `amenity`, тобто на «просто місце»; сховищем лишається те, що
     * саме заявляє захист ([ShelterTruth.isProtective]).
     */
    fun pinKey(poi: PoiEntity): String? =
        if (poi.subcategory == "shelter" && !ShelterTruth.isProtective(poi.tags)) null else poi.subcategory

    /**
     * Чим це місце є для ока — ключ, за яким рахується РОЗМАЇТТЯ кадру.
     * Той самий ключ, що обирає знак, тож «різних ключів у кадрі» і «різних
     * знаків на склі» — це одне число, а не два схожих.
     */
    fun kindOf(poi: PoiEntity): String =
        pinKey(poi)?.takeIf { it.isNotBlank() } ?: poi.category

    fun of(category: String?, subcategory: String?): PlaceTier {
        val sub = subcategory?.takeIf { it.isNotBlank() }
        if (sub != null) {
            if (sub in CRITICAL_KEYS) return PlaceTier.CRITICAL
            if (sub in SUSTAIN_KEYS) return PlaceTier.SUSTAIN
        }
        val cat = category?.takeIf { it.isNotBlank() } ?: return PlaceTier.ORDINARY
        if (cat in CRITICAL_KEYS) return PlaceTier.CRITICAL
        if (cat in SUSTAIN_KEYS || cat in SUSTAIN_CATEGORIES) return PlaceTier.SUSTAIN
        return PlaceTier.ORDINARY
    }

    /** [of] із тегами — єдина форма, якій можна вірити під сиреною. */
    fun of(poi: PoiEntity): PlaceTier = of(poi.category, pinKey(poi))

    /** Чи має рід право на суцільний шар землі. */
    fun hasOwnLayer(poi: PoiEntity): Boolean =
        poi.category.trim().lowercase() in OWN_LAYER_CATEGORIES
}
