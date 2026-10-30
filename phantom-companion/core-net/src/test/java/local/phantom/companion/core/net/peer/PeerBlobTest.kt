package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Опис вкладення, байти якого лежать у сховищі.
 *
 * Найважливіше тут — не те, що правильний опис читається, а те, що НЕправильний
 * не читається: кадр складають чужі руки, і опис, якому повірили наполовину,
 * дає бульбашку, яка вічно качає те, чого немає.
 */
class PeerBlobTest {

    private val key = "msgr/" + "a".repeat(32)
    private val sha = "b".repeat(64)
    private val keyHex = "c".repeat(64)
    private val nonceHex = "d".repeat(24)

    private fun body(
        objectKey: String = key,
        sha256: String = sha,
        size: String = "1048576",
        k: String = keyHex,
        nonce: String = nonceHex,
        extra: String = "",
    ) = """{"object_key":"$objectKey","sha256":"$sha256","size":$size,"key_hex":"$k","nonce_hex":"$nonce"$extra}"""

    // ── читається те, що склали ──────────────────────────────────────────

    @Test
    fun `повний опис читається з усіма полями`() {
        val q = '"'
        val blob = PeerBlob.describe(
            body(extra = ",${q}name$q:${q}звіт.pdf$q,${q}mime$q:${q}application/pdf$q,${q}caption$q:${q}ось$q"),
        )
        requireNotNull(blob)
        assertEquals(key, blob.objectKey)
        assertEquals(sha, blob.sha256)
        assertEquals(1_048_576L, blob.size)
        assertEquals(keyHex, blob.keyHex)
        assertEquals("звіт.pdf", blob.name)
        assertEquals("application/pdf", blob.mime)
        assertEquals("ось", blob.caption)
    }

    @Test
    fun `без імені й типу опис лишається чинним`() {
        val blob = PeerBlob.describe(body())
        requireNotNull(blob)
        assertEquals("", blob.name)
        assertEquals("application/octet-stream", blob.mime)
    }

    @Test
    fun `гігабайт проходить — заради цього все й робилось`() {
        val gib = 1024L * 1024 * 1024
        assertEquals(gib, PeerBlob.describe(body(size = "$gib"))?.size)
    }

    // ── не читається те, чого не склали ──────────────────────────────────

    @Test
    fun `слово людини не стає файлом`() {
        assertNull(PeerBlob.describe("просто слово"))
        assertNull(PeerBlob.describe(""))
        // Людина процитувала JSON у розмові — це досі слово.
        assertNull(PeerBlob.describe("""{"object_key":"msgr/щось"}"""))
    }

    @Test
    fun `опис без будь-якого обовʼязкового поля не читається`() {
        assertNull("без ключа обʼєкта", PeerBlob.describe("""{"sha256":"$sha","size":1,"key_hex":"$keyHex","nonce_hex":"$nonceHex"}"""))
        assertNull("без відбитка", PeerBlob.describe("""{"object_key":"$key","size":1,"key_hex":"$keyHex","nonce_hex":"$nonceHex"}"""))
        assertNull("без ваги", PeerBlob.describe("""{"object_key":"$key","sha256":"$sha","key_hex":"$keyHex","nonce_hex":"$nonceHex"}"""))
        assertNull("без ключа файла", PeerBlob.describe("""{"object_key":"$key","sha256":"$sha","size":1,"nonce_hex":"$nonceHex"}"""))
        assertNull(
            "без разового числа — ключ був би марний",
            PeerBlob.describe("""{"object_key":"$key","sha256":"$sha","size":1,"key_hex":"$keyHex"}"""),
        )
    }

    /**
     * Опис, що вказує на ЗБІРКУ, а не на вкладення.
     *
     * Сервер таке відмовляє сам, але телефон не має й питати: кадр, складений
     * чужими руками, спиняється там, де його прочитали.
     */
    @Test
    fun `ключ поза msgr не читається`() {
        for (bad in listOf(
            "builds/phantom-1.0.apk",
            "msgr/../builds/phantom-1.0.apk",
            "msgr/" + "a".repeat(31),
            "msgr/" + "a".repeat(33),
            "msgr/" + "z".repeat(32),
            "MSGR/" + "a".repeat(32),
            "",
        )) {
            assertNull("ключ «$bad» пройшов", PeerBlob.describe(body(objectKey = bad)))
            assertFalse(PeerBlob.isObjectKey(bad))
        }
        assertTrue(PeerBlob.isObjectKey(key))
    }

