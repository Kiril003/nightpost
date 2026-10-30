// PHANTOM Companion — `:app` module.
//
// Tier 1 scope (per docs/MOBILE_COMPANION.md §3): pairing, profile sync,
// push-to-talk voice WS, sensor batch upload, glass-themed UI for the
// five tabs. Filament / MapLibre / SQLCipher land in Tier 2 modules.
// `java` alone resolves to Gradle's JavaPluginExtension inside a .kts script.
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile
import javax.inject.Inject
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations

// Зовнішній процес на конфігураційній фазі дозволений лише через ValueSource —
// інакше конфіг-кеш валить збірку («configuration cache problems»).
abstract class GitStampSource : ValueSource<String, ValueSourceParameters.None> {
    @get:Inject abstract val execOperations: ExecOperations
    private fun git(vararg args: String): String {
        val out = ByteArrayOutputStream()
        execOperations.exec {
            commandLine("git", *args)
            standardOutput = out
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        return out.toString().trim()
    }

    override fun obtain(): String = runCatching {
        val dirty = git("status", "--porcelain", "--untracked-files=no", "--", ":(top)phantom-companion", ":(top)phantom-os/src")
        git("describe", "--always") + if (dirty.isNotEmpty()) "-dirty" else ""
    }.getOrDefault("")
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// local.properties is machine-local and git-ignored; Gradle reads it for
// `sdk.dir` automatically but NOT for custom keys — those must be loaded by
// hand or `findProperty` silently ignores them. That gap is exactly why the
// `phantom.geoDevHost` override never took and on-device builds kept baking the
// 10.0.2.2 emulator alias (unreachable from a physical phone → routing dead).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Релізний ключ підпису. Поза git назавжди: хто має цей файл — той може
// випустити оновлення, яке кожен встановлений PHANTOM прийме як наше.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val releaseKeystore = keystoreProps.getProperty("storeFile")
    ?.let { rootProject.file(it) }
    ?.takeIf { it.exists() }

// Справжній якір довіри ліцензії. Стандарт — ПОРОЖНІЙ рядок («довіряти
// нічому»): раніше стандартом був наш РОЗРОБНИЦЬКИЙ публічний ключ, тож будь-яка
// збірка без local.properties (CI, свіжий клон) тихо довіряла ключу з цього
// ноутбука й ходила на мертвий license.phantom-os.dev. Ключ ПУБЛІЧНИЙ (ним лише
// звіряють підпис), але задається лише в local.properties/-P — не зашивається.
val licenseKey = (project.findProperty("phantom.licenseKey") as String?)
    ?: localProps.getProperty("phantom.licenseKey", "")
// Переїзд з Fly на Oracle (23.10): старий хост у local.properties чи -P тихо
// стає новим; старий лишається лише як другий сховок для старших збірок.
val PRIMARY_HOST = "https://api.phantom-os.dev"
val LEGACY_RELAY_HOST = "https://phantom-license.fly.dev"
val licenseHost = ((project.findProperty("phantom.licenseHost") as String?)
    ?: localProps.getProperty("phantom.licenseHost", ""))
    .let { if (it.trim().trimEnd('/') == LEGACY_RELAY_HOST) PRIMARY_HOST else it }

// Скринька й сервер, якими ходить debug, коли хоста не задано (DL-3). Лише
// адреса, не якір довіри: ключ ліцензії так само порожній за замовчуванням.
val DEBUG_RELAY_HOST = PRIMARY_HOST

// ATLAS-3 — якір довіри пакiв країн і адреса, звідки вони приїжджають.
// Читаються ТУТ, а не в `buildTypes.all` (де їх споживає buildConfigField), бо
// на них стоїть той самий заслін, що й на ліцензійних. Значення й причина
// порожнього стандарту — біля buildConfigField нижче.
val packKey = (project.findProperty("phantom.packKey") as String?)
    ?: localProps.getProperty("phantom.packKey", "")
val packsBaseUrl = (project.findProperty("phantom.packsBaseUrl") as String?)
    ?: localProps.getProperty("phantom.packsBaseUrl", "")

// База, під якою лежать наші копії Moonshine і Supertonic. Той самий порожній
// стандарт, що й у пакiв: без адреси моделі просто «не встановлені».
val modelsBaseUrl = (project.findProperty("phantom.modelsBaseUrl") as String?)
    ?: localProps.getProperty("phantom.modelsBaseUrl", "")

// Свідомий випуск без доставки пакiв. Ворота нижче стоять на значеннях, яких
// 07.09 ще НЕ ІСНУЄ: складача пакiв не написано, пари ключів не згенеровано,
// публічної адреси бакета не зафіксовано ніде. Твердий заслін без цього
// вимикача заблокував би будь-який реліз, тож вибір тут між «мовчки мертва
// мапа» і «мертва мапа, під якою хтось підписався рукою».
//
// УВАГА: `findProperty` бачить не лише `-P`. Він читає ще й `gradle.properties`
// у корені (а той у git), `~/.gradle/gradle.properties` та змінну середовища
// ORG_GRADLE_PROJECT_phantom.packsDeliveryDisabled. Отже вимикач МОЖЕ осісти в
// дереві й стати тишею вдруге — саме тому кожна збірка, що ним скористалась,
// кричить про це в лог нижче. З `local.properties` він не читається навмисно.
val packsDeliveryDisabled =
    (project.findProperty("phantom.packsDeliveryDisabled") as String?)?.toBoolean() ?: false

// Збірка в хмарі, підпис удома. Релізний ключ не має покидати ноутбука, а
// ноутбук релізної збірки не тягне — і це виглядало глухим кутом, доки
// 09.09 на роздачу не поїхала 690-МБ DEBUG-збірка, підписана ключем, чий
// пароль лежить у цьому ж репозиторії. Отже заслін не вберіг: він забороняв
// чесний непідписаний артефакт і мовчки лишав відкритою гіршу дорогу.
//
// Читається ЛИШЕ з -P: `local.properties` навмисно не бачить його, щоб
// вимикач не осів на машині. `findProperty` усе одно бачить gradle.properties
// і ORG_GRADLE_PROJECT_*, тому кожна така збірка кричить про себе в лог.
val unsignedRelease =
    (project.findProperty("phantom.unsignedRelease") as String?)?.toBoolean() ?: false

// ATLAS-4 «Живий шар». Порожньо за замовчуванням = площини немає, застосунок
// живе суто локально. Anon-ключ Supabase публічний за призначенням (усе
// вирішує RLS), але в git він не їде: лише local.properties / -P, як geoDevHost.
val supabaseUrl = (project.findProperty("phantom.supabaseUrl") as String?)
    ?: localProps.getProperty("phantom.supabaseUrl", "")
val supabaseAnonKey = (project.findProperty("phantom.supabaseAnonKey") as String?)
    ?: localProps.getProperty("phantom.supabaseAnonKey", "")

// Номер збірки. Читається ТУТ, а не в defaultConfig, бо на ньому стоїть гейт
// нижче: без розрізнення «не задано» й «задано 1» реліз мовчки виходив під
// номером 1 щоразу — а пристрій показує оновлення лише коли оголошений
// `latest.build` БІЛЬШИЙ за встановлений. Уся машинерія оновлень існувала,
// проходила тести й не могла спрацювати в жодного користувача.
val versionCodeRaw = (project.findProperty("phantom.versionCode") as String?)
    ?: localProps.getProperty("phantom.versionCode")
val versionCodeValue = versionCodeRaw?.trim()?.toIntOrNull()?.takeIf { it > 0 }

// Остання збірка, яка ВЖЕ поїхала до людей (Play або кабінет). Живе в git, а не
// в local.properties: машинна пам'ять зникає з першим свіжим клоном, і саме так
// «наступний номер має бути більшим» трималось на тому, чи згадає людина. Тут
// це умова збірки. Піднімати РАЗОМ із викладенням: число описує минуле, а не
// намір.
// 30.08.2026: підняте 6 -> 7, і 7 — це ПРАВДА ПРО МИНУЛЕ, а не намір. Збірку
// номер 7 (`0.1.0-tier1+f87a0484`) видано з кабінету ПОЗА цим механізмом, і
// поле про це не дізналось: воно лишалось на шістці, доки хтось не звірив із
// кабінетом. Наслідок був не теоретичний — я взяв 7 як «перший вільний» саме
// з цього рядка й випустив би дублікат.
// Застаріле число гірше за відсутнє: відсутність змушує спитати, а застаріле
// відповідає впевнено й хибно, і заслін нижче його пропускає.
// 9 справді поїхала на сайт 14.09 (зведений месенджер + маршрутизація +
// транзит + груп-аватар).
// 10 поїхала 14.09 увечері (`0.1.0-tier1+392e51d8`, 83 922 511 байтів) і стоїть
// на апараті власника — звірено через /api/downloads і dumpsys.
// 11 поїхала 15.09 (`0.1.0-tier1+e5fd74e9`, 83 930 907 байтів): рід картки на
// дроті, закрита гілка сирого JSON, пам'ятки на мапі, жива кнопка дзвінка,
// тема за добою, географія кнопки укриття, картка лінії без порожньої обіцянки.
// Звірено через /api/downloads (sha256 003b5118…) і /api/policy (latest.build=11).
// Число підняте ПІСЛЯ факту, як велить правило вище; наступна збірка стане 12.
// 16 поїхала 05.10 (`0.1.0-tier1+5d9c91fa`, 74 080 316 байтів, sha256 4b42e919…) як
// nightpost-0.1.0-b16.apk — перша під іменем Nightpost; звірено через /api/downloads.
// 32 поїхала 26.10 (`0.1.0-tier1+e3ac79e2a`, 133 303 145 байтів, sha256 8398443f…) як
// nightpost-0.1.0-b32.apk — на api.phantom-os.dev (/api/policy latest.build=32).
// 34 поїхала 26.10 (`0.1.0-tier1+d58f1a658`, 133 436 941 байтів, sha256 1eafa270…) як
// nightpost-0.1.0-b34.apk — на api.phantom-os.dev (/api/policy latest.build=34).
val publishedBuild = 34

// Реліз без явного номера бере наступний після виданого — ніколи 1. Явний
// -Pphantom.versionCode лишається, щоб перестрибнути згорілий номер; заслін
// нижче все одно не пустить його нижче за publishedBuild. Debug лишається на 1
// (див. defaultConfig): телефон із debug має бачити оголошення про 8 як новіше.
val releaseVersionCode = versionCodeValue ?: (publishedBuild + 1)

// Поріг СХЕМИ пакiв — не те саме, що номер публікації.
//
// `minAppBuild` у манiфесті пака питає «чи вміє ця збірка прочитати мій
// формат», а не «наскільки вона свіжа». Годувати його `VERSION_CODE` було
// хибою, помітною лише на debug: там versionCode навмисно лишається 1 (щоб
// оголошення про 8 читалось як новіше), тож обидва пакові заслони бачили
// «пак просить збірку 8, ця — 1» і відкидали КОЖЕН пак із живого каталогу.
// На апараті власника стоять обидві збірки, і перша ж ручна перевірка пакiв
// упиралась би саме в це.
//
// Тому число тут ОДНЕ для debug і release, і росте лише тоді, коли
// змінюється формат пака, — разом із `--min-app-build` у складачі.
val packSchemaBuild = 8

val webBundleSeal: Map<String, String> =
    providers.fileContents(layout.projectDirectory.file("src/main/assets/webmsgr/BUNDLE_SOURCE"))
        .asText.orNull.orEmpty().lines()
        .mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1).trim() } }
        .toMap()
