# Phase 3 Integration Audit (DA-1)

> System-wide trace of every critical path after Phase 3 GodMode v2.
> Read-only commit. Subsequent DA-2..DA-7 commits delete zombies, fix
> the breaks pinned here, sync drift, migrate schedulers to WorkManager,
> measure perf, and ship the integration-clean APK.
>
> Methodology: for each path, follow the wire from server frame → Kotlin
> producer → Flow surface → UI consumer. **Wiring is alive** when at
> least one production call site collects the flow into something the
> operator can perceive. **Pipe alive · surface empty** when the producer
> emits but no `.collect {}` exists in any non-test code path.

## TL;DR

| # | Path | Status |
|---|------|--------|
| 1 | Server WS event → typed flow → UI consumer | ✅ |
| 2 | PTT → STT → AI → TTS voice cycle | ✅ |
| 3 | ScreenContext → enrichedContext → AIRouter | ✅ |
| 4 | app-action JSON → AccessibilityActionDispatcher → trace UI | ✅ |
| 5 | Notification → classifier → Stream / digest | ✅ |
| 6 | CallScreening → IncomingCallBus → Stream | ✅ |
| 7 | StreamUiState → Room persistence → cold-launch restore | ✅ |
| 8 | SurfaceTray → 7 destinations | ✅ |
| 9 | Settings toggle → preferences → reactive UI | ✅ |

Three breaks (`DA-3` territory) — ALL FIXED:

- **B-1** §10.15 `decision_diary` inbound — Fully wired in `AppContainer.kt`.
- **B-2** `companion_control/lock_vault` verb — Fully wired in `StreamNav.kt`.
- **B-3** `IncomingCallCard` Composable — Fully wired in `StreamNav.kt` (topOverlay slot).

Plus two scheduler fragility issues fixed by **DA-5**:

- **B-4** `StreamCleanupScheduler` (W2-5) — Migrated to `PeriodicWorkRequest`.
- **B-5** `NotificationDigestScheduler` (W4-5) — Migrated to `PeriodicWorkRequest`.

Net audit verdict: **system is wired far better than the
"pipe alive · surface empty" Phase 0 starting point**. Of 10 typed
channel flows, the four with operator-visible UX
(`agentEvents`, `chatEvents`, `handoffEvents`, `incomingCallFlow`,
`notificationImportantFlow`, `notificationDigestFlow`,
`controlVerbs/run_code` outbound) are alive end-to-end. Three breaks
above are the remaining gap.

---

## Path 1 — Server WS event → Kotlin Flow → UI consumer

Server emits per-channel JSON envelopes
(`{channel, type, data, ts}`, see CONTRACT §3). Decoder lives at
`PhantomViewModel.kt:151-298`. Each channel has a typed event class
in `PhantomLinkEvents.kt`. Consumers lookup table:

| Channel | Decoder | ViewModel surface | UI consumer | Status |
|---|---|---|---|---|
| `state` | `PhantomViewModel.kt:156` | `_state.systemState` | `StreamNav.kt`, `SystemPulseHeader` | ✅ |
| `sensor` | `:162` | `_state.bpm` | `SystemPulseHeader` | ✅ |
| `context` | `:172` | `_contextState` (StateFlow) | **none** | ⚠ pipe alive, surface empty (Phase 1-D backlog) |
| `familiar` | `:184` | `_familiarState` (StateFlow) | **none** | ⚠ pipe alive, surface empty (Phase 9 Familiar 3D) |
| `pair` | `:195` | `_pairEvents` (SharedFlow) | **none** | ⚠ pipe alive, surface empty (claim already routed via REST) |
| `agent.stream` | `:212` | `_agentEvents` (SharedFlow) | `StreamNav.kt:142-150` (emotion tone), `:158-165` (proactive insights), `AppContainer.kt:713` (Whisper bus) | ✅ |
| `chat` | `:226` | `_chatEvents` (SharedFlow) | `StreamNav.kt:171-179` (proactive chats) | ✅ |
| `map` | `:242` | `_mapEvents` (SharedFlow) | **none** | ⚠ pipe alive, surface empty (MapWidget consumes local intents only — Phase 1-G) |
| `companion_control` | `:253` | `_controlVerbs` (SharedFlow) | partial — see **B-2** | ⚠ |
| `handoff` | `:267` | `_handoffEvents` (SharedFlow) | `StreamNav.kt:183-191` → `StreamViewModel.handoffEvents` → bottom sheet | ✅ |
| catch-all (`profile_sync`, `decision_diary`, `drive`, `settings`, `oled`, `background_events`, `standing_orders`, `_meta`) | `:287` | `_miscEvents` (SharedFlow) | **none** | ⚠ — see **B-1** for `decision_diary` (the only catch-all channel that has a Kotlin subscriber waiting) |

