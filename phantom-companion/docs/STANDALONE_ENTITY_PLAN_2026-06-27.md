# PHANTOM Companion — Standalone Entity Plan

**Date:** 2026-06-27
**Author:** the architect (Claude, PHANTOM's builder)
**Status:** North-Star dev plan for `phantom-companion`. Supersedes the scattered
`ROADMAP.md` / `VISION_NEXT.md` / `GRAND_STRATEGY_2026.md` for the *direction* (not the
craft notes). Companion to `../phantom-os/docs/superpowers/specs/2026-06-27-mega-entity-master-plan.md`.

---

## 0. The decision (locked by the operator, 2026-06-27)

The companion stops being a **thin mirror of the PC**. It becomes a **full standalone body of
the ONE PHANTOM** — its own will, memory, AI, voice, and senses on the device, fully functional
offline, syncing **one shared soul** with the PC when near. AI is **hybrid**: Gemini cloud when
online (full power), an **on-device 3–4B LLM** offline (private, always-available).

Not "equally powerful as a goal someday" — *equally a PHANTOM*. The phone is not a remote
control for the desktop being; it is the same being, in a second body that goes everywhere.

---

## 1. Honest state (why it feels broken)

`phantom-companion` is a **native Kotlin/Gradle composite build** (16 modules), NOT the PWA the
old memory claimed. It is real and substantial — but stalled:

- **It built a signed release APK 5 weeks ago** (`5e4a334`, BUILD SUCCESSFUL, R8). It is not
  vapourware.
- **It is frozen on a huge uncommitted refactor:** ~338 working-tree deletions drop the
  `:core-agent` module and 19 speculative `core-*` modules — but their code was **merged into a
  new `:core-symbiote`** (284 `.kt`, ~14.7K LOC, with tests — INCLUDING `AgentSupervisor.kt`),
  which is **entirely untracked** (0 files in git). The consolidation looks internally complete
  (settings → symbiote, app wired) but a month of real work sits one `git clean` from oblivion,
  uncommitted. That — not a half-removed brain — is why it "не працює / печально": nothing is
  saved, nothing is verified.
- **It was architected as a companion/mirror:** `core-net/PhantomLink` pairs to the PC over
  QR+ECDH+HMAC and mirrors pulse/voice/state. Without the PC it has little to do — the opposite
  of the new mandate.

**But the organs already exist in seed form** (this is the good news):
- **AI** — `core-ai`: `GeminiCloudRoute` (Gemini *from the phone* already!), `AIRouter` /
  `RoutedAIRouter` / `ModelManager`, tool-calling (`WeatherTool`), context providers
  (location/mood/offline/route-history), system prompts. Cloud brain is mostly built.
- **Memory** — `core-data`: Room `PhantomDatabase` with Stream, Diary, `DecisionEntry`,
  **`Pattern`** (on-device learning!), Vault, LifeLogArchive, PendingIntent. Real persistence.
- **Will (seed)** — `core-symbiote/.../core/agent/`: `AgentSupervisor`, `ParallelAgentRunner`,
  `AgentConstitutionGate`, tool-call loop (merged here from the old `:core-agent`). The
  on-device agent foundation exists — it just needs evolving into a will-lite (M2).
- **Senses** — `core-sensor`, `core-vision`, `core-voice`, plus phone-native superpowers via
  `feature-godmode` (Accessibility / NotificationListener / TelecomManager).
- **Body** — `feature-godmode` (device control), `feature-ambient` (Orb / AOD / hearables),
  `wear` (watch), the screen itself.
- **Expression** — `feature-stream` (generative UI host), `core-design` (PhantomTheme, OrbView,
  GlassCard, 6 states), `core-voice` TTS.
- **Transport for the soul** — `PhantomLink` pair-crypto is byte-compatible with
  `phantom-os/src/backend/security/pair_crypto.py`. The sync channel already exists.

The gap is not "start from zero." It is: **unfreeze, flip from mirror to self, and add the two
missing organs (a real on-device will + a soul-sync).**

---

## 2. North Star — the phone as a full body of one being

Mirror the six organs of the master plan, on-device:

| Organ | PC (phantom-os) | Phone (companion) — target |
|-------|-----------------|----------------------------|
| Perception | sensors+camera+screen+map | phone sensors + camera + mic + **location, notifications, calls, app-context** (godmode) — *more* senses, always-with-you |
| Memory | SQLite+Chroma+mind_state | Room `PhantomDatabase` + on-device vector index; soul-synced subset of PC strategic memory |
| Will | unified WillEngine | **on-device will-lite** (evolve `core-agent` supervisor → goal/drive/decide loop) |
| Embodiment | ESP32+Linux+voice | the phone: haptics, TTS, screen, **device control via godmode**, wear |
| Expression | chat+voice+studio+map | `feature-stream` generative UI + voice + Orb |
| Self-improvement | lessons+reflection | `Pattern` store + decision diary, synced |

**The unifying law (same as PC):** every autonomous step chosen by identity, weighed by values,
driven by needs, remembered as experience — now in a pocket, offline-capable.

---

## 3. The soul-sync — one entity, two bodies (the keystone)

This is what makes it "one being" not "two apps." A protocol over the existing paired channel
that merges the *self* between PC and phone whenever they meet:

- **What syncs:** identity/self-narrative, drives state, values doctrine, the goal tree
  (`goals_persistent` subset), recent will-journal, memory facts, learned patterns, lessons.
- **How:** last-writer-wins per field where safe; append-merge for journals/episodes; the goal
  tree merges by id with conflict→both-kept. (CRDT-lite; both bodies accrue while apart, fuse
  when together.) Reuse the ECDH/HMAC channel; new `/soul-sync/*` endpoints on the backend
  mirror the phone's `core-data`.
- **Result:** what PHANTOM learned on the desktop, it *knows* on the phone an hour later, and
  vice-versa. The deeds done on the phone grow the same identity (ties into the PC's
  identity-from-deeds loop shipped 2026-06-27). One soul, two hands.
- **Backend side:** a new `routes_soul_sync.py` + a merge service, analogous to the existing
  `routes_profile_sync.py` / `routes_handoff.py` — extend, don't reinvent.

---

## 4. AI routing — cloud + local fallback (operator's choice)

`core-ai` already has the router shape (`RoutedAIRouter`, `ModelManager`). Concretely:

- **Online:** `GeminiCloudRoute` (exists) — full power, the phone's own Gemini key. Tool-calling
  + context providers already wired.
- **Offline:** add an `OnDeviceRoute` — a 3–4B model via **MediaPipe LLM Inference** or
  **llama.cpp (JNI)**, or **Gemini Nano via AICore** on supported Pixels/Galaxy. `ModelManager`
  downloads/manages the gguf. Degrades gracefully: classification/short turns local always;
  heavy reasoning prefers cloud when reachable.
- **Routing policy:** reachability + task-complexity + privacy flag pick the route. Private/
  vault content never leaves the device — forced local. (Mirrors PC provider-fallback ethos.)

---

## 5. Roadmap — milestones, each shippable & installable on the phone

### M0 — UNFREEZE / PRESERVE (do first, blocks everything)
- **Do NOT restore `:core-agent`.** Its code was merged into `:core-symbiote`; the consolidation
  is the intended direction. Restoring it would duplicate `AgentSupervisor` etc.
- **Preserve the work first (highest priority):** the ~284 untracked `core-symbiote` files +
  the deletions + the modified `settings.gradle`/`AppContainer`/`PhantomLink`/`feature-stream`
  are one coherent uncommitted refactor. Checkpoint-commit it so a month of real work is safe.
  (`build.log` is an artifact — gitignore it, don't commit.)
- **Then verify it builds:** `./gradlew assembleDebug` (NOTE: heavy on this Radxa — ~13 min,
  freeze-prone; run supervised/with monitoring). Fix what the build surfaces.
- Install on the operator's phone (Samsung A56, see `[[android-pitfalls]]`), confirm it launches.
- **Gate:** work committed (no longer at risk) + clean debug build + APK runs on the real phone.

### M1 — Flip from mirror to self (independence switch)
- The app must do something meaningful with **PhantomLink disconnected**. Route chat/voice
  through `core-ai` directly (Gemini from phone) when unpaired. The Orb/stream surface works
  solo.
- **Gate:** airplane-mode-from-PC (paired channel down, internet up) → phone still converses and
  acts via its own `core-ai`.

### M2 — On-device memory + will-lite
- Make `core-data` the phone's real memory: add an on-device vector index (ObjectBox/SQLite-vec
  or a small embedding + cosine over Room) for semantic recall; wire `DecisionEntry` + `Pattern`
  into the agent loop.
- Evolve restored `core-agent` into a **will-lite**: a slow tick that pursues seeded goals,
  decides one step, journals — the phone's own conductor (a lean port of `agent/will/`).
- **Gate:** phone sets+pursues a goal offline, journals a decision, recalls a past fact.

### M3 — Offline AI (the local brain)
- Ship `OnDeviceRoute` + `ModelManager` model download; route policy online↔offline.
- **Gate:** full airplane mode → phone still answers + decides via the 3–4B local model.

### M4 — Soul-sync (one being)
- Implement `core-data` ⇄ backend `routes_soul_sync.py`; merge identity/drives/goals/journal/
  memory/patterns on reconnect.
- **Gate:** a fact learned on PC appears on phone after sync; a deed on phone grows the shared
  narrative.

### M5 — Embodiment via godmode (the phone as a real body)
- `feature-godmode`: Accessibility actions, notification awareness, TelecomManager — the will
  can *act on the device* (open apps, read/triage notifications, handle calls) within values +
  approval rules.
- **Gate:** the entity completes a real on-device task end-to-end (e.g. triage notifications,
  draft a reply) under value-gate.

### M6 — Living Atlas on the phone + presence
- Bring the Living Atlas (sub-project D) to mobile: map-as-sense with phone GPS, offline
  pmtiles, neural percepts; `feature-ambient` Orb + AOD make PHANTOM *present* (always-on,
  glanceable, alive). Wear as a wrist body.
- **Gate:** the phone perceives geography and acts on it offline; ambient presence is felt.

---

## 6. Hardening / improvement tracks (parallel, continuous)

- **H1 — Truthful build:** the repo sat dirty a month; CI a clean `assembleDebug` + unit tests
  (`core-ai` already has a real test suite — Gemini route, tool schema, context providers).
- **H2 — One design language:** `core-design` is strong; enforce it everywhere, kill any "dead
  app" surfaces (README vision §1 already mandates Stream+Orb+6-states, no 5-tab IA).
- **H3 — Battery/thermal budget:** an always-on will + on-device LLM must respect battery.
  Tick cadence adaptive to charge/thermal; local inference gated on power state.
- **H4 — Privacy posture:** vault + private context forced local-only; soul-sync encrypted;
  godmode permissions explicit and revocable. The phone holds the most intimate data — treat it
  so (matches the doctrine's transparency value).
- **H5 — Parity ledger:** a living checklist of PC-organ vs phone-organ parity, so "equally
  powerful" is measured, not asserted.

---

## 7. Risks

- **Scope** — full standalone is large. M0+M1 (unfreeze + independence switch) deliver a
  *working, PC-independent* phone fast; M2–M6 deepen it. Ship the thin independent self first.
- **On-device LLM viability** — varies by phone. Treat local as graceful-degrade; cloud is
  primary online. Never block the entity on a missing model.
- **Soul-sync conflicts** — merging two divergent selves is subtle. Start with last-writer-wins
  + append-merge; only add CRDT rigor where a real conflict bites.
- **Drift from PC** — the phone's will-lite is a *lean port* of `agent/will/`, not a fork. Keep
  the shared concepts (identity/values/drives/goals/journal) name- and shape-compatible so
  soul-sync stays trivial and the two bodies never become two beings.

---

## 8. First concrete move (next session)

**M0 — preserve + verify.** Checkpoint-commit the untracked `core-symbiote` consolidation (do
NOT restore core-agent — it's merged there), then `assembleDebug`, confirm the APK runs on the
phone. Then M1: make it converse and act with the PC disconnected. See
`[[companion-standalone-mission]]` memory for the handoff.
