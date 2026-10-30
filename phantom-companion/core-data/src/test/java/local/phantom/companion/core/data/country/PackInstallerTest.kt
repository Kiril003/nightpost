package local.phantom.companion.core.data.country

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ATLAS-3 хвиля 2 — пак став річчю, яка вміє приїхати.
 *
 * Головне тут не «манiфест розбирається», а ЧОГО він не робить: непідписаний
 * не встановлюється, підписаний чужим ключем не встановлюється, збірка без
 * якоря довіри не приймає жодного, і жоден із цих випадків не тихий.
 */
class PackInstallerTest {

    private val signer = PackSigner()

    /** Аудит-заглушка: справжній живе в `core-geo` (`PackLicenseAudit`). */
    private val cleanAudit: (List<PackSource>) -> List<String> = { emptyList() }

    @After
    fun tearDown() = CountryPackRegistry.reset()

    private fun read(
        text: String,
        key: String = signer.publicHex,
        appBuild: Int = 100,
        audit: (List<PackSource>) -> List<String> = cleanAudit,
        installedVersion: Int? = null,
    ) = PackInstaller.read(
        text = text,
        publicKeyHex = key,
        appBuild = appBuild,
        assetSource = PackAssetSource.Directory("/tmp/pl"),
        audit = audit,
        installedVersion = installedVersion,
    )

    private fun rejectionOf(outcome: PackInstall): PackReject =
        (outcome as PackInstall.Rejected).reason

    @Test
    fun `підписаний манiфест стає паком з усіма полями`() {
        val text = signer.sign(
            polandManifest(
                extra = ""","transit":[{"city":"Варшава","anchorLat":52.23,"anchorLon":21.01,""" +
                    """"radiusKm":25.0,"vehiclePositionsUrl":"https://example.org/vp"}]""",
            ),
        )
        val pack = (read(text) as PackInstall.Accepted).pack
        assertEquals("PL", pack.id)
        assertEquals(52.2297, pack.home.lat, 1e-6)
        assertEquals(1, pack.transitFeeds.size)
        assertTrue(pack.bounds.contains(52.2297, 21.0122))
    }

    @Test
    fun `без підпису пак не існує`() {
        assertEquals(PackReject.BAD_SIGNATURE, rejectionOf(read(polandManifest())))
    }

    @Test
    fun `чужий підпис не приймається`() {
        val other = PackSigner()
        assertEquals(
            PackReject.BAD_SIGNATURE,
            rejectionOf(read(other.sign(polandManifest()))),
        )
    }

    @Test
    fun `зміна байта після підпису ламає підпис`() {
        val tampered = signer.sign(polandManifest()).replace("52.2297", "52.9999")
        assertEquals(PackReject.BAD_SIGNATURE, rejectionOf(read(tampered)))
    }

    @Test
    fun `збірка без якоря довіри не приймає жодного пака`() {
        // Дзеркало ліцензійних воріт: порожній ключ = «нічому не довіряти».
        assertEquals(
            PackReject.NO_TRUST_ANCHOR,
            rejectionOf(read(signer.sign(polandManifest()), key = "")),
        )
    }

    @Test
    fun `застара збірка не вдає, що розуміє пак`() {
        assertEquals(
            PackReject.APP_TOO_OLD,
            rejectionOf(read(signer.sign(polandManifest(minAppBuild = 500)), appBuild = 100)),
        )
    }

    @Test
    fun `оновлення не їде назад`() {
        assertEquals(
            PackReject.OLDER_VERSION,
            rejectionOf(read(signer.sign(polandManifest(version = 2)), installedVersion = 7)),
        )
    }

    @Test
    fun `вбудований пак не підміняється дротом`() {
        val ua = signer.sign(
            """{"id":"UA","version":9,"bounds":{"minLat":44.0,"minLon":22.0,"maxLat":52.5,""" +
                """"maxLon":40.3},"home":{"lat":50.45,"lon":30.52,"zoom":12.0}}""",
        )
        assertEquals(PackReject.SHADOWS_BUNDLED, rejectionOf(read(ua)))
    }

    @Test
    fun `ліцензійне порушення зупиняє встановлення`() {
        val outcome = read(signer.sign(polandManifest()), audit = { listOf("«pl-base»: без атрибуції") })
        assertEquals(PackReject.LICENSE_VIOLATION, rejectionOf(outcome))
        assertTrue("причина має називати шар", "pl-base" in (outcome as PackInstall.Rejected).detail)
    }

    @Test
    fun `дім за власною рамкою — це не пак`() {
        val text = signer.sign(
            """{"id":"PL","version":1,"bounds":{"minLat":49.0,"minLon":14.1,"maxLat":54.9,""" +
                """"maxLon":24.2},"home":{"lat":50.45,"lon":30.52,"zoom":12.0}}""",
        )
        assertEquals(PackReject.MALFORMED, rejectionOf(read(text)))
    }

    /**
     * Львів тут НЕ підходить за ціль: осьова рамка Польщі (до 24.2° сх. д.)
     * захоплює смугу Західної України, і 49.84/24.03 у ній лежить чесно.
     * Рамка — дешева належність, а не кордон; перевірка ловить фід, який за
     * неї вийшов, а не той, що впав у сусідську довготу.
     */
    @Test
    fun `фід чужого міста в паку — це не пак`() {
        val text = signer.sign(
            polandManifest(
                extra = ""","transit":[{"city":"Київ","anchorLat":50.45,"anchorLon":30.52,""" +
                    """"radiusKm":25.0,"vehiclePositionsUrl":"https://example.org/vp"}]""",
            ),
        )
        assertEquals(PackReject.MALFORMED, rejectionOf(read(text)))
    }

    @Test
    fun `асет із виходом за каталог пака не проходить`() {
        val text = signer.sign(polandManifest(extra = ""","poiAsset":"../../databases/vault""""))
        assertEquals(PackReject.MALFORMED, rejectionOf(read(text)))
    }

    @Test
    fun `стрічка не по https не проходить`() {
        val text = signer.sign(
            polandManifest(
                extra = ""","alarmFeeds":[{"role":"region-state","endpoint":"http://rcb.gov.pl/a",""" +
                    """"userAgent":"PhantomCompanion/0.1"}]""",
            ),
        )
        assertEquals(PackReject.MALFORMED, rejectionOf(read(text)))
    }

    @Test
    fun `оголошена стрічка зі схемою переживає дріт цілою`() {
        val text = signer.sign(
            polandManifest(
                extra = ""","alarmFeeds":[{"role":"region-state","endpoint":"https://rcb.gov.pl/a",""" +
                    """"userAgent":"PhantomCompanion/0.1","schema":{"rootPath":"states",""" +
                    """"fieldMapping":{"isActive":["alertnow"]},""" +
                    """"classificationRules":{"BALLISTIC":["balistyczn"],"MISSILE":["rakiet"]},""" +
                    """"timestampFormat":"yyyy-MM-dd HH:mm:ss","timeZoneId":"Europe/Warsaw"}}]""",
            ),
        )
        val pack = (read(text) as PackInstall.Accepted).pack
        val feed = pack.alarmFeed(AlarmFeedRole.REGION_STATE)
        assertNotNull(feed?.schema)
        assertEquals("rcb.gov.pl", feed?.host)
        // Порядок словника загроз несе сенс: перший збіг виграє.
        val order = feed?.schema?.classificationRules?.keys?.toList().orEmpty()
        assertTrue(order.indexOf("BALLISTIC") < order.indexOf("MISSILE"))
        assertEquals(feed?.endpoint, feed?.schema?.sourceUrl)
    }
}
