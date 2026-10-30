package local.phantom.companion.core.net.peer

/**
 * How a second person comes to exist.
 *
 * Pairing with the operator's own node can lean on a QR the operator physically
 * holds. Two people cannot: they are in a corridor, one of them reads something
 * out, and whatever they exchange has to survive being typed wrong.
 *
 * So an invite is two things, and the split matters. The long part carries the
 * keys and is meant to be scanned or pasted — nobody types 64 bytes. The short
 * part is derived from those keys and is what two people compare. Six digits
 * were that part for a year and are ~20 bits — a substituted key is ground to
 * them in seconds (PeerVerificationTest does it). They stay for stored rows and
 * the tests; what people compare now is [fingerprint], and on a card the two
 * fingerprints of a pair sit side by side as [PeerSafety].
 *
 * Two keys travel, not one. The Ed25519 key proves who said a thing; the
 * X25519 key is what makes the thing unreadable to everyone else on the LAN.
 * An invite carrying only the first can be paired with and can never be
 * whispered to, so the old format is refused rather than half-honoured.
 */
object PeerInvite {

    const val PREFIX = "PH2"

    /** The pre-encryption format. Recognised only so it can be refused aloud. */
    const val LEGACY_PREFIX = "PH1"

    private const val SEP = ':'
    private const val FIELD = '|'

    /**
     * Скільки взагалі читається з поданого рядка.
     *
     * Межа на РОБОТУ, не на зміст: код, довший за це, не влазить ані в QR, ані
     * в жоден чесний сценарій. Рахунок згори: імʼя — 64 графеми, і навіть у
     * найоздобленішому вигляді (сімʼя з емодзі з відтінками шкіри, ~41 байт на
     * знак) це ~2,6 КБ; плюс два ключі по 44, відбиток, адреса, порт — і все це
     * в base64 (×4/3) дає близько 3,7 КБ. Чотири тисячі — стеля з запасом, за
     * якою починається вже не запрошення.
     *
     * Стоїть ПЕРЕД розбором: рядок складає чужий пристрій, і платити за нього
     * розкодуванням base64 на десять кілобайтів немає за що.
     */
    const val MAX_CODE = 4096

    /** The manifest already accepts VIEW on this; nothing ever produced one. */
    const val LINK_SCHEME = "phantom"
    const val LINK_HOST = "invite"

    data class Invite(
        val peerId: String,
        val name: String,
        val pubKey: String,
        /** X25519 public key — without it nothing can be sent to this peer. */
        val dhPubKey: String,
        val host: String,
        val port: Int,
        /**
         * Назване імʼя було довше за [PeerName.MAX] — у [name] скорочене.
         *
         * Останній у списку й з умовчанням: [Invite] складають позиційно і в
         * коді, і в зліпках тестів.
         */
        val nameShortened: Boolean = false,

        /**
         * Надіслане імʼя було, а читаного в ньому не лишилось — у [name] стоїть
         * коротка форма ключа.
         *
         * **Рахується локально, на прийманні, і в дротовий рядок НЕ їде.**
         * Формат PH2 за десять днів до бети не міняємо: нове поле розсинхронило
         * б телефони різних збірок. [encode] перелічує поля явно, тож це
         * умовчання він просто не бачить.
         *
         * Без нього факт гинув у дорозі: `decode` уже очистив імʼя, а
         * `PeerRoster.named()` викликає `PeerName.accept` вдруге — на порожньому
         * рядку, який нічого не памʼятає про триста невидимих знаків, що в ньому
         * були. Повторне приймання не відновлює факт, воно може лише його
         * перенести. Вимір 04.09: 300 × U+200B — категорія Cf, тож `isBlank()`
         * false, і правило «порожнє прапорця не піднімає» тут ні до чого.
         */
        val nameUnreadable: Boolean = false,
    )

    /**
     * Three answers, never two. «Not an invite» and «an invite from a build
     * that cannot encrypt» need different sentences on screen: the first is
     * somebody else's link, the second is a person standing next to you whose
     * phone needs updating.
     */
    sealed interface Decoded {
        data class Ph2(val invite: Invite) : Decoded
        data object LegacyPh1 : Decoded

