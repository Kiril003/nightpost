# PHANTOM Companion — Architecture Refactor Plan

Audit date 2026-07-26 · branch `rebuild/mobile-day1` · read-only pass, no source modified, no Gradle run.
Scope: main tree only (`.claude/worktrees/**` excluded).

Baseline: 1390 Kotlin files, 212 252 LOC. `:feature-stream` alone is 262 files / 62 972 LOC (30 %).
~3 248 `@Test` across 20 modules. No detekt, no ktlint, no `lint {}` block anywhere.

Ordered by leverage. Steps 0–3 are the ones that unlock everything else; do them first and in order.

---

## 0. STATE SHAPE — 350 lines that gate every other item (highest leverage)

`feature-stream/.../domain/StreamUiState.kt` is small, has no dependencies, and is read by the
ViewModel, the screen, the composer, the persistence writer and 1017 tests. Every performance
defect in §3 and half the ViewModel split in §1 route through it. Fix it first; it is one file, one
engineer, ~90 minutes, and it unblocks parallel work on everything else.

### What is wrong

| Line | Defect |
|---|---|
| `StreamUiState.kt:202` | `val items by lazy(PUBLICATION)` — recomputed for **every new state instance**. On `ThreadId.MAIN` it runs `FacetMath.mergeNonVault(threads, itemMeta)` + `FacetMath.filter(...)` over the entire history. |
| `StreamUiState.kt:214` | `rawMainItems by lazy` — a second full merge of the same data. |
| `StreamUiState.kt:225` | `turnPhase by lazy` reads `items` → forces the merge even for callers that only want the phase enum. |
| `StreamUiState.kt:294` | `threadOf(itemId)` — linear scan across every thread × every item. |
| `StreamUiState.kt:298` | `withItemsIn` — `threads + (tid to transform(...))` rebuilds the whole map **and** the whole list. |
| `StreamUiState.kt:312` | `updateItem` = `threadOf` + `withItemsIn` + `items.map{}`. This is the per-token path. |
| `StreamUiState.kt:282` | `appendIfAbsent` — `threads.values.any { bucket.any { ... } }`, full scan; called from ~10 init collectors. |
| whole class | 37 `val` fields, of which `threads: Map<…>`, `itemMeta: Map<…>`, `knownTags: List<…>`, `pinnedIds: Set<…>`, `spaces: List<…>`, `drafts: Map<…>`, `pendingAttachments: List<…>` are Kotlin collection *interfaces* → Compose-unstable. Plus three `Lazy<T>` delegate backing fields, which are unconditionally unstable. Result: `StreamUiState` is an unstable parameter type. |

### Target shape

Split the one god-state into three, and make the timeline a keyed structure instead of a list-of-lists.

```
feature-stream/src/main/java/.../domain/
  StreamUiState.kt        → shrinks to composition root state only
  ConversationState.kt    → NEW: threads + itemMeta + pinnedIds + knownTags + derived views
  ComposerState.kt        → NEW: inputText, composerExpanded, composerQuote, pendingAttachments,
                                  drafts, capturing, partialTranscript, sending
  SessionState.kt         → NEW: brain, unconfigured, loading, spaces, activeThreadId,
                                  activeHandoff, handledHandoffIds, handoffAck*, toolRunning,
                                  intentChips, activeCanvasWidget, activeVisionSource
```

Rules for the new `ConversationState`:

1. **Index by id.** Add `private val threadOfId: Map<String, String>` maintained on append/remove.
   `threadOf` becomes O(1); `updateItem` and `appendIfAbsent` stop scanning.
2. **Persistent list per thread.** Replace `List<StreamItem>` with `kotlinx.collections.immutable.PersistentList`
   (or, if adding a dependency is off the table tonight, keep `List` but mark the holder
   `@Immutable` and add a `stability-config.conf`). `updateItem` then costs O(log n) instead of a
   full copy.
3. **Kill `by lazy` on the data class.** `items` / `rawMainItems` / `turnPhase` move to explicit
   memoised values computed once in the reducer that produced the state, stored as plain `val`s.
   A `by lazy` in a Compose-visible data class is both a stability bug and a hidden allocation.
4. **Streaming text out of the item list entirely.** Add `val streamingText: String?` +
   `val streamingItemId: String?` on `ConversationState`. `appendToken` writes only those two
   fields; the item list is untouched until `finalizeAssistant`. This alone removes the O(N)
   per-token allocation and makes the LazyColumn's `key` set stable during a stream.
5. Annotate all three with `@Immutable` and add `compose_compiler_config.conf` (below) so
   `ForesightEngine` and the flow types are declared stable.

### Compose stability config (create; referenced from every Compose module)

```
# compose_stability.conf  (repo root)
local.phantom.companion.feature.stream.domain.*
local.phantom.companion.core.predict.ForesightEngine
kotlin.collections.*
```
Wire with `composeCompiler { stabilityConfigurationFile = rootProject.file("compose_stability.conf") }`
in `:core-design`, `:feature-stream`, `:app`, `:feature-godmode`, `:feature-ambient`,
`:feature-onboarding`, `:feature-precision`.

### Order (each step compiles and passes tests on its own)

1. Add `threadOfId` index + O(1) `threadOf` / `appendIfAbsent`. No API change. Tests unchanged.
2. Replace the three `by lazy` with reducer-computed `val`s. Signature unchanged.
3. Add `streamingText`/`streamingItemId`; change `appendToken` (`StreamViewModel.kt:2052`) and
   `finalizeAssistant` (`:2081`) to use it; teach `StreamScreen` to render the tail bubble from it.
4. Add the stability config + `@Immutable`.
5. Only then split into the three state classes, with `StreamUiState` kept as a facade holding the
   three (`state.conversation.items`, `state.composer.inputText`, …) so the 1017 existing tests can
   be migrated mechanically rather than rewritten.

