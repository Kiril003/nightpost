package local.phantom.companion.core.solana

import java.io.ByteArrayOutputStream

data class AccountMeta(val key: SolanaKey, val signer: Boolean, val writable: Boolean)

class Instruction(val program: SolanaKey, val accounts: List<AccountMeta>, val data: ByteArray)

object SolanaTx {

    fun message(feePayer: SolanaKey, recentBlockhash: ByteArray, instructions: List<Instruction>): ByteArray {
        require(recentBlockhash.size == 32)
        val metas = LinkedHashMap<SolanaKey, AccountMeta>()
        fun merge(m: AccountMeta) {
            val old = metas[m.key]
            metas[m.key] = if (old == null) m else AccountMeta(m.key, old.signer || m.signer, old.writable || m.writable)
        }
        merge(AccountMeta(feePayer, signer = true, writable = true))
        instructions.forEach { ix ->
            ix.accounts.forEach(::merge)
            merge(AccountMeta(ix.program, signer = false, writable = false))
        }
        val payer = metas.getValue(feePayer)
        // Групи — вимога формату; сортування всередині груп — як у solana-sdk, щоб байти звірялись з еталоном.
        val rest = metas.values.filter { it.key != feePayer }.sortedWith { a, b -> compareBytes(a.key.bytes, b.key.bytes) }
        val ordered = listOf(payer) +
            rest.filter { it.signer && it.writable } +
            rest.filter { it.signer && !it.writable } +
            rest.filter { !it.signer && it.writable } +
            rest.filter { !it.signer && !it.writable }
        val index = ordered.withIndex().associate { (i, m) -> m.key to i }

        val out = ByteArrayOutputStream()
        out.write(ordered.count { it.signer })
        out.write(ordered.count { it.signer && !it.writable })
        out.write(ordered.count { !it.signer && !it.writable })
        shortVec(out, ordered.size)
        ordered.forEach { out.write(it.key.bytes) }
        out.write(recentBlockhash)
        shortVec(out, instructions.size)
        instructions.forEach { ix ->
            out.write(index.getValue(ix.program))
            shortVec(out, ix.accounts.size)
            ix.accounts.forEach { out.write(index.getValue(it.key)) }
            shortVec(out, ix.data.size)
            out.write(ix.data)
        }
        return out.toByteArray()
    }

    fun signerCount(message: ByteArray): Int = message[0].toInt() and 0xff

    /** Транзакція з порожніми місцями під підписи — саме так її приймає гаманець. */
    fun unsigned(message: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val n = signerCount(message)
        shortVec(out, n)
        out.write(ByteArray(64 * n))
        out.write(message)
        return out.toByteArray()
    }

    /** Підписи стоять у порядку перших ключів повідомлення; кожного підписанта мусить бути дано. */
    fun sign(message: ByteArray, signers: List<Ed25519Keypair>): ByteArray {
        val n = signerCount(message)
        val (keyCount, len) = readShortVec(message, 3)!!
        require(keyCount >= n)
        val out = ByteArrayOutputStream()
        shortVec(out, n)
        for (i in 0 until n) {
            val at = 3 + len + 32 * i
            val key = SolanaKey.of(message.copyOfRange(at, at + 32))!!
            val kp = requireNotNull(signers.firstOrNull { it.publicKey == key }) { "missing signer $key" }
            out.write(kp.sign(message))
        }
        out.write(message)
        return out.toByteArray()
    }

    /**
     * Частковий підпис: слоти тих, кого немає в [signers], лишаються нулями — їх заповнить
     * інший підписант (спонсор комісії на сервері). Хоча б один підпис мусить бути нашим.
     */
    fun partiallySigned(message: ByteArray, signers: List<Ed25519Keypair>): ByteArray {
        val n = signerCount(message)
        val (keyCount, len) = readShortVec(message, 3)!!
        require(keyCount >= n)
        var signed = 0
        val out = ByteArrayOutputStream()
        shortVec(out, n)
        for (i in 0 until n) {
            val at = 3 + len + 32 * i
            val key = SolanaKey.of(message.copyOfRange(at, at + 32))!!
            val kp = signers.firstOrNull { it.publicKey == key }
            out.write(kp?.sign(message)?.also { signed++ } ?: ByteArray(64))
        }
        require(signed > 0) { "no signer of ours in this message" }
        out.write(message)
        return out.toByteArray()
    }

