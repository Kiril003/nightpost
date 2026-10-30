package local.phantom.companion.license

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Купівля в Play-версії: розбір каталогу, відповіді сервера і межа між
 * збірками. Play Billing у польовій збірці — порушення з іншого боку: збірка,
 * що їде повз Play, не має тягнути бібліотеку крамниці, якою не користується.
 */
class PlayCheckoutContractTest {

    @Test
    fun `каталог розбирається і відкидає те, чого Play не прийме`() {
        val items = PlayCatalogue.parse(" personal_licence:inapp, crew_yearly:subs ,Bad-Id:inapp,x:weird,personal_licence:subs,plain")
        assertEquals(
            listOf(
                PlayCatalogue.Item("personal_licence", "inapp"),
                PlayCatalogue.Item("crew_yearly", "subs"),
                PlayCatalogue.Item("plain", "inapp"),
            ),
            items,
        )
        assertTrue(PlayCatalogue.parse("").isEmpty())
    }

    @Test
    fun `ключ лише з 200 із ключем, 202 — чекати, решта — відмова з кодом`() {
        assertEquals(
            PlayRedemption.Key("PHTM-AAAAA-BBBBB-CCCCC-DDDDD", "personal"),
            PlayRedemption.parse(200, """{"license_key":"PHTM-AAAAA-BBBBB-CCCCC-DDDDD","tier":"personal"}"""),
        )
        assertEquals(PlayRedemption.Pending, PlayRedemption.parse(202, """{"status":"pending"}"""))
        assertEquals(
            PlayRedemption.Refused(402, "purchase is not paid or was refunded"),
            PlayRedemption.parse(402, """{"detail":"purchase is not paid or was refunded"}"""),
        )
        // 200 без ключа — не успіх: інакше екран сказав би «активовано» ні з чим.
        assertTrue(PlayRedemption.parse(200, "{}") is PlayRedemption.Refused)
        assertTrue(PlayRedemption.parse(502, "<html>bad gateway</html>") is PlayRedemption.Refused)
    }

    private fun appDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return File(dir ?: error("не знайшов корінь дерева"), "app")
    }

    @Test
    fun `play billing лише в store, польова збірка його не тягне`() {
        val build = File(appDir(), "build.gradle.kts").readText()
        assertTrue(""""storeImplementation"(libs.play.billing)""" in build)
        assertFalse(
            "Play Billing у implementation — польова збірка понесе бібліотеку крамниці",
            Regex("""(?m)^\s*implementation\(libs\.play\.billing\)""").containsMatchIn(build),
        )
        // Жоден клас Play Billing не має прослизнути в main чи field.
        listOf("src/main/java", "src/field/java").forEach { root ->
            File(appDir(), root).walkTopDown().filter { it.extension == "kt" }.forEach { kt ->
                assertFalse(
                    "${kt.path} імпортує Play Billing — польова збірка не скомпілюється",
                    "com.android.billingclient" in kt.readText(),
                )
            }
        }
    }

    @Test
    fun `обидві збірки мають CheckoutFactory, і польова повертає null`() {
        val field = File(appDir(), "src/field/java/local/phantom/companion/license/CheckoutFactory.kt").readText()
        val store = File(appDir(), "src/store/java/local/phantom/companion/license/CheckoutFactory.kt").readText()
        assertTrue(Regex("""Checkout\? = null""").containsMatchIn(field))
        assertTrue("PlayCheckout(" in store)
    }

    @Test
    fun `телефон не підтверджує і не витрачає покупку сам — це робить сервер`() {
        val checkout = File(appDir(), "src/store/java/local/phantom/companion/license/PlayCheckout.kt").readText()
        assertFalse("acknowledgePurchase" in checkout)
        assertFalse("consumeAsync" in checkout)
        assertTrue("redeemPlayPurchase" in checkout)
    }
}
