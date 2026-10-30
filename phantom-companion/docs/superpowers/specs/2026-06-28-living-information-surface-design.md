# PHANTOM Companion — Living Information Surface (cardinal redesign)

**Date:** 2026-06-28
**Author:** the architect (Claude, PHANTOM's builder)
**Status:** Approved north-star + implementable spec for **Slice A**.
**Operator decision (2026-06-28):** cardinal redesign, not cosmetics. Full delegation to the architect.
**Relates to:** `../../STANDALONE_ENTITY_PLAN_2026-06-27.md` (the standalone-entity backbone — M0 done).
This spec owns the *experience layer*; the standalone plan owns the *organ backbone*. They compose.

---

## 0. The cardinal reframing (the whole point)

Today the app is *"a chat with smart widgets + an Orb."* The chat metaphor is the ceiling. PHANTOM
on the phone must be **a living surface of a present being**, where conversation is one *mode*, not
the whole app. We replace the home metaphor and add a presentation layer over the (already rich)
widget catalog.

This is honest about what already exists (so we build, not restart):
- **Widget catalog is rich and already described to the model** — `core-ai/SystemPrompts.kt`
  enumerates 15 typed widgets (map/weather/route/code-runner/table/chart/diff/comparison/markdown/
  artifact/progress/app-action/task-list/question/reasoning) with schemas, "when to use" rules, and
  a `say` TTS field. The model already knows how to emit them.
- **Design system is strong** — `core-design`: `PhantomTheme`, `OrbView`/`FilamentOrbView`,
  `GlassCard`, `StateAccent`, `StatePill` (6 states), `MotionTokens`.
- **Stream + threads exist** — `feature-stream` (`StreamSurface`, `ThreadRail`, bubbles, ~30 widget
  renderers), `core-data` (`StreamItemDao`, `ThreadMetaDao`).

The gap is not organs — it is **metaphor + presentation + the missing search organ**.

---

## 1. North Star — 5 pillars

1. **Home = "Present", not a chat log.** A living canvas: the Orb (entity state) + a curated feed of
   *Present Cards* (proactive insights, recent answers, environment glance, reminders). Chat opens
   from it as a mode. First thing seen is *presence*, not an empty input field.
2. **Presentation as the core paradigm.** A `PresentationPlanner` chooses a *cohesive visual
   template* per answer — not just a widget. A weather answer, a research result with sources, an
   environmental alert, and a memory recall each look distinctly different (atmosphere, accent,
   density, motion). This is the operator's "мегарізні дизайни під різне."
3. **Search as a first-class mode.** Web (Gemini grounding) + memory (semantic) + environment, with
   a beautiful sources surface and "save to memory." `/search` and `/memory` become real.
4. **Memory/History reimagined** — not scrollback but a *chronicle of the relationship*: searchable
   by topic/place/day, auto-titled threads, the entity's own annotations.
5. **Embodied co-pilot** (later slices) — sees screen + camera + environment, acts via godmode,
   warns proactively (e.g. "air quality is bad where you are").

### Decomposition into spec→plan cycles
- **A. Present Canvas + Presentation Planner** ← *this spec implements A.*
- **B. Search mode** (web+memory tools + sources surface) — own spec.
- **C. Chronicle** (history/threads as relationship memory) — own spec.
- **D+. Senses, hands, proactivity** (godmode/camera/screen/alerts) — own specs, build on A.

A is first because it replaces the home metaphor and becomes the frame B/C/D render through.

---

## 2. Slice A — architecture

Two isolated components. Each: one purpose, a defined interface, independently testable.

### A1. Present Canvas (`feature-stream/ui/present/`)

A new top-level surface the app opens to. **Not** a chat scrollback — a curated, glanceable canvas.

- `PresentScreen` (Composable) — vertical layout:
  - **Orb header** — reuse `OrbView`/`FilamentOrbView` + `StatePill`; reflects entity state.
  - **Present feed** — a curated list of `PresentCard`s, newest-relevant first.
  - **Mode entries** — open Chat (`StreamSurface`), open History/Search (placeholder hook for
    Slice C), voice (`PttRow`/`VoiceModeOverlay` already exist).
- `PresentViewModel` — aggregates `PresentCard`s from existing sources (no new persistence yet):
  - recent assistant answers (`StreamItemDao.lastN`),
  - proactive insights (`ProactiveInsightParser` / `InsightCardBubble` already exist),
  - an environment-glance card from current context providers (`LocationContextProvider`,
    `MoodContextProvider` — read-only; richer sensing is Slice D),
  - reminders/pending (`PendingIntentDao`).
- `PresentCard` (domain) — `{ id, kind, template, title, body/widgetRef, timestamp, source }`.
  Pure data; rendering is the Planner's job (A2). Aggregation/ranking is pure and unit-tested.
- **Navigation:** `PresentScreen` becomes the start destination; `StreamSurface` (chat) and the
  existing screens (diary/vault/map) are reached from it. The 6-state / Orb / Stream mandate from
  `README` §1 is preserved — Present *is* the Stream-philosophy home, not a 5-tab IA.

### A2. Presentation Planner (`feature-stream/domain/present/` + `core-design`)

A pure layer that wraps every assistant turn (and every Present Card) in a cohesive *template*.

- `PresentationTemplate` (enum/sealed in core-design) — a named visual treatment, each a bundle of
  design tokens: accent, glass level, corner radius, density, motion profile, header glyph, optional
  ambient tint. Initial set (extensible):
  `Answer`, `Research`, `Alert`, `Memory`, `Vital`, `Action`, `Reflection`, `Plain`.
- `PresentationPlanner` (pure, `feature-stream/domain/present/PresentationPlanner.kt`) —
  `plan(intent, widgetType, contentSignals, modelHint?) → PresentationTemplate`.
  - The model MAY emit an optional top-level `"present":"research"` hint; the planner honors it when
    valid, else **infers** from widget type + content signals (e.g. `search-result`→Research,
    sensor/vitals→Vital, environmental warning→Alert, recall→Memory, app-action→Action).
  - Tolerant + backward-compatible: absent hint never breaks rendering (the
    `chart_widget_dual_shape` memory pattern — accept old shapes, prefer new).
- `core-design` gains template tokens: extend `PhantomTheme`/`StateAccent`/`GlassCard`/`MotionTokens`
  so a template resolves to concrete chrome. One design language, no bespoke per-widget styling.
- Rendering: `WidgetBubble`/`AssistantBubble`/`PresentCard` render *inside* the chosen template's
  chrome (a `TemplateScaffold` Composable in core-design wraps content with accent/glass/motion).
- `SystemPrompts.kt`: document the optional `present` hint + the template intents, so the model can
  steer presentation deliberately (without ever being required to).

### Data flow
```
model turn ──▶ existing widget/text parse (ComposerParse) ──▶ (widgetType, content, present?hint)
            └▶ PresentationPlanner.plan(...) ──▶ PresentationTemplate
                                              └▶ TemplateScaffold(template){ WidgetRenderer / text }
Present feed ◀── PresentViewModel.aggregate(stream, insights, context, pending) ──▶ PresentCard[]
```

### Error handling
- Unknown/invalid `present` hint → planner falls back to inferred template (never throws).
- Unknown widget type → existing `FallbackWidget` (unchanged) inside `Plain` template.
- Empty Present feed → a warm empty-state Present Card (entity greeting), not a blank screen.
- Aggregation source failure (e.g. context provider returns null) → that card is skipped, feed
  still renders. No single source can blank the canvas.

### Testing (TDD-first; the repo already has a strong unit suite)
- `PresentationPlannerTest` — every (intent, widgetType, hint) → expected template; invalid hint
  falls back; backward-compat shapes.
- `PresentViewModelTest` — aggregation/ranking, source-failure isolation, empty state.
- `PresentCard` mapping tests.
- Compose: previews per template (follow `StreamScreenPreview` pattern); full APK verified on the
  Samsung A56 at the slice milestone.

---

## 3. Constraints & guardrails (carried from project memory)
- **Build cadence:** `assembleDebug` is ~13 min and freeze-prone on the Radxa host — fast loop is
  unit tests; full APK only at milestones, supervised. (`host_agent_concurrency`: no parallel
  subagents on this host — serialize.)
- **No silent reverts of verbal transparency** (`voice_verbal_transparency_invariant`).
- **AutoMirrored Material icons** where applicable (`material_automirrored_icons`).
- **Any background/retention job uses WorkManager**, not coroutine loops
  (`workmanager_for_oem_battery_kill`).
- **Privacy:** Present cards sourced from vault/private context stay local; never leaves device.
- **Files < 500 lines; one design language; honest stubs over fake-working buttons.**

## 4. Out of scope for Slice A (named, deferred)
- Real web/memory search tools + sources surface → **Slice B**.
- History/chronicle surface, FTS5, thread management → **Slice C**.
- Camera/screen perception, godmode hands, proactive environmental alerts → **Slice D+**.
- On-device LLM, soul-sync → the standalone-entity backbone (M3/M4).

## 5. Definition of done (Slice A)
- App opens to `PresentScreen`; Orb + a curated, non-empty Present feed render; chat is a mode
  reached from it.
- Every assistant turn renders through a `PresentationTemplate`; at least the 8 initial templates
  are visually distinct and applied by the planner.
- Planner + aggregation unit tests green; `assembleDebug` builds; APK runs on the phone with the new
  home as start destination.
