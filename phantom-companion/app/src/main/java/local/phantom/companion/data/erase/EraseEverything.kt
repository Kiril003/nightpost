package local.phantom.companion.data.erase

class EraseEverything(
    private val journal: EraseJournal,
    private val server: EraseServer,
    private val licence: suspend () -> Unit,
    private val wipe: (Set<String>) -> Unit,
    private val wipeKey: () -> Unit = {},
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    interface EraseServer {
        suspend fun claim(): Result
        suspend fun root(): Result
        suspend fun cancel(): Result = Result.Unreachable

        sealed interface Result {
            open class Ok(val dueAtMs: Long = 0L) : Result {
                companion object : Ok(0L)

                override fun equals(other: Any?): Boolean = other is Ok && other.dueAtMs == dueAtMs
                override fun hashCode(): Int = dueAtMs.hashCode()
                override fun toString(): String = if (dueAtMs == 0L) "Ok" else "Ok(dueAtMs=$dueAtMs)"
            }

            data class Refused(val status: Int) : Result
            data object Unreachable : Result
        }
    }

    sealed interface Outcome {
        data object Done : Outcome
        data class Pending(val dueAtMs: Long) : Outcome
        data object Restored : Outcome
        data object NotAuthorised : Outcome
        data object Protected : Outcome
        data class Failed(val step: EraseStep, val result: EraseServer.Result) : Outcome
    }

    suspend fun run(authorised: Boolean): Outcome {
        if (!authorised) return Outcome.NotAuthorised
        if (journal.read() == EraseStep.NONE) {
            when (val claimRes = server.claim()) {
                is EraseServer.Result.Ok -> journal.write(EraseStep.CLAIM_DONE)
                is EraseServer.Result.Refused -> {
                    return if (claimRes.status == 403) Outcome.Protected
                    else Outcome.Failed(EraseStep.NONE, claimRes)
                }
                EraseServer.Result.Unreachable -> return Outcome.Failed(EraseStep.NONE, claimRes)
            }
        }
        return when (val rootRes = server.root()) {
            is EraseServer.Result.Ok -> {
                val due = rootRes.dueAtMs.takeIf { it > 0 } ?: (nowMs() + GRACE_MS)
                journal.writePending(due)
                Outcome.Pending(due)
            }
            is EraseServer.Result.Refused -> {
                if (rootRes.status == 403) Outcome.Protected
                else Outcome.Failed(EraseStep.CLAIM_DONE, rootRes)
            }
            EraseServer.Result.Unreachable -> Outcome.Failed(EraseStep.CLAIM_DONE, rootRes)
        }
    }

    suspend fun cancel(authorised: Boolean = true): Outcome {
        if (!authorised) return Outcome.NotAuthorised
        return when (val res = server.cancel()) {
            is EraseServer.Result.Ok -> {
                journal.write(EraseStep.NONE)
                Outcome.Restored
            }
            is EraseServer.Result.Refused -> {
                if (res.status == 404) {
                    finishPending()
                } else {
                    Outcome.Failed(EraseStep.PENDING, res)
                }
            }
            EraseServer.Result.Unreachable -> Outcome.Failed(EraseStep.PENDING, res)
        }
    }

    suspend fun resume(): Outcome? = when (journal.read()) {
        EraseStep.NONE, EraseStep.CLAIM_DONE -> null
        EraseStep.ROOT_DONE -> {
            runCatching { licence() }
            journal.write(EraseStep.LICENCE_DONE)
            wipe(emptySet())
            wipeKey()
            Outcome.Done
        }
        EraseStep.LICENCE_DONE -> {
            wipe(emptySet())
            wipeKey()
            Outcome.Done
        }
        EraseStep.PENDING -> {
            val due = journal.dueAtMs()
            if (due > 0 && nowMs() >= due + DAY_MS) {
                finishPending()
            } else {
                Outcome.Pending(due)
            }
        }
    }

    private suspend fun finishPending(): Outcome {
        runCatching { licence() }
        wipeKey()
        journal.write(EraseStep.NONE)
        return Outcome.Done
    }

    suspend fun thisPhoneOnly() {
        runCatching { licence() }
        journal.write(EraseStep.LICENCE_DONE)
        wipe(emptySet())
        wipeKey()
    }

    companion object {
        const val DAY_MS = 86_400_000L
        const val GRACE_MS = 7 * DAY_MS
    }
}
