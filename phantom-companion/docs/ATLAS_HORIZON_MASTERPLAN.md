# PROJECT ATLAS — HORIZON: the 1-year automotive masterplan

*Written 2026-07-16, branch `companion-lux`. This document is the carrier: future
build sessions cannot see the conversation that produced it, so every phase names the
existing code it stands on and carries its own rationale. It is a blueprint, not a diff —
no HORIZON feature below is built yet. The predecessor docs it extends:
`docs/ATLAS_MASTERPLAN.md` (FixFlux, RoadMatcher, Kinematics, route cache, map.zone),
the LEVIATHAN sprint (offline A*, EW trail+halo, hillshade, animated radar, map.analyze),
`docs/NAV_MASTERPLAN.md` / `NAV_OMEGA_MASTERPLAN.md`, `docs/EW_DRIFT_MATRIX.md`,
`docs/MAP_MASTERPLAN.md`.*

---

## 0. The doctrine every HORIZON feature inherits

Three laws, non-negotiable, learned the hard way on this codebase:

1. **Honesty (Law 1).** No invented data, ever. A missing barometer is *said*, not
   faked (see the A56 verdict below). A dead-reckoned fix draws a growing doubt circle,
   not a confident dot. An offline route says «приблизно». The moment ATLAS shows a
   number it does not have, it has become Waze walking the operator into a spoof.
2. **Zero-fail phasing.** Anything whose correctness is *feel* — camera motion, gesture
   physics, offline-extract sizing, voice cadence — is a **[DEVICE]** phase, tuned with
   the operator in the seat, never blind-built the night before. Unverified new code is
   the single biggest threat to a field test. Everything provable by unit test or pure
   math is **[BLIND-OK]** and may be built ahead. Every item below carries its tag.
3. **Net-negative LoC bias.** HORIZON grows capability, not surface. Prefer extending a
   living subsystem (RouteRisk, SpatialLayerSpec, GuidanceSession) over a parallel one.
   A feature that needs a new module earns it; most do not.

### 0.1 The A56 barometer verdict (settled, informs Vector I lighting + EW)
`SensorInventory` logs the device roll call at locator start. On the Samsung A56 the
barometer reports **ABSENT (hardware)** — Samsung ships pressure sensors on flagships
only, and no manifest permission gates `TYPE_PRESSURE`. HORIZON therefore assumes **no
barometric altitude in the car**: vertical context (bridge vs underpass, floor of a
parkade) comes from map topology (the DEM hillshade source, LEVIATHAN-3) and GNSS
altitude cross-examined by SpoofGate, never from a sensor the device lacks. `map.analyze`
already speaks this shortfall aloud.

---

## Vector I — Hyper-Visual: the car's face

The phone map is a tool held at arm's length; the car map is a *windshield*. The chrome
recedes, the world leads. Foundation already on the GPU: the Filament Crystal City
(`feature-stream/.../map/crystal/`), native fill-extrusions, the guidance chase camera
(`GuidanceCameraEffect`), FixFlux per-frame dot dynamics, the EW trail/halo.

### I.1 Kinematic camera director — **[DEVICE]**
A camera envelope driven by `KinematicsEngine.MotionProfile` + fused speed + upcoming
corridor curvature (from `RouteVariant.steps`):
- **Zoom** inversely tracks speed (highway pulls out for horizon, city zooms into the
  junction), **tilt** rises with speed for a forward-lean windshield read, **lead**
  offsets the camera target *ahead* of the dot along the corridor bearing so the operator
  sees where they are going, not where they are.
- Continuity is FixFlux's job — the director sets targets, the spring makes them fluid;
  the two must not fight (the deadband lesson from the follow-camera QA of 2026-07-12).
- Curvature pre-brake: the camera eases down-and-in ~2 s before a sharp maneuver, the way
  a driver's own gaze does. Pure math for the envelope [BLIND-OK]; the *feel* is [DEVICE].