val webBundleSha = webBundleSeal["web_sha"].orEmpty()


// Реліз без якоря не має ПОЇХАТИ підписаним. Гейт стоїть на РОЗВ'ЯЗАНОМУ графі
// задач, а не на написанні команди: `build`/`assemble`/`bundle`/`clean build`
// усі складають release-варіант, і task-name-евристика (`contains("Release")` у
// запитаних іменах) їх пропускала — APK виходив підписаний справжнім ключем,
// LICENSE_PUBLIC_KEY="" → кожен клієнтський сертифікат BadSignature → Free,
// мовчки. Ловимо саме задачі, що ВИПУСКАЮТЬ підписаний артефакт; юніт-тести
// (test…ReleaseUnitTest) і lint (lint…Release) їх не планують, тож keyless-CI
// на конфігурації не падає.
// Без параметра лямбди: з `graph ->` Kotlin обирає Groovy-перевантаження Closure
// замість Action, і конфігурація падає ще до першої задачі.
gradle.taskGraph.whenReady {
    val emitsReleaseArtifact = allTasks.any { task ->
        val n = task.name
        // `bundle…Release` саме по собі ловить внутрішні bundleLibCompileToJarRelease
        // кожної бібліотеки — а їх планують і юніт-тести, і lint. Тому лише задачі
        // :app, що справді випускають підписаний артефакт.
        task.project.path == ":app" && (
            (n.startsWith("package") && n.endsWith("Release")) ||
                (n.startsWith("bundle") && n.endsWith("Release") && !n.contains("ToJar")) ||
                (n.startsWith("sign") && n.contains("Release"))
            )
    }
    if (emitsReleaseArtifact && (licenseKey.isBlank() || licenseHost.isBlank())) {
        throw GradleException(
            "phantom.licenseKey і phantom.licenseHost обов'язкові для release-артефакта. " +
                "Задайте їх у local.properties або через -Pphantom.licenseKey=<hex> " +
                "-Pphantom.licenseHost=<url>. Відмова випускати підписану збірку без якоря довіри.",
        )
    }
    // Той самий предикат — і той самий клас мовчання, лише про мапу. Порожній
    // PACKS_BASE_URL означає, що завантаження пакiв віддає NotConfigured, а
    // порожній PACK_PUBLIC_KEY — що не приймається ЖОДЕН пак, у тому числі
    // внесений руками. Разом це підписаний артефакт, у якому офлайн-мапа не
    // може ні приїхати, ні бути покладеною: застосунок довіку сидить на
    // вбудованій Україні, і ніщо в збірці про це не каже.
    // Порожні обидва — не «вільний рівень», а мертва доставка геометрії.
    if (emitsReleaseArtifact && (packKey.isBlank() || packsBaseUrl.isBlank()) && !packsDeliveryDisabled) {
        throw GradleException(
            "phantom.packKey і phantom.packsBaseUrl обов'язкові для release-артефакта. " +
                "Порожній packsBaseUrl = завантаження пакiв віддає NotConfigured; порожній " +
                "packKey = не приймається жоден пак, навіть внесений руками. Підписана " +
                "збірка вийшла б із мертвою офлайн-мапою поза вбудованою Україною. " +
                "Задайте їх у local.properties або через -Pphantom.packKey=<64 hex, Ed25519 " +
                "ПУБЛІЧНИЙ ключ складача пакiв, НЕ ліцензійний> -Pphantom.packsBaseUrl=<база, " +
                "під якою лежить <ID>/manifest.json>. Підставного значення тут навмисно " +
                "немає: будь-яке «аби не порожньо» перетворює чесний NotConfigured на " +
                "мовчазну мережеву помилку.\n" +
                "Якщо пари ключів і бакета ще не існує — випускайте свідомо: " +
                "-Pphantom.packsDeliveryDisabled=true. Це не «все гаразд», це підпис під " +
                "тим, що офлайн-мапа поза вбудованою Україною в цьому артефакті мертва.",
        )
    }
    // Вимикач, який осів у CI-скрипті, — це те саме мовчання, лише з підписом.
    // Тож кожна збірка, що ним скористалась, каже це вголос у власному логу:
    // хто читатиме лог релізу, побачить, що мапу вимкнено, не заглядаючи сюди.
    if (emitsReleaseArtifact && packsDeliveryDisabled && (packKey.isBlank() || packsBaseUrl.isBlank())) {
        logger.lifecycle(
            "PHANTOM: доставку пакiв країн ВИМКНЕНО свідомо (phantom.packsDeliveryDisabled). " +
                "У цьому артефакті офлайн-мапа поза вбудованою Україною не працює: " +
                "завантажити пак нема звідки, а внесений руками не буде прийнято.",
        )
    }
    // Той самий предикат — і найтихіша з усіх підмін. Без keystore.properties
    // реліз падав на відлагоджувальний ключ, пароль якого лежить у цьому ж
    // репозиторії трьома десятками рядків нижче: свіжий клон випускав APK, який
    // Android приймає як наш, і зовні він не відрізняється від справжнього.
    // Краще не зібратись, ніж зібратись чужим підписом.
    if (emitsReleaseArtifact && releaseKeystore == null && !unsignedRelease) {
        throw GradleException(
            "keystore.properties із релізним ключем не знайдено (або storeFile не існує) — " +
                "release-артефакт вийшов би підписаним ВІДЛАГОДЖУВАЛЬНИМ ключем із паролем " +
                "«phantom», який лежить у git. Покладіть keystore.properties поруч із " +
                "settings.gradle.kts: storeFile/storePassword/keyAlias/keyPassword. " +
                "Відмова підписувати реліз ключем, який має кожен.\n" +
                "Якщо це збірка в хмарі, яку підпишуть удома: -Pphantom.unsignedRelease=true. " +
                "Тоді артефакт виходить БЕЗ підпису взагалі — Android його не поставить, " +
                "доки apksigner не підпише його справжнім ключем.",
        )
    }
    // Непідписаний артефакт — не «майже реліз». Поки його не підписали вдома,
    // він нікуди не ставиться; хто читатиме лог, побачить це, не заглядаючи сюди.
    if (emitsReleaseArtifact && unsignedRelease) {
        logger.lifecycle(
            "PHANTOM: release збирається БЕЗ ПІДПИСУ (phantom.unsignedRelease). " +
                "Цей APK не встановиться ні на що. Підпис удома: apksigner sign --ks <ключ> " +
                "--out <підписаний> <цей файл>, далі apksigner verify --print-certs і звірка " +
                "відбитка з signatureSha256 у keystore.properties.",
        )
    }
    // Той самий предикат — той самий заслін. Явний номер, який не більший за
    // вже виданий, означає або збірку, яку Play відхилить, або оголошення, що
    // описує ці байти чужою сумою. Обидва відкриваються мовчки й помічаються
    // місяцями пізніше. Без -P сюди не потрапити: releaseVersionCode завжди
    // publishedBuild + 1.
    if (emitsReleaseArtifact && releaseVersionCode <= publishedBuild) {
        throw GradleException(
            "phantom.versionCode=$releaseVersionCode не більший за вже опубліковану збірку " +
                "$publishedBuild. Play приймає кожен номер лише раз, а пристрій показує " +
                "оновлення лише коли оголошений номер БІЛЬШИЙ за встановлений. " +
                "Приберіть -Pphantom.versionCode — стандарт дасть ${publishedBuild + 1}.",
        )
    }
}

