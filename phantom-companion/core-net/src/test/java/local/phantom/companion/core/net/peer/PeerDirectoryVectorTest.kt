package local.phantom.companion.core.net.peer

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.bytesToHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * ОДИН контракт довідника для телефона й сервера — доведений спільним вектором.
 *
 * Той самий файл лежить у двох деревах (`platform-site/server/tests/vectors/
 * directory_v2_vector.json`) і читається pytest'ом. Це єдиний доказ, який ловить
 * розходження ДО того, як воно стане мовчазним: розійдись тут хоч регістр імені
 * перед хешуванням, хоч порядок полів у підписаному реченні — телефон і далі
 * чесно публікує, сервер і далі чесно відмовляє, і обидва боки зелені кожен у
 * себе.
 *
 * Ed25519 детермінований (RFC 8032), тож обидві мови зобовʼязані видати БАЙТ У
 * БАЙТ той самий рядок підпису з того самого насіння — порівнюється саме він, а
 * не «підпис сходиться» (останнє було б зеленим і при двох різних реченнях).
 *
 * Головне, що доводить вектор: у підписаному реченні стоїть sha256 імені, а не
 * імʼя. Доки було навпаки (v1), сервер діставав кожне опубліковане імʼя.
 */
class PeerDirectoryVectorTest {

