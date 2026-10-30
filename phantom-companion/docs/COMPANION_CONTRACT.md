# Companion <-> Phantom-OS Contract (locked 2026-05-09)

Ground-truth wire contract for the Android Companion. Every non-obvious
claim cites `file:line` from `phantom-os/src/backend/`. This document is
read-only research — no code was changed during its production.

Source spec: `phantom-os/docs/MOBILE_COMPANION.md` (Ukrainian; translated).

---

## 1. Discovery

### mDNS / DNS-SD

Backend advertises a single service on the LAN:

| field | value | source |
|---|---|---|
| service type | `_phantom._tcp.local.` | `discovery/mdns_publisher.py:83` |
| instance name | `PHANTOM._phantom._tcp.local.` (configurable via `instance_name` param) | `discovery/mdns_publisher.py:84`, `main.py:445` |
| port | `config.port` (default `8000`) | `main.py:445` |
| server FQDN | `<gethostname>.local.` | `discovery/mdns_publisher.py:69-73` |
| TXT records | `version=1`, `app=phantom-os` | `discovery/mdns_publisher.py:91-94` |
| IP version | IPv4 only | `discovery/mdns_publisher.py:76` |

Lifecycle: started at FastAPI lifespan start (`main.py:444-447`),
stopped before async-task cancellation on shutdown
(`main.py:628-632`). Failure to register is non-fatal — the daemon
keeps booting (`discovery/mdns_publisher.py:99-105`).

### Relay URL fallback

There is no server-side relay endpoint. The fallback path is encoded in
the QR's `endpoints[]` list (v2 QR format only).

- v1 QR: phone derives a single base URL from `host`/`ip`+`port`
  (`PairingQr.kt:101-111`).
- v2 QR: phone reads `endpoints: [{type: "lan"|"relay"|"tailscale", url, device_id?}, ...]`
  and tries them in order, LAN first (`PairingQr.kt:43-54`, `:94-99`).
- The companion `PairFlowClient.claim()` iterates `qr.candidateUrls()`
  (`PairFlowClient.kt:197-233`).
- `PhantomLink` consumes `paired.lanUrl` then `paired.relayUrl`
  (`PhantomLink.kt:79`).

> The backend `pair_crypto.build_qr_payload` (`security/pair_crypto.py:299-318`)
> emits a v1 QR — it has no `endpoints` field. The v2 endpoint list is a
> client-side capability the server has not yet started populating.
> See §10.

### Backend pair-host config

`config.pair_host` defaults to `"phantom.local"` (`config.py:51`).
`config.pair_port` and `config.pair_cert_sha256` are read with
`getattr(config, ..., default)` in `routes_pair.py:309-317` — defaults
are `8000` and `"dev-no-pin"`. The IP in the QR is determined by a
UDP-connect probe in `_local_ip_guess()` (`routes_pair.py:276-292`).

---

## 2. REST Endpoints

All endpoints below are mounted under prefix `/api/v1`
(`main.py:781-865`).

### 2.1 `POST /api/v1/pair/init`

- Auth: ROOT JWT via `require_root` dep (`routes_pair.py:300`).
- Request: empty body.
- Response (`PairInitResponse`, `routes_pair.py:63-71`):
  ```json
  {
    "pair_id": "<32-hex>",
    "expires_in_seconds": 60,
    "qr": { "v": 1, "host": "...", "ip": "...", "port": 8000,
            "pair_id": "...", "server_pub": "<b64 X25519>",
            "server_cert_sha256": "dev-no-pin"|"<hex>",
            "exp": <unix>, "nonce": "<b64 24B>" },
    "qr_svg_data_url": "data:image/svg+xml;utf8,<urlencoded>"
  }
  ```
- Errors: 401/403 from `require_root`.

### 2.2 `POST /api/v1/pair/claim`

- Auth: NONE — proof-gated (`routes_pair.py:332-340`).
- Request (`PairClaimRequest`, `routes_pair.py:74-90`):
  | field | type | required | constraints |
  |---|---|---|---|
  | `pair_id` | str | yes | 8..64 |
  | `client_pub` | str (b64 X25519, 32B) | yes | 40..64 |
  | `device_pub_ed25519` | str (b64 Ed25519 pub, 32B) | yes | 40..64 |
  | `nonce_echo` | str (b64) | yes | 20..64; must equal session nonce |
  | `client_proof` | str (b64 HMAC, 32B) | yes | 40..64 |
  | `device` | object `{name, model, platform, os_version}` | optional | each str <=128/16/32 |
  | `profile_id` | str (uuid) | optional | <=36; must belong to owner |
  | `profile_display_name` | str | optional | <=64; only used when no profile exists |
- Response (`PairClaimResponse`, `routes_pair.py:93-103`):
  ```json
  {
    "device_jwt": "...",
    "device_id": "<uuid>",
    "expires_at": "<iso8601>",
    "server_proof": "<b64 HMAC>",
    "user":    { "id","username","role","avatar_url" },
    "profile": { "id","user_id","display_name","role","avatar_uri","is_primary" }
  }
  ```
- Error codes (HTTPException `detail.code`):
  - 410 `session_expired` (`routes_pair.py:347`)
  - 400 `bad_nonce` (`:351`)
  - 400 `bad_payload` / `bad_proof` (`pair_crypto.py:198,267`)
  - 409 `owner_missing` (`:382`)
  - 400 `profile_invalid` (`:192`)
- WS side effect: `pair/claimed` broadcast scoped to owner user_id
  (`routes_pair.py:430-444`).

### 2.3 `POST /api/v1/pair/refresh`

- Auth: NONE — proof-gated by Ed25519 signature (`routes_pair.py:457-460`).
- Request (`PairRefreshRequest`, `routes_pair.py:111-123`):
  | field | type | constraints |
  |---|---|---|
  | `old_token` | str | most-recent device JWT (may be expired) |
  | `nonce_b64` | str | 16..64 |
  | `signature_b64` | str | 40..128; Ed25519 over `f"{device_id}:{nonce_b64}"` (`:509`) |