android {
    namespace = "local.phantom.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "local.phantom.companion"
        // minSdk 29 = Android 10. Picked for: BiometricPrompt class 3 stable,
        // EncryptedSharedPreferences, GPS background-location dance, foreground
        // service typing. RenderEffect blur (API 31) is opt-in via SDK_INT
        // checks inside `GlassCard`, so 29-30 devices fall back to a flat tint.
        minSdk = 29
        targetSdk = 36
        // Номер збірки — властивість, бо Play приймає кожен APK лише раз, а
        // політика ліцензії вміє відсікати збірки, старіші за заданий номер
        // (`min_build`). Обидва механізми читають саме це число. Стандарт лишає
        // 1 для відлагоджувальних збірок; release перекриває його на виходах
        // варіанта (onVariants нижче) числом releaseVersionCode.
        versionCode = versionCodeValue ?: 1
        // Чия збірка стоїть на телефоні: кілька сесій ділять один пристрій, тож
        // versionName несе git describe — dumpsys package відповідає без слідства.
        val gitStamp = providers.of(GitStampSource::class) {}.get()
            .takeIf { it.isNotBlank() && !it.contains(' ') }
        versionName = ((project.findProperty("phantom.versionName") as String?)
            ?: localProps.getProperty("phantom.versionName", "0.1.0-tier1")) +
            (gitStamp?.let { "+$it" } ?: "")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ЄДИНІ ТРИ МОВИ ПАКУНКА. Наше дерево російської не несло жодного
        // рядка — а на апараті TalkBack читав «Отображает карту, созданную при
        // помощи MapLibre», «Закрыть лист», «Маркер перемещения» і «Компас.
        // Активируйте…». Усе це `values-ru` З БІБЛІОТЕК (MapLibre, Material3),
        // які приїжджають в APK разом з aar. Сторож російської дивився лише в
        // НАШІ файли, тож був зелений і сліпий водночас.
        //
        // Фільтр вирізає чужі локалі на злитті ресурсів: лишаються `uk`, `en` і `cs`
        // плюс наш стандартний `values/` (українська). На апараті з російською
        // системною мовою бібліотечний рядок тепер падає в стандарт, а не в
        // російський. Другий шар — наші власні перекриття тих самих імен у
        // `values/` та `values-en/`: вони діють навіть тоді, коли цей рядок
        // хтось знесе.
        // Хвильова мова (values-de, …) додається сюди лише з AppLanguage.NATIVE_READY:
        // напівперекладений екран змішав би мови. Сторож — WaveLocaleResourcesTest.
        resourceConfigurations += listOf("uk", "en", "cs")
    }

    androidResources {
        noCompress += listOf("onnx")
    }

    // Distribution split (2026-07-20, Play-compliance pass, brief 03 §4.6):
    //   field — the sideload build: full god-mode surface (Accessibility
    //           screen awareness, notification listener, call screening,
    //           autopilot missions, QUERY_ALL_PACKAGES, mesh radios).
    //   store — the Play-submittable build: src/store/AndroidManifest.xml
    //           strips the god-mode services and every permission Play
    //           rejects or that only god-mode/mesh features need. The CODE
    //           still ships (feature-godmode stays on the classpath —
    //           feature-stream links against it); with the services never
    //           declared and the permissions absent, the capability is
    //           inert. Module-level exclusion needs the :feature-stream →
    //           :feature-godmode edge inverted first (module surgery task).
    // NOTE for gates/CI: with flavors, :app's unit-test task is
    // testFieldDebugUnitTest / testStoreDebugUnitTest — a bare
    // `./gradlew testDebugUnitTest` silently skips :app now.
    flavorDimensions += "distribution"
    productFlavors {
        create("field") {
            dimension = "distribution"
            isDefault = true
        }
        create("store") {
            dimension = "distribution"
            applicationId = "dev.mobilabs.app"
            // Каталог Google Play Billing: `<productId>:<inapp|subs>` через кому.
            // Ідентифікатори мусять збігатися з тими, що створено в Play Console
            // (docs/play/CHECKLIST.md), і з PLAY_PRODUCT_TIERS на сервері ліцензій.
            // Порожній = купівлі в застосунку немає зовсім (CheckoutFactory → null).
            val playProducts = (project.findProperty("phantom.playProducts") as String?)
                ?: localProps.getProperty("phantom.playProducts", "personal_licence:inapp,crew_licence:inapp")
            buildConfigField("String", "PLAY_PRODUCTS", "\"$playProducts\"")
        }
        // Solana dApp Store (Seeker). Маніфест — дієта store; Play Billing немає,
        // ліцензія продається гаманцем, оновлення привозить dApp Store.
        create("seeker") {
            dimension = "distribution"
            applicationIdSuffix = ".seeker"
        }
    }
    sourceSets.getByName("seeker").manifest.srcFile("src/store/AndroidManifest.xml")

    signingConfigs {
        // Debug signing uses a deterministic keystore generated at first build
        // via `scripts/build_apk.sh` so the resulting APK is always installable
        // and even Gradle-less manual rebuilds (the original Day-19 fallback
        // path) sign with the same identity.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "phantom"
            keyAlias = "phantom"
            keyPassword = "phantom"
        }

        // Реліз НЕ може підписуватись ключем із паролем «phantom» у репозиторії:
        // будь-хто зібрав би оновлення, яке Android прийме як наше. Ключ живе в
        // keystore.properties поза git; без нього релізного підпису просто немає,
        // а гейт у taskGraph.whenReady зупиняє збірку ще до першої задачі.
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
            // ДВІ ЗБІРКИ НА ОДНОМУ АПАРАТІ. 06.09 власник: «звідки він взагалі
            // ще є у телефоні?? виходячи з цього я твоїм словам про зміни у
            // застосунку не вірю». Вимір на A52 пояснив: поруч стояли
            // `local.phantom.companion` (05.09) і `…​.debug` (06.09) — різний
            // код під ОДНІЄЮ назвою і ОДНІЄЮ іконкою. Хвилі ставлять debug,
            // а він, відкриваючи «PHANTOM» зі списку програм, відкривав інший.
            //
            // Ім'я і значок розводить `src/debug/res`. Тут — третє місце, де
            // збірки плутались: ПОСИЛАННЯ. Обидва пакети оголошували
            // `phantom://invite|peer|peergroup|msgr`, тож система або питала
            // «яким відкрити», або мовчки віддавала посилання продакшену —
            // саме так `peer-message-check.py` міряв не ту збірку.
            //
            // Розводимо не мову, а ЛОВЛЮ. Генерують обидві збірки той самий
            // `phantom://…` (лист-запрошення мусить відкриватись у будь-кого,
            // і посилання, зроблене на debug, лишається дійсним назавжди), а
            // приймає debug власну схему. Внутрішні сповіщення не зачеплені:
            // їхні наміри явні — `Intent(context, MainActivity::class.java)`,
            // тож чужий пакет їх не перехоплював ніколи.
            //
            // ЦІНА, названа вголос: на апараті, де стоїть ЛИШЕ debug, тапнуте
            // ззовні `phantom://` не має обробника. Перевірки женуть через
            // `-p <пакет>` і `phantom-debug://` (див. scripts/peer-*-check.py).
            manifestPlaceholders["linkScheme"] = "phantom-debug"
            buildConfigField("String", "LINK_SCHEME", "\"phantom-debug\"")
            packaging {
                doNotStrip("**/*.so")
            }
        }
        release {
            // R8 enabled — proguard-rules.pro covers Ktor, BouncyCastle,
            // Room, SQLCipher, ONNX Runtime, Vosk, MediaPipe, MapLibre.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Свідомо НЕпідписаний реліз: збірка в хмарі, підпис удома.
            // Релізний ключ не покидає ноутбука, а ноутбук релізу не тягне
            // (Gradle просить ~7 ГБ, доступно 4). Це не дилема: збірка й підпис
            // — різні дії, і apksigner коштує десятки мегабайтів і секунди.
            //
            // Тут ЯВНЕ null, а не «просто немає ключа»: артефакт виходить без
            // підпису взагалі, тож Android відмовиться його ставити, і сплутати
            // його з готовим до роздачі неможливо. Заслін на keystore лишається
            // на місці для всіх інших шляхів — див. taskGraph.whenReady вище.
            if (unsignedRelease) {
                signingConfig = null
            } else {
                signingConfigs.findByName("release")?.let { signingConfig = it }
            }
            // Мова посилань продукту. Її НЕ можна міняти: `phantom://invite/…`
            // роздано людям і лежить у чужих месенджерах.
            manifestPlaceholders["linkScheme"] = "phantom"
            buildConfigField("String", "LINK_SCHEME", "\"phantom\"")
        }
        // Phase A3 — feature flags. Each flag gates a Phase B–F subsystem
        // that is currently scaffolding only. Flipping to `true` is the
        // last step of the corresponding phase, after the implementation
        // has been verified on-device.
        all {
            // Однакове в debug і release — див. `packSchemaBuild` угорі.
            buildConfigField("int", "PACK_SCHEMA_BUILD", "$packSchemaBuild")
            buildConfigField("String", "WEB_BUNDLE_SHA", "\"$webBundleSha\"")
            buildConfigField("String", "WEB_BUNDLE_SYNCED", "\"${webBundleSeal["synced_at"].orEmpty().take(10).split('-').reversed().take(2).joinToString(".")}\"")
            buildConfigField("Boolean", "ENABLE_SENSOR",         "true")
            buildConfigField("Boolean", "ENABLE_CONTEXT",        "true")
            // Серверні двері сцени зустрічі: сторінки й оцінки закладів, «Я на місці»,
            // бали. Увімкнено рішенням власника №6.4 (10.10, docs/scene/MEETUP_CONTRACT.md §7):
            // ці заяви підписує ОКРЕМИЙ ключ заяв (ClaimKeyStore), не ключ месенджера.
            // SceneGuardsTest червоніє, якщо двері відчинено, а підпис веде не він.
            buildConfigField("Boolean", "SCENE_SERVER", "true")
            buildConfigField("Boolean", "ENABLE_VISION_PREMIUM", "false")
            buildConfigField("Boolean", "ENABLE_WEAR_SYNC",      "false")
            // Dev host for the self-hosted geo stack, as seen FROM the device:
            // 10.0.2.2 = the emulator's alias for the host laptop. A physical
            // phone needs the laptop's LAN IP — set `phantom.geoDevHost` in
            // local.properties (loaded above) or pass -Pphantom.geoDevHost=…,
            // or override per-install in Settings → Мапа → Геосервіси. Only the
            // DEBUG default consumes this (see MapPreferences.geoEndpointsConfig);
            // release ignores it. Precedence: -P flag > local.properties > default.
            val geoDevHost = (project.findProperty("phantom.geoDevHost") as String?)
                ?: localProps.getProperty("phantom.geoDevHost", "10.0.2.2")
            buildConfigField("String", "GEO_DEV_HOST", "\"$geoDevHost\"")

            // ATLAS-3 — ключ Transitland (глобальні розклади: зупинки, лінії,
            // табло). Стандарт ПОРОЖНІЙ: збірка без ключа не вдає, ніби знає
            // транспорт за межами наших власних фідів — джерело просто чесно
            // відсутнє. Задається лише в local.properties або -P; клієнт шле
            // його заголовком, ніколи в URL.
            val transitlandKey = (project.findProperty("phantom.transitlandKey") as String?)
                ?: localProps.getProperty("phantom.transitlandKey", "")
            buildConfigField("String", "TRANSITLAND_KEY", "\"$transitlandKey\"")

            // ATLAS-3 — якір довіри пакiв країн. Той самий Ed25519 і той самий
            // SignedDocument, що й у ліцензійного сертифіката, лише інша пара:
            // паки підписує складач паків, і ліцензійний ключ не має ставати
            // ключем до геометрії. Порожній = «не приймається жоден
            // завантажений пак», а не «приймаються всі» — збірка без якоря
            // живе на вбудованій Україні. Приватний ключ на пристрої не буває
            // ніколи: клієнт уміє лише перевіряти.
            buildConfigField("String", "PACK_PUBLIC_KEY", "\"$packKey\"")

            // ATLAS-3 — статичний хост пакiв країн (наш бакет phantom-maps на
            // R2): `<база>/<id>/manifest.json` плюс названі манiфестом файли.
            // ПОРОЖНЯ ЗА ЗАМОВЧУВАННЯМ і вшитого дефолту не має: збірка, яка
            // мовчки ходить на чужу адресу по геометрію, — та сама релізна
            // ліцензійна бомба, що й публічний Nominatim у фолбеку. Порожня
            // адреса = завантаження пакiв просто нема (PackDownload.NotConfigured).
            buildConfigField("String", "PACKS_BASE_URL", "\"$packsBaseUrl\"")

            // Наші копії важких моделей (слух Moonshine, голос Supertonic). До
            // 12.09 ці 442 МБ їхали в APK, а той самий файл ще раз лягав у
            // filesDir на першому старті — 690 МБ пакунка й ~884 МБ на диску.
            // Тепер їх качають на дотик, а суми з маніфесту лежать у каталозі
            // всередині підписаного APK.
            // ПОРОЖНЯ ЗА ЗАМОВЧУВАННЯМ і без вшитого запасного хоста: мовчазний
            // відкіт на huggingface і був тим, що тягнуло ~398 МБ назовні без
            // жодного ключа. Порожня = «не встановлено, і качати нема звідки».
            //
            // 12.09 той самий клас знайшли вдруге, і вже на релізі: запис Vosk
            // вів на alphacephei.com, тож збірка САМА тягла 143,9 МБ на
            // холодному старті. Обидва випадки — один літерал у каталозі.
            buildConfigField("String", "MODELS_BASE_URL", "\"$modelsBaseUrl\"")

            // Комерційні ворота. Порожній ключ = «жоден сертифікат не
            // приймається» (чесний вільний рівень). Значення й гейт на порожнечу
            // для release-артефакта — угорі скрипта.
            buildConfigField("String", "LICENSE_PUBLIC_KEY", "\"$licenseKey\"")
            // DL-3 (ніч 09.10): debug без `-Pphantom.licenseHost` виходив без
            // скриньки — листи лежали в телефоні вічно, щойно два апарати не в
            // одній мережі (eduroam: різні підмережі, mDNS глухий). Власник
            // носить лише `.debug`, тож телефонна debug-збірка без адреси —
            // месенджер, що не перетинає мереж. Debug бере наш живий хост;
            // release, як і раніше, без явного хоста не збирається (гейт угорі).
            val relayHost = licenseHost.ifBlank { if (name == "debug") DEBUG_RELAY_HOST else "" }
            buildConfigField("String", "LICENSE_BASE_URL", "\"$relayHost\"")
            val legacyRelay = if (relayHost.isNotBlank() && relayHost.trimEnd('/') != LEGACY_RELAY_HOST) LEGACY_RELAY_HOST else ""
            buildConfigField("String", "LEGACY_RELAY_HOST", "\"$legacyRelay\"")
            // SHA-256 сертифіката підпису. Порожній — перевірку вимкнено:
            // збірка не має вдавати, ніби знає, як має виглядати наш підпис.
            // Заповнюється з keystore.properties разом із релізним ключем.
            // Debug — ЗАВЖДИ порожній: його підписує debug.keystore, і на
            // ноутбуці з keystore.properties кожна така збірка порівнювала
            // себе з релізним відбитком і казала власникові «цю копію
            // підписали не ми» (кадр 06.09 03:52, A52).
            // -P теж, не лише keystore.properties: збірка їде в хмару, а
            // keystore.properties лишається вдома — без цього кожен хмарний
            // артефакт виходив би з порожнім EXPECTED_SIGNATURE, тобто не
            // вмів би впізнати власну підробку. Відбиток сертифіката —
            // значення ПУБЛІЧНЕ (його видно з будь-якого встановленого APK),
            // тож у командному рядку спільної машини йому нічого не загрожує.
            // Приватний ключ у хмару не їде ніколи.
            val signatureSha = if (name == "debug") {
                ""
            } else {
                (project.findProperty("phantom.signatureSha256") as String?)
                    ?: keystoreProps.getProperty("signatureSha256", "")
            }
            buildConfigField("String", "EXPECTED_SIGNATURE", "\"$signatureSha\"")

            // Живий шар міток. Обидва порожні → AppContainer не піднімає площину.
            buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Compose stability + suppress deprecation noise from BouncyCastle 1.78
        // pulling JDK 8 helpers we don't actually invoke.
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // 05.09 privacy-truth audit — every existing unit test here happened to
    // avoid the one code path that calls android.util.Log without going
    // through Robolectric, so the gap was invisible: a plain JVM unit test
    // exercising a real ModelManager download FAILURE (not just success)
    // hits Log.e/Log.i and the stub throws instead of returning, killing the
    // test with a crash that has nothing to do with the assertion it was
    // checking. :core-ai already carries this exact setting — see its
    // build.gradle.kts — for the same reason.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            // Коло 3 (perms) додало в :app ще тести Robolectric: android-all у тій самій
            // JVM, що й «стеля — та сама, що в меші» (рядок ~43 МБ), і типових 512 МБ
            // Gradle забракло — OutOfMemoryError у WebMessengerSaveTest й
            // OutboundHostAllowlistTest, а GC-трясіння валило заміри часу поруч.
            all { it.maxHeapSize = "2g" }
        }
    }

    packaging {
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/io.netty.versions.properties",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                // Таблиці LowMC для Picnic — 1,21 МБ у кожному APK. Це ресурси
                // всередині bcprov; R8 їх не бачить, `isShrinkResources` теж (він
                // про res/, не про jar-ресурси), тож їх прибирає лише пакувальник.
                // Постквантового Picnic код не торкається. Вужче за `pqc/**`
                // навмисно: ML-KEM може знадобитись хвилі forward secrecy.
                "org/bouncycastle/pqc/crypto/picnic/**",
                // Схеми protobuf і шаблон генератора коду — інструментарій збірки,
                // що заїхав у APK транзитом через залежності.
                "google/protobuf/*.proto",
                "com/google/auto/**",
            )
        }
    }

    // The only static analysis that costs no network: Android lint ships
    // inside AGP, so it survives the --offline rule detekt/ktlint would break.
    // The baseline holds today's debt so the gate starts green and only new
    // findings can fail a build; delete lint-baseline.xml to re-measure.
    lint {
        // Базової лінії тут НЕМАЄ і не буде. Раніше стояв рядок
        // `baseline = file("lint-baseline.xml")`, який вказував на неіснуючий
        // файл: перший же прогін створив би базову лінію з УСЬОГО поточного
        // боргу і пройшов зеленим. Наказ штабу 30.08: замість базової лінії —
        // ratchet-число, стеля, яка тільки падає. Борг лишається видимим і
        // поіменним; захований під файлом, якого ніхто не перечитує, — ні.
        //
        // Тестові вихідники виключені НЕ для краси числа: конфігурація
        // androidTest офлайн не розв'язується взагалі (`collection-jvm-1.4.0`
        // немає в кеші, лежить 1.4.4), а ворота ходять `--offline`. Без цього
        // рядка лінт не запускається зовсім. Тобто число нижче — по головних
        // вихідниках `:app`; тестові не перевірені ніколи, і це межа, а не вибір.
        ignoreTestSources = true
        abortOnError = true
        // Other modules belong to other workers — gating :app on their sources
        // would fail this build for code this module cannot fix.
        checkDependencies = false
        checkReleaseBuilds = false
        warningsAsErrors = false
        // Correctness issues that ship as warnings by default but are the ones
        // that actually bite this app: unclosed native/IO handles, wrong
        // coroutine dispatch, main-thread disk work, missing permission checks.
        error += listOf(
            "Recycle",
            "InlinedApi",
            "MissingPermission",
            "CoroutineCreationDuringComposition",
            "UnusedResources",
        )
        // Хвильові мови часткові навмисно; повноту обраних мов тримає OfferedLanguagesTranslatedTest.
        disable += "MissingTranslation"
        textReport = true
        htmlReport = true
        xmlReport = false
    }

    // ABI splits — primary target is arm64-v8a (Samsung Galaxy A56, Radxa).
    // Emits a separate APK per ABI; arm64-v8a alone cuts native-lib payload
    // from ~210 MB (7-ABI fat) to ~35-40 MB. Other ABIs kept for CI coverage.
    //
    // ЦЕ І Є СТЕЛЯ ABI — іншої в проєкті бути не може: AGP валить конфігурацію
    // («Conflicting configuration ... in ndk abiFilters cannot be present when
    // splits abi filters are set»), щойно поруч зʼявиться defaultConfig.ndk
    // .abiFilters. Тож `include` нижче — єдине місце, де це вирішується.
    // Ціна помилки виросла з 0.10.22: tasks-genai несе чотири ABI замість
    // одного (arm64 12,25 + x86_64 14,36 + x86 16,42 + armeabi-v7a 7,93 МіБ).
    // isUniversalApk = false тримає їх нарізно; true склеїв би все в один APK.
    // Звірено тестом AbiCeilingTest (local.phantom.companion.packaging) — міняти
    // список лише разом із ним.
    // AAB ріже ABI сам, а AGP 8.10 на bundle з увімкненими splits валить
    // buildPreBundle (PerModuleBundleTask: «more than one matching element»).
    // Тож для bundle та сама стеля йде через ndk.abiFilters, а splits вимкнено.
    val bundleBuild = gradle.startParameter.taskNames.any { it.substringAfterLast(':').startsWith("bundle") }
    if (bundleBuild) defaultConfig.ndk.abiFilters += listOf("arm64-v8a", "x86_64")
    splits {
        abi {
            isEnable = !bundleBuild
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
}

// The androidTest classpath resolves androidx.fragment to 1.5.4, whose .aar was
// never fetched into the Gradle cache — only its metadata. Any task that has to
// resolve that classpath (lint builds a model for every component) therefore
// dies under --offline. Main already resolves 1.8.2 and that one is cached, so
// this aligns the two rather than adding anything new.
configurations.configureEach {
    resolutionStrategy.force("androidx.fragment:fragment:1.8.2")
}

val voskClasses = listOf("org.vosk.LibVosk", "com.sun.jna.Native")

configurations.matching { it.name.startsWith("store") && it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "com.alphacephei")
    exclude(group = "net.java.dev.jna")
}

