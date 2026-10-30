# E2E Manual Test Checklist — companion-v2-phase-0

This checklist covers integration steps that require a physical Android device
and a running phantom-os Docker stack. All automated checks (unit tests,
assembleDebug, lint, static greps) are covered by the QA agent report; only
device-dependent steps appear here.

---

## Required Setup

1. **phantom-os running**

   ```bash
   cd /home/radxa/programming/my_own/phantom_ai/PHANTOM_OS_BLUEPRINT/phantom-os
   cp .env.example .env          # fill JWT_SECRET_KEY and AI_GEMINI_API_KEY
   docker compose up -d
   curl http://localhost:8000/healthz   # expect {"status":"ok"}
   ```

   Services needed: `phantom` container on `:8000`, advertising mDNS
   service type `_phantom._tcp.local.` on the local network.

2. **Android device**
   - On the same Wi-Fi as the Radxa host
   - USB debug enabled
   - APK installed from `phantom-companion/app/build/outputs/apk/debug/app-debug.apk`
     ```bash
     adb install -r app/build/outputs/apk/debug/app-debug.apk
     ```

3. **Logcat filter running**
   ```bash
   adb logcat -s PhantomLink:V PairFlowClient:V PairDiscovery:V StreamViewModel:V PhantomVM:V "*:S"
   ```

---

## Pairing Flow — LAN-first (Happy Path)

- [ ] Open companion app → Pair screen → enter a valid PIN shown on Radxa desktop
- [ ] Logcat shows `PairDiscovery: PIN <n> — trying LAN discovery`
- [ ] Logcat shows `PairDiscovery: LAN discovery hit: <ip>:<port>` (NOT `LAN miss → relay fallback`)
- [ ] Pair completes in under 5 seconds (no relay roundtrip)
- [ ] Logcat shows `PairFlowClient: Successfully claimed pairing via http://<lan-ip>:8000`
- [ ] WS connects to `ws://<lan-ip>:8000/ws?token=<jwt>` — confirm in logcat `PhantomLink`
- [ ] Logcat shows subscribe sent for channels: `sensor, context, state, familiar, pair, agent.stream`
- [ ] No subscribe for bare `agent` channel (only `agent.stream`)
- [ ] App home screen shows "Connected" link status indicator

---

## Pairing Flow — Relay Fallback

- [ ] Disable Wi-Fi on the device (use mobile data) OR block mDNS at router level
- [ ] Open companion app → Pair screen → enter PIN
- [ ] Logcat shows `PairDiscovery: LAN discovery hit` timing out, then `relay fallback`
- [ ] Pair still completes via relay endpoint `relay.phantom-ai.io`
- [ ] App shows "Connected" after relay WS handshake

---

## Server Proof Rejection (Security Invariant §10.8)

- [ ] On Radxa: open `phantom-os/src/backend/security/pair_crypto.py`
- [ ] Locate the `build_server_proof` function and corrupt its output (e.g. flip one byte or return `b"\x00" * 32`)
- [ ] `docker compose restart phantom`
- [ ] Attempt pairing from device
- [ ] App shows error: `Сервер не пройшов перевірку proof` (Ukrainian: "Server did not pass proof verification")
- [ ] No JWT is stored — app remains in unpaired state
- [ ] Restore `pair_crypto.py` and restart phantom before continuing

---

## Voice → AI Roundtrip (STT Dispatch Fix)

- [ ] Device is paired and WS is connected
- [ ] Press PTT button, speak "what time is it"
- [ ] Logcat shows exactly ONE `STT result` emission from `HybridSttEngine`
- [ ] Logcat shows `StreamViewModel` calling `AIRouter.complete()` exactly once
- [ ] Logcat shows ZERO references to `sendToAi` (function was deleted)
- [ ] AI response tokens stream back and appear on the chat surface
- [ ] No duplicate AI responses for a single utterance

---

## Channel Reception — Server → Phone

For each channel below: trigger a server-side event (via Radxa desktop UI or
direct API call), then verify the phone receives it in logcat / UI.

| Channel | How to trigger | Expected phone behavior |
|---|---|---|
| `state` | Change desktop focus mode (focus → conversation) | `PhantomViewModel._state.systemState` updates; logcat shows state name |
| `sensor` | Radxa heart-rate sensor emits a BPM change | BPM indicator in `SystemPulseHeader` updates |
| `context` | Change a desktop preference (e.g. theme) | `contextState` flow receives `ContextSnapshot` with new preference |
| `agent.stream` | Desktop agent emits a proactive utterance | `agentEvents` SharedFlow emits `AgentEvent`; type visible in logcat |
| `chat` | Send a desktop chat message to the session | `chatEvents` flow emits `ChatEvent`; message appears on chat surface |
| `map` | Desktop sends `map.show_route` payload | `mapEvents` flow emits `MapEvent` with op=`show_route` |
| `companion_control` | Desktop sends `lock_vault` verb | `controlVerbs` flow emits `ControlVerb` with verb=`lock_vault` |
| `handoff` | Initiate handoff from desktop | `handoffEvents` flow emits `HandoffEvent`; phone shows handoff prompt |
| `pair` | Refresh device JWT from desktop | `pairEvents` flow emits `PairEvent` with kind=`claimed` or `refresh` |

For each row:
- [ ] Event arrives within 2 seconds of server emission
- [ ] No crash (check `AndroidRuntime:E` in logcat)
- [ ] Correct channel string in `HubEvent.channel` — especially `agent.stream` not `agent`

---

## Misc Channel Catch-all

- [ ] Trigger an unrecognised channel (e.g. `profile_sync`) from the server
- [ ] Logcat shows event routed to `miscEvents` flow, not dropped silently
- [ ] No crash

---

## Notes for Operator

- Docker: `docker compose` (plugin form) is required. `docker-compose` (v1 standalone) is NOT present on the Radxa host.
- `.env` file: must be created from `.env.example` — `JWT_SECRET_KEY` and `AI_GEMINI_API_KEY` are mandatory; the compose file will refuse to start without them.
- mDNS advertising: phantom-os advertises `_phantom._tcp.local.` only when the `PHANTOM_MDNS_ENABLED` env var is set (or by default if the NSD library is bundled). Verify this in `phantom-os/src/backend/` if LAN discovery does not hit.
- APK path: `phantom-companion/app/build/outputs/apk/debug/app-debug.apk` (produced by `./gradlew assembleDebug` on the Radxa host).
