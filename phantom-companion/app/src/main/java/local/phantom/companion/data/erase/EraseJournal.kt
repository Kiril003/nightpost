package local.phantom.companion.data.erase

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class EraseStep {
    NONE,
    CLAIM_DONE,
    ROOT_DONE,
    LICENCE_DONE,
    PENDING,
}

interface EraseJournal {
    fun read(): EraseStep
    fun write(step: EraseStep)
    fun dueAtMs(): Long = 0L
    fun writePending(dueAtMs: Long) {
        write(EraseStep.PENDING)
    }
}

class PrefsEraseJournal(context: Context) : EraseJournal {
    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    init {
        syncState()
    }

    override fun read(): EraseStep {
        val raw = prefs.getString(KEY_STEP, null) ?: return EraseStep.NONE
        return EraseStep.entries.firstOrNull { it.name == raw } ?: EraseStep.NONE
    }

    override fun write(step: EraseStep) {
        val editor = prefs.edit().putString(KEY_STEP, step.name)
        if (step != EraseStep.PENDING) editor.remove(KEY_DUE_AT)
        editor.commit()
        syncState()
    }

    override fun dueAtMs(): Long = prefs.getLong(KEY_DUE_AT, 0L)

    override fun writePending(dueAtMs: Long) {
        prefs.edit()
            .putString(KEY_STEP, EraseStep.PENDING.name)
            .putLong(KEY_DUE_AT, dueAtMs)
            .commit()
        syncState()
    }

    private fun syncState() {
        val step = read()
        _state.value = State(step, if (step == EraseStep.PENDING) dueAtMs() else 0L)
    }

    data class State(val step: EraseStep, val dueAtMs: Long)

    companion object {
        const val FILE_NAME = "nightpost_erase_v1"
        private const val KEY_STEP = "step"
        private const val KEY_DUE_AT = "due_at"

        private val _state = MutableStateFlow(State(EraseStep.NONE, 0L))
        val state: StateFlow<State> = _state.asStateFlow()
    }
}
