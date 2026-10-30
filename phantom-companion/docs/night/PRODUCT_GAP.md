# PHANTOM Companion — Product Gap Audit

Audit date 2026-07-26. Read-only pass over `app`, `core-*`, `feature-*` main sources.
`.claude/worktrees/**` excluded. No build was run — all claims are static-analysis
backed with `file:line` evidence.

Scale: 1390 Kotlin files, ~952 in `src/main`. Largest modules: `feature-stream` 79.5k LOC,
`core-symbiote` 23.9k, `app` 18.9k, `core-data` 12.5k, `core-sensor` 11k.

---

## 0. Headline findings (the five things that decide shippability)

| # | Finding | Evidence |
|---|---|---|
| 1 | **Routing and geocoding are dead in a release build.** `GeoEndpointsConfig.DISABLED` is the release default; every OSRM/Nominatim call returns null unless the operator hand-types two URLs in Map Settings. | `app/.../data/MapPreferences.kt:143-149`, `core-net/.../map/GeoEndpoints.kt:52-59`, `app/.../data/AppContainer.kt:448` |
| 2 | **The system prompt is ~50 000 chars (~25k tokens) per turn**, and the tool catalogue is sent **twice** — once as text `[TOOLS:…]`, once as 70 native Gemini `functionDeclarations`. | `core-ai/.../SystemPrompts.kt:31-161` (33 418 chars), 67 tool descriptions = 16 112 chars, `feature-stream/.../StreamViewModel.kt:1911-1917,3477-3485` |
| 3 | **The local model cannot run the product.** MediaPipe engine is capped at `setMaxTokens(1024)` total — the system prompt alone is ~25× that. Offline = a 0.5B Qwen that never sees its instructions. | `core-ai/.../route/LocalLlmRoute.kt:160`, `ModelManager.kt:88-97` |
| 4 | **Air-raid alerts only exist while the map screen is open.** `AirAlertClient`/`SirenClient` are constructed inside `MapViewModel`; there is no service, no worker, no notification channel for alerts. | `feature-stream/.../ui/map/MapViewModel.kt:238-239`, `MapLiveSession.kt:34-36`; manifest has 4 services, none of them alert-related (`app/src/main/AndroidManifest.xml:141-202`) |
| 5 | **~3 065 LOC of "symbiote philosophy" code is instantiated in `AppContainer` and never reaches a UI or a tool** — plus 1 429 LOC with zero references anywhere. | §3 below |

---

## 1. Capability inventory — 70 registered tools

Registry: `app/src/main/java/local/phantom/companion/data/AppContainer.kt:2998-3383`
(`ToolRegistry(tools, constitutionRegistry)` at `:3379`). Every tool class under
`core-symbiote/.../core/tool/impl/` **is** registered — there are no orphan tool classes.
Admission is constitution-gated (`core/tool/ToolRegistry.kt:31-46`); a failing
`FeatureManifest` is silently dropped, which is a real (untested-in-prod) hole: a tool can
vanish from the model's world with no log.

### SOLID — 47 tools, real dependency, real side effect

Store-backed (SQLCipher, offline, verified end-to-end):
`calendar.add/list/remove`, `task.add/list/done`, `reminder.add/list`, `place.save/list`
— `CalendarTools.kt:55,104,133`, `TaskTools.kt:36,74,104`, `ReminderTools.kt:68,110`,
`PlaceTools.kt:37,82`; bound to real stores at `AppContainer.kt:3368-3377`.
`reminder.add` is the only one with a full delivery chain: `ReminderAlarmSync` mirrors the
table into `AlarmManager`, `ReminderReceiver` re-arms recurrences
(`app/.../service/ReminderAlarmSync.kt:17-30`, `ReminderAlarms.kt:49-58`,
`ReminderReceiver.kt:26-30`, armed from both `PhantomApp.kt:76` and `BootReceiver.kt:19`).