---

## 1. `StreamViewModel.kt` — 4177 lines, 59 constructor parameters

`feature-stream/src/main/java/local/phantom/companion/feature/stream/StreamViewModel.kt`

59 constructor parameters, **54 of them nullable-with-`= null` default**. That default is not an
architectural choice, it is a test-affordance leak: every new collaborator gets bolted on as another
optional null because the alternative is touching 1017 tests. The class has 21 flow collectors in a
single 635-line `init`, a 45-branch `dispatch`, and four multi-hundred-line async continuations.

### Responsibility map (measured)

| Lines | Responsibility | Extract to |
|---|---|---|
| 1–97 | package + 80 imports (many fully-qualified inline anyway) | — |
| 98–401 | 59-parameter constructor | `StreamDependencies` (see below) |
| 402–485 | fields: `scope`, `suspendedToolCalls`, `_state`, `_insightActions`, `_lockVaultPulse`, `inFlight`, reflection timers, `persistenceWriter` | stays |
| **486–1120** | `init` — 21 flow collectors: brain state, persistence mirror, thread meta, dormant digests, context/familiar/map events, `WidgetEventBus`, cold-launch restore, STT results + partials, proactive insights, nervous bus, error bus, proactive chats, notification-important, notification digest, autopilot notes, incoming calls, lock-vault, handoff, tasker suggestions, `AppActionTraceBus` | **`StreamIngestWiring`** |
| 1121–1458 | `dispatch` — 45 `StreamEvent` branches | **`StreamReducer` + 4 handlers** |
| 1459–1511 | `generateDormantDigests` | `ThreadDigestUseCase` |
| 1512–1554 | `handleAttachPicked` | `AttachmentController` |
| 1555–1579 | `regenerate` | `TurnController` |
| **1580–2025** | `submit` — slash-command parsing (`/debug`, `/search`, `/remind`, `/agent`), held-prompt bootstrap, quote folding, attachment widgets, 10 context-header providers, reflection trigger, system-prompt assembly, `aiRouter.complete` collect loop, error mapping | **`PromptAssembler` + `TurnController`** |
| 2026–2049 | Ukrainian label formatters (`formatReason`, `friendlySource`) | `StreamCopy.kt` |
| 2050–2080 | `appendToken`, `parseNextChips` | `TurnController` |
| **2081–2558** | `finalizeAssistant` — 478 lines: `⟦NEXT⟧` trailer parse, `<think>` recovery, `AssistantJsonExtractor` widget dispatch (~18 widget kinds inline), `AppActionParser`, `AgentCallParser` single + parallel, TTS text selection, `PredictiveIntentEngine` chips | **`AssistantTurnFinalizer` + `WidgetDispatcher`** |
| 2559–2625 | per-turn scratch (`turnToolIds`, `turnCanvasTitle`), `messageTextById`, `appendActionNotice`, `appendGroundingCitations` | `TurnController` |
| 2626–2775 | `handleDirectHand` (Hands Deck), `handleSpeakMessage`, `voiceIfEnabled` | **`VoiceController` (feature-side) + `HandsDeckController`** |
| 2776–2895 | `buildAiHistoryTurns`, `entityToHistoryTurn`, `domainItemToHistoryTurn`, `collectPinnedFacts`, `handleTogglePin`, `applyFacetToggle`, `parseTextRole` | **`HistoryBuilder`** (pure, JVM-testable) |
| 2896–2964 | `reportError`, `errorMessage`, `cancelInFlight` | `TurnController` |
| 2965–3079 | `handleDebugCommand` (114 lines of `/debug` subcommands), `refreshAvailability` | **`DebugCommands.kt`** — debug source set |
| 3080–3372 | tool-trace payload builders, `updateToolTraceWidget`, `dispatchNativeToolCall`, `toolExecutor`, `runToolCall` | **`ToolCallController`** |
| 3373–3568 | `continueAfterTool` (196 lines — a second full router round-trip) | `ToolCallController` |
| 3569–3798 | agent progress payload merge/terminal/finalize, `continueAfterAgent` | **`AgentCallController`** |
| 3799–3952 | `continueAfterParallelAgents` | `AgentCallController` |
| 3953–4064 | `handleHandoffEnvelope`, `closeIfActive`, `dispatchHandoffAck` | **`HandoffController`** |
| 4065–4134 | `performDigest` | `ThreadDigestUseCase` |
| 4135–4172 | top-level `nowBlock`, `formatLockVaultNotice`, `formatHandoffNotice`, `formatHandoffTime` | `StreamCopy.kt` |

### The seam: `StreamDependencies` + `StreamStateHost`

Two interfaces make the split possible without a DI framework.

```kotlin
// domain/StreamStateHost.kt — what every controller needs from the VM
internal interface StreamStateHost {
    val state: StateFlow<StreamUiState>
    fun update(block: (StreamUiState) -> StreamUiState)
    fun launch(block: suspend CoroutineScope.() -> Unit): Job
    fun notice(text: String, severity: StreamItem.Notice.Severity)
}
```

```kotlin
// StreamDependencies.kt — the 59 params, grouped, non-null, with Noop defaults
data class StreamDependencies(
    val ai: AiPorts,           // aiRouter, aiConfigured, brainState, wakeBrain, negotiator, embedder
    val voice: VoicePorts,     // voiceManager, ttsEngine, ttsEnabledProvider, ttsGate
    val persistence: PersistencePorts, // streamRepository, threadMeta, vaultStore, lifeLog, memoryRecall
    val context: ContextPorts, // 12 *ContextProvider fields — see below
    val tools: ToolPorts,      // toolRegistry, toolGate, toolDiary, toolWidgetFlow, toolPromptBlock, toolListProvider
    val agents: AgentPorts,    // agentSupervisor, parallelAgentRunner, agentCancellationRegistry, council, foresight
    val godmode: GodModePorts, // tasker, screenContext, selectedText, appActionDispatcher, notification/call flows
    val events: EventPorts,    // 9 inbound Flow<…> fields
)
```

