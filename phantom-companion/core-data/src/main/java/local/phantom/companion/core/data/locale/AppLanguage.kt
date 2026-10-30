package local.phantom.companion.core.data.locale

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Мова Nightpost — один вибір на весь застосунок.
 *
 * Збірка 18 (власник, 08.10): перший запуск починається з вибору мови,
 * англійська — перша й уже обрана, далі решта мов, які застосунок СПРАВДІ
 * має (рядки в `values-<tag>/` і мова в `locales_config.xml`; сторож —
 * `OfferedLanguagesTranslatedTest`). Прапор — для впізнавання з першого
 * погляду, назва — мовою самої мови.
 *
 * Де живе вибір:
 * - Android 13+ — мова застосунку в системі (`LocaleManager`); її ж бачить
 *   «Налаштування › Застосунки › Мова», і система сама перестворює активність.
 * - до Android 13 окремої мови застосунку в системі немає: вибір лежить у
 *   `phantom_language`, а на весь процес його розносить [wrap] — у
 *   `attachBaseContext` застосунку (`PhantomApp`: сповіщення, служби, рядки з
 *   `applicationContext`) і кожної активності, разом із `Locale.getDefault()`
 *   (дати, геокодер, перевірки мови). [choose] робить те саме одразу й
 *   перестворює активність.
 *
 * Позначка «людина вже обирала» пишеться завжди — нею перший екран знає,
 * що другий запуск його пропускає.
 *
 * «Як у системі»: людина обрала мову тут, а потім у «Налаштування › Застосунки
 * › Nightpost › Мова» повернула «Як у системі» (Android 13+). Це теж вибір:
 * перший екран не вертається, а рідні екрани говорять мовою телефона — тож
 * [current], помічник і месенджер говорять нею ж, а не давнім вибором, якого
 * вже ніде не видно. Профіль › Мова з такого стану вміє повернути будь-яку.
 */
enum class AppLanguage(val tag: String, val nativeName: String, val flag: String) {
    EN("en", "English", "🇬🇧"),
    UK("uk", "Українська", "🇺🇦"),
    CS("cs", "Čeština", "🇨🇿"),
    ;

    val locale: Locale get() = Locale.forLanguageTag(tag)

