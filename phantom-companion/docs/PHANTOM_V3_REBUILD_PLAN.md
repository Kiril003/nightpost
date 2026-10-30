# PHANTOM V3 — Total Rework Execution Plan

Date: 2026-07-01. Author: Fable 5 (planning). Executor: Sonnet 5.
Supersedes the execution ordering of `PHANTOM_NORTH_STAR_REMEDIATION.md`
(its principles stay in force; its slices are absorbed and re-ordered here).
Concept & capability architecture: `PHANTOM_SYMBIOTE_CODEX.md` (same date) —
it defines Facets, ContextFrame, WatchTower, the RuleCard catalog, and the
privacy contract that phases R2.6–R2.7, R7, and R8 below implement.

## Why this plan exists

Operator verdict on the current build, verified against the code:

| Operator complaint | Verified root cause |
|---|---|
| "Map renders, but no points, no routes, nothing" | Marker/polyline infra EXISTS (`MapWidget.kt` MAP-6/MAP-8) but no tool produces real geodata — no geocoding, no routing, no POI search. The model has nothing real to draw. |
| "It voiced something but nothing was written near the map" | Verbal-transparency notices are spoken by TTS (`StreamViewModel.kt` ~1678–1748) but not rendered as visible stream items. |
| "Why `/commands` in the composer? There's nothing behind them" | `ComposerCatalog.SLASH` = 15 commands, ~0 wired. "Honest stubs" that read as fraud. |
| "Grey-black all day" | `ThemeMode.kt` has exactly 2 palettes (SunriseWarm / AmberNight); System-dark keeps it AmberNight all day. No living/time-of-day theme. |
| "Doesn't see real geolocation" | `core-sensor` has GPS but there is NO location tool and NO location context injection — the AI literally cannot know where the operator is. |
| "Takes fake weather" | No weather tool. The model hallucinates data into the weather widget. |
| "Can't work with internet info" | Gemini Google-Search grounding NOT enabled in `GeminiCloudRoute.kt`; `HttpTool`'s `HostAllowlist` starts EMPTY so even HTTP is dead by default. |
| "Can't do phone commands" | Only Launch/Accessibility/Settings tools; no alarm, timer, flashlight, volume, calendar, dial/SMS tools. |
| "Top row (orb, focus, 3-dot) wastes space" | Header oversized; must collapse to a minimal presence line. |
| "Chat vs Telegram = sky and earth" | Message list/composer below messenger baseline (see R2 checklist). |
| "Self-made artifacts/HTML are nothing" | One-shot HTML in a WebView. No native generative-UI pipeline, no multi-pass quality loop. |

Root diagnosis: **the app is presentation-rich but sense-poor.** Beautiful or
not, an assistant with no location, no weather, no web, and no phone hands is
"дуже тупий" by construction. Fix the senses first, then raise the chat above
the Telegram baseline, then build the unattainable layer.

## Laws (non-negotiable, apply to every phase)

1. **No fake theater.** A command, button, or palette entry may exist in the UI
   ONLY if its handler executes a real capability end-to-end. Unwired = absent
   (not greyed, not "TODO" toast — absent).
2. **Spoken = shown.** Anything TTS says must exist as a visible stream item.
3. **No new class in `AppContainer` without a consumer** (see
   `orphaned_symbiote_tiers`: ~18 subsystems already rot there).
4. **Every new Tool requires extending the flat-schema→OpenAPI adapter in
   `GeminiCloudRoute.kt`** (`propSchema`), or cloud 400s on EVERY request.
5. **Never set `thinkingBudget=0`** — it kills all typed-JSON widgets.
6. Build gate: `./gradlew :app:assembleDebug` (NOT bare `assembleDebug` — the
   `:wear` module fails dex-merge). Never pipe gradle through `| tail` (masks
   exit code). Full cycle ≈ 9 min on this SBC — batch changes per build.
7. **Serialize everything.** This host crashes when parallel subagents run.
   One phase, one commit, one verification at a time.
8. Run module unit tests for every touched module before commit.
9. UI lock pills must never call `VaultStore.lockNow()` (one-way invariant).
10. All new network calls are HTTPS (avoids the NSC cleartext/CIDR pitfall).
11. Files under 500 lines where feasible; `StreamViewModel.kt` (3049) must not
    grow — extract new logic into collaborators.

