package local.phantom.companion.ui.messenger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.phantom.companion.R
import local.phantom.companion.core.net.peer.PeerDirectory
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerProfile
import local.phantom.companion.core.net.peer.PeerRoster
import local.phantom.companion.data.AppContainer
import local.phantom.companion.data.PeerDirectoryStore
import local.phantom.companion.data.PeerHandleBook
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Пошук людини за іменем — та половина довідника, яку бачить веб.
 *
 * Веб уміє питати список користувачів вузла; телефон такого списку не має й
 * мати не буде. Тому `users` тут завжди повертає нуль або один запис, і це не
 * обмеження видачі, а форма: перелік ніде не збирається, тож і віддати його
 * нема звідки. Питати можна ЛИШЕ повним іменем, яке тобі вже назвали.
 *
 * «Нікого немає» і «не спитали» — різні відповіді. Порожній масив каже перше,
 * 503 з причиною — друге; звести їх докупи означало б сказати людині, що її
 * знайомого не існує, бо в метро немає мережі.
 */
internal class WebMessengerDirectory private constructor(
    private val container: AppContainer?,
    private val announce: (PeerProfile.Field<String>) -> Unit,
    private val find: suspend (String) -> PeerDirectoryStore.Found,
    private val myPeerId: () -> String,
    private val sayRes: (Int) -> String,
) {
    constructor(
        container: AppContainer,
        announce: (PeerProfile.Field<String>) -> Unit,
    ) : this(
        container = container,
        announce = announce,
        find = { name -> withContext(Dispatchers.IO) { container.directory.lookup(name) } },
        myPeerId = {
            container.mesh.selfIdFlow.value.ifBlank {
                kotlin.runCatching { container.identity.get().id }.getOrDefault("")
            }
        },
        sayRes = { id -> container.applicationContext.getString(id) },
    )

    internal constructor(
        find: suspend (String) -> PeerDirectoryStore.Found,
        myPeerId: () -> String = { "" },
        say: (Int) -> String = { "err_$it" },
    ) : this(
        container = null,
        announce = {},
        find = find,
        myPeerId = myPeerId,
        sayRes = say,
    )


    private val app: AppContainer
        get() = checkNotNull(container)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checking = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var renewing = false

    private val handles by lazy { PeerHandleBook.open(app.applicationContext) }

    /** Звірений @нік людини; незвірену заяву тихо віддає довіднику на перевірку. */
    fun handleOf(p: PeerIdentity): String? {
        val now = System.currentTimeMillis()
        handles.due(p, now)?.let { claim ->
            if (checking.add(p.id)) {
                scope.launch {
                    try {
                        val owner = when (val found = app.directory.lookup(claim)) {
                            is PeerDirectoryStore.Found.One -> PeerHandleBook.Owner.Key(found.invite.peerId)
                            PeerDirectoryStore.Found.None -> PeerHandleBook.Owner.Nobody
                            is PeerDirectoryStore.Found.NotProven -> PeerHandleBook.Owner.Nobody
                            else -> PeerHandleBook.Owner.Unknown
                        }
                        handles.settle(p, claim, owner, System.currentTimeMillis())
                    } finally {
                        checking.remove(p.id)
                    }
                }
            }
        }
        return handles.shown(p)
    }

    /** Мій нік для кадру профілю. */
    fun handleField(): PeerProfile.Field<String> =
        app.directory.handle.value?.let { PeerProfile.Field.Set(it) } ?: PeerProfile.Field.Absent

    suspend fun route(
        method: String,
        seg: List<String>,
        query: String,
        body: String? = null,
        conversationOf: (String) -> JSONObject? = { null },
    ): String = when {
        seg.getOrNull(2) == "users" && method == "GET" -> users(queryOf(query))
        seg.getOrNull(2) == "lookup" && method == "GET" -> lookup(query)
        seg.getOrNull(2) == "start-chat" && method == "POST" -> startChat(body, conversationOf)
        seg.getOrNull(2) == "me" && method == "GET" -> ok(me())
        seg.getOrNull(2) == "me" && method == "POST" -> publish(body)
        seg.getOrNull(2) == "me" && method == "DELETE" -> withdraw()
        else -> fail(404, "not_found", say(R.string.dir_err_no_route))
    }

    // ── пошук ────────────────────────────────────────────────────────────

    private suspend fun users(raw: String): String {
        val found = find(raw)
        val rows = when (found) {
            is PeerDirectoryStore.Found.One ->
                PeerDirectory.rows(found.handle, found.invite, found.compact)
            // Не імʼя — це те саме «нікого», що й порожня відповідь сервера:
            // сервера ми навіть не питали, і сказати тут нема про що.
            PeerDirectoryStore.Found.BadHandle,
            PeerDirectoryStore.Found.None,
            -> emptyList()
            PeerDirectoryStore.Found.NoAddress ->
                return fail(503, "no_directory", say(R.string.dir_err_no_address))
            // Слово сервера, а не наше: «довідник ще не відкрито» — це не
            // «нікого немає», і порожній масив тут був би брехнею про людину.
            is PeerDirectoryStore.Found.Closed ->
                return fail(503, "directory_closed", found.detail)
            PeerDirectoryStore.Found.Unreachable ->
                return fail(503, "unreachable", say(R.string.dir_err_unreachable))
            is PeerDirectoryStore.Found.NotProven ->
                return fail(502, "not_proven", say(R.string.dir_err_not_proven))
        }
        val arr = JSONArray()
        rows.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("username", it.handle)
                    .put("display_name", realName(it.name, it.id))
                    .put("compact", it.compact),
            )
        }
        return ok(arr)
    }

    private suspend fun startChat(body: String?, conversationOf: (String) -> JSONObject?): String {
        val asked = body?.let { JSONObject(it).optString("username") }
        val found = withContext(Dispatchers.IO) { app.directory.lookup(asked) }
        return when (found) {
            is PeerDirectoryStore.Found.One -> {
                // Джерело — REMOTE (типове для accept): довідник доводить, що
                // запис склав власник ключа, і НЕ доводить, що ключ належить
                // тому, кого шукали. Контакт лишається незвіреним.
                val peer = withContext(Dispatchers.IO) { app.mesh.accept(found.invite) }
                handles.remember(peer.id, found.handle, System.currentTimeMillis())
                // Обгортка та сама, що й у POST /messenger/conversations: веб
                // розпаковує {ok,status,data}, і голий обʼєкт він не прочитає.
                conversationOf(peer.id)?.let { ok(it) }
                    ?: fail(500, "no_conversation", say(R.string.dir_err_refused))
            }
            PeerDirectoryStore.Found.BadHandle ->
                fail(400, "bad_handle", say(R.string.dir_err_bad_handle))
            PeerDirectoryStore.Found.None ->
                fail(404, "no_user", say(R.string.dir_err_none))
            PeerDirectoryStore.Found.NoAddress ->
                fail(503, "no_directory", say(R.string.dir_err_no_address))
            is PeerDirectoryStore.Found.Closed ->
                fail(503, "directory_closed", found.detail)
            PeerDirectoryStore.Found.Unreachable ->
                fail(503, "unreachable", say(R.string.dir_err_unreachable))
            is PeerDirectoryStore.Found.NotProven ->
                fail(502, "not_proven", say(R.string.dir_err_not_proven))
        }
    }

    private suspend fun lookup(query: String): String =
        lookup(
            query = query,
            myPeerId = myPeerId(),
            badHandleDetail = say(R.string.dir_err_bad_handle),
            noAddressDetail = say(R.string.dir_err_no_address),
            unreachableDetail = say(R.string.dir_err_unreachable),
            notProvenDetail = say(R.string.dir_err_not_proven),
            find = find,
        )

    // ── моє імʼя ─────────────────────────────────────────────────────────

    private fun me(): JSONObject {
        val name = app.directory.handle.value
        val until = app.directory.untilSeconds.value
        if (name != null && !renewing && PeerDirectoryStore.dueForRenewal(until, System.currentTimeMillis())) {
            renewing = true
            scope.launch {
                try {
                    app.directory.publish(name)
                } finally {
                    renewing = false
                }
            }
        }
        return JSONObject()
            .put("handle", name ?: JSONObject.NULL)
            .put("published", name != null)
            .put("until", if (name != null && until > 0L) iso(until) else JSONObject.NULL)
    }

    private suspend fun publish(body: String?): String {
        val asked = body?.let { JSONObject(it).optString("handle") }
        return when (val outcome = withContext(Dispatchers.IO) { app.directory.publish(asked) }) {
            is PeerDirectoryStore.Outcome.Published -> {
                announce(PeerProfile.Field.Set(outcome.handle))
                ok(me())
            }
            PeerDirectoryStore.Outcome.Taken -> fail(409, "taken", say(R.string.dir_err_taken))
            is PeerDirectoryStore.Outcome.Closed ->
                fail(503, "directory_closed", outcome.detail)
            PeerDirectoryStore.Outcome.BadHandle ->
                fail(400, "bad_handle", say(R.string.dir_err_bad_handle))
            PeerDirectoryStore.Outcome.NoIdentity ->
                fail(503, "no_identity", say(R.string.dir_err_no_identity))
            PeerDirectoryStore.Outcome.NoAddress ->
                fail(503, "no_directory", say(R.string.dir_err_no_address))
            PeerDirectoryStore.Outcome.Unreachable ->
                fail(503, "unreachable", say(R.string.dir_err_unreachable))
            else -> fail(502, "refused", say(R.string.dir_err_refused))
        }
    }

    private suspend fun withdraw(): String =
        when (val outcome = withContext(Dispatchers.IO) { app.directory.withdraw() }) {
            PeerDirectoryStore.Outcome.Withdrawn -> {
                announce(PeerProfile.Field.Cleared)
                ok(me())
            }
            is PeerDirectoryStore.Outcome.Closed ->
                fail(503, "directory_closed", outcome.detail)
            PeerDirectoryStore.Outcome.NoAddress ->
                fail(503, "no_directory", say(R.string.dir_err_no_address))
            PeerDirectoryStore.Outcome.NoIdentity ->
                fail(503, "no_identity", say(R.string.dir_err_no_identity))
            PeerDirectoryStore.Outcome.Unreachable ->
                fail(503, "unreachable", say(R.string.dir_err_unreachable))
            else -> fail(502, "refused", say(R.string.dir_err_refused))
        }

    // ── службове ─────────────────────────────────────────────────────────

    private fun queryOf(query: String): String =
        query.split('&').firstOrNull { it.startsWith("query=") }
            ?.substringAfter('=')
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            .orEmpty()

    private fun say(id: Int): String = sayRes(id)

    private fun iso(seconds: Long): String = Instant.ofEpochSecond(seconds).toString()

    private fun ok(data: Any): String =
        JSONObject().put("ok", true).put("status", 200).put("data", data).toString()

    private fun fail(status: Int, code: String, detail: String): String =
        JSONObject().put("ok", false).put("status", status).put("code", code)
            .put("detail", detail).toString()

    companion object {
        /** Імʼя людини або порожньо: короткий код ключа іменем не буває. */
        fun realName(name: String, id: String): String =
            name.takeUnless { it.isBlank() || it == PeerRoster.shortId(id) } ?: ""

        /** Заголовок розмови: імʼя, без імені — `@нік` або [untitled]; код ключа іменем не стає. */
        fun title(shown: String, name: String, id: String, handle: String?, duplicate: Boolean, untitled: String): String = when {
            realName(name, id).isEmpty() -> handle?.takeIf { it.isNotBlank() }?.let { "@$it" } ?: untitled
            duplicate && !handle.isNullOrBlank() -> "$shown · @$handle"
            else -> shown
        }
        internal fun handleParam(query: String): String =
            query.split('&').firstOrNull { it.startsWith("handle=") }
                ?.substringAfter('=')
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                .orEmpty()

        internal suspend fun lookup(
            query: String,
            myPeerId: String = "",
            badHandleDetail: String = "bad_handle",
            noAddressDetail: String = "no_directory",
            unreachableDetail: String = "unreachable",
            notProvenDetail: String = "not_proven",
            find: suspend (String) -> PeerDirectoryStore.Found,
        ): String {
            val name = PeerDirectory.normaliseForPublish(handleParam(query))
                ?: return lookupFail(400, "bad_handle", badHandleDetail)
            return when (val found = find(name)) {
                is PeerDirectoryStore.Found.One -> {
                    val mine = myPeerId.isNotBlank() && found.invite.peerId == myPeerId
                    lookupOk(name, available = mine, mine = mine)
                }
                PeerDirectoryStore.Found.None -> lookupOk(name, available = true)
                PeerDirectoryStore.Found.BadHandle ->
                    lookupFail(400, "bad_handle", badHandleDetail)
                PeerDirectoryStore.Found.NoAddress ->
                    lookupFail(503, "no_directory", noAddressDetail)
                is PeerDirectoryStore.Found.Closed ->
                    lookupFail(503, "directory_closed", found.detail)
                PeerDirectoryStore.Found.Unreachable ->
                    lookupFail(503, "unreachable", unreachableDetail)
                is PeerDirectoryStore.Found.NotProven ->
                    lookupFail(502, "not_proven", notProvenDetail)
            }
        }

        private fun lookupOk(handle: String, available: Boolean, mine: Boolean = false): String =
            buildJsonObject {
                put("ok", true)
                put("status", 200)
                put(
                    "data",
                    buildJsonObject {
                        put("handle", handle)
                        put("available", available)
                        put("taken", !available)
                        if (mine) put("mine", true)
                    },
                )
            }.toString()

        private fun lookupFail(status: Int, code: String, detail: String): String =
            buildJsonObject {
                put("ok", false)
                put("status", status)
                put("code", code)
                put("detail", detail)
            }.toString()
    }
}