        /**
         * Код нашого ж компʼютера, а не людини.
         *
         * Скло ПК склеює `phantom://invite/` з base64url ДВІЙКОВОГО конверта:
         * перші три байти `PHI`, далі версія, довжини й ключ. Двокрапок там
         * немає, тож розбір давав `parts.size == 2` і телефон казав «код
         * запрошення неповний — попроси надіслати ще раз». Код не був
         * неповним: він цілий і з нашого ж ПК, лише іншого роду, і людина
         * йшла просити переслати те саме.
         *
         * ПК уже відповідає симетрично на телефонний код («це код із
         * телефона…»), тепер відповідає й телефон. Конверт при цьому НЕ стає
         * контактом: іншої дороги ПК↔телефон повз доказ спарування не існує,
         * і `PcEnvelope` навмисно не несе жодного поля — його нічим прийняти.
         */
        data object PcEnvelope : Decoded

        data object NotInvite : Decoded
    }

    /**
     * Немає порту — немає адреси.
     *
     * Телефон слухає не завжди: доки [PeerLink] не підняв сокет, порт у
     * запрошенні нуль, і адреса поруч із ним нікуди не веде — перший лист
     * однаково піде маяком у спільній мережі або через сховок. Але сама вона
     * при цьому називає кожному, кому показали QR, мережу, у якій людина
     * зараз стоїть: на склі 04.09 у коді A52 стояла зовнішня IP апарата й нуль
     * після неї. Тому host їде РАЗОМ із живим портом, а не про запас.
     *
     * Зріз тут, а не у викликачів: код друкують QR, посилання, «поділитись» і
     * міст у веб, і правило, розкладене на чотири поверхні, тримається доти,
     * доки не зʼявиться пʼята.
     */
    fun encode(invite: Invite): String {
        val reachable = invite.port in 1..65_535
        val payload = buildString {
            append(invite.peerId).append(FIELD)
            append(PeerName.cap(invite.name).replace(FIELD, ' ')).append(FIELD)
            append(invite.pubKey).append(FIELD)
            append(invite.dhPubKey).append(FIELD)
            append(if (reachable) invite.host else "").append(FIELD)
            append(invite.port)
        }
        val body = b64(payload.toByteArray(Charsets.UTF_8))
        return "$PREFIX$SEP$body$SEP${checksum(body)}"
    }

    /**
     * «host:port», або нічого — і поверхня мусить сказати це словом.
     *
     * Порожньо тут означає рівно «прямої адреси немає, доставка через сховок»,
     * а не «щось пішло не так»: це нормальний стан телефона, який нічого не
     * слухає.
     */
    /**
     * Чи ця адреса приватна — тобто ззовні за нею нікого.
     *
     * Поверхні підписували `host:port` як «так вас видно ззовні». Для
     * 10.0.2.15 (NAT емулятора), 192.168.x, 100.64/10 (CGNAT оператора) і
     * решти цього переліку це неправда: адреса чинна лише всередині тієї ж
     * мережі. Заяви про видимість не робить ані міст, ані веб — вони читають
     * цей факт.
     *
     * `null` — адреси взагалі немає; це не «приватна» і не «публічна».
     */
    fun isLocalAddress(invite: Invite): Boolean? {
        val host = invite.host.trim().lowercase().trim('[', ']')
        if (host.isBlank()) return null
        if (host == "localhost" || host.endsWith(".local")) return true
        if (host.startsWith("fc") || host.startsWith("fd")) return true // IPv6 ULA
        if (host == "::1") return true
        val o = host.split('.').mapNotNull { it.toIntOrNull() }
        if (o.size != 4) return null // імʼя, не адреса — судити нема про що
        return when {
            o[0] == 10 -> true
            o[0] == 127 -> true
            o[0] == 172 && o[1] in 16..31 -> true
            o[0] == 192 && o[1] == 168 -> true
            o[0] == 169 && o[1] == 254 -> true
            o[0] == 100 && o[1] in 64..127 -> true
            else -> false
        }
    }

    fun address(invite: Invite): String? {
        val host = invite.host.trim()
        if (host.isBlank() || invite.port !in 1..65_535) return null
        // IPv6 без дужок зливається з портом у рядок, де двокрапок більше, ніж
        // значень, і жоден бік не скаже, де кінчається адреса.
        return if (host.count { it == ':' } > 1) "[$host]:${invite.port}" else "$host:${invite.port}"
    }

