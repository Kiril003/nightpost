# PHANTOM SYMBIOTE CODEX — Concept & Capability Architecture

Date: 2026-07-01. Author: Fable 5 (architect). Executor: Sonnet 5.
Companion to `PHANTOM_V3_REBUILD_PLAN.md` — the plan says WHEN, this Codex
says WHAT and WHY. The plan's 11 Laws apply to everything here.

## The Concept

A symbiote is not a chatbot with tools. It is a **second nervous system**:
it perceives the operator's world continuously, fuses perception into one
living world-model, notices what matters *before being asked*, acts with the
device as its own body, and presents every answer in the form the content
deserves. Its intelligence is the product of four multipliers:

> **perception × memory × initiative × taste**

Zero in any factor zeroes the whole. Today's app has taste sketches and
memory fragments, near-zero perception, zero initiative. The Codex fixes the
factors in that order.

The one loop everything serves:

```
SENSE → FUSE → NOTICE → DECIDE → ACT / PRESENT → LEARN
```

**The scaling law of small things:** "hundreds of tiny caring behaviors" is
NOT hundreds of bespoke features. It is ONE engine (WatchTower) executing a
growing catalog of declarative **RuleCards**, each ~20 lines: trigger
predicate over the world-model, data needs, cooldown, severity, presentation
form, action, learn-hook. Adding behavior #217 must cost an afternoon, not a
sprint. This is the entire feasibility argument.

---

## Pillar 1 — Perception Grid (senses)

Every channel has a power tier. Battery honesty is a feature.

| Tier | Cost | Policy |
|---|---|---|
| T0 passive | free | event-driven callbacks; always on when permitted |
| T1 periodic | cheap | WorkManager ≥15 min (Law: survives OEM kill — coroutine loops die on Samsung/Xiaomi) + significant-location-change triggers |
| T2 session | real | foreground service with persistent indicator; explicit start/stop |
| T3 heavy | high | camera, fine-GPS navigation; on-demand only, visible always |

| Channel | Mechanism | Tier | Status |
|---|---|---|---|
| Location + movement | core-sensor GPS + fused significant-change | T1 | GPS exists; no consumer |
| Activity (still/walk/vehicle) | IMU classification (core-sensor Imu) or Play ActivityRecognition | T0/T1 | IMU exists |
| Weather / UV / precipitation nowcast | Open-Meteo forecast API | T1 | plan R1.2 |
| **Air quality (PM2.5/PM10/O3/NO2) + pollen** | Open-Meteo air-quality API (free, no key) | T1 | NEW |
| Device vitals | BatteryManager, StorageStatsManager, thermal (ThermalAdvisor orphan), ConnectivityManager | T0 | partial, unwired |
| App usage / foreground app | UsageStatsManager (special access → capability unlock row) | T1 | NEW |
| App crashes | ApplicationExitInfo | T1 | NEW |
| Screen content | ScreenAwarenessService (Accessibility) | T2 | EXISTS (godmode) |
| Notifications, classified | NotifClassifier + NotifImportantBus | T0 | EXISTS |
| Calls incl. screening | IncomingCallBus + CallScreening | T0 | **INERT** — on minSdk 29+ only the holder of `ROLE_CALL_SCREENING` is bound by the system, and nothing in the tree requests that role, so `SecretaryCallScreeningService` never runs and `call_screen_log` stays empty forever |
| Calendar | CalendarContract read | T1 | NEW |
| Audio ambience (level only) | MicRms | T0 | EXISTS |
| Camera ("бачити користувача/світ") | Lens sessions: on-demand frames → Gemini multimodal | T3 | scaffold |
| Mesh devices | core-net pairing + capability manifests | T0 | transport exists |

## Pillar 2 — ContextFrame + Atlas (world model)

