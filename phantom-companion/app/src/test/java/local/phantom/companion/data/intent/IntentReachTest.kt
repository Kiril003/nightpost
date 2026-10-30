package local.phantom.companion.data.intent

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Намір мусить ДОЛІТАТИ: від слова людини в розмові з PHANTOM до клієнта,
 * від клієнта до сітки й SMS, від відповіді назад у веб. Окремі частини
 * доведено своїми тестами (IntentClientTest, IntentViewTest, IntentSettingsTest);
 * тут — що ланцюг цілий і жодна ланка не лишилась написаною й не викликаною.
 */
class IntentReachTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "app").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь репозиторію не знайдено") as Nothing
    }

    private fun source(path: String): String {
        val f = File(repoRoot(), "app/src/main/java/local/phantom/companion/$path")
        assertTrue("$path не знайдено — сторож нічого не боронить", f.isFile)
        return f.readText()
    }

    @Test fun `питання PHANTOM без інтернету стає Наміром, і веб бачить його`() {
        val bridge = source("ui/messenger/WebMessengerBridge.kt")
        assertTrue("без інтернету питання не йде клієнтові", bridge.contains("container.intents.client.question(asked, anchor = userMsg.id)"))
        assertTrue("до ПК їде опис вкладення з ключем", bridge.contains("val asked = AssistantInput.promptOf(text)"))
        assertTrue("рішення не спирається на справжню мережу", bridge.contains("InternetPath.has(container.applicationContext)"))
        assertTrue("міст не веде /messenger/intents* до WebMessengerIntents", bridge.contains("intentsRoute.handle(method, seg, body)"))
        assertTrue("веб не може прочитати журнал", source("ui/messenger/WebMessengerIntents.kt").contains("IntentView.page(wiring.client.journal.value)"))
        assertTrue("рядок питання не несе id прохання", bridge.contains("\"intent_id\", container.intents.client.forAnchor(m.id)"))
        val host = source("ui/messenger/WebMessengerHost.kt")
        assertTrue("веб не дізнається про зміну журналу", host.contains("__phantomIntentsChanged"))
    }

    @Test fun `клієнт підключений до сітки, SMS і старту сервісу`() {
        val wiring = source("data/intent/IntentWiring.kt")
        assertTrue("ключ пари не з сітки", wiring.contains("container.mesh.pairKeyFor(pcId)"))
        assertTrue("лист сховком не через сітку", wiring.contains("container.mesh.send(pcId, body)"))
        assertTrue("відповіді сховком не читаються", wiring.contains("client.onLetter(m.fromId, m.body)"))
        assertTrue("SMS від шлюзу не доходять", wiring.contains("IntentInbox.sink = { text -> client.onSms(text) }"))
        assertTrue("SMS-смуга не з флейвора", wiring.contains("IntentSmsFlavor.port("))
        assertTrue("сітка не вміє дати ключ пари", source("data/PeerMesh.kt").contains("fun pairKeyFor(peerId: String)"))
        assertTrue("контейнер не тримає клієнта", source("data/AppContainer.kt").contains("IntentWiring(this)"))
        assertTrue("клієнт не стартує разом із сіткою", source("service/PeerMeshService.kt").contains("container.intents.start(scope)"))
    }

    @Test fun `домашній шлюз дістає SMS моїх телефонів і віддає відповідь SMS`() {
        val field = File(repoRoot(), "app/src/field/java/local/phantom/companion/data/intent/IntentSmsFlavor.kt").readText()
        assertTrue("приймач не віддає SMS шлюзу", field.contains("GatewayInbox.deliver(from"))
        assertTrue("шлюз працює поза вікном приймання", field.contains("goAsync()") && field.contains("withTimeoutOrNull(GATEWAY_WINDOW_MS)"))
        assertTrue("приймач бере SMS не лише від моїх номерів", field.contains("settings.allowed"))
        val wiring = source("data/intent/IntentWiring.kt")
        assertTrue("шлюз не отримує SMS", wiring.contains("GatewayInbox.sink = { from, text -> gateway?.onSms(from, text) }"))
        assertTrue("відповідь сховком не доходить до шлюзу", wiring.contains("gateway?.onLetter(m.body)"))
        assertTrue("LAN-дорога шлюзу не до мого ПК", wiring.contains("GatewayLan {"))
    }

    @Test fun `дозволи SMS просить лише екран «Без інтернету»`() {
        val host = source("ui/messenger/WebMessengerHost.kt")
        assertTrue("екран застосунку не показує запит", host.contains("IntentPermit.requests.collect"))
        assertTrue("міст не вміє попросити", source("ui/messenger/WebMessengerIntents.kt").contains("IntentPermit.ask()"))
        // Ніде більше в src/main назви дозволів SMS немає — інакше їх просить хтось іще.
        val root = File(repoRoot(), "app/src/main/java")
        val elsewhere = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "IntentPermit.kt" }
            .filter { it.readText().contains("permission.SEND_SMS") || it.readText().contains("permission.RECEIVE_SMS") }
            .map { it.name }
            .toList()
        assertTrue("SMS-дозволи просять ще й тут: $elsewhere", elsewhere.isEmpty())
    }
}
