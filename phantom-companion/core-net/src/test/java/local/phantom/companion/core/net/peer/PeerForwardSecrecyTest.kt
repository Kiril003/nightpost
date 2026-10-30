package local.phantom.companion.core.net.peer

import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.Hkdf
import local.phantom.companion.core.net.crypto.X25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Що дає захоплений телефон тому, хто ще й записував мережу.
 *
 * Це не опис ризику, а сам ризик, виконаний. Хто пише сюди тест — грає за
 * слідчого: він має рівно те, що дає вилучений телефон (приватний X25519 із
 * `phantom_identity_v1`) і рівно те, що видно збоку (реєстр і кадри, зняті в
 * тій самій мережі — відкритій, чужому роутері або хотспоті з паролем, який
 * знає десяток людей).
 *
 * Ключ пари виводиться з двох ДОВГИХ ключів, тож він не «зберігається» —
 * він рахується наново будь-коли. Через це захоплення одного телефона
 * розшифровує не лише те, що на ньому лежить, а весь записаний трафік пари
 * назад у часі: і те, що обидва вже стерли, і те, чого цей телефон ніколи не
 * зберігав.
 *
 * Ці випадки мусять лишатись зеленими, доки живий шлях v3 — він лишається для
 * співрозмовників зі старою збіркою, і це і є чесна ціна сумісності. Мірою
 * того, що зроблено, є [`ключі зустрічі`][PeerSessionTest] і дзеркальні
 * випадки в кінці цього файлу.
 */
class PeerForwardSecrecyTest {

    /** Телефон, як його бачить [local.phantom.companion.data.DeviceIdentityStore]. */
    private class Phone(val id: String) {
        /** Викарбувано раз при першому запуску — і більше ніколи. */
        val signing = Ed25519.generateKeypair()
        val dh = X25519.generateKeypair()
        val dhPubB64: String = b64(dh.publicKey)
        val pubB64: String = b64(signing.publicKey)
    }

    private val marta = Phone("marta-fp")
    private val kyrylo = Phone("kyrylo-fp")

    /** Те, що бачить і зберігає той, хто просто сидить у тій самій мережі. */
    private fun record(count: Int, channel: ByteArray): List<Pair<String, String>> =
        (1..count).map { n ->
            val message = PeerSealing.seal(
                PeerMessage(
                    id = "m-$n",
                    fromId = marta.id,
                    toId = kyrylo.id,
                    body = "зустріч $n о 07:$n, PH3-RECORDED-$n",
                    sentAtMs = 1_700_000_000_000L + n * 86_400_000L,
                ),
                marta.signing.privateKey,
            )
            val sealed = PeerEnvelope.seal(message, channel)!!
            message.body to sealed.frame
        }

    private fun channel(): ByteArray =
        PeerChannel.derive(marta.dh.privateKey, kyrylo.dhPubB64, marta.id, kyrylo.id)!!

    @Test
    fun `a captured phone opens every frame the pair ever sent`() {
        val recorded = record(40, channel())

        // Захоплення. Усе, що дістає слідчий: приватний ключ шифрування з
        // цього телефона й публічний ключ співрозмовника з реєстру. Жодного
        // кадру, жодної підказки з розмови.
        val stolenPrivate = kyrylo.dh.privateKey
        val fromRoster = marta.dhPubB64

        val recomputed = PeerChannel.derive(stolenPrivate, fromRoster, kyrylo.id, marta.id)
        assertNotNull("ключ пари виводиться наново з украдених ключів", recomputed)

        val opened = recorded.mapNotNull { (_, frame) ->
            PeerEnvelope.open(frame) { recomputed }?.body
        }

        assertEquals(
            "кожен записаний кадр відкрився ключем, порахованим після захоплення",
            recorded.map { it.first },
            opened,
        )
        assertTrue(opened.all { it.contains("PH3-RECORDED-") })
    }

    @Test
    fun `frames the phone no longer holds are opened all the same`() {
        // Найдорожче в цій діркі: обидва боки могли стерти розмову місяці тому,
        // а запис у чужому ноутбуці лишився. Стирання не робить кадр нечитним,
        // бо ключ до нього — не в розмові, а в довгих ключах.
        val recorded = record(3, channel())
        val theOnlyCopy = recorded.first().second

        val recomputed = PeerChannel.derive(
            kyrylo.dh.privateKey, marta.dhPubB64, kyrylo.id, marta.id,
        )!!

        assertEquals(
            "стерте на обох телефонах усе одно читається із запису",
            recorded.first().first,
            PeerEnvelope.open(theOnlyCopy) { recomputed }?.body,
        )
    }