`ContextPorts` deserves its own note: `locationProvider`, `locationAddressProvider`,
`installedAppsProvider`, `offlineRegionsProvider`, `spatialMemoryProvider`, `moodProvider`,
`geofenceProvider`, `routeHistoryProvider`, `mapViewportProvider`, `screenContextProvider`,
`selectedTextProvider`, `contextEngine` are all consumed at exactly one place —
`StreamViewModel.kt:1770–1860` inside `submit`, each wrapped in `runCatching{}.getOrNull()` and
formatted by a matching `*ContextFormatter`. That is a **list**, not twelve fields:

```kotlin
fun interface PromptContextBlock { suspend fun render(): String? }
class PromptAssembler(private val blocks: List<PromptContextBlock>) {
    suspend fun contextHeader(): String? =
        blocks.mapNotNull { runCatching { it.render() }.getOrNull() }
              .filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString("\n")
}
```
Twelve constructor params collapse to one, and adding a 13th context source stops being a
ViewModel edit — which is exactly the property you need with five engineers tonight.

### Extraction order (each step compiles independently)

1. **`StreamCopy.kt`** — move the four top-level formatters (4135–4172) + `formatReason` +
   `friendlySource`. Pure functions, zero risk, gives the ViewModel a −60-line warm-up and proves
   the test harness survives file moves.
2. **`HistoryBuilder.kt`** (2776–2895) — pure, takes `(threadId, fallbackItems, excludeIds)`.
   Testable on JVM with no ViewModel. −120 lines.
3. **`PromptContextBlock` + `PromptAssembler`** — 12 constructor params → 1. `submit` shrinks by
   ~90 lines. This is the step that makes the constructor tractable.
4. **`DebugCommands.kt`** (2965–3079) into `src/debug/` — 114 lines leave the release binary too.
5. **`StreamStateHost`** interface; `StreamViewModel` implements it. No behaviour change, no test
   change. This is the seam everything after this point plugs into.
6. **`ToolCallController`** (3080–3568, ~490 lines) — takes `StreamStateHost` + `ToolPorts`.
7. **`AgentCallController`** (3569–3952, ~384 lines) — same shape.
8. **`HandoffController`** (3953–4064) + **`ThreadDigestUseCase`** (1459–1511, 4065–4134).
9. **`AssistantTurnFinalizer` + `WidgetDispatcher`** (2081–2558). Biggest single win; do it after
   6–8 so the tool/agent branches inside it are already calls into controllers.
   `WidgetDispatcher` should be a `Map<String, WidgetHandler>` keyed on `WidgetTypes.*`, not the
   current `when` chain — that is what lets two engineers add widgets without a merge conflict.
10. **`StreamIngestWiring`** (486–1120) — one class per collector group
    (`ProactiveIngest`, `NotificationIngest`, `HandoffIngest`, `RestoreBootstrap`,
    `WidgetBusIngest`), each `fun install(host: StreamStateHost, scope: CoroutineScope)`.
    Note the two `if (ioScope == null)` guards at `:601` and `:1088` — those are production-only
    collectors gated by a *test* condition. Replace with an explicit
    `StreamDependencies.hotBusesEnabled: Boolean`.
11. **`StreamDependencies`** grouping of the remaining constructor params.
12. **`StreamReducer`** — the 45-branch `dispatch` split into `ComposerEvents`, `ThreadEvents`,
    `MessageEvents`, `HandoffEvents`, each a `fun handle(e: StreamEvent): Boolean`.

Endpoint: `StreamViewModel` ≈ 250 lines — state holder, `dispatch` fan-out, controller ownership.

---

## 2. `AppContainer.kt` — 3589 lines, 167 `val`s, 123 `by lazy`, 2 `init` blocks

`app/src/main/java/local/phantom/companion/data/AppContainer.kt`

This is not a DI container. It is a DI container *plus* the routing engine, the transit planner, the
tool catalogue, the memory consolidator, the proactive-voice seat and the bus-piping layer.

### Responsibility map