### B-1 — `decision_diary` inbound subscriber not started

- Producer: server emits `decision_diary/{created,updated,deleted,follow_up}`
  per CONTRACT §10.15.
- Decoder: `PhantomViewModel.kt:287` (catch-all `else` branch) emits a
  `MiscEvent(channel="decision_diary", …)` onto `_miscEvents`.
- Parser: `feature-stream/.../diary/DiaryWireParser.kt` (`tryParse`
  takes `(type, raw)` and produces a typed `DiaryWireMessage`).
- Subscriber: `feature-stream/.../diary/DiaryWebSocketSubscriber.kt`
  (`subscribe(scope, messages: Flow<DiaryWireMessage>)`).
- **Production wiring**: zero call sites. `grep -rn "DiaryWebSocketSubscriber\(" --include="*.kt"` returns
  test files only. AppContainer constructs `diaryStore` and the
  publish-side `diaryTransport`, but no `viewModelScope.launch { … }`
  pumps `phantomVm.miscEvents` through the parser into the subscriber.
- **Fix in DA-3**: in `StreamNav.kt` add a `LaunchedEffect(phantomVm)`
  that filters `miscEvents` by `channel == DiaryWireParser.CHANNEL`,
  parses, and feeds the flow to `DiaryWebSocketSubscriber.subscribe()`.

### B-2 — `companion_control/lock_vault` verb not threaded

- Producer: server emits `companion_control/lock_vault` per
  CONTRACT §7. `_controlVerbs` already decodes it.
- Parser: `feature-stream/.../domain/LockVaultEvent.kt` (`LockVaultParser.tryParse`).
- VM input: `StreamViewModel.kt:103` (`lockVaultEvents: Flow<LockVaultEvent>?`).
- VM output: `StreamViewModel.kt:415-436` (clamps `vaultStore.lockNow()`,
  emits Notice + `_lockVaultPulse`).
- **Production wiring**: `StreamNav.kt:198-228` constructs
  `StreamViewModel` without passing `lockVaultEvents` *or* `vaultStore`.
  KDoc on `lockVaultEvents` says «NOT yet hooked in production
  StreamNav per operator's deferral — needs on-device verification on
  the SM-A566B target before going live». Phase 3 close = on-device
  verification has happened many times; this defer is now a paper-cut
  that costs the operator the entire `lock_vault` remote-clamp UX.
- **Fix in DA-3**: thread `phantomVm.controlVerbs.filter{verb=="lock_vault"}`
  → `LockVaultParser.tryParse` → into the VM constructor in StreamNav.
  Also pass `vaultStore = container.vaultStore` so the actual clamp
  fires.

### Path 1 wiring summary