    /**
     * Anything that is not exactly a PH2 invite decodes to something that is
     * not an [Invite]. A code that was mistyped, truncated by a messenger, or
     * is simply another app's string must not half-parse into a peer with a
     * broken key — a roster entry that can never be reached is worse than an
     * error the operator can see.
     *
     * There is deliberately no path from [Decoded.LegacyPh1] to an [Invite]:
     * a downgrade that cannot be expressed cannot be forced.
     *
     * Порт нуль — не помилка й не відмова. Доти, доки [encode] клав адресу
     * завжди, нуль означав «зіпсутий код», і дверей було двоє: сюди — лише з
     * адресою, у довідник — і без неї. Тепер безадресна форма — це те, що
     * друкує сам телефон, тож двері одні, і відмовляти в них означало б
     * відмовляти власним кодам, і тим, що вже роздані з IP і нулем після неї.
     * Відмова «запис довідника несе адресу» від цього нікуди не поділась — у
     * неї своє слово, і живе вона в [PeerDirectory.verify].
     */
    /**
     * Чи це двійковий конверт ПК: base64url, перші три байти — `PHI`.
     *
     * Свідомо ЛИШЕ впізнавання, без розбору: далі нічого не читається й
     * нікуди не потрапляє. Мутація «дай decode прийняти конверт як
     * запрошення» мусить валити сторожа не за текстом, а за тим, що чужий
     * конверт узагалі пройшов.
     */
    private fun looksLikePcEnvelope(code: String): Boolean {
        if (code.length < 4 || code.length > MAX_CODE) return false
        val head = code.take(8).filter { it != '=' }
        val decoded = runCatching {
            java.util.Base64.getUrlDecoder().decode(head.padEnd((head.length + 3) / 4 * 4, 'A'))
        }.getOrNull() ?: return false
        return decoded.size >= 3 &&
            decoded[0] == 'P'.code.toByte() &&
            decoded[1] == 'H'.code.toByte() &&
            decoded[2] == 'I'.code.toByte()
    }

    fun decode(text: String?): Decoded {
        val code = text?.trim() ?: return Decoded.NotInvite
        if (code.length > MAX_CODE) return Decoded.NotInvite
        val parts = code.split(SEP)
        // Чужий рід упізнаємо ДО того, як казати «неповний»: конверт ПК не має
        // двокрапок і починається байтами «PHI» під base64url.
        if (parts.size != 3) return if (looksLikePcEnvelope(code)) Decoded.PcEnvelope else Decoded.NotInvite
        if (parts[0] == LEGACY_PREFIX) return Decoded.LegacyPh1
        if (parts[0] != PREFIX) return Decoded.NotInvite
        val body = parts[1]
        if (checksum(body) != parts[2].lowercase()) return Decoded.NotInvite
        val raw = runCatching { String(unB64(body), Charsets.UTF_8) }.getOrNull()
            ?: return Decoded.NotInvite
        val f = raw.split(FIELD)
        if (f.size != 6) return Decoded.NotInvite
        val port = f[5].toIntOrNull() ?: return Decoded.NotInvite
        if (port !in 0..65_535) return Decoded.NotInvite
        if (f[0].isBlank() || f[2].isBlank() || f[3].isBlank()) return Decoded.NotInvite
        // Імʼя з ЧУЖОГО пристрою: межа при кодуванні обмежувала лише нас, а
        // рядок сюди подає той бік. Саме звідси 04.09 на A52 приїхало імʼя на
        // 45 знаків, яке ростер далі мовчки зрізав до 40.
        val name = PeerName.accept(f[1])
        return Decoded.Ph2(
            Invite(
                peerId = f[0],
                name = name.name.ifBlank { PeerRoster.shortId(f[0]) },
                pubKey = f[2],
                dhPubKey = f[3],
                host = f[4],
                port = port,
                nameShortened = name.shortened,
                nameUnreadable = name.unreadable,
            ),
        )
    }

    /**
     * Стеля конверта, який їде ВСЕРЕДИНІ чогось іншого.
     *
     * Те саме число, що на сервері довідника (`directory.py:86 INVITE_MAX`), і
     * навмисно те саме: конверт, який довідник прийме, а гілка відкине, розвів
     * би дві правди про один і той самий рядок. [MAX_CODE] ширший — він про
     * код, який людина ВСТАВЛЯЄ, і там 4 КБ ще не абсурд.
     */
    const val ENVELOPE_MAX = 512

