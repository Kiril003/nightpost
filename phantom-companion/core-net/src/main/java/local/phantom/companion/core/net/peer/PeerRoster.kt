package local.phantom.companion.core.net.peer

import kotlinx.serialization.Serializable

/**
 * Who PHANTOM can talk to.
 *
 * Pairing today is singular by construction: one JWT, one base URL, one
 * device — the operator's own PC. That shape cannot hold a second person, so
 * «людям» was a screen honestly admitting it had no mesh behind it.
 *
 * A roster is the missing noun. It is deliberately NOT a contact list copied
 * from the phone: a peer here is something that proved a key, which is the
 * only claim worth making about who is on the other end.
 */
@Serializable
data class PeerIdentity(
    /** Fingerprint of [pubKey] — the identity, never a chosen name. */
    val id: String,
    /** What the operator calls them. Renaming must never re-identify. */
    val name: String,
    val kind: Kind,
    val pubKey: String,
    /**
     * X25519 public key. Blank means a row added before messages were
     * encrypted: it can be looked at, renamed and removed, but nothing can be
     * sent to it or believed from it until a fresh invite brings a key.
     */
    val dhPubKey: String = "",
    /** Where it was last reachable — a hint, never a promise. */
    val transport: String? = null,
    val addedAtMs: Long = 0L,
    val lastSeenMs: Long = 0L,
    /** Up to when this conversation was actually looked at. */
    val readUpToMs: Long = 0L,
    /**
     * When two people read the six digits to each other and they matched.
     *
     * Zero is the honest default and it is NOT a small thing: an unverified
     * peer means a key arrived over some channel and nobody ever checked it
     * belongs to the person whose name is on the row. A roster that hides that
     * distinction is a roster that promises more than it knows.
     */
    val verifiedAtMs: Long = 0L,
    /**
     * When a key arrived that was not the key this row already held.
     *
     * The roster has always dropped verification on a key change, and the
     * screens said nothing — the row quietly lost an ornament nobody watches.
     * A substituted key IS the attack the six digits exist to catch, so the
     * moment it happens has to survive being written down and re-read.
     */
    val keyChangedAtMs: Long = 0L,
    // Which channel this key arrived by. Written by the entry point that
    // received it and never carried in PeerInvite — a peer cannot describe how
    // its own key travelled, so it cannot claim the strong one.
    val keySource: KeySource = KeySource.UNKNOWN,
    /**
     * Коли з цією людиною востаннє склався ключ зустрічі під ЦИМИ ключами.
     *
     * Єдине засвідчене свідчення в протоколі — підпис під стенограмою. Тому
     * воно й лежить на диску: тримати його лише в памʼяті означало б, що
     * досить дочекатись перезапуску застосунку, і пара знову опускається на
     * довічний ключ. Гасне разом з [verifiedAtMs], коли ключ підмінили.
     */
    val metAtMs: Long = 0L,
    /**
     * Коли доведено, що НАС тримає їхній реєстр.
     *
     * Нуль означає «ще не знаємо», а не «немає»: саме він змушує сітку
     * представитись. Лежить на диску, бо інакше кожен перезапуск слав би
     * знайомство наново тим, хто давно нас має. Гасне разом з рештою доказів,
     * коли ключ підмінили: доказ належав тим ключам, а не цим, і перевстановлений
     * телефон майже напевно втратив свій реєстр.
     */
    val introducedAtMs: Long = 0L,
    /**
     * Імʼя, під яким людина представилась, було довше за [PeerName.MAX], і в
     * [name] лежить скорочене.
     *
     * Лежить на диску поруч з іменем, бо інакше картка не мала б чим це
     * сказати: рядок з «…» на кінці сам по собі не відрізняється від імені, у
     * якому «…» набрали. Прапорець не гасне при повторному прийманні — вже
     * скорочене імʼя, пройшовши [PeerName.accept] вдруге, коротше за межу й
     * повернуло б «не скорочували».
     *
     * СТОЇТЬ ОСТАННІМ навмисно: [PeerIdentity] складають і позиційно, тож поле
     * посеред списку мовчки зʼїхало б у чужий аргумент.
     */
    val nameShortened: Boolean = false,
    /**
     * Надіслане імʼя було, але читаного в ньому не лишилось — самі невидимі чи
     * керівні знаки. У рядку стоїть коротка форма ключа; без цього прапорця
     * людина бачила б її й не знала, що імʼя взагалі надсилали.
     */
    val nameUnreadable: Boolean = false,
    /**
     * Востаннє побачений НА ЦІЙ МЕРЕЖІ — mDNS-знахідка або кадр із прямого
     * звʼязку (PeerLink), ніколи лист зі сховка.
     *
     * [lastSeenMs] ставить і сховок теж («написав хвилину тому з іншого
     * міста»), тому саме воно годиться для сортування й кольору рядка, але не
     * для слова «поруч»: сховок доводить лише що людина жива, не де вона.
     */
    val lanSeenMs: Long = 0L,

    // ── профіль, який приїхав кадром [PeerProfile] ───────────────────────
    //
    // Лежить ТУТ, а не виводиться з журналу розмови, і це рішення. Журнал
    // обрізається ([PeerOutbox.prune], 500 рядків на людину) і стирається
    // цілком («очистити розмову»), тож виведений із нього профіль зникав би
    // разом із листами — людина прибрала б переписку й побачила б, що в
    // співрозмовника «зникло» фото. Імʼя вже живе тут з тієї самої причини.
    //
    // Три стани поля з контракту тримаються типом, а не домовленістю:
    // `null` — тега в кадрі не було НІКОЛИ («ще не приїхало»), `""` — кадр ніс
    // тег нульової довжини («людина стерла це в себе»), значення — значення.
    // Кадр, що НЕ несе тега, лишає попереднє відоме на місці: контракт каже,
    // що відсутність — це «відправник на старій збірці», а не «стерто».

    /**
     * Найбільший `rev`, застосований від цієї людини. Нуль — жодного кадру.
     *
     * Лежить на диску поруч із самими полями навмисно: тримати лічильник лише
     * в памʼяті означало б, що перезапуск застосунку знову впускає будь-який
     * переказаний сіткою старий кадр, і профіль стрибав би назад.
     */
    val profileRev: Long = 0L,
    /** Base64 байтів мініатюри. WebP або JPEG — рід визначає той, хто показує. */
    val avatarThumbB64: String? = null,
    val statusEmoji: String? = null,
    val statusText: String? = null,
    val bio: String? = null,
    /** Коли кадр приїхав. Для «оновлено щойно», ніколи для порядку застосування. */
    val profileAtMs: Long = 0L,
    /** Імʼя дав власник цього телефона — імʼя з кадру профілю його не перебиває. */
    val nameByOwner: Boolean = false,
    /** @нік, який людина сама про себе заявила. Не звірений — показувати лише після довідника. */
    val claimedHandle: String? = null,
    /** Опис банера з кадру профілю ([PeerProfile.Banner.spec]); `""` — людина банер стерла, null — не повідомляла. */
    val bannerSpec: String? = null,
    /**
     * Ключ змінився в людини, яку ЗВІРЯЛИ (або вже чекала на «Продовжити»), —
     * мить зміни; нуль — паузи немає. Поки стоїть, лист цій людині не їде:
     * звірка — це обіцянка «пишу саме їй», і новий ключ її скасовує, доки
     * людина свідомо не скаже «Продовжити» або не звірить заново.
     * Невідзвірений контакт паузи не має — обіцяти не було чого, лишається
     * спокійний службовий рядок. Гасне [PeerRoster.continueAfterKeyChange] і
     * [PeerRoster.markVerified].
     */
    val keyHoldAtMs: Long = 0L,
    /**
     * Що ця людина САМА повідомила в профілі, що вміє приймати зі сцени зустрічі
     * ([PeerProfile.SCENE_V1]). Нуль — збірка до 18 або ще не повідомляла: позначки
     * картки (`PH-CST:`) їй не шлемо, бо вона побачила б їх сирим дротом.
     * Приходить лише кадром профілю, ніколи з-за слів когось третього.
     */
    val sceneLevel: Int = 0,
    /** Людина просить не показувати, коли вона була в мережі (P07). */
    val hidesSeen: Boolean = false,
) {
    enum class Kind { DEVICE, PERSON }

    // Weakest first: [PeerRoster.upsert] keeps the strongest channel one key
    // ever arrived by, so the order of these constants is load-bearing.
    //
    // CAMERA means the live camera read it off the other person's screen. No
    // one can sit in that path, which is why it — and not the six digits — is
    // what proves a key. SHARED is a code some other app handed this phone
    // (a share sheet, a phantom://invite link): nobody typed it here, and the
    // app that carried it could have carried its own key instead.
    enum class KeySource { UNKNOWN, SHARED, REMOTE, CAMERA }

    /** Presence is a fact with an age; past this it is only a memory. */
    fun isLive(nowMs: Long): Boolean =
        lastSeenMs > 0L && nowMs - lastSeenMs < PeerRoster.LIVE_WINDOW_MS

    /**
     * Genuinely «поруч»: seen on this LAN, not merely heard from somewhere.
     * A contact who just wrote through the relay is [isLive] but not this —
     * the row owes them a different word.
     */
    fun isNearby(nowMs: Long): Boolean =
        lanSeenMs > 0L && nowMs - lanSeenMs < PeerRoster.LIVE_WINDOW_MS

    val isVerified: Boolean get() = verifiedAtMs > 0L

    // The only pair that may claim the key belongs to this person. Matching
    // digits on a key that came through another app prove both phones hold the
    // SAME key and no more: the six digits are ~20 bits of a public function of
    // two published keys, so whoever owns that channel grinds their own key
    // until it shows the same six. See PeerVerificationTest.
    val isProven: Boolean get() = isVerified && keySource == KeySource.CAMERA

    /**
     * The key under this name moved, and nobody has read the new digits aloud
     * since. Ends by verifying again — never by forgetting.
     */
    val keyAlarm: Boolean get() = keyChangedAtMs > verifiedAtMs

    /** Лист цій людині чекає на «Продовжити» (див. [keyHoldAtMs]). */
    val sendHeld: Boolean get() = keyHoldAtMs > 0L && keyAlarm
}

