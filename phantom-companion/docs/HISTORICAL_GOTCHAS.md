# Historical Gotchas — archived from active memory

> Memory entries that captured a real problem at the time but have
> since been resolved by code, doc, or test. Kept here so a future
> reader can understand why a particular invariant got hardened, but
> no longer load-bearing for in-flight work.
>
> Archive policy:
>   • A gotcha lands here once the breakage is fixed AND there is a
>     regression test (or doc, or removed-by-deletion guarantee) that
>     stops the same bug from reappearing.
>   • Active memory entries — i.e. things still alive in the
>     `~/.claude/.../memory/` directory — are NOT mirrored here.
>   • Items removed from `MEMORY.md` move to this file with a
>     pointer back to the closing commit.

---

## phase0_intelligence_gap (archived 2026-05-10, closed by DA-3)

**Original entry** (paraphrased from the active-memory file):

> After companion-v2-phase-0 closure, the WS pipe is alive and every
> contract channel routes correctly into typed Kotlin Flows — but the
> Stream surface has no UI consumers for those flows. The app feels
> "dry" next to phantom-os because the engine input is wired and the
> operator output is empty.

**What was wired since**:

| Channel / verb | Surface that consumes it now | Phase landing |
|---|---|---|
| `agent.stream/proactive.cycle` + `proactive.pending_action` | `StreamItem.Insight` via `ProactiveInsightParser` | Phase 1-C |
| `agent.stream/emotion.updated` | `LocalEmotionTone` accent shift via `EmotionToneParser` | Phase 1-D |
| `agent.stream/<any kind>` | `WhisperLineRibbon` overlay via `WhisperEventBus` | Phase 3-E3 |
| `chat/message.proactive` | ASSISTANT `StreamItem.Text` via `ProactiveChatParser` | Phase 1-E |
| `handoff/created|accepted|rejected|cancelled` | `HandoffSheet` bottom-sheet via `HandoffParser` + `StreamViewModel.handoffEvents` | Phase 1-G |
| `companion_control/run_code` (outbound) | `CodeRunnerWidget` → `CodeRunnerActionBus` → `link.send` | Phase 1-F5 |
| `companion_control/lock_vault` | `StreamViewModel.lockVaultEvents` → `vaultStore.lockNow()` + Notice + pulse | **DA-3 (this Wave)** |
| `decision_diary/created|updated|deleted|follow_up` | `DiaryWebSocketSubscriber` → `DiaryStore` → `DiaryScreen` | **DA-3 (this Wave)** |
| `IncomingCallBus.events` | `IncomingCallCard` via new `topOverlay` slot | **DA-3 (this Wave)** |
| `sensor/snapshot` (`bpm`) | `SystemPulseHeader` | Phase 1-A (vitals) |
| `state/transition` | `_state.systemState` → `SystemPulseHeader` | Phase 1-A |

**What is still pipe-alive but surface-empty** (intentional, tracked
in `docs/VISION_NEXT.md` Tier 5):

- `context/preferences_changed` (`_contextState`) — Phase 1-D backlog;
  no UX surface designed yet.
- `familiar/manifestation` (`_familiarState`) — Phase 9 Familiar 3D.
- `pair/claimed|revoked` (`_pairEvents`) — REST flow already routes
  pairing; the WS rebroadcast is desktop-side multi-client coherence
  that no phone surface needs today.
- `map/<op>` (`_mapEvents`) — Phase 1-G MapWidget Live (server-pushed
  routes). Local AI map intents already render.
- `_miscEvents` (`profile_sync`, `drive`, `settings`, `oled`,
  `background_events`, `standing_orders`, `_meta`) — `decision_diary`
  was the load-bearing one and is now wired; the rest are future
  surfaces.

**Closing artefact**: `docs/INTEGRATION_AUDIT.md` (DA-1) lists every
surviving "pipe alive · surface empty" channel with its target
phase, so this gotcha no longer needs to live in active memory.
