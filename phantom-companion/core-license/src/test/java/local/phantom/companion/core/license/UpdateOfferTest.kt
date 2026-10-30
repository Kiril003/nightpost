package local.phantom.companion.core.license

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Оновлення пропонується РІВНО тоді, коли є що ставити і є куди піти. Усе інше
 * — мовчання: обіцянка оновлення, яке не встановиться, коштує дорожче за
 * жодного повідомлення.
 */
class UpdateOfferTest {

    private val issuer = TestSigner()
    private val installed = 41

    private fun policy(latest: String?) =
        BuildPolicy.verified(issuer.policy(latest = latest), issuer.publicHex)

    private fun offer(
        latest: String?,
        build: Int = installed,
        licensed: Boolean = true,
        dismissed: Int = 0,
        developer: Boolean = false,
    ) = UpdateOffer.forThisBuild(
        policy = policy(latest),
        installedBuild = build,
        licensed = licensed,
        developerBuild = developer,
        dismissedBuild = dismissed,
    )

    @Test
    fun `a genuinely newer build is offered, with the file named and weighed`() {
        val shown = offer(issuer.latest(build = 42))!!

        assertEquals(42, shown.build)
        assertEquals("1.4.0", shown.version)
        assertEquals("phantom-1.4.0.apk", shown.filename)
        assertEquals(41_234_567L, shown.sizeBytes)
        assertEquals("b".repeat(64), shown.sha256)
    }

    @Test
    fun `the same build and an older one are not updates`() {
        assertNull(offer(issuer.latest(build = 41), build = 41))
        assertNull(offer(issuer.latest(build = 40), build = 41))
    }

    @Test
    fun `an announcement without a usable checksum is refused outright`() {
        // Не «показати без суми», а НЕ ПОКАЗАТИ: людині пропонували б поставити
        // файл, якого нема з чим звірити.
        listOf(
            issuer.latest(sha256 = ""),
            issuer.latest(sha256 = "b".repeat(63)),
            issuer.latest(sha256 = "z".repeat(64)),
            issuer.latest(sha256 = "B".repeat(64) + "!"),
        ).forEach { assertNull(offer(it)) }
    }

    @Test
    fun `sha256 in capitals is the same sha256`() {
        assertEquals("b".repeat(64), offer(issuer.latest(sha256 = "B".repeat(64)))!!.sha256)
    }

    @Test
    fun `a half-described release is refused`() {
        listOf(
            issuer.latest(version = ""),
            issuer.latest(filename = ""),
            issuer.latest(build = 0),
            issuer.latest(size = 0),
            issuer.latest(url = "http://phantom-os.dev/account"),
            issuer.latest(platform = ""),
        ).forEach { assertNull(offer(it)) }
    }

    @Test
    fun `an announcement for another platform is not ours`() {
        assertNull(offer(issuer.latest(platform = "windows")))
    }

    @Test
    fun `a licence-gated build is never announced to a device without one`() {
        // Кабінет розверне такий пристрій з 403. Сказати «оновіться» — це
        // послати людину в глухий кут її ж грошима.
        assertNull(offer(issuer.latest(requires = "licence"), licensed = false))
        assertNotNull(offer(issuer.latest(requires = "account"), licensed = false))
        // Невідоме значення читаємо як «потрібен ключ»: мовчання дешевше.
        assertNull(offer(issuer.latest(requires = "everyone"), licensed = false))
    }

    @Test
    fun `a freely downloadable build is announced to a device with no key at all`() {
        // Саме той пристрій, заради якого існує вільне завантаження: без ключа,
        // на старій збірці, з дірою в руках. Поки сервер віддає файл кожному,
        // мовчати перед ним — не обережність, а покинутість.
        val shown = offer(issuer.latest(requires = "open"), licensed = false)

        assertNotNull(shown)
        assertEquals(false, shown!!.requiresLicence)
    }