/**
 * The roster's rules, kept pure so identity — the one thing that must not be
 * sloppy — is provable without a radio, a server or a second phone.
 */
object PeerRoster {

    /** After this long with no word, a peer stops being «зараз поруч». */
    const val LIVE_WINDOW_MS = 90_000L

    /**
     * Adds or re-adds a peer. The KEY is the identity: pairing again from a
     * new address must update the peer, never create a twin — a roster with
     * two of the same person is a roster the operator stops trusting.
     * A name the operator has chosen is never overwritten by the wire.
     */
    fun upsert(
        roster: List<PeerIdentity>,
        peer: PeerIdentity,
        // Defaulted to a real clock rather than to zero: a caller that forgets
        // it would silently lose the alarm, which is the one outcome worse
        // than not having built it.
        nowMs: Long = System.currentTimeMillis(),
        keepLocalName: Boolean = true,
    ): List<PeerIdentity> {
        val existing = roster.firstOrNull { it.id == peer.id }
            ?: return roster + named(peer)
        // An upsert that carries no encryption key is making no claim about it
        // (the paired PC, a rename); one that carries a different key is.
        val dhPubKey = peer.dhPubKey.ifBlank { existing.dhPubKey }
        val sameKeys = existing.pubKey == peer.pubKey && existing.dhPubKey == dhPubKey
        val incoming = named(peer)
        val merged = existing.copy(
            name = if (keepLocalName) existing.name else incoming.name,
            nameShortened = if (keepLocalName) {
                existing.nameShortened
            } else {
                incoming.nameShortened
            },
            nameUnreadable = if (keepLocalName) {
                existing.nameUnreadable
            } else {
                incoming.nameUnreadable
            },
            // Спарений ПК, що згодом надіслав звичайне запрошення, лишається
            // пристроєм: інакше він ставав «людиною» в розмовах.
            kind = if (existing.kind == PeerIdentity.Kind.DEVICE) existing.kind else peer.kind,
            pubKey = peer.pubKey,
            dhPubKey = dhPubKey,
            transport = peer.transport ?: existing.transport,
            lastSeenMs = maxOf(existing.lastSeenMs, peer.lastSeenMs),
            lanSeenMs = maxOf(existing.lanSeenMs, peer.lanSeenMs),
            // Re-adding somebody must not silently mark their unread as read.
            readUpToMs = maxOf(existing.readUpToMs, peer.readUpToMs),
            // A key that CHANGED has never been checked, whatever was checked
            // before. Carrying the tick across a new key is exactly how a
            // substituted key gets to wear somebody else's proof. Both keys
            // count: the six digits cover both, so either one moving means the
            // two people never read aloud what they are now holding.
            verifiedAtMs = if (sameKeys) {
                maxOf(existing.verifiedAtMs, peer.verifiedAtMs)
            } else {
                0L
            },
            // Losing the tick is not a signal — nothing was showing it. The
            // instant is, and it outlives the process that noticed.
            keyChangedAtMs = if (sameKeys) existing.keyChangedAtMs else nowMs,
            // Пауза — лише коли було що обіцяти: звірений контакт, або той, що
            // ще чекав на «Продовжити» після попередньої зміни.
            keyHoldAtMs = when {
                sameKeys -> existing.keyHoldAtMs
                existing.isVerified || existing.sendHeld -> nowMs
                else -> 0L
            },
            // Same key seen again over a better channel is an upgrade — meeting
            // and scanning someone you first pasted is exactly the advice the
            // screens give. A different key keeps only its own channel, and a
            // silent upsert (a beacon, a rename) claims nothing.
            keySource = if (sameKeys) {
                maxOf(existing.keySource, peer.keySource)
            } else {
                peer.keySource
            },
            // Зустріч доводила ті ключі, не ці. Перенести її на підмінений
            // ключ означало б захистити підміну тим самим правилом, яке має
            // її ловити.
            metAtMs = if (sameKeys) maxOf(existing.metAtMs, peer.metAtMs) else 0L,
            introducedAtMs = if (sameKeys) {
                maxOf(existing.introducedAtMs, peer.introducedAtMs)
            } else {
                0L
            },
        )
        return roster.map { if (it.id == peer.id) merged else it }
    }