- Response (`PairRefreshResponse`, `routes_pair.py:126-129`): `{device_jwt, expires_at}`.
- Errors: 401 `old_token_rejected` / `device_unknown` / `device_revoked` / `device_owner_mismatch` / `bad_signature` (`:482-525`).
- The new token preserves `orig_iat` so the absolute-lifetime cap fires regardless of refresh chain (`:529-533`, `device_token.py:55,87,99`).

### 2.4 `GET /api/v1/pair/status?pair_id=...`

- Auth: ROOT (`routes_pair.py:545-549`).
- Response: `{pair_id, status: "pending"|"closed", device_id?}` (`PairStatusResponse`, `:105-108`). After consume the session is gone — both expired and claimed return `closed` (`:556-560`).

### 2.5 `GET /api/v1/pair/devices?include_revoked=false`

- Auth: ROOT (`routes_pair.py:564-568`).
- Response: `list[PairedDeviceRow]` (`:131-141`) — `id, device_name, device_model, platform, platform_version, paired_at, last_seen_at, revoked_at, capabilities[]`.

### 2.6 `DELETE /api/v1/pair/devices/{device_id}?reason=...`

- Auth: ROOT (`routes_pair.py:578-583`).
- Response: `{ok: true, already_revoked?: true}`.
- Errors: 404 `device_not_found`.
- WS side effect: `pair/revoked` (`:603-608`).

### 2.7 `POST /api/v1/sensors/mobile_batch`

- Auth: device JWT via `get_current_device` (`routes_mobile_sensors.py:36,100`).
- Request (`MobileSensorBatchIn`, `:72-83`):
  | field | type | notes |
  |---|---|---|
  | `device_ts_ms` | int | default 0 |
  | `gps` | `{lat, lon, accuracy_m?}` | nullable |
  | `motion_class` | str | "walking"\|"still"\|"driving"\|... |
  | `mic_rms` | float | 0..1 |
  | `body` | `{bpm?, hrv?}` | nullable |
  | `ble[]` | `{mac?, name?, rssi}` | list |
  | `wifi[]` | `{mac, ssid, rssi, encryption, channel}` | list |
- Response (`MobileSensorBatchOut`, `:85-90`): `{ok, batch_id, received_at, wardriving_inserted, wardriving_updated}`.
- Side effects: persists `MobileSensorBatch`, bumps `device.last_seen_at`, opportunistically forwards WiFi to `wardriving.upsert_network` when GPS is fixed (`:131-159`), broadcasts `sensor/mobile_batch` scoped to user (`:182-185`).

### 2.8 `GET /api/v1/profile-sync/snapshot?reveal=0|1`

- Auth: dual (`get_user_or_device_user`, `routes_profile_sync.py:285`).
- Response (`SnapshotOut`, `:128-139`): `{user_id, username, role, avatar_url, preferences, behavioral_model, profiles[], facts[], vault_cards[], recent_sessions[], snapshot_ts}`.
- `?reveal=1` un-masks `UserFact.value` (decrypted via `decrypt_pii`); default masked (`:217-231,_mask_value`).

### 2.9 `POST /api/v1/profile-sync/import`

- Auth: dual (`:353`).
- Request (`ImportProfileIn`, `:142-155`): `{client_event_id, profile, facts[], preferences?}`.
- Response (`ImportProfileOut`, `:158-162`): `{profile_id, facts_created, facts_updated, is_new}`.
- WS side effect: `profile_sync/profile_imported` scoped to user (`:434-444`).

### 2.10 `POST /api/v1/profile-sync/event`

- Auth: dual (`:457`).
- Request (`EventIn`, `:165-179`): `{client_event_id, type, data}` where `type` is one of:
  `preference_changed | profile_renamed | fact_upserted | fact_deleted | behavior_updated`
  (regex enforced, `:177`).
- Response (`EventOut`, `:182-185`): `{accepted, type, server_ts}`.
- Per-type `data` shape (`routes_profile_sync.py:464-556`):
  `preference_changed`: `{key, value?}` (missing `value` deletes); `behavior_updated`: `{delta, scope:"user"|"profile", profile_id?}`; `profile_renamed`: `{profile_id, display_name}`; `fact_upserted`: `{id, category, label?, value}` (value encrypted via `encrypt_pii`); `fact_deleted`: `{id}`.
- Errors: 422 `missing_key`/`delta_not_object`/`missing_fields`/`missing_id`; 404 `profile_not_found`.
- WS side effect: same `type` rebroadcast on `profile_sync` channel (`:564`).

### 2.11 `POST /api/v1/companion-control/{verb}`

- Auth: ROOT (`routes_companion_control.py:126,141,...`).
- Verbs (closed enum, `ControlVerb`, `:46-53`): `open_route`, `open_card`, `navigate`, `focus_screen`, `lock_vault`, `ping`.
- Request (`ControlIn`, `:55-63`): `{target_device_id?, payload}` (extra fields forbidden).
- Response (`ControlOut`, `:66-69`): `{accepted, delivered_to, verb}`.
- Errors: 404 `target_device_unknown`, 409 `target_device_revoked`.
- WS side effect: `companion_control/<verb>` scoped to user (`:86`).

### 2.12 Handoff endpoints (`/api/v1/handoff*`)

Auth: dual (`get_user_or_device_user`, `routes_handoff.py:199,237,...`).
Calling-device id is recovered from the Bearer token if it is a device JWT (`:146-161`).

- `POST /handoff` — body (`HandoffCreate`, `:114-122`): `{kind, title?, payload?, target_device_id?, ttl_seconds=900 (15..86400)}`. `kind` enum (`:105-112`): `map_route | vault_card | chat_thread | file_drop | voice_continuation | custom`. Returns `HandoffOut` (`:124-136`). Errors: 404/409 target-device. WS: `handoff/created`.
- `GET /handoff` — list pending; lazy-expires (`:257-268`). Returns `{handoffs[]}`.
- `POST /handoff/{id}/{accept|reject|cancel}` — `HandoffOut`; errors 404 `handoff_not_found`, 409 `handoff_not_pending`. WS: `handoff/accepted|rejected|cancelled`.