// Класи, які тримаються ЗА ІМЕНЕМ, бо їх шукає JNI або рефлексія, а не компілятор.
// Для решти достатньо прямого посилання з Kotlin — там за нас відповідає збірка.
// Приклад, заради якого цей список існує: `-keep class net.sqlcipher.**` стояло в
// proguard-rules.pro після переїзду пакета на net.zetetic і не ловило НІЧОГО. Жоден
// тест цього не показав, бо Room звертається до класів прямо. Правило, яке більше
// нічого не тримає, мовчить — а функція за ним відмовляє в руках у людини.
val r8MustSurvive = listOf(
    "net.zetetic.database.sqlcipher.SQLiteDatabase",
    "ai.onnxruntime.OrtEnvironment",
    "org.vosk.LibVosk",
    "com.sun.jna.Native",
    "org.maplibre.android.maps.MapLibreMap",
    "com.google.android.filament.Engine",
    "com.google.mlkit.vision.barcode.BarcodeScanning",
    "com.google.mlkit.vision.text.TextRecognition",
)

// Перевіряємо ВИЗНАЧЕНІ класи в dex, а не рядки в ньому. Різниця не косметична:
// Рядкова константа для Class.forName (так колись тримався LlmInference) проходила б
// пошук за текстом вічно — навіть коли самого класу
// в збірці вже немає, а Class.forName тихо ловиться в runCatching.
abstract class VerifyDexSurvivors : DefaultTask() {
    @get:InputFiles abstract val apkFolder: DirectoryProperty
    @get:InputFile abstract val mergedManifest: RegularFileProperty
    @get:Input abstract val required: ListProperty<String>
    @get:OutputFile abstract val stamp: RegularFileProperty