    /**
     * Спарений ПК — мій пристрій, а не людина. Імʼя з його запрошення («ПК ·
     * PHANTOM OS») поступається моєму, а те, що дав сам власник, — ні.
     */
    fun adoptOwnDevice(
        roster: List<PeerIdentity>,
        peer: PeerIdentity,
        nowMs: Long = System.currentTimeMillis(),
    ): List<PeerIdentity> {
        val existing = roster.firstOrNull { it.id == peer.id }
        val keepName = existing != null &&
            (existing.kind == PeerIdentity.Kind.DEVICE || existing.nameByOwner)
        return upsert(roster, peer.copy(kind = PeerIdentity.Kind.DEVICE), nowMs, keepLocalName = keepName)
    }

    /** Відновлення реєстру з резервної копії: приводить імена до канонічного вигляду. */
    fun adoptBackup(backup: List<PeerIdentity>): List<PeerIdentity> =
        backup.map { named(it) }

    /** Власник сказав, що це його пристрій (або ні). Дріт цього не міняє ніколи. */
    fun setKind(roster: List<PeerIdentity>, id: String, kind: PeerIdentity.Kind): List<PeerIdentity> =
        roster.map { if (it.id == id) it.copy(kind = kind) else it }

    /** Ключ зустрічі склався. Ставить лише засвідчений підпис, ніколи не екран. */
    fun markMet(roster: List<PeerIdentity>, id: String, atMs: Long): List<PeerIdentity> =
        roster.map { if (it.id == id) it.copy(metAtMs = atMs) else it }