---

## Phase R0 — Trust Repair (1 session, do first)

Small, fast, removes the "it lies to me" feeling.

1. **Purge the slash palette.** In `ComposerCatalog.SLASH` keep ONLY entries
   with a real end-to-end handler today (audit each; expected survivors: none
   or `vault`). Delete the rest. They return one-by-one in later phases as
   they become real. Same audit for `ATTACH` (returns real in R2.3).
2. **Spoken = shown.** Wherever `tts.speak(...)` fires in `StreamViewModel`,
   also emit a small visible stream item (muted "voice notice" style) with the
   same text. Keep the `voice_verbal_transparency_invariant` — never silence.
3. **Shrink the header.** Collapse the top area to a single minimal line:
   orb ≤ 28dp + one status word + overflow `⋯`. Auto-hide on scroll-down,
   reveal on scroll-up (standard messenger behavior). Delete anything the
   overflow already covers.
4. **Theme: stop the all-day grey-black.** Add `ThemeMode.Living` (make it the
   default): a pure resolver `resolveLivingTheme(hourOfDay)` returning one of
   4 palettes — Dawn (05–09), Day (09–17), Dusk (17–21), Night (21–05).
   Derive Dawn/Day/Dusk as new `PhantomThemeSpec` instances from SunriseWarm
   with shifted hue/temperature; Night = AmberNight. Keep Light/Dark/System
   as explicit overrides. Unit-test the resolver boundaries.

Acceptance: composer shows no dead commands; every spoken notice visible in
stream; header ≤ 56dp and hides on scroll; at 13:00 the app is warm/light by
default, at 23:00 dark.

## Phase R1 — Real Senses (the "тупий" fix; highest priority after R0)

### R1.1 LocationTool + ambient location context
- New `core-symbiote/tool/impl/LocationTool.kt`: `location.current` returns
  lat, lon, accuracy, bearing, speed, age, and reverse-geocoded address.
  Backed by existing `core-sensor` GPS. Register in ToolRegistry +
  PermissionGate (+ Gemini adapter, Law 4).
- Ambient injection: on each AI turn, append a one-line location context to
  the system context (coords + coarse address + movement state) when the
  permission is granted. The model must never have to ask "where are you".

### R1.2 WeatherTool (kill fake weather)
- New `WeatherTool.kt`: `weather.now` / `weather.forecast` via **Open-Meteo**
  (`api.open-meteo.com`, HTTPS, free, no key). Args: lat/lon (default =
  current location), hours/days.
- Hard rule in the system prompt + widget path: the weather widget may only be
  rendered from tool output. If the tool fails → honest error card, never
  invented numbers.

### R1.3 Web access
- **Enable Gemini Google-Search grounding** in `GeminiCloudRoute`: add
  `{"google_search": {}}` to `tools`. Gemini 2.5 supports mixing it with
  function declarations — verify on-device with one real query; if the API
  rejects the mix, fall back to a `web.search` Tool that makes a secondary
  Gemini call with grounding only and returns snippets+URLs.
- Render grounding citations (title + URL chips) under answers that used it.
- Seed `HostAllowlist` defaults: `open-meteo.com`,
  `nominatim.openstreetmap.org`, `overpass-api.de`,
  `router.project-osrm.org`, `wikipedia.org` (still per-call gated by
  ToolPermissionGate; the operator can revoke in Settings).
- Restore `/search` to the palette — now real (Law 1).

### R1.4 Geo stack (the map stops being empty tiles)
- `GeoTool.kt` with three functions, all HTTPS public services with graceful
  degradation and correct User-Agent:
  - `geo.search` — geocoding via Nominatim (respect 1 req/s policy).
  - `geo.nearby` — POI via Overpass (cafes, pharmacies, ATMs… radius query).
  - `geo.route` — walking/driving route via public OSRM (demo server may
    throttle: on failure return an honest "routing unavailable" — Law 1).
- Wire outputs into the existing `MapWidget` `markers`/`polylines` fields and
  into full `MapScreen` layers. "Знайди аптеку поруч" must produce dots on
  the map and a route on request.

Acceptance (all on-device, real): "яка погода?" → real Open-Meteo numbers for
the real current location; "що це за місце?" → reverse-geocoded answer;
"знайди 3 кафе поруч і покажи на мапі" → markers appear; "новини про X" →
grounded answer with citations.

