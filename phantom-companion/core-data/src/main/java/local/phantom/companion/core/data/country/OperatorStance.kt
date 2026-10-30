package local.phantom.companion.core.data.country

/**
 * ДЕ ТИ Й ХТО ТИ.
 *
 * «При создании аккаунта надо спрашивать аккаунт и от него отталкиваться
 * предлагать ли людям кнопки по типу укриття» — троє тестувальників, 11.09,
 * зі Словаччини. Швидкий доступ мапи пропонував їм «Укриття» і «Пункт
 * незламності»: категорії, яких у їхній країні просто немає в жодному реєстрі.
 *
 * Країну вже знає [CountryPackRegistry] — з фікса, з памʼяті минулого запуску
 * і з ручного вибору оператора. Бракувало другої половини: ролі. Волонтер і
 * служба дивляться на ту саму мапу іншими очима, і в чужій країні теж.
 */
enum class OperatorRole {
    CIVILIAN,
    VOLUNTEER,
    SERVICE,
    ;

    companion object {
        fun of(name: String?): OperatorRole =
            entries.firstOrNull { it.name == name } ?: CIVILIAN
    }
}

/**
 * Рішення оператора про себе. [countryId] — id пака ([CountryPack.id]), або
 * null, коли країну лишили на автомат.
 */
data class OperatorStance(
    val countryId: String?,
    val role: OperatorRole = OperatorRole.CIVILIAN,
) {
    companion object {
        /** Чинна країна: вибір оператора головніший за здогад із фікса. */
        fun activeCountryId(stance: OperatorStance?, located: String?): String? =
            stance?.countryId ?: located

        /**
         * Чи показувати воєнні категорії України.
         *
         * Волонтер і служба бачать їх скрізь: вони возять допомогу через
         * кордон, і «в моїй країні такого немає» для них не аргумент.
         *
         * [localeRegion] — остання інстанція, і лише поки країни не знає ніхто:
         * свіжий телефон у Києві до першого фікса мусить бачити укриття.
         */
        fun showsUkraineWartime(
            stance: OperatorStance?,
            located: String?,
            localeRegion: String?,
        ): Boolean {
            val role = stance?.role ?: OperatorRole.CIVILIAN
            if (role != OperatorRole.CIVILIAN) return true
            return when (val country = activeCountryId(stance, located)) {
                null -> localeRegion.equals("UA", ignoreCase = true)
                else -> country == UkrainePack.id
            }
        }
    }
}
