# PHANTOM METAMORPHOSIS — THE 40K INITIATIVE

**Mandate:** OMNI-LEVEL, 2026-07-16. The kinematic engine is ironclad
(IRONCLAD-1/2, a4b4dc9). Now the shell evolves to match it: main screen,
navigation, every chamber, the assistant's daily surface, and the
symbiote's personality — one campaign, executed in waves.

**Branch:** `companion-lux`. **Baseline:** `a4b4dc9` (re-drive QA baseline —
see Law Μ-1). **Predecessor doctrine:** `docs/PROTEUS_PROTOCOL.md` — this
plan *absorbs* PROTEUS's unbuilt waves (P1, P3, P6, P7); it does not
compete with them. Where this document and PROTEUS disagree, PROTEUS's
laws win and this document is wrong.

---

# ЧАСТИНА 0 — Конституція кампанії

### Закон Μ-1 — Запечатаний двигун
The kinematic core is the re-drive QA baseline and is **sealed**:

- `CustomLocationEngine`, `FixFlux`, `BearingFuser`, `SpoofGate`,
  `RoadMeshStore`, `RoadMatcher`, ZUPT, the Kalman spine — **no wave may
  modify them**. The shell consumes `NavFix` / eased FixFlux output /
  fused course, nothing deeper.
- If a wave discovers it "needs" an engine change, the wave is wrong.
  Stop, record the finding, redesign the wave.
- Rationale: 4 h of Kyiv field failure taught us unverified churn is the
  enemy (`ZERO-FAIL`, `KYIV FIELD REPAIRS`). The engine gets field QA
  before it changes again.

### Закон Μ-2 — Правило еквівалентної еволюції
No feature is stripped. For every organ deprecated, an existing organ
absorbs its function in a superior form, recorded in the
**Еквівалентний реєстр** (Частина III). A deprecation without a ledger
row is a regression, not progress.

### Закон Μ-3 — Верховенство PROTEUS
PROTEUS Закони 1–6 (одне тіло, вісь Z, розчинення, глибина замість меню,
хаптика — скелет, чесність недоторканна) remain the supreme UI law.
Every Metamorphosis wave is judged against them. The honesty invariants
in particular: no fabricated waveforms, no fake theater (nothing on
screen without a real backend — the 2026-06-28 purge is precedent),
verbal transparency stays, widgets render inline, PHANTOM is «він».

### Закон Μ-4 — Чесна маса
Scale comes from **breadth of real subsystems**, never from padding.
The 500-line rule holds for every new and touched file. The firewalls —
`TypographyDriftFirewallTest`, `ShapeDriftFirewallTest`,
`PhantomThemeAuditTest`, plus the new `MotionDriftFirewallTest` (Μ0) —
must be green after every wave. A line counter is an output of honest
work, not a target; code written to inflate a number is deleted on
sight.

### Закон Μ-5 — Хвильові ворота
Every wave ends with: `./gradlew :app:assembleDebug` green (never bare
`assembleDebug` — `:wear` dex-merge breaks it), unit suites green
(`core-design`, `feature-stream`, `app`), a wave commit, and appended
manual-QA items in `docs/E2E_MANUAL_CHECKLIST.md` style. Device QA is
batched by the operator; **no adb input simulation** — the operator
presses. Host law: this box cannot run parallel subagents — waves are
executed serially with direct tools.

---

# ЧАСТИНА I — Анатомія на старті (аудит 2026-07-16)