| Lines | Responsibility | Destination |
|---|---|---|
| 90–170 | buses, pair/discovery clients, `PhantomApi`, `PhantomLink` | `NetGraph.kt` |
| 117–170 | DB, `ProfileStore`, `VaultStore`, `DiaryStore` + `DiaryPublishTransport` anon object | `DataGraph.kt` |
| 170–330 | `AiKeyStore`, `ModelManager`, `OnnxEmbeddingHelper`, `LocalLlmRoute`, `GeminiCloudRoute`, the 80-line inline `cloudResumable` anonymous `AIRouter` (`:251`), `HotSwapRouter` | `AiGraph.kt`; **move `cloudResumable` to `core-ai/route/ResumableAIRouter.kt`** — it is production routing logic hiding in a DI file |
| 329–430 | `NetworkProbe`, `AiBootstrapper`, `HearingProvisioner`, `ttsEngine` composition | `AiGraph.kt` / `VoiceGraph.kt` |
| 447–450 | `init { GeoEndpoints.config = … }` — global mutable static set from a constructor | replace with a `GeoEndpointsProvider` injected value |
| **475–678** | `fetchRoadWays`, `snapToRoad`, `routedVariants`, `offlineMeshRoute`, `fetchCoverGrid`, `corridorLaunch` — real routing algorithms | **`:core-nav` → `RoadCorridorService`** |
| 679–760 | POI / shelter / spatial-layer repositories, `offlinePoiPack` asset parse | `GeoGraph.kt` |
| **738–840** | 8 app-wide `MutableStateFlow`/`MutableSharedFlow` cross-screen channels (`activeThreadRef`, `mapViewport`, `mapOpenRequests`, `continuumStratumRequest`, `visibleStratum`, `streamPromptRequests`, `emotionTone`, `threatBriefRequests`, `navCommands`, `activeRoutePoints`) | **`AppEventBus.kt`** — these are the single worst parallel-edit hotspot; give them their own file |
| **861–1010** | `multimodalPlannerLazy` (parses `kyiv_metro.json` + `kyiv_surface.json`, builds RAPTOR timetable), `navEngine` wiring | **`:core-nav` → `TransitPlannerFactory`** |
| 1011–1060 | `worldSummoner`, `offlineRegions` — instantiate `feature-stream/ui/map/**` classes | see §4 misplacement |
| 1064–1160 | `voiceManager`, `duplexVoice`, `sensorAdapter` | `VoiceGraph.kt` / `SensorGraph.kt` |
| 1157–1300 | `streamRepository`, `offlineIntentQueue`, schedulers, `threadMetaRepository`, `soundEngine`, `locator`, `blackBox` | `DataGraph.kt` |
| **1308–1356** | `assembleTacticalSnapshot` — fuses fix + trust + guidance + layers + shelter | **`:core-nav` → `TacticalSnapshotAssembler`** |
| 1358–1670 | `guardedLocation`, `locationProvider` (110-line inline fix-selection policy at `:1368`), `installedAppsProvider` with hand-rolled cache, `lastKnownByProvider`, `reverseGeocoder`, `locationSnapshotProvider` | **`:core-sensor` → `FusedFixPolicy`**; the rest to `LocationGraph.kt` |
| 1676–1780 | `contextEngine` construction — a 90-line inline `ContextInput` builder that registers a `BATTERY_CHANGED` receiver **synchronously** | `ContextGraph.kt`; the input builder to `:core-context` |
| 1783–1860 | `memoryFactStore`, `localGenerate`, `memoryConsolidator`, **`consolidateMemory`** (business logic) | **`:core-data` → `MemoryConsolidationUseCase`** |
| 1860–1900 | calendar/task/reminder/place stores, `patternRepository`, `patternDetector`, `taskerEngine` | `DataGraph.kt` |
| **1903–2316** | `init` #2 — 413 lines: brain bootstrap, a `while(true)` memory-consolidation loop, BrainWarmer, accessibility authorizer, call-screening policy, GodMode event archiving, download-event → TTS announcements, link-status notices, code-runner bridge, service starts, diary/whisper routing | **`AppBootSequence.kt`** with explicit ordered phases |
| 2317–2398 | `startProactiveVoiceSeat` | `:core-symbiote` (`ProactiveEngine` already lives there) |
| 2399–2545 | `pipeBusesToNervousBus` — 5 bus adapters | **`NervousBusBridge.kt`** |
| 2554–2586 | `buildRunCodeEnvelope` | `:core-net` |
| 2588–2830 | constitution/embodiment/bioloop/offload/predict/tempo/create/diplomacy/distributedself/memorypalace/negotiate/extensionsenses/healthmesh/swarm singletons | see §3 — mostly deletable |
| **2998–3385** | `toolRegistry` — ~80 `Tool` instances with inline lambdas | **`:core-symbiote` → `ToolCatalog(ports: ToolPorts)`** split into `PhoneToolCatalog`, `MapToolCatalog`, `AgentToolCatalog`, `DataToolCatalog` |
| 3387–3450 | agent supervisor, council, foresight, weather | `AgentGraph.kt` |
| 3451–3565 | `bootSymbioteManifests` — 115 lines of literal `FeatureManifest`s | **`manifests.json` asset** or a `@FeatureManifest`-annotated registry |
| 3567–3589 | `nullRouter`, `nullTts` null-objects | `core-ai` / `core-voice` (they belong next to the interfaces) |

### Decomposition target

```
app/src/main/java/local/phantom/companion/di/
  AppContainer.kt        // ~200 lines: owns the graphs, exposes them, nothing else
  AppEventBus.kt         // the 10 cross-screen flows
  AppBootSequence.kt     // ordered, cancellable, instrumented boot phases
  graph/NetGraph.kt  DataGraph.kt  AiGraph.kt  VoiceGraph.kt  SensorGraph.kt
  graph/GeoGraph.kt  ContextGraph.kt  ToolGraph.kt  AgentGraph.kt  GodModeGraph.kt
  bridge/NervousBusBridge.kt
```

Each `*Graph` is `class XGraph(private val ctx: Context, private val deps: …)` with `by lazy`
members and **no `init` side effects**. `AppContainer` becomes `val net = NetGraph(ctx)`,
`val data = DataGraph(ctx, net)`, … — an explicit topological order, which is the thing the current
file's 123 interleaved `by lazy` fields hide.

### Extraction order

1. **`AppEventBus.kt`** (738–840). Zero logic, ten fields, touched by every feature — extract first
   so the other four engineers stop rebasing on it.
2. **`NullObjects.kt`** (3567–3589) → `:core-ai` / `:core-voice`.
3. **`ToolCatalog`** (2998–3385) into `:core-symbiote` behind a `ToolPorts` interface listing the
   ~25 callbacks the tools need. Biggest single line count, mechanical, isolated.
4. **`AppBootSequence.kt`** (1903–2316). Move as-is, then split into named phases.
5. **`RoadCorridorService` / `TransitPlannerFactory` / `TacticalSnapshotAssembler`** → `:core-nav`
   (475–678, 861–1010, 1308–1356). These are the pieces that make `:app` non-testable today.