### I.2 Lane-ribbon rendering — **[DEVICE]** (renderer) / **[BLIND-OK]** (geometry)
The active corridor rendered as a raised, glowing Filament ribbon with **per-lane**
resolution where OSM carries `lanes` / `turn:lanes`:
- Geometry builder (OSM tags → lane offsets → triangle strip) is pure and testable
  [BLIND-OK]. The Filament material, glow, and depth compositing against the Crystal City
  are [DEVICE].
- The active lane for the next maneuver glows brighter; unavailable lanes dim. This is the
  single most-requested car feature Google *does* have and Apple half-has — HORIZON's
  version is tactical (the safe lane, not just the legal one) and reads on the EW palette.
- Foundation: `CrystalCityOverlay` already reconstructs a perspective camera from
  MapLibre's and draws extruded meshes; the ribbon is another mesh in that scene, and
  NAV-3 already routes the selected polyline into it as a pulse.

### I.3 Environmental lighting — **[BLIND-OK]** (sun math) / **[DEVICE]** (look)
The map lit by the real world:
- **Sun position** from a local SunCalc implementation (pure astronomy, no API, no key)
  drives MapLibre `style.light` azimuth/polar and the Filament sun. Golden hour rakes the
  building faces; solar noon flattens shadows. All [BLIND-OK] math.
- **Night** = the basemap goes obsidian (already the dark style) and the operator dot
  casts a **headlight cone** along its bearing — a soft additive gradient, wider at speed.
- **Weather** from the LEVIATHAN-4 radar reel: active precipitation over the viewport
  desaturates and darkens the palette, wets the road sheen (specular bump on the road
  lines). The data is already fetched; this consumes it for mood.

### I.4 Tactical HUD strata — **[DEVICE]** (composition)
A car-first chrome that composites the intelligence layers into one windshield read:
EW trail + trust halo (LEVIATHAN-2), threat corridors + deadline band (NAV-Ω1), the
maneuver banner + lane ribbon, `map.zone` washes. The phone's touch chrome (tool orb,
pills) collapses to a minimal glance-and-voice surface; the car head
(`CarNavigationScreen`, currently CATEGORY_POI) is promoted toward NAVIGATION templates as
`:core-nav` matures. The composition — what shows when, how it layers — is pure [DEVICE].

---

## Vector II — Next-Gen Routing: beyond ETA and traffic

Foundation: `RoutePlanner` (cache + variants + intel), `RouteRisk` / `RouteIntel`
(corridor scoring), `LocalRouter` (offline A*), `ThreatWatch`, `GuidanceSession`, the
BlackBox + EwTrail history.

### II.1 EW-aware routing — «маршрут чистого неба» — **[BLIND-OK]** (scorer)
The feature no tech giant has, because no tech giant's map assumes the sky is hostile:
- Every drive's `EwTrail` + `BlackBox` records where trust degraded — a **per-tile GNSS
  integrity prior** accumulates on-device (the `poi_gazetteer` pattern: accumulate, never
  TTL-evict). Tiles where the operator (or, later, the swarm) has seen jamming carry a
  penalty.
- `RouteRisk` gains an EW factor; the planner can offer a **clean-sky variant** that
  trades minutes for GNSS integrity — routes *around* the jammer's shadow, so the operator
  is never navigated into the exact zone where navigation fails. The scoring is pure and
  testable [BLIND-OK]; it needs real drive data to *populate*, which is why Q1 runs a
  passive collection phase before Q2 exposes the variant.

### II.2 Kinematic momentum routing — **[BLIND-OK]**
`KinematicsEngine`'s verdict biases variant selection: a TRAIN classification suppresses
car reroutes entirely (the operator is not driving, so «зверніть праворуч» is noise); DRIVE
at highway speed weights *against* variants with U-turns or immediate exits the momentum
can't take. Pure scoring layer on existing variants.