    // Кожен <activity>/<service>/<receiver>/<provider> система створює ЗА ІМЕНЕМ
    // із маніфеста. Якщо R8 перейменував або прибрав такий клас, збірка мовчить, а
    // падає воно в людини: сервіс не стартує, екран не відкривається. Список не
    // ведеться вручну — він читається з того самого маніфеста, що поїде в APK,
    // тож нова компонента отримує заслін автоматично, без чиєїсь пам'яті.
    private fun manifestComponents(xml: java.io.File): List<String> {
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder().parse(xml)
        val pkg = doc.documentElement.getAttribute("package")
        // Тільки всередині <application>. У <queries> лежать ЧУЖІ компоненти, які
        // застосунок лише хоче бачити (напр. androidx.car.app.connection.provider);
        // їхніх класів у нашому dex немає й бути не має.
        val app = doc.documentElement.getElementsByTagName("application").item(0) as org.w3c.dom.Element
        val out = ArrayList<String>()
        listOf("activity", "activity-alias", "service", "receiver", "provider").forEach { tag ->
            val nodes = app.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as org.w3c.dom.Element
                val n = el.getAttribute("android:name")
                if (n.isNotEmpty()) out.add(if (n.startsWith(".")) pkg + n else n)
            }
        }
        return out.distinct()
    }

    private fun u32(d: ByteArray, o: Int): Int =
        (d[o].toInt() and 255) or ((d[o + 1].toInt() and 255) shl 8) or
            ((d[o + 2].toInt() and 255) shl 16) or ((d[o + 3].toInt() and 255) shl 24)

    private fun definedIn(dex: ByteArray): List<String> {
        val stringIds = u32(dex, 0x3C)
        val typeIds = u32(dex, 0x44)
        val count = u32(dex, 0x60)
        val off = u32(dex, 0x64)
        val names = ArrayList<String>(count)
        for (i in 0 until count) {
            val typeIdx = u32(dex, off + i * 32)
            var p = u32(dex, stringIds + u32(dex, typeIds + typeIdx * 4) * 4)
            while (dex[p].toInt() and 0x80 != 0) p++
            p++
            val sb = StringBuilder()
            while (dex[p].toInt() != 0) { sb.append((dex[p].toInt() and 0xFF).toChar()); p++ }
            val d = sb.toString()
            if (d.length > 2 && d[0] == 'L') names.add(d.substring(1, d.length - 1).replace('/', '.'))
        }
        return names
    }

    @TaskAction
    fun check() {
        val apks = apkFolder.get().asFile.walkTopDown().filter { it.name.endsWith(".apk") }.toList()
        if (apks.isEmpty()) throw GradleException("нема APK для перевірки у ${apkFolder.get().asFile}")
        val components = manifestComponents(mergedManifest.get().asFile)
        if (components.isEmpty()) {
            // Порожній список пройшов би «успішно» й не перевірив нічого — саме так
            // виглядає заслін, що зламався. Хай краще падає.
            throw GradleException("у злитому маніфесті не знайдено жодної компоненти — перевірка була б порожньою")
        }
        val want = required.get() + components
        val report = StringBuilder()
        apks.forEach { apk ->
            val defined = HashSet<String>()
            ZipFile(apk).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.startsWith("classes") && it.name.endsWith(".dex") }
                    .forEach { e -> zip.getInputStream(e).use { defined += definedIn(it.readBytes()) } }
            }
            val gone = want.filterNot(defined::contains)
            if (gone.isNotEmpty()) {
                throw GradleException(
                    "R8 не лишив у ${apk.name} класи, за якими стоїть жива функція: " +
                        gone.joinToString(", ") + ". Це або правило -keep, що застаріло " +
                        "(пакет у бібліотеці перейменували), або знята залежність. " +
                        "Полагодьте правило в app/proguard-rules.pro — або, якщо функції " +
                        "справді більше немає, приберіть ім'я зі списку r8MustSurvive.",
                )
            }
            report.append(apk.name)
                .append(" dex-класів=").append(defined.size)
                .append(" перевірено=").append(want.size)
                .append(" (з них компонент маніфеста ").append(components.size).append(")\n")
        }
        stamp.get().asFile.parentFile.mkdirs()
        stamp.get().asFile.writeText(report.toString())
    }
}