6. **`ResumableAIRouter`** (251–297) → `:core-ai`; **`FusedFixPolicy`** (1368–1450) → `:core-sensor`;
   **`MemoryConsolidationUseCase`** (1825–1858) → `:core-data`.
7. Split the remaining `by lazy` fields into the `*Graph` files by package prefix. Mechanical.
8. `bootSymbioteManifests` → asset.

---

## 3. Module graph

### Actual edges (from `settings.gradle.kts` + 21 `build.gradle.kts`)

```
app ──► core-{voice,ai,data,net,nav,sensor,context,design,geo,symbiote,gnss}
    └─► feature-{onboarding,stream,precision,car,godmode,ambient}
core-data ──► core-net, core-ai                     ◄── layering inversion
core-geo  ──► core-net, api core-data, core-symbiote ◄── layering inversion
core-nav  ──► api core-net, core-data
core-symbiote ──► core-ai, core-data, core-net, core-context
core-design ──► core-context
feature-stream ──► core-{data,net,nav,geo,design,ai,voice,vision,sensor,context,symbiote}
               └─► feature-godmode                   ◄── feature→feature
feature-godmode ──► core-{data,net,design,ai}
feature-precision ──► core-design, api core-{gnss,mobility,indoor}, core-geo, core-sensor
feature-ambient ──► core-{data,net,context,design}
feature-onboarding ──► core-{data,net,design}
feature-car ──► (no project deps at all)
core-{ai,net,context,sensor,voice,vision,gnss,indoor,mobility} ──► (leaf)
```

**No Gradle cycles.** Three structural problems instead:

1. **`core-data → core-ai`.** A persistence module depending on the inference module. Caused by
   `LifeLogArchive(dao, onnxEmbeddingHelper)` needing an embedder. Fix: `fun interface Embedder`
   in `:core-data`, implemented in `:core-ai`, injected at the `:app` seam. Removes `core-ai` from
   the critical path of every `:core-data` recompile.
2. **`core-geo → core-symbiote`.** `:core-geo` needs `core.tool.Tool` for `MetroStationTool`.
   Fix: move the `Tool`/`ToolResult`/`ToolContext` *interfaces* into a new 200-LOC `:core-tool-api`;
   `:core-symbiote` keeps the implementations. Also breaks `:core-symbiote` out of the compile path
   of `:feature-stream`.
3. **`feature-stream → feature-godmode`.** `StreamViewModel` imports 10 symbols from
   `feature.godmode.engine` (`AppAction`, `AppActionParser`, `ScreenContext`, `TaskerEngine`, …).
   Fix: those are contracts, not UI — move `engine/` to a new `:core-automation`, leave
   `feature-godmode` as the accessibility-service + settings UI. Two features stop depending on each
   other, and `:feature-stream` recompiles stop cascading from GodMode edits.

### Near-empty / speculative

| Module | main files / LOC | Verdict |
|---|---|---|
| `:core-vision` | **3 / 168** | Pulls `mediapipe-tasks-vision` + `mlkit-barcode` + `mlkit-text` (tens of MB of AARs) for 168 lines, 0 tests, `ENABLE_VISION_PREMIUM=false`. **Fold into `:core-ai` or delete.** |
| `:feature-car` | 4 / 444 | No project deps; wired by `PhantomApp.onCreate` setting a static `PhantomCarAppService.ports`. Fine as-is, but the static handshake should become a `ContentProvider`-free explicit initializer. |
| `:core-context` | 18 / 746 | Hosts `core/bus/PhantomNervousBus`, which has nothing to do with context. Move the bus to `:core-net` or a new `:core-bus`. |
| `:core-symbiote` | **204 / 15 155** | 23 unrelated top-level packages. `bioloop`, `swarm`, `negotiate`, `healthmesh`, `extensionsenses`, `memorypalace`, `distributedself`, `diplomacy`, `create`, `tempo`, `offload`, `embodiment` have **5–11 references each, all of them inside `AppContainer.kt` / `SymbioteRuntime.kt`** — instantiated, never consumed by a product surface. ~102 references total for what is likely half the module's LOC. |
| `wear/` | 0 main files, only a `build/` dir | **Not in `settings.gradle.kts`.** Dead directory. Delete. |
| `scratch/`, `graphify-out/`, `build.log` | — | Committed build detritus. Delete or `.gitignore`. |

### Misplaced code

- **UI package holding infrastructure:** `AppContainer` instantiates
  `feature.stream.ui.map.prefetch.CorridorPrefetcher` (`:635`),
  `feature.stream.ui.map.OfflineRegionsManager` (`:1059`),
  `feature.stream.ui.map.WorldLayerSummoner` (`:1011`),
  `feature.stream.ui.map.nav.RoadSnapFix` (`:515`). None are Composables. They belong in
  `:core-geo`/`:core-nav`.
- **Business logic in `:app`:** routing (475–678), transit planning (861–1010), tactical snapshot
  (1308–1356), fix-selection policy (1368–1450), memory consolidation (1825–1858), the tool
  catalogue (2998–3385). `:app` is 17 643 LOC and has 78 tests; most of that logic is untestable
  where it sits.
- **UI in `:app` that belongs in a feature:** `ui/continuum/ContinuumHost.kt` (806),
  `ui/settings/MapSettingsScreen.kt` (627), `ui/PhantomIsland.kt` (502),
  `navigation/PhantomNavGraph.kt` (619). `:app` should be manifest + nav host + DI.

### Target module set