### II.3 Predictive hazard avoidance — **[BLIND-OK]**
Turn the reactive warnings into pre-drive scoring: `ThreatWatch` (oblast air-raid
crossings), hazard marks (`map.zone` / `pushHazardMark`), and known threat corridors score
*candidate routes before departure*, not just warn en route. «Цей маршрут перетинає активну
тривогу — є альтернатива на 4 хв довша» at plan time, from data already on the device.

### II.4 Swarm intelligence — **[DESIGN-ONLY, Q4]**
`AmbientMeshManager` (core-sensor) is the seed. Define, do not build: a signed, TTL'd
**trust-mesh message** — one symbiote's hazard/jamming/trust observation, broadcast to
nearby symbiotes over the existing BLE/multicast mesh, verified (no unsigned hazard
injection — the abuse surface is obvious and the reason this is design-only until there is
a second device to test consensus against). Decentralized, no server, no account. The
endgame of II.1: the clean-sky prior becomes collective.

### II.5 Offline mesh maturation — **[DEVICE]** (extracts)
`LocalRouter` today routes over whatever road tiles the matcher cached. HORIZON persists
those into **district graphs** (accumulate, survive process death) and, at the far end,
NAV-Ω4's real offline extracts. Extract sizing is [DEVICE] (never guess an extract's
footprint blind). The air-raid-plus-dead-network route already works over cached tiles
(LEVIATHAN-1); this makes it durable across sessions.

---

## Vector III — Symbiotic Integration: PHANTOM as co-driver

Foundation: `map.analyze` (tactical brief from real instruments), `map.zone` (tactical
layers), the SpatialLayerSpec push pipeline, the full-duplex voice engine, IntentChipEngine.

### III.1 PHANTOM as co-driver — **[BLIND-OK]** (cadence) / **[DEVICE]** (voice feel)
During guidance, `map.analyze` runs on a **cadence** (not every fix — event-driven: trust
flip, alert crossing, deadline band, kinematic state change) and PHANTOM speaks *unprompted*
tactical notes through the existing voice pipeline: «попереду тривала тривога», «небо
почало глушитися — веду обережно», «за 300 м зручний з'їзд до пального». The trigger logic
is [BLIND-OK]; how often he should speak before he becomes annoying is [DEVICE] (the
verbal-transparency invariant says he narrates on purpose — but a co-driver who never shuts
up is worse than a silent map).

### III.2 Generative tactical overlays — **[BLIND-OK]**
The model already pushes `SpatialLayerSpec`. Add **parametric generators** PHANTOM can
invoke: a **time-to-shelter isochrone** (LocalRouter one-to-many over the cached graph →
concentric reachability bands), a **danger gradient field** (distance-weighted wash from
hazard marks), a **radio-silence zone** (`map.zone` corridor that also arms a DND geofence).
Each is pure geometry over existing data.

### III.3 Spatial queries as language — **[BLIND-OK]**
«що по цій вулиці далі», «де найближче пальне по дорозі», «що це за будівля» — resolved
against `RoadGraph` + the `poi_gazetteer` + `route.along` **without an LLM round-trip where
the data is local** (the composer-as-spatial-search carryover from ATLAS §6). The map
becomes a queryable surface, not a picture.

---

## Vector IV — Signage & Elements

Foundation: `POIOverlay` (SDF-capable MapLibre symbol layers), `KinematicsEngine`,
GuidanceSession overspeed logic, the Silent-Luxury design language, `ChatTints` fixed-hue
exception (sanctioned non-theme palette for coded meaning).

### IV.1 Speed-adaptive POI density — **[BLIND-OK]**
`KinematicsEngine.MotionState` → a POI density budget: FLIGHT shows only cities, DRIVE
shows exits / fuel / shelters (the things a moving vehicle can act on), WALK shows doors and
entrances, STILL shows everything. The overlay already clusters; this makes the budget a
function of how the operator moves. Pure policy over `showNearby`.

