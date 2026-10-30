package local.phantom.companion.core.net.peer

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Скільки коштує файл у черзі — ВИМІРЯНО, а не прикинуто.
 *
 * [PeerFile.MAX_BYTES] — єдине число в месенджері, яке людина бачить як
 * «безмежно чи ні», і воно стояло на арифметиці в коментарі. Власник просить
 * «безмежно»; правда полягає в тому, що межу ставить не дріт і не ввічливість,
 * а спосіб, у який черга лежить на диску: `PeerOutboxStore.save` серіалізує
 * ВЕСЬ журнал в ОДИН рядок і переписує його цілком на кожну зміну. Поки шматки
 * в журналі, цей рядок важить стільки ж, скільки файл у base64 — і він мусить
 * поміститись у купу телефона РАЗОМ із самими шматками.
 *
 * Тут ця ціна міряється на справжніх кадрах і справжньою серіалізацією, а не
 * множенням на віру.
 *
 * **16.09: байти пішли з журналу.** Вимір лишився й лишився чинним — він
 * пояснює, ЧОМУ стеля була 4 МіБ, і саме він тримає в чесності твердження, що
 * інакше було не можна. Змінилось одне: він більше не міряє ЧИННУ стелю, бо
 * чинна ([PeerFile.MAX_BYTES] = 32 МіБ) стоїть уже не на цій ціні. Нову міряє
 * `PeerFileVaultTest`; тут лишається ціна СТАРОГО способу, на тому самому
 * розмірі, на якому її колись зняли.
 */
class PeerFileCeilingTest {

    /** Та сама настройка, що в `PeerOutboxStore`: розмір від неї не залежить. */
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private fun chunkLetters(sizeBytes: Int): List<PeerMessage> {
        val blobId = "0".repeat(PeerFile.BLOB_ID_CHARS)
        val total = PeerFile.chunkCount(sizeBytes)
        val full = ByteArray(PeerFile.CHUNK_BYTES) { (it and 0x7f).toByte() }
        return (0 until total).map { i ->
            PeerMessage(
                id = "msg_" + "%012d".format(i),
                fromId = "a".repeat(16),
                toId = "b".repeat(16),
                body = PeerFile.encodeChunk(blobId, i, total, full),
                sentAtMs = 1_788_517_702_898L,
                signature = "s".repeat(88),
                state = PeerMessage.State.QUEUED,
            )
        }
    }

    /**
     * Найдорожча мить файла: усі шматки ще в черзі, і журнал переписується.
     *
     * Міряється на нинішній стелі — 4 МіБ, 171 шматок, — бо це справжній,
     * дешевий вимір. Ціна лінійна за побудовою (шматок фіксованої ваги, рядок
     * фіксованих полів), тож із неї виводиться будь-яке інше число.
     */
    @Test
    fun `ціна файла в черзі виміряна, а не прикинута`() {
        // Рівно 4 МіБ — стара стеля, а не [PeerFile.MAX_BYTES]: міряється ціна
        // СПОСОБУ, яким возили байти, і привʼязувати її до чинної стелі
        // означало б щоразу переписувати число, яке пояснює минуле рішення.
        val oldCeiling = 4 * 1024 * 1024
        val letters = chunkLetters(oldCeiling)
        assertEquals("шматків на старій стелі стало інакше", 171, letters.size)

        // 7 («PH-FCH:») + 32 (blob) + 3 роздільники + «0» + «171» + base64 24
        // КіБ (32 768). Саме ПЕРШИЙ шматок: у наступних номер довший на цифру,
        // тож рівність тут була б неправдою про решту.
        assertEquals("кадр шматка змінив вагу", 32_814, letters.first().body.length)

        val serialized = json.encodeToString(letters)
        val perLetter = serialized.length.toDouble() / letters.size

        // Знаки, а не байти: рядок у купі — UTF-16, тобто ×2. Саме він і є та
        // мить, коли телефон тримає ОДНОЧАСНО і шматки, і їхню копію в одному
        // рядку — тобто платить за файл двічі, перш ніж дійде до шифрування.
        val charsAtCeiling = serialized.length
        assertTrue(
            "журнал на стелі важить $charsAtCeiling знаків — уже не 5-6 МБ",
            charsAtCeiling in 5_600_000..6_200_000,
        )

        // Ось і вся відповідь на «безмежно»: те саме число для 100 МБ.
        val hundredMb = 100 * 1024 * 1024
        val chunksFor100 = PeerFile.chunkCount(hundredMb)
        assertEquals("100 МБ — це стільки шматків", 4_267, chunksFor100)
        val charsFor100 = (chunksFor100 * perLetter).toLong()
        assertTrue(
            "100 МБ дали б $charsFor100 знаків в одному рядку — тобто " +
                "${charsFor100 * 2 / 1024 / 1024} МБ лише на цю копію, і стільки ж " +
                "на самі тіла шматків, перш ніж почнеться шифрування",
            charsFor100 > 140_000_000L,
        )
    }

    /**
     * Друга межа, незалежна від памʼяті: черга рахує ШМАТКИ рядками.
     *
     * Файл, що дає більше шматків, ніж уміщує черга однієї розмови, не
     * «повільно їде» — він виштовхує сам себе, і частина його шматків зникає з
     * черги, не поїхавши. Мовчазна поразка найгіршого ґатунку: людина бачить
     * надіслане фото, якого другий бік не збере ніколи.
     *
     * Ця межа НЕ зникла від виносу байтів — вона лишилась така сама, лише
     * посунулась разом із [PeerOutbox.MAX_PER_PEER]. Тому й перевіряється тут
     * на ЧИННИХ числах, а не на старих.
     */
    @Test
    fun `файл на стелі вміщується в чергу однієї розмови`() {
        assertTrue(
            "стеля файла дає ${PeerFile.chunkCount(PeerFile.MAX_BYTES)} шматків " +
                "при черзі на ${PeerOutbox.MAX_PER_PEER} рядків — частина не поїде",
            PeerFile.chunkCount(PeerFile.MAX_BYTES) < PeerOutbox.MAX_PER_PEER,
        )
        // І скільки саме важить «безмежно» цією міркою: 100 МБ — це 4267
        // рядків. Навіть при піднятій стелі черги вони не вміщуються, і саме
        // тому слова «безмежно» немає ніде в продукті.
        assertTrue(
            "100 МБ це ${PeerFile.chunkCount(100 * 1024 * 1024)} рядків проти " +
                "${PeerOutbox.MAX_PER_PEER} — черга зʼїла б власний файл",
            PeerFile.chunkCount(100 * 1024 * 1024) > PeerOutbox.MAX_PER_PEER,
        )
    }
}
