package local.phantom.companion.ui.messenger

import java.io.File
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerOutbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Три вироки з живого апарата, зміряні в джерелі й у бандлі, що їде в телефон.
 *
 * `org.json` у юніт-тесті `:app` — заглушка, тож пройти мостом по-справжньому
 * тут нема як. Але обидві вади були не в обчисленні, а в ДОГОВОРІ між склом і
 * телефоном, а договір оголошений двічі: словом мосту й розбором бандла. Тому
 * звіряються саме ці два оголошення — зелене на одному боці тут не доводить
 * нічого (див. [WebMessengerStateVocabularyTest], те саме правило).
 */
class MessengerSendTruthTest {

    /** Слово знака поразки в бандлі (feedParts.FailedMark), яке читає читалка екрана. */
    private val FAILED_WORD = "Не надіслано · Повторити"

    /**
     * Ключ каталогу, під яким це слово живе після i18n (злиття cloud/r18-en,
     * 09.10): знак кличе `t("feedParts.failedRetry")`, а слово лежить у
     * каталозі `index-*.js`. Ланцюг той самий, лише ланка «слово» тепер дві:
     * знак → ключ, ключ → слово.
     */
    private val FAILED_KEY = "feedParts.failedRetry"

    /**
     * Бандл без рядків каталогу (`"ключ.з.крапкою":"текст"`). Каталог іде в
     * бандл цілим, разом із текстами гілок, яких телефонна збірка не має, —
     * сторож шукає те, що сторінка РОБИТЬ, а не те, що лежить у словнику.
     */
    private fun withoutCatalog(glass: String): String =
        Regex(""""[\w-]+(?:\.[\w-]+)+":"(?:[^"\\]|\\.)*"""").replace(glass, "")

    /** Ключі, якими сторінка колись відповідала за помічника сама. */
    private val pageResponderKeys = listOf("store.assistantSilent")

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь дерева не знайдено") as Nothing
    }

    private fun bridgeSource(): String {
        val src = File(
            repoRoot(),
            "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt",
        )
        assertTrue("сторож осліп: немає WebMessengerBridge.kt", src.isFile)
        return src.readText()
    }

    private fun bundle(): String {
        val assets = File(repoRoot(), "app/src/main/assets/webmsgr/assets")
        val js = assets.listFiles { f: File -> f.name.startsWith("index-") && f.name.endsWith(".js") }
            ?.sortedBy { it.name }
            ?: emptyList()
        assertEquals("у бандлі має бути рівно один index-*.js: ${js.map { it.name }}", 1, js.size)
        return js.first().readText()
    }

    private fun layoutBundle(): String {
        val assets = File(repoRoot(), "app/src/main/assets/webmsgr/assets")
        val js = assets.listFiles { f: File ->
            f.name.startsWith("MessengerLayout-") && f.name.endsWith(".js")
        }?.sortedBy { it.name } ?: emptyList()
        assertEquals("у бандлі має бути рівно один MessengerLayout-*.js", 1, js.size)
        return js.first().readText()
    }

    /**
     * Лист помічнику PHANTOM іде ОДНИМИ дверима — тими ж, що й людині.
     *
     * До 24.09 скло на рід «phantom» било двічі: `/messenger/.../messages` і
     * `/chat/message`, чекаючи готову відповідь у тілі; другі двері міст
     * відмовляв (409 `already_in_log`), а сторінка малювала свої етапи
     * «Сприймаю контекст…» і «Помічник зараз не відповідає», хоча відповідав
     * застосунок. Веб 24.09 (`!__PHONE_BUILD__` у `isAiChat`) вирізав цю гілку з
     * телефонного бандла. Відповідь дає [WebMessengerBridge.append] через
     * `dispatchPhantomDialogue` — тож доки текст іде `appendMessage`, помічник
     * лист отримує.
     */
    @Test
    fun `лист помічнику йде одними дверима, і відповідає застосунок`() {
        assertEquals(emptyList<String>(), assistantSendBroken(bundle(), bridgeSource()))
    }

