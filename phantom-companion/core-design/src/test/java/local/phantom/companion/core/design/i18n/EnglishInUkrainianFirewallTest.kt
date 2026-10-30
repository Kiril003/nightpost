package local.phantom.companion.core.design.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * A Ukrainian sentence with an English word dropped into it reads as an
 * unfinished translation, and the operator cannot tell whether the word is a
 * brand, a button elsewhere in the app, or something the developer forgot.
 *
 * On 2026-07-28 the vault had five names: «сховище» in the section tray,
 * «Vault» in the island, «VAULT · ПРИМАРА» as its own title, lowercase
 * «vault» inside prose, and «GHOST» when locked. Nothing told the operator
 * those were one place.
 *
 * [PROPER] are words that stay English because they name something outside
 * this product — a company, a protocol, a model. Everything else has a
 * Ukrainian word, and the product is Ukrainian.
 *
 * GATE LAW (shared with the other firewalls): this reads other modules'
 * resources as raw file I/O, invisible to Gradle's up-to-date check. Run with
 * `--rerun-tasks` at every gate.
 */
class EnglishInUkrainianFirewallTest {

    private val modules = listOf(
        "app", "core-design", "core-voice",
        "feature-stream", "feature-godmode", "feature-ambient",
        "feature-onboarding", "feature-precision", "feature-car",
    )

    private val proper = setOf(
        // Імʼя продукту латиницею на всіх ринках і не відмінюється
        // (docs/brand/BRAND.md §1). Старого «PHANTOM» тут більше немає:
        // український рядок із ним — недороблене перейменування, і
        // BrandNameFirewallTest ловить його окремо.
        "Nightpost", "Gemini", "Solana", "Seeker", "USDC", "Radxa",
        // Пошукові слова гаманця: людина шукає їх саме так, малими літерами.
        "solana", "seeker", "usdc", "Google", "Android", "Whisper",
        // «Google Play» — назва магазину, у якому людина платить; кнопку
        // «Купити в Google Play» вона шукає саме так.
        "Play",
        // «Solana dApp Store» — назва магазину застосунків на Seeker, через
        // який приходять оновлення (`update_via_dapp_store`). Той самий рід, що
        // «Google Play»: власна назва чужого магазину, і на телефоні людина
        // знаходить його саме під цим написанням.
        "dApp", "Store",
        "OpenStreetMap", "MapLibre", "ODbL", "Supertonic", "Qwen", "Gemma",
        "ONNX", "HuggingFace", "OpenAI", "JSON", "GPS", "GNSS", "USB",
        "Bluetooth", "MediaPipe", "Room", "SQLite", "TTS", "OSM", "OLED",
        // «Android System WebView» — назва застосунку, який людина мусить
        // знайти в налаштуваннях телефона. Перекласти її означало б зробити
        // пораду непридатною: у списку застосунків вона стоїть саме так.
        "System", "WebView",
        // Third-party services and formats the operator will meet by name.
        "OpenFreeMap", "MapTiler", "Stadia", "Photon", "Nominatim", "OSRM",
        "Valhalla", "Vosk", "Moonshine", "YAMNet", "MiniLM", "Alidade", "Samsung",
        // Виробники оболонок, які самі притримують сповіщення, і назви мов —
        // той самий рід, що «Samsung» вище й «KOTLIN»/«PYTHON» нижче, лише
        // в тому написанні, яким їх пише світ.
        "Xiaomi", "Huawei", "Kotlin", "Python", "React",
        // Схема адреси, яку власник вводить у поле дослівно. «https» уже тут.
        "http",
        "Telegram", "Viber", "XLSX", "HTTP", "html", "podcast", "epoch",
        // Контейнер тайлів, яким названо РІД пакета на екрані завантажень.
        // Той самий рід, що XLSX і ONNX вище: формат, а не наше слово.
        "PMTiles", "PMTILES",
        // 18.09: роди картинки, які приймає аватар профілю
        // (`msgr_profile_avatar_kind`). Четвертий запис того самого роду, що
        // XLSX, ONNX і PMTiles вище, і поставлений сюди свідомо, а не щоб
        // погасити червоне — ось чому саме сюди, а не переписуванням рядка:
        //
        //  • Українського слова для них немає: WebP — формат Google, JPEG —
        //    назва комітету, що його видав. Обидва «genuinely name something
        //    outside this product», як і просить повідомлення цього тесту.
        //  • Переписати на розширення («.webp або .jpg») було б ТОЧНІШЕ повз
        //    сторожа (крапка спереду знімає збіг) і МЕНШ правдиво по суті:
        //    `PeerProfile.imageKind` впізнає рід за МАГІЧНИМИ БАЙТАМИ
        //    (FF D8 FF та RIFF….WEBP), а не за іменем файла. Файл із іменем
        //    .png і байтами JPEG приймається. Тобто правдиве твердження тут —
        //    саме про формат, і назва формату є єдиним чесним словом.
        "WebP", "JPEG",
        "contributors", "copyright", "Keys", "Authentication", "Property",
        "Smooth", "Night", "self", "hosted", "tier",
        // Іншомовні вуличні префікси в регексі нормалізації пошуку (не UI-текст).
        "ulica",
        // Identifiers that appear verbatim in developer-facing diagnostics.
        "widget", "payload", "nodes", "proof", "expired", "handoff", "TODO",
        "haptic", "place", "tool", "agent", "Agent", "Vision", "baseline",
        "FakeSttEngine", "ForesightEngine", "WeatherTool", "OpenMeteo",
        "agentSupervisor", "parallelAgentRunner", "appContextProvider",
        "locationProvider", "Search", "Date", "clamp", "free", "dark",
        "local", "roasters", "code", "password", "verification",
        "auth", "otp", "pin",
        // Тексти check() в OggOpusWriter: власні імена методів того ж класу
        // («writeHeaders()», «finish()») і терміни RFC 7845 — «mapping family»
        // та «granule». Це не копія для власника, а діагностика для того, хто
        // читатиме її поруч зі специфікацією.
        "writeHeaders", "finish", "mapping", "family", "granule",
        // Prompt-protocol markers, matched verbatim by the leak firewall.
        "TOOLS",
        // StreamToolRunner's tool-result vocabulary. These live in
        // `ToolBatchTurns.Answered.output`, which is fed back into the model's
        // turn — the model reads the code, the operator reads the Ukrainian
        // clause after the dash. Siblings `not_run`/`unknown_tool`/`bad_args`/
        // `no_context` are already invisible to the sweep for carrying an
        // underscore; these two are the same vocabulary without one.
        "ERROR", "refused",
        "Radxa", "Tool", "toolId", "Live", "Dialog",
        "Interactive", "Cards", "Done", "Mesh", "Relay", "Action", "Studio",
        "Active", "Router", "Node", "Meet", "KOTLIN", "PYTHON", "TruCam",
        "Park", "Ride", "Smart", "Card", "Starlink", "Power", "Direct",
        "Compose", "WebRTC", "Cafe", "Coffee", "Driving",
        "false", "true", "latency", "Jetpack", "gapKm", "https", "ukrainealarm",
        "null", "Kyiv", "power", "message", "maxSteps", "budgetMs", "appLabel",
        "packageName", "host",
    )