    /** Доведено, що їхній реєстр нас має. Ставить лише кадр, ніколи не екран. */
    fun markIntroduced(roster: List<PeerIdentity>, id: String, atMs: Long): List<PeerIdentity> =
        roster.map { if (it.id == id) it.copy(introducedAtMs = atMs) else it }

    /**
     * The number matched. Set by a person saying so — or by the camera reading
     * it off the other screen, the one caller allowed to name its channel:
     * [source] can only ever raise [PeerIdentity.keySource], never lower it.
     */
    fun markVerified(
        roster: List<PeerIdentity>,
        id: String,
        atMs: Long,
        source: PeerIdentity.KeySource? = null,
    ): List<PeerIdentity> =
        roster.map {
            if (it.id == id) {
                it.copy(
                    verifiedAtMs = atMs,
                    keySource = if (source == null) it.keySource else maxOf(it.keySource, source),
                    keyHoldAtMs = 0L,
                )
            } else {
                it
            }
        }

    /** Withdrawn — the operator no longer stands behind that key. */
    /**
     * «Продовжити» після зміни ключа: людина знає, що ключ новий, і пише далі
     * без звірки. Тривога лишається (контакт не звірений), пауза — ні.
     */
    fun continueAfterKeyChange(roster: List<PeerIdentity>, id: String): List<PeerIdentity> =
        roster.map { if (it.id == id) it.copy(keyHoldAtMs = 0L) else it }

    fun clearVerified(roster: List<PeerIdentity>, id: String): List<PeerIdentity> =
        roster.map { if (it.id == id) it.copy(verifiedAtMs = 0L) else it }

    fun remove(roster: List<PeerIdentity>, id: String): List<PeerIdentity> =
        roster.filterNot { it.id == id }