### 2.13 Voice TTS (referenced from §4)

`POST /api/v1/voice/tts` — referenced in spec (MOBILE_COMPANION.md
§3 Tier-1 "Push-to-talk голос") and consumed by Phase-1 plan; lives
on `routes_voice` (`main.py:791`). Out of scope for this contract
(not in the file list).

---

## 3. WebSocket `/ws`

Path: `/ws` (`main.py:930`). NOT under the `/api/v1` prefix. The
companion's `PhantomLink` connects at `<base>/api/v1/ws?token=<jwt>`
(`PhantomLink.kt:139`) — this is a path mismatch (see §10).

### Connection auth

Token via query string `?token=<jwt>` (`main.py:931`). Resolution order:

1. `verify_token` (user JWT) — `main.py:943-945`.
2. On failure, `verify_device_token` and lookup `PairedDevice` by
   `dev_payload.device_id` — `main.py:949-985`.
3. If row exists, not revoked, and user matches → `user_id`+`device_id`
   set, `last_seen_at` bumped (`:961-975`).
4. If row is revoked → `ws.close(code=4401, reason="device_revoked")`
   (`:978-982`).
5. If neither token verifies → connection still accepts but with
   `user_id=None` (anonymous; legacy desktop behaviour, `:986`).

### Default channel filter

Phones (clients with non-None `device_id`) default to a narrower set:
`{sensor, state, pair, familiar, alert, _meta, context, chat}`
(`main.py:919-928, :990-991`). Desktop clients keep `channels = None`
(receive everything).

### Subscribe / unsubscribe protocol

Client → server control frames (out-of-band of channel routing,
`websocket_hub.py:200-203, :226-262`):

```json
{"control": "subscribe",     "channels": ["sensor","map"]}
{"control": "unsubscribe",   "channels": ["map"]}
{"control": "subscribe_all"}
```

`subscribe` is additive (UNION with current set). `unsubscribe` is
subtractive. `subscribe_all` resets to `None` (every channel).

Server echoes ack on the `_meta` channel:
```json
{"channel":"_meta","type":"subscribed","data":{"channels":[...]|null},"ts":<ms>}
```
(`websocket_hub.py:265-282`)

### Server → client envelope

Every frame is JSON with shape (`websocket_hub.py:73`):
```json
{"channel": "<name>", "type": "<verb>", "data": {...}, "ts": <unix-ms>}
```
Non-finite floats are coerced to `null` before send (`_sanitize`, `:22-30`).

### Channel inventory

| channel | direction | payload schema | when emitted |
|---|---|---|---|
| `sensor` / `snapshot` \| `mobile_batch` | server→client | `snapshot`: `{snapshot:<ContextSnapshot>}` (every 500 ms or per serial batch); `mobile_batch`: `{source:"mobile",device_id,device_name,received_at,device_ts_ms,gps?,motion_class,body?}` | `main.py:{104,208}`; `routes_mobile_sensors.py:164-185` user-scoped |
| `state` / `transition` | server→client | `{from, to, trigger, timestamp, auto}` | `StateMachine.evaluate` returns transition; emitted via event_bus (`dispatch/state_broadcaster.py:113-115`); also `agent/runtime.py:500,1147` |
| `context` / `preferences_changed` | server→client | `{changes:{...}, preferences:{...}}` | after `PATCH /auth/me` mutates `preferences_json` (`routes_auth.py:497-507`), scoped to user |
| `familiar` / `manifestation` | server→client | varies (manifestation pose/intent payload) | `POST /api/v1/familiar/manifest` (`routes_familiar.py:140`) |
| `pair` / `claimed` \| `revoked` | server→client | `claimed`: `{device_id,device_name,device_model,platform,user_id,profile_id,profile_display_name,paired_at}`; `revoked`: `{device_id,reason}` | scoped to owner (`routes_pair.py:{430-444,603-608}`) |
| `agent.stream` / `proactive.cycle` \| `proactive.pending_action` \| `proactive.action_fired` \| `emotion.updated` \| `task.report_acknowledged` \| inner-monologue events | server→client | per-event payload | `agent/proactive.py:{258,372,407}`, `agent/emotion.py:153`, `agent/runtime.py:{381,1185}`, `agent/monologue_emitter.py:89` |
| `chat` / `message` \| `stream` \| `message.proactive` | server→client | `message`: `{message,session_id}`; `stream`: `{message_id,delta,done,message?}`; `message.proactive`: insert payload | `routes_chat.py:{790-797,982-1002}`, `agent/proactive.py:652` |
| `map` / `<map mutation op>` | server→client | per-op MapMutation payload | every map action (`agent/actions/map/_common.py:121` via `broadcast_map_mutation`); ops emitted include `wardriving_update` (`main.py:215`), `plan_route`, `add_layer`, `add_marker`, `flyto`, `set_view`, `enable_layer`/`disable_layer`, `isochrone`, `optimize_visit`, `snap_track`, `explain_view`, `open_map`, `snapshot` |
| `profile_sync` / `preference_changed` \| `profile_renamed` \| `fact_upserted` \| `fact_deleted` \| `behavior_updated` | server→client | `{client_event_id, data, ts}` | `POST /profile-sync/event` type-passthrough (`routes_profile_sync.py:564`) |
| `profile_sync` / `profile_imported` | server→client | `{client_event_id, profile, facts_created, facts_updated, is_new}` | `POST /profile-sync/import` (`routes_profile_sync.py:434-444`) |
| `companion_control` / `<verb>` | server→client | `{verb, payload, target_device_id?}` | `POST /companion-control/{verb}` (`routes_companion_control.py:86`) |
| `handoff` / `created` \| `accepted` \| `rejected` \| `cancelled` | server→client | `HandoffOut` | each handoff lifecycle endpoint (`routes_handoff.py:{231,291,313,333,368}`) |
| `drive` / `<verb>` \| `settings` / `config.reloaded` \| `oled` / `frame` \| `background_events` / `<type>` | server→client | per-channel payload | `routes_drive.py:147`, `routes_settings.py:760-768`, `vision/oled_animator.py:147`, `agent/runtime.py:366` |
| `standing_orders` / `tick` \| `fired` \| `skipped` | server→client | per-topic dict: tick `{cycle_at_iso,evaluated,fired,skipped}`, fired `{order_id,action_kind,task_id,outcome_summary,fired_at_iso}`, skipped `{order_id,action_kind,reason,at_iso}` | standing-order runner (`dispatch/standing_order_broadcaster.py:38-67`) |
| `_meta` / `subscribed` | server→client | `{channels: [..]|null}` | response to `subscribe`/`unsubscribe`/`subscribe_all` control message (`websocket_hub.py:265-282`) |