    @Test
    fun `ключ розшифрування мусить бути AES-256`() {
        assertNull("короткий ключ", PeerBlob.describe(body(k = "c".repeat(32))))
        assertNull("довгий ключ", PeerBlob.describe(body(k = "c".repeat(65))))
        assertNull("не шістнадцятковий", PeerBlob.describe(body(k = "z".repeat(64))))
    }

    @Test
    fun `разове число мусить бути завдовжки з GCM`() {
        assertNull("коротке", PeerBlob.describe(body(nonce = "d".repeat(16))))
        assertNull("довге", PeerBlob.describe(body(nonce = "d".repeat(32))))
        assertNull("не шістнадцяткове", PeerBlob.describe(body(nonce = "z".repeat(24))))
        assertEquals(nonceHex, PeerBlob.describe(body())?.nonceHex)
    }

    @Test
    fun `вага поза межами не читається`() {
        assertNull("нуль", PeerBlob.describe(body(size = "0")))
        assertNull("відʼємна", PeerBlob.describe(body(size = "-1")))
        assertNull(
            "понад стелю",
            PeerBlob.describe(body(size = "${PeerBlob.MAX_OBJECT_BYTES + 1}")),
        )
        assertEquals(
            "рівно стеля мусить проходити",
            PeerBlob.MAX_OBJECT_BYTES,
            PeerBlob.describe(body(size = "${PeerBlob.MAX_OBJECT_BYTES}"))?.size,
        )
    }

    // ── межа названа числом ──────────────────────────────────────────────

    /**
     * Стеля — дзеркало `platform-site/server/attachments.py:MAX_OBJECT_BYTES`.
     * Сервер у іншому дереві, тож рівність тут не перевірити; прибито хоч саме
     * число, щоб воно не поїхало мовчки.
     */
    @Test
    fun `стеля саме два гігабайти`() {
        assertEquals(2L * 1024 * 1024 * 1024, PeerBlob.MAX_OBJECT_BYTES)
    }

    // ── дорога видима ────────────────────────────────────────────────────

    /**
     * Власник просив, щоб людина бачила дорогу файла: від неї залежить, дійде
     * він за секунди чи за годину.
     */
    @Test
    fun `дорога читається з самого листа`() {
        assertEquals(PeerBlob.Route.STORAGE, PeerBlob.routeOf(body()))
        assertNull("слово не має дороги", PeerBlob.routeOf("привіт"))
    }

    /**
     * Опис сховища й опис шматкового шляху не сплутуються.
     *
     * Обидва — JSON, обидва їдуть тим самим набраним входом. Сплутай їх — і
     * приймач або качатиме з сховища те, що приїхало шматками, або чекатиме
     * шматків на те, що вже лежить у сховищі.
     */
    @Test
    fun `опис сховища не плутається з описом шматків`() {
        val mesh = """{"blob_id":"${"a".repeat(32)}","key_hex":"$keyHex","sha256":"$sha","size":1024}"""
        assertNull("шматковий опис прочитався як сховище", PeerBlob.describe(mesh))
        assertEquals(PeerBlob.Route.MESH, PeerBlob.routeOf(mesh))

        assertNull("опис сховища прочитався як шматковий", PeerFile.describe(body()))
        assertEquals(PeerBlob.Route.STORAGE, PeerBlob.routeOf(body()))
    }

    /**
     * Опис сховища мусить лишатись РЕПЛІКОЮ — він і є вкладення в стрічці.
     *
     * Це те саме рішення, що й для [PeerFile.describe]: рід у реєстрі
     * [PeerFrames] отримав би відмову в `refuseTyped`, а веб кладе файл тим
     * самим набраним входом, яким шле слово. Тобто реєстрація зупинила б
     * надсилання вкладень зовсім.
     */
    @Test
    fun `опис сховища їде як слово, а не як службовий кадр`() {
        val letter = PeerMessage(
            id = "m1",
            fromId = "a".repeat(16),
            toId = "b".repeat(16),
            body = body(),
            sentAtMs = 1_788_517_702_898L,
        )
        assertTrue("опис сховища випав з розмови", PeerOutbox.isSpoken(letter))
        assertNull("опис сховища став службовим родом", PeerFrames.kindOf(body()))
        assertNull("опису сховища відмовили б на вході", PeerFrames.refuseTyped(body()))
    }

