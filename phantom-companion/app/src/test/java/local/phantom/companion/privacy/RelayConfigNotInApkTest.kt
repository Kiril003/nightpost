package local.phantom.companion.privacy

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Конфіг ретранслятора не живе в пакунку.
 *
 * До 12.09 `iceConfig()` (тоді ще в `WebMessengerBridge`) носив адресу
 * ретранслятора, порт і СПІЛЬНИЙ КЛЮЧ літералами. Пакунок роздається людям —
 * отже роздавався і доступ. Друга половина тієї ж вади: `turn = true`
 * казалось завжди, тож веб малював «TURN <адреса>» у картці дзвінка й у
 * налаштуваннях навіть тоді, коли ретранслятора не існує.
 *
 * 09.10 ця логіка переїхала в [local.phantom.companion.ui.messenger.WebMessengerIce]
 * (`selfHosted()`) — сторож переїхав разом із нею, інакше залишився б міряти
 * порожнє місце в мосту. Контракт той самий, що на ПК (`messenger/turn.py`):
 * немає `turn.json` у даних застосунку — немає ретранслятора, і телефон каже
 * це прямо.
 *
 * Третій рядок — демо-хост сигналінгу дзвінка — лишається на мосту, він туди
 * не переїжджав: кадр із SDP апарата їхав на адресу, яку веб-бандл забороняє
 * власним сторожем, а міст звітував «доставлено» на будь-яку відповідь 2xx.
 */
class RelayConfigNotInApkTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "feature-stream").isDirectory }
        ?: error("не знайшла кореня сховища: жоден предок не має теки feature-stream")

    private val ice: String by lazy {
        val f = File(root, "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerIce.kt")
        assertTrue("зник WebMessengerIce.kt — конфіг ретранслятора більше ні на чому міряти", f.isFile)
        f.readText()
    }

    private val bridge: String by lazy {
        File(root, "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt").readText()
    }

    @Test
    fun `WebMessengerIce не носить ані адреси, ані ключа`() {
        // Весь файл, а не лише selfHosted(): `fromServer`/`sanitize` і companion
        // теж ходять у мережу, і вшитий у них хост чи ключ роздавався б із
        // пакунком так само, як колись вшитий в iceConfig().
        assertFalse(
            "адреса ретранслятора вписана в WebMessengerIce — вона мусить приходити з даних вузла",
            Regex("""["']\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}""").containsMatchIn(ice),
        )
        assertFalse(
            "довгий шістнадцятковий літерал у WebMessengerIce — спільний ключ у пакунку",
            Regex("""["'][0-9a-fA-F]{32,}["']""").containsMatchIn(ice),
        )
        assertFalse(
            "назва хоста вписане літералом у WebMessengerIce — адреса мусить йти зі збірки чи з даних вузла",
            Regex("""["']https?://[A-Za-z0-9.-]+\.[A-Za-z]{2,}""").containsMatchIn(ice),
        )
        assertTrue(
            "ICE більше не читає конфіг із даних застосунку",
            "turn.json" in ice,
        )
        // Сторож міряє весь файл, але порожнє місце міряти нічим: якщо гілка
        // власного ретранслятора зникла з WebMessengerIce, три перевірки вище
        // зеленіють самі собою. Тож її наявність названа окремо (раніше це
        // стерегло `.also` на витягнутому тілі selfHosted()).
        assertTrue(
            "зникла selfHosted() у WebMessengerIce — перевимір шляху дзвінків",
            Regex("""fun selfHosted\(\)""").containsMatchIn(ice),
        )
    }

    @Test
    fun `без конфігу прапорець turn не піднімається`() {
        assertTrue(
            "ICE більше не має гілки «ретранслятора немає» — turn поїде true завжди",
            Regex("""put\("turn",\s*false\)""").containsMatchIn(ice),
        )
    }

    @Test
    fun `сигнал дзвінка не їде на демо-хост`() {
        assertFalse(
            "сигнал дзвінка знову має запасний хост у коді — кадр із SDP поїде невідомо куди",
            "try.phantom-os.dev" in bridge,
        )
    }
}
