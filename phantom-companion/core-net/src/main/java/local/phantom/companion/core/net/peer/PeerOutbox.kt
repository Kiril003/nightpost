package local.phantom.companion.core.net.peer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One thing said to one peer.
 *
 * Signed with the sender's long-term key, because on a mesh there is no server
 * to vouch for anyone: the signature IS the claim of authorship, and a
 * message whose signature does not check is not a suspicious message, it is
 * somebody else's.
 */
@Serializable
data class PeerMessage(
    val id: String,
    /** Peer id of the author — a key fingerprint, never a display name. */
    val fromId: String,
    val toId: String,
    val body: String,
    val sentAtMs: Long,
    /** Id повідомлення, на яке це відповідає. Порожній — звичайний лист. */
    val replyToId: String = "",
    val signature: String = "",
    val state: State = State.QUEUED,
    val attempts: Int = 0,
    val nextAttemptMs: Long = 0L,
    /**
     * Which key actually carried this letter across.
     *
     * Written only when this phone did the sealing or the opening, so it is a
     * fact about US — never a claim about the other side's build, which is
     * unauthenticated and can be faked by anyone on the path. Same rule that
     * governs [PeerIdentity.keySource]: say what you know about yourself.
     */
    val sealing: Sealing = Sealing.UNKNOWN,
    /**
     * Nonce тієї копії, яку прийняв сховок. Порожній — у сховку нічого немає.
     *
     * Лежить тут, бо квитанція про доставку привʼязана саме до цієї копії, а
     * між вкиданням і квитанцією телефон може перезапуститись: у памʼяті це
     * означало б, що «ДОСТАВЛЕНО» більше ніколи не настане.
     */
    val heldNonce: String = "",
    /** До якої миті сховок обіцяв тримати. Нуль — не обіцяв нічого. */
    val heldUntilMs: Long = 0L,
    /**
     * Коли автор переписав цей лист. Нуль — не переписував.
     *
     * Окреме поле, а не заміна [sentAtMs]: правка, що зсуває час надсилання,
     * переставляє лист у стрічці й ховає сам факт правки. Тут час початковий
     * лишається на місці, а правка називає себе.
     *
     * Час — годинник автора правки ([PeerRevise]), тож він однаковий на обох апаратах.
     */
    val editedAtMs: Long = 0L,
    /** Автор стер рядок «для всіх» ([PeerRevise]); тіло вже порожнє. Нуль — не стирав. */
    val deletedAtMs: Long = 0L,
    /**
     * Автор просив «без звуку»: шторка приймача кладе рядок мовчки. Цілісність
     * дає AES-GCM конверта, тож у підпис поле не входить — старі збірки
     * перевіряють такий лист як завжди, ігнорують ключ і просто дзвонять.
     */
    val quiet: Boolean = false,
    /** Кого автор позначив @ у групі (ключі). Як і [quiet], поза підписом; старі збірки поле ігнорують. */
    @SerialName("mn")
    val mentions: List<String> = emptyList(),
    /**
     * Переслане: чий це лист насправді. Null — власні слова (або «як своє»).
     * Поза підписом, як і [quiet]: цілісність дає AES-GCM конверта, а старі
     * збірки ключа не знають, ігнорують його й перевіряють підпис як завжди.
     */
    val fw: Forward? = null,
    /** Процитована частина листа [replyToId], до [QUOTE_MAX] символів. Порожня — відповідь на весь лист. */
    val qt: String = "",
) {
    @Serializable
    data class Forward(val name: String, val id: String = "")

    companion object {
        const val QUOTE_MAX = 200
        const val FORWARD_NAME_MAX = 64
        private const val ID_MAX = 128

        /** Автор пересланого з того, що дав веб або чужий кадр. Без імені — не переслане. */
        fun forwardOf(name: String?, id: String?): Forward? {
            val n = name?.trim()?.take(FORWARD_NAME_MAX).orEmpty()
            if (n.isEmpty()) return null
            return Forward(n, id?.trim()?.take(ID_MAX).orEmpty())
        }

        /** Цитата в межах [QUOTE_MAX]; пробіли довкола не їдуть. */
        fun quoteOf(raw: String?): String = raw?.trim()?.take(QUOTE_MAX).orEmpty()
    }

    /**
     * QUEUED — written down, nobody has heard it yet.
     * SENT — handed to the transport; the peer has not confirmed.
     * HELD — сховок узяв зашифрований лист і тримає його; адресат ще мовчить.
     * DELIVERED — the peer said it has it.
     * FAILED — given up on; still visible, never silently gone.
     *
     * Порядок констант несе сенс: [PeerGroup.collapse] згортає віяр копій до
     * найменш просунутої, тож HELD мусить стояти ПЕРЕД DELIVERED — інакше
     * рядок групи, де одна копія лежить у сховку, а друга дійшла, показав би
     * «доставлено» за всіх.
     */
    enum class State { QUEUED, SENT, HELD, DELIVERED, FAILED }

    /**
     * UNKNOWN is the honest default and it means «no claim»: every row written
     * before this existed reads back as UNKNOWN, and a screen must say nothing
     * about those rather than invent a past for them.
     */
    enum class Sealing {
        UNKNOWN,
        PAIR_KEY,
        MEETING_KEY,

        /** Конверт v5 через сховок: одноразовий ключ отримувача, пряма секретність (RELAY_FS.md). */
        ONE_TIME_KEY,

        /**
         * Конверт v5, коли одноразові ключі отримувача скінчились: лише підписаний
         * SPK. Пряма секретність — з обертанням SPK (до 21 доби), не з кожним листом.
         */
        SIGNED_PREKEY,
    }
}