Filter precedence for `hub.broadcast(channel, type, data, user_id?, profile_id?)`:
1. `profile_id` set → only matching `client.profile_id` (`websocket_hub.py:154`).
2. `user_id` set → matching `client.user_id` only (`:156`).
3. `client.wants(channel)` predicate (`:162`).
4. Both unset → fan-out to everyone.

---

## 4. `/ws/voice` (PTT and always-on)

Path: `/ws/voice?token=<jwt>` (`routes_voice_stream.py:425-427`).
Auth: user JWT only — closes 1008 if missing/invalid (`:79-88`).
NOTE: device JWTs are NOT accepted on this endpoint (see §10).

### Frame format

- Binary frames: raw mono **s16le PCM @ 16 000 Hz** (`:9-11, :65, :130-132`).
  Recommended frame size: 960 bytes (30 ms) — advisory, server buffers
  internally (`:65, :375`).
- Text frames: JSON commands.

### Commands (client → server, `:200-271`)

```json
{"cmd":"reset"}
{"cmd":"mic_duck"}                       // frontend starts TTS
{"cmd":"mic_unduck"}                     // frontend finished TTS
{"cmd":"set_confidence","value":0.0..1.0}
{"cmd":"stop"}
{"cmd":"client_speech_start"}            // hint, logged only
{"cmd":"client_speech_end"}              // hint, logged only
```

### Server → client events (`:159-198, :283-325, :371-389`)

Sent JSON (no `channel` envelope on this endpoint — direct payloads):

| `type` | payload |
|---|---|
| `ready` | `{sample_rate:16000, frame_size_recommended:960, enabled, mode, wake_phrase, silence_timeout_ms, wake_words, confidence_min, continuation_window_s, streaming_partials, partial_debounce_ms, refine_with_whisper}` |
| `wake` | wake-word detected |
| `speech_start` | VAD turned on |
| `speech_end` | VAD turned off — end-of-utterance |
| `partial` | streaming partial transcript (Vosk) |
| `final` | finalised transcript |
| `final_revised` | post-Whisper refine (when `refine_with_whisper`) |
| `rejected` | input did not pass thresholds |
| `cooldown_end` | wake-word cooldown ended |
| `reset_ack`, `mic_duck_ack`, `client_speech_ack`, `stopped`, `config`, `error` | command acks / errors |
| `tts_start` | `{text}` |
| `tts_chunk` | `{index, is_final, audio:<b64 wav>, text, sample_rate}` |
| `tts_end` | end of TTS stream |

### End-of-utterance signal

Server-driven via Silero VAD. The server emits `speech_end` and then a
`final` (and optionally `final_revised`). There is **no client-emitted
end-of-utterance command** — `client_speech_end` is a hint only and the
server stays authoritative (`:265-269`).

### Close codes

- `1008` — token required / invalid (`:55, :79-87`).
- `1011` — orchestrator init failed (`:57, :363`).
- `1000` — normal stop (`:56, :254`).

---

## 5. Pairing Flow

### Step-by-step

1. **`POST /pair/init`** (ROOT). Server creates ephemeral X25519 keypair
   `(s_priv, s_pub)`, 24-byte nonce, 60-second TTL session in memory
   (`pair_crypto.py:130-151, :75`). Returns QR JSON + SVG data URL.
2. Phone scans QR (`PairingQr.kt:128-136`), validates expiry against
   `exp` (`:88-89`), generates ephemeral X25519 (`PairFlowClient.kt:179`)
   and long-term Ed25519 (`:180`).
3. Phone derives `K = HKDF-SHA256(salt=nonce_bytes, ikm=ECDH(c_priv, s_pub), info="phantom-os/mobile-pair-v1", L=32)`
   (`pair_crypto.py:79, :224-241`).
4. Phone computes `client_proof = HMAC-SHA256(K, pair_id_utf8 || device_pub_ed25519_raw)` and sends
   `POST /pair/claim` with `{pair_id, client_pub, device_pub_ed25519, nonce_echo, client_proof, device, profile_id?, profile_display_name?}` (`pair_crypto.py:248-267`).
5. Server `consume()`s the session atomically (single-shot, `pair_crypto.py:163-173`),
   validates `nonce_echo == session.nonce_b64` (`routes_pair.py:350`),
   re-derives `K`, constant-time-compares `client_proof` (`pair_crypto.py:266`).
6. Server picks/lazy-creates `Profile` (`routes_pair.py:166-219, :391-396`),
   inserts `PairedDevice` row (`:399-413`), mints device JWT, computes
   `server_proof = b64(HMAC-SHA256(K, device_jwt_utf8))` (`pair_crypto.py:270-274`).
7. Server broadcasts `pair/claimed` (`routes_pair.py:430-444`) and returns
   the body. Phone verifies `server_proof` (`PairFlowClient.kt:217-223` —
   stores it in the outcome but verification of equality is the caller's
   responsibility; the value is exposed via `PairClaimOutcome.sharedKey`
   for caller-side comparison).
8. Refresh: phone signs `f"{device_id}:{nonce_b64}"` with the long-term
   Ed25519 private key, posts to `/pair/refresh` (`routes_pair.py:456-542`).

### Crypto details