    fun rename(roster: List<PeerIdentity>, id: String, name: String): List<PeerIdentity> =
        roster.map {
            if (it.id == id) {
                val accepted = accept(name, id)
                // Прапорець ставиться НАНОВО, а не додається до старого: власне
                // імʼя, яке оператор дав сам, більше нічого не приховує, і
                // картка не має далі казати «показано скорочено».
                // Перейменування рукою знімає обидві позначки: людина назвала
                // запис сама, тож ні «скорочено», ні «не читається» вже не про
                // те, що вона бачить.
                it.copy(
                    name = accepted.name,
                    nameShortened = accepted.shortened,
                    nameUnreadable = false,
                    nameByOwner = name.isNotBlank(),
                )
            } else {
                it
            }
        }

    /**
     * Заглушка, яку колишній веб сам записував у реєстр рукою «власника»
     * («Новий контакт», іноді з датою: «Новий контакт · 27 вер., 16:04»).
     */
    private val LEGACY_PLACEHOLDER = Regex("^новий контакт(\\s*·.*)?$", RegexOption.IGNORE_CASE)

    fun isLegacyPlaceholder(name: String): Boolean = LEGACY_PLACEHOLDER.matches(name.trim())

    /**
     * Одноразове лікування реєстру: заглушка «Новий контакт» стоїть із
     * [PeerIdentity.nameByOwner], і через цей прапорець [absorbProfile]
     * не пускав справжнє імʼя людини — контакт лишався «Без імені» назавжди
     * (A52, 02.10). Власник цього імені не давав, тож і прапорець не його.
     *
     * [PeerIdentity.profileRev] скидається теж: кадр, у якого імʼя відкинули
     * через заглушку, уже записано як застосований, і без скидання той самий
     * кадр, переказаний ще раз, лишився б «повтором».
     *
     * Повертає той САМИЙ список, коли лікувати нічого, — за цим викликач
     * знає, що писати на диск не треба.
     */
    fun releaseLegacyPlaceholders(roster: List<PeerIdentity>): List<PeerIdentity> {
        if (roster.none { it.nameByOwner && isLegacyPlaceholder(it.name) }) return roster
        return roster.map {
            if (it.nameByOwner && isLegacyPlaceholder(it.name)) it.copy(nameByOwner = false, profileRev = 0L) else it
        }
    }

    fun sawIt(roster: List<PeerIdentity>, id: String, nowMs: Long): List<PeerIdentity> =
        roster.map { if (it.id == id) it.copy(lastSeenMs = nowMs) else it }

    /**
     * Кадр [PeerProfile] від людини, яка вже в реєстрі.
     *
     * [fromId] береться з конверта, перевіреного підписом. Покластись на щось
     * усередині тіла означало б дозволити одному контакту переписати картку
     * іншого — рівно те, від чого захищає підпис.
     *
     * Незнайомець мовчки відкидається: профіль того, кого ми не тримаємо, нема
     * куди покласти, а заводити рядок реєстру з кадру профілю означало б, що
     * будь-хто, хто дізнався наш ідентифікатор, зʼявляється в «Людях» з фото.
     *
     * Повертає той САМИЙ список, коли нічого не змінилось, — і саме за цим
     * викликач відрізняє «оновлення застосовано» від «повтор переказу».
     */
    fun absorbProfile(
        roster: List<PeerIdentity>,
        fromId: String,
        body: String,
        nowMs: Long,
    ): List<PeerIdentity> {
        if (fromId.isBlank()) return roster
        val frame = PeerProfile.decode(body) ?: return roster
        val held = roster.firstOrNull { it.id == fromId } ?: return roster
        val knownRev = held.profileRev.takeIf { it > 0L }
        if (!PeerProfile.isNewer(frame.rev, knownRev)) return roster
        val announced = (frame.name as? PeerProfile.Field.Set)?.value
            ?.takeUnless { held.nameByOwner }
            ?.let(PeerName::accept)
            ?.takeIf { it.name.isNotBlank() }
        val merged = held.copy(
            name = announced?.name ?: held.name,
            nameShortened = announced?.shortened ?: held.nameShortened,
            nameUnreadable = if (announced != null) false else held.nameUnreadable,
            profileRev = frame.rev,
            profileAtMs = nowMs,
            avatarThumbB64 = frame.avatar.merge(held.avatarThumbB64) {
                java.util.Base64.getEncoder().encodeToString(it)
            },
            statusEmoji = frame.statusEmoji.merge(held.statusEmoji) { it },
            statusText = frame.statusText.merge(held.statusText) { it },
            bio = frame.bio.merge(held.bio) { it },
            claimedHandle = frame.handle.merge(held.claimedHandle) { it },
            bannerSpec = frame.banner.merge(held.bannerSpec) { it.spec() },
            // Кадр без тега — «не повідомляв» (0), не «лишається, як було»: людина
            // могла повернутись на стару збірку.
            sceneLevel = frame.scene,
            hidesSeen = frame.hidesSeen,
        )
        return roster.map { if (it.id == fromId) merged else it }
    }