## Phase R2 — Chat Beyond the Telegram Baseline

**Progress (2026-07-04): chat-upgrade polish pass (`chat-upgrade-1`) on top
of r2.1–r2.7.** Verb-hued action system (`ChatTints`: sky/indigo/teal/green/
violet/rose — the documented fixed-hue exception, same class as syntax
colors): last-answer row grew to six colored actions (regenerate/alt-angle/
copy/share/speak/vault), long-press menu gained «поділитись» (system share
sheet, UI-side) + «озвучити» (StreamEvent.SpeakMessage → TtsEngine,
bypasses the auto-voice toggle on explicit ask, honest notices when
unspeakable), attach menu joined the PhantomMenu family with per-source
hues, slash palette per-command tints, tool-trace lines color-code by tool
FAMILY (weather=sky, space=teal, phone=violet, net=indigo, exec=green,
inner=rose; trouble stays coral) with tinted expanded chrome, user bubble
got the lit-from-above 8%-gold gradient. Tests: SpeakMessageTest (4),
TurnTraceToolParseTest +3. Visual on-device check pending operator.

The operator's bar is Telegram. Checklist — implement all, in this order:

1. **Message list**: day separators; timestamps; message grouping by author;
   smooth streaming-token rendering; scroll-to-bottom FAB with unread count;
   text selection + copy; swipe-to-quote (quote block in composer); markdown
   tables; code blocks with monospace, syntax tint, copy button; tap-to-zoom
   images.
2. **Last-answer actions visible** (not long-press-hidden): regenerate, copy,
   → vault, quote. Long-press keeps the full menu.
3. **Real attachments** (`ATTACH` returns, real): camera capture, gallery
   photo, file — via ActivityResult pickers → Gemini multimodal
   (`inline_data`, base64, mime). Photo Q&A must work end-to-end. This is a
   high-value, API-ready win.
4. **Composer polish**: multiline growth cap + inner scroll; send-state
   (idle/streaming/stop); jump-to-draft per thread; ghost-text only if backed
   by the real AIRouter side-prompt, else delete the heuristic (Law 1).
5. **Threads as spaces** (from North Star Slice 2): rename chat buckets into
   task/person/device spaces only AFTER 1–4 are done — metadata first, UI
   filter second. To the operator's question "робити як нейронка пропонувала?"
   — partially: the North Star threading idea survives, but it comes last in
   this phase, after the messenger fundamentals it presupposes.
6. **Facets presentation engine** (Codex Pillar 5): form-follows-content.
   Extend the existing typed-widget pipeline with ~10 new facets (briefing,
   comparison-table, stat-hero, citation-answer, aqi-gauge, checklist,
   digest, memory-echo, alert-card, trip-pack), a form-picker system-prompt
   contract (the model MUST classify answer intent and choose a facet;
   bubble = fallback only), progressive disclosure (collapsed essence line →
   expand), and the per-intent visual grammar defined once in `:core-design`.
7. **History V3** (Codex Pillar 6): index every stream item on write with
   the EXISTING `OnnxEmbeddingHelper` (core-ai — already real ONNX, already
   consumed by LifeLog digest); search UI ranked vector+keyword+recency with
   thread/date/facet filters; auto-digest of threads dormant >14 days;
   pinned messages; jump-to-date.

Acceptance: side-by-side with Telegram on the same device, the list scroll,
copy, quote, image, and code-block experiences are not visibly worse; an
information question returns a facet, not a text wall; "що ти казав про X
місяць тому" finds it semantically.

## Phase R3 — Visual Identity (kill the "гівняний вигляд")