```
:core-bus         (new)  PhantomNervousBus, PhantomErrorBus, AppEventBus contracts
:core-tool-api    (new)  Tool, ToolResult, ToolContext, ToolPermission  — ~200 LOC
:core-automation  (new)  ex feature-godmode/engine: AppAction*, ScreenContext, Tasker, call screening
:core-net  :core-data  :core-ai  :core-voice  :core-sensor  :core-context  :core-design
:core-nav         + RoadCorridorService, TransitPlannerFactory, TacticalSnapshotAssembler
:core-geo         + CorridorPrefetcher, OfflineRegionsManager, WorldLayerSummoner
:core-gnss :core-indoor :core-mobility            (keep — real math, 217 tests between them)
:core-agent       (split from core-symbiote: agent/, tool/, predict/, resilience/, constitution/)
:core-symbiote    (what remains — or delete the 12 dead packages outright)
:feature-stream   (split further: see §4)
:feature-map      (new — extract feature-stream/ui/map/**, ~12 000 LOC of the 63 000)
:feature-godmode :feature-ambient :feature-onboarding :feature-precision :feature-car
:app              (manifest, nav host, DI graphs, services)
DELETE: :core-vision (fold), wear/, scratch/, graphify-out/
```

---

## 4. `StreamScreen.kt` — 1745 lines

`feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/StreamScreen.kt`

Better hygiene than the other two — `stableOnEvent` (`:481`), `contentType` (`:579`), keyed
`itemsIndexed` (`:575`) are all present and deliberate. The problem is size and mixed concerns.

| Lines | Responsibility | Extract to |
|---|---|---|
| 128–200 | signature + 4 derived-list `remember`s | stays (rewritten per §0) |
| 200–260 | scroll/unread/haptic-arrival state machine | `rememberStreamScrollState()` |
| 400–450 | error-haptic + heartbeat `LaunchedEffect`s | `StreamHaptics.kt` |
| 450–500 | voice Z-axis morph, `streamRecede` `graphicsLayer` | `StreamVoiceMorph.kt` |
| 500–700 | `Box`/`Column`/`LazyColumn` scaffold | stays — this is the screen |
| 690–860 | thread rail / space creation inline lambdas | `SpaceRailSection.kt` |
| 994–1070 | `TextSelectionSheet` | `sheet/TextSelectionSheet.kt` |
| 1071–1136 | `DialogueBackdrop`, `Modifier.fadingEdges` | `core-design` (`PhantomBackdrop`, `Modifier.fadingEdges`) |
| 1137–1365 | `StreamItemRow` — swipe-to-quote gesture, long-press action menu, share intent | `row/StreamItemRow.kt` + `row/MessageActionMenu.kt` |
| 1366–1448 | `InnerStreamItemRow` — the item-type `when` | `row/StreamItemContent.kt` |
| 1449–1493 | `StreamRow.keyOrIndex`, `contentType`, `isToolTrace`, `isChainPlaceholder` | `row/StreamRow.kt` |
| 1494–1602 | `withTimeRibs`, `timestampOf`, `bucketOf`, `formatDate`, `sameLocalDay` | `TimeRibs.kt` — pure, JVM-testable |
| 1603–1745 | `TimeRib`, `StreamRestoreSkeleton`, `EmptyState`, `StreamAmbientLayer` | `parts/` |

Order: 1494–1602 (pure) → 1449–1493 → 1071–1136 (to `core-design`) → 1137–1448 (rows) →
994–1070 (sheet) → 200–500 (state/effect hoists) → 690–860. Target ≈ 300 lines.

---

## 5. State management — top 10 performance defects

`StreamViewModel` exposes exactly three reactive surfaces (`state: StateFlow<StreamUiState>` at
`:410`, `insightActions: SharedFlow` at `:419`, `lockVaultPulse: SharedFlow` at `:431`) — the
exposure discipline is good. The cost is all in what one emission *costs*, and there is one emission
per streamed token.

| # | Defect | Where | Cost |
|---|---|---|---|
| 1 | `StreamUiState` is Compose-unstable — 7 collection-interface fields + 3 `Lazy<T>` backing fields | `StreamUiState.kt:202,214,225` + fields | Under strong skipping, params are compared by instance; a new instance per token means `StreamScreen` (`StreamScreen.kt:130`), `ComposeRow` (`ComposeRow.kt:94`) and everything under them de-skip **every token** |
| 2 | Per-token full-collection copy | `StreamViewModel.kt:2052` → `StreamUiState.kt:312` → `:294` + `:298` | `threadOf` scans all threads × items, then the map **and** the active list are rebuilt. O(N) alloc per token; on a 500-turn thread this is the frame-drop the operator feels |
| 3 | `items` recomputed per state instance | `StreamUiState.kt:202` | On `ThreadId.MAIN`: `FacetMath.mergeNonVault` + `FacetMath.filter` over all threads, per token. The `by lazy` fixed *repeat reads of one instance*; it did nothing for *a new instance per token* |
| 4 | `turnPhase` forces `items` | `StreamUiState.kt:225–233` | Any header read of the phase drags in #3 |
| 5 | Deep structural `Map`/`List` equality on every emission | `StreamViewModel.kt:528` `.distinctUntilChangedBy { it.threads }` | `equals` over the whole conversation, per token, on the collector dispatcher — and it always returns false during streaming, so it never saves the work it costs |
| 6 | `appendIfAbsent` full scan | `StreamUiState.kt:282` | `threads.values.any { it.any { … } }`; called from ~10 init collectors (`:589`, `:774`, `:900`, `:1006`, `:1098`, …) |
| 7 | Four derived lists re-materialised per token | `StreamScreen.kt:168,173,190,194` | Keys are `state.items` / `visibleItems`, both fresh instances per token → `filterIsInstance`, `filterNot`, `withTimeRibs` (a full re-bucket of the timeline) and `lastOrNull` all re-run every token |
| 8 | `ribbed` captured inside a per-item `graphicsLayer` | `StreamScreen.kt:597–604` | `val fromTail = (ribbed.lastIndex - idx)` — `ribbed` changes identity per token, so every visible row's layer block is re-created and re-drawn even when its own content did not change |
| 9 | `ForesightEngine?` threaded to every row | `StreamScreen.kt:1142`, passed at `:610` | An unstable class reference as a row parameter defeats per-row skipping regardless of #1 |
| 10 | Unbounded `LazyListState` retention | `StreamScreen.kt:164` `remember { mutableStateMapOf<String, LazyListState>() }` | One `LazyListState` per thread ever opened, never evicted, for the composition's lifetime |