    /**
     * Три стани кадру проти того, що вже знаємо.
     *
     * Відсутнє поле лишає попереднє: контракт каже, що тега немає в кадрів
     * старої збірки, а не в людини, яка щось стерла. Очищене ставить `""` —
     * порожнє, але ВІДОМЕ, і саме цим воно відрізняється від `null`.
     */
    private inline fun <T> PeerProfile.Field<T>.merge(
        held: String?,
        encode: (T) -> String,
    ): String? = when (this) {
        is PeerProfile.Field.Absent -> held
        is PeerProfile.Field.Cleared -> ""
        is PeerProfile.Field.Set -> encode(value)
    }

    /** Same fact as [sawIt], plus proof it came off this LAN — see [PeerIdentity.isNearby]. */
    fun sawNearby(roster: List<PeerIdentity>, id: String, nowMs: Long): List<PeerIdentity> =
        roster.map { if (it.id == id) it.copy(lastSeenMs = nowMs, lanSeenMs = nowMs) else it }

    /**
     * The conversation was read up to [uptoMs]. Monotonic on purpose: opening
     * an old chat, then a newer one, must not un-read the newer one.
     */
    fun markRead(roster: List<PeerIdentity>, id: String, uptoMs: Long): List<PeerIdentity> =
        roster.map {
            if (it.id == id) it.copy(readUpToMs = maxOf(it.readUpToMs, uptoMs)) else it
        }

    /**
     * Only the people. The paired PC leaves a proved key in the roster too, but
     * it is not somebody you write to — its place is the pairing surface, not
     * «Люди». Kept here so the list, its search and its unread all agree.
     */
    fun people(roster: List<PeerIdentity>): List<PeerIdentity> =
        roster.filter { it.kind == PeerIdentity.Kind.PERSON }

    /** Live first, then most recently heard from, then by name. */
    fun ordered(roster: List<PeerIdentity>, nowMs: Long): List<PeerIdentity> =
        roster.sortedWith(
            compareByDescending<PeerIdentity> { it.isLive(nowMs) }
                .thenByDescending { it.lastSeenMs }
                .thenBy { it.name.lowercase() },
        )

    fun search(roster: List<PeerIdentity>, query: String): List<PeerIdentity> {
        val q = query.trim()
        if (q.isEmpty()) return roster
        return roster.filter { it.name.contains(q, ignoreCase = true) }
    }

    /**
     * A name that is blank, or is just the fingerprint again, is no name; the
     * roster shows a short form of the key instead so the row is still a row.
     *
     * Імʼя приходить з чужого пристрою, тож межа й вичищення — [PeerName.accept],
     * одні на весь продукт. Коли після вичищення не лишилось нічого (рядок був
     * порожній або складався з самих керівних символів), рядок дістає коротку
     * форму ключа — і тоді нічого не скорочували, бо не було чого.
     */
    private fun accept(name: String, id: String): PeerName.Accepted {
        val accepted = PeerName.accept(name)
        return if (accepted.name.isBlank()) {
            PeerName.Accepted(shortId(id), false, unreadable = accepted.unreadable)
        } else {
            accepted
        }
    }

    /**
     * Запис, приведений до показу. Прапорець складається з тим, що вже несе
     * запис: імʼя, скорочене раніше (у [PeerIntro] на мережі), удруге вже
     * коротше за межу й саме по собі повернуло б «не скорочували».
     */
    private fun named(peer: PeerIdentity): PeerIdentity {
        val accepted = accept(peer.name, peer.id)
        return peer.copy(
            name = accepted.name,
            nameShortened = accepted.shortened || peer.nameShortened,
            nameUnreadable = accepted.unreadable || peer.nameUnreadable,
        )
    }

    /** «a1b2·c3d4» — enough for a human to compare two keys out loud. */
    fun shortId(id: String): String {
        val bare = id.filter { it.isLetterOrDigit() }.lowercase()
        if (bare.length < 8) return bare.ifBlank { "?" }
        return "${bare.take(4)}·${bare.drop(4).take(4)}"
    }
}
