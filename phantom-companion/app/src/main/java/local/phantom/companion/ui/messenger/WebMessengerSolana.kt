package local.phantom.companion.ui.messenger

import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.solana.Amounts
import local.phantom.companion.core.solana.BillView
import local.phantom.companion.core.solana.CashVoucher
import local.phantom.companion.core.solana.Cluster
import local.phantom.companion.core.solana.Coin
import local.phantom.companion.core.solana.EscrowConfig
import local.phantom.companion.core.solana.PaymentFrame
import local.phantom.companion.core.solana.SealFrame
import local.phantom.companion.core.solana.SealVerdict
import local.phantom.companion.core.solana.SolanaKey
import local.phantom.companion.core.solana.SolanaLayer
import local.phantom.companion.core.solana.SolanaNotice
import local.phantom.companion.core.solana.WalletActivity
import local.phantom.companion.core.solana.WalletMe
import local.phantom.companion.core.solana.WalletMeReply
import local.phantom.companion.data.AppContainer
import local.phantom.companion.data.sharedPocketOf
import local.phantom.companion.data.sharedWalletOf
import org.json.JSONObject

/**
 * `/messenger/solana/…` — див. docs/solana/PAYMENT_CARD_SPEC.md:
 *  GET  peer/{id} → чи можна цій людині переказати (її адреса вже приїхала в розмові);
 *  GET  me        → {address, cluster, kind, sponsored, balances, balances_at, stale, tokens, reclaim,
 *                   activity[{signature, coin, units, at, counterparty, peer_id, name, failed}]};
 *                   мережа впала — останні баланси з часом і stale, не нуль; не читали — null;
 *  POST pay       → {peer_id | to, token?, amount?, chat_id?, ref?} рідний екран переказу; монета й
 *                   сума з вебу лише заповнюють діалог — перевірка й підтвердження відбитком лишаються
 *                   там; з `chat_id` картка (`PH-PAY:` v2 з `ref`) лягає в групу, а не в 1:1;
 *  GET  escrow    → {on, cluster, program} — ескроу рахунків у мережі гаманця (сервер /api/solana/config);
 *  GET  escrow/bill/{address} → {status, paid, total, deadline} — рахунок-ескроу з ланцюга;
 *  POST seal      → {sha256, label, peer_id?} засвідчити відбиток файла, який веб порахував сам.
 */
internal class WebMessengerSolana(private val container: AppContainer) {

    suspend fun handle(method: String, seg: List<String>, body: String?): String {
        val state = SolanaLayer.state.value
        val ctx = container.applicationContext
        return when {
            seg.size == 4 && seg[2] == "peer" && method == "GET" -> ok(
                JSONObject()
                    .put("address", state.address?.base58 ?: JSONObject.NULL)
                    .put("can_pay", state.address != null && container.sharedWalletOf(seg[3]) != null)
                    .put("cluster", state.cluster.wire)
                    .put("seeker", state.seekerMint != null),
            )
            seg.size == 3 && seg[2] == "me" && method == "GET" -> {
                val byCoin = Coin.on(state.cluster).associateWith { runCatching { SolanaLayer.activity(it) }.getOrNull() }
                val people = runCatching { SolanaLayer.config?.contacts?.invoke() }.getOrNull().orEmpty()
                ok(WalletMeReply.of(SolanaLayer.state.value, WalletMe.lines(byCoin, people)))
            }
            seg.size == 5 && seg[2] == "escrow" && seg[3] == "bill" && method == "GET" ->
                ok(billReply(SolanaLayer.bill(seg[4])))
            seg.size == 3 && seg[2] == "escrow" && method == "GET" -> {
                SolanaLayer.escrowSoon()
                ok(escrowReply(state.escrow, state.cluster))
            }
            seg.size == 3 && seg[2] == "pay" && method == "POST" -> {
                val o = json(body) ?: return fail(400, "bad_body")
                val ask = payAsk(
                    o.optString(PAY_PEER), o.optString(PAY_TOKEN), o.optString(PAY_AMOUNT),
                    to = o.optString(PAY_TO), chat = o.optString(PAY_CHAT), ref = o.optString(PAY_REF),
                ) ?: return fail(400, "bad_body")
                // Групу звіряємо ДО гаманця: відбиток під переказ, картка якого
                // ляже не туди, — гірше, ніж чесна відмова тут.
                if (ask.chat != null) {
                    val log = container.mesh.outbox.value
                    val group = WebMessengerGroups.find(log, ask.chat) ?: return fail(404, "no_chat")
                    chatRefusal(ask, group, PeerGroup.standing(log, group, container.mesh.selfIdFlow.value))?.let { return fail(409, it) }
                }
                val intent = SolanaLayer.walletIntent(ctx, ask.peer)
                walletExtras(ask).forEach { (k, v) -> intent.putExtra(k, v) }
                ctx.startActivity(intent)
                ok(JSONObject().put("opened", true))
            }
            seg.size == 3 && seg[2] == "seal" && method == "POST" -> {
                val o = json(body) ?: return fail(400, "bad_body")
                val hex = o.optString("sha256").lowercase().takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
                    ?: return fail(400, "bad_hash")
                ctx.startActivity(
                    SolanaLayer.walletIntent(ctx, o.optString("peer_id").takeIf { it.isNotBlank() })
                        .putExtra(WalletActivity.EXTRA_SEAL_HASH, hex)
                        .putExtra(WalletActivity.EXTRA_SEAL_LABEL, o.optString("label").take(80)),
                )
                ok(JSONObject().put("opened", true))
            }
            else -> fail(404, "not_found")
        }
    }

