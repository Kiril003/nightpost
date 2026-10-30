package local.phantom.companion.core.data.toolaudit

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Слід одного виклику інструмента: що застосунок зробив, за чиїм словом і чим
 * це скінчилось. Дзеркало `core.tool.diary.DecisionEntry` на диску.
 *
 * НЕ плутати з `core.data.diary.DecisionEntry` — то особистий журнал рішень
 * оператора (заголовок, контекст, статус), інша таблиця й інший екран.
 *
 * [risk] — тариф ризику інструмента НА МОМЕНТ виклику, [decision] — як
 * вирішився дозвіл ("read"|"once"|"always"|"never"|"denied"). Обидва
 * зберігаються рядками, а не enum'ами: значення в коді ще перейменують, а
 * рядок, що вже ліг на диск, мусить читатись і через рік.
 */
@Entity(
    tableName = "tool_audit",
    indices = [Index(value = ["timestamp"])],
)
data class ToolAuditEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val toolId: String,
    val args: String,
    val outcome: String,
    val outputDigest: String,
    val agentId: String?,
    val agentDepth: Int,
    val risk: String,
    val decision: String?,
)