    @Test
    fun `clearing the key cache protects nothing`() {
        // Кеш ключів живе лише в памʼяті, і це справді щось значить — але не
        // проти цієї атаки: ключ не зберігали, його ВИВОДЯТЬ. Забути виведене
        // так само корисно, як забути відповідь до задачі, лишивши умову.
        val cache = PeerChannel.Cache()
        val live = cache.get(kyrylo.id, kyrylo.dhPubB64) {
            PeerChannel.derive(marta.dh.privateKey, kyrylo.dhPubB64, marta.id, kyrylo.id)
        }!!
        val recorded = record(5, live)

        cache.clear()
        assertEquals(0, cache.size())

        val afterWipe = PeerChannel.derive(
            kyrylo.dh.privateKey, marta.dhPubB64, kyrylo.id, marta.id,
        )!!
        assertEquals(
            "очищений кеш не забрав у слідчого жодного кадру",
            recorded.map { it.first },
            recorded.mapNotNull { (_, frame) -> PeerEnvelope.open(frame) { afterWipe }?.body },
        )
    }

    @Test
    fun `capture also mints receipts for deliveries that never happened`() {
        // Квитанція йде під тим самим ключем пари, тож той, хто його порахував,
        // може заднім числом «підтвердити» будь-який записаний кадр. Це не
        // окрема діра — це та сама, і закриється вона тим самим.
        val live = channel()
        val (_, frame) = record(1, live).single()
        val env = PeerEnvelope.parse(frame)!!

        val honest = PeerReceipt.of(live, "m-1", marta.id, kyrylo.id, env.n)!!
        val afterCapture = PeerReceipt.of(
            PeerChannel.derive(kyrylo.dh.privateKey, marta.dhPubB64, kyrylo.id, marta.id)!!,
            "m-1", marta.id, kyrylo.id, env.n,
        )

        assertTrue(
            "захоплений телефон пише квитанції за минулі кадри",
            PeerReceipt.matches(honest, afterCapture),
        )
    }

    @Test
    fun `the pair key is one key, both directions, for the life of the keys`() {
        // Саме це робить діру ретроактивною і взаємною: немає «ключа цього
        // листа» — є один ключ на пару назавжди, і він однаковий в обидва боки.
        val hers = channel()
        val his = PeerChannel.derive(kyrylo.dh.privateKey, marta.dhPubB64, kyrylo.id, marta.id)!!

        assertTrue(hers.contentEquals(his))
        assertTrue(hers.contentEquals(channel()))

        val toHim = PeerEnvelope.seal(
            PeerMessage("m-a", marta.id, kyrylo.id, "туди", 1L, signature = "cGlkcGlz"),
            hers,
        )!!
        assertNotNull(
            "той самий ключ відкриває обидва напрямки",
            PeerEnvelope.open(toHim.frame) { his },
        )
    }