/**
 * What happens to a message nobody could deliver.
 *
 * This is the whole design question of an offline-first channel, and the wrong
 * answer is the one most apps ship: drop it and show a red dot. Here a message
 * waits — through a dead Wi-Fi, a jammer, a peer with a flat battery — and the
 * operator can always see that it is waiting and why. Rules are pure so that
 * behaviour is provable without a radio.
 */
object PeerOutbox {

    /**
     * Паузи між спробами після ВІДМОВИ. Перші короткі: найчастіша відмова —
     * гонка зустрічі чи перезапуск того боку, і вона минає за секунду.
     */
    val BACKOFF_MS: List<Long> = listOf(500L, 2_000L, 5_000L, 15_000L, 60_000L, 300_000L, 1_800_000L)

    /**
     * Скільки лист живе. Межа — вік, а не лічильник: вісім відмов складались у
     * 96 хвилин, і лист гинув за півтори години без мережі. Сім діб — стільки ж, скільки його
     * тримає сховок; довше нема кому його нести.
     */
    const val LIFETIME_MS: Long = PeerRelay.TTL_MS

    fun outlived(m: PeerMessage, nowMs: Long): Boolean = nowMs - m.sentAtMs >= LIFETIME_MS

    /**
     * Ніхто не був досяжний: ані телефон поруч, ані сховок.
     *
     * Спроби це не витрачає — лист лежить у черзі, доки хтось із двох шляхів не
     * відкриється або доки не мине [LIFETIME_MS]. Змінюється лише те, як рідко
     * його чіпають.
     *
     * Пауза рахується від віку листа, а не від лічильника.
     */
    val UNREACHED_MS: List<Long> = listOf(10_000L, 60_000L, 300_000L, 1_800_000L)

    fun unreachedBackoff(ageMs: Long): Long = when {
        ageMs < 60_000L -> UNREACHED_MS[0]
        ageMs < 600_000L -> UNREACHED_MS[1]
        ageMs < 3_600_000L -> UNREACHED_MS[2]
        else -> UNREACHED_MS[3]
    }

    /**
     * Засув на стані свідомий: лист, який щойно став FAILED справжньою
     * відмовою, не має воскресати через те, що наступний рух не додзвонився.
     */
    fun markUnreached(outbox: List<PeerMessage>, id: String, nowMs: Long): List<PeerMessage> =
        outbox.map {
            if (
                it.id == id &&
                (it.state == PeerMessage.State.QUEUED || it.state == PeerMessage.State.SENT)
            ) {
                if (outlived(it, nowMs)) return@map it.copy(state = PeerMessage.State.FAILED)
                it.copy(
                    state = PeerMessage.State.QUEUED,
                    nextAttemptMs = nowMs + unreachedBackoff(nowMs - it.sentAtMs),
                )
            } else {
                it
            }
        }