    /**
     * Стан дописується щоразу, коли міст віддає картку: мережа могла вже відповісти.
     * [fromId] і [messageId] — чия це картка: «підтверджено» лише тоді, коли платив
     * той, хто її надіслав, і цей підпис не показано раніше іншим повідомленням;
     * знак «Seeker перевірено» на купюрі теж чесний лише від своєї особи.
     */
    fun decorate(o: JSONObject, body: String, fromId: String, messageId: String) {
        val wire = body.trim()
        val p = o.optJSONObject(WebMessengerKinds.PAYMENT)
        if (p != null) {
            val frame = PaymentFrame.decode(wire)
            val voucher = if (frame == null) CashVoucher.decode(wire) else null
            val payment = frame ?: voucher?.payment ?: return
            val ownCard = fromId == container.mesh.selfIdFlow.value
            val payer = if (voucher != null) container.sharedPocketOf(fromId) else container.sharedWalletOf(fromId)
            val verdict = SolanaLayer.cardStatus(payment, voucher?.tx, "$fromId:$messageId", ownCard, payer, voucher?.signer)
            p.put("status", verdict.status.wire)
            verdict.reason?.let { p.put("mismatch_reason", it) }
            val mine = SolanaLayer.state.value.address
            p.put("to_mine", if (mine == null) JSONObject.NULL else mine == payment.to)
            if (voucher != null) {
                // Офлайн: підпис сервера ліцензій, строк і особа відправника. Знак може казати
                // SGT, стейк SKR або обидва; поля — лише з перевіреного знака, інакше false/null.
                val badge = SolanaLayer.seekerBadgeOf(voucher, fromId)
                p.put("seeker_verified", badge?.sgt == true)
                p.put("seeker_skr_tier", badge?.skrTier?.takeIf { it.stakes }?.wire ?: JSONObject.NULL)
                p.put("seeker_label", SolanaNotice.badgeLine(container.applicationContext, badge) ?: JSONObject.NULL)
            }
        }
        val s = o.optJSONObject(WebMessengerKinds.SEAL) ?: return
        val seal = SealFrame.decode(wire) ?: return
        when (val v = SolanaLayer.sealOf(seal)) {
            is SealVerdict.Anchored -> s.put("status", "confirmed").put("block_time", v.atSeconds ?: JSONObject.NULL)
            SealVerdict.Pending -> s.put("status", "pending")
            SealVerdict.Mismatch -> s.put("status", "mismatch")
            SealVerdict.Failed -> s.put("status", "failed")
        }
    }

    private fun json(body: String?): JSONObject? = body?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun ok(data: Any): String = JSONObject().put("ok", true).put("status", 200).put("data", data).toString()

    private fun fail(status: Int, code: String): String =
        JSONObject().put("ok", false).put("status", status).put("code", code).put("detail", code).toString()