    /**
     * ЩО САМЕ дано слідчому — читати перед тим, як вірити цьому тесту.
     *
     * Перша версія цього випадку НЕ давала атакуючому записаного
     * рукостискання, і через це проходила навіть тоді, коли ефемерну половину
     * взагалі прибрали з виведення ключа: свіжість тримали самі лише випадкові
     * добавки в солі, а вони публічні. Тест, який не може впасти, — не доказ;
     * тест, що падає зі слабшої причини, ніж заявляє, — гірше за відсутній, бо
     * перетворює невідоме на хибну певність.
     *
     * Тому тут слідчому дано БІЛЬШЕ, ніж дає один захоплений телефон:
     * — обидва приватні X25519 і обидва приватні Ed25519 (два захоплення);
     * — увесь реєстр;
     * — записані кадри листів;
     * — і ОБИДВА привітання, бо вони летіли тією самою мережею, що й листи.
     *
     * Немає лише приватних половин ефемерних ключів: вони жили в памʼяті двох
     * процесів і були обнулені. Якщо колись хтось прибере домішування
     * ефемерного секрету, цей випадок мусить почервоніти — і він червоніє.
     */
    @Test
    fun `capture of both phones and the recorded handshake still opens no meeting frame`() {
        val offer = PeerSession.offer(marta.id, kyrylo.id, marta.signing.privateKey)!!
        val answer = PeerSession.answer(
            theirHello = PeerSession.parse(offer.frame)!!,
            myId = kyrylo.id,
            theirSigningPubB64 = marta.pubB64,
            mySigningKey = kyrylo.signing.privateKey,
            myStaticDh = kyrylo.dh.privateKey,
            theirStaticDhB64 = marta.dhPubB64,
            nowMs = 1_000L,
        )!!
        val mine = PeerSession.finish(
            offer = offer,
            theirHello = PeerSession.parse(answer.frame)!!,
            theirSigningPubB64 = kyrylo.pubB64,
            myStaticDh = marta.dh.privateKey,
            theirStaticDhB64 = kyrylo.dhPubB64,
            nowMs = 1_000L,
        )!!

        val recorded = (1..40).map { n ->
            PeerEnvelope.sealV4(
                PeerSealing.seal(
                    PeerMessage(
                        id = "m-$n",
                        fromId = marta.id,
                        toId = kyrylo.id,
                        body = "зустріч $n, PH4-RECORDED-$n",
                        sentAtMs = 1_700_000_000_000L + n,
                    ),
                    marta.signing.privateKey,
                ),
                mine.sid,
                mine.key,
            )!!.frame
        }

        // Зустріч закінчилась разом із процесами. Нічого не було записано.
        val cache = PeerSession.Cache()
        cache.put(answer.session, "bind", 1_000L)
        cache.clear()

        // Слідчий бачив і сам обмін привітаннями — він летить тією самою
        // мережею. Отже, у нього є ОБИДВІ випадкові добавки й ОБИДВА ефемерні
        // ПУБЛІЧНІ ключі. Бракує рівно приватних половин, які жили в памʼяті
        // двох процесів і були обнулені.
        val transcript = listOf(PeerSession.parse(offer.frame)!!, PeerSession.parse(answer.frame)!!)
        val everythingCaptureGives = listOf(
            marta.dh.privateKey,
            kyrylo.dh.privateKey,
            marta.signing.privateKey,
            kyrylo.signing.privateKey,
            PeerChannel.derive(kyrylo.dh.privateKey, marta.dhPubB64, kyrylo.id, marta.id)!!,
            PeerChannel.derive(marta.dh.privateKey, kyrylo.dhPubB64, marta.id, kyrylo.id)!!,
            // Найкраща спроба: довгий спільний секрет пари, розтягнутий рівно
            // тією ж сіллю, що й справжній ключ зустрічі. Це і є ключ, який
            // вийшов би, якби ефемерну половину колись перестали домішувати.
            bestGuessWithoutEphemerals(transcript[0], transcript[1]),
        )

        val opened = recorded.flatMap { frame ->
            everythingCaptureGives.mapNotNull { stolen ->
                PeerEnvelope.open(frame, sessionKey = { stolen }) { stolen }
            }
        }

        assertTrue(
            "кадр зустрічі відкрився чимось із захопленого телефона",
            opened.isEmpty(),
        )
        assertEquals(40, recorded.size)
    }

    /**
     * Усе, що слідчий може порахувати без ефемерних ПРИВАТНИХ ключів: довгий
     * ECDH пари й публічний обмін привітаннями. Виведення тут навмисно
     * переписане з [PeerSession], а не викликане з нього: це не помічник, це
     * атака, і вона мусить лишитись слабшою за реалізацію, навіть якщо та
     * колись зміниться.
     */
    private fun bestGuessWithoutEphemerals(
        initiator: PeerSession.Hello,
        responder: PeerSession.Hello,
    ): ByteArray {
        val sep = ''
        val low = if (marta.id <= kyrylo.id) marta.id else kyrylo.id
        val high = if (marta.id <= kyrylo.id) kyrylo.id else marta.id
        return Hkdf.derive(
            ikm = X25519.ecdh(
                kyrylo.dh.privateKey,
                java.util.Base64.getDecoder().decode(marta.dhPubB64),
            ),
            salt = "$low$sep$high$sep${initiator.n}$sep${responder.n}".toByteArray(Charsets.UTF_8),
            info = "PHANTOM OS/peer-msg-v4-session".toByteArray(Charsets.UTF_8),
            length = 32,
        )
    }
}