    fun withSignature(message: ByteArray, signature: ByteArray): ByteArray {
        require(signature.size == 64 && signerCount(message) == 1)
        val out = ByteArrayOutputStream()
        shortVec(out, 1)
        out.write(signature)
        out.write(message)
        return out.toByteArray()
    }

    /** Перший підпис підписаної транзакції — це й є її ідентифікатор у мережі. */
    fun firstSignature(tx: ByteArray): ByteArray? {
        val (n, len) = readShortVec(tx, 0) ?: return null
        if (n < 1 || tx.size < len + 64) return null
        return tx.copyOfRange(len, len + 64)
    }

    fun messageOf(tx: ByteArray): ByteArray? {
        val (n, len) = readShortVec(tx, 0) ?: return null
        val start = len + 64 * n
        return if (tx.size > start) tx.copyOfRange(start, tx.size) else null
    }

    class Parsed(val signers: Int, val keys: List<SolanaKey>, val instructions: List<Triple<SolanaKey, List<SolanaKey>, ByteArray>>)

    /** Розбір повідомлення до останнього байта; хвіст чи нестача — null. */
    fun parse(message: ByteArray): Parsed? = runCatching {
        var at = 3
        fun vec(): Int = readShortVec(message, at)!!.let { (v, n) -> at += n; v }
        fun take(n: Int): ByteArray = message.copyOfRange(at, at + n).also { require(it.size == n); at += n }
        val keyCount = vec()
        val keys = List(keyCount) { SolanaKey.of(take(32))!! }
        take(32)
        val ixs = List(vec()) {
            val program = keys[take(1)[0].toInt() and 0xff]
            val accounts = List(vec()) { keys[take(1)[0].toInt() and 0xff] }
            Triple(program, accounts, take(vec()))
        }
        require(at == message.size)
        Parsed(message[0].toInt() and 0xff, keys, ixs)
    }.getOrNull()

    internal fun shortVec(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (true) {
            val b = v and 0x7f
            v = v ushr 7
            if (v == 0) { out.write(b); return }
            out.write(b or 0x80)
        }
    }