    private val pageResponder = listOf("Сприймаю контекст", "Помічник зараз не відповідає", "Формую відповідь")

    private fun assistantSendBroken(glass: String, bridge: String): List<String> {
        val out = mutableListOf<String>()
        // Імʼя змінної в шаблоні дає мініфікатор (`${e}` у sol-wedge, `${t}` після
        // sweep) — сторож дивиться на двері, а не на букву, яку вибрав terser.
        if (!Regex("""/messenger/conversations/\${'$'}\{[\w$]+\}/messages""").containsMatchIn(glass)) {
            out += "у бандлі нема дверей листа"
        }
        if (!Regex("""\.appendMessage\([\w$]+,\{client_id:[^{}]*kind:"text",body:[\w$]+\.trim\(\)""").containsMatchIn(glass)) {
            out += "текст із поля вводу більше не йде appendMessage"
        }
        val code = withoutCatalog(glass)
        pageResponder.filter { code.contains(it) }.forEach { out += "сторінка знову відповідає за помічника: «$it»" }
        pageResponderKeys.filter { code.contains("\"$it\"") }.forEach { out += "сторінка знову кличе $it" }
        val phantom = Regex(
            """if \(peerId == AgencySessionManager\.PHANTOM_AGENT_ID\) \{.*?\n        \}""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(bridge)?.value
        if (phantom?.contains("dispatchPhantomDialogue(") != true) out += "append() більше не будить помічника"
        return out
    }

    @Test
    fun `дзеркало дверей помічника червоніє`() {
        val glass = bundle()
        val bridge = bridgeSource()
        val append = Regex("""\.appendMessage\(([\w$]+),\{client_id:([^{}]*)kind:"text",body:""")
        val mutants = mapOf(
            "сторінка відповідає сама" to (glass + "\"Сприймаю контекст…\"" to bridge),
            "сторінка кличе текст мовчання" to (glass + "tNow(\"store.assistantSilent\")" to bridge),
            "дверей листа немає" to (Regex("""/messenger/conversations/\${'$'}\{[\w$]+\}/messages""").replace(glass, "/gone") to bridge),
            "текст не йде в міст" to (append.replace(glass) { ".noop(${it.groupValues[1]},{client_id:${it.groupValues[2]}kind:\"text\",body:" } to bridge),
            "міст не будить помічника" to (glass to bridge.replace("container.agencyManager.dispatchPhantomDialogue(", "container.agencyManager.toString(")),
        )
        mutants.forEach { (name, pair) ->
            assertTrue("мутант «$name» холостий", pair.first != glass || pair.second != bridge)
            assertTrue("мутант «$name» пройшов", assistantSendBroken(pair.first, pair.second).isNotEmpty())
        }
    }

    /**
     * ОДИН дотик — ОДИН запис. Двері, що кладуть у журнал слово людини, одні.
     *
     * До 20.09 їх було двоє: `/messenger/conversations/<id>/messages` і
     * `/chat/message`, і обидві кликали `append`. На апараті власника це давало
     * два однакові рядки праворуч з тією самою хвилиною — і вони переживали
     * вихід із розмови, бо це були два СПРАВЖНІ записи.
     */
    @Test
    fun `у журнал слово людини кладуть одні двері`() {
        val src = bridgeSource()
        val calls = Regex("""(?<![A-Za-z_])append\(""").findAll(src)
            .count() - Regex("""private fun append\(""").findAll(src).count()
        assertEquals(
            "дверей, що кладуть лист у журнал, має бути рівно одні — знайдено $calls",
            1,
            calls,
        )
    }

    /**
     * «Звірено» означає звірене число безпеки — і нічого іншого.
     *
     * Шапка розмови з локальним агентом писала позначку довіри поруч із
     * панеллю, яка в ТІЙ САМІЙ розмові казала «Ключа співрозмовника ще
     * немає». Звірки не було: ні числа, ні ключа, ні співрозмовника.
     */
    @Test
    fun `позначка звірки не зʼявляється без звірки`() {
        val src = bridgeSource()
        listOf("phantomConversation", "agencyConversation").forEach { fn ->
            val body = Regex(
                """private fun $fn\(.*?\n    \}""",
                RegexOption.DOT_MATCHES_ALL,
            ).find(src)?.value ?: fail("не знайшов $fn: сторож не читає того, що стереже") as Nothing
            assertFalse(
                "$fn стверджує звірку, якої не було",
                Regex("""put\("contact_verified",\s*true\)""").containsMatchIn(body),
            )
            assertTrue(
                "$fn мовчить про звірку — шапка не скаже нічого",
                body.contains("""put("contact_verified", VERIFIED_NEVER_HAPPENED)"""),
            )
        }
        assertFalse(
            "у мості лишилось поле звірки, поставлене літеральним true",
            Regex("""put\("contact_verified",\s*true\)""").containsMatchIn(src),
        )
        // Дзеркало тримається ЗА ІМʼЯ ПОЛЯ, а не за мініфікований вираз.
        //
        // 20.09 воно було пришпилене до `s.contactVerified===!0` — тобто до
        // однієї конкретної форми порівняння в одному конкретному чанку. Того
        // ж дня сусідній фронт прибрав позначку звірки з шапки (рішення
        // власника: безпека мовчить, поки все гаразд), вираз зник — і сторож
        // почервонів на ПРАВИЛЬНІЙ зміні, стверджуючи «дзеркало застаріло».
        //
        // Застарів сам спосіб дивитись. Мініфікація перейменовує змінні й
        // переписує порівняння на кожній збірці; що НЕ міняється — це імʼя
        // поля на дроті. Доки бандл розбирає `contact_verified`, чесність
        // мосту має споживача; коли перестане — поле стане мертвим вантажем,
        // і ось ЦЕ варто ловити.
        assertTrue(
            "бандл більше не читає contact_verified — чесність мосту нікому не потрібна",
            bundle().contains("contact_verified"),
        )
    }

    /**
     * Лист без ключа має СТАН, а не порожнечу.
     *
     * Доти міст відмовляв (409 `no_key`) і не писав нічого: слова людини жили
     * до виходу з розмови й зникали разом із нею. Тепер рядок лягає поразкою —
     * бандл малює на ній «не пішло» і кнопку повтору, яка після обміну
     * запрошеннями справді має що зробити. З 24.09 слово стоїть у mono під
     * бульбашкою, а кнопка каже «Надіслати ще раз».
     */
    @Test
    fun `лист без ключа лягає в журнал поразкою`() {
        val src = bridgeSource()
        val appendBody = Regex(
            """private fun append\(.*?\n    \}""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(src)?.value ?: fail("не знайшов append(): сторож не читає того, що стереже") as Nothing
        assertFalse(
            "відсутність ключа знову ковтає лист замість того, щоб дати йому стан",
            appendBody.contains("""fail(409, "no_key""""),
        )
        assertTrue(
            "міст більше не кладе недоставний лист у журнал",
            appendBody.contains("container.mesh.ingestUndeliverable("),
        )
        val mesh = File(
            repoRoot(),
            "app/src/main/java/local/phantom/companion/data/PeerMesh.kt",
        ).readText()
        val fn = Regex(
            """fun ingestUndeliverable\(.*?\n    \}""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(mesh)?.value ?: fail("немає ingestUndeliverable у PeerMesh") as Nothing
        assertTrue(
            "недоставний лист лягає не поразкою — бульбашка знову промовчить",
            fn.contains("PeerMessage.State.FAILED"),
        )

        // Журнал мусить лишити свідомо поставлений стан: інакше рядок став би
        // QUEUED і бульбашка обіцяла б доставку, якої нічим виконати.
        val stalled = PeerMessage(
            id = "m1",
            fromId = "me",
            toId = "peer",
            body = "привіт",
            sentAtMs = 1L,
            state = PeerMessage.State.FAILED,
        )
        val log = PeerOutbox.enqueue(emptyList(), stalled)
        assertEquals("лист не дійшов до журналу", 1, log.size)
        assertEquals(
            "журнал перетер поразку на «у черзі» — обіцянка замість стану",
            PeerMessage.State.FAILED,
            log.first().state,
        )
        val bridgeWord = Regex("""PeerMessage\.State\.FAILED -> "failed"""")
        assertTrue("міст більше не називає поразку словом «failed»", bridgeWord.containsMatchIn(src))
        assertEquals(emptyList<String>(), failureDrawnInWords(bundle(), layoutBundle()))
    }

    /**
     * Знак поразки кличе повтор і називає себе словом під ключем знака. З UI #2
     * (15.10) слово може нести причину («Немає мережі · Повторити»), тож знак
     * спершу кладе слово в змінну, а `aria-label` бере її; без причини це те
     * саме слово «Не надіслано · Повторити».
     */
    private val failedLabel = Regex(
        """(?:const|let|var)\s+([\w$]+)\s*=[^;]{0,300}?[\w$]+\("$FAILED_KEY"\)\s*;""" +
            """[^;]{0,600}?retrySend\(\w+\)\}[^;]{0,400}?"aria-label"\s*:\s*(\1)(?=[,}])""",
    )

    /**
     * Ланцюг від FAILED вузла до знака біля часу, три ланки в бандлі:
     * `delivery_state:"failed"` → статус `failed` → червоний знак, що й кличе
     * повтор, і каже «Не надіслано · Повторити» читалці екрана (28.09 власник:
     * стан — галочками й знаками, не реченням під бульбашкою). Редизайн 01.10
     * змінив лише розділювач слова (було «— повторити»); ланцюг той самий.
     * Порожній список — ланцюг цілий; інакше — назви розірваних ланок.
     */
    private fun failureDrawnInWords(index: String, layout: String): List<String> = listOfNotNull(
        "статус із delivery_state".takeUnless {
            Regex("""===\s*"failed"\s*\?\s*"failed"""").containsMatchIn(index)
        },
        "знак на статусі failed".takeUnless {
            Regex("""\.status\s*===\s*"failed"""").containsMatchIn(layout)
        },
        "знак «Не надіслано · Повторити», що кличе повтор".takeUnless { failedLabel.containsMatchIn(layout) },
        "слово «Не надіслано · Повторити» під ключем знака".takeUnless {
            index.contains("\"$FAILED_KEY\":\"$FAILED_WORD\"")
        },
    )

    @Test
    fun `дзеркало поразки червоніє, коли слово зникає`() {
        val index = bundle()
        val layout = layoutBundle()
        val mutants = mapOf(
            "поразка без слова" to (index to failedLabel.replace(layout) { it.value.substring(0, it.groups[2]!!.range.first - it.range.first) + "null" }),
            "поразка словом успіху" to (index.replace("\"$FAILED_KEY\":\"$FAILED_WORD\"", "\"$FAILED_KEY\":\"Доставлено\"") to layout),
            "без повтору" to (index to layout.replace(Regex("""retrySend\((\w+)\)\}"""), "noop(\$1)}")),
            "failed стає sent" to
                (Regex("""===\s*"failed"\s*\?\s*"failed"""").replace(index, "===\"failed\"?\"sent\"") to layout),
        )
        mutants.forEach { (name, pair) ->
            assertTrue(
                "мутант «$name» не змінив копії бандла — мутація холоста",
                pair.first != index || pair.second != layout,
            )
            assertTrue(
                "мутант «$name» пройшов — дзеркало не вміє почервоніти",
                failureDrawnInWords(pair.first, pair.second).isNotEmpty(),
            )
        }
    }
}