| Organ | State | Metamorphosis fate |
|---|---|---|
| `ContinuumHost` (671) | P0 built: camera-Z, 3 strata, back-semantics, OrbActor outside layers | Kept; gains velocity-carrying rack springs (Μ0), parallax polish (Μ5) |
| `MotionTokens` (102) | 3 verbs: snap / breathing / fade | Evolves into `PhantomMotion` grammar (Μ0) |
| `PhantomNavGraph` (616) | Transition zoo P5 marked «під ніж» — still alive | All routes migrate to `chamberDrift` verbs (Μ0) |
| Dialogue stratum | Bubbles + LivePresenceHeader + FacetBar + PinnedStrip + scroll-FAB | Манускрипт: prose, Меридіан, recession, composer fold (Μ1) |
| `SettingsDialog` (1257) | Largest 500-line violation in the shell | Decomposed into the Settings realm panes (Μ2) |
| 16 chambers (Vault, Diary, Agenda, People, Creator, Body, Hands, LifeGraph, Whisper, Lens, Spaces, HistorySearch, …) | Alive, uneven aesthetics | Window-by-window glass pass (Μ2), Agenda→День (Μ3) |
| `SurfaceTray` in `StreamNav` | Superseded by Orb Chambers (P5) but still wired | Absorbed into Сузір'я, residue removed (Μ2) |
| `PhantomMenu`/`WidgetContextMenu` | P2 leftovers — dropdowns still standing | Migrate to Сузір'я petals (Μ2) |
| `EmotionTone`, `StateAccent`, `BreathPhase`, `VoiceAura`, `SystemState` | Five separate mood-ish signals | Unified `MoodEngine` bus (Μ4) |
| `PhantomHaptics` (10 verbs) | Grammar exists, coverage partial | Full vocabulary + heartbeat (Μ4, Μ5) |
| `GlassCard`, `LivingCard`, `PhantomSheet`, `PhantomTextRoles`, `PhantomRadii` | The design-kit spine | The material every wave builds from |

What already honors the mandate and is **not** rebuilt: the Orb single
actor, Constellation gesture engine, Chambers, stratum switcher
(СВІТ/СХОВОК), PhantomSheet spring physics, edge-to-edge immersion,
FixFlux camera easing. Rebuilding the healthy is TABULA RASA, and
TABULA RASA was revoked.

---

# ЧАСТИНА II — Хвилі

Порядок жорсткий: Μ0 → Μ1 → Μ2 → Μ3 → Μ4 → Μ5. Μ3 may swap with Μ2 if
the Settings decomposition blocks on design review.

## Μ0 — КІНЕТИКА: the Motion Kernel

**LANDED 2026-07-16** — build green, units green (firewall 2/2, grammar
pins 4/4, Shape/Typography firewalls untouched-green). Field findings:

- *Velocity carry is already physics, not plumbing:* the descent gesture
  commits mid-drag at the 180px crossing (no fling velocity exists at
  that moment) and `Animatable.animateTo` inherits in-flight velocity
  when racks interrupt. A VelocityTracker on the nested-scroll seam
  would be fake work over a field-repaired gesture (W-12 scar). The
  rack ships as the named `PhantomMotion.rackSpring`.
- *Shared-element API confirmed available* (BOM 2024.11.00 → Compose
  1.7.5); `sharedMorph` lands with its first consumer in Μ1 — helpers
  with zero call-sites are dead code, not architecture.
- *The zoo died at the root:* navigation 2.8.4 supports graph-level
  default transitions, so per-route args were deleted rather than
  migrated — the LUX family gained pop-symmetry it never had.

The single largest lever on "cinematic": before Μ0 three verbs existed
and the nav graph improvised. After Μ0 there is **one motion
vocabulary** and zero ad-hoc transitions.

- `core-design/motion/PhantomMotion.kt` — the grammar built atop
  `MotionTokens` (which stays; its constants are pinned by tests):
  - **`chamberDrift`** — the canonical chamber enter/exit: scale
    0.96→1.0 + fade on the snap spring, subtle vertical drift; four
    verbs (`enter`, `exit`, `returnEnter`, `returnExit`) so back always
    feels like *returning*, not re-opening. This is the knife P5
    promised for the nav-graph zoo.
  - **`stratumRack`** — camera-Z spring that accepts gesture
    `initialVelocity`, so a flick continues at the finger's speed
    instead of restarting from zero. `ContinuumHost.rackTo` adopts it.
  - **`veilFade`** — the sanctioned scrim/veil alpha spec (sheets,
    constellation scrim, chamber returns) — one curve everywhere.
  - **`sharedMorph`** — shared-element choreography helpers. *Gated on
    a live check of the Compose BOM's shared-transition API level; if
    the API is unavailable, morphs are built as rect-lerp overlays and
    the doc records the divergence. Never build blind against an API
    we haven't confirmed on this toolchain.*