    /**
     * Newest kept per peer; beyond this the oldest delivered ones go first.
     *
     * **Було 500, і чому піднялось.** Число впиралось у те, що кожен шматок
     * файла — це рядок, а рядок віз base64 своїх байтів: 500 рядків на розмову
     * означали 500 шматків, тобто файл не більший за ~12 МіБ, ще й за умови, що
     * в розмові немає жодного слова. Байти пішли з журналу
     * ([PeerFile.vaultedChunk]), рядок подешевшав більш ніж у 80 разів — і
     * стеля, яка колись боронила памʼять телефона, стала боронити порожнечу.
     *
     * 2000 — не кругле число заради круглого: [PeerFile.MAX_BYTES] = 32 МіБ дає
     * 1366 шматків, і решта 634 рядки лишаються САМІЙ РОЗМОВІ. Черга, зайнята
     * одним файлом під завʼязку, — це розмова, у якій не лишилось слів; саме
     * тому вимір у `PeerFileVaultTest` вимагає щонайменше 500 вільних рядків, а
     * не просто «вміщується».
     */
    const val MAX_PER_PEER = 2000

    fun enqueue(outbox: List<PeerMessage>, message: PeerMessage): List<PeerMessage> {
        // Re-sending the same id must not double it: a retry after an
        // ambiguous failure is the normal case, not an edge case.
        if (outbox.any { it.id == message.id }) return outbox
        // Стан, який викликач поставив свідомо, не перетирається. Лист, що
        // ПРИЙШОВ, заходить сюди вже DELIVERED; зробити з нього QUEUED означало
        // б записати чуже слово в наші борги — [waiting] показав би його людині
        // як «чекає», а [prune] не мав би права його забути ніколи.
        val fresh = if (message.state == PeerMessage.State.QUEUED) {
            message.copy(nextAttemptMs = 0L)
        } else {
            message
        }
        return outbox + fresh
    }

    /** What may go out right now, oldest first — order is what makes a chat. */
    fun due(outbox: List<PeerMessage>, nowMs: Long): List<PeerMessage> =
        outbox.filter {
            (it.state == PeerMessage.State.QUEUED || it.state == PeerMessage.State.SENT) &&
                it.deletedAtMs == 0L &&
                it.nextAttemptMs <= nowMs
        }.sortedBy { it.sentAtMs }

    /** Лише з черги: прямий шлях біжить поруч зі сховком, і HELD не має відкотитись у SENT. */
    fun markSent(outbox: List<PeerMessage>, id: String): List<PeerMessage> =
        outbox.map { if (it.id == id && it.state == PeerMessage.State.QUEUED) it.copy(state = PeerMessage.State.SENT) else it }

    /**
     * Шлях щойно відкрився (мережа, сховок, людина повернулась): усе, що чекає
     * паузи, — до спроби зараз. Спроби не скидаються, тож межа відмов та сама.
     * Нічого не змінилось — той самий список, щоб не переписувати журнал.
     */
    fun wake(outbox: List<PeerMessage>, nowMs: Long): List<PeerMessage> {
        val sleeping = outbox.any { sleeps(it, nowMs) }
        if (!sleeping) return outbox
        return outbox.map { if (sleeps(it, nowMs)) it.copy(nextAttemptMs = 0L) else it }
    }

    private fun sleeps(m: PeerMessage, nowMs: Long): Boolean =
        (m.state == PeerMessage.State.QUEUED || m.state == PeerMessage.State.SENT) &&
            m.deletedAtMs == 0L && m.nextAttemptMs > nowMs