Honourable mentions: `finalizeAssistant` does `val snapshot = _state.value` (`:2085`) then a later
`_state.update` — a read-modify-write race with concurrent token appends, mitigated only by the
`deferred` side-effect queue at `:2112`; and `_state.update` inside `WidgetEventBus.collect`
(`:601`) runs on `Dispatchers.IO` while Compose reads on main — correct via `StateFlow`, but every
one of the 21 collectors can publish a state, and none of them are rate-limited.

**Fixes, in order of payoff:** #2+#3 together (streaming text out of the item list, §0 step 3) —
this removes the per-token O(N) entirely. Then #1 (stability config, §0 step 4). Then #5 (compare
`threads` by a monotonically-incrementing revision counter, not by `equals`). Then #7/#8 (key the
`remember`s on a `revision: Long` instead of on list identity). #6, #9, #10 are one-liners.

---

## 6. Build health

**Versions** (`gradle/libs.versions.toml`): AGP 8.7.3, Kotlin 2.0.21, KSP 2.0.21-1.0.27,
Compose BOM 2024.11.00, Coroutines 1.9.0, Room 2.6.1, Ktor 2.3.13, Serialization 1.7.3.
Internally consistent (KSP matches Kotlin, Compose plugin is the K2 `kotlin-compose` one). One
generation behind current, which is the right call tonight — **do not bump anything.**

**Compiler flags.** Every module repeats the same four-line `freeCompilerArgs` opt-in block
(`app/build.gradle.kts:138`, `core-ai:32`, `core-data:52`, … 20 copies). Missing:
`-Xjvm-default=all`, `-Werror`, `-Xexplicit-api` for the `:core-*` modules, and any
`composeCompiler { reportsDestination }` — so nobody can currently *measure* the §5 defects.
Two `kotlinOptions` blocks use the deprecated `kotlinOptions` DSL (AGP 8.7 warns; AGP 9 removes it).

**Fix:** add `buildSrc/` or a `build-logic/` convention plugin —
`phantom.android.library`, `phantom.android.compose`, `phantom.android.feature`. That deletes
~600 lines of duplicated Gradle and is the only way five engineers can add a module tonight without
copy-pasting a stale template. Ship the stability config and
`composeCompiler { metricsDestination = layout.buildDirectory.dir("compose-metrics") }` through it.

**Static analysis: none.** No detekt, no ktlint, no `lint {}` block, no `lintOptions`, no baseline.
Android Lint runs only in its default `assembleRelease` form. Conceptually it would not pass:
`AppContainer.kt:1716` calls `registerReceiver` on the main thread inside a `by lazy`;
`StreamViewModel.kt:2050` uses a `ConcurrentHashMap<String, StringBuilder>` where `StringBuilder`
is mutated outside the map's lock; there are ~40 `android.util.Log.d` calls with interpolated
payload in release paths (`StreamViewModel.kt:1969`, `:2082` logs `finalText.take(2000)` —
conversation content into logcat, a privacy defect, not just a perf one).

**Tests.** ~3 248 `@Test` in 20 modules. Distribution: `:feature-stream` 1017, `:core-symbiote` 548,
`:core-data` 218, `:core-net` 166, `:core-ai` 163, `:core-design` 155, `:core-sensor` 141,
`:feature-godmode` 132, `:core-geo` 100, `:core-voice` 96, `:core-gnss` 95, `:core-indoor` 86,
`:app` **78**, `:core-nav` 64, `:feature-ambient` 54, `:core-mobility` 36, `:feature-precision` 34,
`:core-context` 29, `:feature-onboarding` 29, `:feature-car` 7, `:core-vision` **0**.
`:app` holds 17 643 LOC and 78 tests — that ratio is the direct consequence of §2's misplaced
business logic. No instrumented tests wired in CI (`.github/workflows/ci.yml` is JVM-only, by design).

**R8.** `release { isMinifyEnabled = true; isShrinkResources = true }` with
`proguard-android-optimize.txt` + `proguard-rules.pro` (`app/build.gradle.kts:95–106`).
`debug` unminified with `doNotStrip("**/*.so")`. ABI splits arm64-v8a + x86_64, no universal APK.
Reasonable. `signingConfig = signingConfigs.getByName("debug")` on **release** (`:105`) — fine for
tonight, blocker for shipping.

**Startup path — `PhantomApp.onCreate` (`app/src/main/java/local/phantom/companion/PhantomApp.kt`).**
Main-thread work, in order:
1. `System.loadLibrary("sqlcipher")` — native load, ~10–30 ms.
2. `AppContainer(this)` — runs `init` #1 (`AppContainer.kt:447`, sets `GeoEndpoints.config`, which
   forces `mapPrefs` → SharedPreferences read) and `init` #2 (`:1903`, 413 lines). That init calls
   `aiBootstrapper.ensureBrain()`, `networkProbe` observation, starts a `while(true)` memory loop,
   two `Dispatchers.IO` warm-ups, and registers accessibility/call-screening authorizers.
   **`aiBootstrapper` and `networkProbe` are `by lazy` — touching them here forces
   `ConnectivityManager` + `ModelManager` + `AiKeyStore` (EncryptedSharedPreferences → Keystore)
   construction on the main thread.** EncryptedSharedPreferences first-open is 50–150 ms on a
   mid-range device.
