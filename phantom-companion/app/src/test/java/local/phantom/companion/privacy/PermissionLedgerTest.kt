package local.phantom.companion.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * СТОРОЖ ДОЗВОЛІВ.
 *
 * 05.09 вимір на апараті власника (SM-A525F): польова збірка просила
 * READ_PHONE_STATE, людина його ДАЛА — а місцеположення не дала. Тобто мапа
 * лишилась без координат, зате застосунок отримав доступ до стану телефона.
 * Обґрунтування в коді казало «For AI Secretary to detect calls»; секретаря,
 * що помічає дзвінки, у дереві немає, обробник дозволу був порожнім тілом, а
 * магазинний маніфест цей дозвіл уже прибирав — бо Play такого не пропускає.
 * Тобто ми знали, що просити нема за чим, і просили лише в тій збірці, яку
 * ніхто не рецензує.
 *
 * Того самого дня тим самим способом виміряно ще два: UWB_RANGING (єдина
 * згадка в дереві — коментар «платформа не вміє без UWB») та
 * ACTIVITY_RECOGNITION (жодного виклику). Обидва прибрано.
 *
 * А потім цей самий сторож, уже написаний, знайшов те, чого я не шукала:
 * BLUETOOTH_ADVERTISE і BLUETOOTH_CONNECT теж без читачів — і перевірка
 * показала, що **Bluetooth у продукті немає взагалі**: нуль рядків
 * `android.bluetooth` у всьому дереві. Локальна сітка тримається на mDNS
 * через Wi-Fi. BLUETOOTH_SCAN при цьому спершу пройшов ЗЕЛЕНИМ через ваду
 * самого зонда — зразок `startScan` збігся з `WifiManager.startScan`. Урок
 * записано в тілі переліку: доказ мусить бути з тієї самої підсистеми, а не
 * просто схожим словом.
 *
 * ЦІНА ЗАЙВОГО ДОЗВОЛУ НЕ НУЛЬОВА. Android показує їх людині одним списком:
 * зайвий чутливий рядок поруч із потрібним підвищує шанс, що відмовлять
 * ГУРТОМ — і продукт лишиться без того єдиного дозволу, без якого він не
 * працює взагалі. Саме це й сталось на апараті власника.
 *
 * ПРАВИЛО: кожен дозвіл польового маніфесту стоїть у переліку нижче, і кожен
 * позначений `readerProof` мусить мати хоч ОДНОГО читача в `src/main` якогось
 * модуля. Дозвіл без читача — це не «на майбутнє», це прохання без причини.
 *
 * Дзеркало до [[phantom-written-but-never-called]] і [[prove-reach-not-correctness]]:
 * там ми доводили досяжність коду, тут — досяжність дозволу.
 */
class PermissionLedgerTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    private val modules = listOf(
        "app", "core-ai", "core-context", "core-data", "core-design", "core-geo",
        "core-gnss", "core-indoor", "core-license", "core-mobility", "core-nav",
        "core-net", "core-sensor", "core-symbiote", "core-vision", "core-voice",
        "feature-ambient", "feature-car", "feature-godmode", "feature-onboarding",
        "feature-precision", "feature-stream",
    )

    /**
     * Дозвіл -> символи, поява яких у `src/main` доводить, що його справді
     * читають. `null` означає «читача в Kotlin не буває за природою дозволу»
     * (він або оголошується для системи, або споживається платформою) — такі
     * рядки несуть причину в коментарі, а не доказ у коді.
     */
    private val ledger: Map<String, List<String>?> = mapOf(
        // — Позиція: серце продукту. Без цього мапа, маршрут і «до укриття
        //   200 м» не існують. Просити разом із цим щось зайве — шкодити собі.
        "ACCESS_FINE_LOCATION" to listOf("ACCESS_FINE_LOCATION"),
        "ACCESS_COARSE_LOCATION" to listOf("ACCESS_COARSE_LOCATION"),
        "ACCESS_BACKGROUND_LOCATION" to listOf("ACCESS_BACKGROUND_LOCATION"),
        // — «Показати світові іншу точку»: замовлення власника 31.08. Система
        //   віддає стороннім підкладену позицію, наша навігація рахує правду.
        "ACCESS_MOCK_LOCATION" to listOf("addTestProvider", "setTestProviderLocation"),
        // — Слух і голос.
        "RECORD_AUDIO" to listOf("AudioRecord", "MediaRecorder"),
        // — Друга половина тієї самої пари, і без неї перша марна. Chromium у
        //   WebView вимагає ОБИДВА разом: без MODIFY_AUDIO_SETTINGS він навіть
        //   не пробує взяти пристрій запису й пише в logcat «Requires
        //   MODIFY_AUDIO_SETTINGS and RECORD_AUDIO. No audio device will be
        //   available for recording». Заміряно 20.09 на A52: дзвінок тримався
        //   30 с у повній тиші й помирав за таймером, не сказавши людині нічого.
        //   Читач — не наш звуковий код, а рушій усередині WebView; доказом
        //   стоїть те місце, де ми віддаємо йому мікрофон.
        "MODIFY_AUDIO_SETTINGS" to listOf("RESOURCE_AUDIO_CAPTURE"),
        // — Зір.
        "CAMERA" to listOf("androidx.camera", "CameraManager", "CameraX"),
        // — Оновлення поза магазином (польова збірка ставить APK сама).
        "REQUEST_INSTALL_PACKAGES" to listOf("application/vnd.android.package-archive"),
        // — Сітка між пристроями. Вона НЕ на Bluetooth: у всьому дереві нуль
        //   рядків `android.bluetooth`. Локальний звʼязок — mDNS через Wi-Fi
        //   (NsdManager ×15, ServerSocket, MulticastLock). Три дозволи
        //   BLUETOOTH_* стояли в маніфесті без жодного коду і прибрані 05.09.
        "CHANGE_WIFI_MULTICAST_STATE" to listOf("MulticastLock", "createMulticastLock"),
        // — 02.10, лише field: купюра «телефон до телефона» без роутера через
        //   Google Nearby Connections. Bluetooth-коду свого в дереві й далі
        //   немає — радіо веде Nearby, тож доказ читача — його клієнт, а не
        //   схоже слово на кшталт startScan.
        "BLUETOOTH" to listOf("ConnectionsClient"),
        "BLUETOOTH_ADMIN" to listOf("ConnectionsClient"),
        "BLUETOOTH_SCAN" to listOf("ConnectionsClient"),
        "BLUETOOTH_ADVERTISE" to listOf("ConnectionsClient"),
        "BLUETOOTH_CONNECT" to listOf("ConnectionsClient"),
        "NEARBY_WIFI_DEVICES" to listOf("ConnectionsClient"),
        // — Руки над пристроєм (godmode; магазинна збірка це прибирає).
        "QUERY_ALL_PACKAGES" to listOf("getInstalledPackages", "getInstalledApplications", "queryIntentActivities"),
        "SYSTEM_ALERT_WINDOW" to listOf("TYPE_APPLICATION_OVERLAY", "canDrawOverlays"),
        "WRITE_SETTINGS" to listOf("Settings.System.put", "canWrite("),
        // — Календар як контекст.
        "READ_CALENDAR" to listOf("CalendarContract"),
        // — Замок застосунку.
        "USE_BIOMETRIC" to listOf("BiometricPrompt"),
        // — Тривога мусить будити екран і звучати вчасно.
        "SCHEDULE_EXACT_ALARM" to listOf("setExactAndAllowWhileIdle", "AlarmManager"),
        "USE_EXACT_ALARM" to listOf("setExactAndAllowWhileIdle", "AlarmManager"),
        "USE_FULL_SCREEN_INTENT" to listOf("fullScreenIntent"),
        "MANAGE_OWN_CALLS" to listOf("CAPABILITY_SELF_MANAGED"),
        "POST_NOTIFICATIONS" to listOf("POST_NOTIFICATIONS", "NotificationCompat"),
        "VIBRATE" to listOf("Vibrator", "VibrationEffect"),
        "WAKE_LOCK" to listOf("WakeLock", "newWakeLock"),
        // — Без FCM листи й дзвінки тримає лише власна служба; Doze її присипляє.
        "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" to listOf("ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"),
        "RECEIVE_BOOT_COMPLETED" to listOf("BOOT_COMPLETED"),
        // — Мережа й служби переднього плану: споживаються платформою за
        //   оголошенням у маніфесті, читача в Kotlin не мають за природою.
        "INTERNET" to null,
        "ACCESS_NETWORK_STATE" to null,
        "ACCESS_WIFI_STATE" to null,
        "CHANGE_NETWORK_STATE" to null,
        "CHANGE_WIFI_STATE" to null,
        "FOREGROUND_SERVICE" to null,
        "FOREGROUND_SERVICE_DATA_SYNC" to null,
        "FOREGROUND_SERVICE_HEALTH" to null,
        "FOREGROUND_SERVICE_LOCATION" to null,
        "FOREGROUND_SERVICE_MICROPHONE" to null,
        "FOREGROUND_SERVICE_CAMERA" to null,
        "FOREGROUND_SERVICE_SPECIAL_USE" to null,
        // — Не прохання до людини, а замок на плитці «Зупинити все»: лише
        //   системна шторка може її привʼязати. Людині цей рядок не показують.
        "BIND_QUICK_SETTINGS_TILE" to listOf("TileService"),
        // — SMS-смуга «Наміру» (розд. 07 §3.3) — ЛИШЕ польовий маніфест.
        //   Відправити прохання на мій домашній шлюз і прийняти відповідь.
        "SEND_SMS" to listOf("sendTextMessage", "sendMultipartTextMessage"),
        "RECEIVE_SMS" to listOf("SMS_RECEIVED_ACTION"),
    )

    private fun declaredPermissions(): List<String> {
        val manifest = File(repoRoot(), "app/src/main/AndroidManifest.xml")
        assertTrue("немає ${manifest.path}", manifest.isFile)
        // Польова надбудова ДОДАЄ дозволи — вони теж мусять мати рядок і читача.
        val field = File(repoRoot(), "app/src/field/AndroidManifest.xml")
        val text = manifest.readText() + (if (field.isFile) field.readText() else "")
        return Regex("""<uses-permission[^>]*android:name="android\.permission\.([A-Z_]+)"(?![^>]*tools:node="remove")""")
            .findAll(text)
            .map { it.groupValues[1] }
            .distinct()
            .sorted()
            .toList()
    }

    private fun sourceFilesContaining(needles: List<String>): List<File> {
        val root = repoRoot()
        return modules.flatMap { module ->
            listOf("main", "field").map { File(root, "$module/src/$it") }.filter { it.isDirectory }.flatMap { src ->
                src.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .filter { f -> needles.any { f.readText().contains(it) } }
                .toList()
            }
        }
    }

    @Test
    fun `кожен оголошений дозвіл стоїть у переліку з причиною`() {
        val undeclared = declaredPermissions().filterNot { ledger.containsKey(it) }
        assertEquals(
            "Новий дозвіл у польовому маніфесті без рядка в переліку. Додай його " +
                "сюди РАЗОМ із причиною й доказом читача — або прибери з маніфесту. " +
                "Зайвий чутливий дозвіл коштує нам згоди на потрібний.",
            emptyList<String>(),
            undeclared,
        )
    }

    @Test
    fun `кожен дозвіл із доказом справді має читача`() {
        val declared = declaredPermissions().toSet()
        val orphans = ledger
            .filterKeys { it in declared }
            .mapNotNull { (perm, proof) ->
                if (proof == null) null
                else if (sourceFilesContaining(proof).isEmpty()) perm else null
            }
            .sorted()
        assertEquals(
            "Дозвіл оголошено, і його ніхто не читає — саме так READ_PHONE_STATE " +
                "прожив до бети. Або знайди читача, або прибери дозвіл.",
            emptyList<String>(),
            orphans,
        )
    }

    @Test
    fun `прибрані 05_09 дозволи не повернулись`() {
        val gone = listOf(
            "READ_PHONE_STATE", "UWB_RANGING", "ACTIVITY_RECOGNITION",
            // BLUETOOTH_* повернулись 02.10 разом із читачем — Nearby Connections (field).
            // 02.10: пульс читала лише знята поверхня тіла — читача в дереві нуль.
            "BODY_SENSORS",
        )
        val back = declaredPermissions().filter { it in gone }
        assertEquals(
            "Ці три прибрано 05.09 як прохання без причини (нуль читачів у дереві). " +
                "Повертати — лише разом із кодом, що їх справді читає.",
            emptyList<String>(),
            back,
        )
    }
}
