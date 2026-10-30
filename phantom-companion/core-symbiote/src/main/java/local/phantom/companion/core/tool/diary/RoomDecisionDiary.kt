package local.phantom.companion.core.tool.diary

import android.util.Log
import kotlinx.coroutines.CancellationException
import local.phantom.companion.core.data.toolaudit.ToolAuditDao
import local.phantom.companion.core.data.toolaudit.ToolAuditEntity
import java.util.concurrent.atomic.AtomicInteger

/**
 * [DecisionDiary], що переживає смерть процесу.
 *
 * [InMemoryDecisionDiary] тримав 500 записів у купі й зникав разом із
 * застосунком — питання «що воно зробило вчора і за чиїм словом» не мало де
 * дістати відповідь. Тут той самий слід лягає в `tool_audit`.
 *
 * Таблиця не росте без меж: [keep] найновіших рядків, решта йде при
 * періодичному обрізанні.
 */
class RoomDecisionDiary(
    private val dao: ToolAuditDao,
    private val keep: Int = KEEP_ROWS,
    private val pruneEvery: Int = PRUNE_EVERY,
) : DecisionDiary {

    private val writes = AtomicInteger(0)

    init {
        // Нуль тут дав би ділення на нуль усередині проковтнутого catch — тобто
        // щоденник, що мовчки не пише нічого.
        require(keep > 0 && pruneEvery > 0) { "keep=$keep pruneEvery=$pruneEvery" }
    }

    override suspend fun write(entry: DecisionEntry) {
        try {
            dao.insert(entry.toEntity())
            // Лічильник з нуля дає обрізання на ПЕРШОМУ записі процесу: хвіст,
            // що наріс перед убивством застосунку, не доживає до другої сесії.
            if (writes.getAndIncrement() % pruneEvery == 0) dao.pruneToNewest(keep)
        } catch (c: CancellationException) {
            // Скасування — не збій диска. Проковтнути його тут означало б
            // мовчки продовжити скасовану агентну петлю.
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "слід виклику «${entry.toolId}» не ліг на диск", t)
        }
    }

    override suspend fun recent(limit: Int): List<DecisionEntry> =
        dao.recent(limit).map { it.toDomain() }

    private fun DecisionEntry.toEntity(): ToolAuditEntity = ToolAuditEntity(
        id = id,
        timestamp = timestamp,
        toolId = toolId,
        args = args,
        outcome = outcome,
        outputDigest = outputDigest,
        agentId = agentId,
        agentDepth = agentDepth,
        risk = risk,
        decision = decision,
    )

    private fun ToolAuditEntity.toDomain(): DecisionEntry = DecisionEntry(
        id = id,
        timestamp = timestamp,
        toolId = toolId,
        args = args,
        outcome = outcome,
        outputDigest = outputDigest,
        agentId = agentId,
        agentDepth = agentDepth,
        risk = risk,
        decision = decision,
    )

    companion object {
        const val KEEP_ROWS: Int = 2000
        const val PRUNE_EVERY: Int = 200
        private const val TAG = "RoomDecisionDiary"
    }
}