    /**
     * Сховок узяв. З цієї миті телефон за цей лист не платить: HELD не
     * потрапляє в [due], тож драбина півгодинних пауз проти
     * сплячого телефона просто зупиняється.
     *
     * Це НЕ доставка, і жодне місце не сміє прочитати це як доставку: сервер
     * тримає байти, яких не вміє прочитати, і нічого не знає про адресата.
     */
    fun markHeld(
        outbox: List<PeerMessage>,
        id: String,
        nonceB64: String,
        heldUntilMs: Long,
    ): List<PeerMessage> =
        outbox.map {
            // Прямий шлях устиг першим: лист уже доставлено, сховкова копія —
            // дублікат. Nonce лишається, щоб її квитанцію впізнати й спалити.
            if (it.id == id && nonceB64.isNotBlank() && it.state == PeerMessage.State.DELIVERED) {
                it.copy(heldNonce = nonceB64)
            } else if (it.id == id && nonceB64.isNotBlank()) {
                it.copy(
                    state = PeerMessage.State.HELD,
                    heldNonce = nonceB64,
                    heldUntilMs = heldUntilMs,
                    nextAttemptMs = 0L,
                )
            } else {
                it
            }
        }

    /** Що лежить у сховку до цієї людини — по цьому перебирають квитанції. */
    fun held(outbox: List<PeerMessage>, peerId: String): List<PeerMessage> =
        outbox.filter { it.state == PeerMessage.State.HELD && it.toId == peerId }

    /** Чию квитанцію можна впізнати: у сховку або вже доставлене прямо, але з копією там. */
    fun receiptable(outbox: List<PeerMessage>, peerId: String): List<PeerMessage> =
        outbox.filter {
            it.toId == peerId && it.heldNonce.isNotBlank() &&
                (it.state == PeerMessage.State.HELD || it.state == PeerMessage.State.DELIVERED)
        }

    /**
     * Що ми поклали в сховок РАНІШЕ, ніж народилась його теперішня база — тобто
     * чого там уже точно немає.
     *
     * Обидві миті рахуються годинником СЕРВЕРА: [storeSinceMs] він назвав на
     * заборі, а [PeerMessage.heldUntilMs] — на вкиданні, і мить вкидання це
     * строк мінус TTL. Взяти тут наш власний годинник означало б хибне «лист
     * стерто» на кожному телефоні, що трохи відстає.
     */
    fun heldBefore(outbox: List<PeerMessage>, storeSinceMs: Long): List<String> =
        outbox.filter {
            it.state == PeerMessage.State.HELD &&
                it.heldUntilMs > 0L &&
                it.heldUntilMs - PeerRelay.TTL_MS < storeSinceMs
        }.map { it.id }

    /**
     * Свої листи, покладені в скриньку доби раніше за [oldestAsked] — її адресат
     * уже не питає. Лише ті, чий тиждень від написання ще не минув: строк
     * сховка рахується від вкидання, і перекладання без цієї межі відсувало б
     * його без кінця.
     */
    fun staleHeld(outbox: List<PeerMessage>, myId: String, oldestAsked: Long, nowMs: Long): List<PeerMessage> =
        outbox.filter {
            it.state == PeerMessage.State.HELD &&
                it.fromId == myId &&
                it.heldUntilMs > 0L &&
                it.sentAtMs + PeerRelay.TTL_MS > nowMs &&
                PeerRelay.epochOf(it.heldUntilMs - PeerRelay.TTL_MS) < oldestAsked
        }

    /** Чий термін у сховку вийшов: сервер стер копію, адресат так і не прийшов. */
    fun expiredHeld(outbox: List<PeerMessage>, nowMs: Long): List<String> =
        outbox.filter {
            it.state == PeerMessage.State.HELD &&
                it.heldUntilMs in 1L until nowMs
        }.map { it.id }

    /**
     * Сім днів минуло без квитанції. Лист не зникає і не стає FAILED — він
     * повертається в чергу з чистими спробами, бо єдине, що сталось, — сплила
     * тимчасова копія на сервері. Сам лист нікуди не дівався. Чи пробувати
     * далі, вирішує його вік ([outlived]) на наступній відповіді шляху.
     */
    fun markExpiredHeld(outbox: List<PeerMessage>, id: String): List<PeerMessage> =
        outbox.map {
            if (it.id == id && it.state == PeerMessage.State.HELD) {
                it.copy(
                    state = PeerMessage.State.QUEUED,
                    attempts = 0,
                    nextAttemptMs = 0L,
                    heldNonce = "",
                    heldUntilMs = 0L,
                )
            } else {
                it
            }
        }