    /**
     * Безадресний конверт — і чому це окрема перевірка, а не «просто decode».
     *
     * Ця форма їде двома різними дорогами: записом довідника й полем акту
     * складу гілки. Обидві мусять відмовляти ОДНАКОВО, бо обидві кладуть чужий
     * ключ у реєстр цього телефона. Доки перевірка жила всередині
     * [PeerDirectory.verify], друга дорога мусила б її переписати — і переписала
     * б з дрібною розбіжністю, якої ніхто не побачив би, поки нею не проїхала б
     * адреса.
     *
     * Що тут НЕ перевіряється: підпис під реченням публікації. Він накриває хеш
     * імені, якого в гілці немає — там конверт засвідчує підпис ЛИСТА, що його
     * привіз ([PeerSealing]), тобто той, хто додав. Тому й ключ звідси лягає
     * [PeerIdentity.KeySource.REMOTE], а не звіреним.
     */
    sealed interface Addressless {
        data class Ok(val invite: Invite) : Addressless

        /** Не конверт PH2: не той початок, збита контрольна сума, обрізаний. */
        data object NotInvite : Addressless

        data object TooLong : Addressless

        /** Конверт несе адресу — ані довідник, ані гілка її не возять. */
        data object HasAddress : Addressless

        /** Конверт про іншого: заявлений id не той, що в самому конверті. */
        data object WrongId : Addressless
    }

    /**
     * [expectId] порожній означає «кого — ще не знаємо» (так питає довідник:
     * там особу називає хеш імені, а не викликач). Гілка знає завжди, і саме
     * тому там ця звірка не декорація: акт «додай X» із конвертом Y поклав би в
     * реєстр ключ під чужим ідентифікатором.
     */
    fun addressless(code: String, expectId: String = "", maxChars: Int = ENVELOPE_MAX): Addressless {
        if (code.length > maxChars) return Addressless.TooLong
        val invite = when (val decoded = decode(code)) {
            is Decoded.Ph2 -> decoded.invite
            else -> return Addressless.NotInvite
        }
        if (invite.host.isNotBlank() || invite.port != 0) return Addressless.HasAddress
        if (expectId.isNotBlank() && invite.peerId != expectId) return Addressless.WrongId
        return Addressless.Ok(invite)
    }

    /**
     * Другий бік [addressless]: конверт, придатний їхати ВСЕРЕДИНІ чогось.
     *
     * Зріз тут, а не у викликачів. Довідник свій зріз уже має
     * ([PeerDirectory.addressless]) — він працює зі значенням, а не з рядком,
     * бо підписує саме значення; звірку того, що обидва ріжуть однаково, тримає
     * тест. Гілці потрібен рядок, і без цього місця кожна з двох дорог різала б
     * по-своєму: одна забула б порт, друга — імʼя, і жодна з розбіжностей не
     * мала б симптому, поки нею не проїхала б адреса чужої мережі.
     */
    fun envelopeOf(invite: Invite): String =
        encode(invite.copy(host = "", port = 0, name = ""))

    /**
     * The same code in a form a messenger renders as something to tap.
     *
     * The only remote way to add somebody today is: copy the raw code, send it
     * through some other app, and hope the recipient finds «встав код людини».
     * The deep-link HANDLER has shipped all along — this is the missing half.
     *
     * Every character [encode] emits is base64url, hex or ':', all legal in a
     * path segment, so nothing here needs escaping and nothing gets mangled by
     * a messenger that linkifies text.
     */
    fun link(invite: Invite): String = "$LINK_SCHEME://$LINK_HOST/${encode(invite)}"

    const val WEB_HOST = "phantom-os.dev"
    const val WEB_PATH = "/i/"

    // The code rides in the fragment: a browser never sends it to the site.
    fun webLink(invite: Invite): String = "https://$WEB_HOST$WEB_PATH#${encode(invite)}"

    fun fromLink(url: String?): Invite? =
        (decode(codeInLink(url)) as? Decoded.Ph2)?.invite

    /** Our site's invite address, with or without a code after `#`. */
    fun isWebLink(url: String?): Boolean = siteUri(url) != null

