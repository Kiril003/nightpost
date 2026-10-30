package local.phantom.companion.core.truth

/**
 * Контракт сервера краудправди. Сервера ще немає — лише ця форма.
 *
 * ДЕ. Поруч зі сховком і довідником: `platform-site/server` (FastAPI, Fly
 * `phantom-license`, fra), тими самими дверима, що `/api/attachments/put`, —
 * заява підписана КЛЮЧЕМ ПРИСТРОЮ (`pub_key`, `nonce`, `issued_ms`,
 * `signature`, перевірка `directory.verify` + `att.is_fresh_nonce`). Особа —
 * ключ, без акаунта й пошти. База — окремий файл на окремому томі, як у сховку.
 *
 * ЧЕСНО ПРО СЛІПОТУ. Сховок сліпий, цей сервер — НІ: щоб зважити голос, він
 * мусить бачити, хто, про що, де (комірка ~460 м) і коли сказав. Тому:
 *   * звʼязка «ключ ↔ свідчення» живе не довше 2 півжиттів виду (для укриття
 *     14 діб, для світла 2 год), далі лишаються лише агрегати й лічильники
 *     репутації; захоплений сервер віддає сліди присутності лише за це вікно;
 *   * мережу запиту сервер зводить до `network_id` = HMAC(сіль доби, /24 чи
 *     /48) і забуває адресу; клієнт `network_id` не шле;
 *   * час свідчення ставить СЕРВЕР при прийомі; `at_ms` клієнта ігнорується —
 *     інакше заднім числом можна було б вписатись у чуже вікно підтвердження.
 *
 * POST /truth/observe                              — одне свідчення
 *   { pub_key, nonce, issued_ms, signature,
 *     cell: "88...ff",  object?: "osm:node/123",
 *     kind: "shelter.open",                         — лише [FactKind.wire]
 *     claim: "assert"|"confirm"|"deny"|"update",
 *     reading?: "open",                             — для assert/update
 *     presence?: { from_ms, to_ms },                — комірка присутності = `cell`, інших не шлемо
 *     note?: "…" }                                  — ≤ 280, лише в чергу модерації
 *   202 { verdict: VerdictJson }
 *   422 { refused: [Refusal].name, message_uk, message_en }
 *   429 { retry_after_s }                           — стеля на ключ: 30 свідчень / год
 *
 * GET /truth/cell/{cell}                           — вироки всіх фактів комірки
 *   200 { cell, verdicts: [VerdictJson] }
 *   VerdictJson = { cell, object?, kind, state: "LIKELY"|"UNKNOWN"|"STALE",
 *                   reading?, probability, supporters, last_support_ms?,
 *                   newest_ms?, dissent, contested, viewers_24h }
 *   Жодних ключів голосувальників, жодних сирих свідчень. Свідчення під
 *   [Embargo] не входять у вирок до свого часу. Запит комірки каже серверу,
 *   куди людина дивиться, — тому клієнт тягне батьківську комірку r6 (~3 км)
 *   цілком і фільтрує в себе: GET /truth/area/{cell_r6}, та сама відповідь.
 *
 * POST /truth/viewed                               — «я глянув на факт» для лічильника користі
 *   { cells: ["88…"], kinds: ["shelter.open"], pub_key?, nonce?, issued_ms?, signature? }
 *   `viewer_token` = HMAC(сіль доби, pub_key, якщо підписано, інакше IP) — лічить
 *   різних глядачів доби, але не склеює їхні дні.
 *   204
 *
 * POST /truth/me                                   — власна користь і стан
 *   { pub_key, nonce, issued_ms, signature }
 *   200 { contributions_30d, helped: [ImpactCounter.band], standing: "new"|"trusted"|"doubted" }
 *   Число репутації не віддається навмисно: видиме число — це ціль для гри.
 *
 * Питання «це ще так?» сервер не ставить: [PromptPlanner] рахує на пристрої з
 * відповіді /truth/area, власної історії питань і власної комірки — сервер не
 * дізнається, коли й де людину питали.
 *
 * Модерація — `POST /truth/moderation/…` для людини-модератора, поза цим етапом.
 */
object TruthWire {
    const val OBSERVE = "/truth/observe"
    const val CELL = "/truth/cell/"
    const val AREA = "/truth/area/"
    const val VIEWED = "/truth/viewed"
    const val ME = "/truth/me"
    const val MAX_OBSERVATIONS_PER_HOUR = 30

    /** Речення під підписом ключа пристрою — слово в слово `observe_message` з `truth_store.py`. */
    fun observeMessage(
        pubKey: String,
        nonce: String,
        issuedMs: Long,
        cell: String,
        objectId: String?,
        kind: String,
        claim: String,
        reading: String?,
        presence: Pair<Long, Long>?,
        note: String?,
    ): String {
        val noteHash = if (note.isNullOrEmpty()) "" else sha256Hex(note)
        return listOf(
            "phantom-truth-observe", "v1", pubKey, nonce, issuedMs.toString(), cell, objectId.orEmpty(), kind, claim,
            reading.orEmpty(), presence?.first?.toString().orEmpty(), presence?.second?.toString().orEmpty(), noteHash,
        ).joinToString("|")
    }

    private fun sha256Hex(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