    @Test
    fun `later means until the next build, not until the next launch`() {
        assertNull(offer(issuer.latest(build = 42), dismissed = 42))
        assertNotNull(offer(issuer.latest(build = 43), dismissed = 42))
    }

    @Test
    fun `the licence screen still sees a build the card was dismissed for`() {
        val announced = UpdateOffer.announcedFor(
            policy(issuer.latest(build = 42)),
            installed,
            developerBuild = false,
        )

        assertEquals(42, announced!!.build)
        assertNull(offer(issuer.latest(build = 42), dismissed = 42))
    }

    @Test
    fun `a developer build takes no part in the update game`() {
        // Апарат власника 04.09 о 02:00: у польовій збірці versionCode дорівнює
        // одиниці (номер задає лише scripts/release.sh), кабінет оголошував
        // сьому — і банер пропонував «оновитись» на збірку, СТАРІШУ за ту, що
        // стоїть. Одне натискання відкочувало щоденний апарат.
        val cabinet = policy(issuer.latest(build = 7, version = "0.1.0-tier1"))

        assertNull(UpdateOffer.announcedFor(cabinet, 1, developerBuild = true))
        assertNull(
            UpdateOffer.forThisBuild(
                policy = cabinet,
                installedBuild = 1,
                licensed = true,
                developerBuild = true,
            ),
        )
    }

    @Test
    fun `a published build never offers itself an older one`() {
        // Опублікована збірка рахує як раніше: рівне й старіше — не оновлення.
        val cabinet = policy(issuer.latest(build = 7))

        assertNull(UpdateOffer.announcedFor(cabinet, 8, developerBuild = false))
        assertNull(UpdateOffer.announcedFor(cabinet, 7, developerBuild = false))
    }

    @Test
    fun `a published build still hears about a genuinely newer one`() {
        // Друга половина правила: заслін для польових збірок не сміє зачинити
        // оновлення там, де воно справжнє.
        val cabinet = policy(issuer.latest(build = 8, version = "0.1.1"))
        val shown = UpdateOffer.forThisBuild(
            policy = cabinet,
            installedBuild = 7,
            licensed = true,
            developerBuild = false,
        )

        assertEquals(8, shown!!.build)
        assertEquals(8, UpdateOffer.announcedFor(cabinet, 7, developerBuild = false)!!.build)
    }

    @Test
    fun `a server we never reached changes nothing`() {
        // Порожня політика — це стан телефона, який жодного разу не вийшов на
        // звʼязок, і стан того, кому сервер відповів 204 чи не відповів зовсім
        // (LicenseService у таких випадках лишає збережений документ як є).
        assertNull(offer(latest = null))
        assertNull(UpdateOffer.forThisBuild(null, installed, licensed = true, developerBuild = false))
        assertNull(UpdateOffer.announcedFor(null, installed, developerBuild = false))
    }

    @Test
    fun `an unsigned announcement is not an announcement`() {
        val forged = TestSigner().policy(latest = issuer.latest(build = 99))

        assertNull(BuildPolicy.verified(forged, issuer.publicHex))
        assertNull(
            UpdateOffer.forThisBuild(
                policy = BuildPolicy.verified(forged, issuer.publicHex),
                installedBuild = installed,
                licensed = true,
                developerBuild = false,
            ),
        )
    }

    @Test
    fun `announcing a build takes nothing away`() {
        val verdict = LicenseGate.decide(
            LicenseGate.Input(
                certificateJson = issuer.certificate(),
                policyJson = issuer.policy(latest = issuer.latest()),
                publicKeyHex = issuer.publicHex,
                deviceFingerprint = TestSigner.FINGERPRINT,
                buildNumber = installed,
                nowMs = 1_760_000_000_000L,
                lastCheckInMs = 1_760_000_000_000L,
                firstRunMs = 1_760_000_000_000L - LicenseModel.DAY_MS,
            ),
        )

        assertEquals(LicenseReason.Licensed, verdict.reason)
        assertEquals(LicenseTier.Personal, verdict.tier)
    }
}