    fun markDelivered(outbox: List<PeerMessage>, id: String): List<PeerMessage> =
        outbox.map {
            if (it.id == id) {
                it.copy(state = PeerMessage.State.DELIVERED, nextAttemptMs = 0L)
            } else {
                it
            }
        }

    /**
     * Відмова — не мовчання. Той кінець відповів, і відповідь була «ні»: лист
     * лишається на місці й чекає довше, до півгодини. Лічильник веде лише
     * драбину пауз; FAILED настає, коли мине [LIFETIME_MS], і лист лишається
     * на екрані, не стертий.
     *
     * Тиша сюди не потрапляє: для неї є [markUnreached].
     */
    fun markFailed(
        outbox: List<PeerMessage>,
        id: String,
        nowMs: Long,
        jitter: (Long) -> Long = ::jittered,
    ): List<PeerMessage> =
        outbox.map { msg ->
            if (msg.id != id) return@map msg
            // Другий шлях уже доніс або поклав: відмова першого нічого не означає.
            if (msg.state == PeerMessage.State.DELIVERED || msg.state == PeerMessage.State.HELD) return@map msg
            val attempts = msg.attempts + 1
            if (outlived(msg, nowMs)) {
                msg.copy(state = PeerMessage.State.FAILED, attempts = attempts)
            } else {
                msg.copy(
                    state = PeerMessage.State.QUEUED,
                    attempts = attempts,
                    nextAttemptMs = nowMs + jitter(backoffFor(attempts)),
                )
            }
        }

    /** До пʼятої частини раніше, ніколи пізніше за драбину. */
    fun jittered(ms: Long): Long = ms - java.util.concurrent.ThreadLocalRandom.current().nextLong(ms / 5 + 1)

    /**
     * Which key carried it, recorded at the moment it actually crossed. Kept
     * apart from [markDelivered] because it is also true of a letter that
     * ARRIVED, and because a delivery whose sealing nobody recorded must stay
     * [PeerMessage.Sealing.UNKNOWN] rather than inherit a default.
     */
    fun noteSealing(
        outbox: List<PeerMessage>,
        id: String,
        sealing: PeerMessage.Sealing,
    ): List<PeerMessage> =
        outbox.map { if (it.id == id) it.copy(sealing = sealing) else it }

    /** The operator asking again: a given-up message gets a fresh start. */
    fun retry(outbox: List<PeerMessage>, id: String): List<PeerMessage> =
        outbox.map {
            if (it.id == id) {
                it.copy(state = PeerMessage.State.QUEUED, attempts = 0, nextAttemptMs = 0L)
            } else {
                it
            }
        }

    fun backoffFor(attempts: Int): Long =
        BACKOFF_MS[(attempts - 1).coerceIn(0, BACKOFF_MS.lastIndex)]

    /**
     * Keeps the log bounded without ever dropping something still owed: only
     * delivered messages are forgotten, oldest first.
     *
     * Шматки доставленого файла забуваються ПЕРШИМИ, і це не оптимізація.
     * Одне фото — це сотні рядків; за старим правилом «найдавніші спочатку»
     * воно виштовхувало б з розмови таку ж кількість живих речень, і людина
     * дізналась би про це, лише не знайшовши їх. Доставлений шматок своє вже
     * відпрацював — байти лежать на диску в обох; доставлене речення ні.
     */
    fun prune(outbox: List<PeerMessage>, peerId: String): List<PeerMessage> {
        val theirs = outbox.filter { it.toId == peerId || it.fromId == peerId }
        // Доставлений профіль уже в реєстрі адресата, а з фото він важить до
        // 40 КБ — у журналі, що переписується цілком на кожен лист.
        val spent = spentChunks(theirs) +
            theirs.filter { (PeerProfile.isProfile(it.body) || PeerBanner.isBanner(it.body)) && it.state == PeerMessage.State.DELIVERED }.map { it.id }
        val left = if (spent.isEmpty()) outbox else outbox.filterNot { it.id in spent }
        val mine = left.filter { it.toId == peerId || it.fromId == peerId }
        if (mine.size <= MAX_PER_PEER) return left
        val excess = mine.size - MAX_PER_PEER
        val droppable = mine
            .filter { it.state == PeerMessage.State.DELIVERED }
            .sortedWith(
                compareByDescending<PeerMessage> { PeerFile.isChunk(it.body) }
                    .thenBy { it.sentAtMs },
            )
            .take(excess)
            .map { it.id }
            .toSet()
        return left.filterNot { it.id in droppable }
    }