**Progress (2026-07-03, branch companion-v2-phase-0):** R3.1 partial — fixed
two real Living-theme contrast bugs (`SurfaceTray` active-pill label was a
hardcoded ink that broke contrast on non-gold `StateAccent` colors; new
`Color.onAccentInk()` in `PhantomTheme.kt`; `AudioWaveformWidget` play/pause
tint + cover glow now read `theme.primary`). Audited ~15 other hardcoded-color
sites — diff/syntax/status colors and `QuickAskBubble`'s documented fixed-gold
chip are intentional, left alone. R3.4 done — `MotionTokens` (previously zero
consumers) now wired: `EmotionTone` springs dedupe onto it, `StreamNav` state-
accent/saturation transitions and `StreamScreen` header hide/reveal +
list-item entrance (`animateItem(fadeInSpec=...)`) all use it.
**Remaining, needs operator-in-loop:** R3.2 (deep design-refs pass), R3.3
(30-widget renderer polish, visual verification on device). Claude Design
import (project `aace64fc-3e8a-4fa2-a72f-9364dfe067c0`, file "Phantom
v3.dc.html") blocked on `/design-login`.

1. **Living theme rollout** (R0.4 palettes) across all surfaces — audit every
   hardcoded color; everything reads from `PhantomThemeSpec`.
2. **Design pass over `docs/design-refs/phantom-app2/`** — treat as
   inspiration floor, not ceiling (operator: "можна і набагато краще").
   Extract what's strong (glass, glow discipline, orb states), redo spacing/
   typography scale, delete visual noise. NOTE the gotcha: `phaseN.jsx` files
   are the design tool's iterations, NOT our phases; specs live in
   `live-pieces.jsx` + `chats/chat2.md`.
3. **Widget renderer polish (~30 renderers)** — REQUIRES OPERATOR IN THE LOOP
   (device drops off adb; appearance can't be verified unattended). Batch:
   render each widget type from a fixture prompt, screenshot, fix, repeat.
4. Motion: use existing `MotionTokens` for list item entrance, orb state
   transitions, header hide/reveal. Calm, 150–250ms, no bounce.

## Phase R4 — Spatial Intelligence (Map V3)

**Progress (2026-07-04, branch companion-v2-phase-0):** R4.1 spine DONE —
`SpatialLayerStore` shipped as the one personal+world layer table
(`core-data/spatial/`: `SpatialLayerEntity`/`Dao`/`SpatialLayerRepository`
implementing the pure `SpatialLayerSink` port; DB v17 + migration 16→17),
`map.show`/`map.focus`/`map.clear` tools (core-symbiote `MapLayerTools.kt`),
thread-scoped via `AppContainer.activeThreadRef` (published by StreamNav's
state collector — StreamViewModel untouched, Law 11). Full `MapScreen` now
renders every visible layer (markers/lines/regions, per-feature tint) under a
"від Фантома" chip row (toggle/dismiss/focus), a tapped layer-marker opens a
place-card (Law 1: real detail only), and `map.focus` flies the camera.
R4.2 shared-gaze DONE — `MapViewportContextProvider` + `[MAP_VIEW:…]` line
(centre/zoom/reverse-geocoded locality/visible POI/active layers) injected per
turn while the map is open (`AppContainer.mapViewport`, published by
`MapScreen.onViewportChanged`). **First wave of R4.4–R4.6 living-world tools
DONE (all real sources, self-expiring WORLD layers, Law 1):** `transit.nearby`
+ `transit.routes` (Overpass stops + route-relation polylines),
`world.cameras` (OSM surveillance + speed/enforcement), `world.aircraft`
(free ADS-B adsb.lol — live flying objects w/ heading+alt), `world.report`
(honest operator self-report of patrol/incident/hazard — NO fake police feed).
SystemPrompts teaches all of it. Tests: `MapLayerToolsTest` (7),
`SpatialLayerRepositoryTest`, `MapViewportContextProviderTest`,
`WorldToolsTest`. **Remaining:** R4.3 PlaceStore (home/work/parking, `place.*`
tools, offline-first search re-rank), R4.4 route-card facet + guidance-lite
("супутник"), R4.5 living-map style-follows-theme + RainViewer radar + POI
clustering + Spatial Brief v2, R4.6 GeofenceRegistry + `geo.watch`, R4.7 Trail.
Each new world layer is now ~one tool on the spine (the "hundreds" argument).

**Thesis.** The map stops being a screen the operator visits and becomes the
place where the dialogue and the world share one gaze. Google Maps answers
"як доїхати"; PHANTOM's map answers "що я знаю про твій простір" — it
remembers places that matter, carries the conversation's spatial objects as
living layers, watches locations on request, and keeps a private trace of
the operator's day that no server ever sees. Every item below is two-way:
what the model says lands on the map; what the operator sees on the map
lands in the model's context.

**Inventory (do not rebuild — extend).** Already real: `MapScreen` (1302 l.:
operator pin + compass bearing, follow mode, POI overlay via Overpass +
`PoiRepository` cache, Nominatim `SearchOverlay`, route `LineLayer`, 3D
extrusions, `SpatialIntelligenceBrief` + ask-chips, `OfflineRegionsManager`),
`GeoTool` (`geo.search/nearby/route`, widget-ready `map` objects), inline
`MapWidget` (markers/circles/polylines), `LocationTool` + ambient context,
`WeatherTool`, `ReverseGeocoder`, `TileProvider` catalogue (OpenFreeMap
default), `OfflineRegionsContextProvider` (context-provider precedent),
`MapActionRouter` in godmode. The old R4 checklist items 2–4 are ~half-built;
audit each against Law 1 before writing new code.

**Contracts this phase creates for later phases** (get these shapes right —
R7/R8 build on them, wrong shapes here cost two rewrites later):
- `PlaceStore` (core-data `place/`): the ONE personal-place table. R4 writes
  it manually (saved POIs, home/work, parking); R7 Atlas dwell-clustering
  becomes the second writer of the SAME store — never a parallel one.
- `GeofenceRegistry`: register/unregister watches; GeofencingClient when
  Play Services present, WorkManager ≥15 min polling fallback (OEM-kill
  law). Consumers: R4.6 location reminders now; R7 Atlas geofences and R8
  M-cards later.
- `leaveTime(etaSec, eventStartMs, marginMin)` pure function in
  core-symbiote — R8 card M1 ("виходь зараз") is this function + a trigger.
- `place-card` / `route-card` facets on the R2.6 grammar — R8 M2/M3 render
  through them.

### R4.1 Spatial spine — layers as objects (`r4.1-spatial-layers`)
- `SpatialLayerStore` (Room, core-data): a layer = {id, title, origin
  (phantom/operator/system), ownerThreadId, markers/polylines/regions,
  createdAt, optional ttl}. Persisted; survives process death.
- Tool family `map.show` / `map.focus` / `map.clear(layer)` (+ Gemini
  adapter, Law 4): the model pushes NAMED layers to the full `MapScreen`,
  grouped under "від Фантома", each chip-listed, individually toggleable and
  clearable. `map.show` also returns the widget-ready object so the inline
  `MapWidget` in the stream and the full map render the same truth.
- Layers belong to threads/spaces: opening the map from a thread shows that
  thread's layers first. "Знайди 3 кафе" → layer; next day the layer is
  still there, listed, deletable.
- `StreamViewModel` must not grow (Law 11): tool→layer plumbing lives in a
  new collaborator, not in the 3049-line god-file.

### R4.2 Shared gaze — the map is context (`r4.2-shared-gaze`)
- `MapViewportContextProvider` (pattern: `OfflineRegionsContextProvider`):
  while the map is open, each AI turn gets one line — camera center + zoom,
  reverse-geocoded viewport locality, count/kinds of visible POIs, active
  layers. "А що тут?" while panning Відень answers about Відень, not about
  the operator's GPS position.
- Long-press anywhere → pin + composer geo-chip: exact coords, address,
  3-POI summary — a structured attachment, not pasted text. The existing
  ask-chips keep working; this generalizes them.
- Tap a POI/marker → `place-card` facet in the stream: name, distance,
  Overpass tags it actually has (hours, phone — Law 1: absent tag = absent
  row), actions: route / save to Places / ask about it.

### R4.3 Places that matter — explicit spatial memory (`r4.3-places`)
- `PlaceStore` (contract above) + UI: save from place-card, long-press
  "запам'ятати місце", Settings rows for home/work. Personal-places layer
  (always-available, distinct glyph).
- **Parking**: "я припаркувався" (voice or map action) → `kind=parking` pin;
  "де машина" → instant map-card + walking route + distance. Auto-detect on
  vehicle→walk transition is R7 (M3) — same pin, zero migration.
- `place.save` / `place.query` tools (+ adapter): the model can recall "де
  мій дім" and save places the operator names in conversation.
- `SearchOverlay` re-ranked: personal places and recents first, Nominatim
  after — searching "дім" must not go to the internet.

### R4.4 Route intelligence + guidance lite (`r4.4-routes`)
- `route-card` facet: distance, ETA walk vs drive (two OSRM calls),
  leave-time line when a calendar event is the destination (via the
  `leaveTime` pure fn), elevation note if OSRM provides it, actions:
  start / show alternatives / save.
- **Guidance lite — "супутник", not a nav app**: start → follow mode with
  bearing-up camera, next-maneuver banner (OSRM `steps=true`), spoken turn
  cues through the existing TTS path (spoken=shown, Law 2), off-route ≥50 m
  → one re-query, arrival card. Demo-OSRM throttling → honest "маршрут
  недоступний" (Law 1) — degradation IS the acceptance path. No lane
  guidance, no traffic claims, no pretending.

### R4.5 The living map (`r4.5-living-map`)
- Map style follows the Living theme: extend `TileStyleUrls.resolve(isDark)`
  to resolve from the Living phase (Dawn/Day → light, Dusk/Night → dark) and
  read ALL Phantom overlay colors (markers, routes, pins, radar tint) from
  `PhantomThemeSpec` — at 23:00 the map is night, at 13:00 it is day,
  without touching Settings.
- **Precipitation radar layer** (toggle in the layer chips): RainViewer free
  composite raster tiles over the base map; add host to `HostAllowlist`;
  verify current ToS/attribution on-device before shipping. When
  `weather.forecast` says rain within 2h, the brief offers the toggle —
  data-driven, not always-on.
- POI clustering (`GeoJsonSource` cluster mode) — the map at z12 in a city
  must not be a measles rash.
- **Spatial Brief v2** — real data only (Law 1): place name from
  `PlaceStore` if known ("ти вдома"), one-line real weather strip
  (WeatherTool), active layers, guidance state, GPS confidence. Delete any
  brief line that cannot cite its source.

### R4.6 Watched locations (`r4.6-geo-watch`)
- `GeofenceRegistry` (contract above) + `geo.watch` tool: "скажи коли буду
  біля аптеки" / "нагадай про хліб біля супермаркета" → registered watch →
  notification + stream card on trigger, with the reminder text. List +
  revoke in Settings; every trigger audited. Time-based reminders stay in
  R5 (`phone.alarm`) — this is the WHERE half of `/remind`.

### R4.7 Trail — the private day-path (`r4.7-trail`, opt-in, flagged)
- Local-only Room table of the operator's path; in R4 collected only while
  map/follow/guidance is active (honest scope — passive T1 collection joins
  in R7's power-tier scheduler). Day-playback slider on the map, "де я був
  сьогодні" answered by a `trail.today` tool behind `ToolPermissionGate`.
- Privacy hard lines: never leaves the device, excluded from AI context
  unless the tool is explicitly invoked, retention slider (default 7 days),
  one-tap purge, visible in the R8 perception ledger once it exists.

**Session & model routing.** Three sessions: (1) r4.1+r4.2 — the
architecture-defining spine; execute with Fable 5 or Opus 4.8 (cross-cutting
StreamViewModel/tool-pipeline/MapScreen integration, contract shapes for
R7/R8). (2) r4.3+r4.4+r4.5 — Sonnet 5 against this spec. (3) r4.6+r4.7 —
Sonnet 5; escalate to Opus only if the WorkManager fallback path fights
back. Commit per sub-item with the tags above; build gate + on-device check
per Law 6.

**Master acceptance (operator scenario).** "Знайди аптеку поруч" → layer
with markers, place-card with real hours; "збережи цю, це моя" → in Places,
findable by "аптека" offline in search; "веди" → bearing-up guidance with
spoken turns, honest failure if OSRM throttles; при цьому о 22:30 мапа
нічна сама по собі; "скажи коли буду біля дому" спрацьовує після
перезапуску телефона (WorkManager law); "де машина" після "я припаркувався"
— миттєва картка з пішим маршрутом; and every one of these visible as a
stream item, never voice-only (Law 2).

## Phase R5 — Device Hands (phone commands)

**Progress (2026-07-04, branch companion-v2-phase-0): DONE (`r5-device-hands`).**
`PhoneTools.kt` (core-symbiote): `phone.alarm`/`phone.timer` (AlarmClock
intents + `EXTRA_SKIP_UI` + SET_ALARM manifest permission — set silently),
`phone.dial`/`phone.sms` (ACTION_DIAL/SENDTO — operator confirms in system
UI, never auto-send), `phone.calendar` (CalendarContract insert draft),
`phone.panel` (Settings.Panel wifi/internet/volume/nfc; BT = full settings
screen, honest). Flashlight/volume/brightness/DND stayed in the EXISTING
`settings` tool (Law 1: extend, don't duplicate) — volume gained relative
`delta` so «тихіше/гучніше» works without knowing the level. All flat-token
schemas (Gemini adapter needs no change), audit via toolDiary automatic,
SystemPrompts «ТЕЛЕФОННІ РУКИ» block teaches the whole family incl. the
launch(action=…) unlock flow for permission_required. `/remind` restored to
the palette → routed at phone.alarm/timer. TurnTrace narrates phone.* +
map/transit/world (verbs, names, icons). Tests: PhoneToolsTest (28).
On-device acceptance («постав будильник на 7», «ліхтарик», «тихіше»,
«нагадай о 18:00») pending operator check.

New tools, each behind `ToolPermissionGate`, each added to the Gemini adapter
(Law 4), each with an audit entry:

| Tool fn | Mechanism | Notes |
|---|---|---|
| `phone.alarm` / `phone.timer` | `AlarmClock.ACTION_SET_ALARM/TIMER` intents | no special permission |
| `phone.flashlight` | `CameraManager.setTorchMode` | |
| `phone.volume` | `AudioManager` streams | DND needs policy-access → honest unlock row in Settings |
| `phone.brightness` | needs `WRITE_SETTINGS` → capability unlock flow | |
| `phone.dial` / `phone.sms` | `ACTION_DIAL` / `ACTION_SENDTO` (user confirms in system UI) | never auto-send |
| `phone.calendar` | `CalendarContract` insert intent | |
| `phone.wifi/bt panel` | `Settings.Panel` intents | panels, not silent toggles |

Restore `/remind` to the palette via alarm/timer + existing
OfflineIntentQueue. Acceptance: "постав будильник на 7", "ліхтарик", "тихіше",
"нагадай о 18:00" all work by voice.

## Phase R6 — The Unattainable Layer (PHANTOM-level artifacts + proactivity)

This is the "щось недосяжне" the concept demands. Do LAST — it is only
credible on top of real senses and a solid chat.

### R6.1 PhantomCanvas — native generative UI (replaces throwaway HTML)
- A typed, versioned artifact spec (JSON → Compose node tree): layouts, text,
  charts (existing ChartWidget kinds), inputs, buttons whose actions bind to
  real Tools. Rendered NATIVELY in Compose — no WebView.
- **Multi-pass builder, not one prompt**: plan → skeleton → data-fill →
  self-critique → refine (2–3 model turns), with visible progress in the
  stream ("будую: план → каркас → дані → полірування"). Reuse
  `Supervisor`/`ParallelRunner` from `core-symbiote/agent` (already wired).
- Artifacts persist, are versioned, and are editable by follow-up prompts
  ("зроби графік ширшим" → spec patch, not regeneration from zero).
- Retire the ChartWidget legacy `{label,values,trend}` shape in the same
  effort (known tech debt).

### R6.2 Agent mode
- `/agent` returns to the palette: a task the model decomposes and executes
  over Tools with the trust contract (proposed action → permission → audit,
  undo where reversible) from North Star Autonomy V3.

### R6.3 Distributed self remainder
- Wire `distributedself.*` (orphaned Mesh brain) onto the live core-net
  transport; device handoff from Stream/Map. (The unified event bus and
  orphan PRUNE originally sketched here moved to R7 — WatchTower needs them
  earlier.)

## Phase R7 — Perception Grid & World Model (Codex Pillars 1–2)

1. **Unified `PhantomEvent` bus**: merge GodModeEventBus, AppActionTraceBus,
   PhantomErrorBus, IncomingCallBus, NotifImportantBus into one typed stream.
   In the SAME pass, PRUNE off-mission orphans in AppContainer (childhood,
   legacy, diplomacy… — delete, don't keep dead lazies; Law 3).
2. **New senses** (each a bus producer, each behind a capability unlock row):
   device vitals (battery/storage/thermal — wire ThermalAdvisor orphan;
   ConnectivityManager), UsageStats foreground-app + per-app anomalies,
   ApplicationExitInfo crash reports, CalendarContract read, Open-Meteo
   air-quality (extends R1.2 WeatherTool), activity classification from
   existing IMU.
3. **ContextFusion → ContextFrame**: one service folding the bus into the
   versioned world snapshot + diffs. Two consumers immediately: distilled
   per-turn block in AI system context; WatchTower stream (R8).
4. **Atlas**: dwell-clustering place learner (home/work auto-detected,
   operator naming), visit history, geofences. Feeds M-series cards and
   "де машина".
5. **Power-tier scheduler**: T0 passive / T1 WorkManager ≥15min (OEM-kill
   survival law) / T2 indicated sessions / T3 on-demand. Measured per-sense
   daily cost surfaced in Settings capability matrix.

Acceptance: ContextFrame inspectable in diagnostics; asking "де я і що
навколо?" answers from the frame with zero tool calls; battery cost of all
T0/T1 senses ≤ 3%/day measured.

## Phase R8 — WatchTower: Proactive Symbiosis (Codex Pillar 3)

1. **RuleCard engine**: declarative cards (trigger over ContextFrame diffs,
   cooldown, severity, facet, action, learnHook), pure-function unit tests
   over synthetic frames.
2. **Calm contract**: attention budget (default 6/day), quiet hours,
   severity ladder (silent-log → card → notification → voice), why-now +
   confidence + dismiss-with-reason on every insight; 3 dismissals → card
   auto-demotes to silent-log.
3. **Perception ledger**: daily "що я відчув / що сказав / що притримав"
   surface — every suppressed insight visible (Codex privacy contract §4).
4. **Starter Fifteen** cards: E1 E3 M1 M2 M3 M6 D1 D2 D9 H1 H3 H6 C2 R5 R6
   (see Codex catalog). Morning/Evening brief (R5/R6 cards) are composed
   Facets, exercising the R2.6 grammar.
5. **Routine compilation**: WatchTower-suggested repeated behaviors compile
   into TaskerEngine programs after explicit approval.
6. **Ghost mode**: one gesture suspends all perception, orb shows it;
   `/silent` returns to the palette wired to this (Law 1).

Acceptance (operator's own scenario): walking into a bad-air district
produces an aqi-gauge alert with why-now within minutes, honoring budget and
quiet hours, logged in the ledger, dismissible with a remembered reason.

## Phase R9 — People & Connected Phantoms (Codex Pillar 7)

⚠ OPERATOR IN THE LOOP REQUIRED — needs the new `:feature-people` Gradle
module (P6-class blast radius: settings.gradle + nav + DI). Never start
this phase unattended.

1. `:feature-people` module + people store (profile, trust tier, space).
2. Person pairing ceremony over existing core-net PairFlow (QR/claim-link),
   phantom-to-phantom channel on the Ed25519/X25519/AesGcm transport.
3. Tier-gated capabilities: T1 per-moment shares (walk-home live location,
   ETA) → T2 presence + delegated free/busy asks → T3 family geofence
   events + SOS target + shared Atlas places. All ledger-logged, revocable.
4. `/meet` real: free/busy exchange → slot proposal → book on accept.
5. `@` palette fills with actually-paired people only (Law 1).
6. Shared spaces (both phantoms in one thread) + delegated tasks (queued,
   attributed, audited).

Acceptance: two phones, two operators: pair via QR; walk-home share expires
on arrival; "спитай у фантома X чи вільний о 19" answers from free/busy
without exposing calendar details; unilateral revoke works; every exchange
visible in both ledgers.

## Execution order & session budgeting

**R0 → R1 → R2 → R3 → R4 → R5 → R7 → R8 → R6 → R9.** (IDs stable; R6 runs
after R8 because PhantomCanvas inherits the Facets node grammar and agent
mode is only credible on top of real perception; R9 last because it needs
the operator present and a second participant to verify.) One phase per
work session where possible; R1, R2, R7 may take two each. Commit per numbered sub-item with the phase tag
(e.g. `r1.2-weather-tool`). After each commit: module tests +
`:app:assembleDebug` + one real on-device check of that item's acceptance
line. If a public service (OSRM/Overpass) is down during verification, the
honest-degradation path IS the acceptance test.

## Out of scope (explicitly)

- New Gradle modules (`:feature-people` etc.) — highest build-blast-radius;
  only with the operator live (known P6 deferral).
- Wear/showcase modules.
- On-device model work (cloud Gemini is the wired brain; local is additive).
