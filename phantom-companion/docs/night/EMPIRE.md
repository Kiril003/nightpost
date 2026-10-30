# PHANTOM — the three surfaces

Owner's architecture note, 2026-07-26. Written after measuring what
actually exists, not what the READMEs claim.

## The finding that changes the plan

**The backbone is already built and tested.** I expected to design a
device-identity layer tonight. It exists, on both sides, with real
cryptography:

- `phantom-companion/core-net/.../pair/PairProofs.kt` — X25519 ECDH,
  HKDF-SHA256, HMAC-SHA256 proof of possession.
- `phantom-os/src/backend/security/pair_crypto.py` (318 lines) — the
  byte-equivalent counterpart. The HKDF info string is pinned on both
  sides and a divergence is a hard 400, deliberately.
- `routes_pair.py` — init / claim / refresh / status / devices / revoke.
- QR out-of-band verification, WS device auth, and a mobile foreground
  service (`PhantomLinkService`) that keeps the link alive across doze.
- Tests on the Python side covering pair QR, WS subscribe, WS device
  auth, and pair refresh.

X25519 identity + authenticated key agreement + out-of-band verification
is *exactly* the primitive set an end-to-end encrypted peer channel
needs. This is the expensive, easy-to-get-wrong part, and it is done.

## What each surface is

**Phone — `phantom-companion`.** Kotlin/Compose. The operator's body:
sensors, GNSS, air-raid, offline maps, voice, the chat surface. Goes
where he goes and works with the network down. This is the product most
people will ever see, which is why tonight went into its face.

**PC — `phantom-os`.** Python/FastAPI + React, 685 py files (~152k
lines), 387 ts/tsx (~60k). The operator's desk: the agent kernel, the
long-memory store, the heavy tool executor, ChromaDB.

**Correction (2026-07-27).** I first repeated the architect's list of
"god objects" — `tool_executor.py` 2718, `loop.py` 2178, `models.py`
1359 — by line count alone. Measured properly, most of that list is
wrong:

- `tool_executor.py` — 77 top-level functions, largest 95 lines. A long
  file of small functions is not a god object. Leave it.
- `models.py` — 52 classes, largest 76. It is a schema file. Leave it.
- `routes_chat.py` — `send_message` at 387 lines. Worth splitting, not
  urgent.
- **`loop.py` — this is the real one.** `_run_task_loop_impl` is a
  single function of **1225 lines** at nesting depth 11, with 652 lines
  sitting at depth ≥5. It is the worst function in either codebase and
  the only thing on the PC side that genuinely gates the peer work,
  since this loop is what a node runs on someone else's behalf.

Line count is not the measure; a file of small functions is fine and one
1225-line function is not. Measure before assigning a team.

**Peers — not built.** Family, friends, work.

## The topology, and why it is not a messenger

Today the shape is a star: phone → *my own* PC. The PC is the hub, and
the hub belongs to the operator. Keep that.

For peers, the same shape composes into a mesh of stars: **each
operator's own PHANTOM OS node is their rendezvous.** Two people talking
is two nodes agreeing to relay for their owners. There is never a central
server, never an account, never a company in the middle — which is the
only version of this consistent with a product whose entire premise is
local-first privacy. Co-located peers skip the nodes entirely and go over
LAN/BLE directly.

The differentiator the owner named — *it must not be a usual messenger* —
falls straight out of that. A peer link is not a text pipe with the
Familiar bolted on. It is a channel between two Familiars who already
know their operators:

- «де ти, ти в порядку?» does not need to be typed or answered. The
  Familiar answers it, from the position it already holds, through the
  spoof/network-divergence gate already built for it.
- An air-raid alert for a peer's oblast reaches you because their node
  already knows and yours already speaks.
- A route ETA is live because navigation is a first-class thing here,
  not an attachment.
- A vault item can be shared to exactly one peer, sealed to their key.

The unit is not the message. The unit is **what my Familiar may tell
yours**, scoped per peer: family sees position and safety, work sees
availability and ETA, a friend sees what you granted. Text is one
projection of that, and the least interesting one.

## Execution order

1. **Finish the phone.** It is the surface that sells the product and it
   is closest to done. Nothing below matters if the first screen does not
   convince.
2. **Pay down `phantom-os`'s god objects** — the same decomposition the
   phone needs, and a precondition for a node stable enough to act as
   someone's rendezvous.
3. **Peer channel, transport first**: reuse `PairProofs`/`pair_crypto`
   unchanged, add peer-to-peer rendezvous between two nodes. If the
   crypto needs changing, that is a signal the design is wrong.
4. **Grant model before UI.** What my Familiar may tell yours, per peer,
   revocable. Build this before a single peer bubble renders, because
   retrofitting consent onto a shipped channel is how every messenger
   ends up leaking.
5. **The peer surface** — a thread whose other side is a person. The
   chat surface already exists; this is a new participant type in it, not
   a new screen.

## Standing rule

Every claim in this file was measured tonight. Where the code's own
comments contradicted reality, reality won — that happened five times
tonight already. Measure before designing on top of something.