    private val cyrillic = Regex("[а-яіїєґА-ЯІЇЄҐ]")
    private val latinWord = Regex("""(?<![\w./@\-])[A-Za-z]{4,}(?![\w./@\-])""")
    private val stringTag = Regex("""<string name="([^"]+)">([^<]*)</string>""")
    private val kotlinLiteral = Regex("\"([^\"\\n]{3,200})\"")

    /**
     * ПЕРЕДУМОВА, а не дрібниця: `.filter { it.isDirectory }` мовчки викидав
     * теку, якої немає, і набір худнув без жодного слова — з пʼяти модулів
     * лишалось два, а сторож і далі казав «порушників немає». Перевіряти треба
     * не «список не порожній», а що **жоден шлях не зник**: інакше зникнення
     * трьох із пʼяти виглядає так само, як чистий код.
     */
    private fun requireScanned(dir: File) {
        // Шлях від кореня сховища, а не абсолютний: повідомлення читають о третій
        // ночі й ідуть за ним у дерево, а `/home/…/phantom-companion-<чиясь-гілка>/`
        // на початку — це шум, який ще й видає чуже робоче дерево.
        val shown = dir.relativeTo(repoRoot()).path
        if (!dir.isDirectory) fail("сторож дивиться в порожнє: не існує $shown")
        else if (dir.walkTopDown().none { it.isFile }) {
            fail("тека є, файлів немає: $shown")
        }
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    /**
     * Апостроф в українському слові — завжди ʼ (U+02BC), правило власника.
     * Шрифт Manrope на телефоні малює ʼ своїм апострофом, тож глиф один, а
     * знак — літера, а не розділовий: слово «пам\u02BCять» не рветься на два
     * ні в пошуку, ні при перенесенні. ASCII ' і типографський ’ між
     * українськими літерами — червоне.
     *
     * Дивимось у ВСІ модулі з `src/main`, не лише UI: рядки ядра (core-truth,
     * core-ai, core-data, core-symbiote…) теж доходять до людини — порадою,
     * відповіддю інструмента, підписом на мапі. Згортання для пошуку
     * (`normalize`/`fold`) теж зводить усі апострофи саме до ʼ, тож ключі й
     * введене людиною сходяться в одному знаку.
     */
    @Test
    fun `the apostrophe inside a Ukrainian word is always the modifier letter ʼ`() {
        val root = repoRoot()
        val wrong = Regex("[а-яіїєґА-ЯІЇЄҐ](?:\\\\'|'|’)[а-яіїєґА-ЯІЇЄҐ]")
        val scanned = root.listFiles().orEmpty()
            .map { File(it, "src/main") }
            .filter { it.isDirectory }
        if (scanned.size < 20) fail("сторож бачить лише ${scanned.size} модулів із src/main — корінь не той")
        val offenders = scanned.flatMap { dir ->
            dir.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || (it.extension == "xml" && it.path.contains("/res/values-uk"))) }
                .flatMap { f ->
                    f.readText().lineSequence().withIndex()
                        .filter { (_, line) -> wrong.containsMatchIn(line) }
                        .map { (i, line) -> "${f.relativeTo(root).path}:${i + 1}  ${line.trim().take(90)}" }
                }
        }