    /**
     * Що веб попросив переказати. Без монети чи з кривою сумою — просто відкрити переказ людині.
     * [peer] — людина; [to] — гаманець закладу (тоді [peer] null). [chat] — група, куди ляже
     * картка (null — 1:1 з людиною); [ref] — id рахунку для `PaymentFrame` v2.
     */
    data class PayAsk(
        val peer: String?,
        val coin: Coin?,
        val amount: String?,
        val to: SolanaKey? = null,
        val chat: String? = null,
        val ref: String? = null,
    )

    companion object {
        fun claims(seg: List<String>): Boolean = seg.size >= 3 && seg[1] == "solana"

        /** Поля тіла `POST /messenger/solana/pay` — дзеркало `PayAsk` у `solanaActions.ts`. */
        const val PAY_PEER = "peer_id"
        const val PAY_TOKEN = "token"
        const val PAY_AMOUNT = "amount"

        /** Збірка 18, частка рахунку (`docs/scene/MEETUP_CONTRACT.md` §4). */
        const val PAY_CHAT = "chat_id"
        const val PAY_REF = "ref"
        const val PAY_TO = "to"

        /**
         * Монета чужа чи сума крива — лишається просто «переказ людині», діалог порожній.
         * А от кривий `ref` чи адреса — відмова: переказ «без привʼязки» замість частки
         * рахунку виглядав би оплатою, якою не є. Закладу — лише з групи: інакше
         * картку нікому показати.
         */
        fun payAsk(
            peer: String?,
            token: String?,
            amount: String?,
            to: String? = null,
            chat: String? = null,
            ref: String? = null,
        ): PayAsk? {
            val who = peer?.takeIf { it.isNotBlank() }
            val address = to?.takeIf { it.isNotBlank() }?.let { SolanaKey.parse(it) ?: return null }
            if ((who == null) == (address == null)) return null
            val bill = ref?.takeIf { it.isNotBlank() }?.also { if (!PaymentFrame.isRef(it)) return null }
            val room = chat?.takeIf { it.isNotBlank() && it != who }
            if (address != null && room == null) return null
            val coin = Coin.of(token?.lowercase())
            val sum = amount?.takeIf { coin != null && Amounts.toUnits(it, coin) != null }
            return PayAsk(who, coin.takeIf { sum != null }, sum, address, room, bill)
        }

        /** Що з [PayAsk] їде в екран гаманця (людина — окремо, через `walletIntent`). */
        fun walletExtras(ask: PayAsk): List<Pair<String, String>> = buildList {
            // Не EXTRA_TO: той — «вписано вручну», а гаманець закладу дав рахунок зустрічі.
            ask.to?.let { add(WalletActivity.EXTRA_PAY_VENUE to it.base58) }
            if (ask.coin != null && ask.amount != null) {
                add(WalletActivity.EXTRA_PAY_COIN to ask.coin.wire)
                add(WalletActivity.EXTRA_PAY_AMOUNT to ask.amount)
            }
            ask.chat?.let { add(WalletActivity.EXTRA_PAY_CHAT to it) }
            ask.ref?.let { add(WalletActivity.EXTRA_PAY_REF to it) }
        }

        /** `GET escrow`: програма рахунків лише тієї мережі, де гаманець; сервер мовчить — ескроу немає. */
        /** `GET escrow/bill/{address}`: статус лише з ланцюга; не прочитали — `status: null`, без здогадок. */
        fun billReply(view: BillView?): JSONObject = JSONObject()
            .put("status", view?.status?.name?.lowercase() ?: JSONObject.NULL)
            .put("paid", view?.paid ?: 0)
            .put("total", view?.total ?: 0)
            .put("deadline", view?.deadline ?: JSONObject.NULL)

        fun escrowReply(config: EscrowConfig?, wallet: Cluster): JSONObject {
            val program = config?.programFor(wallet)
            return JSONObject()
                .put("on", program != null)
                .put("cluster", wallet.wire)
                .put("program", program?.base58 ?: JSONObject.NULL)
        }

        /** Група, куди ляже картка, мусить бути моєю, а отримувач-людина — в її складі. */
        fun chatRefusal(ask: PayAsk, group: PeerGroup.Group, standing: PeerGroup.Standing): String? = when {
            standing != PeerGroup.Standing.IN -> "not_in_group"
            ask.peer != null && ask.peer !in group.memberIds -> "payee_not_in_group"
            else -> null
        }
    }
}