Device hands: `phone.alarm/timer/dial/sms/calendar/panel` (real `Intent` launch,
`PhoneTools.kt:108-560`, launcher at `AppContainer.kt:3001`), `settings`
(`SettingsTool.kt:39` + `applicationContext`), `notification`, `media`, `launch`,
`apps.list`, `accessibility` (awaited dispatch with an honest 5-state outcome —
`AppContainer.kt:3036-3077`), `screen.read` (real a11y tree render, `:3078-3085`),
`device.wait`.

World/net: `weather.now/forecast` (Open-Meteo), `web.search` (DDG Lite,
`WebSearchTool.kt:153`), `research.deep` (decompose → multi-search → page read →
synthesize, `DeepResearchTool.kt:55-296`), `geo.search/nearby` (Nominatim + Overpass
`GeoTool.kt:353`), `transit.nearby/routes`, `world.cameras`, `world.aircraft`
(ADS-B `WorldSensorTools.kt:233`), `place.info`, `http`, `file` (sandboxed to
`filesDir/phantom/agent`).

Map/nav (push named, TTL'd layers onto the live map through `SpatialLayerRepository`):
`map.show/focus/clear/zone/point/route/nearby/analyze`, `shelter.nearest`,
`route.compare`, `route.along`, `threat.brief`, `nav.start/stop/deadline`,
`world.report`, metro station layer. `map.analyze` reads genuine process state —
GNSS trust, motion, guidance progress, live layers, nearest shelter
(`AppContainer.kt:1308-1345`).

Local: `widget` (emits into `toolWidgetEmissions`), `mental_park`, `canvas.build/patch`
(extra model turns via `singleTurnComplete`, `AppContainer.kt:2878-2897`),
`mission.arm/status/pause/resume/stop` (bridged to the real `AutopilotService`,
`AppContainer.kt:2977-2985`).

### HALF-BUILT — 5 tools that work in dev and fail silently in the field

- **`geo.route`** — registered with `offlineRouteCalculator` left **null**
  (`AppContainer.kt:3264-3268`), even though the app owns a working offline
  `LocalRouter`/`RoadGraph` used by `EvacuationEngine` and `OsrmRoadRouter`
  (`app/.../data/OsrmRoadRouter.kt:17-20`, `feature-stream/.../map/EvacuationEngine.kt:61-70`).
  Offline, the model's routing verb dies while the app can still route.
- **`map.route`, `route.compare`, `map.point`, `transit.home`, `map.prefetch`** — all
  resolve through `GeoEndpoints.config`, which is `DISABLED` in release
  (`MapPreferences.kt:143-149`). They return honest failures, which is correct
  engineering and a broken product: on a store build the model can never route or geocode.
- **`shell`** — real `ProcessBuilder("sh","-c",…)` (`ShellTool.kt:78-90`), but on an
  unrooted Android that is the app sandbox: no `pm`, no `am`, no filesystem. The Termux
  RPC that would make it useful is still `TODO(Phase 1.D)` (`ShellTool.kt:27`).
- **`accessibility` / `screen.read`** — correct code, but both no-op unless the operator
  has manually enabled the Accessibility service. Nothing in the prompt or UI drives that
  enrolment; the tool just reports `service-unbound`.
- **`mission.*` / autopilot** — a genuinely working expectimax 2048 pilot
  (`feature-godmode/gamesense` 959 LOC + `mission` 1 376 LOC). Real, and product dead
  weight for a war-zone companion.

### FAKE — 0

No tool returns canned text. Every `execute` reaches a real dependency. The quality
problem here is not fakery — it is **surface area**: 70 tools, ~16k chars of descriptions,
handed to a model that must choose among them from a flat one-line list
(`ToolPromptInjector.kt:26-32`).

---

## 2. Data systems

Room `version = 20` (`core-data/.../PhantomDatabase.kt:73`), 21 entities, 17 migrations
`3→20` chained at `:146-151`, **with `.fallbackToDestructiveMigration()` still installed at
`:162`.** That is a silent total-data-loss path on any migration mismatch, including the
encrypted vault and the whole chat history. Migration `19_20` (`:622-666`) is byte-copied
from generated `20.json` — that discipline is right; the destructive fallback undoes it.

| Store | DAO | Entity | Store/Repo | UI surface | Tool | Verdict |
|---|---|---|---|---|---|---|
| calendar | ✓ | ✓ | `CalendarStore` | `AgendaWidget` (chat card only) | 3 | **UI half-orphan** — the `День`/Agenda screen does **not** read it (see below) |
| tasks | ✓ | ✓ | `TaskStore` | `TaskListWidget` + `TaskListMath` | 3 | complete |
| reminders | ✓ | ✓ | `ReminderStore` | `ReminderWidget` | 2 | complete + alarms |
| places (saved) | ✓ | ✓ | `SavedPlaceStore` | `PlacesWidget` | 2 | complete, but **duplicated** by vault `map-poi` rows |
| memory (facts) | ✓ | ✓ | `MemoryFactStore` + `MemoryConsolidator` | **none** | **none** | **orphan data** — write path `AppContainer.kt:1849-1851`, read path only `StreamVmFactory.kt:137`. No card, no tool, no "what do you remember about me". |
| vault | ✓ | ✓ | `VaultStore` | `VaultScreen`/`VaultViewModel` | **none** | **UI without a tool**; and `value` is stored **plaintext** inside the SQLCipher DB (`VaultEntity.kt:13-21`), `locked` defaults to `false` and is documented as advisory (`VaultStore.kt:18-28`) |
| lifelog | ✓ | ✓ | `LifeLogArchive` | none (written by godmode + StreamItem) | none | **write-only** — 3 producers, 0 readers with a screen |
| diary (decisions) | ✓ | ✓ | `DiaryStore` | `DiaryScreen` | none | UI only, no tool |
| poi / gazetteer / FTS | ✓ | ✓ | `PoiRepository` | map | `map.nearby`, `shelter.nearest` | complete, offline-capable |
| spatial layers | ✓ | ✓ | `SpatialLayerRepository` | map | 8 map tools | complete |
| calls | ✓ | ✓ | `CallLogRepository` | `HandsAutomationScreen` | none | UI only |
| notifications digest | ✓ | ✓ | `NotificationDigestRepository` | worker | none | background only |
| patterns | ✓ | ✓ | `PatternRepository` | none | none | godmode-internal |
| pending intents | ✓ | ✓ | `OfflineIntentQueue` | none | none | infra, fine |
| profile | ✓ | ✓ | `ProfileStore` | onboarding | none | fine |
| transit (GTFS/RAPTOR) | — | — | `TransitRepository` | map | `transit.*` | partly orphaned: `GtfsStaticParser.kt` (253 LOC) unreachable from any entry point |
| evac | — | — | `ShelterRepository` | map | `shelter.nearest` | complete |
| errors | — | — | `PhantomErrorBus` | diagnostics | none | fine |

**Orphaned UI:** `feature-stream/.../ui/agenda/DayScreen.kt:78-86` takes
`weather`, `alerts`, `onRapidPrompt` — and nothing calendar-shaped. Its own docstring
(`:40-62`) still says *"the phone's calendar is NOT connected"*. The local calendar
landed in migration 19→20 and the Agenda screen was never rewired to it.

**Duplicate model:** `TransitHomeTool` resolves "home" by string-parsing vault entries
with `metadata == "map-poi"` (`AppContainer.kt:3304-3327`) — `entity.value.split(" · ")`
— while `SavedPlaceStore` now stores exactly that with typed `lat`/`lon`/`category`.
Two homes, one of them parsed out of a display string.

---

## 3. Dead weight — ranked deletion list

Method: word-index over all 1390 `.kt` files, then per-symbol reference count excluding
the declaring file; plus transitive reachability from manifest components + `app/`.
Reachable-but-never-surfaced is reported separately from unreferenced.

**Total removable: ~5 000 LOC of main source + ~1 900 LOC of tests for the same packages.**

### Tier 1 — zero references anywhere (1 429 LOC, `core-symbiote`)

44 files. No production caller, no test caller, no `AppContainer` mention. Ranked:

```
 86  core/embodiment/PostureMetrics.kt      76  core/embodiment/HapticPattern.kt
 66  core/swarm/TaskGraph.kt                66  core/memorypalace/FsrsRehearsal.kt
 64  core/memorypalace/DreamCapture.kt      58  core/bioloop/BioloopManifest.kt
 47  core/negotiate/ProofClaim.kt           43  core/negotiate/NegotiationChannel.kt
 43  core/diplomacy/ApologyComposer.kt      42  core/predict/PredictManifest.kt
 41  core/tempo/TemporalSelfViewer.kt       40  core/offload/OffloadManifest.kt
 40  core/memorypalace/TimeMachine.kt       40  core/diplomacy/DiplomacyManifest.kt
 39  core/diplomacy/VoiceTone.kt            39  core/create/CreateManifest.kt
 32  core/tempo/TempoManifest.kt            31  core/bioloop/SleepStage.kt
 30  core/negotiate/Escrow.kt               30  core/embodiment/EmbodimentManifest.kt
 30  core/distributedself/SubPersonality.kt 29  core/extensionsenses/EnvSensors.kt
 … 22 more files ≤ 26 LOC each (all `*Manifest.kt`, `SensorKind`, `MagneticNorthFeel`,
   `Reconciler`, `PhantomDetection`, `PreClinicalAlertRouter`, `Provenance`, `Author`…)
```
Evidence per file: `grep -rlw '<ClassName>' --include='*.kt' app core-* feature-*` returns
only the file itself.

### Tier 2 — instantiated in `AppContainer` only, never surfaced (1 636 LOC, 36 files)

These are constructed, held in `by lazy` fields, and read by nothing. They cost startup,
memory and — worse — the illusion of features.

```
 83  core/offload/WorkingMemoryRing.kt        81  core/create/StyleFork.kt
 73  core/create/TwinCanvasState.kt           71  core/create/ConversationTree.kt
 69  core/diplomacy/DeescalationDetector.kt   62  core/bioloop/HrvCollector.kt
 58  core/predict/ShimmerEngine.kt            58  core/distributedself/SensorChannel.kt
 57  core/tempo/RollingBuffer.kt              55  core/swarm/DistributedJobScheduler.kt
 53  core/predict/RehearsalAgent.kt           53  core/embodiment/ProprioceptiveTicker.kt
 52  core/healthmesh/MedicationTracker.kt     50  core/tempo/PaceModulator.kt
 49  core/embodiment/PostureMonitor.kt        46  core/offload/WillpowerProtocol.kt
 45  core/healthmesh/BaselineModel.kt         43  core/predict/DoubtAmplifier.kt
 43  core/create/CoAuthorshipLog.kt           42  core/memorypalace/ForgetCeremony.kt
 42  core/embodiment/AndroidHapticEmitter.kt  42  core/distributedself/IdentityCRDT.kt
 41  core/healthmesh/AnomalyDetector.kt       39  core/predict/ConvictionScheduler.kt
 … 12 more ≤ 35 LOC
```

Only 14 files (719 LOC) across those twelve packages are genuinely live —
`ForesightEngine`, `MoodArc`, `MentalPark`, `SpatialMemoryStore`,
`HardwareTelemetryEngine`, `StressDetector`, `CognitiveLoadEstimator`,
`EmotionFusion`, `BreathDetector`, `Trust`, `Pace`, `Settlement`, `Conviction`,
`DistributedStateFabric`. **Keep those 14, delete the other 80 files.**

Their tests: `core-symbiote/src/test/**/{bioloop,diplomacy,distributedself,embodiment,
extensionsenses,healthmesh,memorypalace,negotiate,offload,swarm,tempo,create}` = 1 893 LOC
that will go with them.

### Tier 3 — unreachable from any entry point (2 092 LOC outside symbiote)

Computed by walking file→file reference edges from manifest components and `app/`:

```
459  core-mobility/.../ModeSegmenter.kt        352  core-mobility/.../FlightDetector.kt
349  core-net/.../env/FirmsClient.kt           334  core-net/.../transit/TransitlandClient.kt
274  core-indoor/.../align/GeneticAligner.kt   271  core-voice/.../route/NavVoiceLines.kt
263  core-indoor/.../align/AlignmentProblem.kt 253  core-data/.../transit/gtfs/GtfsStaticParser.kt
193  core-design/.../FilamentOrbView.kt        189  core-geo/.../cameras/SpeedCameraLayer.kt
179  core-design/.../SymbiontEnginePanel.kt    174  core-indoor/.../rtt/RttSolver.kt
142  feature-ambient/.../PhantomWallpaperService.kt (not in manifest)
108  core-design/.../VitalsRow.kt               93  feature-stream/.../StreamScreenPreview.kt
 90  core-vision/.../MediaPipeVisionEngine.kt   86  core-net/.../crypto/AesGcm.kt
 81  core-net/.../env/MicroCsv.kt               80  core-indoor/.../IndoorTrace.kt
 62  feature-ambient/.../HearablesBridge.kt     54  feature-ambient/.../MarginGlowService.kt
 51  core-ai/.../audio/CognitivePipeline.kt     42  feature-stream/.../spaces/DeepAgentScreen.kt
 32  core-data/.../poi/search/Rrf.kt            29  core-ai/.../sdk/sandbox/PluginSandbox.kt
```
Note `core-indoor/align` (537 LOC) and `core-net/env` (430 LOC) have **no external
consumer at all** — whole packages, tests included.

### Tier 4 — `Module.kt` stubs, 14 files, 350 LOC

`core-{ai,net,data,design,voice,vision}`, `feature-{stream,godmode,ambient,onboarding}`,
plus 13 `core-symbiote/*/Module.kt`. Documentation objects nothing imports.
Zero-risk delete.

### Tier 5 — screens with one indirect doorway (~3 300 LOC, `feature-stream/ui/`)

The bottom island exposes exactly 5 slots — `Chat / Map / Voice / Vault / Settings`
(`app/.../ui/PhantomIsland.kt:99,447-450`). Everything else is reachable only through the
`Spaces` grid string-matching Ukrainian labels (`PhantomNavGraph.kt:411-420`):

```
602  ui/motion/    (MotionCatalogScreen — a design showcase)
435  ui/lens/      414  ui/whisper/     413  ui/lifegraph/
324  ui/agenda/    (DayScreen — the one that ignores CalendarStore)
289  ui/spaces/    229  ui/hands/       223  ui/creator/
192  ui/body/      175  ui/people/
```
These are not unreferenced — they are *unvisited*. Deleting them removes 28 nav
destinations' worth of maintenance and 3 300 LOC. Keep `hands/` only if the call-screening
data has a future.

---

## 4. The intelligence layer

**Models.** Cloud: `gemini-3.5-flash-lite` via
`generativelanguage.googleapis.com/v1beta/…:streamGenerateContent?alt=sse`
(`core-ai/.../route/GeminiCloudRoute.kt:632-641`), key from `AiKeyStore`, output floor
2048 tokens. Local: MediaPipe `LlmInference` over whichever `.task` is on disk — Gemma 3
1B int4 if the operator imported it, otherwise auto-pulled Qwen 2.5 0.5B int8 / 546 MB
(`ModelManager.kt:80-97`, `LocalLlmRoute.kt:68-74`). Chat template is resolved from the
file family, not assumed — that part is genuinely well done (`LocalLlmRoute.kt:300-356`).
Embeddings: ONNX MiniLM (`OnnxEmbeddingHelper`, `WordPieceTokenizer`).

**Routing.** `RoutedAIRouter(local = localRetrying, cloud = hotSwap, strategyProvider =
{ RoutingStrategy.CloudFirst })` — `AppContainer.kt:307-311`. The strategy lambda is a
**constant**, despite `RoutedAIRouter`'s own KDoc promising runtime policy changes
(`RoutedAIRouter.kt:26-29`). There is no operator switch, no battery rule, no
"jamming → local" rule. Layering, outermost in:
`RoutedAIRouter → HotSwapRouter(8 s first-token deadline) → RetryingAIRouter(1/3/7 s) →
StreamResumer(resume-with-prefix) → GeminiCloudRoute → offlineIntentQueue + WorkManager
drain`. Local sits in two places at once (inside `HotSwap` and as the fallback candidate),
so a cloud outage can attempt the local model twice.

**Offline fallback path.** Cloud IOException → `StreamResumer` retries → exhausted →
`cloud.complete()` enqueues the intent to `OfflineIntentQueue` and emits
`Notice + Final[QUEUED]` (`AppContainer.kt:244-272`, `work/OfflineDrainScheduler.kt`).
If `HotSwap`'s 8 s deadline fires first, the local model takes the turn instead.

**Prompt engineering — the honest assessment.** `SystemPrompts.DEFAULT_PHANTOM`
(`SystemPrompts.kt:31-161`) is 33 418 characters. It is not padding: it is 30 widget
schemas, a form-follows-content classifier, an anti-loop rule, a `say`/`present` protocol,
a device-control loop doctrine, and a mandatory `⟦NEXT⟧` suffix. The craft is real —
several rules are scarred with the exact device date the bug was seen
(`:88` `<think>` tags, `:87` invented `id`s, `ToolCallParser.kt:41-43` a leading space in a
tool id). But the aggregate is unmanageable, and it is appended to, never edited.

### Top failure modes visible in the code

1. **Prompt budget.** 33 418 chars of persona + 16 112 chars of tool descriptions ≈ 25k
   tokens **every turn**, before history, before `[LOCATION]`/`[APPS]`/`[SCREEN]`/
   `[MAP_VIEW]` context blocks. On flash-lite that is latency and cost; on the local
   route it is simply impossible (`setMaxTokens(1024)`, `LocalLlmRoute.kt:160`) — the
   0.5B model is fed a prompt 25× its total window and will be truncated into nonsense.
   **Offline chat is, in practice, non-functional.**
2. **Double tool protocol.** `nativeTools` (70 `functionDeclarations`) is sent alongside
   the text `[TOOLS:…]` block on every call (`StreamViewModel.kt:1911-1917,3477-3485` →
   `GeminiCloudRoute.kt:478-495`). If the model answers with a *real* `functionCall`, the
   replay path cannot echo it back: Gemini 3 rejects a replayed `functionCall` without a
   `thought_signature` (documented at `GeminiCloudRoute.kt:420-437`, HTTP 400 on every
   tool turn). The workaround was to replay tool exchanges as **text** — which means the
   native declarations are pure prompt tax and a live 400-risk.
3. **Local tool-calling is wired to a format nothing emits.** `LocalLlmRoute`'s sniffer
   fires on `{"tool":` (`:210`), while the prompt mandates
   `{"type":"tool-call","tool":…}` (`SystemPrompts.kt:105`). The sniffer can never match;
   local tool calls fall through to text and are rescued only by `StreamViewModel`'s
   `ToolCallParser`. Dead branch pretending to be a feature.
4. **Role flattening.** Both `SYSTEM` and `TOOL` history turns are sent to Gemini as
   `"user"` (`GeminiCloudRoute.kt:407-419`), then adjacent same-role turns are merged
   (`:466-476`). A long tool chain collapses into one enormous user message — exactly the
   shape that makes a model re-emit an already-shown widget, which is why an explicit
   anti-loop rule had to be written (`SystemPrompts.kt:157`).
5. **Silent constitution drops.** `ToolRegistry` excludes a tool whose manifest fails
   audit and deliberately does not log (`ToolRegistry.kt:38-42`). A manifest typo removes
   a capability with no trace.
6. **Proactive engine is off.** `PROACTIVE_VOICE_ENABLED = false`
   (`AppContainer.kt:78`); `startProactiveVoiceSeat()` returns immediately (`:2318`).
   The engine itself is sound — salience floor, 20 s min gap, 8-deep anti-repetition,
   model-as-sole-judge, fail-safe-to-SILENT parsing (`ProactiveEngine.kt:33-52`,
   `ProactiveLlmReasoner.kt:52-71`) — but its `generate` seam is `RoutingStrategy.LocalOnly`
   (`AppContainer.kt:2330-2334`), i.e. it depends on the 1024-token local model that
   failure mode 1 already broke. Turning the flag on today produces silence or garbage.
7. **`.fallbackToDestructiveMigration()`** (`PhantomDatabase.kt:162`) — one schema slip
   wipes the vault, the chat history and the memory facts.

---

## 5. Gap list — ranked by user-visible value per engineering hour

### P0 — the product does not work without these

**G1. Air-raid alerts as a background service with notifications.** *(M)*
Today alerts render only inside `MapViewModel` while the map is on screen. For a war-zone
companion this is the single highest-value missing feature: an alert the operator does not
see is not an alert. Build on: `AirAlertClient`/`SirenClient`
(`feature-stream/.../map/LiveMapFeeds.kt:143`, `SirenAlerts.kt:153`), the bundled
`ua_region_index.json` + `ua_oblasts.geojson` for offline oblast resolution, and the
already-proven notification pattern in `ReminderAlarms.kt:66-93`. Needs: a foreground
service or 30-60 s `WorkManager` poll, a high-importance channel, oblast filtering against
the operator's fix, and an all-clear notification. `ThreatWatch.Cue.AlertZoneEntered`
(`core-nav/.../ThreatWatch.kt:41-44`) already speaks the right sentence — it just has no
data source outside a guidance session.

**G2. Ship a working geo stack by default.** *(S)*
`GeoEndpointsConfig.DISABLED` in release means no routing, no geocoding, and — per the
project's own zero-config law — no acceptable way to ask the operator for a URL. Options,
cheapest first: bundle a self-hosted relay URL as a release `buildConfigField` (hours);
or make `LocalRouter` + the offline gazetteer the release default and treat online as the
optional upgrade (days). Build on: `MapPreferences.kt:143-158`,
`core-sensor/.../nav/LocalRouter.kt`, `PoiGazetteer`.

**G3. A second, small system prompt for the local route.** *(S)*
Split `SystemPrompts` into `DEFAULT_PHANTOM` (cloud) and a ≤1 500-char `LOCAL_PHANTOM`
carrying persona + the 6 widgets that matter offline + 8-10 tools. Raise
`setMaxTokens` to the model's real window (Qwen 2.5 0.5B supports far more than 1024) and
route the prompt by `Route`. Touches: `SystemPrompts.kt`, `LocalLlmRoute.kt:160,300-356`,
`StreamViewModel.kt:3477`. This is what converts "offline mode exists" into "offline mode
answers".

**G4. Wire `geo.route` to the offline router.** *(S)*
`GeoRouteTool` already accepts `offlineRouteCalculator`; pass the same
`LocalRouter`/`RoadGraph` pair `EvacuationEngine` uses. One constructor argument at
`AppContainer.kt:3264`. Turns the model's routing verb from dead-when-offline into
degrade-gracefully.

### P1 — competitive parity

**G5. Tool catalogue tiering.** *(M)*
70 flat tools in one `[TOOLS:…]` line is worse than 15 well-chosen ones. Add a
`Tool.tier`/`Tool.contextRelevance` and render only the relevant subset per turn
(map open → map tools; chat → data-system tools; always → location/weather/search).
`ToolPromptInjector.render` already takes a `List<Tool>` — the filter is the whole change
(`ToolPromptInjector.kt:26`, `StreamVmFactory.kt:210-214`). Also drop `nativeTools`
entirely: it is unused prompt tax and a 400-risk (failure mode 2). Expected: ~10k tokens
and several hundred ms off every turn.

**G6. Connect the calendar data system to a screen.** *(S)*
`DayScreen` is the natural home and already exists; it just needs `CalendarStore.upcoming`
and `ReminderStore.pendingFlow` instead of nothing (`ui/agenda/DayScreen.kt:78-86`). Today
the only way to see your own calendar is to ask the model to draw a card. Also add a
calendar-event alarm path — reminders fire, events do not.

**G7. Memory that the operator can see and edit.** *(M)*
`MemoryFactEntity` + `MemoryConsolidator` + embedding recall all work
(`AppContainer.kt:1806-1851`, `StreamVmFactory.kt:137`) and have no UI and no tool. Add
`memory.list` / `memory.forget` tools and a card. "What do you know about me / forget
that" is table stakes for a personal AI and the substrate is already paid for.

**G8. Real vault encryption + a lock ceremony.** *(M)*
`VaultEntity.value` is plaintext inside the DB (`VaultEntity.kt:16`) and `locked` is
advisory-only (`VaultStore.kt:18-28`, `unlock()` at `:50`). Encrypt per entry with a
Keystore key gated on `AndroidBiometricGate`, make `locked` default `true`, and require
biometrics per session. For a companion carried into a war zone, "encrypted vault" must
survive device seizure, not just casual browsing.

**G9. Remove `.fallbackToDestructiveMigration()`.** *(S)*
`PhantomDatabase.kt:162`. Replace with a crash + operator-visible export path. One line;
prevents total data loss.

### P2 — leverage through subtraction

**G10. Delete Tiers 1-4 from §3.** *(M, ~5 000 main LOC + ~1 900 test LOC)*
Eighty `core-symbiote` files, twelve unreachable packages, fourteen `Module.kt` stubs. Not
cosmetic: they inflate `AppContainer` (3 589 lines, 9 of its own declarations orphaned),
slow every build, and make the codebase read as if features exist that do not.

**G11. Hide the unvisited screens.** *(S, ~3 300 LOC)*
Motion/Lens/Whisper/LifeGraph/Agenda-as-is/Spaces/Hands/Creator/Body/People. Keep the five
island slots. This aligns the code with the shipped nav model instead of maintaining
twenty-eight destinations for five.

**G12. Delete the 2048 autopilot.** *(S, ~2 300 LOC)*
`feature-godmode/{gamesense,mission}` + 5 registered `mission.*` tools. Impressive; wrong
product.

### P3 — after the above

**G13. Runtime routing policy.** *(S)* Replace the constant `{ RoutingStrategy.CloudFirst }`
(`AppContainer.kt:310`) with a real provider: operator toggle, battery level, and GNSS
trust (jam/spoof → `LocalOnly`). The plumbing exists; only the lambda is fake.

**G14. Turn the proactive engine on — after G3.** *(S)* One flag
(`AppContainer.kt:78`) plus the local-prompt fix. The engine, sink and salience gating are
finished code sitting behind a boolean.

**G15. PTT/voice offline verification.** *(M)* `VoskSttEngine`, `WhisperSttEngine`,
`HybridSttEngine`, Supertonic TTS and `YamnetAudioClassifier` all exist in `core-voice`
(5 364 LOC); `NavVoiceLines.kt` (271 LOC) is currently unreachable. Worth a device pass to
establish which STT path actually runs with no network, since PTT is one of the five
island slots.

---

## Appendix — reproducing the dead-code numbers

```bash
# word→file index
find app core-* feature-* wear -path "*/src/*" -name "*.kt" | grep -v /build/ > /tmp/allkt.txt
grep -oHP '[A-Za-z_][A-Za-z0-9_]*' $(cat /tmp/allkt.txt) | awk -F: '{print $2"\t"$1}' | sort -u > /tmp/wordfile.tsv
# a single symbol's external references
grep -rlw '<ClassName>' --include='*.kt' app core-* feature-* | grep -v /build/
```
Reachability was computed by building file→file edges (file A cites a top-level symbol
declared in file B) and flooding from `AndroidManifest.xml` components plus all of `app/`.
