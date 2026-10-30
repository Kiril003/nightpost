package local.phantom.companion.packaging

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * ДОГОВІР PLAY-ЗБІРКИ.
 *
 * Що лишається в store після зняття (src/store/AndroidManifest.xml) — і чи
 * кожен такий дозвіл має обґрунтування для форми декларацій Play у
 * docs/play/PERMISSIONS.md. Рахуємо з НАШИХ маніфестів (app + модулі дерева);
 * злитий маніфест разом зі сторонніми бібліотеками звіряє задача
 * verifyStore<Тип>Permissions на самій збірці.
 *
 * Три боки однієї обіцянки, і кожен може почервоніти окремо:
 *   1. заборонене Play у store не лишилось;
 *   2. документ декларацій називає рівно те, що лишилось, — не більше й не менше;
 *   3. кнопки знятих можливостей сховані прапорцем `Distribution`.
 */
class StoreManifestContractTest {

    private val tree: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    private fun parse(f: File): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(f).documentElement

    private val androidNs = "http://schemas.android.com/apk/res/android"
    private val toolsNs = "http://schemas.android.com/tools"

    private fun Element.children(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.name(): String = getAttributeNS(androidNs, "name")

    private fun sourceManifests(): List<File> =
        listOf(File(tree, "app/src/main/AndroidManifest.xml")) +
            (tree.listFiles().orEmpty())
                .filter { it.isDirectory && (it.name.startsWith("core-") || it.name.startsWith("feature-")) }
                .map { File(it, "src/main/AndroidManifest.xml") }
                .filter { it.isFile }

    private val store: Element by lazy { parse(File(tree, "app/src/store/AndroidManifest.xml")) }

    private fun removedInStore(tag: String): Set<String> = store.children(tag)
        .filter { it.getAttributeNS(toolsNs, "node") == "remove" }
        .map { it.name() }
        .toSet()

    /** Дозволи store-збірки з наших маніфестів, після зняття. */
    private fun storePermissions(): Set<String> {
        val declared = sourceManifests().flatMap { f -> parse(f).children("uses-permission").map { it.name() } }.toSet()
        return declared - removedInStore("uses-permission")
    }

    private fun storeServices(): List<Element> {
        val removed = removedInStore("service")
        return sourceManifests().flatMap { f ->
            val root = parse(f)
            val pkg = when {
                f.path.contains("/app/") -> "local.phantom.companion"
                else -> {
                    val module = f.parentFile.parentFile.parentFile.name // core-x | feature-x
                    "local.phantom.companion." + module.replace('-', '.')
                }
            }
            root.children("service").map { s ->
                val n = s.name()
                s.setAttribute("resolved", if (n.startsWith(".")) pkg + n else n)
                s
            }
        }.filter { it.getAttribute("resolved") !in removed }
    }

    @Test
    fun `заборонене Play у store не лишилось`() {
        val forbidden = setOf(
            "android.permission.QUERY_ALL_PACKAGES",
            "android.permission.REQUEST_INSTALL_PACKAGES",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.ACCESS_MOCK_LOCATION",
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.WRITE_SETTINGS",
            "android.permission.USE_EXACT_ALARM",
            "android.permission.BODY_SENSORS",
            "android.permission.FOREGROUND_SERVICE_HEALTH",
            "android.permission.READ_SMS",
            "android.permission.SEND_SMS",
            "android.permission.RECEIVE_SMS",
            "android.permission.READ_CALL_LOG",
            "android.permission.WRITE_CALL_LOG",
            "android.permission.PROCESS_OUTGOING_CALLS",
            "android.permission.READ_PHONE_STATE",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_ADVERTISE",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            "androidx.car.app.NAVIGATION_TEMPLATES",
            "androidx.car.app.ACCESS_SURFACE",
        )
        val left = storePermissions() intersect forbidden
        assertTrue("у store лишилось заборонене Play: $left", left.isEmpty())
    }

    @Test
    fun `у store немає служб спецможливостей, слухача сповіщень і перевірки дзвінків`() {
        val binds = setOf(
            "android.permission.BIND_ACCESSIBILITY_SERVICE",
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
            "android.permission.BIND_SCREENING_SERVICE",
        )
        val offenders = storeServices()
            .filter { it.getAttributeNS(androidNs, "permission") in binds }
            .map { it.getAttribute("resolved") }
        assertTrue("store оголошує $offenders — Play відхилить без декларації", offenders.isEmpty())
    }

    @Test
    fun `у store немає служб, що малюють поверх або підміняють місце`() {
        val gone = setOf(
            "local.phantom.companion.service.FalsePositionService",
            "local.phantom.companion.feature.ambient.service.WhisperLineService",
            "local.phantom.companion.feature.godmode.mission.AutopilotService",
        )
        val left = storeServices().map { it.getAttribute("resolved") }.toSet() intersect gone
        assertTrue("у store лишились $left", left.isEmpty())
    }

    /**
     * Зняття в store — лише того, що справді оголошено. Вузол `remove` на
     * службу, якої вже нема (орб пішов зі старою оболонкою), нічого не знімає,
     * але читається як «ця служба в нас є» — і тягне за собою мертві сторожі.
     */
    @Test
    fun `store знімає лише служби, що є в наших маніфестах`() {
        val declared = sourceManifests().flatMap { f ->
            val root = parse(f)
            val pkg = if (f.path.contains("/app/")) "local.phantom.companion"
            else "local.phantom.companion." + f.parentFile.parentFile.parentFile.name.replace('-', '.')
            root.children("service").map { s -> s.name().let { if (it.startsWith(".")) pkg + it else it } }
        }.toSet()
        val stale = removedInStore("service") - declared
        assertTrue("store знімає неіснуючі служби: $stale", stale.isEmpty())
    }

    /** Дозволи, названі в таблиці docs/play/PERMISSIONS.md (перша колонка в `…`). */
    private fun documented(): Set<String> {
        val doc = File(tree.parentFile, "docs/play/PERMISSIONS.md")
        assertTrue("немає ${doc.path} — декларації дозволів нічим тримати", doc.isFile)
        // Рядки з джерелом «бібліотека» додає злиття маніфестів (Play Billing
        // тощо); у наших файлах їх немає, їх звіряє задача на злитому маніфесті.
        return doc.readLines()
            .mapNotNull { line ->
                val m = Regex("""^\|\s*`([a-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)`\s*\|\s*([^|]*)\|""").find(line) ?: return@mapNotNull null
                m.groupValues[1].takeUnless { m.groupValues[2].trim().startsWith("бібліотека") }
            }
            .toSet()
    }

    @Test
    fun `документ декларацій називає рівно ті дозволи, що лишились у store`() {
        val actual = storePermissions()
        val doc = documented()
        assertTrue("дозволи store без обґрунтування в декларації: ${(actual - doc).sorted()}", (actual - doc).isEmpty())
        // Зайвий рядок — неправда в декларації так само, як і пропущений.
        assertTrue("описано дозволи, яких store не має: ${(doc - actual).sorted()}", (doc - actual).isEmpty())
    }

    @Test
    fun `кожна служба переднього плану store має обґрунтування свого типу`() {
        val doc = File(tree.parentFile, "docs/play/PERMISSIONS.md").readText()
        storeServices()
            .filter { it.getAttributeNS(androidNs, "foregroundServiceType").isNotEmpty() }
            .forEach { s ->
                val simple = s.getAttribute("resolved").substringAfterLast('.')
                assertTrue("служба $simple не описана в docs/play/PERMISSIONS.md", "`$simple`" in doc)
            }
    }

    @Test
    fun `кнопки знятих можливостей сховані прапорцем Distribution`() {
        // 04.10 (profile-hub): старий перелік важелів (SettingsRealm) розпущено,
        // і ці кнопки живуть у розділах Профілю. Саме там двох прапорців і не
        // було: «Слід поза екраном» і «Стрічка поверх екрана» стояли голі.
        val profile = File(tree, "app/src/main/java/local/phantom/companion/ui/home/ProfileSections.kt").readText()
        listOf(
            "Distribution.falsePosition" to "FalsePositionEntry()",
            "Distribution.backgroundLocation" to "BackgroundLocationEntry()",
            "Distribution.overlays" to "R.string.dev_whisper",
            "Distribution.batteryAsk" to "BatteryGrantEntry(health = health)",
        ).forEach { (flag, entry) ->
            val at = profile.indexOf(entry)
            assertTrue("немає пункту $entry", at > 0)
            val guard = profile.lastIndexOf(flag, at)
            assertTrue(
                "пункт $entry не стоїть під $flag — у store він відкриє екран, де застосунку немає",
                guard in 0 until at && at - guard < 300,
            )
        }
        // Служби спецможливостей і слухача сповіщень store не має (тест вище) —
        // і кнопки, що ведуть до їхніх системних екранів, теж питають прапорець.
        // Не `isStore`: seeker бере маніфест store, але isStore у нього false,
        // і двері в seeker вели б на системний екран без застосунку.
        val access = File(tree, "app/src/main/java/local/phantom/companion/ui/settings/AccessPane.kt").readText()
        listOf("ACTION_ACCESSIBILITY_SETTINGS", "ACTION_NOTIFICATION_LISTENER_SETTINGS").forEach { door ->
            val at = access.indexOf(door)
            assertTrue("немає дверей $door", at > 0)
            val guard = access.lastIndexOf("Distribution.systemListeners", at)
            assertTrue(
                "двері $door не стоять під Distribution.systemListeners — у store і seeker вони ведуть у порожнечу",
                guard in 0 until at && at - guard < 900,
            )
        }
        val dist = File(tree, "app/src/main/java/local/phantom/companion/Distribution.kt").readText()
        assertTrue(Regex("""val isStore: Boolean = BuildConfig\.FLAVOR == "store"""").containsMatchIn(dist))
        assertTrue(
            "служби-слухачі мусять бути лише у field, бо seeker теж несе маніфест store",
            Regex("""val systemListeners: Boolean get\(\) = isField""").containsMatchIn(dist),
        )
    }
}