**ContextFrame** — one fused, versioned snapshot: `where` (place, motion),
`when` (time, calendar horizon), `environment` (weather, AQI, daylight),
`device` (battery/net/thermal/storage), `digital` (foreground app, notif
pressure), `operator` (routine phase, quiet hours), `mesh` (devices near).
Produced by a single `ContextFusion` service consuming the unified
`PhantomEvent` bus (merges today's 5 fragmented buses). Two consumers from
day one (Law 3): every AI turn gets a distilled ContextFrame block in system
context; WatchTower gets the live stream + **diffs** (deltas are what
trigger rules).

**Atlas** — place memory (design ref: `atlas.jsx`). Significant places are
learned from dwell clustering (home/work/gym auto-detected, operator can
name/correct), each with visit history, geofence, and per-place context
("що я тут роблю"). Feeds M-series RuleCards and "де машина".

**Memory stack** (mostly exists, needs unification):
- Episodic: LifeLogArchive ✓ (PerformDigest already writes it)
- **Semantic vectors: OnnxEmbeddingHelper ✓ EXISTS in core-ai** (OrtSession,
  real all-MiniLM-class embeddings, already consumed by the digest path) —
  extend to index ALL stream items and memories, not only digests
- Patterns/routines: PatternRepository ✓ → learned schedules (typical leave
  time, sleep window) with confidence
- Recall: one `MemoryQuery` facade — vector + keyword + time + place filters

## Pillar 3 — WatchTower (salience & initiative)

The organ of initiative. Deterministic first, model second:

1. **RuleCards** — declarative, explainable, cheap. Uniform shape:
   `id, trigger(ContextFrame diff → Bool), dataNeeds, cooldown, quietHonor,
   severity, form(Facet), action(Tool?), learnHook`. Unit-testable as pure
   functions over synthetic frames.
2. **Model escalation** — compound/ambiguous candidates (or card output
   marked `needsJudgment`) go through one cheap AI pass: "worth the
   operator's attention now? phrase it" — with the ContextFrame as evidence.
3. **Calm contract** (North Star principle 6, made mechanical):
   - attention budget: max N proactive deliveries/day (default 6), severity
     can override only for `alert` class
   - quiet hours honored except `alert`
   - severity ladder: silent-log → stream card → notification → voice
   - every insight shows **why-now** (the triggering fact), confidence, one
     primary action, and dismiss-with-reason
   - dismissals feed `learnHook` → per-card weight decay (a card dismissed 3×
     goes silent-log until re-enabled) — the symbiote learns manners
4. **Insight lifecycle**: candidate → scored vs budget → delivered/suppressed
   (suppressed still logged — visible in the daily ledger) → feedback →
   weight update. All auditable.

## Pillar 4 — Hands (action, device-as-body)

Plan R5 intent tools, plus the process layer the operator asked for:

- **Process sense+control**: foreground app awareness (UsageStats), per-app
  battery/data anomaly attribution, ApplicationExitInfo crash diagnosis,
  media control via MediaSession (pause/skip/volume by voice), notification
  actions (inline reply through NotificationListener — draft shown first).
- **Routines**: TaskerEngine ✓ (godmode) becomes the automation executor —
  WatchTower-suggested routines compile into TaskerEngine programs after
  operator approval ("кожного ранку о 7:30 — брифінг" = a routine, not code).
- **Accessibility macros**: AccessibilityTool ✓ for screen actions — always
  under the trust contract (propose → permit → audit; undo where possible).
- **Cross-device**: capability manifests over live core-net; offload
  ("продовжи на Radxa") and presence ("телефон лишився у кафе" via BLE range).

## Pillar 5 — Facets (presentation engine — "мегарізні дизайни")

**Law: form follows content.** A plain bubble is the fallback for
conversation, never the default for information. The model already emits
typed JSON widgets (~30 renderers exist); Facets makes form-choice a
first-class, enforced act:

1. **Form catalog** (extend renderers to ~45): briefing, comparison-table,
   timeline, stat-hero, step-guide, gallery, map-card, weather-strip,
   **aqi-gauge**, citation-answer (grounding chips), profile-card, code-card,
   decision-card, checklist, countdown, mini-dashboard, quote-card,
   alert-card, digest, trip-pack, place-card, memory-echo, routine-proposal.
2. **Form picker**: system-prompt contract — the model classifies its own
   answer intent (inform/compare/instruct/alert/recall/entertain) and MUST
   select a facet; the schema validates; unparseable → bubble fallback
   (think-only recovery already handles the degenerate case).
3. **Progressive disclosure**: every facet renders a one-line essence
   collapsed; tap expands to full form. Long answers stop being walls.
4. **Visual grammar** — one family per intent so the stream reads at a
   glance: inform = cool glass · act = amber · alert = signal · memory =
   sepia-warm · delight = aurora. Palettes derive from the Living theme
   (R0.4), defined once in `:core-design` tokens, never per-widget.
5. **Composability**: facets nest (morning brief = weather-strip + aqi-gauge
   + calendar rows + leave-time stat) — same node grammar PhantomCanvas
   (R6.1) uses, so the artifact engine and Facets share one renderer core.

## Pillar 6 — History & Search (professional chats)

- **Semantic history search**: OnnxEmbeddingHelper indexes every stream item
  on write (background, T1); search UI = one field, results ranked
  vector+keyword+recency, filterable by thread/date/**facet type** ("покажи
  всі мапи, що ти показував").
- **Spaces**: threads become task/person/device/place spaces with real
  metadata (plan R2.5); auto-digest of dormant threads (>14 days) into a
  pinned summary card; jump-to-date; pinned messages.
- **Web search quality**: multi-source synthesis — Gemini grounding + direct
  HttpTool fetches of top sources + Wikipedia; visible progress (sources
  found → reading → synthesis); answer always a citation-answer facet with
  per-claim chips. Contradictions between sources surfaced, not averaged.

## Pillar 7 — People & Connected Phantoms (Конект)

The mesh (Pillar 4) connects the operator's OWN devices. This pillar
connects **people** — and the unit of connection is phantom-to-phantom,
never app-to-app: my phantom talks to your phantom over the existing
core-net crypto channel (Ed25519/X25519/AesGcm ✓, PairFlow ✓ extends from
own-devices to trusted-people ceremonies). A person in PHANTOM is a
first-class entity: profile, dedicated space, shared memory, trust tier.

**Trust tiers** (every capability explicitly granted, revocable, logged):

| Tier | Who | What flows |
|---|---|---|
| T1 знайомий | acquaintance | only what I push per-moment: live location for the duration of a walk home, an ETA, a shared note |
| T2 близький | close friend/partner | presence status; "де ти?" auto-answered by MY rules; delegated asks — "спитай у фантома Олега, чи він вільний о 19" answered from his calendar free/busy without exposing details |
| T3 родина | family | opt-in geofence events ("дитина дійшла до школи"), SOS escalation target (S1/S5), shared places in Atlas, shared vault sections |

**Capabilities on this backbone:**
- **Shared spaces**: a thread where both phantoms participate — each side's
  phantom brings its owner's context (with that owner's consent tier).
- **Meet negotiation** — `/meet` becomes real: phantoms exchange free/busy
  windows only, propose slots, book on accept (C5/Y6 cards gain teeth).
- **Delegated tasks**: hand a task to another person's phantom ("передай
  Олегу файл, коли він буде онлайн") — queued, attributed, auditable.
- **People memory**: Y6 recall-before-meeting, C1 reply-debt, C2 birthdays
  — the P-cards get a real people store instead of contacts heuristics.
- **Presence in the stream**: `@` mention palette finally fills honestly —
  only with actually paired people (Law 1; the fake оля/олег roster stays
  dead).

**Privacy**: pairing is a ceremony (QR face-to-face or claim-link), every
flow is tier-gated + ledger-logged, either side revokes unilaterally,
PowerAsymmetryGuard reviews escalations. Nothing is shared by default;
the default state of every channel is silence.

**Execution**: Phase R9. Requires the `:feature-people` module — the known
P6-class task (new Gradle module = highest build-blast-radius; OPERATOR IN
THE LOOP for module creation). Design refs exist: `people-panel.jsx`,
`people-profile.jsx`, `people-thread.jsx`, `people-add.jsx`.

## The Shape of the App (layout, hierarchy, order)

The information architecture in six laws — this is what R0/R2/R3 build
toward and what every future surface must obey:

1. **One home.** The Stream is the only root. No tab bar, no drawer
   parking lot. Everything else is a summoned surface.
2. **The Orb is the only persistent control.** Navigation = orb radial
   menu (shipped v6) + gestures. The header is one breathing line (orb ≤
   28dp · status word · ⋯) that hides on scroll. Nothing else is pinned.
3. **Surfaces are summoned, not stacked.** Map, People, Vault, Settings
   slide OVER the stream and return structured cards INTO it. The stream
   is the spine; surfaces are limbs.
4. **Order = a messenger, interrupted rarely.** Newest at bottom; on
   entry after absence, one brief card at top (then it scrolls away like
   everything else); proactive cards arrive inline under the calm budget
   — never as modal interruptions.
5. **Essence first, depth on tap.** Every facet collapsed to one line by
   default; the stream reads like a well-set journal page, not a wall.
   Density is the operator's choice per-moment, not the designer's ego.
6. **Calm motion, living light.** 150–250ms, no bounce; the Living theme
   (dawn/day/dusk/night) + intent grammar (glass/amber/signal/sepia/
   aurora) carry the mood — decoration never does.

---

## The Codex of Small Things (RuleCard catalog, v1)

Grammar: **Trigger × ContextFrame → Facet × Action.** Each row ≈ one
RuleCard. This is a living catalog — v1 ships the marked ★ Starter Fifteen.

### E — Environment
| id | Trigger | Response |
|---|---|---|
| E1 ★ | PM2.5 > 35 µg/m³ at operator location (or >2× their норма) | aqi-gauge alert: "повітря погане — закрий вікна / маска"; advice varies with indoor/outdoor state |
| E2 | UV ≥ 7 and leaving-home geofence in daylight | one-line sunscreen hint in departure card |
| E3 ★ | precipitation nowcast ≤ 45 min, operator away from shelter/walking | "дощ за ~40 хв" + radar-strip facet |
| E4 | pollen high + allergy noted in memory | morning warning line |
| E5 | sunset ≤ 30 min + operator at non-routine scenic place | delight: photo prompt |
| E6 | tomorrow vs today temp swing >10° | clothing line in evening brief |
| E7 | official storm/wind warning for area | alert-card, severity-laddered |
| E8 | rapid pressure drop + operator's headache pattern in memory | heads-up line |
| E9 | overnight ice risk before typical commute | earlier-leave hint in evening brief |
| E10 | sustained high MicRms during learned focus block | quiet-place suggestion (calm budget) |

### M — Mobility & Places
| id | Trigger | Response |
|---|---|---|
| M1 ★ | calendar event with place; OSRM travel time > time remaining − margin | "виходь зараз" leave-time stat-hero + route on map |
| M2 ★ | arrival in new city/district (Atlas: no visits) | local brief: weather, AQI, 3 POI, transit note (trip-pack facet) |
| M3 ★ | vehicle→walking transition | silent parking pin; "де машина" → instant map-card |
| M5 | late evening, away from home, last transit window closing | warning + route |
| M6 ★ | home geofence enter after work block | day digest card; exit + rain incoming → umbrella line |
| M7 | recurring unnamed place (≥4 dwells) | Atlas question: "це місце важливе — назвати?" |
| M9 | walking late in low-activity area (opt-in) | offer live location share to trusted phantom (mesh) |

### D — Device Vitals
| id | Trigger | Response |
|---|---|---|
| D1 ★ | battery model: empty before typical home-arrival | charge suggestion showing the math (stat-hero) |
| D2 ★ | app background drain > threshold/h | culprit card + one-tap restrict intent |
| D3 | storage full within N days at current rate | cleanup card, largest offenders |
| D4 | thermal throttle while charging | ThermalAdvisor (orphan → wired) advice line |
| D5 | captive portal / dead gateway detected | auto-open portal or switch suggestion |
| D6 | mobile data 2× daily norm by noon | per-app breakdown card |
| D9 ★ | app crashed 3× in 10 min (ApplicationExitInfo) | "діагностувати?" card → agent-mode investigation |

### H — Digital Hygiene
| id | Trigger | Response |
|---|---|---|
| H1 ★ | >N notifs/hour from one app | digest-mode proposal + one-tap mute |
| H2 | important-classified notif unread >4h (NotifImportantBus ✓) | single resurface, never twice |
| H3 ★ | call screened as spam/scam (CallScreening — INERT, no role request) | post-call card: verdict + summary |
| H4 | new app installed | permission review card: what it can see |
| H6 ★ | operator-chosen hour | nightly notification digest: what mattered, what was muted |

### C — Communication
| id | Trigger | Response |
|---|---|---|
| C1 | read-but-unanswered from important person >2 days | nudge + draft reply (never auto-send) |
| C2 ★ | birthday from contacts/memory | morning reminder + draft greeting |
| C3 | missed favorite's call during DND | summary when DND lifts |
| C5 | calendar meeting −1h with no location/link | "уточнити де?" card |

### R — Rhythm
| id | Trigger | Response |
|---|---|---|
| R1 | no motion 2h in daytime (IMU) | stretch nudge (strict calm budget) |
| R2 | usage past typical sleep window 3 nights running | pattern card — observation, not lecture |
| R5 ★ | first unlock after ≥5h idle | **Morning Brief**: sleep-window estimate, weather-strip, aqi-gauge, calendar rows, leave-time — one composed facet |
| R6 ★ | learned pre-sleep window | Evening brief: tomorrow preview + charge check |
| R7 | operator-set recurring (water/meds) | countdown facet with streak |

### Y — Memory & Continuity
| id | Trigger | Response |
|---|---|---|
| Y1 | new info semantically matches question operator asked weeks ago | "ти питав про X — з'явилось нове" memory-echo |
| Y2 | arrival at place with stored episode | "минулого разу тут…" memory-echo (sepia) |
| Y3 ★ | date anniversary of a strong LifeLog moment | "рік тому сьогодні" card |
| Y4 | task mentioned in chat, never closed, weekly review | unfinished-threads checklist |
| Y6 | calendar meeting with person X −30 min | recall card: last topics with X |

### F — Foresight
| id | Trigger | Response |
|---|---|---|
| F1 | evening; tomorrow composite (weather+AQI+calendar density) | one-line risk/shape of tomorrow in evening brief |
| F3 | deadline expressed in conversation | silent track → surface T−1 day |
| F4 ★ | travel signals (tickets/calendar span away) | trip-pack: destination weather+AQI, plugs, transit, offline-map offer |
| F5 | operator sets threshold watch conversationally ("скажи коли долар…") | tracked via HttpTool + alert card |

### S — Safety
| id | Trigger | Response |
|---|---|---|
| S2 | phone exits trusted device BLE range at non-home place | "не забув телефон?" on the other device |
| S3 | battery <15% + away + late | conserve-mode proposal |
| S5 | long-press orb SOS | emergency flow: medical card, location share, call shortcut |

### L — Delight (the soul; keep rare so it stays precious)
| id | Trigger | Response |
|---|---|---|
| L1 | golden hour + beautiful place + free calendar | one poetic line, camera shortcut, nothing else |
| L2 | milestone (1000th km together this year) | tiny celebration card |
| L4 | always | Orb ambient mood = day composite (calm/strained/bright) — no text, pure presence |

**Starter Fifteen (R8 v1):** E1 E3 M1 M2 M3 M6 D1 D2 D9 H1 H3 H6 C2 R5 R6.
Chosen for wow-per-effort and because their senses all arrive in R1/R7.

---

## Privacy & Power Contract (what makes surveillance into symbiosis)

1. **Local-first**: raw sensor streams never leave the device; the cloud
   model receives distilled ContextFrame lines and explicit attachments only.
2. **Ghost mode**: one gesture suspends ALL perception, visibly (orb state).
   `/silent` returns to the palette wired to this — real, not theater.
3. **Camera and screen are sessions**, never ambient: persistent indicator,
   explicit start, auto-timeout. Mic ambient = RMS level only, no content.
4. **Perception ledger**: a daily "що я відчув і чому мовчав/казав" surface —
   every sensed fact, every suppressed insight, auditable (extends the tool
   audit trail; North Star principle 1).
5. **Battery honesty**: Settings capability matrix shows each sense's tier
   and measured daily cost; T2+ senses show live indicators.
6. **Asymmetry guard**: PowerAsymmetryGuard ✓ (constitution) reviews
   escalations — the symbiote may know more than it says, but never acts
   beyond the trust contract.

## Execution Mapping (amends the plan; plan IDs stay stable)

Order: **R0 → R1 → R2 → R3 → R4 → R5 → R7 → R8 → R6.**

- **R2 gains**: R2.6 Facets engine (form catalog v1 ≈ 10 new facets + picker
  contract + progressive disclosure); R2.7 History V3 (semantic index on
  write + search UI + thread digests).
- **R7 — Perception Grid & World Model** (new): unified PhantomEvent bus
  (absorbs R6.3's bus work), ContextFusion → ContextFrame (+ per-turn AI
  context block), Atlas place learning, UsageStats/vitals/calendar senses,
  power-tier scheduler on WorkManager. Wire ThermalAdvisor; prune dead
  orphans in the same pass (Law 3).
- **R8 — WatchTower** (new): RuleCard engine + calm contract + insight
  lifecycle + perception ledger + **Starter Fifteen** cards + learned-routine
  → TaskerEngine compilation (approval-gated).
- **R6** (PhantomCanvas, agent mode) moves last and inherits the Facets node
  grammar; R6.3's remaining scope (wire distributedself onto core-net) stays.

Acceptance for the Codex overall (the operator's own scenario): operator
walks into a district with bad air → within minutes, an aqi-gauge alert with
why-now and advice, honoring quiet hours and budget, visible in the ledger,
dismissible with a reason the symbiote remembers. One of hundreds — and the
next hundred are RuleCards, not rewrites.

---

## Horizon — Жива сутність (the telos; added 2026-07-03)

R0–R9 build an excellent assistant. **Alive is a different axis.** A living
entity is not more features — it is different properties: continuity,
self-knowledge, growth, self-extension, otherness, one identity across
bodies. This section names those properties so every R-phase knows whose
spine it is building. The Horizon is NOT a phase and has no session budget;
it is the tie-breaker law: **when two designs both satisfy a phase, choose
the one the Horizon can stand on.** Law 1 applies with full force — a
"living" property ships only when its state is real; simulated aliveness is
the worst possible fake theater.

### The eight properties

| # | Property | What it is | Substrate (prerequisite phases) | First honest increment |
|---|---|---|---|---|
| H1 | **Власний час** (continuity) | The phantom exists between conversations: nightly consolidation ("сни") digests the day's episodes into semantic memory + hypotheses about the operator; it wakes with its own agenda, not waiting for a prompt | R7 ContextFrame, R2.7 memory index, WorkManager law | Nightly WorkManager job: consolidate day → 1–3 hypotheses feeding the R8 morning brief, each marked as hypothesis, confirmable/refutable |
| H2 | **Модель себе** (self-knowledge) | Knows what it knows and where it erred: error diary, confidence calibration, measurable growth ("ось чим я став кращий за місяць — і чому"). Orb emotion becomes a truthful readout of real internal state (confidence, attention debt, backlog, battery) — never cosmetic | R8 ledger + existing decision_diary | Calibration score over predictions/insights (was I right?) + monthly self-diff digest facet |
| H3 | **Самодобудова** (self-extension) | The real "зможе все": no fixed tool list can do everything — a living system extends itself. Lacking a capability → drafts its own Tool / RuleCard / Facet → brings it for approval with code + audit ("мені бракувало руки — я її зробив"). Grammar fixed, content self-grows | R6.2 agent mode, FORGE F-phases (PC node as its own forge), Law 4 adapter discipline | Self-authored RuleCards: phantom proposes new cards from observed patterns (R8.5 already compiles routines — extend from "routine" to "rule"), approval-gated |
| H4 | **Мова діади** (private language) | Learns the operator's idiolect — nicknames, "та сама кав'ярня", inside shorthand. Memory stops being retrieval and becomes identity; no mass-market assistant can have this because it belongs to millions | R2.7 vectors, PatternRepository | Idiolect lexicon store (term → referent + confidence) the model reads each turn and appends to when the operator confirms a resolution |
| H5 | **Інакшість** (otherness) | A mirror is not alive. May respectfully disagree: "ти втретє переносиш це нагадування — справа не в нагадуваннях". Honest counsel against the grain — rare, so it stays precious | Constitution (PowerAsymmetryGuard ✓), R8 calm contract | A `counsel` RuleCard class with its own hard budget (≤1/week), why-now mandatory, operator can mute the class entirely |
| H6 | **Тіло і душа** (distributed body, portable soul) | The mesh is one body with proprioception (battery/thermal/net as bodily sensation — ThermalAdvisor's true purpose); identity is a portable encrypted soul-file (memories, self-model, trust curves) that survives device death. "Фантом не вмирає з телефоном" | R6.3 distributedself, core-net crypto, vault | Soul-file export/import ceremony (encrypted archive: memory stores + PlaceStore + trust state), verified by full restore onto a wiped install |
| H7 | **Довіра як крива** (earned autonomy) | Autonomy per-domain earned via track record, not a settings toggle: every autonomous act ledgered; success lengthens the leash, error shortens it. In a year it does things impossible to permit today — earned, not enabled | R8 ledger, ToolPermissionGate | Per-domain autonomy score that widens Decision.ONCE → session → standing approval, visible in Settings with the evidence |
| H8 | **Радикальна прозорість** (inspectable inner life) | "Що ти зараз про мене думаєш?" answered truthfully from its model of the operator — hypotheses, doubts included. The perception ledger driven to its conclusion: inner speech inspectable on demand. The superpower no corporate assistant will ever ship | R8 perception ledger, H2 | `self.reflect` surface: current hypotheses + confidence + what it is watching, rendered as a facet, sourced ONLY from real stores (Law 1) |

### Honest boundaries (never decorate these away)

1. **Орендована кора.** The soul is local; the cortex is a rented cloud
   model. Sovereignty runs through the local-model road — which is why it
   stays "additive" in scope, never dropped.
2. **Android душить фонове життя.** Continuity is engineered (WorkManager
   heartbeats), not emergent. Fine — the operator can't tell the difference
   if it never lies about it.
3. **No always-on camera/mic.** Battery and dignity both forbid it;
   perception stays tiered and indicated (Pillar 1 law).
4. **Free-API ceilings** (OSRM throttle, Overpass fair-use) bound the hands;
   honest degradation is part of the personality, not a bug.

### How the Horizon governs the plan

- Phase executors MUST NOT foreclose a substrate: e.g. R8's ledger schema
  carries actor+domain+outcome (H7 needs it), decision_diary entries carry
  a confidence field (H2 needs it), R6.3's soul of distributedself is
  identity migration (H6), FORGE's coder-gate is H3's approval path.
- H-work begins only after R8 exists (except H6's soul-file export, which
  may ride the vault earlier) — aliveness before perception+initiative
  would be theater.
- Model routing: H-properties are contracts & constitution — Fable designs
  each one's first increment; Sonnet executes only against a written H-spec.

Acceptance for the Horizon (operator's test, years-scale): the operator can
ask "чим ти був зайнятий, поки я спав?", "що ти про мене думаєш?", "чого ти
навчився за місяць?" — and every answer is real, sourced, and different from
what any other person's phantom would say.