| primitive | parameters | source |
|---|---|---|
| ECDH | X25519 raw 32B keys | `pair_crypto.py:131-135, :231-235` |
| HKDF | SHA-256, salt=`nonce_bytes` (24B), info=`b"phantom-os/mobile-pair-v1"`, L=32 | `pair_crypto.py:79, :236-241` |
| HMAC (client_proof) | SHA-256 over `pair_id_utf8 || device_pub_ed25519_raw_32B` | `pair_crypto.py:262-263` |
| HMAC (server_proof) | SHA-256 over `device_jwt_utf8` | `pair_crypto.py:270-274` |
| Device signing key | Ed25519 raw 32B pub | `pair_crypto.py:277-293` |
| Compare | `hmac.compare_digest` (constant-time) | `pair_crypto.py:266` |
| Base64 | accepts standard or url-safe; tolerates missing padding | `pair_crypto.py:192-213` |

### QR JSON shape (v1, server-emitted)

`build_qr_payload` (`pair_crypto.py:299-318`):
```json
{
  "v": 1,
  "host": "phantom.local",
  "ip":   "<auto-detected LAN ip>",
  "port": 8000,
  "pair_id": "<32-hex>",
  "server_pub": "<b64 X25519 pub 32B>",
  "server_cert_sha256": "dev-no-pin" | "<sha256 hex>",
  "exp": <int unix s, +60s from issue>,
  "nonce": "<b64 24 random bytes>"
}
```

Spec MOBILE_COMPANION.md §4 documents this same shape (with same field
names and semantics).

### Token lifetime / refresh window

- Pair session TTL: **60 s** (`pair_crypto.py:75`, monotonic clock).
- Device JWT default TTL: **30 days** (`device_token.py:54`).
- Audience: `"device"`, role: `"DEVICE"` (`device_token.py:46-47, :89-90`).
- Absolute lifetime cap: anchored to `orig_iat`, equals `ABSOLUTE_LIFETIME_DAYS` from `jwt_manager` (`device_token.py:99-100`).
- Refresh: `pair/refresh` mints a new token preserving `orig_iat` so chain dies after the cap regardless of activity (`routes_pair.py:529-533`).
- Revocation: `PairedDevice.revoked_at` checked on every WS handshake
  and refresh; revoked → 401 / WS close 4401 (`main.py:976-982, routes_pair.py:493-497`).

---

## 6. Profile Sync

REST in §2.8–§2.10. WS rebroadcasts in §3 channel inventory under
`profile_sync`. Endpoints accept dual auth (`get_user_or_device_user`).

Wire shape for `ProfileLite` (`routes_profile_sync.py:80-91`):
```
{id, display_name(1..64), role="OPERATOR", avatar_uri?, is_primary,
 archived, behavioral_model, created_at?, last_seen_at?}
```

`UserFactLite` (`:94-101`): values masked unless `?reveal=1` is passed.
Mask format: `"<first><dots><last>"` for len>4, all dots for len<=4
(`:191-196`).

`VaultCardMeta` (`:104-117`) NEVER includes secret values — only
`field_kinds: {name -> "secret"|"plain"}`.

`ChatSessionSummary` (`:120-126`): `{id, started_at?, ended_at?, message_count, summary?}`. Snapshot returns up to 20 most recent (`:317-323`).

Snapshot `?reveal=0` (default) returns masked facts. Internal helper
`emit_profile_event(user_id, type, data)` exists for desktop-internal
use (`:268-276`).

---

## 7. Companion Control verbs

Closed enum, ROOT-only. WS frames on channel `companion_control` carry
`{verb, payload, target_device_id?}`.

| verb | spec'd payload (from comments) |
|---|---|
| `open_route` | `{coords:[[lat,lon],...], summary, profile:"drive"}` (`routes_companion_control.py:129-132`) |
| `open_card` | `{card_id, ...}` — phone opens Vault detail |
| `navigate` | `{path: "/now"|"/voice"|...}` |
| `focus_screen` | empty / payload-defined |
| `lock_vault` | empty — emergency clamp |
| `ping` | empty — health probe + RTT |

Each verb is a separate REST route at `/api/v1/companion-control/<verb>`
(`:123-192`). Per-call body validation rejects unknown fields
(`extra="forbid"`, `:60`).

---

## 8. Handoff lifecycle

States: `pending | accepted | rejected | cancelled | expired`
(`routes_handoff.py:95, :223, :285, :307, :328`; expiry computed in `:259-267`).

Transitions:
- `(none) → pending`: `POST /handoff` or internal `emit_handoff_for_user`
  (`:226-229, :367-368`).
- `pending → accepted`: `POST /handoff/{id}/accept` by any device of
  the same user (`:285`).
- `pending → rejected`: `POST /handoff/{id}/reject` (`:307`).
- `pending → cancelled`: `POST /handoff/{id}/cancel` — the origin
  recalls (`:328`).
- `pending → expired`: lazy on `GET /handoff` after `expires_at`
  (`:262-265`).

`resolved_by_device_id` set from the calling device JWT on accept/reject
(`:287, :309`). On `cancel`, no resolver attribution.

`HandoffKind` enum (closed, `:105-112`): `map_route, vault_card,
chat_thread, file_drop, voice_continuation, custom`.

TTL: `15..86400` seconds, default `900` (`:122`).

---

## 9. Mobile Sensors Batch

Endpoint detail in §2.7. Schema mirrors `MobileSensorBatchIn`
(`routes_mobile_sensors.py:72-83`). Persisted to `MobileSensorBatch`
table (`:106-120`). BLE/WiFi stored as JSON columns (`:118-119`).

### Batching rules

- No server-side rate limit visible in the route — phone-driven cadence.
- WiFi forwarding to wardriving requires GPS fix (`:131`).
- Best-effort: wardriving forward errors do not fail the request
  (`:154-159`); the broadcast also tolerates failures (`:182-187`).
- `device.last_seen_at` updated on every batch (`:122`) — feeds the
  30-day dormancy logic from spec §9 of MOBILE_COMPANION.md.

Spec §6 ("Phone as sensor") prescribes 500 ms cadence to align with
ContextEngine tick. The route imposes no minimum/maximum interval.

---

## 10. Open Questions / Ambiguities

