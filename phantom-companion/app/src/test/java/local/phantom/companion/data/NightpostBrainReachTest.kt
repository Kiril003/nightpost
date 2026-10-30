package local.phantom.companion.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сервер Nightpost — розум для тих, у кого нема ні ключа, ні ПК. Механізм
 * доводить NightpostBrainRouteTest у :core-ai; тут — що контейнер справді
 * ставить його в ланцюг, у тому місці й під тим самим вибором людини.
 * Читає джерела: Android-контексту в JVM-тестах :app немає.
 */
class NightpostBrainReachTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    private fun src(path: String): String {
        val f = File(repoRoot(), "app/src/main/java/local/phantom/companion/$path")
        assertTrue("$path не знайдено — сторож нічого не боронить", f.isFile)
        return f.readText()
    }

    @Test
    fun `сервер стоїть після ПК і власного ключа, перед правилами`() {
        val container = src("data/AppContainer.kt")
        assertTrue(
            "ланцюг розумів не містить сервера на четвертому місці",
            Regex("""BrainChain\(\s*listOf\(pcBrainRoute, cloudUnlessLocalOnly, nightpostBrain\)""").containsMatchIn(container),
        )
        assertTrue(container.contains("nightpostBrainRoute(applicationContext, brainPrefs, identity, ownKeyDead = { cloudUnlessLocalOnly.dead() }) { aiKeyStore.geminiKey.value }"))
        assertTrue("власний ключ без сторожа: мертвий ключ глушить помічника", container.contains("core.ai.route.OwnKeyGuard("))
        assertFalse(
            "ПК, який людина спарила сама, віддає свої ходи серверу Nightpost",
            container.contains("nightpostBrain.availableRoutes()"),
        )
    }

    @Test
    fun `голос — мертвий власний ключ віддає токен серверу`() {
        val live = src("self/BrainLiveToken.kt")
        assertTrue("токен сервера закритий, поки мертвий ключ лежить у сховищі", live.contains("c.ownKeyDead(),"))
        val call = src("self/PhantomCall.kt")
        assertTrue("відмова ключа в дзвінку не позначає ключ мертвим", call.contains("c.noteOwnKeyRefusal(code, text)"))
        assertTrue("мертвий ключ знову питають у кожному дзвінку", call.contains("takeUnless { c.ownKeyDead() }"))
    }

    @Test
    fun `адреса — сервер ліцензій, «лише на пристрої» його вимикає, номер установки не з особи`() {
        val wiring = src("data/NightpostBrain.kt")
        assertTrue(wiring.contains("BuildConfig.LICENSE_BASE_URL"))
        assertTrue(
            "«лише на пристрої», живий власний ключ і мертвий ключ — одне правило",
            wiring.contains("NightpostBrainRoute.mayAsk(prefs.routing.value, ownKey(), ownKeyDead())"),
        )
        assertTrue("номер установки не в звичайних SharedPreferences", wiring.contains("getSharedPreferences("))
        val install = wiring.substringAfter("keepInstallId(").substringBefore("\n    }")
        assertTrue(install.contains("install_id"))
        listOf("Encrypted", "identity", "ANDROID_ID", "Build.SERIAL", "peerId", "publicKey").forEach {
            assertFalse("номер установки звʼязаний з «$it»", install.contains(it))
        }
    }

    @Test
    fun `рядок «Розум» у Профілі й порада про ключ не кажуть «нема чим думати», коли відповідає сервер`() {
        val section = src("ui/home/ProfileSections.kt")
        assertTrue(
            "Профіль питає в сервера, що він сам про себе каже, а не здогадується",
            section.contains("container.nightpostBrain.standing()"),
        )
        assertTrue(
            "Профіль збігав би по статус і тим, у кого є ПК чи свій ключ",
            section.contains("if (state.paired != null || !key.isNullOrBlank()) null"),
        )
        assertTrue("рядок «Розум» іде через mindValue", section.contains("mindValue(state.paired != null, !key.isNullOrBlank(), serverMind)"))
        val value = src("ui/home/ProfileSettings.kt")
        listOf(
            "server == NightpostBrainRoute.Standing.UP -> R.string.profile_value_server",
            "server == NightpostBrainRoute.Standing.SPENT -> R.string.profile_value_server_spent",
            "server == NightpostBrainRoute.Standing.UNKNOWN -> R.string.profile_value_server_quiet",
        ).forEach {
            assertTrue("гілки немає: $it — людина читала б «нема чим думати»", value.contains(it))
        }

        val res = File(repoRoot(), "app/src/main/res")
        listOf("values-uk", "values").forEach { dir ->
            val mind = File(res, "$dir/strings_home.xml").readText()
            val hub = File(res, "$dir/strings_look_profile_hub.xml").readText()
            listOf("phantom_mind_server", "phantom_mind_server_spent", "phantom_mind_server_offline").forEach {
                assertTrue("$dir: немає $it", mind.contains("name=\"$it\""))
            }
            assertTrue(
                "$dir: добовий ліміт без окремих слів — людина читала б «зачекай»",
                File(res, "$dir/strings.xml").readText().contains("name=\"ai_fail_daily_limit\""),
            )
            val noKey = Regex("""name="look_brain_no_key">([^<]*)<""").find(hub)?.groupValues?.get(1).orEmpty()
            assertTrue("$dir: порада про ключ мовчить про сервер: $noKey", noKey.contains("Nightpost"))
            assertFalse("$dir: порада й далі каже «хмара недоступна»: $noKey", noKey.contains("недоступна") || noKey.contains("unavailable"))
        }
    }

    @Test
    fun `поразка відповіді стає видимим рядком у розмові, а не лише станом, якого веб не читає`() {
        val agency = src("ui/messenger/AgencySessionManager.kt")
        val fail = agency.substringAfter("private fun failLive(").substringBefore("private fun endLive(")
        assertTrue("failLive не кладе поразку в розмову", fail.contains("container.mesh.ingestDirect(") && fail.contains("fromId = PHANTOM_AGENT_ID"))
        // Поразки першого проходу, повторного й мовчання ToolRounds зводить в одне Failed (ToolRoundsTest).
        val dispatch = agency.substringAfter("fun dispatchPhantomDialogue(").substringBefore("\n    private suspend fun stream(")
        val failed = dispatch.substringAfter("is ToolRounds.Outcome.Failed ->").substringBefore("is ToolRounds.Outcome.Answer ->")
        val caught = dispatch.substringAfter("} catch (t: Throwable) {")
        assertTrue("не кожна поразка вільного питання доходить до розмови", "failAsk(messageId, errText, meId, questionId)" in failed && "failAsk(messageId, errText, meId, questionId)" in caught)
        // Питання з листом у журналі несе поразку само (знак «не пішло» + причина); без листа — рядок помічника.
        val ask = agency.substringAfter("private fun failAsk(").substringBefore("fun retryAsk(")
        assertTrue("питання без відповіді не стає FAILED", "markDirect(questionId, PeerMessage.State.FAILED)" in ask)
        assertTrue("питання без листа лишилось би без видимої поразки", "failLive(messageId, reason, meId)" in ask)
        val fromMap = src("ui/home/HomeScreen.kt").substringAfter("internal fun askPhantom(").substringBefore("\n}\n")
        assertTrue("питання з мапи має лист, але поразка лягла б окремим листом помічника", "dispatchPhantomDialogue(prompt, me, questionId = asked.id)" in fromMap)
        val notices = src("ui/messenger/AssistantNotices.kt")
        assertTrue("рядок поразки став би останнім словом у списку розмов", notices.contains("R.string.ai_fail_no_network") && notices.contains("R.string.ai_fail_silent"))
    }

    @Test
    fun `помічник відповідає мовою людини в розмові`() {
        val agency = src("ui/messenger/AgencySessionManager.kt")
        assertTrue(agency.substringAfter("DialogueTurns.messengerIntent(").substringBefore("\n                )").contains("languageRuleFor(prompt, aiHistory)"))
        assertFalse("груповий шлях помічника повернувся — група не говорить із сервером", agency.contains("languageRule = languageRuleFor(question, history)"))
        assertTrue(agency.contains("ReplyLanguage.locale("))
        assertFalse(
            "правило мови знову дописано в кінець системного припису — по дорозі після нього лягає памʼять і правила інструментів",
            Regex("""systemPrompt\s*=[^\n]*\n?[^\n]*SystemPrompts\.languageRule""").containsMatchIn(agency),
        )
        assertTrue(
            "добовий ліміт сервера показано як «зачекай»",
            agency.contains("FailureWords.of(") &&
                src("ui/messenger/FailureWords.kt").let { it.contains("NightpostBrainRoute.isSpent(cause.body)") && it.contains("R.string.ai_fail_daily_limit") },
        )
    }
}