    /**
     * Шматки файла, який доїхав увесь. Вони більше нічого не винні: байти
     * лежать на диску в обох, а рядок черги тримає ще й їхній base64.
     *
     * Забувати їх ОДРАЗУ, не чекаючи стелі в 500, — не ощадливість. Журнал
     * шифрується й переписується цілком на кожну зміну; фото, що лишається в
     * ньому назавжди, робить кожен наступний рядок дорожчим за попередній, і
     * через десяток світлин найдешевше «привіт» коштує секунди.
     *
     * Умова навмисне сувора: доставлені МУСЯТЬ бути всі до одного, і їх має
     * бути рівно стільки, скільки обіцяв кадр. Один недолік — і не чіпаємо
     * нічого, бо неповний файл ще має шанс доїхати.
     */
    private fun spentChunks(mine: List<PeerMessage>): Set<String> {
        val heads = mine.mapNotNull { m -> PeerFile.head(m.body)?.let { it to m } }
        if (heads.isEmpty()) return emptySet()
        return heads
            .groupBy { (head, _) -> head.blobId }
            .values
            .filter { rows ->
                val total = rows.first().first.total
                rows.size == total &&
                    rows.map { it.first.index }.toSet().size == total &&
                    rows.all { it.second.state == PeerMessage.State.DELIVERED }
            }
            .flatMap { rows -> rows.map { it.second.id } }
            .toSet()
    }

    /**
     * Стерто на цьому телефоні — і тим самим знято з черги.
     *
     * Журнал і черга — один список: [due] читає ті самі рядки, які показує
     * екран. Тож «стерти, але дослати» тут не існує як варіант, і добре: телефон,
     * який далі передає те, що людина вважає стертим, гірший за будь-яке
     * формулювання.
     *
     * Один виняток, якого не закрити: копія, вже прийнята сховком, звідти не
     * відкликається — вкидання не повертає її ідентифікатора, тож спалити її
     * нема чим. Стертий [PeerMessage.State.HELD] лист до адресата все одно дійде.
     */
    fun delete(outbox: List<PeerMessage>, ids: Set<String>): List<PeerMessage> =
        if (ids.isEmpty()) outbox else outbox.filterNot { it.id in ids }

    /** Oldest first, group copies excluded: they are not this person's thread. */
    fun conversation(outbox: List<PeerMessage>, peerId: String): List<PeerMessage> =
        outbox
            .filter { (it.toId == peerId || it.fromId == peerId) && isSpoken(it) }
            .sortedBy { it.sentAtMs }

