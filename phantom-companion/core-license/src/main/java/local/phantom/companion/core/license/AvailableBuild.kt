package local.phantom.companion.core.license

import kotlinx.serialization.json.JsonObject

/**
 * Збірка, яка вже існує десь там. Приходить у ПІДПИСАНІЙ політиці — тим самим
 * документом, який пристрій і так забирає й перевіряє ключем із збірки. Окремий
 * канал «а ось вам оновлення» був би найзручнішим місцем підсунути чужий APK.
 */
data class AvailableBuild(
    val platform: String,
    val build: Int,
    val version: String,
    val filename: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    /**
     * Кабінет віддає файл лише власникам чинного ключа. Поле каже, як воно
     * ЗАРАЗ, а не як було колись: пропонувати оновлення тому, кого сервер
     * розверне з 403, — це обіцянка, яку нема кому виконати.
     */
    val requiresLicence: Boolean,
) {
    companion object {

        /** Оголошення, яке стосується телефона. Решта платформ — не наша шкала. */
        const val PLATFORM = "android"

        private val HEX64 = Regex("^[0-9a-f]{64}$")

        /**
         * `null`, якщо оголошення описане не повністю. Напівописане оновлення
         * гірше за жодне: людині показали б кнопку, за якою нема з чим звірити
         * завантажене. Тому кожне поле — умова, а не побажання.
         */
        fun from(document: JsonObject?): AvailableBuild? {
            val obj = document ?: return null
            val build = obj.str("build")?.toIntOrNull()?.takeIf { it > 0 } ?: return null
            val platform = obj.str("platform")?.takeIf { it.isNotBlank() } ?: return null
            val version = obj.str("version")?.takeIf { it.isNotBlank() } ?: return null
            val filename = obj.str("filename")?.takeIf { it.isNotBlank() } ?: return null
            val url = obj.str("url")?.takeIf { it.startsWith("https://") } ?: return null
            val size = obj.str("size")?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            val sha = obj.str("sha256")?.lowercase()?.takeIf(HEX64::matches) ?: return null
            return AvailableBuild(
                platform = platform,
                build = build,
                version = version,
                filename = filename,
                url = url,
                sizeBytes = size,
                sha256 = sha,
                // `open` — файл віддають кожному, без акаунта й без ключа;
                // `account` — кожному, хто ввійшов. Невідоме значення читаємо
                // як «потрібен ключ»: помилка в бік мовчання коштує одне
                // непоказане оновлення, помилка в протилежний — обіцянку, за
                // якою двері зачинені.
                requiresLicence = obj.str("requires") !in setOf("open", "account"),
            )
        }
    }
}

/**
 * Чи є про що казати ЦІЙ копії застосунку.
 *
 * Чистий розрахунок над уже збереженою політикою: жодної мережі, жодного
 * очікування. Телефон без звʼязку рахує те саме, що й телефон зі звʼязком, —
 * просто над останнім документом, який бачив.
 */
object UpdateOffer {

    /**
     * Що взагалі оголошено для цього телефона й новіше за встановлене. Тут
     * НЕМАЄ ні ключа, ні «пізніше»: екран ліцензії мусить бачити збірку навіть
     * тоді, коли картку на стрічці вже відхилили, — інакше відмовитись один раз
     * означало б втратити адресу назавжди.
     */
    fun announcedFor(
        policy: BuildPolicy?,
        installedBuild: Int,
        developerBuild: Boolean,
    ): AvailableBuild? {
        // Збірка з ноутбука не має номера в шкалі кабінету: versionCode у ній
        // завжди 1 (номер задає лише scripts/release.sh), тож БУДЬ-ЯКЕ
        // оголошення виглядає новішим, і «оновитись» означало б відкотити
        // апарат на давно опубліковану збірку. Хто не публікувався — не грає.
        if (developerBuild) return null
        val latest = policy?.latest ?: return null
        if (!latest.platform.equals(AvailableBuild.PLATFORM, ignoreCase = true)) return null
        // Рівна збірка — не оновлення. Менша означає відкат політики або ручну
        // збірку свіжішу за оголошену; в обох випадках казати нічого.
        //
        // ВІДКОТУ НЕМАЄ — знати це треба на першій хвилині аварії, а не на
        // десятій. Строга нерівність тут означає: погану збірку можна лише
        // ПЕРЕКРИТИ більшим номером, і жодне оголошення не поверне апарат
        // назад. Опустити `latest.build` у політиці — не ліки: телефони на
        // поганій збірці порахують те саме `<=` і не побачать нічого. Єдиний
        // важіль — випустити наступний номер. (Другий замок, нижче за нашим:
        // Android сам відмовляє встановити менший versionCode.)
        //
        // Це чокпойнт на ВСЮ доставку — заміряно 05.09 у дереві
        // phantom-companion-integration [integration/beta], проходом по ланцюгу:
        //
        //   UpdateNotice.kt      offer?.let { build -> … installer.start(build) }
        //   ← service.update  ←  LicenseService.availableUpdate()
        //   ← UpdateOffer.forThisBuild(installedBuild = BuildConfig.VERSION_CODE)
        //   ← announcedFor(…)  ←  ЦЕЙ рядок
        //
        // Другий вихід, announcedUpdate() для екрана ліцензії, ходить сюди ж.
        // Інших порівнянь із installedBuild і інших джерел AvailableBuild для
        // інсталятора в дереві немає, тож нижчий номер не може дійти до
        // installer.start() жодним шляхом.
        // Доведено: UpdateOfferTest, `a published build never offers itself an
        // older one` (8 встановлено проти 7 оголошених, і 7 проти 7).
        if (latest.build <= installedBuild) return null
        return latest
    }

    /** Про що доречно сказати САМОМУ, не чекаючи, поки людина відкриє екран. */
    fun forThisBuild(
        policy: BuildPolicy?,
        installedBuild: Int,
        licensed: Boolean,
        developerBuild: Boolean,
        dismissedBuild: Int = 0,
    ): AvailableBuild? {
        val latest = announcedFor(policy, installedBuild, developerBuild) ?: return null
        // Кабінет однаково відмовить — тоді це не «оновіться», а «заплатіть»,
        // і на стрічці такій пропозиції не місце.
        if (latest.requiresLicence && !licensed) return null
        // Відмовився один раз — не питаємо знову, поки не вийде ЩЕ новіше.
        // Інакше «пізніше» означало б «до наступного запуску».
        if (latest.build <= dismissedBuild) return null
        return latest
    }
}
