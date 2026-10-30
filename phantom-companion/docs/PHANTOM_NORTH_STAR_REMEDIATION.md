# PHANTOM Companion — North Star Remediation

Date: 2026-06-30

This is the product repair plan for moving Companion from "chat app with features"
to "multi-surface symbiotic operating layer".

## Diagnosis

The concept is not failing because one button, one screen, or one model is weak.
It is failing because powerful subsystems are presented as disconnected app
features. The operator sees chat, map, settings, permissions, models, pairing,
and tools as separate burdens instead of one coherent Phantom presence.

The fix is a product architecture shift:

- Stream is the command center, not a single generic chat.
- Map is a spatial reasoning surface, not only tile rendering.
- Settings is a capability cockpit, not a warehouse of switches.
- Permissions are capability unlocks with visible payoff, not random Android
  detours.
- Pairing is the start of distributed self, not a QR afterthought.
- Tools are trustworthy only when every autonomous action is explained,
  reversible, and audited.

## Product Principles

1. Show Phantom's state before asking the operator to trust it.
2. Every permission must answer: what new organ does this unlock?
3. Every system ability must have a visible status: ready, limited, missing, or
   acting.
4. Chat must support surfaces, people, devices, memory, tools, and spatial
   context without turning into one undifferentiated log.
5. Map must understand "where, why, next, risk, memory", not only latitude and
   longitude.
6. Proactivity is allowed only when it is useful, calm, reversible, and
   attributable.
7. No fake-working theater: when an action is not wired, the UI names the seam
   honestly and offers the next setup step.

## Architecture Targets

### Stream V3: Cognitive Cockpit

- Persistent surface rail: Now, People, Devices, Map, Memory, Tools, Vault.
- Capability state row: brain, voice, map, screen, mesh, tools, memory.
- Per-surface context hints: "what Phantom sees here" and "what it can do next".
- Assistant bubble actions promoted from hidden long-press into discoverable
  action menus for complex results.
- Threading evolves from arbitrary chat buckets into task/person/device spaces.

### Map V3: Spatial Intelligence

- Live location confidence, bearing, follow mode, reverse address.
- Nearby POI density and selected-place reasoning.
- Phantom layer: suggested route, memory places, mood zones, avoid zones,
  people, offline regions.
- Long-press / selected place can ask AI with exact coordinates and local
  context.
- Map output can return to Stream as a structured decision, not plain text.

### Settings V3: Capability Matrix

- Top-level sections become capability groups: Brain, Senses, Space, Mesh,
  Memory, Autonomy, Privacy.
- Each group has state, unlocked features, missing permissions, and test action.
- OS permission screens are launched only from a row that explains why.
- Debug-only controls move behind diagnostics.

### Pairing V3: Distributed Self

- QR remains the reliable method.
- BLE near-radar, audio chirp, and local-network discovery become staged
  capability paths, clearly marked by availability.
- Paired devices publish capability manifests: compute, sensors, storage,
  battery, screen, audio, trust level.

### Autonomy V3: Trust Contract

- Every tool call has three surfaces: proposed action, permission decision,
  audit result.
- Reversible actions show undo.
- Destructive actions require elevated confirmation.
- Repeated low-risk actions can become trusted routines.

## Execution Plan

### Slice 1 — Trust and Shape

- Remove timeline dimming/scale that makes chat feel broken.
- Add Stream Cognitive Cockpit with visible surfaces and capability status.
- Add Map Spatial Brief so map opens with a sense of "what Phantom knows".
- Write this North Star plan and keep future work tied to it.

### Slice 2 — Chat That Can Work

- Make assistant message actions visible for latest answer.
- Add "branch", "save to memory", "ask from map", and "delegate to device"
  affordances with honest wired/unwired states.
- Convert single smart replies into mode-aware suggestions.
- Add people/device/task surface filters backed by real thread metadata.

### Slice 3 — Settings That Explain Itself

- Replace long switch list with capability cards.
- Move debug state override out of normal flow.
- Add permission payoff copy and live tests: mic test, screen-awareness test,
  map tile test, pairing ping.

### Slice 4 — Spatial Phantom

- Map layers for memory, people, offline, route, and Phantom suggestions.
- Selected POI returns a structured Stream card.
- Route/nearby prompts include current location, search target, offline packs,
  recent movement, and saved memories.

### Slice 5 — Distributed Phantom

- Capability manifests for phone, Radxa, wearables.
- Device handoff UI from Stream and Map.
- Cross-device progress and failure reporting.

### Slice 6 — Proactive Intelligence

- Decision diary and memory recall become first-class surfaces.
- Proactive insights show why now, confidence, action, and dismissal reason.
- Learned routines start as suggestions, then become trusted automations.

## Current Commit Direction

This pass begins Slice 1. It does not pretend to finish the full vision. It
changes the product posture: Stream becomes a cockpit, Map becomes a spatial
briefing surface, and the roadmap becomes explicit enough to keep the next
work from drifting back into disconnected features.