    private fun siteUri(url: String?): java.net.URI? {
        val uri = runCatching { java.net.URI(url?.trim().orEmpty()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        // Authority, not host: a `user@` or `:port` around our name is not our site.
        if (!uri.rawAuthority.equals(WEB_HOST, ignoreCase = true)) return null
        return uri.takeIf { it.rawPath == WEB_PATH || it.rawPath == WEB_PATH.trimEnd('/') }
    }

    /** The code out of a link, or null for a URL that is not one of ours. */
    fun codeInLink(url: String?): String? {
        siteUri(url)?.let { return it.fragment?.trim()?.takeIf(String::isNotEmpty) }
        val text = url?.trim().orEmpty()
        // Карбуємо схему продукту, але debug-збірка ловить із маніфесту ще й
        // власну — і посилання з рідного сповіщення читається тут же. Схему
        // беремо з самого рядка (anyScheme), а не з однієї зашитої назви.
        val anyScheme = Regex("""^[A-Za-z][A-Za-z0-9+.-]*://${Regex.escape(LINK_HOST)}/""")
        val head = anyScheme.find(text) ?: return null
        val scheme = text.substringBefore("://")
        if (scheme != LINK_SCHEME && !scheme.startsWith("$LINK_SCHEME-")) return null
        return text.removePrefix(head.value).trimStart('/').takeIf { it.isNotBlank() }
    }

    /**
     * The code inside whatever a messenger delivered. An invite travels as a
     * whole introduction message — a sentence, a link, a signature — and the
     * person receiving it forwards or pastes ALL of it, not a surgically
     * clean code. Only spans that actually decode count: a PH2 that lost its
     * tail to truncation stays invisible here, so the caller's own «неповний
     * код» path keeps telling that truth.
     */
    fun findInText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val whole = text.trim()
        if (decode(whole) !is Decoded.NotInvite) return whole
        return IN_TEXT.findAll(text)
            .map { it.value }
            .firstOrNull { decode(it) !is Decoded.NotInvite }
    }

    // [0-9a-fA-F]: decode already lowercases the checksum, the net may not.
    private val IN_TEXT = Regex("PH[0-9]$SEP[A-Za-z0-9_-]+$SEP[0-9a-fA-F]{6}")

    /**
     * The short code: six digits of the digest over BOTH keys. Kept because
     * stored rows and tests speak it — not because it defends anything. It is
     * ~20 bits, and whoever carried the invite grinds a substitute encryption
     * key to the same six in about a million tries. [fingerprint] is the same
     * digest at a length nobody grinds.
     *
     * Covering the X25519 key is not decoration even here: a middleman who
     * passes the signing key through untouched and swaps only the encryption
     * key reads every message. There is no single-key overload on purpose.
     */
    fun verificationCode(pubKey: String, dhPubKey: String): String {
        val digest = digest(pubKey, dhPubKey)
        var value = 0L
        for (i in 0 until 5) value = (value shl 8) or (digest[i].toLong() and 0xFF)
        return (value % 1_000_000L).toString().padStart(6, '0')
    }

    /** Grouped for reading aloud: «482 913». */
    fun spokenCode(pubKey: String, dhPubKey: String): String =
        verificationCode(pubKey, dhPubKey).let { "${it.take(3)} ${it.drop(3)}" }

    const val FINGERPRINT_DIGITS = 24

    // Twelve bytes folded into 24 digits: ~80 bits, against the six digits'
    // ~20. Twelve rather than ten so the fold stays close to uniform.
    private const val FINGERPRINT_BYTES = 12

    /**
     * The long form of the same digest — what two people actually compare.
     * Digits, not base32: they are read aloud in Ukrainian, and a digit has
     * one name in every language while «Q» and «W» do not.
     */
    fun fingerprint(pubKey: String, dhPubKey: String): String {
        val digest = digest(pubKey, dhPubKey)
        val value = java.math.BigInteger(1, digest.copyOf(FINGERPRINT_BYTES))
        return value.mod(java.math.BigInteger.TEN.pow(FINGERPRINT_DIGITS))
            .toString().padStart(FINGERPRINT_DIGITS, '0')
    }

    /** «4829 1375 0028 3391 7745 0012» — six groups the eye can hold. */
    fun spokenFingerprint(pubKey: String, dhPubKey: String): String =
        fingerprint(pubKey, dhPubKey).chunked(4).joinToString(" ")

    private fun digest(pubKey: String, dhPubKey: String): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest((pubKey.trim() + "|" + dhPubKey.trim()).toByteArray(Charsets.UTF_8))

    private fun checksum(body: String): String {
        var hash = 0x811C9DC5L
        for (c in body) {
            hash = hash xor c.code.toLong()
            hash = (hash * 16_777_619L) and 0xFFFFFFFFL
        }
        return hash.toString(16).padStart(8, '0').takeLast(6)
    }

    private fun b64(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun unB64(text: String): ByteArray =
        java.util.Base64.getUrlDecoder().decode(text)
}