```
WS frame
   └── PhantomLink.events  (channel-tagged raw JsonObject)
         └── PhantomViewModel.init.launch (channel switch)
               ├── _state.systemState              ─► SystemPulseHeader   ✅
               ├── _state.bpm                      ─► SystemPulseHeader   ✅
               ├── _contextState                   ─► (no consumer)       ⚠
               ├── _familiarState                  ─► (no consumer)       ⚠
               ├── _pairEvents                     ─► (no consumer)       ⚠
               ├── _agentEvents       ─► EmotionToneParser      ─► LocalEmotionTone        ✅
               │                      ─► ProactiveInsightParser ─► StreamItem.Insight       ✅
               │                      ─► WhisperEventBus        ─► WhisperLineRibbon        ✅
               ├── _chatEvents        ─► ProactiveChatParser    ─► StreamItem.Text         ✅
               ├── _mapEvents                      ─► (no consumer)       ⚠
               ├── _controlVerbs      ─► (lock_vault not parsed)          ⚠ B-2
               │                      ─► (run_code is OUTBOUND, see CodeRunnerActionBus) ✅
               ├── _handoffEvents     ─► HandoffParser ─► StreamViewModel.handoffEvents
               │                                        ─► bottom-sheet UI                  ✅
               └── _miscEvents        ─► (no consumer; B-1 unhooks decision_diary)         ⚠
```

---

## Path 2 — Voice cycle (PTT → STT → AI → TTS → playback)

```
StreamEvent.PttPress
   └── StreamViewModel.dispatch (line 534)
         └── voiceManager.startCapturing            (core-voice)
               └── AudioRecorder PCM16 16kHz
                     └── VoskHybridDecoder + Whisper refine
                           └── voiceManager.sttResults  ─► StreamViewModel.submit  ✅
                                 └── aiRouter.complete (RoutedAIRouter cloud→local)
                                       └── AIChunk.Token / Final
                                             └── finalizeAssistant (line 715)
                                                   └── ttsEngine.speak (gated by ttsEnabled) ✅
```

- Test: `feature-stream/.../SttDispatchInvariantTest.kt` (34× hot-path
  per project memory) pins the dispatcher invariant.
- AppContainer wiring confirmed at `:207-225` (VoiceManager) and
  `:182-201` (RoutedTts).

Status **✅**.

---

## Path 3 — ScreenContext → AIRouter

```
ScreenAwarenessService (TYPE_WINDOW_CONTENT_CHANGED)
   └── ScreenContextExtractor.walk
         └── ScreenContextStore.put            (StateFlow<ScreenContext?>)
               └── AppContainer.screenContextProvider (gated by
                                                       voicePrefs.screenAwarenessEnabled)
                     └── StreamNav.kt:216 (param)
                           └── StreamViewModel.submit (line 612)
                                 └── ScreenContextFormatter.format
                                       └── enrichedContext (prepended to AIIntent.context)
                                             └── AIRouter.complete    ✅
```

- Verified by `feature-godmode/.../engine/ScreenContextStore.kt`
  StateFlow + `AppContainer.kt:333-337` gated provider +
  `StreamViewModel.kt:612-614` formatter call.
- W4-2 commit `5d142d2` shipped this end-to-end.
- Toggle: `voicePrefs.screenAwarenessEnabled` is reactive — flipping
  it in Settings → Permissions takes effect on the next prompt.

Status **✅**.

---

## Path 4 — app-action JSON → AccessibilityNodeInfo

```
AI emits JSON: {"type":"app-action","actions":[…]}
   └── StreamViewModel.finalizeAssistant (line 753-779)
         ├── AppActionParser.parseArray
         ├── appActionDispatcher.invoke(widgetId, actions)
         │     └── AccessibilityServiceActionRegistry.requestDispatch
         │           └── AccessibilityActionDispatcher.run
         │                 └── ScreenAwarenessService.findNodeByText / clickNode
         │                       └── AppActionTraceBus.emit(ProgressEvent)
         │                             └── AppActionWidget Composable
         │                                   (subscribes to AppActionTraceBus.events
         │                                    filtered by widgetId)              ✅
         └── StreamItem.Widget("app-action", payload)                  ✅
```

- Authorizer: `AppActionAuthorizer` armed in `AppContainer.init` at
  `:543-550`. `SensitiveAppDenylist` denies banking / OTP / wallet
  packages.