    companion object {
        /** Що вже обрано на першому екрані, і що означає «не обирала». */
        val DEFAULT: AppLanguage = EN

        /** Порядок першого екрана й Профілю: англійська перша, далі решта. */
        val offered: List<AppLanguage> = listOf(EN, UK, CS)

        /**
         * Мова `res/values/` (без суфікса): на неї Android скочується, коли в
         * телефона немає жодної мови з наших. Сторож — `OfferedLanguagesTranslatedTest`.
         */
        private val RESOURCE_DEFAULT: AppLanguage = EN

        private const val PREFS = "phantom_language"
        private const val PICKED = "picked"
        private const val SYSTEM = "system"

        fun of(locale: Locale?): AppLanguage? {
            val language = locale?.language ?: return null
            return entries.firstOrNull { it.locale.language == language || it.tag == language }
        }

        /** Що людина обрала саме для Nightpost; null — не обирала. */
        fun chosen(context: Context): AppLanguage? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val picked = context.getSystemService(LocaleManager::class.java)?.applicationLocales
                    ?.takeUnless { it.isEmpty }?.get(0)
                if (picked != null) return of(picked) ?: DEFAULT
                // Мови застосунку в системі немає: або людина не обирала, або
                // обрала тут і повернула «Як у системі» — тоді слід за телефоном.
                return if (prefs(context).contains(PICKED)) followingPhone(context) else null
            }
            return prefs(context).getString(PICKED, null)?.let { of(Locale.forLanguageTag(it)) }
        }

        /**
         * Яку з наших мов рідні екрани показують на «Як у системі»: Android бере
         * першу мову зі списку телефона, що в застосунку є; жодної — `res/values/`.
         */
        private fun followingPhone(context: Context): AppLanguage {
            val phone = context.resources.configuration.locales
            for (i in 0 until phone.size()) of(phone.get(i))?.let { return it }
            return RESOURCE_DEFAULT
        }

        /**
         * Що показують Профіль › Мова і помічник: вибір, а без нього — мова
         * телефона, якщо вона в нас є (власник 29.10: мова — за системою).
         */
        fun current(context: Context): AppLanguage = chosen(context) ?: followingPhone(context)

        /** Перший екран: позначена мова телефона, а не завжди англійська. */
        fun firstRunGuess(context: Context): AppLanguage = followingPhone(context)

        /** Жодна з мов телефона не рідна для застосунку (de, es, …): рідні екрани — `values/`. */
        fun noneOfOurs(phone: List<Locale>): Boolean = phone.none { of(it) != null }

        /** [noneOfOurs] для мов самого телефона, а не застосунку. */
        fun phoneHasNoneOfOurs(): Boolean {
            val phone = android.content.res.Resources.getSystem().configuration.locales
            return noneOfOurs((0 until phone.size()).map { phone.get(it) })
        }

        /** Чи йде застосунок за мовою телефона (Профіль › Мова › «Як у телефоні»). */
        fun followsPhone(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                prefs(context).contains(PICKED) &&
                    context.getSystemService(LocaleManager::class.java)?.applicationLocales?.isEmpty != false
            } else {
                prefs(context).getString(PICKED, null) == SYSTEM
            }

        /**
         * «Як у телефоні»: власної мови застосунку більше немає. Android 13+
         * сам перестворює активність мовою телефона; до 13 — тут, як у [choose].
         */
        fun followPhone(context: Context) {
            prefs(context).edit().putString(PICKED, SYSTEM).commit()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.getSystemService(LocaleManager::class.java)?.applicationLocales = LocaleList.getEmptyLocaleList()
            } else {
                val phone = android.content.res.Resources.getSystem().configuration.locales
                val language = (0 until phone.size()).firstNotNullOfOrNull { of(phone.get(it)) } ?: RESOURCE_DEFAULT
                applyToProcess(context.applicationContext ?: context, language)
                context.activity()?.recreate()
            }
        }

        /** Перший запуск: мову ще не обирали ні тут, ні в Профілі. */
        fun needsPick(context: Context): Boolean =
            chosen(context) == null && !prefs(context).contains(PICKED)

        /**
         * Поставити мову. Android 13+ сам перестворює активність і розносить
         * мову по процесу; нижче — це робимо тут: `Locale.getDefault()` і
         * ресурси застосунку міняються одразу, активність перестворюється, а
         * наступна (і холодний старт) бере вибір через [wrap].
         */
        fun choose(context: Context, language: AppLanguage) {
            prefs(context).edit().putString(PICKED, language.tag).commit()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                    LocaleList.forLanguageTags(language.tag)
            } else {
                applyToProcess(context.applicationContext ?: context, language)
                context.activity()?.recreate()
            }
        }

        /**
         * `attachBaseContext` застосунку й КОЖНОЇ активності: до Android 13 —
         * мова з вибору. Заодно ставить `Locale.getDefault()` — його читають
         * дати, геокодер і перевірки мови, і сповіщення з служб лишались би
         * мовою телефона. На 13+ мову ставить система, але лишає список мов
         * телефона: A52 з [ru-UA, uk-UA] брав ru (його приносить WebView),
         * не знаходив values-ru і показував англійську при обраній українській.
         * Тож без мови застосунку в системі контекст звужується до однієї нашої;
         * з нею система вже дає одну мову й оновлює її сама.
         */
        fun wrap(base: Context): Context {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val system = base.getSystemService(LocaleManager::class.java)?.applicationLocales
                if (system != null && !system.isEmpty) return base
                val language = chosen(base) ?: return base
                val phone = base.resources.configuration.locales
                phoneWave((0 until phone.size()).map { phone.get(it) })?.let { return narrowed(base, it) }
                return localized(base, language)
            }
            val language = chosen(base) ?: return base
            Locale.setDefault(language.locale)
            return localized(base, language)
        }

        @Suppress("DEPRECATION")
        private fun applyToProcess(app: Context, language: AppLanguage) {
            Locale.setDefault(language.locale)
            val resources = app.resources
            resources.updateConfiguration(
                Configuration(resources.configuration).apply { setLocale(language.locale) },
                resources.displayMetrics,
            )
        }

        /** Контекст, з якого рядки беруться саме цією мовою. */
        fun localized(base: Context, language: AppLanguage): Context = narrowed(base, language.locale)

        private fun narrowed(base: Context, locale: Locale): Context {
            val config = base.resources.configuration
            if (config.locales.size() == 1 && config.locales.get(0)?.language == locale.language) return base
            return base.createConfigurationContext(Configuration(config).apply { setLocale(locale) })
        }

        /** Мови з частковим `values-<мова>` (решта рядків — `values/`); у вибір не входять. */
        private val WAVE = setOf("es", "pt", "de", "fr", "pl", "tr", "it", "in", "id")

        /**
         * Хвильові мови, чиї рідні екрани перекладено повністю. Лише вони їдуть в APK
         * (`resourceConfigurations`) і в [phoneWave]: напівперекладений екран змішав би
         * дві мови. Сторож — WaveLocaleResourcesTest.
         */
        val NATIVE_READY: Set<String> = emptySet()

        /**
         * «Як у телефоні» на телефоні хвильовою мовою: перша мова телефона, що наша
         * або хвильова. Хвильова — її локаль, щоб [wrap] не звузив до англійської
         * і `values-de` дійшов до екрана; наша — null, діє звичне правило.
         */
        fun phoneWave(phone: List<Locale>, ready: Set<String> = NATIVE_READY): Locale? {
            for (l in phone) {
                if (of(l) != null) return null
                if (l.language in WAVE) return l.takeIf { it.language in ready }
            }
            return null
        }

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun Context.activity(): Activity? {
            var c: Context? = this
            while (c != null) {
                if (c is Activity) return c
                c = (c as? ContextWrapper)?.baseContext
            }
            return null
        }
    }
}
