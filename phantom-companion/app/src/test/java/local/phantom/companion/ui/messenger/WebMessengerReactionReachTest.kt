package local.phantom.companion.ui.messenger

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Чи смайл справді ДОСЯЖНИЙ, а не просто намальований.
 *
 * Те, що кадр позначки перетинає дріт і стає числом під рядком на ДРУГОМУ
 * апараті, доводиться в core-net справжніми сокетами (`PeerMarkReachTest`). Тут
 * доводиться інше — і саме воно було зламане: дорога від дотику у вкладці до
 * мешу, і назад у стрічку.
 *
 * Обидві половини були мертві незалежно:
 *  - НАЗОВНІ: `messengerStore.toggleReaction` кликав
 *    `messengerNetworkEngine.sendReaction`, а той має дві гілки, і в телефонній
 *    збірці не веде нікуди ані одна — `p2p` кидає кадр у браузерну WebRTC-сітку,
 *    якої на телефоні немає, друга шле в `this.ws`, сокет до чужого брокера. У
 *    мосту слово «reaction» не траплялось ЖОДНОГО разу.
 *  - ДОДОМУ: навіть власний смайл жив до першого ж `__phantomMeshChanged` —
 *    `loadMessagesForChat` заступає `c.messages` цілком тим, що дав міст, а
 *    поля `reactions` у нього не було.
 *
 * GATE LAW: читає файли як текст. Входи задачі оголошені.
 */
class WebMessengerReactionReachTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь дерева не знайдено") as Nothing
    }

    private fun source(rel: String): String {
        val f = File(repoRoot(), rel)
        if (!f.isFile) fail("немає файла $rel")
        return f.readText()
    }

    /** Джерело без рядків-коментарів: сторож не має червоніти від KDoc. */
    private fun codeOf(text: String): String = text.lineSequence()
        .filterNot { line ->
            val t = line.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        .joinToString("\n")

    private val bridge by lazy {
        source("app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt")
    }
    private val mark by lazy {
        source("core-net/src/main/java/local/phantom/companion/core/net/peer/PeerMark.kt")
    }

    /** Дотик має куди поїхати: у мосту є ручка, і вона кладе кадр у сітку. */
    @Test
    fun `смайл має дорогу в сітку`() {
        val code = codeOf(bridge)
        assertTrue(
            "у мосту немає шляху для смайла — веб не має куди постукати",
            code.contains("seg[5] == \"reactions\""),
        )
        assertTrue(
            "ручка смайла не кличе складача кадру",
            code.contains("PeerMark.encode(PeerMark.Mark(PeerMark.What.REACTION"),
        )
        assertTrue(
            "кадр смайла нікуди не їде — складений і викинутий",
            code.contains("container.mesh.send(peerId, frame)"),
        )
    }

    /**
     * Напрям дотику вирішує ТОЙ САМИЙ згортач, яким малюється результат.
     *
     * Друге, простіше правило — «спитай веб, що в нього намальовано» —
     * розійшлося б із першим рівно тоді, коли поле застаріло на один кадр: дотик
     * знімав би те, чого вже немає, і смайл застрягав би увімкненим.
     */
    @Test
    fun `поставити чи зняти вирішує журнал, а не вкладка`() {
        val code = codeOf(bridge)
        assertTrue(
            "міст не питає журнал, чи смайл уже стоїть",
            code.contains("PeerMark.holds(log, PeerMark.What.REACTION"),
        )
        assertFalse(
            "напрям дотику приїхав із вкладки — вона не джерело правди про журнал",
            code.contains("optString(\"action\")") || code.contains("optBoolean(\"on\""),
        )
    }

    /**
     * Смайл ДОЇЖДЖАЄ ДОДОМУ — у стрічку, яку міст віддає вебові.
     *
     * Без цього поля половина роботи мовчки гинула б у власному апараті: кадр
     * їде, приїжджає, лягає в журнал — і не малюється, бо `loadMessagesForChat`
     * заступає намальоване порожнечею.
     */
    @Test
    fun `згорнутий смайл їде в стрічку разом із рядком`() {
        val code = codeOf(bridge)
        assertTrue(
            "у рядку немає поля зі смайлами — веб не має звідки їх узяти",
            code.contains("\"reactions\""),
        )
        assertTrue(
            "смайли не згортаються з журналу",
            code.contains("PeerMark.foldAll(container.mesh.outbox.value, PeerMark.What.REACTION)"),
        )
    }

    /**
     * Згортається ПОВНИЙ журнал, а не стрічка.
     *
     * Позначки в стрічці немає за побудовою ([PeerFrames.Kind.REACTION] несе
     * `reachesConversation=false`), тож фолд по видимих рядках завжди давав би
     * порожньо — мовчазна поразка з боку того, хто нібито все полагодив.
     */
    @Test
    fun `смайли згортаються з журналу, а не зі стрічки`() {
        val code = codeOf(bridge)
        assertFalse(
            "смайли згортаються зі стрічки, у якій їх немає за побудовою",
            code.contains("PeerMark.foldAll(lines") ||
                code.contains("PeerMark.foldAll(WebMessengerKinds.feed"),
        )
    }

    /**
     * СМАЙЛ КАЖЕ ПРАВДУ ПРО ДОРОГУ. Власник: «начебто поставилось».
     *
     * [PeerMark.foldAll] навмисно сліпий до `PeerMessage.State` — він
     * відповідає на питання «хто що тримає», і відповідь мусить бути
     * однаковою на обох апаратах. Наслідок: позначка в черзі згорталась у
     * рядок точно так само, як доставлена, і веб не мав ЧИМ їх розрізнити.
     *
     * Тому міст кладе в кожен смайл `mine` — стан того кадру, яким я це
     * поставив, — і питає його `PeerMark.latestCast`, а не другим згортачем.
     */
    @Test
    fun `смайл несе дорогу власної позначки`() {
        val code = codeOf(bridge)
        assertTrue(
            "смайл не несе поля `mine` — черга й доставлене виглядають однаково",
            code.contains("\"mine\""),
        )
        assertTrue(
            "дорога питається не в того, хто її знає",
            code.contains(".latestCast("),
        )
        assertTrue(
            "стан кадру не перекладається тими словами, які знає скло",
            code.contains("?.let { state(it.state) }"),
        )
    }

    /**
     * Слова дороги — ті САМІ, що для листа.
     *
     * Словник стану закритий ([WebMessengerStateVocabularyTest]): гілка
     * «інакше» в бандлі тихо каже `sent`, тож нове слово стало б не «не
     * знаю», а «надіслано». `mine` тому й читає `state()`, а не власний
     * `when`: друга таблиця слів розійшлася б із першою на першому ж
     * уточненні.
     */
    @Test
    fun `дорога позначки не заводить власних слів`() {
        val block = codeOf(bridge)
            .substringAfter("private fun reactionsOf(")
            .substringBefore("private fun message(")
        val quoted = Regex("\"([a-z_]+)\"").findAll(block).map { it.groupValues[1] }.toSet()
        // У блоці мають бути лише НАЗВИ полів. Жодного слова стану власного
        // карбування: вони приходять із `state()`.
        assertEquals(
            "reactionsOf карбує власні слова стану замість state()",
            setOf("emoji", "count", "users", "names", "mine"),
            quoted,
        )
    }

    /**
     * ХТО поставив — іменами, бо `users` це ключі вузлів.
     *
     * До 18.09 веб малював людині `r.users.join(', ')`, тобто на питання
     * «хто?» екран відповідав `operator_me, 8f3ac1d0`. Імена знає лише цей
     * бік, тож він їх і називає — і рівно в тому ж порядку, інакше підпис
     * зʼїхав би на одного автора.
     */
    @Test
    fun `смайл несе імена, а не лише ключі`() {
        val code = codeOf(bridge)
        assertTrue("смайл не несе імен — вебові нічим підписати", code.contains("\"names\""))
        assertTrue(
            "імена складаються не з того ж списку, що ключі — підпис зʼїде на одного",
            code.contains("users.forEach {") && code.contains("else nameOf(it)"),
        )
    }

    /**
     * Згортач лишається ОДИН.
     *
     * [PeerMark.fold] тепер питає [PeerMark.foldAll] і бере свій рядок. Друга,
     * «швидка» копія правила розійшлася б із першою на першому ж уточненні —
     * а уточнення там уже є (`single` для опитування з одним вибором).
     */
    @Test
    fun `швидкий згортач не став другим правилом`() {
        assertTrue(
            "fold завів власне тіло — два правила про той самий журнал",
            codeOf(mark).contains("): Map<String, List<String>> = foldAll(log, what, single)[targetId].orEmpty()"),
        )
    }
}
