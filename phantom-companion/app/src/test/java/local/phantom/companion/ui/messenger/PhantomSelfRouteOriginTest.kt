package local.phantom.companion.ui.messenger

import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.data.memory.MemoryFactStore
import local.phantom.companion.self.CSharpTutor
import local.phantom.companion.self.CommitmentResolver
import local.phantom.companion.self.CommitmentService
import local.phantom.companion.self.CommitmentSpeech
import local.phantom.companion.self.CommitmentState
import local.phantom.companion.self.FakeFactDao
import local.phantom.companion.self.FakeReminders
import local.phantom.companion.self.FakeTutorState
import local.phantom.companion.self.InMemoryCommitmentStore
import local.phantom.companion.self.PhantomSelf
import local.phantom.companion.self.SelfMemory
import local.phantom.companion.self.plain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhantomSelfRouteOriginTest {

    private var clock = 1_000L
    private val dao = FakeFactDao()
    private val commitmentStore = InMemoryCommitmentStore()
    private val roster = mutableListOf(
        CommitmentResolver.RosterPerson("peer_olia_1", "Оля Коваль", "family"),
        CommitmentResolver.RosterPerson("peer_olia_2", "Оля Мельник", "work"),
    )
    private val commitments = CommitmentService(
        store = commitmentStore,
        roster = { roster },
        now = { clock },
        zone = { ZoneId.of("UTC") },
    )
    private val memory = SelfMemory(MemoryFactStore(dao)) { clock++ }
    private val self = PhantomSelf(
        memory = memory,
        tutor = CSharpTutor(FakeTutorState(), memory, { t, _ -> t.id }, FakeReminders()),
        hasBrain = { false },
        render = ::plain,
        commitments = commitments,
        now = { clock },
    )

    // Only ("peer_chat_1", "msg_from_owner") is authored by the owner.
    private val ownerMessages = setOf("peer_chat_1" to "msg_from_owner")

    private val route = PhantomSelfRoute(
        self = { self },
        label = { it.name },
        isOwnerMessage = { chatId, msgId -> (chatId to msgId) in ownerMessages },
    )

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `peer chat message claiming owner_statement without owner message_id is treated as PEER_MESSAGE`() = runBlocking {
        // Non-explicit -> 403 needs_explicit_remember even though caller claimed owner_statement
        val refused = json(
            route.handle(
                "POST",
                listOf("messenger", "self", "facts"),
                """{"text":"Я живу в Одесі","origin":"owner_statement","chat_id":"peer_chat_1","message_id":"msg_from_peer"}""",
            ),
        )
        assertEquals("false", refused["ok"].toString())
        assertEquals("403", refused["status"].toString())
        assertEquals("needs_explicit_remember", refused["code"]!!.jsonPrimitive.content)

        // Omitting chat_id while passing a peer message_id must also be treated as PEER_MESSAGE
        val missingChatRefused = json(
            route.handle(
                "POST",
                listOf("messenger", "self", "facts"),
                """{"text":"Я живу в Харкові","origin":"owner_statement","message_id":"msg_from_peer"}""",
            ),
        )
        assertEquals("false", missingChatRefused["ok"].toString())
        assertEquals("403", missingChatRefused["status"].toString())
        assertEquals("needs_explicit_remember", missingChatRefused["code"]!!.jsonPrimitive.content)

        // Explicit "remember this" is the owner's own act, as in Ledger.rememberText.
        val explicit = json(
            route.handle(
                "POST",
                listOf("messenger", "self", "facts"),
                """{"text":"Олег винен 200","origin":"owner_statement","explicit":true,"chat_id":"peer_chat_1","message_id":"msg_from_peer"}""",
            ),
        )
        assertEquals("true", explicit["ok"].toString())
    }

    @Test
    fun `verified owner message in peer chat allows owner_statement and caller origin can only downgrade`() = runBlocking {
        val allowed = json(
            route.handle(
                "POST",
                listOf("messenger", "self", "facts"),
                """{"text":"Я працюю інженером","origin":"owner_statement","chat_id":"peer_chat_1","message_id":"msg_from_owner"}""",
            ),
        )
        assertEquals("true", allowed["ok"].toString())
        assertEquals("owner_said", allowed["data"]!!.jsonObject["source"]!!.jsonPrimitive.content)

        // Caller claiming peer_message for an owner message downgrades to PEER_MESSAGE
        val downgraded = json(
            route.handle(
                "POST",
                listOf("messenger", "self", "facts"),
                """{"text":"Я люблю зелений чай","origin":"peer_message","chat_id":"peer_chat_1","message_id":"msg_from_owner"}""",
            ),
        )
        assertEquals("403", downgraded["status"].toString())
    }

    @Test
    fun `commitments bridge route handles choose, list, done and drop`() = runBlocking {
        assertTrue(route.claims(listOf("messenger", "self", "commitments")))
        val parsed = CommitmentSpeech.parse("Надішлю Олі фото завтра", clock, ZoneId.of("UTC"))!!
        val res = commitments.record("Надішлю Олі фото завтра", parsed, "msg_1") as CommitmentResolver.Resolution.Ambiguous

        val chosenResp = json(
            route.handle(
                "POST",
                listOf("messenger", "self", "commitments"),
                """{"choose":{"pending_id":"${res.pending.id}","peer_id":"peer_olia_2"}}""",
            ),
        )
        assertEquals("true", chosenResp["ok"].toString())
        val savedId = chosenResp["data"]!!.jsonObject["id"]!!.jsonPrimitive.content
        assertEquals("peer_olia_2", chosenResp["data"]!!.jsonObject["peer_id"]!!.jsonPrimitive.content)

        val listResp = json(route.handle("GET", listOf("messenger", "self", "commitments")))
        val items = listResp["data"] as JsonArray
        assertEquals(1, items.size)

        val doneResp = json(route.handle("POST", listOf("messenger", "self", "commitments", savedId, "done")))
        assertEquals("true", doneResp["ok"].toString())
        assertEquals(CommitmentState.DONE, commitmentStore.get(savedId)?.state)

        val dropResp = json(route.handle("POST", listOf("messenger", "self", "commitments", savedId, "drop")))
        assertEquals("true", dropResp["ok"].toString())
        assertEquals(CommitmentState.DROPPED, commitmentStore.get(savedId)?.state)
    }

    @Test
    fun `commitments bridge route creates direct commitment from outgoing promise tag`() = runBlocking {
        val createResp = json(
            route.handle(
                "POST",
                listOf("messenger", "self", "commitments"),
                """{"person":"Оля Коваль","peer_id":"peer_olia_1","what":"скіну звіт","text":"Скину звіт завтра о 18:00","due_ms":1761580800000,"source_message_id":"msg_out_42"}""",
            ),
        )
        assertEquals("true", createResp["ok"].toString())
        val data = createResp["data"]!!.jsonObject
        val id = data["id"]!!.jsonPrimitive.content
        assertEquals("Оля Коваль", data["person"]!!.jsonPrimitive.content)
        assertEquals("peer_olia_1", data["peer_id"]!!.jsonPrimitive.content)
        assertEquals("скіну звіт", data["what"]!!.jsonPrimitive.content)
        assertEquals("1761580800000", data["due_ms"]!!.jsonPrimitive.content)
        assertEquals("msg_out_42", data["source_message_id"]!!.jsonPrimitive.content)

        // Duplicate tap with same source_message_id returns the same open commitment without duplicating
        val dupResp = json(
            route.handle(
                "POST",
                listOf("messenger", "self", "commitments"),
                """{"person":"Оля Коваль","peer_id":"peer_olia_1","what":"скіну звіт","due_ms":1761580800000,"source_message_id":"msg_out_42"}""",
            ),
        )
        assertEquals(id, dupResp["data"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(1, commitmentStore.listOpen().count { it.sourceMessageId == "msg_out_42" })
    }
}