    private val resourceText: String = run {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(VECTOR)) {
            "вектор $VECTOR не знайдено у test resources"
        }
        stream.reader(Charsets.UTF_8).readText()
    }

    private val vector: JsonObject = Json.parseToJsonElement(resourceText).jsonObject

    private fun text(vararg path: String): String {
        var node = vector
        for (i in 0 until path.size - 1) node = node[path[i]]!!.jsonObject
        return node[path.last()]!!.jsonPrimitive.content
    }

    private val handle get() = text("handle")
    private val digest get() = text("expected", "handle_hash")
    private val code get() = text("invite", "code")
    private val exp get() = vector["exp"]!!.jsonPrimitive.long
    private val seed get() = hexToBytes(text("ed25519", "seed_hex"))

    @Test fun `адреса запису — це sha256 імені, і ніщо інше`() {
        assertEquals(digest, PeerDirectory.handleHash(handle))
        // Те саме імʼя, як його набирає людина: з решіткою і великими літерами.
        assertEquals(handle, PeerDirectory.normalise(text("handle_raw")))
        assertEquals(digest, PeerDirectory.handleHash(text("handle_raw").removePrefix("@")))
    }

    @Test fun `речення публікації й відклику збігаються з вектором до байта`() {
        assertEquals(
            text("expected", "publish_message"),
            String(PeerDirectory.publishPayload(digest, code, exp), Charsets.UTF_8),
        )
        assertEquals(
            text("expected", "withdraw_message"),
            String(PeerDirectory.withdrawPayload(digest, exp), Charsets.UTF_8),
        )
        assertFalse(
            "імені в підписаному реченні немає — у цьому вся v2",
            text("expected", "publish_message").contains(handle),
        )
        assertFalse(text("expected", "withdraw_message").contains(handle))
    }

    @Test fun `підпис збігається з вектором байт у байт`() {
        assertEquals(
            text("expected", "publish_sig_b64url"),
            PeerDirectory.sign(PeerDirectory.publishPayload(digest, code, exp), seed),
        )
        assertEquals(
            text("expected", "withdraw_sig_b64url"),
            PeerDirectory.sign(PeerDirectory.withdrawPayload(digest, exp), seed),
        )
        // І ключ із вектора справді той, який лежить усередині запрошення.
        assertEquals(
            text("ed25519", "pub_hex"),
            bytesToHex(Ed25519.publicKeyFromPrivate(seed)),
        )
    }

    @Test fun `запис із вектора проходить ту саму перевірку, що й чужий`() {
        val record = PeerDirectory.Record(
            handle = handle,
            invite = code,
            sig = text("expected", "publish_sig_b64url"),
            expSeconds = exp,
            pubKey = text("ed25519", "pub_b64"),
        )
        val verdict = PeerDirectory.verify(record, nowMs = (exp - 3_600L) * 1_000L)
        assertTrue("вердикт: $verdict", verdict is PeerDirectory.Verdict.Trusted)

        // Запрошення з вектора — рівно те, що друкує сам телефон для довідника:
        // без адреси. Порт у ньому «0», а не порожньо (поле — Int), і саме це
        // мусить читатись обома боками.
        val invite = (verdict as PeerDirectory.Verdict.Trusted).invite
        assertEquals("", invite.host)
        assertEquals(0, invite.port)

        // З 04.09 безадресна форма — це не окремий діалект довідника, а те, що
        // телефон друкує собі. Вектор — єдине місце, де видно, що ЦЕЙ САМИЙ
        // рядок приймають обидві мови: тут — звичайним декодером, там —
        // `parse_invite` (порт «0» лежить у python-множині NO_PORT).
        assertTrue(PeerInvite.decode(code) is PeerInvite.Decoded.Ph2)

        // Поля беруться з РОЗПАКОВАНОГО тіла коду: розбір підставляє короткий
        // ідентифікатор замість порожнього імені, тож `invite.name` непорожнє
        // й сховало б зріз. Обидві мови звіряють цей самий список.
        val fields = String(unB64Local(code.split(":")[1]), Charsets.UTF_8).split("|")
        val expected = vector["expected"]!!.jsonObject["invite_fields"]!!
            .jsonArray.map { it.jsonPrimitive.content }
        assertEquals(expected, fields)
        assertEquals("показне імʼя зрізане", "", fields[1])
        assertEquals("адреси немає", "", fields[4])
        assertEquals("порт друкується нулем", "0", fields[5])

        // А на скло виходить короткий id, не порожній рядок.
        assertEquals(PeerRoster.shortId(invite.peerId), invite.name)
        assertTrue(invite.name.isNotBlank())
    }

    /**
     * Другий примірник вектора — у дереві сервера. Якщо його поруч немає
     * (окремо викачане дерево телефона), тест ЗУПИНЯЄТЬСЯ з причиною, а не тихо
     * зеленіє: «крос-мовність не доведено» і «крос-мовність доведено» — це різні
     * відповіді.
     */
    /**
     * ДРЕЙФ ВЕКТОРА ЛОВИТЬСЯ БЕЗ ДРУГОГО ДЕРЕВА.
     *
     * Сусідній тест звіряє примірник сервера напряму — і саме тому мовчить там,
     * де це найпотрібніше: на воротах клонується САМ phantom-companion, дерева
     * `platform-site` поруч немає, `assumeTrue` пропускає перевірку, і прогін
     * лишається зеленим. Виміряно 12.09: крос-мовність не довів ЖОДЕН прогін
     * воріт, хоча тест написано й він чесно каже це в повідомленні.
     *
     * Тому відбиток вектора прибитий числом. Той самий рядок стоїть у
     * `platform-site/server/tests/test_directory.py`, і кожен бік звіряє СВІЙ
     * примірник зі спільним числом — не бачачи чужого дерева. Розійдуться
     * копії — червоніють ОБОЄ воріт, кожні у себе.
     *
     * Міняти число можна лише разом із вектором і разом із другим боком. Якщо
     * міняєте одне — ви й зламали контракт, про який цей файл.
     */
    @Test fun `відбиток вектора прибитий і звіряється без дерева сервера`() {
        val digest = bytesToHex(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(resourceText.toByteArray(Charsets.UTF_8)),
        )
        assertEquals(
            "вектор довідника змінився — злічіть його наново на ОБОХ боках або поверніть як було",
            VECTOR_SHA256,
            digest,
        )
    }

    @Test fun `примірник у дереві сервера однаковий байт у байт`() {
        val twin = generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, "platform-site/server/tests/vectors/$VECTOR") }
            .firstOrNull { it.isFile }

        assumeTrue(
            "поруч немає дерева сервера з $VECTOR: крос-мовність цим запуском не доведено",
            twin != null,
        )
        assertEquals(resourceText, twin!!.readText(Charsets.UTF_8))
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun unB64Local(body: String): ByteArray =
        java.util.Base64.getUrlDecoder().decode(body)

    private companion object {
        const val VECTOR = "directory_v2_vector.json"

        /**
         * sha256 самого файла вектора, 12.09. Однакове число тримає й pytest на
         * сервері — це і є дзеркало, яке працює без другого дерева.
         */
        const val VECTOR_SHA256 = "ed1c9337acea1d7104d226fd9a18f104324fd6f9f3584d26a26f7a8427313ffb"
    }
}
