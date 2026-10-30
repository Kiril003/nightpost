# Design v2 — Integration Seam Map (Phase 7)

Every `TODO(integration)` seam introduced by the design-v2 work, its
target core module/API, and status. The design surfaces are built and
green; these are the deliberate "honest stub" points where a labelled
notice / heuristic / mock stands in for a real backend, so wiring can
resume incrementally without blocking the UI.

Generated 2026-05-16 (branch `companion-v2-phase-0`). Convention: a stub
shows a real affordance + a clear "— скоро (TODO)" notice or a
deterministic heuristic — never a fake-working button.

## Seams

| # | Surface / source | Stub today | Target | Status |
|---|---|---|---|---|
| 1 | Smart replies — `SmartReplyHeuristic`, `StreamViewModel.finalizeAssistant` | Deterministic heuristic (0/2/3 chips by question/length) | `:core-ai` AIRouter short follow-up-suggestion prompt | heuristic is the permanent offline fallback; AIRouter call additive |
| 2 | Ghost-text — `ComposerCatalog.ghostFor`, `GhostText` | 4 hard-coded prefix→completion seeds | `:core-ai` AIRouter anticipation (short side-prompt) | heuristic stays as no-key fallback |
| 3 | Mention `@` roster — `ComposerCatalog.PEOPLE`, `MentionPicker` | Mock 5-person roster | `:feature-people` roster (Phase 6 module, not yet created) | blocked on Phase 6 |
| 4 | Attach `+` menu — `StreamEvent.AttachPicked`, `StreamViewModel`, `AttachMenu` | `«долучення «<kind>» — скоро (TODO)»` Notice | `:core-tool` real camera / gallery / file / location / screen pickers + intents | seam ready; pickers TBD |
| 5 | Message action «інший кут» — `StreamEvent.RetryAltAngle` | Info Notice | Re-route the originating user turn with an "answer differently" system nudge (`StreamViewModel` + `:core-ai`) | seam ready |
| 6 | Message action «розгалузити» — `StreamEvent.ForkFrom` | Info Notice | Thread-fork in `StreamRepository` (`:core-data`) | seam ready |
| 7 | Message action «→ phantom-os» — `StreamEvent.SendToPhantomOs` | Info Notice | **No outbound `:core-net` WS path exists** — only inbound Phase 1-G handoff. Needs a new outbound relay. | needs design |

## Wired for real (NOT stubs)

- Message action **copy** → system clipboard (UI-side).
- Message action **→ vault** → `VaultStore.saveEntry` (`:core-data`).
- Thread switch / stop / clear → `⋯` overflow → `StreamEvent`.
- Slash `/` · mention `@` · ghost accept → pure input-text edits via
  `ComposerParse` (unit-tested).
- Cloud tool-calling → `GeminiCloudRoute.toGeminiParameters` adapter
  (flat argsSchema → valid OpenAPI; see `[[gemini_tool_schema_adapter]]`).
- `<think>`-only answer recovery → `AssistantJsonExtractor.recoverThinkOnly`.

## Not yet built (deferred)

- **Phase 6 scaffold modules** `:feature-people` / `:feature-lens` /
  `:feature-magic` — new Gradle modules + nav + DI. Deliberately deferred
  from the unattended run: creating modules / `settings.gradle.kts` /
  nav-graph unattended is the highest build-blast-radius task and a
  break fails the whole build with no one to recover it. Do with a human
  in the loop.
- Visual-fidelity polish of the ~30 existing widget renderers vs the
  bundle JSX — needs eyes-on the device (can't verify appearance
  unattended; the test phone drops off adb intermittently).