        // Повідомлення несе ГОТОВУ команду, а не список для ручного пошуку:
        // ASCII-апостроф дає будь-яка розкладка, і ганятися за ним руками
        // дорожче, ніж один раз дати спосіб.
        assertEquals(
            "a Ukrainian word uses ' or ’ — the product writes ʼ (U+02BC).\n" +
                "Полагодити все одразу, з кореня phantom-companion:\n" +
                "    grep -rlZ -P \"[а-яіїєґА-ЯІЇЄҐ](\\\\\\\\?'|’)[а-яіїєґА-ЯІЇЄҐ]\" \\\n" +
                "      --include='*.kt' --include='*.xml' */src/main \\\n" +
                "      | xargs -0 -r perl -CSD -i -pe \"s/(\\p{Cyrillic})(?:\\\\\\\\?'|\\x{2019})(?=\\p{Cyrillic})/\\$1\\x{02BC}/g\"\n" +
                offenders.joinToString("\n") { "    $it" },
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * `$foo`, `${bar()}` and `{profile}` are code, not words the operator
     * reads. The last is a URL template token they must type verbatim —
     * brace-delimited by convention, so it is skipped structurally rather
     * than being whitelisted word by word.
     */
    private val template = Regex("""\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*|\{[^}\s]*\}""")

    private fun stray(value: String): List<String> {
        if (!cyrillic.containsMatchIn(value)) return emptyList()
        // Уламок чужого виразу, а не рядок. Літерал із вкладеними лапками —
        // «...${"%.1f".format(zoom)}...» — рве наївний сканер на лапці всередині
        // шаблону, і хвіст «.format(zoom)}, фіч ...» приїжджає сюди вже без
        // відкритої дужки, тож [template] не має що вирізати. Закривна дужка
        // раніше за відкривну означає рівно це.
        val close = value.indexOf('}')
        val open = value.indexOf('{')
        val body = if (close >= 0 && (open < 0 || close < open)) value.substring(close + 1) else value
        val prose = template.replace(body, " ")
        return latinWord.findAll(prose).map { it.value }.filter { it !in proper }.toList()
    }

    @Test
    fun `no Ukrainian string carries a stray English word`() {
        val root = repoRoot()
        val fromXml = modules.flatMap { m ->
            listOf("values-uk/strings.xml", "values/strings_air_alert.xml").map { File(root, "$m/src/main/res/$it") }
        }.filter { it.isFile }.flatMap { f ->
            stringTag.findAll(f.readText()).mapNotNull { m ->
                val words = stray(m.groupValues[2])
                if (words.isEmpty()) null else "strings.xml:${m.groupValues[1]}  «${m.groupValues[2]}»  ← ${words.joinToString()}"
            }
        }
        // The permission rows in settings kept «Accessibility Service» and
        // «Battery Exemption» in Kotlin, where the XML-only sweep never looked.
        val fromKotlin = modules.map { File(root, "$it/src/main") }
            .onEach { requireScanned(it) }
            .flatMap { dir ->
                dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
                    kotlinLiteral.findAll(f.readText()).mapNotNull { m ->
                        val words = stray(m.groupValues[1])
                        if (words.isEmpty()) null else "${f.name}  «${m.groupValues[1].take(64)}»  ← ${words.joinToString()}"
                    }
                }
            }
        val offenders = fromXml + fromKotlin

        assertEquals(
            "a Ukrainian string carries an English word:\n" +
                offenders.joinToString("\n") { "    $it" } +
                "\n\nUse the Ukrainian word, or add it to `proper` if it genuinely " +
                "names something outside this product.",
            emptyList<String>(),
            offenders,
        )
    }
}