1. **WS path mismatch.** Backend mounts the multiplexed WS at `/ws`
   (`main.py:930`). The companion connects at `<base>/api/v1/ws`
   (`PhantomLink.kt:139`). One of the two is wrong. Spec doesn't disambiguate.
   FastAPI does NOT prefix `@app.websocket` decorators with
   `app.include_router` prefixes — the backend route is literal `/ws`.
   The phone will currently 404 unless a reverse proxy rewrites.

2. **Voice WS uses user JWT only, not device JWT.** `routes_voice_stream.py:81-88`
   calls `verify_token` (user audience). A device JWT will be rejected.
   Design `MOBILE_COMPANION.md` §6 says the phone "opens the WS only
   on hold-button" with the same `/ws/voice?token=<jwt>` URL. If the
   phone holds only a device JWT, it can't talk to the voice endpoint.
   No device-token branch exists like the multiplexed `/ws` has.

3. **QR `endpoints[]` v2 not emitted by server.** The phone has full
   parsing for `v: 2` QR with `endpoints[{type:"lan"|"relay"|"tailscale"}]`
   (`PairingQr.kt:43-54, :94-99`), but `build_qr_payload`
   (`pair_crypto.py:299-318`) always emits `v: 1` with single host/ip/port.
   The relay-fallback story is client-only.

4. **`PairFlowClient` retries the same body across LAN+relay candidates.**
   The HMAC `client_proof` is bound only to `pair_id || device_pub`,
   not to the host — so retrying across endpoints is safe cryptographically
   (`pair_crypto.py:262-263`). But the session is single-shot
   (`pair_crypto.py:163-173`), so the second endpoint will get
   `410 session_expired`. The retry loop will appear to "succeed on first"
   only.

5. **`PairedDevice.profile_id` FK.** `routes_pair.py:402` writes
   `profile_id=profile.id`. Spec §4 doesn't mention a profile binding
   on the pairing record — this is a code-only invariant introduced in
   "Phase 1-B (companion-v2)" comments (`routes_pair.py:81-90`).

6. **Default mobile channel set includes `chat`.** `main.py:919-928`
   adds `chat` to the default mobile filter, but spec §3 (Tier-1) only
   mentions `sensor`+`context`+`familiar`+`state` for Phase 1. The
   default is wider than the spec.

7. **`alert` channel listed but no emitter.** `main.py:924` whitelists
   `"alert"` for phones, but no `hub.broadcast("alert", ...)` call site
   exists in the backend (grep on `phantom-os/src/backend`). Either the
   channel is reserved for future use or a producer was removed.

8. **`server_proof` verification client-side.** `PairFlowClient.kt:217-223`
   stores `serverProof` in the outcome but does not call
   `hmac.compare_digest`-equivalent against `HMAC(K, device_jwt)`. The
   spec (§4 last paragraph) says the client "перевіряє server_proof" —
   the verification is the caller's responsibility, not enforced by the
   ktor client.

9. **`pair_cert_sha256` is a soft attribute.** Backend reads it via
   `getattr(config, "pair_cert_sha256", "dev-no-pin")` (`routes_pair.py:317`)
   — the `Config` class (`config.py`) does not formally declare this
   field, so it is None unless monkey-patched via `apply_overrides`.
   Production deploys must explicitly set it; nothing in code refuses
   to ship `dev-no-pin` to prod.

10. **`PairCrypto.kt`/`QrPayload.kt` deleted in companion working tree.**
    `git status` shows both removed. Pairing logic now lives entirely in
    `core-net/.../pair/` (PairingQr.kt + PairFlowClient.kt + PairProofs +
    crypto helpers). Any consumer still importing the old classes will
    fail compile.

11. **Two channels for preference changes.** PATCH `/auth/me` broadcasts
    `context/preferences_changed` (`routes_auth.py:497-507`); the newer
    `POST /profile-sync/event {type:"preference_changed"}` broadcasts
    `profile_sync/preference_changed` (`routes_profile_sync.py:564`).
    Consumers must subscribe to both or miss edits from one path.

12. **Spec §7 Tier-2 `/agent/sentinel_trigger` not implemented.** No
    matching route in the included routers — geofence trigger backend
    is unimplemented.

13. **Channel `"agent"` mismatch.** `PhantomLink.kt:56` subscribes to
    bare `agent`; backend emits only on `agent.stream` (with dot). Phone
    will never receive proactive/emotion/monologue events unless
    `"agent.stream"` is added to the subscribe list.

14. **PIN-resolve endpoint missing server-side.** `PairDiscoveryClient.kt`
    (companion-v2-phase-0) issues `GET /api/v1/pair/resolve/<pin>` to
    convert an 8-digit PIN into a `PairingQr`. The backend's
    `routes_pair.py` exposes only `/pair/init`, `/pair/claim`,
    `/pair/refresh`, `/pair/status`, `/pair/devices` — no `/resolve`
    handler exists. PIN-based pairing therefore returns 405 from the
    LAN host and falls through to the relay (also 405). The QR-paste
    path is currently the only working pair flow. Verified live on
    SM-A566B / Android 16 against `phantom-os v0.19.0-jarvis-online`
    (2026-05-09). Either the backend needs a `/pair/resolve/{pin}`
    route, or the phone should drop the PIN UX and go QR-only.

