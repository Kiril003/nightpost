package local.phantom.companion.core.solana

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SeekerGenesisTest {

    private val sgt = "HAF57K9jMt61dZnQ5pqwNeuBgfguSRNqXqiuNjaF1Vtr"
    private val other = "So11111111111111111111111111111111111111112"

    private fun json(text: String): JsonElement = SolanaRpc.JSON.parseToJsonElement(text)
    private fun fixture(name: String) = javaClass.getResource("/solana/$name")!!.readText()

    private fun account(mint: String, amount: String) =
        """{"pubkey":"x","account":{"data":{"parsed":{"info":{"mint":"$mint","owner":"o","tokenAmount":{"amount":"$amount","decimals":0}},"type":"account"},"program":"spl-token-2022"},"owner":"TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"}}"""

    @Test
    fun emptiedAccountsDoNotCount() {
        val accounts = json("""{"context":{"slot":1},"value":[${account(sgt, "0")},${account(other, "5")}]}""")
        assertEquals(listOf(other), SeekerGenesis.heldMints(accounts))
    }

    @Test
    fun realSgtMintIsRecognisedAndOrdinaryMintIsNot() {
        val accounts = json(fixture("sgt_multiple_accounts.json"))
        assertEquals(sgt, SeekerGenesis.genesisMint(listOf(other, sgt), accounts))
    }

    @Test
    fun aMintThatOnlyPointsAtSgtMetadataIsAFake() {
        val forged = fixture("sgt_multiple_accounts.json").replace("\"tokenGroupMember\"", "\"somethingElse\"")
        assertNull(SeekerGenesis.genesisMint(listOf(other, sgt), json(forged)))
        val classic = fixture("sgt_multiple_accounts.json").replace(
            "\"owner\":\"TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb\"",
            "\"owner\":\"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA\"",
        )
        assertNull(SeekerGenesis.genesisMint(listOf(other, sgt), json(classic)))
    }

    @Test
    fun memberOfAnotherGroupIsNotSeeker() {
        val wrongGroup = fixture("sgt_multiple_accounts.json")
            .replace("\"group\": \"GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te\"", "\"group\": \"$other\"")
        assertFalse(SeekerGenesis.isGenesis(sgt, (json(wrongGroup) as kotlinx.serialization.json.JsonObject)["value"]!!.let { (it as kotlinx.serialization.json.JsonArray)[1] }))
    }

    @Test
    fun checkAsksMainnetForToken2022AccountsThenTheirMints() = runTest {
        val calls = mutableListOf<String>()
        val rpc = SolanaRpc("https://mainnet") { _, body ->
            calls += body
            when {
                "getTokenAccountsByOwner" in body ->
                    """{"jsonrpc":"2.0","id":1,"result":{"context":{"slot":1},"value":[${account(other, "1")},${account(sgt, "1")}]}}"""
                else -> """{"jsonrpc":"2.0","id":1,"result":${fixture("sgt_multiple_accounts.json")}}"""
            }
        }
        val verdict = SeekerGenesis.check(SolanaKey.of(ByteArray(32) { 1 })!!, rpc)
        assertEquals(SeekerGenesis.Verdict.Holder(sgt), verdict)
        assert("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb" in calls[0])
    }

    @Test
    fun walletWithoutTokensIsNotHolderWithoutSecondCall() = runTest {
        var n = 0
        val rpc = SolanaRpc("x") { _, _ -> n++; """{"jsonrpc":"2.0","id":1,"result":{"value":[]}}""" }
        assertEquals(SeekerGenesis.Verdict.NotHolder, SeekerGenesis.check(SolanaKey.of(ByteArray(32) { 1 })!!, rpc))
        assertEquals(1, n)
    }
}
