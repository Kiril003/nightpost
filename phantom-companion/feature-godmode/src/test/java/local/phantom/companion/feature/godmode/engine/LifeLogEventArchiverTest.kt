package local.phantom.companion.feature.godmode.engine

import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.ai.OnnxEmbeddingHelper
import local.phantom.companion.core.data.lifelog.LifeLogArchive
import local.phantom.companion.core.data.lifelog.LifeLogDao
import local.phantom.companion.core.data.lifelog.LifeLogEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * Чужі слова не лишаються в довічному архіві.
 *
 * Перевіряється не «функцію викликали», а що ЧИТАЄТЬСЯ НАЗАД: жоден
 * рядок таблиці й жодна відповідь семантичного пошуку не містить тіла
 * повідомлення чи разового коду.
 */
class LifeLogEventArchiverTest {

    private class FakeLifeLogDao : LifeLogDao {
        val rows = mutableListOf<LifeLogEntryEntity>()
        override suspend fun insert(entry: LifeLogEntryEntity) {
            rows.removeIf { it.id == entry.id }
            rows.add(entry)
        }
        override suspend fun getAllEntries(): List<LifeLogEntryEntity> =
            rows.sortedByDescending { it.timestampMs }
        override suspend fun getEntriesSince(sinceMs: Long): List<LifeLogEntryEntity> =
            rows.filter { it.timestampMs >= sinceMs }
        override suspend fun deleteById(id: String) {
            rows.removeIf { it.id == id }
        }
        override suspend fun deleteByType(eventType: String): Int {
            val before = rows.size
            rows.removeIf { it.eventType == eventType }
            return before - rows.size
        }
        override suspend fun clear() = rows.clear()
    }

    private fun archiveOver(dao: FakeLifeLogDao) =
        LifeLogArchive(dao, OnnxEmbeddingHelper(File("non_existent.onnx")))

    @Test
    fun `body of a messenger notification never reaches the archive`() = runTest {
        val dao = FakeLifeLogDao()
        val archive = archiveOver(dao)

        LifeLogEventArchiver(archive).onEvent(
            GodModeEvent.Notification(
                pkg = "org.telegram.messenger",
                title = "Оля",
                text = "буду о шостій, не забудь ключі",
            ),
        )

        val stored = dao.rows.joinToString("\n") { "${it.eventType} ${it.eventData}" }
        assertFalse(stored, stored.contains("буду о шостій"))
        assertFalse(stored, stored.contains("ключі"))
        // Конверт лишається: який застосунок і хто написав.
        assertEquals(1, dao.rows.size)
        assertEquals(LifeLogEventArchiver.TYPE_NOTIFICATION, dao.rows[0].eventType)
        assertTrue(dao.rows[0].eventData, dao.rows[0].eventData.contains("Оля"))
    }

    @Test
    fun `a one-time code is not stored and cannot be recalled`() = runTest {
        val dao = FakeLifeLogDao()
        val archive = archiveOver(dao)

        LifeLogEventArchiver(archive).onEvent(
            GodModeEvent.Notification(
                pkg = "ua.privatbank.ap24",
                title = "ПриватБанк",
                text = "Код підтвердження 4821. Нікому не повідомляйте.",
            ),
        )

        val stored = dao.rows.joinToString("\n") { it.eventData }
        assertFalse(stored, stored.contains("4821"))
        val recalled = archive.searchMemories("4821", limit = 10).joinToString("\n")
        assertFalse(recalled, recalled.contains("4821"))
    }

    @Test
    fun `a code in the title is redacted out of the envelope`() = runTest {
        val dao = FakeLifeLogDao()
        val archive = archiveOver(dao)

        LifeLogEventArchiver(archive).onEvent(
            GodModeEvent.Notification(
                pkg = "com.google.android.gm",
                title = "Ваш код 903472",
                text = "Вітаємо у сервісі, гарного дня і продуктивної роботи",
            ),
        )

        val stored = dao.rows.joinToString("\n") { it.eventData }
        assertFalse(stored, stored.contains("903472"))
    }

    @Test
    fun `a burst leaves a count, not the messages`() = runTest {
        val dao = FakeLifeLogDao()
        val archive = archiveOver(dao)

        LifeLogEventArchiver(archive).onEvent(
            GodModeEvent.NotificationBurst(
                pkg = "org.telegram.messenger",
                combinedText = "Оля: привіт\nОля: ти вдома\nОля: передзвони будь ласка",
            ),
        )

        val stored = dao.rows.joinToString("\n") { it.eventData }
        assertFalse(stored, stored.contains("передзвони"))
        assertFalse(stored, stored.contains("ти вдома"))
        assertTrue(stored, stored.contains("3"))
    }

    @Test
    fun `screen dumps stay out and app switches stay in`() = runTest {
        val dao = FakeLifeLogDao()
        val archiver = LifeLogEventArchiver(archiveOver(dao))

        archiver.onEvent(GodModeEvent.ScreenSnapshot("com.bank.app", "баланс 12 400 грн"))
        archiver.onEvent(GodModeEvent.ScreenChanged("com.bank.app", "MainActivity"))
        archiver.onEvent(GodModeEvent.ScreenChanged("com.bank.app", "MainActivity"))
        archiver.onEvent(GodModeEvent.ScreenChanged("local.phantom.companion", "Stream"))

        assertEquals(1, dao.rows.size)
        assertEquals(LifeLogEventArchiver.TYPE_SCREEN_CHANGED, dao.rows[0].eventType)
        assertEquals("com.bank.app", dao.rows[0].eventData)
    }

    @Test
    fun `the purge actually removes rows the old seam wrote`() = runTest {
        val dao = FakeLifeLogDao()
        val archive = archiveOver(dao)
        // Точний вигляд того, що писав `event.toString()` до виправлення.
        archive.archiveEvent(
            "Notification",
            "Notification(pkg=org.telegram.messenger, title=Оля, text=код 4821)",
        )
        archive.archiveEvent("NotificationBurst", "NotificationBurst(pkg=com.viber.voip, ...)")
        archive.archiveEvent("dialog_digest", "розмова про каву")

        val purged = LifeLogEventArchiver(archive).purgeRawNotificationRows()

        assertEquals(2, purged)
        assertEquals(1, dao.rows.size)
        assertEquals("dialog_digest", dao.rows[0].eventType)
        val recalled = archive.searchMemories("4821", limit = 10).joinToString("\n")
        assertFalse(recalled, recalled.contains("4821"))
    }
}