3. On failure it constructs `AppContainer` **a second time** (`:50–58`) — the "degraded fallback"
   will re-run the same failing init and double every side effect that did succeed.
4. `PhantomCarAppService.ports = AppCarPorts(container)`.
5. `bootSymbioteManifests()` — 115 literal manifests + 13 `*Manifests.all` concatenations, main thread.
6. `symbioteRuntime.start(scope)`, `ReminderAlarmSync.start`, `WidgetUpdater.start`.
7. Three `NotificationChannel` registrations, `Watchdog.schedule`.
8. `warmGeoAssets()` — correctly on a background thread at `NORM_PRIORITY-1`.

**Fix:** `AppBootSequence` with three phases — `critical()` (synchronous, SQLCipher + notification
channels only), `deferred()` (on `Dispatchers.Default` after the first frame: manifests, symbiote
runtime, reminder sync, widget updater), `idle()` (`Choreographer` idle callback: brain warm-up,
memory loop). Remove the double-construct fallback at `:50–58`; a failed container should surface as
a degraded-mode flag, not a re-run.

---

## 7. Risk list — 5 engineers in parallel tonight

Ten most likely collisions, and the partition that prevents them.

| # | Risk | Mitigation |
|---|---|---|
| 1 | Two people editing `StreamViewModel.kt` → 4177-line merge conflicts, guaranteed | **One owner, no exceptions.** Extractions are append-a-file + delete-a-range; sequence them (§1 order) rather than parallelising them |
| 2 | `AppContainer.kt` is touched by *every* feature that needs a new dependency | Do §2 step 1 (`AppEventBus.kt`) and step 3 (`ToolCatalog`) **first, before anyone else starts**, then declare `AppContainer.kt` append-only for the night (new `by lazy` at the bottom, no reordering) |
| 3 | `StreamUiState.kt` changes shape under everyone at once | §0 is a **blocking prerequisite**, merged before parallel work begins. Everyone rebases on it |
| 4 | 1017 `:feature-stream` tests break en masse on a state-shape change | Keep `StreamUiState` as a facade over the three new classes (§0 step 5) so old accessors still compile; migrate tests in a separate follow-up commit |
| 5 | `libs.versions.toml` bumps mid-refactor invalidate every module's build cache | **Freeze `gradle/libs.versions.toml` and all `build.gradle.kts` except the convention-plugin change.** One owner for build files, done first, everyone rebases |
| 6 | `configuration-cache=true` + `parallel=true` (`gradle.properties`) — a new convention plugin or a `project.findProperty` at execution time silently invalidates the cache for everyone | Same owner as #5; verify with `./gradlew --configuration-cache assembleFieldDebug` once before handing off |
| 7 | Widget-type `when` chains in `finalizeAssistant` (`StreamViewModel.kt:2176–2470`) are the natural place for three different people to add a widget | Land `WidgetDispatcher` as a `Map<String, WidgetHandler>` (§1 step 9) early; after that each widget is a new file |
| 8 | Global mutable statics — `GeoEndpoints.config` (`AppContainer.kt:449`), `PhantomCarAppService.ports` (`PhantomApp.kt:63`), `WidgetEventBus`, `AppActionTraceBus`, `GodModeEventBus`, `SelectedTextRouter`, `ScreenContextStore`, `ForegroundAppMonitor` — order-dependent init that breaks non-deterministically under refactor | Inventory them into `AppEventBus.kt` / `AppBootSequence.kt`; forbid new ones tonight |
| 9 | `:core-symbiote` (204 files) and `:feature-stream` (262 files) are on the compile path of nearly everything; any signature change there is a full rebuild for all five engineers | Do the `:core-tool-api` split (§3 item 2) if time allows; otherwise assign the two big modules to the two engineers who need them and keep the other three in leaf modules |
| 10 | No detekt/ktlint means style drift lands silently and shows up as conflict noise in every subsequent diff | Add ktlint with `--format` in the convention plugin **or** add nothing. Do not add a *checking-only* linter tonight — it will fail CI on 1390 pre-existing files |

### Partition

| Engineer | Owns (exclusive write) | First deliverable |
|---|---|---|
| **A — state** | `feature-stream/.../domain/**` | §0 steps 1–4, merged before anyone else starts. Then §0 step 5 |
| **B — ViewModel** | `feature-stream/.../StreamViewModel.kt` + new `feature-stream/.../turn/**`, `tool/**`, `agent/**`, `ingest/**` | §1 steps 1–5, then 6–8 |
| **C — DI/boot** | `app/src/main/java/local/phantom/companion/data/**`, `di/**`, `PhantomApp.kt` | §2 steps 1–4 (AppEventBus first, within the hour) |
| **D — UI** | `feature-stream/.../ui/**` (excluding `ui/map/**`) + `core-design/**` | §4 steps 1–5 |
| **E — build/modules** | all `build.gradle.kts`, `settings.gradle.kts`, `gradle/**`, new `build-logic/**` | §6 convention plugin + stability config + compose metrics, then §3 items 1–3 |

Serialisation points: **A's §0 steps 1–4 block B and D.** **E's convention plugin blocks nobody but
must land before any other `build.gradle.kts` edit.** **C's `AppEventBus` extraction blocks nobody
but should land within the hour to stop rebase churn.** Everything else is genuinely parallel.

Cross-cutting rule for the night: **extract by adding a file and deleting a contiguous range** —
never reorder, never reformat, never rename in the same commit as a move. That keeps every diff
reviewable and every conflict a one-line resolution.