    /**
     * Чи це сказане людиною, а не службовий вантаж.
     *
     * Шматок файла — теж рядок черги, і має ним бути: інакше довелося б знову
     * будувати підпис, повтори й квитанцію. Але в РОЗМОВІ його немає: 171
     * рядок base64 замість фотографії — це не «докладніше», це замість.
     *
     * Відкрите не для зручності: саме цей набір мусить збігатись з набором,
     * якому [PeerFrames.refuseTyped] відмовляє, інакше рід, щойно впущений у
     * розмову, лишиться без відмови й повернеться підробкою походження.
     */
    fun isSpoken(m: PeerMessage): Boolean =
        !PeerGroup.isGroup(m.body) && !PeerFile.isChunk(m.body) && !PeerLive.isCargo(m.body) &&
            !PeerCall.isCall(m.body) && !PeerMark.isMark(m.body) && !PeerHistory.isPack(m.body) &&
            // Профіль — не репліка: людина змінила свій аватар, а не написала
            // в розмову. Рядок «оновив біо» у стрічці був би листом, якого
            // ніхто не писав, а лічильник непрочитаного порахував би його.
            !PeerProfile.isProfile(m.body) && !PeerBanner.isBanner(m.body) && !PeerBanner.isPull(m.body) && !PeerWhere.isWhere(m.body) && !PeerRevise.isRevise(m.body) &&
            !PeerRead.isRead(m.body) && !PeerTyping.isTyping(m.body) &&
            // Намір і відповідь ПК — запечатані байти, а не слова людини.
            !PeerIntent.isFrame(m.body) && !RelayFs.isBundle(m.body) &&
            // Строк зникнення — налаштування розмови, а не репліка.
            !PeerDisappear.isTimer(m.body) &&
            // Картка посилання — частина листа, а не ще один лист.
            !PeerPreview.isPreview(m.body) && !MomentWire.isMoment(m.body) && !PeerContact.isContact(m.body) && !PeerGroupJoin.isJoin(m.body) &&
            !StaffWire.isStaff(m.body) &&
            // Рядок каналу живе лише в конверті гілки; голий — чужий дріт.
            !PeerGroup.isChannelLine(m.body) &&
            // Кадр новішої збірки, якого ця не знає: дріт, а не слова.
            !PeerFrames.isFuture(m.body)

    /**
     * How many lines this peer said that nobody has looked at.
     *
     * Counted from what THEY sent, never from what the operator sent: a chat
     * that shows «2» for one's own messages is a chat nobody believes. The
     * boundary is a timestamp rather than a per-message flag so a message that
     * arrives while the chat is open is already read by the same rule that
     * reads the rest.
     */
    fun unread(outbox: List<PeerMessage>, peerId: String, readUpToMs: Long): Int =
        outbox.count {
            it.fromId == peerId && it.sentAtMs > readUpToMs && isSpoken(it)
        }

    /** The most recent thing said either way — what a roster row previews. */
    fun lastLine(outbox: List<PeerMessage>, peerId: String): PeerMessage? =
        outbox
            .filter { (it.toId == peerId || it.fromId == peerId) && isSpoken(it) }
            .maxByOrNull { it.sentAtMs }

    /**
     * What the operator is owed an explanation for.
     *
     * Файл рахується за ОДНУ річ, а не за свої 171 шматок. Людина надіслала
     * одну фотографію; смуга стану, що каже «171 чекає», не докладніша за ту,
     * що каже «1» — вона просто неправдива про те, скільки речей у дорозі.
     *
     * Тік живого положення не рахується взагалі: людина його не надсилала —
     * його поклав сеанс, і про його долю говорить плашка сеансу, а не смуга
     * речей, які людина вважає своїми листами. Кадр дзвінка — з тієї ж причини:
     * про долю дзвінка говорить екран дзвінка, і «1 чекає» через годину після
     * розмови — це смуга, яка бреше про лист, якого людина не писала.
     */
    fun waiting(outbox: List<PeerMessage>): Int {
        // Позначка ([PeerMark]) не рахується з тієї самої причини, і лише з
        // неї: у черзі вона лишається, повторюється й доїжджає, як усе інше.
        // Просто смайл — не лист, і «1 чекає» над ним обіцяє людині
        // ненадіслане речення.
        val owed = outbox.filterNot {
            PeerLive.isCargo(it.body) || PeerCall.isCall(it.body) || PeerMark.isMark(it.body) ||
                PeerWhere.isWhere(it.body) || PeerRevise.isRevise(it.body) || PeerRead.isRead(it.body) || it.deletedAtMs > 0L
        }.filter {
            it.state == PeerMessage.State.QUEUED ||
                it.state == PeerMessage.State.SENT ||
                // У сховку — це ще не доставлено. Не рахувати його означало б
                // тихо списати лист, за який ніхто не відзвітував.
                it.state == PeerMessage.State.HELD
        }
        val (chunks, words) = owed.partition { PeerFile.isChunk(it.body) }
        return words.size + chunks.mapNotNull { PeerFile.head(it.body)?.blobId }.distinct().size
    }
}
