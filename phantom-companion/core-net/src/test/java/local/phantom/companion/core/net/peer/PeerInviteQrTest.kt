package local.phantom.companion.core.net.peer

import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.ByteMatrix
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Запрошення, що проходить крізь камеру.
 *
 * Намальований QR виглядає правильним завжди — навіть тоді, коли жоден
 * сканер його не бере: код надто щільний для рівня корекції, або взагалі не
 * вліз у версію. Тому тут замкнуте коло: те, що екран покаже, декодується
 * назад і має дати ТОЙ САМИЙ запрошувальний код, з якого потім виходить той
 * самий ключ.
 */
class PeerInviteQrTest {

    private fun invite(name: String = "Богдан") = PeerInvite.Invite(
        peerId = "a1b2c3d4e5f60718",
        name = name,
        // Справжня довжина: 32 байти Ed25519 у base64 — саме вона робить код
        // щільним, і саме на ній ламається надто високий рівень корекції.
        pubKey = "lvAM7W9FbsvhhP3pSbjGsAHWPzuR2FxmG/ASUi4u/AM",
        // Другий ключ подовжує код на 43 символи — саме він вирішує, чи лишиться
        // QR достатньо рідким, щоб зчитуватись з екрана на екран.
        dhPubKey = "hSDwCYkwp1R0i33ctD73Wg2/Og0mOBr066SpjqqbTmo",
        host = "192.168.1.144",
        port = 47331,
    )

    /** Точно те, що малює InviteQr — сітка модулів, не картинка. */
    private fun render(text: String): ByteMatrix = Encoder.encode(
        text,
        ErrorCorrectionLevel.M,
        mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"),
    ).matrix

    /**
     * Матриця як яскравість — те саме, що камера бачить на екрані.
     *
     * Із запасом у кілька пікселів на модуль: один піксель на модуль не читає
     * ЖОДЕН сканер, тож перевірка на такій картинці міряла б не наш код, а
     * межу бінаризатора. На екрані модуль займає десятки пікселів.
     */
    private fun asSource(matrix: ByteMatrix, quiet: Int = 4, scale: Int = 6): LuminanceSource {
        val w = (matrix.width + quiet * 2) * scale
        val h = (matrix.height + quiet * 2) * scale
        val pixels = ByteArray(w * h) { -1 }
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y).toInt() != 1) continue
                for (dy in 0 until scale) {
                    val row = ((y + quiet) * scale + dy) * w
                    for (dx in 0 until scale) {
                        pixels[row + (x + quiet) * scale + dx] = 0
                    }
                }
            }
        }
        return com.google.zxing.PlanarYUVLuminanceSource(
            pixels, w, h, 0, 0, w, h, false,
        )
    }

    private fun decode(matrix: ByteMatrix): String? = runCatching {
        MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(asSource(matrix)))).text
    }.getOrNull()

    @Test
    fun `an invite survives the round trip through a QR`() {
        val code = PeerInvite.encode(invite())
        val read = decode(render(code))
        assertEquals("the QR did not decode back to the same invite", code, read)
    }

    @Test
    fun `what comes back off the QR is still a valid invite`() {
        val original = invite()
        val read = decode(render(PeerInvite.encode(original)))
        val parsed = inviteIn(read)
        assertNotNull("scanned code did not parse as an invite", parsed)
        assertEquals(original.peerId, parsed!!.peerId)
        assertEquals(original.pubKey, parsed.pubKey)
        assertEquals(original.dhPubKey, parsed.dhPubKey)
        assertEquals(original.port, parsed.port)
        // Найважливіше: шість цифр, які двоє прочитають одне одному, мусять
        // збігтися по обидва боки камери.
        assertEquals(
            PeerInvite.spokenCode(original.pubKey, original.dhPubKey),
            PeerInvite.spokenCode(parsed.pubKey, parsed.dhPubKey),
        )
    }

    @Test
    fun `a long name still fits and still scans`() {
        val long = invite(name = "Б".repeat(PeerName.MAX))
        val code = PeerInvite.encode(long)
        assertEquals(code, decode(render(code)))
    }

    @Test
    fun `the drawing stays cheap enough to be a frame`() {
        // Що менше прямокутників на кадр, то менша ймовірність, що показ
        // власного коду стане ANR — саме цим він одного разу і був.
        val matrix = render(PeerInvite.encode(invite()))
        var runs = 0
        for (y in 0 until matrix.height) {
            var x = 0
            while (x < matrix.width) {
                if (matrix.get(x, y).toInt() != 1) { x++; continue }
                runs++
                while (x < matrix.width && matrix.get(x, y).toInt() == 1) x++
            }
        }
        assertTrue("QR draws $runs rects a frame", runs < 1200)
    }

    private fun version(text: String): Int = com.google.zxing.qrcode.encoder.Encoder.encode(
        text,
        ErrorCorrectionLevel.M,
        mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"),
    ).version.versionNumber

    @Test
    fun `the code an actual person shows stays sparse enough to scan off a screen`() {
        // Справжня кількість модулів, а не запитаний розмір картинки: щільність
        // вирішує все. Понад версію 10 (57×57) зчитування з екрана на екран
        // починає залежати від того, наскільки чистий чужий дисплей — тож
        // ЗВИЧАЙНЕ імʼя мусить лишатись у цій межі й після другого ключа.
        val v = version(PeerInvite.encode(invite()))
        assertTrue("QR reached version $v for an ordinary name", v <= 10)
    }

    @Test
    fun `the longest possible name still scans, at a cost that is named`() {
        // Заміряно: звичайне імʼя — версія 10, 32 кириличні літери — 12, 64 —
        // 14 (73×73). Рівень корекції лишається M навмисно: на брудному екрані
        // виправлення помилок рятує частіше, ніж зайві модулі шкодять.
        //
        // 64 — це стеля ПРИЙМАННЯ [PeerName.MAX]: стільки ми беремо від чужого
        // пристрою, і рівно стільки може опинитись у коді, який ми передруковуємо.
        // Власне імʼя оператора обмежене вужче — бюджетом щільності QR у
        // ProfileTab (CAP_QR_NAME = 32, версія 12), бо його набирає той, хто
        // дивиться на поле.
        val ours = version(PeerInvite.encode(invite(name = "Б".repeat(32))))
        assertTrue("QR reached version $ours for the operator's own name", ours <= 12)
        val v = version(PeerInvite.encode(invite(name = "Б".repeat(PeerName.MAX))))
        assertTrue("QR reached version $v — too dense to scan off a screen", v <= 14)
    }
}
