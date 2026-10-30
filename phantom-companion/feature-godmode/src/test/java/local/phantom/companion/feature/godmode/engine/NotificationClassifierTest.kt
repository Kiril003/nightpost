package local.phantom.companion.feature.godmode.engine

import local.phantom.companion.feature.godmode.engine.NotificationClassifier.Classification
import local.phantom.companion.feature.godmode.engine.NotificationClassifier.PackageRule
import local.phantom.companion.feature.godmode.service.toDigestRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationClassifierTest {

    // ---------- redactSensitive ----------

    @Test
    fun `redacts banking OTP code in plain SMS`() {
        val out = NotificationClassifier.redactSensitive(
            "Your code is 4528. Don't share it.",
        )
        assertFalse("digit must be masked: $out", out.contains("4528"))
        assertTrue("redaction marker visible: $out", out.contains("***"))
    }

    @Test
    fun `redacts otp keyword phrasing`() {
        val out = NotificationClassifier.redactSensitive("OTP: 392011 valid for 5 min")
        assertFalse(out.contains("392011"))
    }

    @Test
    fun `redacts ukrainian banking phrasing`() {
        val out = NotificationClassifier.redactSensitive(
            "Код підтвердження: 9821. Не повідомляйте нікому.",
        )
        assertFalse("Ukrainian otp redacted: $out", out.contains("9821"))
    }

    @Test
    fun `does not redact prices`() {
        val out = NotificationClassifier.redactSensitive("Latte 25.99 ₴ — пригощайся")
        assertTrue("prices kept: $out", out.contains("25.99"))
    }

    @Test
    fun `redacts bare 4-digit code`() {
        val out = NotificationClassifier.redactSensitive("1234 is your verification code")
        assertFalse(out.contains("1234"))
    }

    // ---------- classify ----------

    @Test
    fun `telegram default is important`() {
        val r = NotificationClassifier.classify(
            packageName = "org.telegram.messenger",
            channelId = "msgs",
            title = "Олег",
            text = "коли зустрінемось?",
        )
        assertEquals(Classification.IMPORTANT, r.classification)
    }

    @Test
    fun `gmail default is important via mail substring`() {
        val r = NotificationClassifier.classify(
            packageName = "com.google.android.gm",
            channelId = "inbox",
            title = "Анна",
            text = "ремонт у пʼятницю",
        )
        assertEquals(Classification.IMPORTANT, r.classification)
    }

    @Test
    fun `system noise package is spam`() {
        val r = NotificationClassifier.classify(
            packageName = "com.google.android.gms.subpackage",
            channelId = "play_protect",
            title = "Update",
            text = "Sign-in services updated",
        )
        assertEquals(Classification.SPAM, r.classification)
    }

    @Test
    fun `unknown package defaults routine`() {
        val r = NotificationClassifier.classify(
            packageName = "com.spotify.music",
            channelId = "now_playing",
            title = "Phantom Boards",
            text = "playing on living room speaker",
        )
        assertEquals(Classification.ROUTINE, r.classification)
    }

    @Test
    fun `banking OTP redacts to spam`() {
        val r = NotificationClassifier.classify(
            packageName = "ua.privatbank.privat24",
            channelId = "auth",
            title = "Privat24",
            text = "Code 8231 to confirm payment",
        )
        // After redaction the remaining body is too short to be
        // worth surfacing, so the classifier downgrades to spam.
        assertEquals(Classification.SPAM, r.classification)
        assertFalse("redacted body never carries the code: ${r.redactedText}", r.redactedText.contains("8231"))
    }

    @Test
    fun `per-package override beats default`() {
        val rules = listOf(
            PackageRule("org.telegram.messenger", Classification.SPAM),
        )
        val r = NotificationClassifier.classify(
            packageName = "org.telegram.messenger",
            channelId = "msgs",
            title = "X",
            text = "y",
            rules = rules,
        )
        assertEquals(Classification.SPAM, r.classification)
    }

    @Test
    fun `a queued digest row carries neither the raw title nor the raw body`() {
        val r = NotificationClassifier.classify(
            packageName = "ua.privatbank.ap24",
            channelId = null,
            title = "Код 4821",
            text = "Вхід у застосунок з нового пристрою, перевірте будь ласка",
        )
        assertEquals(Classification.ROUTINE, r.classification)

        val row = r.toDigestRow(id = "row-1", atMs = 0L)
        assertFalse("title on disk: ${row.title}", row.title.contains("4821"))
        assertFalse("body on disk: ${row.redactedText}", row.redactedText.contains("4821"))
    }

    @Test
    fun `redactedText preserves original when nothing matched`() {
        val r = NotificationClassifier.classify(
            packageName = "com.x.y",
            channelId = null,
            title = "T",
            text = "звичайне повідомлення",
        )
        assertEquals(r.text, r.redactedText)
    }
}
