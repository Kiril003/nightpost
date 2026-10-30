package local.phantom.companion.ui.messenger

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonPrimitive
import local.phantom.companion.ui.home.HomeTab

/** `PhantomBridge.navigate(to, argsJson)` — docs/messenger/NAV_CONTRACT.md. Pure: org.json is a stub in unit tests. */
internal object WebNav {

    sealed interface Act {
        data class Tab(val tab: HomeTab, val peerId: String? = null, val pickMeetup: Boolean = false) : Act
        data object CallPhantom : Act
        data object Scan : Act
    }

    private val PEER = Regex(""""peerId"\s*:\s*"([^"\\]+)"""")
    private val PICK_MEETUP = Regex(""""pick"\s*:\s*"meetup"""")

    fun parse(to: String?, argsJson: String?): Act? = when (to?.trim()) {
        "chats" -> Act.Tab(HomeTab.CHATS)
        "profile" -> Act.Tab(HomeTab.PROFILE)
        "map" -> Act.Tab(
            HomeTab.MAP,
            argsJson?.let { PEER.find(it)?.groupValues?.get(1) },
            pickMeetup = argsJson?.let { PICK_MEETUP.containsMatchIn(it) } == true,
        )
        "call-phantom" -> Act.CallPhantom
        "scan" -> Act.Scan
        else -> null
    }

    sealed interface Door {
        data class OpenTab(val key: String) : Door
        data object ComposeMoment : Door
        data class OpenMoment(val id: String) : Door
        data object ShareIn : Door
        data object OpenProfiles : Door
        data object ProfilesBack : Door
        data object OpenHelp : Door

        /** Веб-місце без рідного екрана: [PLACE_PEOPLE], [PLACE_BUILDER]. */
        data class OpenPlace(val key: String) : Door

        /** Вкладка напряму над списком розмов; порожній id знімає шар. */
        data class OpenDirection(val id: String) : Door

        /** Посилання-запрошення в гілку: аркуш «Приєднатися». */
        data class OpenGroupLink(val link: String) : Door
    }

    data class Ask(val door: Door, val expiresAtMs: Long)

    enum class Outcome { OPENED, REFUSED, MISSING, EXPIRED }

    val pending = MutableStateFlow<Ask?>(null)

    const val TTL_MS = 5 * 60_000L
    const val TRIES = 20
    const val RETRY_MS = 500L

    fun request(door: Door, nowMs: Long = System.currentTimeMillis()) {
        pending.value = Ask(door, nowMs + TTL_MS)
    }

    fun script(door: Door): String = when (door) {
        is Door.OpenTab ->
            "(function(){var f=window.__phantomOpenTab;" +
                "if(typeof f!=='function')return 'missing';" +
                "return f(${JsonPrimitive(door.key)})?'opened':'refused';})()"
        Door.ComposeMoment ->
            "(function(){var f=window.__phantomComposeMoment;" +
                "if(typeof f!=='function')return 'missing';" +
                "return f()===false?'refused':'opened';})()"
        is Door.OpenMoment ->
            "(function(){var f=window.__phantomOpenMoment;" +
                "if(typeof f!=='function')return 'missing';" +
                "return f(${JsonPrimitive(door.id)})?'opened':'refused';})()"
        Door.ShareIn ->
            "(function(){var f=window.__phantomShareIn;" +
                "if(typeof f!=='function')return 'missing';" +
                "return f()===false?'refused':'opened';})()"
        Door.OpenProfiles ->
            "(function(){var p=window.__phantomProfiles;" +
                "if(!p||typeof p.open!=='function')return 'missing';" +
                "return window.__phantomProfiles.open()===false?'refused':'opened';})()"
        is Door.OpenDirection ->
            "(function(){var f=window.__phantomOpenDirection;" +
                "if(typeof f!=='function')return 'missing';" +
                "return f(${JsonPrimitive(door.id)})?'opened':'refused';})()"
        Door.OpenHelp ->
            "(function(){var f=window.__phantomOpenHelp;" +
                "if(typeof f!=='function')return 'missing';" +
                "return f()===false?'refused':'opened';})()"
        is Door.OpenPlace ->
            "(function(){var f=window.__phantomOpenPlace;" +
                "if(typeof f!=='function')return 'missing';" +
                "return f(${JsonPrimitive(door.key)})?'opened':'refused';})()"
        is Door.OpenGroupLink ->
            "(function(){var f=window.__phantomOpenGroupLink;" +
                "if(typeof f!=='function')return 'missing';" +
                "return f(${JsonPrimitive(door.link)})?'opened':'refused';})()"
        Door.ProfilesBack ->
            "(function(){var p=window.__phantomProfiles;" +
                "if(!p||typeof p.back!=='function')return 'missing';" +
                "return String(window.__phantomProfiles.back());})()"
    }

    fun openHelp(nowMs: Long = System.currentTimeMillis(), switchTab: (HomeTab) -> Unit) {
        request(Door.OpenHelp, nowMs)
        switchTab(HomeTab.CHATS)
    }

    const val PLACE_PEOPLE = "people"
    const val PLACE_BUILDER = "builder"

    fun openPlace(key: String, nowMs: Long = System.currentTimeMillis(), switchTab: (HomeTab) -> Unit) {
        request(Door.OpenPlace(key), nowMs)
        switchTab(HomeTab.CHATS)
    }

    fun openGroupLink(link: String, nowMs: Long = System.currentTimeMillis(), switchTab: (HomeTab) -> Unit) {
        request(Door.OpenGroupLink(link), nowMs)
        switchTab(HomeTab.CHATS)
    }

    fun outcome(raw: String?): Outcome = when (raw?.trim('"')?.substringBefore(':')) {
        "opened" -> Outcome.OPENED
        "refused" -> Outcome.REFUSED
        else -> Outcome.MISSING
    }

    suspend fun deliver(
        ask: Ask,
        run: suspend (script: String) -> String?,
        pause: suspend (ms: Long) -> Unit = { kotlinx.coroutines.delay(it) },
        now: () -> Long = System::currentTimeMillis,
    ): Outcome {
        repeat(TRIES) {
            if (now() >= ask.expiresAtMs) {
                pending.compareAndSet(ask, null)
                return Outcome.EXPIRED
            }
            val result = outcome(run(script(ask.door)))
            if (result != Outcome.MISSING) {
                pending.compareAndSet(ask, null)
                return result
            }
            pause(RETRY_MS)
        }
        return Outcome.MISSING
    }
}
