package local.phantom.companion.core.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тести спільного словника модуля.
 *
 * Тут немає складної математики — і саме тому це небезпечне місце. Ключ
 * супутника, ознака двочастотності й поріг «скомпрометовано» — це контракти, на
 * які мовчки покладається решта коду. Якщо ключ переплутає L1 і L5 одного
 * апарата, TDCP порівняє фазу різних несучих і видасть сміття за рух; якщо поріг
 * `compromised` спрацює на епсилон пізніше, ніж домовлено, — рішення про перехід
 * на числення прийде на епоху запізно.
 */
class GnssTypesTest {

    private fun obs(
        svid: Int,
        constellation: Constellation = Constellation.GPS,
        band: Band = Band.L1,
        cn0: Double = 42.0,
    ) = SatObservation(
        svid = svid,
        constellation = constellation,
        band = band,
        cn0DbHz = cn0,
        pseudorangeRateMps = 0.0,
    )

    // ————————————————————————————————————————————————————————————————
    // Ключ супутника
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `ключ стабільний між викликами для того самого супутника`() {
        // Ключ — це те, за чим фаза зшивається між епохами. Якщо він хоч трохи
        // «плаває», кожна епоха виглядатиме як новий супутник і TDCP не матиме
        // що віднімати.
        val a = obs(5, Constellation.GALILEO, Band.E5B, cn0 = 40.0)
        val b = obs(5, Constellation.GALILEO, Band.E5B, cn0 = 12.0)
        assertEquals(
            "ключ не має залежати від C/N0 чи іншого виміру, лише від тотожності апарата",
            a.key, b.key,
        )
    }

    @Test
    fun `ключ розрізняє смуги того самого апарата`() {
        // Двочастотний апарат випромінює L1 і L5 з ОДНИМ svid. Якщо ключ їх не
        // розрізнить, безіоносферна комбінація зіллється в кашу, а різниця фаз
        // порівняє несучі різної частоти — це і є «сміття, видане за рух».
        val l1 = obs(7, band = Band.L1)
        val l5 = obs(7, band = Band.L5)
        assertNotEquals("L1 і L5 одного апарата — це два різні виміри", l1.key, l5.key)
    }

    @Test
    fun `ключ розрізняє констеляції з однаковим svid`() {
        // svid унікальний лише в межах своєї констеляції: GPS-7 і BeiDou-7 —
        // різні супутники в різних кутках неба. Ключ мусить це утримати.
        assertNotEquals(
            "однаковий номер у різних системах — різні апарати",
            obs(7, Constellation.GPS).key,
            obs(7, Constellation.BEIDOU).key,
        )
    }

    @Test
    fun `ключ включає всі три складові тотожності`() {
        // Фіксуємо формат явно: зміна схеми ключа зламає зшивання епох мовчки,
        // тож нехай про неї спіткнеться цей тест, а не поле.
        assertEquals("GALILEO-11-L5", obs(11, Constellation.GALILEO, Band.L5).key)
    }

    // ————————————————————————————————————————————————————————————————
    // Двочастотність
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `двочастотність вимагає одночасно L1 і L5`() {
        // Саме пара L1+L5 дозволяє зняти іоносферу — головне джерело затримки.
        val epoch = GnssEpoch(
            elapsedRealtimeNanos = 0L,
            observations = listOf(obs(1, band = Band.L1), obs(1, band = Band.L5)),
        )
        assertTrue("L1 разом з L5 — це двочастотний прийом", epoch.dualFrequency)
    }

    @Test
    fun `сама лише L1 не є двочастотністю`() {
        val epoch = GnssEpoch(
            elapsedRealtimeNanos = 0L,
            observations = listOf(obs(1, band = Band.L1), obs(2, band = Band.L1)),
        )
        assertFalse("два супутники на одній смузі — це все ще одна частота", epoch.dualFrequency)
    }

    @Test
    fun `сама лише L5 не є двочастотністю`() {
        // Дзеркало: наявність L5 без L1 теж не дає безіоносферної комбінації.
        val epoch = GnssEpoch(
            elapsedRealtimeNanos = 0L,
            observations = listOf(obs(1, band = Band.L5), obs(2, band = Band.L5)),
        )
        assertFalse("сама L5 без L1 — не двочастотність", epoch.dualFrequency)
    }

    @Test
    fun `порожня епоха не двочастотна і не падає`() {
        val epoch = GnssEpoch(elapsedRealtimeNanos = 0L, observations = emptyList())
        assertFalse("на порожньому небі двочастотності бути не може", epoch.dualFrequency)
        assertEquals("порожня епоха має нуль супутників", 0, epoch.satCount)
    }

    @Test
    fun `L2 не сплутано з L5 у ознаці двочастотності`() {
        // L2 — теж друга смуга, але ознака в коді побудована саме на парі L1+L5.
        // Тест фіксує цей вибір, щоб L2 випадково не почала його вмикати.
        val epoch = GnssEpoch(
            elapsedRealtimeNanos = 0L,
            observations = listOf(obs(1, band = Band.L1), obs(1, band = Band.L2)),
        )
        assertFalse("пара L1+L2 не рахується як двочастотність у цьому коді", epoch.dualFrequency)
    }

    // ————————————————————————————————————————————————————————————————
    // Поріг «скомпрометовано» — на межах
    // ————————————————————————————————————————————————————————————————

    private fun integrity(jamming: Double, spoofing: Double) =
        GnssIntegrity(jammingScore = jamming, spoofingScore = spoofing, usableSats = 8)

    @Test
    fun `чисте небо не скомпрометоване`() {
        assertFalse("нульові оцінки — сигнал чистий", integrity(0.0, 0.0).compromised)
    }

    @Test
    fun `рівно на порозі глушіння вже скомпрометовано`() {
        // Поріг 0.6 — саме на ньому приймається рішення перейти на числення.
        // «Строго більше» замість «більше-дорівнює» відклало б реакцію на епоху,
        // і саме в цю епоху брехня встигла б протекти у фільтр.
        assertTrue("0,6 глушіння — це вже поріг, а не «майже»", integrity(0.6, 0.0).compromised)
    }

    @Test
    fun `рівно на порозі підміни вже скомпрометовано`() {
        // 21.07 бреха була саме підміною: C/N0 висока, spoofingScore великий.
        assertTrue("0,6 підміни — це вже поріг", integrity(0.0, 0.6).compromised)
    }

    @Test
    fun `трохи нижче порога ще не скомпрометовано`() {
        // Друга сторона межі: 0,5999 не має вважатись атакою, інакше детектор
        // кричав би від будь-якої хмари й оператор перестав би йому вірити.
        assertFalse("0,5999 ще під порогом", integrity(0.5999, 0.5999).compromised)
    }

    @Test
    fun `достатньо однієї з двох оцінок, щоб визнати компрометацію`() {
        // Глушіння й підміна — незалежні механізми, тож поріг по кожному
        // спрацьовує окремо: висока підміна при нульовому глушінні — це атака.
        assertTrue("висока лише підміна", integrity(0.0, 0.85).compromised)
        assertTrue("високе лише глушіння", integrity(0.9, 0.0).compromised)
    }
}