- Test: `feature-stream/.../AppActionWidgetParseTest.kt` pins the
  parse + render + trace flow.
- W4-4 commit `44e8a0d` shipped this end-to-end.

Status **✅**.

---

## Path 5 — Notification → Stream digest

```
NotificationListenerService.onNotificationPosted
   └── NotificationClassifier.classify  (Important / Routine / Spam)
         ├── Important
         │     └── NotificationImportantBus.events
         │           └── StreamViewModel.notificationImportantFlow (line 359)
         │                 └── StreamItem.Notice (severity=WARNING)            ✅
         ├── Routine
         │     └── NotificationDigestRepository.enqueue
         │           └── NotificationDigestScheduler (coroutine loop, 30 min ticks)
         │                 └── DigestFormatter.format
         │                       └── notificationDigestFlow.tryEmit
         │                             └── StreamViewModel.notificationDigestFlow (line 376)
         │                                   └── StreamItem.Notice (severity=INFO) ✅
         └── Spam → drop
```

- Wiring confirmed in `AppContainer.kt:558-561` (config + scheduler launch).
- W4-5 commit `8995e09` shipped this end-to-end.

### B-4 — scheduler fragility

`NotificationDigestScheduler.kt:51-55` is a `while (isActive) { … delay(30min) }`
loop. The class KDoc itself documents the trade-off:

> «The original brief called for a WorkManager periodic job — same
> trade-off as W2-5's StreamCleanupScheduler: the project doesn't
> have androidx.work on the classpath and the «no new dependencies»
> Phase-3 constraint forbids adding it.»

OEMs (Samsung Sleeping Apps, Xiaomi MIUI, Huawei) kill the process
freely. After a process kill, the loop dies; after device reboot the
loop never starts until the operator next opens the app. **9am
digest never fires** unless the operator happens to launch the app
before 9am.

**Fix in DA-5**: add `androidx.work:work-runtime-ktx` (one-time
exception to the no-new-deps rule, justified in the commit message),
migrate to `PeriodicWorkRequest`.

Status **⚠** until DA-5.

---

## Path 6 — CallScreening → IncomingCallCard

```
SecretaryCallScreeningService.onScreenCall
   └── CallScreeningPolicy.decide (allow / screen / reject)
         ├── CallLogRepository.recordIncoming   (Room v9 callLog table)
         └── IncomingCallBus.publish
               └── StreamViewModel.incomingCallFlow (line 398)
                     └── StreamItem.Notice ("дзвінок · номер · причина")  ✅
```

### B-3 — `IncomingCallCard` Composable orphan

- Defined: `feature-stream/.../ui/IncomingCallCard.kt:45` —
  `@Composable fun IncomingCallCard(call: ScreenedCall, …)` with
  «прийняти / відхилити / спочатку послухати» actions and a tap
  handler that routes through `CallLogRepository.recordOperatorAction`.
- Renderer call sites: `grep -rn "IncomingCallCard(" --include="*.kt"`
  returns the definition only — **zero callers** in production tree.
- Operator-visible reality: a screened call surfaces as a single
  WARNING Notice line. The "answer / decline / listen-first"
  affordance (the entire point of the W4-6 Phase 3-C v2 commit
  `16c353f`) is invisible.
