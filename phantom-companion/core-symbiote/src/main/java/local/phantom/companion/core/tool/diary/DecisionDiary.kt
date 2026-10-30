package local.phantom.companion.core.tool.diary

/**
 * Append-only audit log of every tool invocation.
 *
 * Phase 4 will provide a Room-backed persistent implementation; until then
 * [InMemoryDecisionDiary] is the only concrete class (used in tests and
 * live in the running app as a smoke-capable placeholder).
 *
 * Both methods are `suspend` so that a future Room implementation can
 * execute them on the IO dispatcher without changing call sites.
 */
interface DecisionDiary {

    /**
     * Appends [entry] to the diary. Must never throw — if persistence fails
     * the implementation should log and swallow the error.
     */
    suspend fun write(entry: DecisionEntry)

    /**
     * Returns the [limit] most recent entries, newest first.
     *
     * @param limit Maximum number of entries to return. Defaults to 50.
     */
    suspend fun recent(limit: Int = 50): List<DecisionEntry>
}
