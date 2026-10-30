package local.phantom.companion.feature.stream.diary

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import local.phantom.companion.core.data.diary.DecisionEntry
import local.phantom.companion.core.data.diary.DiaryStore

/**
 * Phase 3-D2 — incoming `decision_diary/<verb>` consumer.
 *
 * Subscribes to a parsed [DiaryWireMessage] flow (the :app side feeds
 * `phantomViewModel.miscEvents` through [DiaryWireParser.tryParse]),
 * dispatches each verb to the appropriate [DiaryStore] mutation, and
 * surfaces `follow_up` messages on a hot [followUps] SharedFlow that
 * Phase 3-D5's TimeShiftedHelp card subscribes to.
 *
 * Verb handling:
 *  - Created → [DiaryStore.update] (insert OR replace, idempotent)
 *  - Updated → fetch existing + merge status/context + [DiaryStore.update];
 *              if no entry exists locally yet (out-of-order delivery),
 *              the update is silently dropped — backend should re-emit
 *              the original `created` first
 *  - Deleted → [DiaryStore.delete]
 *  - FollowUp → emit on [followUps] SharedFlow (no store mutation —
 *               follow-up is a transient prompt, not a stored decision)
 *
 * Pure logic class; no Compose, no Android. Tests instantiate with a
 * fake DiaryStore and a MutableSharedFlow event source.
 */
class DiaryWebSocketSubscriber(
    private val store: DiaryStore,
) {

    private val _followUps = MutableSharedFlow<DiaryWireMessage.FollowUp>(
        replay = 0,
        extraBufferCapacity = 8,
    )
    /** Hot stream of follow-up prompts from the server. UI subscribes here. */
    val followUps: SharedFlow<DiaryWireMessage.FollowUp> = _followUps.asSharedFlow()

    /**
     * Begin collecting from [messages]. Returns the [Job] so callers can
     * cancel for tests / lifecycle teardown. Idempotent in the sense that
     * a second call starts a parallel collector — guard at the call site
     * if you don't want that.
     */
    fun subscribe(scope: CoroutineScope, messages: Flow<DiaryWireMessage>): Job =
        scope.launch {
            messages.collect { handle(it) }
        }

    /** Pure dispatch — exposed for unit tests that want to fire one event at a time. */
    suspend fun handle(message: DiaryWireMessage) {
        when (message) {
            is DiaryWireMessage.Created -> store.update(message.entry)
            is DiaryWireMessage.Updated -> mergeUpdate(message)
            is DiaryWireMessage.Deleted -> store.delete(message.id)
            is DiaryWireMessage.FollowUp -> _followUps.emit(message)
        }
    }

    /**
     * Server emits `updated` with `{id, status, context?}`; phone reads
     * the existing entry, applies the diff, writes it back. If the
     * entry doesn't exist locally yet (out-of-order delivery, lost
     * Created event during a network blip), the update is dropped —
     * backend will re-broadcast on reconnect.
     */
    private suspend fun mergeUpdate(update: DiaryWireMessage.Updated) {
        val existing = store.getEntry(update.id) ?: return
        val merged = DecisionEntry(
            id = existing.id,
            createdAt = existing.createdAt,
            title = existing.title,
            context = update.context ?: existing.context,
            status = update.status,
        )
        store.update(merged)
    }
}