    internal fun readShortVec(bytes: ByteArray, at: Int): Pair<Int, Int>? {
        var value = 0
        for (i in 0 until 3) {
            val b = bytes.getOrNull(at + i)?.toInt()?.and(0xff) ?: return null
            value = value or ((b and 0x7f) shl (7 * i))
            if (b and 0x80 == 0) return value to (i + 1)
        }
        return null
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    internal fun u64(v: Long): ByteArray = ByteArray(8) { i -> (v ushr (8 * i)).toByte() }
    internal fun u32(v: Int): ByteArray = ByteArray(4) { i -> (v ushr (8 * i)).toByte() }
}

object Programs {
    val SYSTEM = SolanaKey.of("11111111111111111111111111111111")
    val TOKEN = SolanaKey.of("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
    val TOKEN_2022 = SolanaKey.of("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb")
    val ASSOCIATED_TOKEN = SolanaKey.of("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")
    val MEMO = SolanaKey.of("MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr")
    val RECENT_BLOCKHASHES = SolanaKey.of("SysvarRecentB1ockHashes11111111111111111111")

    fun systemTransfer(from: SolanaKey, to: SolanaKey, lamports: Long, reference: SolanaKey? = null) = Instruction(
        SYSTEM,
        listOfNotNull(
            AccountMeta(from, signer = true, writable = true),
            AccountMeta(to, signer = false, writable = true),
            reference?.let { AccountMeta(it, signer = false, writable = false) },
        ),
        SolanaTx.u32(2) + SolanaTx.u64(lamports),
    )

    val RENT = SolanaKey.of("SysvarRent111111111111111111111111111111111")
    const val NONCE_SPACE = 80

    fun seededAddress(base: SolanaKey, seed: String, owner: SolanaKey): SolanaKey =
        SolanaKey.of(java.security.MessageDigest.getInstance("SHA-256").digest(base.bytes + seed.toByteArray() + owner.bytes))!!

    fun createNonceWithSeed(funder: SolanaKey, base: SolanaKey, seed: String, lamports: Long, authority: SolanaKey): List<Instruction> {
        val nonce = seededAddress(base, seed, SYSTEM)
        val create = Instruction(
            SYSTEM,
            listOf(
                AccountMeta(funder, signer = true, writable = true),
                AccountMeta(nonce, signer = false, writable = true),
                AccountMeta(base, signer = true, writable = false),
            ),
            SolanaTx.u32(3) + base.bytes + SolanaTx.u64(seed.length.toLong()) + seed.toByteArray() +
                SolanaTx.u64(lamports) + SolanaTx.u64(NONCE_SPACE.toLong()) + SYSTEM.bytes,
        )
        val init = Instruction(
            SYSTEM,
            listOf(
                AccountMeta(nonce, signer = false, writable = true),
                AccountMeta(RECENT_BLOCKHASHES, signer = false, writable = false),
                AccountMeta(RENT, signer = false, writable = false),
            ),
            SolanaTx.u32(6) + authority.bytes,
        )
        return listOf(create, init)
    }

    fun advanceNonce(nonceAccount: SolanaKey, authority: SolanaKey) = Instruction(
        SYSTEM,
        listOf(
            AccountMeta(nonceAccount, signer = false, writable = true),
            AccountMeta(RECENT_BLOCKHASHES, signer = false, writable = false),
            AccountMeta(authority, signer = true, writable = false),
        ),
        SolanaTx.u32(4),
    )

    fun associatedTokenAddress(owner: SolanaKey, mint: SolanaKey, tokenProgram: SolanaKey = TOKEN): SolanaKey =
        SolanaKey.findProgramAddress(listOf(owner.bytes, tokenProgram.bytes, mint.bytes), ASSOCIATED_TOKEN).first

    fun createAssociatedIdempotent(payer: SolanaKey, owner: SolanaKey, mint: SolanaKey, tokenProgram: SolanaKey = TOKEN) =
        Instruction(
            ASSOCIATED_TOKEN,
            listOf(
                AccountMeta(payer, signer = true, writable = true),
                AccountMeta(associatedTokenAddress(owner, mint, tokenProgram), signer = false, writable = true),
                AccountMeta(owner, signer = false, writable = false),
                AccountMeta(mint, signer = false, writable = false),
                AccountMeta(SYSTEM, signer = false, writable = false),
                AccountMeta(tokenProgram, signer = false, writable = false),
            ),
            byteArrayOf(1),
        )

    fun tokenTransferChecked(
        owner: SolanaKey,
        to: SolanaKey,
        mint: SolanaKey,
        amount: Long,
        decimals: Int,
        reference: SolanaKey? = null,
        tokenProgram: SolanaKey = TOKEN,
    ) = Instruction(
        tokenProgram,
        listOfNotNull(
            AccountMeta(associatedTokenAddress(owner, mint, tokenProgram), signer = false, writable = true),
            AccountMeta(mint, signer = false, writable = false),
            AccountMeta(associatedTokenAddress(to, mint, tokenProgram), signer = false, writable = true),
            AccountMeta(owner, signer = true, writable = false),
            reference?.let { AccountMeta(it, signer = false, writable = false) },
        ),
        byteArrayOf(12) + SolanaTx.u64(amount) + byteArrayOf(decimals.toByte()),
    )

    fun memo(text: String) = Instruction(MEMO, emptyList(), text.toByteArray(Charsets.UTF_8))
}
