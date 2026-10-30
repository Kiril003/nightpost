package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Рівень сцени в профілі: чи можна слати цій людині позначки карток.
 *
 * Дріт версію застосунку не несе, а `PH-CST:` старій збірці (15, 17) показався б
 * сирим дротом і сповіщенням на кожен дотик. Тому позначки їдуть лише тому, хто
 * САМ повідомив у профілі, що вміє їх приймати. Тут — дротова й реєстрова половини.
 */
class PeerSceneLevelTest {

    private val nowMs = 1_700_000_000_000L

    private fun peer() = PeerIdentity(
        id = "p1", name = "Олена", kind = PeerIdentity.Kind.PERSON, pubKey = "PUB", dhPubKey = "DH",
    )

    private fun frame(rev: Long, scene: Int) =
        PeerProfile.encode(PeerProfile.Body(rev = rev, statusText = PeerProfile.Field.Set("тут"), scene = scene))!!

    @Test
    fun `рівень сцени їде в кадрі й читається назад`() {
        val got = PeerProfile.decode(frame(3, PeerProfile.SCENE_V1))!!
        assertEquals(PeerProfile.SCENE_V1, got.scene)
        assertEquals(PeerProfile.Field.Set("тут"), got.statusText)
    }

    @Test
    fun `кадр без тега сцени — це 0, а не помилка, і решта поля на місці`() {
        val old = PeerProfile.decode(frame(3, 0))!!
        assertEquals(0, old.scene)
        assertEquals(PeerProfile.Field.Set("тут"), old.statusText)
    }

    @Test
    fun `кадр збірки 15 без тега сцени читається так само, як завжди`() {
        // Ручний TLV: rev=2 і статус «hi» — рівно те, що писала збірка 15.
        val raw = byteArrayOf(1, 4, 0, 0, 0, 2, 4, 2, 'h'.code.toByte(), 'i'.code.toByte())
        val body = "PH-PRF:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(raw)
        val got = PeerProfile.decode(body)!!
        assertEquals(0, got.scene)
        assertEquals(PeerProfile.Field.Set("hi"), got.statusText)
    }

    @Test
    fun `покалічене значення тега сцени не вбиває профіль`() {
        // tag 9, довжина 2 — не наш формат: сцена 0, rev і поля цілі.
        val raw = byteArrayOf(1, 4, 0, 0, 0, 2, 9, 2, 7, 7)
        val body = "PH-PRF:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(raw)
        val got = PeerProfile.decode(body)
        assertNotNull(got)
        assertEquals(0, got!!.scene)
        assertEquals(2L, got.rev)
    }

    @Test
    fun `кадр профілю тримає рівність разом зі сценою`() {
        assertEquals(PeerProfile.Body(1, scene = 1), PeerProfile.Body(1, scene = 1))
        assertTrue(PeerProfile.Body(1, scene = 1) != PeerProfile.Body(1, scene = 0))
    }

    @Test
    fun `реєстр запам'ятовує рівень сцени з кадру профілю`() {
        val after = PeerRoster.absorbProfile(listOf(peer()), "p1", frame(1, PeerProfile.SCENE_V1), nowMs)
        assertEquals(PeerProfile.SCENE_V1, after.single().sceneLevel)
    }

    @Test
    fun `людина без оголошення має рівень 0 — їй позначки не шлемо`() {
        assertEquals(0, peer().sceneLevel)
        val after = PeerRoster.absorbProfile(listOf(peer()), "p1", frame(1, 0), nowMs)
        assertEquals(0, after.single().sceneLevel)
    }

    @Test
    fun `старіший кадр рівня не міняє, а новіший без тега знімає`() {
        val up = PeerRoster.absorbProfile(listOf(peer()), "p1", frame(5, PeerProfile.SCENE_V1), nowMs)
        // Повтор переказу зі старішим rev — той самий список, рівень на місці.
        val replay = PeerRoster.absorbProfile(up, "p1", frame(4, 0), nowMs)
        assertSame(up, replay)
        assertEquals(PeerProfile.SCENE_V1, replay.single().sceneLevel)
        // Новіший кадр без тега: людина повернулась на збірку, що цього не вміє.
        val down = PeerRoster.absorbProfile(up, "p1", frame(6, 0), nowMs)
        assertEquals(0, down.single().sceneLevel)
    }

    @Test
    fun `рівень приходить лише з кадру самої людини`() {
        // Кадр від p2 не може підняти рівень p1: реєстр бере fromId з конверта.
        val roster = listOf(peer(), peer().copy(id = "p2"))
        val after = PeerRoster.absorbProfile(roster, "p2", frame(1, PeerProfile.SCENE_V1), nowMs)
        assertEquals(0, after.first { it.id == "p1" }.sceneLevel)
        assertEquals(PeerProfile.SCENE_V1, after.first { it.id == "p2" }.sceneLevel)
    }
}