15. **`decision_diary` channel — backend route does not exist yet.**
    Phase 1.5-D shipped a local-only Decision Diary surface
    (`feature-stream/.../ui/diary/DiaryScreen.kt` + `DiaryStore` Room
    table). Phase 3-D2..D5 (companion-v2-phase-1) wire bidirectional
    sync over WebSocket, but the backend route is unimplemented.
    Phone scaffolding is in place; backend team picks up this spec.

    **Channel:** `decision_diary`

    **Direction:** bidirectional except where noted.

    **Verbs:**

    | type           | direction        | payload schema |
    |----------------|------------------|----------------|
    | `created`      | bidirectional    | `{id, ts, title, context, status}` |
    | `updated`      | bidirectional    | `{id, status, context?}` (status always present; context optional) |
    | `deleted`      | bidirectional    | `{id}` |
    | `follow_up`    | server → client  | `{id, prompt}` — server proposes a re-visit of an existing decision |

    **Field shapes:**
    - `id`: UUID v4 string, 36 chars, stable across the entry's lifetime
    - `ts`: epoch milliseconds (Long), set at creation, immutable
    - `title`: ≤ 80 chars, operator-typed, plain text
    - `context`: ≤ 1000 chars, multiline, captures *why* the decision was made
    - `status`: closed enum, Ukrainian lowercase verbatim:
      `"актуально"` | `"переглянути"` | `"завершено"`
    - `prompt` (`follow_up` only): ≤ 240 chars, the new condition that
      makes the original decision worth re-visiting (e.g.
      «вартість зросла на 30%»)

    **WS envelope** (per §3 standard shape):
    ```json
    {
      "channel": "decision_diary",
      "type":    "created" | "updated" | "deleted" | "follow_up",
      "data":    { ...payload above... },
      "ts":      <unix-ms>
    }
    ```

    **Scoping:** user_id (decisions are personal — never leak across
    a Family Hive boundary).

    **Auth (deferred):** future phases will gate `decision_diary`
    operations behind an unlocked vault; for Tier 1 the channel is
    open to any device JWT scoped to the user.

    **Phone-side consumer:** `feature-stream/.../diary/
    DiaryWebSocketSubscriber.kt` (Phase 3-D2) maps incoming verbs to
    `DiaryStore.upsert` / `update` / `delete` / surfaces `follow_up`
    as a `StreamItem.TimeShiftedNote`. Outgoing verbs are produced by
    `DiaryStore.publish` (Phase 3-D3) with a `pending_publish` Room
    table for offline queuing.

    **Open question for backend team:** `follow_up` cadence — should
    the server emit on a daily cron, or as a side-effect of a
    contextual trigger (location, calendar event, vector-similarity
    over journal entries)? The phone treats both equivalently.

16. **`companion_control/run_code` verb — backend route does not
    exist yet.** Phase 1-F5 ships `CodeRunnerWidget` with a
    «це виконається на phantom-os. дозволити?» bottom-sheet that
    dispatches `phantomLink.send(channel="companion_control",
    payload={verb:"run_code", code, lang, env_hint?})`. The current
    `ControlVerb` enum (`routes_companion_control.py:46-53`) is
    closed over six verbs — `run_code` is not in the set. Backend
    team picks up the spec:

    **Verb:** `run_code` (under `POST /api/v1/companion-control/run_code`).

    **Auth:** ROOT (matches the rest of the closed enum).

    **Request body** (`ControlIn` — extra fields forbidden):
    ```json
    {
      "target_device_id": "<uuid>",   // the calling phone's device id
      "payload": {
        "code":      "print('hi')",
        "lang":      "python" | "bash" | "kotlin-script" | ...,
        "env_hint":  "venv-name" | null,
        "timeout_s": 30                 // server-side wallclock cap
      }
    }
    ```

    **Response:** `ControlOut` (`{accepted, delivered_to, verb}`) on
    enqueue; the actual stdout / exit-code is not returned in-band.
    Phone listens on `companion_control/run_code/result` (a sub-channel)
    for the report.

    **Phone-side gating:** the Run button on `CodeRunnerWidget` is
    disabled when `pairStore.isPaired.value == false` so the operator
    can't try to dispatch into nothing. The bottom-sheet confirmation
    is single-step (no «do not show again» — code execution always
    needs explicit consent).

    **Sandbox:** out-of-scope for the spec; backend team picks
    container / virtualenv / kotlin-script-eval per their own threat
    model. The phone emits the code; what runs it is the server's
    problem.

17. **`call_screen` channel — Phase 3 W4-6, docs-only.** Phone
    scaffolding lands in this commit; backend team implements the
    server side in a follow-up. The channel mirrors call-screening
    decisions to the desktop so the operator's phantom-os UI knows
    when the phone screened a call (e.g. surface a notification on
    the desktop overlay, log into the activity feed, allow remote
    «answer for me» from a paired Linux machine).

    **Channel:** `call_screen`

    **Direction:** bidirectional (mostly mobile → desktop; one
    desktop → mobile verb for the future remote-screen flow).

    **Verbs:**

    | type                | direction        | payload schema |
    |---------------------|------------------|----------------|
    | `incoming`          | mobile → desktop | `{id, caller_id?, label?, decision, reason, decided_at_ms}` |
    | `decision_recorded` | mobile → desktop | `{id, action, acted_at_ms}` — `action ∈ {"accept","reject","listen_first"}` |
    | `request_screen`    | desktop → mobile | `{caller_id}` — desktop tells phone to silence the in-flight call (Phase 4 deferred wire) |

    **Field shapes:**
    - `id`: UUID v4 string, 36 chars; mirrors the `call_screen_log.id`
      Room row primary key on the phone side
    - `caller_id`: raw caller id from `Call.Details.handle.schemeSpecificPart`
      (E.164 when the carrier supplies it, may be a vendor-specific
      string for VoIP). Null when caller-id was withheld at network
      level.
    - `label`: friendly display name resolved via Contacts lookup.
      Null when the caller is unknown.
    - `decision`: closed lowercase enum: `"allow"` / `"screen"` /
      `"reject"`
    - `reason`: machine-grep tag, closed enum:
      `"trusted-contact"` / `"unknown-number"` / `"caller-id-withheld"`
      / `"vault-locked"` / `"denylisted"`
    - `action`: closed enum: `"accept"` / `"reject"` / `"listen_first"`
    - `decided_at_ms`, `acted_at_ms`: epoch milliseconds (Long)

    **WS envelope** (per §3 standard shape):
    ```json
    {
      "channel": "call_screen",
      "type":    "incoming" | "decision_recorded" | "request_screen",
      "data":    { ...payload above... },
      "ts":      <unix-ms>
    }
    ```

    **Scoping:** user_id (calls are personal — never leak across a
    Family Hive boundary).

    **Auth:** ROOT for `request_screen` (matches the rest of
    companion-control); device JWT for `incoming` /
    `decision_recorded` (mobile pushes its own state).

    **Phone-side producer:** `feature-godmode/.../service/
    SecretaryCallScreeningService` writes a row to
    `core-data/.../calls/CallLogEntry` Room v9 table, then publishes
    `incoming` once the phantom-os WebSocket transport is wired
    (separate phantom-os ticket — phone scaffolds the table + bus
    in this commit, the WS publish is a follow-up).

    **Phone-side consumer:** `IncomingCallCard` Compose component in
    `feature-stream/.../ui/IncomingCallCard.kt` renders «прийняти /
    відхилити / спочатку послухати» actions; tap routes through
    `CallLogRepository.recordOperatorAction` and (Phase 4 wire)
    publishes `decision_recorded`.

    **Open question for backend team:** retention policy for
    `call_screen_log` mirroring on the desktop side. Phone retains
    indefinitely (Room table grows monotonically until a future
    cleanup pass). Desktop ought to mirror the same retention
    window if it caches; otherwise treat each `incoming` as
    fire-and-forget UI surfacing.