    // ── заява на посилання ───────────────────────────────────────────────

    /**
     * НАЙВАЖЛИВІШЕ ТУТ: підпис, узятий на заяві, не має зійти за підпис
     * деінде.
     *
     * Міст підписує заяву ключем ПРИСТРОЮ — тим самим, яким підписані листи.
     * Якби речення заяви можна було сплутати з канонічним рядком листа або з
     * payload довідника, підпис, виданий для вкладення, прийняли б там як
     * авторство листа. Тому рід окремий, і тут це перевіряється як факт, а не
     * як намір.
     */
    @Test
    fun `рід заяви не збігається з жодним іншим підписаним у продукті`() {
        val put = PeerBlob.putClaim("PUB", 1024, "a".repeat(32), 1_700_000_000_000L)
        val get = PeerBlob.getClaim("PUB", key, "a".repeat(32), 1_700_000_000_000L)

        // Власний рід, і він на початку — саме там, де читач вирішує, що це.
        assertTrue(put.startsWith(PeerBlob.CLAIM_DOMAIN + "-put|"))
        assertTrue(get.startsWith(PeerBlob.CLAIM_DOMAIN + "-get|"))

        // Не службовий кадр продукту: інакше заяву можна було б надіслати
        // листом, а лист — підсунути на підпис.
        for (claim in listOf(put, get)) {
            assertFalse("заява починається міткою кадру", claim.startsWith(PeerFrames.MARK))
            assertNull("заява прочиталась як службовий рід", PeerFrames.kindOf(claim))
            assertNull("заява прочиталась як опис вкладення", PeerBlob.describe(claim))
            assertFalse("заява прочиталась як шматок", PeerFile.isChunk(claim))
            assertFalse("заява прочиталась як картка", PeerCard.isCard(claim))
        }

        // Запис і читання — РІЗНІ речення: інакше заява на читання зійшла б за
        // заяву на запис.
        assertFalse(put == get)
    }

    @Test
    fun `заява несе версію — старий підпис не зійде за новий`() {
        val put = PeerBlob.putClaim("PUB", 1024, "a".repeat(32), 1L)
        assertTrue(put.contains("|" + PeerBlob.CLAIM_VERSION + "|"))
    }

    /**
     * Дзеркало `platform-site/server/attachments.py`: `put_message` і
     * `get_message` слово в слово. Сервер в іншому дереві, тож рівність тут не
     * перевірити — прибиті самі рядки.
     */
    @Test
    fun `речення заяви саме те, яке звіряє сервер`() {
        assertEquals(
            "phantom-attachment-put|v1|PUB|123|" + "a".repeat(32) + "|1700000000000",
            PeerBlob.putClaim("PUB", 123, "a".repeat(32), 1_700_000_000_000L),
        )
        assertEquals(
            "phantom-attachment-get|v1|PUB|$key|" + "a".repeat(32) + "|1700000000000",
            PeerBlob.getClaim("PUB", key, "a".repeat(32), 1_700_000_000_000L),
        )
    }

    @Test
    fun `вага і ключ входять у речення — заяву не переписати`() {
        val n = "a".repeat(32)
        assertFalse(PeerBlob.putClaim("P", 1, n, 1L) == PeerBlob.putClaim("P", 2, n, 1L))
        val other = "msgr/" + "b".repeat(32)
        assertFalse(PeerBlob.getClaim("P", key, n, 1L) == PeerBlob.getClaim("P", other, n, 1L))
    }

    @Test
    fun `разове число строгої форми`() {
        assertTrue(PeerBlob.isClaimNonce("a".repeat(32)))
        assertFalse(PeerBlob.isClaimNonce("a".repeat(31)))
        assertFalse(PeerBlob.isClaimNonce("z".repeat(32)))
        assertEquals(32, PeerBlob.CLAIM_NONCE_CHARS)
    }
}