### IV.2 Ukrainian tactical signage set — **[BLIND-OK]** (glyphs) / **[DEVICE]** (density feel)
A drawn glyph family — shelter, pharmacy, water, fuel, checkpoint — in the Silent-Luxury
language, rendered as **SDF icons** (MapLibre native, scale-crisp) that replace the generic
POI dots at drive zoom. Sanctioned fixed-hue coding (the `ChatTints` precedent). The glyphs
are designable/testable [BLIND-OK]; when they show vs the base style's own icons is [DEVICE].

### IV.3 Live sign intelligence — **[BLIND-OK]**
OSRM `maxspeed` annotations (already fetched in `RouteVariant.speedsMps` / RouteIntel) vs
the fused speed: the overspeed nudge (GuidanceSession) grows a **visual HUD speed sign** —
the posted limit rendered as a road sign that reddens when exceeded. Data is already on the
device; this renders it.

---

## Quarter map

Each quarter closes with something the operator can *drive*, and each item carries its tag.

- **Q1 — Foundations & data.** Kinematic camera director v1 [DEVICE]. Signage v1: speed-
  adaptive density + tactical glyph set [BLIND-OK/DEVICE]. **Passive EW-prior collection**
  [BLIND-OK] — start accumulating the per-tile integrity map now, so Q2's clean-sky router
  has data to route on. Live speed sign [BLIND-OK].
- **Q2 — Intelligence surfaces.** Lane-ribbon rendering [DEVICE]. Clean-sky routing v1
  [BLIND-OK scorer, DEVICE exposure]. Momentum + predictive-hazard scoring [BLIND-OK].
  Co-driver cadence [BLIND-OK trigger, DEVICE voice].
- **Q3 — The world reacts.** Environmental lighting: sun math + night headlight + weather
  palette [BLIND-OK math, DEVICE look]. Generative overlays: isochrones + gradient fields
  [BLIND-OK]. Offline district graphs [DEVICE extracts].
- **Q4 — Beyond the giants.** Swarm trust-mesh design + first two-device consensus test
  [DESIGN → DEVICE]. Tactical HUD strata composition [DEVICE]. The integration pass — every
  vector lit at once in the car — that is the demo that makes Google Maps look like a
  web page from 2015.

---

## Annex — Drive-day QA (tomorrow's vehicle session)

The concrete list for the first in-car test. Order: sensor truth first, then the
uncommitted work, then HORIZON reconnaissance.

1. **Barometer verdict (Mandate 1).** `adb logcat -s PhantomSensors` at launch → confirm
   `PRESSURE (barometer): ABSENT (hardware)` with the full sensor list beneath it as proof;
   EW HUD shows `baro none (no sensor)`; «проаналізуй обстановку» speaks the missing-baro
   line. This closes the "barometer inactive" report as hardware, permanently.
2. **ATLAS round-1 fixes** (2026-07-16, still needing device): compass arrow tracks
   physical rotation when standing, hands to travel bearing when moving; `map.zone` draws a
   *visible* red wash + dashed border + label; taps land cleanly everywhere with alerts on
   and off (the opacity-0 oblast touch-hijack fix).
3. **LEVIATHAN on-road** (built, never device-tested): EW trail colors through a real
   underpass (should redden to inertial), trust halo growth in a GNSS-poor stretch, offline
   shelter route in **airplane mode** (expect a degraded/offline card), radar reel animates,
   `map.analyze` while driving.
4. **Kinematics at speed:** DRIVE verdict on the open road, the TRAIN-vs-DRIVE honesty cap,
   whether a highway cruise ever misreads as TRAIN.
5. **FixFlux at 90 km/h:** does the 1.6 s bridge cap ever show as the dot lagging the true
   position between fixes? (If so, the cap scales with speed — a Q1 tweak, noted here.)
6. **Camera baseline:** the *current* guidance chase camera at speed — the reference the
   Q1 kinematic director must beat.

*Next code session picks up from Q1 [BLIND-OK] items — camera-director envelope math,
signage geometry, EW-prior accumulation, live speed sign — none of which need the car to
build, all of which need it to tune.*