---

## 11. Widget Catalog (P4-3 — Chat Artifacts)

Widgets are rendered inline in the Stream by `WidgetRenderer.kt`. Each
envelope is shaped as

```json
{
  "type":    "<widget-kind>",
  "payload": { ...kind-specific schema... }
}
```

where `type` is one of the constants in `WidgetTypes` and `payload` is
parsed by the kind-specific `@Serializable` data class. The wire format
of `payload` is **always** a JSON object — the chat pipeline stringifies
it before handing it to `StreamItem.Widget(id, type, payload)`. New
parser shapes pick **one** canonical schema; legacy dual-shape parsers
are flagged for retirement under §11.99 (see chart entry).

Backend `respond_*` tools (`phantom-os/src/backend/ai/chat_tools.py`)
fan-out to these widget kinds via the `_widget_response` short-circuit
in `chat_pipeline.py`. Tier A widgets below are routed by the AI's
function-call choice; Tier B (`artifact`) is the long-tail escape hatch
for anything outside the catalog.

### 11.1 `markdown` — rich text bubble

```json
{
  "type": "markdown",
  "payload": {
    "markdown": "# Heading\n\nSome **bold** *italic* with `inline code` …\n\n- bullet\n- bullet\n\n```python\nprint('hi')\n```"
  }
}
```

Supported subset of CommonMark (line-based parser, single pass, no
nesting):

| Construct | Syntax | Notes |
|---|---|---|
| Headings | `# H1` / `## H2` / `### H3` | 4+ hashes fall through to paragraph |
| Bold | `**bold**` | |
| Italic | `*italic*` | |
| Strikethrough | `~~struck~~` | |
| Inline code | `` `code` `` | monospace + accent colour |
| Fenced code | ` ```lang\n…\n``` ` | language captured but not syntax-highlighted in this widget |
| Unordered list | `- ` or `* ` | |
| Ordered list | `1. ` | renderer re-numbers sequentially regardless of authored index |
| Blockquote | `> quote` | left accent bar, italic |
| Horizontal rule | `---` | |
| Link | `[label](url)` | rendered as styled text — taps are **no-op** (no navigation out of the chat surface) |

**Failure mode:** payload that does not parse as `MarkdownPayload`
falls back to an empty bubble that reads «немає вмісту». Unclosed code
fences consume to EOF without crashing.

### 11.2 `table` — tabular data

```json
{
  "type": "table",
  "payload": {
    "title":   "День",
    "columns": [
      {"key": "time", "label": "Час",       "align": "start"},
      {"key": "task", "label": "Завдання",  "align": "start"},
      {"key": "min",  "label": "Хв",        "align": "end"}
    ],
    "rows": [
      {"time": "09:00", "task": "Стенд-ап", "min": "15"},
      {"time": "09:15", "task": "P4-3",     "min": "180"}
    ],
    "sortable":         true,
    "max_visible_rows": 200,
    "footer":           "усього: 195хв"
  }
}
```

- All cell values are **strings**. Numbers and booleans must be
  pre-formatted by the AI; the renderer is tolerant of JSON-typing
  variance but the schema documents string-only to keep the parser
  trivial.
- `align`: `"start"` (default) / `"end"` / `"center"`.
- `sortable=true` makes column headers tappable; numeric cells sort
  as numbers (`200 > 9`), text cells sort lexicographically
  case-insensitive. Empty / missing cells always sink to the bottom
  regardless of direction.
- `max_visible_rows` caps the render (default `200`). Overflow shows
  a «+ ще N рядів» footer.
- Empty rows or empty columns → «немає даних».

### 11.99 Schema-canonical policy (retire dual shapes)

Per the `chart_widget_dual_shape` memory: a widget that historically
accepted two payload shapes in parallel pays double the maintenance
tax. Phase 0 `chart` widget still accepts both `{label, values, trend}`
and `{kind, data, annotations}` — a follow-up commit will retire the
legacy shape after the backend prompt cutover (§12 below) lands. New
P4-3 widgets ship with one canonical schema only; if a field needs to
change shape, add a `version` field to the payload and branch the
renderer on it rather than carrying parallel field sets.

---

## 12. AI prompt — when to emit a widget

The chat system prompt (assembled by `phantom-os/src/backend/ai/
prompt_builder.py`) teaches the AI which `respond_*` function call maps
to which §11 widget kind. The catalog is the source of truth: every
widget added to §11 should land with a matching `respond_*` tool entry
in `chat_tools.py` and a one-line rule in
`personality.RESPONSE_FORMS_GUIDANCE`. Without the prompt update the
AI never picks the new kind even when the renderer is ready.

Backend session that picks up this contract should:

1. Add the `respond_<kind>` tool definition in `chat_tools.py`.
2. Map its envelope into a `StreamItem.Widget(type=<kind>, payload=…)`
   inside `chat_pipeline._widget_response`.
3. Add one-line rule to `RESPONSE_FORMS_GUIDANCE` in `personality.py`.
4. Bump the system-prompt cache-prefix (Day-5 perf comment) is
   automatic since the static block is rebuilt.
