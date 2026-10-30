package local.phantom.companion.feature.stream.ui.map.country

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ATLAS-3 РАТЧЕТ — Україна живе в паку, а не в застосунку.
 *
 * До цієї хвилі країна була вшита в шість місць: фолбек Києва в дванадцяти
 * виразах, три адреси стрічок тривог, дві назви асетів, назва окупованої
 * області рядком у предикаті. Кожне з них працювало рівно доти, доки телефон
 * не виїжджав за кордон — і жодне про це не казало.
 *
 * Правило: жодного клієнта країни, адреси її стрічки чи імені її асета поза
 * каталогом `country/`. Allow-list — це каталог, а не файл: другий пак не має
 * подовжувати цей список.
 *
 * ЗАКОН ВОРІТ: таке читання невидиме для up-to-date перевірки Gradle —
 * запускати з `--rerun-tasks`, як це робить `scripts/verify.sh`.
 */
class CountryPackFirewallTest {

    /** Конструктори клієнтів країни + адреси й асети, якими вони живуть. */
    private val packOwned = listOf(
        "AirAlertClient(",
        "SirenClient(",
        "AlertsInUaClient(",
        "ubilling.net.ua",
        "siren.pp.ua",
        "api.alerts.in.ua",
        "track.ua-gis.com",
        "oblasts.geojson",
        "region_index.json",
        "country/ua/poi.json",
        "country/ua/metro.json",
        "country/ua/surface.json",
    )

    /**
     * Імена, яких не має бути НІДЕ, зокрема й у паку: мертві константи та
     * старі імена київських асетів, що переїхали в простір імен пака. Поки
     * `kyiv_poi.json` живе бодай в одному рядку, застосунок носить Київ у
     * кишені по всій планеті.
     */
    private val dead = listOf(
        "KYIV_LAT",
        "KYIV_LON",
        "kyiv_poi.json",
        "kyiv_metro.json",
        "kyiv_surface.json",
    )

    private val forbidden = packOwned + dead

    private val roots = listOf(
        "app/src/main",
        "core-data/src/main",
        "core-geo/src/main",
        "core-nav/src/main",
        "feature-stream/src/main",
    )

    @Test
    fun `країна живе тільки в паку`() {
        val offenders = mutableListOf<String>()
        sourceFiles().forEach { file ->
            if (inPackModule(file)) return@forEach
            codeLines(file).forEach { (lineNo, line) ->
                // Оголошення класу — не конструювання: клієнти й далі живуть
                // у своїх файлах, забороняється лише будувати їх поза паком.
                if ("class " in line) return@forEach
                forbidden.firstOrNull { it in line }?.let { token ->
                    offenders += "${file.name}:$lineNo — $token"
                }
            }
        }
        assertEquals(
            "Україна вшита поза паком:\n" + offenders.joinToString("\n") +
                "\n\nСпитай реєстр: CountryPackRegistry.current()/alarmFeed(…), " +
                "countryHome(). Друга країна має додаватись даними, не клієнтом.",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `каталог пака справді тримає те, що боронить це правило`() {
        val packFiles = sourceFiles().filter { inPackModule(it) }
        assertTrue("каталогів country/ не знайдено — правило вже нічого не боронить", packFiles.isNotEmpty())
        val covered = packFiles.flatMap { file -> codeLines(file).map { it.second } }
        val orphans = packOwned.filter { token -> covered.none { token in it } }
        assertEquals("токени, яких у паку вже нема — приберіть їх зі списку", emptyList<String>(), orphans)
    }

    private fun inPackModule(file: File): Boolean =
        "/country/" in file.path.replace(File.separatorChar, '/')

    /** Рядки коду: без коментарів. Літерали НЕ гасяться — адреси тут і є ціль. */
    private fun codeLines(file: File): List<Pair<Int, String>> {
        var inBlockComment = false
        return file.readLines().mapIndexedNotNull { idx, raw ->
            val trimmed = raw.trimStart()
            when {
                inBlockComment -> {
                    if ("*/" in raw) inBlockComment = false
                    null
                }
                trimmed.startsWith("/*") -> {
                    if ("*/" !in raw) inBlockComment = true
                    null
                }
                trimmed.startsWith("//") || trimmed.startsWith("*") -> null
                else -> (idx + 1) to stripLineComment(raw)
            }
        }
    }

    /**
     * Пастка, на якій це правило вже один раз стало зеленим ні про що:
     * звичайне `substringBefore("//")` ріже рядок по «https://» і забирає з
     * ним увесь хост — сканер бачив `"https:` і чесно нічого не знаходив.
     * Тому косі, перед якими стоїть двокрапка, коментарем не вважаються.
     */
    private fun stripLineComment(raw: String): String {
        var i = raw.indexOf("//")
        while (i > 0 && raw[i - 1] == ':') i = raw.indexOf("//", i + 2)
        return if (i >= 0) raw.substring(0, i) else raw
    }

    private fun sourceFiles(): List<File> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val root = dir ?: fail("корінь репозиторію не знайдено") as Nothing
        return roots.flatMap { rel ->
            val d = File(root, rel)
            if (!d.isDirectory) emptyList()
            else d.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
    }
}