abstract class VerifyWebBundleSeal : DefaultTask() {
    @get:InputFiles abstract val packages: ConfigurableFileCollection
    @get:Input abstract val prefix: Property<String>
    @get:Input abstract val expectedWebSha: Property<String>
    @get:OutputFile abstract val stamp: RegularFileProperty

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun fail(apk: String, why: String): Nothing = throw GradleException(
        "веб-месенджер у $apk: $why\n" +
            "Бандл змінено руками або скопійовано без печатки. Перезберіть його з коміта веба:\n" +
            "  scripts/sync_web_messenger.sh <гілка або SHA веба>\n" +
            "і закомітьте app/src/main/assets/webmsgr разом із BUNDLE_SOURCE.",
    )

    // Читаємо сам APK, а не теку в src: сторож мусить бачити те, що поїде на
    // телефон, разом із тим, що aapt викинув чи підмішав з іншого source set.
    @TaskAction
    fun check() {
        val prefix = prefix.get()
        val apks = packages.asFileTree.filter { it.name.endsWith(".apk") || it.name.endsWith(".aab") }.toList()
        if (apks.isEmpty()) throw GradleException("нема APK чи AAB для перевірки у ${packages.files}")
        val report = StringBuilder()
        apks.forEach { apk ->
            ZipFile(apk).use { zip ->
                val entries = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith(prefix) }
                    .associateBy { it.name.removePrefix(prefix) }
                if (entries.isEmpty()) fail(apk.name, "у пакунку немає жодного файла $prefix")
                val seal = entries["BUNDLE_SOURCE"]?.let { e -> zip.getInputStream(e).use { String(it.readBytes()) } }
                    ?: fail(apk.name, "немає печатки ${prefix}BUNDLE_SOURCE — походження невідоме.")
                val fields = seal.lines().mapNotNull { l ->
                    l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1).trim() }
                }.toMap()
                val webSha = fields["web_sha"].orEmpty()
                val recorded = fields["content_sha256"].orEmpty()
                if (!Regex("[0-9a-f]{40}").matches(webSha)) fail(apk.name, "печатка без повного web_sha.")
                if (!Regex("[0-9a-f]{64}").matches(recorded)) fail(apk.name, "печатка без content_sha256 — стара печатка або вписана руками.")
                if (webSha != expectedWebSha.get()) {
                    fail(apk.name, "печатка в APK каже веб $webSha, а BuildConfig — ${expectedWebSha.get()}.")
                }
                // Побайтовий порядок, як `LC_ALL=C sort` у scripts/find_web_repo.sh.
                val names = entries.keys.filter { it != "BUNDLE_SOURCE" }
                    .sortedWith { a, b ->
                        val x = a.toByteArray(); val y = b.toByteArray()
                        (0 until minOf(x.size, y.size)).firstOrNull { x[it] != y[it] }
                            ?.let { (x[it].toInt() and 255) - (y[it].toInt() and 255) } ?: (x.size - y.size)
                    }
                val listing = StringBuilder()
                names.forEach { n ->
                    listing.append(sha256(zip.getInputStream(entries.getValue(n)).use { it.readBytes() }))
                        .append("  ").append(n).append('\n')
                }
                val actual = sha256(listing.toString().toByteArray())
                if (actual != recorded) {
                    fail(
                        apk.name,
                        "вміст не збігається з печаткою (записано ${recorded.take(12)}, у пакунку ${actual.take(12)}, " +
                            "файлів ${names.size}, у печатці ${fields["files"] ?: "?"}).",
                    )
                }
                report.append(apk.name).append(" web=").append(webSha.take(8))
                    .append(" files=").append(names.size).append(" content=").append(actual).append('\n')
            }
        }
        stamp.get().asFile.parentFile.mkdirs()
        stamp.get().asFile.writeText(report.toString())
    }
}