- **Fix in DA-3**: render `IncomingCallCard` from `StreamScreen.kt`
  for the most-recent unhandled `ScreenedCall` (probably as a top
  overlay above the LazyColumn so it doesn't scroll out of view).

Status **⚠** until DA-3.

---

## Path 7 — Stream history persistence

```
StreamUiState.update
   └── StreamViewModel._state.collect (line 256-258)
         └── StreamPersistenceWriter.observe
               ├── 200ms debounce window
               └── StreamItemDao.upsert      (Room v6 stream_items)        ✅

Cold launch:
StreamViewModel.init (line 261-284)
   └── streamRepository.byThread(NOW, limit=100)
         └── StreamItemEntityMapper.toDomain
               └── StreamUiState.threads     ─► StreamScreen LazyColumn    ✅

Cleanup (W2-5):
StreamCleanupScheduler (coroutine loop, 1h ticks)
   └── runOnceIfDue (gated by retentionDays > 0 + 24h since last)
         └── StreamRepository.cleanupBefore
               └── StreamItemDao.deleteOlderThan                            ⚠ (B-5 fragile)
```

Status **✅** for read/write/restore.
**⚠** for cleanup scheduler (B-5: same coroutine-loop fragility,
fixed by DA-5).

---

## Path 8 — SurfaceTray → 7 destinations

`SurfaceId.kt` declares: `Stream`, `Onboarding`, `Pair`, `Vault`,
`Diary`, `Motion` (devOnly), `Whisper`. `PhantomNavGraph.kt:67-87`
declares matching `composable(Routes.<id>.path)` for each:

| Pill | NavGraph destination | Status |
|---|---|---|
| Stream | `:71` `StreamDestination` (current screen) | ✅ |
| Onboarding | `:68` `OnboardingDestination` | ✅ |
| Pair | `:70` `PairDestination` | ✅ |
| Vault | `:75` `VaultDestination` → `VaultScreen` | ✅ |
| Diary | `:77` `DiaryDestination` → `DiaryScreen` | ✅ |
| Motion | `:77-83` `MotionCatalogScreen` (devOnly) | ✅ |
| Whisper | `:84-93` `WhisperPreviewScreen` | ✅ |

No stub destinations remain. Every pill leads to a real Composable.

Status **✅**.

---

## Path 9 — Settings → preferences → reactive UI

`SettingsDialog.kt:86-650` delegates every toggle to a setter on
`VoicePreferences` (Keystore-backed). Each toggle reads a `StateFlow`:

| Toggle | Setter | Reader | Status |
|---|---|---|---|
| TTS on/off | `voicePrefs.setTtsEnabled` | `voicePrefs.ttsEnabled` | ✅ |
| Orb overlay | `voicePrefs.setOrbOverlayEnabled` | `voicePrefs.orbOverlayEnabled` | ✅ |
| Screen Awareness | `voicePrefs.setScreenAwarenessEnabled` | `voicePrefs.screenAwarenessEnabled` | ✅ |
| Whisper overlay | `voicePrefs.setWhisperOverlayEnabled` | `voicePrefs.whisperOverlayEnabled` | ✅ |
| Theme mode | `vm.setThemeMode` (writes SharedPrefs) | `state.themeMode` | ✅ |
| AI keys (Gemini / HF) | `aiKeyStore.saveGeminiKey` / `saveHfToken` | `aiKeyStore.geminiKey` / `hfToken` | ✅ |
| Stream cleanup retention | `voicePrefs.setCleanupAfterDays` | `voicePrefs.cleanupAfterDays` | ✅ |

QH1 (`2e9762e`) confirmed: every keystroke is reactive — there is no
"save" button anywhere. Setter writes immediately; reader's
StateFlow propagates through Compose recomposition.

Status **✅**.

---

## Memory entries — disposition before DA-4

Active: 9 entries in `MEMORY.md`. DA-4 will audit them; pre-audit
notes:

| Entry | Likely disposition |
|---|---|
| `pair_flow_gotchas` | Captured by 4 PIN/CIDR/claim-URL/host-only tests; keep, add «captured by» refs |
| `nsc_cidr_pitfall` | Live invariant in `network_security_config_debug.xml`; keep |
| `phase0_intelligence_gap` | **Mostly resolved by Phase 1-D/1-E/1-F/1-G**; archive to `HISTORICAL_GOTCHAS.md` after DA-3 ships, since `agent.stream` / `chat` / `handoff` consumers are now wired |
| `e2e_playbook` | Operational; keep |
| `material_automirrored_icons` | Verified live: codebase uses `Icons.AutoMirrored.Outlined.InsertDriveFile` (`FileCard.kt:168`); keep as gotcha |
| `chart_widget_dual_shape` | Live tech-debt; keep |
| `host_agent_concurrency` | Operational; keep — directly informs DA-N execution |
| `vault_lock_one_way_invariant` | Live invariant; keep |
| `kdoc_close_inside_backticks` | Live gotcha (Kotlin compiler still reports unclosed comment at file end); keep |

---

## Out of scope this audit

- **MapWidget Phase 1-G** — server `map/*` events arrive at
  `_mapEvents` with no consumer. The MapWidget renders local AI
  intents only. Wiring `_mapEvents` to push remote routes into the
  rendered widget is Phase 1-G work, not Phase 3 closure.
- **Familiar 3D manifestations** — `_familiarState` carries server
  `familiar/manifestation` payloads with no Composable consumer.
  Filament 3D Familiar is on the Tier-2.5 done list but the **WS
  manifestation surface** is Phase 9.
- **`context/preferences_changed` mirror** — `_contextState` snapshot
  is held but the desktop's preference change is already mirrored
  through `profile_sync/preference_changed` (which `_miscEvents`
  catches → no consumer either, but profile-sync has a separate
  surface plan in Phase 1-D).
- **`pair/claimed|revoked` UX** — pairing already routes through the
  REST flow; the WS rebroadcast is a desktop-side
  multi-client-coherence signal that no phone surface needs today.

These four "pipe alive · surface empty" items are tracked in
VISION_NEXT Tier 5 §1-D / §1-G and will land in dedicated phases.

---

## What DA-2..DA-7 will do based on this audit

| Commit | Touches |
|---|---|
| **DA-2** dead code | Empty Phase-0 `Module.kt` markers in `core-context` / `core-sensor` (both `@Deprecated`); any orphan `IncomingCallCard`/`LockVault` infra **kept** (DA-3 will use them); orphan `StreamScreenPreview.kt` Compose previews kept (build-time only) |
| **DA-3** wiring fixes | Wire B-1 (`decision_diary`), B-2 (`lock_vault`), B-3 (`IncomingCallCard` placement); regression tests for each |
| **DA-4** memory + docs | Archive `phase0_intelligence_gap` after B-1 lands; add «captured by …Test» lines to active gotchas; sync ROADMAP / CONTRACT / DESIGN_BRIEF drift |
| **DA-5** WorkManager | B-4 + B-5 — migrate `StreamCleanupScheduler` and `NotificationDigestScheduler` to `PeriodicWorkRequest`; delete coroutine-loop bodies |
| **DA-6** perf baseline | Cold-launch `am start -W`, heap audit, APK size; capture metrics in `docs/PERF_BASELINE.md` (no fixes this Wave) |
| **DA-7** APK + closing | Smoke verify, build APK, Phase 3 closure entry in ROADMAP, write `PHASE_3_CLOSING_REPORT.md` |

End of DA-1.

---

## Slice A milestone — Present Canvas + Presentation Planner (2026-06-28)

- **Build:** `:app:assembleDebug` BUILD SUCCESSFUL with all Slice A changes; ABI-split debug APKs produced (`app-arm64-v8a-debug.apk` 150 MB, `app-x86_64-debug.apk`). Per-module unit suites green (`core-design`, `feature-stream` planner/aggregator/VM/mappers, `core-ai` prompt tests).
- **What landed:** app now boots to the **Present** canvas (Orb + curated, ranked feed; chat is a mode opened from it). Every assistant widget renders through a `PresentationTemplate` chosen by a pure render-time `PresentationPlanner`. `recentAnswers` wired from `streamRepository.lastN`; insights/reminders/environment stubbed empty with a greeting fallback. Optional `present` hint documented in `SystemPrompts`.
- **On-device:** pending — Samsung A56 not attached at build time. Install the arm64 split: `adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`.
- **Known, out-of-scope:** full `assembleDebug` fails at `:wear:mergeExtDexDebug` (R8 8.7.18 dex-merge in the **watch** module; unrelated to Slice A — the phone APK builds clean). Track separately.