- `PhantomNavGraph` migration: every `composable()` route declares its
  transitions through the verbs; per-route improvised
  `slideIn/tween/fadeIn` combinations die.
- **`MotionDriftFirewallTest`** — source-scan in the
  `ShapeDriftFirewall` tradition: raw `tween(`/`spring(`/`slideIn`
  outside `core-design/motion` (and sanctioned exceptions, enumerated
  in the test) fail the build. Count violations at HEAD *before*
  writing the test — the firewall lands with an honest baseline.
- QA: chamber open/close feels like one substance everywhere; back
  from any chamber lands where the drift began; no transition visibly
  differs per route.

## Μ1 — МАНУСКРИПТ: PROTEUS P1, absorbed verbatim

**Μ1 COMPLETE 2026-07-16** (Μ1a 163e69d, Μ1b 9830edd, Μ1c 46f9b93,
Μ1d 33ea3ac — all gates green). Μ1d landed the composer fold: seam ↔
crease-open 180ms (`creaseQuart`), `GlassCard.presence` dial (the Закон-3
mechanism for all Μ2 dissolutions), imprintCommit on send, IntentDock as
whisper. Recorded deviation: PTT keeps its left seat. Field findings:

- *The codebase had converged on §2.1 before the wave:* AssistantBubble
  was already containerless prose; the rebirth was the name matching the
  law. OperatorImprint keeps the on-device glass-pane verdict («floating
  debug text» → the pane) over the spec's solid-ink text — deviation
  recorded in the organ itself, Appendix-D style.
- *ACTING exists* (TurnPhase has five members, not three) — the tool
  phase sparks in state accent today; ChatTints verb-hue plumbing comes
  with the Μ4 mood bus (TurnTrace owns the tool family).
- *TimeRail is a tombstone:* deleted pre-Μ1 with cause; §2.4's scrubber
  is moot unless the operator revives it live. TimeRib already IS the
  fathom line in substance.
- *Gate law tightened:* the typography firewall lives in core-design's
  suite — every feature-stream slice runs core-design's tests too (the
  Μ1a rename shipped a red firewall for one slice; repaired in Μ1b).

The main screen *is* the Dialogue; its redesign is P1 and P1's spec is
already approved doctrine. Built as written in PROTEUS §2 + Хвиля P1:

- `AssistantBubble` → `AssistantProse` (container death; the
  assistant's words sit on the canvas). `UserBubble` →
  `OperatorImprint` (§2.1 form).
- **Меридіан** — one living line replacing LivePresenceHeader +
  FacetBar + PinnedStrip; turn-phase lives in the line's behavior.
  (Appendix D's deviation #2 is revisited here: with the Меридіан
  born, the mini-Orb's seat on it is re-decided live.)
- Смерть scroll-FAB — unread physics via the Меридіан; `atBottom`
  logic survives.
- Часова глибина — recession of older turns, фатом-лінії, `TimeRail`
  depth gauge.
- Композер-складка (§2.5) — the surface folds; local-echo invariant
  stays under test.
- **Ризик-інваріанти:** widget-inline-render; operator imprint never
  vanishes (fix 6fc5e65); verbal-transparency notices visible without
  card flesh; `StreamUiState.items` stays a stable snapshot (the
  per-token re-merge scar).
- QA: a long thread reads as manuscript; unread is felt without a FAB;
  zero rich-widget regressions.

## Μ2 — ПАЛАТИ ВІДРОДЖЕНІ: window-by-window

**Stage A — Settings realm. LANDED 2026-07-16.** The 1257-line
monolith decomposed into `SettingsRealm` (the scaffold, ~330) +
per-subject panes in the same package (zero import churn): `BrainPane`,
`AssetsPane` (grid + `AssetGridCard` + the shared `ModelRow`),
`SettingsPanes` (voice/map/mic/orb/link), `AccessPane`, `DangerZonePane`
(self-contained double-confirm + cooldown state machine); shared chrome
(`SettingsSectionCard`, `SectionHeader`) joined `SettingsDesignKit`.
Every control verified moved — ledger row closed. `DangerZoneController`,
`SaveConfirmationStrip` survive as realm-wide organs.

**Stage B — the chamber pass.** Audit of 2026-07-17 split the chambers
into two classes:

- *Already honest and dressed* (Vault, Diary, LifeGraph, Whisper — all on
  theme surfaces, GlassCard, zBackGesture, PhantomMono kickers): light
  polish only — typography roles, dissolution triggers, haptic verbs.
- *FAKE THEATER — the 2026-06-28 law applies before any glass:*
  **PeopleScreen** (hardcoded contact roster incl. «Оля» — the exact stub
  already purged once from threads; fake UWB/BLE proximity, fake
  pubkeys), **HandsAutomationScreen** (hardcoded AutomationLog lines that
  never happened; three local toggles wired to NOTHING — screenAwareness/
  overlayOrb/callScreening exist as real prefs elsewhere), **Body** (ring
  animation cosmetic; data source unverified). These get honest re-wiring
  (real prefs, real logs, or honest emptiness) — decoration of a corpse
  is exactly what the purge forbade.

For each chamber:

- `PhantomTextRoles` typography only; `GlassCard`/`LivingCard` spine
  for list flesh; `PhantomRadii` shapes; state accents through the
  theme, never hardcoded (ChatTints stays the one sanctioned
  exception).
- `chamberDrift` entry (free, from Μ0).
- A dissolution trigger per Закон 3: chrome idles to zero, gesture
  resurrects.
- Micro-interactions with real haptic verbs — no decorative buzzing.

**Stage C — глибина замість меню, доведена.** Remaining dropdown
call-sites (`PhantomMenu`, `WidgetContextMenu`, long-press of a turn)
migrate to Сузір'я petals; `SurfaceTray` residue in `StreamNav` is
removed once its targets are reachable through the Orb (each target
verified live first). `PhantomDropdownMenu` deleted after the last
call-site.

- QA per chamber: reachable in ≤1 gesture from the Orb; back returns
  to the drift origin; firewalls green; no control lost (ledger
  audit).

**Μ2b LANDED 2026-07-17** (8c0cf3f): the fake-theater purge — People
(hardcoded roster incl. «Оля», fake ECDH claims → the one real peer with
its real Ed25519 identity), Hands (invented logs → CallLogRepository;
orphan toggles → real VoicePreferences flows), Body (hardcoded 72 BPM +
data-free animated gauges → the real battery ring + «немає датчика»).
Pruned: SystemPulseHeader + ContextStrip (zero live call-sites). Net
−597 lines. **Stage C (dropdown→Сузір'я, SurfaceTray removal) is
DEVICE-GATED** — Appendix D: кожен call-site живцем.

## Μ3 — СТРІЧКА ДНЯ: the Ultimate Daily Assistant

**LANDED 2026-07-17** (b14a0fd): Agenda was the fourth fake-theater
chamber (invented events, hardcoded 20°C). DayScreen wired to real
backends: OpenMeteoClient at the real last-known fix (honest absence
without one), the ubilling air-raid feed, rapid actions through the real
Lens prompt bus + the M-I01 map bus. Calendar honestly absent until a
real permission bridge exists. DeadlineWatch deliberately stays on the
map (guidance-session state). ChamberDestinations.kt carved out;
PhantomNavGraph 715→582.

Agenda evolves into «**День**» — the operator's daily command surface.
Everything on it is wired to a real backend; a tile without a backend
does not ship (fake-theater law).

- **Ранковий рядок** — the brief: weather (existing widget spine),
  air-raid oblast status (keyless ubilling feed, already verified),
  first deadline from DeadlineWatch, transit-home hint
  (`transit.home`).
- **«Відлік»** — DeadlineWatch timeline as the ribbon's spine;
  `nav.deadline` integration; countdown physics with haptic detents at
  thresholds.
- **RapidActions** — a teachable pinned-action grid. Each action is a
  first-class tool invocation through the existing executor
  (`mission.*`, `transit.home`, `route.along`, `place.info`,
  `apps.launch`, device tools). Operator pins/unpins via Сузір'я.
  WorkManager (not coroutine loops) for anything that must survive
  OEM battery kills.
- **Тіло і місії** — Body chamber vitals (`VitalsRow`) and the
  Autopilot missions HUD state surface here as live organs; the
  chambers remain, День aggregates.
- QA: every tile provably live (airplane-mode honesty check — tiles
  admit absence instead of faking presence).

## Μ4 — ШАР ОСОБИСТОСТІ: quirks with a spine

Every quirk maps to a **real signal** — Закон 6 applies to personality
too. No random shimmer pretending to be mood.

- **`MoodEngine`** (core-design) — one bus unifying `EmotionTone`,
  `StateAccent`, `BreathPhase`, `VoiceAura`, and `SystemState` into
  `MoodState(tone, tempo, glow, gesture)`. Consumers stop sampling
  five sources; the five feeders keep their producers (Equivalent
  Evolution: unify consumption, don't delete signals).
- **Dynamic status glows** — SystemState (ФОКУС/ВАРТА/ПРИМАРА/ТІНЬ/
  ДІАЛОГ/СОН) drives a slow chamber-tint drift through MoodAwareTheme.
- **Haptic heartbeat** — `PhantomHaptics.heartbeat`: a sub-perceptual
  pulse while PHANTOM runs long tools (>3 s, keyed to TurnTrace
  phase), silence on completion — the operator *feels* him working.
- **Processing shifts** — LocalBreathPhase tempo quickens while
  thinking, settles on answer; the Orb leans toward the composer on
  input focus.
- **Чесні дивацтва** — each keyed to a real event: air-raid alert →
  amber pulse on the Orb; jamming detected (SpoofGate verdict, read
  not touched) → protective shiver; deep night → «нічний» dimmed
  breath; app birthday / first-fix-of-day → one-time glint. Each
  quirk: one file, one signal source, documented.
- QA: with logs open, every visible mood change traces to a logged
  signal.

**Μ4 LANDED 2026-07-17** (7db2093): PhantomAppShell NAMED as the mood
engine (the five-signal bus already existed — a second unification would
be scaffolding theater) + two real-signal tempo shifts (Processing
quickens the breath ×0.6, night theme slows it ×1.3) +
PhantomHaptics.heartbeat on sustained ACTING (>3s, 2.4s beat, silence on
completion). Status-glow drift found already existing (stateAccent).
Deferred with cause: Meridian verb-hue + text-turn breath quickening
(both need a process-global turn-phase hub with tool family — one
subsystem, built once, not two private channels); event quirks (air-raid
Orb pulse, SpoofGate shiver) need a global alert bus — Μ5+ material.
**GATE LAW learned:** the drift firewalls read cross-module sources
invisible to Gradle's up-to-date check — wave gates run core-design
tests with `--rerun` or a stale green hides count moves.

## Μ5 — СЕНСОРНЕ ЗАВЕРШЕННЯ + РОЗЧИНЕННЯ: PROTEUS P6+P7, absorbed

**DEVICE-GATED IN FULL (2026-07-17):** the haptic-coverage audit needs
fingers, sound is earned live, the dissolution inventory needs eyes on
glass, and the perf half was always «Пристрій обов'язковий». Nothing
here may be built blind; Μ5 begins with the operator's QA pass of
Μ0–Μ4.

- Full `PhantomHaptics` vocabulary on every stratum/chamber boundary;
  one haptic voice per gesture, id-guards against double-buzz.
- `SoundEngine` hooks land wired but **default silent** — sound is
  earned, per Закон 5.
- Закон-3 audit: full chrome inventory; every element gets a
  dissolution trigger and a resurrection gesture, or dies.
- Perf pass against `docs/PERF_BASELINE.md`: stratum-transition jank,
  hibernation battery, map-snapshot memory. **Пристрій обов'язковий**
  — the perf half of Μ5 never runs blind.

---

# ЧАСТИНА III — Еквівалентний реєстр

| Deprecated organ | Absorbed by (superior form) | Wave |
|---|---|---|
| `PhantomNavGraph` transition zoo | `PhantomMotion.chamberDrift` verbs | Μ0 |
| `LivePresenceHeader` + `FacetBar` + `PinnedStrip` | Меридіан — one living line | Μ1 |
| scroll-FAB | Меридіан unread physics (`atBottom` survives) | Μ1 |
| `AssistantBubble` container | `AssistantProse` on the canvas | Μ1 |
| `SettingsDialog` monolith (1257) | Settings realm panes on `SettingsDesignKit` | Μ2 |
| `SurfaceTray` (StreamNav long-press) | Orb Сузір'я / Chambers | Μ2 |
| `PhantomMenu`, `WidgetContextMenu`, remaining dropdowns | Сузір'я petals | Μ2 |
| Static Agenda list | «День» ribbon with live organs | Μ3 |
| Five separate mood signals (consumption side) | `MoodEngine` bus | Μ4 |

Ledger discipline: a row is closed only when the operator can do
everything the old organ did, faster, and the old organ's code is
gone. Rows are audited at each wave gate.

---

# ЧАСТИНА IV — Анти-дрейф (доповнення до PROTEUS-таблиці)

| Заборонено | Вартовий |
|---|---|
| Touching the sealed engine from any Μ-wave | Law Μ-1; reviewer eyes on every wave diff |
| Raw `tween`/`spring`/`slideIn` outside core-design/motion | `MotionDriftFirewallTest` (Μ0) |
| A День tile without a live backend | Fake-theater law; airplane-mode QA item |
| A mood change without a traceable signal | Μ4 QA: log-traceability check |
| Deprecation without a ledger row | Частина III audit at wave gates |
| New file >500 lines | 500-line rule; decompose before commit |
| Padding for line count | Закон Μ-4; deleted on sight |

---

# ЧАСТИНА V — QA-книга (оператор тисне сам; adb-ввід заборонено)

### Μ0 (готово до перевірки)

- [ ] Open any chamber (Vault / Diary / Settings) — it rises from depth
      (subtle scale + fade). No lateral slide exists anywhere in the app.
- [ ] Back from a chamber — the dolly runs in reverse: the chamber sinks
      into depth, the body settles forward. Back must feel like
      *returning*, never like a fresh fade-in.
- [ ] Settings → sub-pane (voice / mic / map / symbiote) and back — same
      dolly both ways, one level per Back press.
- [ ] Constellation — blooms in from around the press point (1.3 → 1),
      evaporates back on exit; feel unchanged from pre-Μ0 (only the
      easing was canonicalised).
- [ ] Stratum racks P↔D↔W — unchanged: arrival detent fires, descent
      preview tracks the finger, **no W-12 double-exposure regression**
      (bright map chrome under the chat).
- [ ] Cold start Bootstrap → Continuum — a single depth settle, no slide.

### Μ1a–Μ1c (готово до перевірки)

- [ ] The Dialogue at rest: presence row + ONE breathing hairline. No
      facet shelf, no pin shelf until the line is tapped.
- [ ] Tap the Meridian line — petals (facets, pins) unfold in ~220ms;
      tap again — they fold. A petal tick is felt on the gesture.
- [ ] Send a prompt and watch the line: slow pulse while he thinks, a
      gold spark running the line while he writes, an accent-colored
      spark while a tool runs, steady-bright under a held mic.
- [ ] Scroll up past a screenful: no floating arrow FAB anywhere. A
      short line above the composer warms (saturates toward the accent)
      as replies arrive below; each offscreen arrival is ONE haptic tick.
      Tap the warm line — dive to the tail.
- [ ] Long thread: older turns visibly recede (dimmer, a breath smaller)
      the further above the tail they sit; the live exchange reads at
      full presence. Date rules recede with the water.
- [ ] Operator turn still reads as the glass pane (NOT solid ink — that
      change happens only if you decide it live, looking at it).

### Μ1d (готово до перевірки)

- [ ] Dialogue at rest, keyboard closed, nothing typed: the composer is a
      seam — one faint fold line, the quiet invitation text, no glass
      pane, no borders; the mic/attach/send glyphs sit chromeless.
- [ ] Tap the field — the canvas creases open in ~180ms: glass pane,
      border, send circle materialize together. Blur with an empty field
      → it settles back to the seam.
- [ ] Hold the mic from the seam — PTT still works instantly (capture
      forces the fold open with the accent border).
- [ ] Send a message — the press feels like a *seal being pressed*
      (deeper than the old confirm tick).
- [ ] After an answer: intent suggestions read as one quiet line of verbs
      over the seam — no pills, no borders; each still fires on tap.

### Μ2 Stage A (готово до перевірки)

- [ ] Settings opens and scrolls exactly as before — every control from
      the old monolith present: keys, assets grid, voice, live dialog,
      map chip, mic privacy, orb overlay, permissions, Radxa link,
      diagnostics/symbiote chips, danger zone with double-confirm +
      3s cooldown.
- [ ] «✓ збережено» strip still acknowledges every reactive write.
- [ ] Import/download/delete a model from the assets grid — unchanged.

### Μ2b + Μ3 (готово до перевірки)

- [ ] People: no invented contacts. Paired → one card with the real
      Radxa node (username, LAN/relay, truncated real Ed25519 key,
      «спарований» — no ONLINE claims). Solo → an honest solo line.
- [ ] Hands: the two switches mirror Settings live (flip in one place,
      see it in the other). The journal shows only real screened calls —
      or says «поки порожньо».
- [ ] Body: the battery ring matches the system battery exactly and does
      NOT breathe (data is static → gauge is static); pulse/HRV/steps say
      «немає датчика».
- [ ] День: weather appears near your actual location (airplane-mode →
      the honest absence line, never Kyiv defaults); the air-raid card
      matches the map's picture; each rapid action lands in the Dialogue
      as a real prompt and PHANTOM answers with real tools; «мапа →»
      aims the camera at the World.

### Μ4 (готово до перевірки)

- [ ] Ask something that runs a long tool chain — after ~3s a faint
      periodic beat under the finger/pocket; the instant the answer
      lands, silence.
- [ ] During voice processing the whole surface breathes visibly faster;
      deep at night (amber theme) — slower.

---

# ЧАСТИНА VI — Слово про масштаб

The mandate names 40,000 lines. This campaign is genuinely of that
order — six waves across the entire 60k-line shell, absorbing four
unbuilt PROTEUS waves and birthing five new subsystems
(PhantomMotion, Меридіан, Settings realm, День, MoodEngine). But the
number is a **consequence**, not a goal: every line ships because a
law or a wave requires it, each wave lands green and committed, and
the campaign spans as many sessions as honest execution takes. That is
the only reading of "make it massive, make it flawless" in which both
halves survive contact with each other.

Виконання починається з Μ0 — негайно.