// ВОРОТА ДОЗВОЛІВ PLAY. Злитий маніфест store-збірки — разом із тим, що
// підмішали бібліотеки (Play Billing, WorkManager, ML Kit…), — звіряється з
// таблицею docs/play/PERMISSIONS.md. Дозвіл без рядка обґрунтування валить
// збірку: інакше бібліотека, оновлена мимохідь, додала б у Play-версію дозвіл,
// про який ніхто не знає й на який форма декларацій не відповідає. Рядок без
// дозволу — теж відмова: декларація, що описує неіснуюче, так само неправда.
abstract class VerifyStorePermissions : DefaultTask() {
    @get:InputFile abstract val mergedManifest: RegularFileProperty
    @get:InputFile abstract val declarations: RegularFileProperty
    @get:Input abstract val applicationId: Property<String>
    @get:OutputFile abstract val stamp: RegularFileProperty

    @TaskAction
    fun check() {
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(mergedManifest.get().asFile)
        val ns = "http://schemas.android.com/apk/res/android"
        val nodes = doc.documentElement.getElementsByTagName("uses-permission")
        val merged = (0 until nodes.length)
            .map { (nodes.item(it) as org.w3c.dom.Element).getAttributeNS(ns, "name") }
            // Власні сигнатурні дозволи застосунку (DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
            // від androidx.core) — не дозвіл у людини, декларувати нічого.
            .filterNot { it.startsWith(applicationId.get() + ".") }
            .toSet()
        val table = declarations.get().asFile.readLines()
            .mapNotNull { Regex("""^\|\s*`([a-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)`""").find(it)?.groupValues?.get(1) }
            .toSet()
        val undeclared = merged - table
        val phantom = table - merged
        if (undeclared.isNotEmpty() || phantom.isNotEmpty()) {
            throw GradleException(
                "Play-збірка і docs/play/PERMISSIONS.md розійшлись.\n" +
                    "  у маніфесті, без обґрунтування: ${undeclared.sorted()}\n" +
                    "  в обґрунтуваннях, але не в маніфесті: ${phantom.sorted()}\n" +
                    "Або знесіть дозвіл у src/store/AndroidManifest.xml (tools:node=\"remove\"), " +
                    "або допишіть рядок у таблицю — з поясненням для форми декларацій Play Console.",
            )
        }
        stamp.get().asFile.parentFile.mkdirs()
        stamp.get().asFile.writeText(merged.sorted().joinToString("\n", postfix = "\n"))
    }
}

androidComponents {
    onVariants(selector().withFlavor("distribution" to "store")) { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercaseChar() }
        val gate = tasks.register<VerifyStorePermissions>("verify${cap}Permissions") {
            mergedManifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
            declarations.set(rootProject.layout.projectDirectory.file("../docs/play/PERMISSIONS.md"))
            applicationId.set(variant.applicationId)
            stamp.set(layout.buildDirectory.file("outputs/logs/${variant.name}-play-permissions.txt"))
        }
        tasks.matching { it.name == "assemble$cap" || it.name == "bundle$cap" }.configureEach { dependsOn(gate) }
    }
}

// Злитий маніфест seeker-збірки (з бібліотеками) проти того, на що рецензент
// dApp Store відповість відмовою. Той самий список у SeekerManifestTest.
abstract class VerifySeekerManifest : DefaultTask() {
    @get:InputFile abstract val mergedManifest: RegularFileProperty
    @get:OutputFile abstract val stamp: RegularFileProperty

    @TaskAction
    fun check() {
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(mergedManifest.get().asFile)
        val ns = "http://schemas.android.com/apk/res/android"
        fun names(tag: String, attr: String) = doc.documentElement.getElementsByTagName(tag).let { n ->
            (0 until n.length).map { (n.item(it) as org.w3c.dom.Element).getAttributeNS(ns, attr) }
        }
        val forbidden = setOf(
            "android.permission.SEND_SMS", "android.permission.RECEIVE_SMS", "android.permission.READ_SMS",
            "android.permission.ACCESS_MOCK_LOCATION", "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.REQUEST_INSTALL_PACKAGES", "android.permission.QUERY_ALL_PACKAGES",
            "com.android.vending.BILLING",
        )
        val binds = setOf(
            "android.permission.BIND_ACCESSIBILITY_SERVICE",
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
            "android.permission.BROADCAST_SMS",
        )
        val bad = (names("uses-permission", "name").toSet() intersect forbidden) +
            ((names("service", "permission") + names("receiver", "permission")).toSet() intersect binds)
        if (bad.isNotEmpty()) {
            throw GradleException("Seeker-збірка несе те, що dApp Store відхилить: ${bad.sorted()}")
        }
        stamp.get().asFile.parentFile.mkdirs()
        stamp.get().asFile.writeText(names("uses-permission", "name").sorted().joinToString("\n", postfix = "\n"))
    }
}

androidComponents {
    onVariants(selector().withFlavor("distribution" to "seeker")) { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercaseChar() }
        val gate = tasks.register<VerifySeekerManifest>("verify${cap}Manifest") {
            mergedManifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
            stamp.set(layout.buildDirectory.file("outputs/logs/${variant.name}-seeker-manifest.txt"))
        }
        tasks.matching { it.name == "assemble$cap" || it.name == "bundle$cap" || it.name == "test${cap}UnitTest" }
            .configureEach { dependsOn(gate) }
    }
}

androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercaseChar() }
        val seal = tasks.register<VerifyWebBundleSeal>("verify${cap}WebBundleSeal") {
            packages.from(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK))
            prefix.set("assets/webmsgr/")
            expectedWebSha.set(webBundleSha)
            stamp.set(layout.buildDirectory.file("outputs/logs/${variant.name}-web-bundle-seal.txt"))
        }
        val aabSeal = tasks.register<VerifyWebBundleSeal>("verify${cap}WebBundleSealAab") {
            packages.from(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.BUNDLE))
            prefix.set("base/assets/webmsgr/")
            expectedWebSha.set(webBundleSha)
            stamp.set(layout.buildDirectory.file("outputs/logs/${variant.name}-web-bundle-seal-aab.txt"))
        }
        tasks.matching { it.name == "assemble$cap" || it.name == "install$cap" }.configureEach { dependsOn(seal) }
        tasks.matching { it.name == "bundle$cap" }.configureEach { dependsOn(aabSeal) }
    }
}

androidComponents {
    onVariants { variant ->
        if (variant.buildType != "release") return@onVariants
        val cap = variant.name.replaceFirstChar { it.uppercaseChar() }

        // На виходах, а не в defaultConfig: так debug лишається на 1, а кожен
        // ABI-зріз release несе один і той самий номер.
        variant.outputs.forEach { it.versionCode.set(releaseVersionCode) }

        // Стиснення dex і .so — лише для `field`, тобто для збірки, яку людина
        // качає з кабінету по своєму каналу. Там мірою є хвилини на поганій
        // мережі, і 200 кбіт/с роблять із кожних 10 МБ ще 7 хвилин чекання.
        // `store` лишається як був: Play роздає з AAB і стискає транспортом сам,
        // тож те саме перемикання там лише додало б розпакування на пристрої.
        if (variant.flavorName == "field") {
            variant.packaging.dex.useLegacyPackaging.set(true)
            variant.packaging.jniLibs.useLegacyPackaging.set(true)
        }

        val guard = tasks.register<VerifyDexSurvivors>("verify${cap}Survivors") {
            apkFolder.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK))
            mergedManifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
            required.set(if (variant.flavorName == "store") r8MustSurvive - voskClasses else r8MustSurvive)
            stamp.set(layout.buildDirectory.file("outputs/logs/${variant.name}-survivors.txt"))
        }
        // `tasks.named` тут падає: всередині onVariants задачі варіанта ще не
        // зареєстровані, і звертання за іменем валить конфігурацію всього проєкту,
        // а не лише :app. Жива вибірка чекає, доки задача з'явиться.
        tasks.matching { it.name == "assemble$cap" }.configureEach { dependsOn(guard) }
    }
}

dependencies {
    // Wear OS 3+ ships standalone — no `wearApp(project(":wear"))` legacy bundling.
    // The `:wear` module is built separately and installed via Play directly,
    // not embedded as a raw resource inside the phone APK. Bundling required
    // both modules to share an applicationId, which we deliberately split
    // (companion vs companion.wear) for clearer telemetry.
    // Phase 1-C-3 (companion-v2 wire-up) — local modules.
    implementation(project(":core-voice"))
    implementation(project(":core-ai"))
    implementation(project(":core-data"))
    implementation(project(":core-net"))
    implementation(project(":core-nav"))

    // HIGHWAY DOMINANCE — the car head. Reads :core-nav's engine; owns no route.
    //
    // BOTH artifacts are required, and it is not obvious why: `app-projected` is
    // only the projected-host glue, and it does NOT put `androidx.car.app:app`
    // (CarContext, Screen, NavigationTemplate — i.e. every type the car code
    // actually names) on the compile classpath. Declaring only `app-projected`
    // resolves cleanly and then fails to compile.
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.car.app.projected)
    implementation(project(":core-sensor"))
    implementation(project(":core-context"))
    implementation(project(":core-design"))
    implementation(project(":core-license"))
    // МЕТРО — інструмент `metro.station` із `:core-geo` реєструється в
    // toolRegistry нижче. Під землею супутника немає, а в Києві метро ще й
    // основне укриття, тож схема станції — це та відповідь, якої решта стека
    // дати не може.
    implementation(project(":core-geo"))
    implementation(project(":core-symbiote"))
    // ImageTool відкриває ImageInspector у сигнатурі — app мусить бачити core-vision.
    implementation(project(":core-vision"))  // Phase 3.B — OfflineIntentQueue + drain worker
    // Symbiote foundation (Tier 14+ per docs/SYMBIOTE_VISION.md). AppContainer
    // registers each Tier-14+ feature with the Constitution before the
    // sub-modules are exposed to the rest of the graph; depending on all
    // five from `:app` keeps the wiring explicit in one place.
    implementation(project(":feature-onboarding"))
    implementation(project(":feature-stream"))
    implementation(project(":core-truth"))
    implementation(project(":core-memory"))
    implementation(project(":core-solana"))
    // Купюра «телефон до телефона» без роутера. Лише field: у store цієї дороги немає,
    // і маніфест Play не отримує BLUETOOTH_* / NEARBY_WIFI_DEVICES.
    "fieldImplementation"(libs.play.services.nearby)
    implementation(libs.play.services.auth.blockstore)
    // Геоплатформа та субметрова навігація: екран чесного стану позиції
    // (небесна карта, виключені супутники, розбіжність GPS↔мережа, вердикт РЕБ)
    // читає реальні сирі виміри через :core-gnss.
    implementation(project(":feature-precision"))
    implementation(project(":core-gnss"))
    implementation(project(":feature-car"))
    implementation(project(":feature-godmode"))
    implementation(project(":feature-ambient"))

    // Room types leak through PhantomDatabase (a RoomDatabase subclass)
    // when :app touches AppContainer.profileStore — without these the
    // compiler can't see the supertype and refuses to load
    // PhantomDatabase. Cheaper than re-declaring core-data deps as
    // `api` (would force every other consumer to re-resolve too).
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    // Журнал розмов — окрема зашифрована база (msgstore).
    implementation(libs.sqlcipher.android)

    // Купівля ліцензії в Play-версії — ЛИШЕ store. Польова збірка Play Billing
    // не несе: вона їде повз Play і продає ключ на сайті.
    "storeImplementation"(libs.play.billing)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.biometric)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.websockets)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.zxing.core)
    implementation(libs.bouncycastle)
    implementation(libs.guava)
    implementation(libs.onnxruntime.android)

    // DA-5 — WorkManager. Migrates StreamCleanupScheduler +
    // NotificationDigestScheduler off the coroutine-loop pattern so
    // they survive process kill on OEMs that aggressively clamp
    // background work (Samsung Sleeping Apps, Xiaomi MIUI, Huawei).
    // The 9 am notification digest is the operator-visible payoff:
    // pre-DA-5 it never fired after device reboot; post-DA-5 it's
    // a system-broker-backed schedule that boots itself.
    implementation(libs.androidx.work.runtime.ktx)

    // Phase-0 follow-up — :app unit tests for PhantomLink helpers and other
    // cross-cutting glue that doesn't fit in any feature/core module.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // DA-5 — assert PeriodicWorkRequest constraints (interval,
    // network, unique-name policy) on the WorkScheduling helpers
    // without spinning up an actual WorkManager runtime.
    testImplementation(libs.androidx.work.testing)
    // Продовження строку токена й повтор після 401 перевіряються підробленим
    // рушієм: інакше єдиним способом побачити 401 був би прострочений вузол.
    testImplementation(libs.ktor.client.mock)
    // База розмов у JVM-тестах — той самий SQL через JDBC (рідного SQLite Robolectric на ARM не має).
    testImplementation(libs.sqlite.jdbc)
    // Robolectric — для рамок Android, а не логіки: шторка дзвінка, служба розмови, маршрут звуку,
    // а також дрібні системні речі (дозволи, перемикачі, налаштування) без застосунку й ресурсів.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
