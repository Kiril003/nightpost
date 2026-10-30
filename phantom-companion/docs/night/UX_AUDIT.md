# PHANTOM Companion — UX/UI audit

Audit mode, read-only, 2026-07-26. Scope: `core-design/`, `app/`, `feature-*`.
`.claude/worktrees/` excluded throughout. No source modified, no gradle run.

**Verdict in one line.** The design system is *written* but not *enforced*: an
excellent token layer sits next to ~4,500 raw literals that bypass it. The
product does not look like a student project because nobody thought about it —
it looks that way because three or four different design doctrines were each
implemented sincerely and none was ever removed. The chat surface carries three
competing container languages, the card system carries two, and the type scale
carries 29 distinct font sizes against a declared 8.

---

## 1. Design system reality check

### 1.1 What actually exists

`core-design/src/main` — 40 files, 4,393 lines. The token layer is real:

| Layer | File | Content |
|---|---|---|
| Colour | `theme/PhantomTheme.kt:54-198` | `PhantomThemeSpec` — 18 semantic slots × 5 palettes (`SunriseWarm`, `AmberNight`, `DawnBloom`, `DayClear`, `DuskEmber`) |
| Type | `theme/PhantomTheme.kt:342-406` | M3 `Typography` override, 8 explicitly specified roles |
| Type roles | `theme/PhantomTextRoles.kt` | 5 composables: `PhantomKicker` (10sp), `PhantomCaption` (11sp), `PhantomGlyph` (17sp), `PhantomDataText` (12sp mono/tnum), `PhantomProse` (16/24) |
| Spacing | `theme/PhantomSpacings.kt:9-19` | 8 steps: 0/4/8/16/24/32/40/48 |
| Radii | `theme/PhantomRadii.kt:38-66` | 6 steps + `Pill` + `SheetTop`, with pre-allocated shapes |
| Motion | `motion/MotionTokens.kt` | ~25 named durations/springs, 7 "verbs", documented asymmetry law (exit < enter) |
| Elevation | — | **Does not exist.** No `PhantomElevation`. Depth is faked per-site via `GlassCard`'s hardcoded inner shadow (`GlassCard.kt:102-113`) and per-widget `drawBehind` |

The colour slots are genuinely semantic — `ink`, `inkSecondary`, `inkStrong`,
`glassPanel`, `glassBorder`, `success`, `userBubbleBg/Fg`, `accentSharp`. That
is a real system. `StateAccent.kt:69-124` layers a 6-state accent matrix on top.

### 1.2 Judgement: is there a real type scale?

**No.** There is a real type scale *declaration* and an ad-hoc reality.

The declared ramp (`PhantomTheme.kt:302-306`) is 36 / 32 / 20 / 18 / 16 / 14 /
12 / 11. Sensible. But `MaterialTheme.typography` is referenced only **36 times
in the entire app+features**. Meanwhile there are **851 hardcoded
`fontSize = N.sp`** call sites across **29 distinct sizes**:

```
11(180) 12(135) 13(104) 10(95) 14(67) 9(56) 16(40) 10.5(26) 15(22) 11.5(17)
18(15) 8(14) 12.5(12) 9.5(9) 8.5(9) 32(8) 24(8) 22(8) 20(7) 13.5(6) 28(4)
34(2) 7.5 46 40 36 21 19 17 (1 each)
```

Half-point sizes (7.5, 8.5, 9.5, 10.5, 11.5, 12.5, 13.5) are a tell: they were
tuned per-screen by eye to make one specific string fit. `13.sp` appears 104
times and is not in the scale at all. Anything below 11sp — 9sp (56), 10sp (95),
8sp (14), 7.5sp — is below the readable floor for a field device.

The named roles are barely used: `PhantomProse` **6 times**, `PhantomDataText`
19, `PhantomCaption` 22, `PhantomKicker` 34. The flagship prose renderer does
not use `PhantomProse` — `items/AssistantProse.kt:193-195` re-types 16sp/24sp
by hand.

`labelLarge` letter-spacing is `4.sp` at 12sp (`PhantomTheme.kt:396`). At that
tracking Ukrainian words break into disconnected glyphs; it is why no screen
uses it and everyone hand-rolls a kicker instead.

### 1.3 Judgement: are colours semantic or raw?

Semantic in `core-design`, raw everywhere else. **152 `Color(0x…)` literals
outside `core-design`.** Worst offenders:

| Count | File |
|---|---|
| 17 | `feature-stream/…/ui/widget/PhotoHeroWidget.kt` |
| 13 | `feature-stream/…/ui/items/CouncilDebateCard.kt` |
| 9 | `feature-stream/…/ui/map/MapChromeParts.kt` |
| 8 | `feature-stream/…/ui/map/MapToolsCapsule.kt` |
| 7 | `feature-stream/…/ui/map/SolarSky.kt`, `…/ui/map/EwHud.kt` |
| 6 | `…/widget/ImageGenInProgressWidget.kt`, `…/widget/FileTransferWidget.kt`, `…/widget/DiffWidget.kt`, `…/whisper/WhisperPreviewScreen.kt`, `…/ui/ChatTints.kt` |

`ChatTints.kt` is the one *defensible* raw-colour file (documented exception,
6 hues, semantic names) — but its hues were only checked against the dark
palette; see §6.

### 1.4 Dimension leakage

**2,472 `.dp` literals** in app+features against **98** `PhantomSpacings` and
**147** `PhantomRadii` references. The distribution proves the scale has the
wrong rungs:

```
8(306) 12(209) 6(208) 16(208) 10(205) 14(158) 4(143) 0.5(132) 2(106) 24(91)
18(84) 20(79) 1(56) 3(52) 5(44) 7(31) 28(25) 999(23) 32(22) …
```

`12.dp` (209 uses) and `20.dp` (79) are the 2nd and 12th most common values and
**neither is in `PhantomSpacings`**. So the "mathematically rigid 8px grid"
(`PhantomSpacings.kt:7`) is not what the app is built on — the app is on a
2px grid with no rhythm.

`999.dp` appears **23 times** as a fake pill radius (`widget/LivingCard.kt:242,
247`; `widget/CanvasWidget.kt:190,191,230,237`; `widget/PhotoHeroWidget.kt:304,
359`; `live/VoiceModeOverlay.kt:181,186,203`; `live/TurnTrace.kt:365,367`;
`live/IntentDock.kt:47`; `history/HistorySearchScreen.kt:207,276`; …) despite
`PhantomRadii.Pill` existing precisely for this. 317 raw `RoundedCornerShape(`
constructions total.

Worst combined raw-token files (dp + sp + hex, single file):

| Tokens | File |
|---|---|
| 101 | `feature-stream/…/ui/map/MapChromeParts.kt` |
| 94 | `app/…/ui/settings/MapSettingsScreen.kt` |
| 82 | `feature-stream/…/ui/map/MapSearchSheet.kt` |
| 79 | `feature-stream/…/ui/map/MapToolsCapsule.kt` |
| 77 | `feature-stream/…/ui/live/TurnTrace.kt` |
| 72 | `feature-stream/…/ui/report/ReportCharts.kt`, `…/map/nav/GuidanceChrome.kt` |
| 70 | `feature-stream/…/ui/map/MapCommandSheet.kt` |
| 66 | `feature-stream/…/ui/ComposeRow.kt` |
| 64 | `feature-stream/…/ui/vault/VaultScreen.kt` |

### 1.5 Density-independence bug

`GlassCard.kt:96-98` draws the signature "lit-from-above" top highlight with
**raw pixels**, not dp: `start = Offset(0f, 1.5f)`, `strokeWidth = 2.5f`. Same
at `GlassCard.kt:64` (`Offset(0f, 4f)`). On a 1x device that is a 2.5px line;
on a 3.5x phone it is still 2.5px — i.e. the card's defining visual detail
gets ~3× thinner as screens get denser. `PhantomIsland.kt:168-171` copies the
same bug into the navigation rail.

### 1.6 Copy is not a system either

**~2,079 Cyrillic string literals** are hardcoded in UI Kotlin, against **51**
`stringResource` calls and 160 lines of `strings.xml` total. Consequences that
are visible, not theoretical:

- Mixed capitalisation in the same row: `ComposeRow.kt:559` —
  `"зупинити відповідь"` vs `"Надіслати"`.
- The same concept spelled differently across files (`"скасувати"`,
  `"відхилити"`, `"закрити"`).
- `values-en/strings.xml` exists (31 + 30 lines) so an English build renders
  ~98% Ukrainian anyway.

---

## 2. Screen inventory

46 `@Composable …Screen(` / `…Pane(` declarations. Reachability:

### 2.1 Reachable

| # | Surface | Entry | What it shows |
|---|---|---|---|
| 1 | `PhantomLoaderScreen` (`core-design/component/PhantomLoader.kt`) | app start | breathing amber core while `profileStore` resolves |
| 2 | `OnboardingScreen` + `NameStep`/`PinStep`/`PermissionExplanationScreen`/`PairOffer`/`PairScan`/`PairClaiming`/`PairFailed` | fresh install | profile creation, PIN, permissions, optional Radxa pair |
| 3 | `ProfileLauncherScreen` | profiles exist, none active | profile picker |
| 4 | **`StreamScreen`** (Continuum · Dialogue) | home | the chat — flagship |
| 5 | `MapScreen` (Continuum · World) | island «Мапа», pull-down from chat | MapLibre map + chrome |
| 6 | `PresentScreen` (Continuum · Presence) | camera rack only (no island slot) | presence hub, feed cards |
| 7 | `VaultScreen` | island «Vault» | biometric-gated secrets |
| 8 | `SettingsRealm` | island «⚙» | settings hub |
| 9–14 | `DiagnosticsScreen`, `VoiceSettingsScreen`, `MicPrivacyScreen`, `MapSettingsScreen`, `SymbioteSettingsScreen`, `DataLicensesScreen` | Settings rows | sub-pages |
| 15 | `PrecisionScreen` | Diagnostics | GNSS constellation / spoof verdict |
| 16 | `PairScreen` | Settings → pair | QR claim |
| 17 | `HistorySearchScreen` | chat header `⋯` | semantic search |
| 18 | `SpacesManagementScreen` | chat header `⋯` | thread rename/delete |
| 19 | `SurfaceTray` | chat header **long-press** | hidden surface pills |
| 20 | `ConstellationScreen` | orb **long-press** → «ще…» petal | 8 spaces |
| 21–26 | `LensScreen`, `DayScreen`, `PeopleScreen`, `CreatorCanvasScreen`, `BodyVitalsScreen`, `HandsAutomationScreen` | orb long-press ring / Constellation | the chambers |
| 27 | `LifeGraphScreen` | Constellation «Пам'ять» | memory graph |
| 28 | `DiaryScreen` | route only — **no UI entry found** | decision diary |
| 29 | `WhisperPreviewScreen` | route only — no UI entry found | whisper overlay preview |
| 30 | `MotionCatalogScreen` | debug only | animation catalogue |

### 2.2 Orphaned (compiled, unreachable, zero references outside their own file)

- `app/src/main/java/local/phantom/companion/ui/settings/PermissionsScreen.kt` —
  **a whole permissions UI that no route reaches.** This is why §5 finds no
  permission story.
- `app/src/main/java/local/phantom/companion/ui/settings/NotificationSettingsScreen.kt`
- `app/src/main/java/local/phantom/companion/ui/settings/WhisperSettingsScreen.kt`
- `feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/spaces/DeepAgentScreen.kt`

Plus `InputBar.kt` (250 lines) and `PttRow.kt`, both superseded by `ComposeRow`
and still carrying their own `CircularProgressIndicator` styling.

### 2.3 Top-3 defects per reachable surface

**StreamScreen** — see §3 in full. Headline three:
1. `StreamScreen.kt:601-608` — every message older than `RECESSION_SPAN` is
   scaled and faded by construction. The operator's own history is progressively
   dimmed for a metaphor; on a bright day the top half of the screen is unreadable.
2. `StreamScreen.kt:1355-1361` — the pin indicator is a literal `"📌"` emoji at
   `12.sp` absolutely positioned at `TopEnd`. A colour emoji is the only
   full-saturation element in an amber-monochrome UI.
3. `StreamScreen.kt:552-571` — 24dp horizontal gutter + 8dp uniform item spacing
   + 8dp extra on group start. There is no rhythm difference between "same
   speaker, next line" and "new speaker" beyond 8dp.

**MapScreen (World stratum)**
1. `ui/continuum/ContinuumHost.kt:318-322` — system Back from Dialogue racks to
   WORLD and Back on WORLD is `Unit`. **The app cannot be left with the Back
   button**, and Back on the home screen navigates *into* the map.
2. `ui/map/MapToolsCapsule.kt:393,450,565,573` — `IconButton(modifier =
   Modifier.size(20.dp))` / `22.dp`. Sub-half-size touch targets on the surface
   most likely to be used with gloves in a moving vehicle.
3. `ui/map/MapChromeParts.kt` — 101 raw tokens, 9 hardcoded hexes: the map
   chrome has effectively seceded from the design system.

**VaultScreen**
1. `ui/vault/VaultScreen.kt:182,242,353,424` — 9sp text. Below the legibility
   floor, on the screen holding credentials the operator must transcribe.
2. `ui/vault/VaultScreen.kt:530` — biometric prompt strings hardcoded
   (`"скасувати"`), inconsistent with everything else.
3. 64 raw tokens; no `LivingCard`/`GlassCard` — the vault list invents its own
   row container, so it reads as a different app.

**SettingsRealm**
1. `ui/settings/AssetsPane.kt:183-207,403-430` — eight `IconButton`s at 36dp and
   28dp, all below the 48dp minimum, in the model-download list.
2. `ui/settings/MapSettingsScreen.kt` — 94 raw tokens, 45 `.sp` literals in one
   settings screen: no shared row component is being used.
3. Settings is a single scrolling `Column` of panes (`SettingsRealm.kt:113+`)
   with no search, no sections index, and ~9 sub-routes — unnavigable once it
   grows past a screenful.

**PresentScreen (Presence)** — no island slot points at it; it is reachable
only by a camera rack most users will never perform. 48 raw tokens. It duplicates
the chat's job (feed of cards) with a different card language.

**OnboardingScreen** — `OnboardingScreen.kt:223` uses `24.sp` for the step
title, `185/230` use `14.sp` body; neither maps to a role. No progress
indicator across the 5–7 steps.

**PrecisionScreen** — the strongest-looking screen in the app (real data
visualisation, real purpose) and it is buried three levels deep under
Settings → Diagnostics → Precision. This is the app's differentiator and it is
invisible.

---

## 3. The chat screen, in detail

Files: `StreamScreen.kt` (1,745 lines), `ComposeRow.kt` (913),
`items/*` (12 renderers), `widget/*` (47 files).

### 3.1 Message bubbles — three container doctrines at once

The code's own comments record a pendulum that never stopped swinging
(`items/OperatorImprint.kt:27-85` is a five-paragraph apology for it):

| Element | Container | Radius | Type |
|---|---|---|---|
| Assistant prose | `GlassCard(level = Card)` (`AssistantProse.kt:134-138`) | 16dp | 16/24 hardcoded |
| Operator turn | bespoke solid fill, `OperatorPaneShape` (`OperatorImprint.kt:185-190`) | **18/18/18/6** — off-scale | 15sp Medium / 22 |
| Widget | `LivingCard` → `GlassCard(Card)` (`LivingCard.kt:117`) | 16dp | 11sp caps title |
| Widget (16 of them) | bare `GlassCard` | 16dp | whatever the file chose |
| Notice / SystemEvent / LifeLog | own containers | various | various |

Assistant prose sits in the *same* glass card as every widget, with the *same*
16dp radius and the *same* fill. So on screen, a paragraph of PHANTOM's answer
and a weather card are the same object. There is no visual grammar separating
"speech" from "artifact" — which is the single most important distinction this
product has.

### 3.2 Spacing rhythm

- Timeline gutter 24dp (`StreamScreen.kt:567-568`), item gap 8dp
  (`:571`), +8dp on speaker change (`:609`). Speaker change is therefore a
  **16dp** break vs **8dp** — a 2:1 ratio the eye cannot resolve at 16sp text.
- Operator turn adds its own asymmetric `top = 20.dp, bottom = 4.dp`
  (`OperatorImprint.kt:110`) — so the rhythm is 8 / 16 / 20 / 24 / 28 depending
  on which two things are adjacent. Non-deterministic vertical rhythm.
- `AssistantProse` inner padding `horizontal = 16.dp, vertical = 13.dp`
  (`:142`) — 13 is not on any grid.

### 3.3 Avatars

**There are none.** Authorship is carried by alignment + fill only. For a
product whose whole pitch is a *companion with a presence*, PHANTOM has no face
in his own conversation. The 28dp mini-orb exists but lives in the header
(`live/LivePresenceHeader.kt`) and scrolls away.

### 3.4 Timestamps

`items/BubbleFooter.kt:57-63`: mono, **9.5sp**, `inkSecondary` at **70% alpha**.
Measured contrast on the night card: **3.87:1** — below AA for any size, and
this is 9.5sp. In daylight it is invisible. `BubbleFooter.kt:50-55` renders the
separator `·` at 9sp in the same washed tint.

Every message that has `meta` shows a timestamp; there is no
"only show on speaker/time change" rule, so a rapid exchange produces a column
of identical `14:32 · 14:32 · 14:32`.

### 3.5 Typography hierarchy inside the stream

Distinct sizes visible in one scroll of the chat: 8 (`AssistantProse.kt:112`
`· ПРОАКТИВНЕ`), 9 / 9.5 (footer), 10 (`TimeRib`, `StreamScreen.kt:1634`), 11
(restore skeleton, `:1677`), 11 caps (LivingCard title), 12 (act pill), 13
(`AssistantProse.kt:160,280` — the empty/pending states), 13.5 (LivingCard
essence), 15 (operator turn), 16 (assistant prose). **Ten sizes, no scale.**

### 3.6 The composer bar

`ComposeRow.kt:348-576`.

- Two mutually exclusive bottom elements: the island (`PhantomIsland.kt:463`
  hides itself when `composerExpanded`) and the composer. The swap is a
  presence-crease animation (`MotionTokens.CREASE_DURATION_MS = 180`) — this
  part is genuinely good.
- Placeholder is hardcoded and *conditional on a layout fact*
  (`ComposeRow.kt:454-455`): `"напиши або тримай мікрофон"` vs `"напиши запит"`,
  at **14sp** while the input text is 16sp — the placeholder is a different size
  from the text that replaces it, so the field visibly jumps on first keystroke.
- Attach button is **40dp** (`ComposeRow.kt:629`) — below 48dp.
- Send button is 48dp inside a 48dp `Box` (`:523-553`) — correct — but its
  `contentDescription` is hardcoded and inconsistently capitalised (`:559`).
- `BrainLine` (`:596-617`) renders a status sentence at **10.5sp** under the
  bar — the app's only "model is downloading" signal, at the smallest type in
  the product.
- Ghost-completion accept affordance is the character `"→"` at 16sp with
  `padding(horizontal = 8, vertical = 4)` (`:500-509`) — roughly a 32×24dp
  target.

### 3.7 Streaming / typing states

Three different "PHANTOM is working" indicators can be on screen at once:
1. `ThinkingBreath` — 3 pulsing beads (`AssistantProse.kt:234-260`).
2. Blinking block caret painted in the draw phase (`AssistantProse.kt:197-208`).
3. `MeridianLine` pulse in the header (`StreamScreen.kt:711-715`).

Plus a tool-trace pulse. `StreamScreen.kt:173-189` contains a hand-written
filter whose only job is to *suppress* one of them — an admission that the
states were never designed as a set.

The streaming jitter (`AssistantProse.kt:173-185`) alpha-ramps the last 6
glyphs. Nice idea; it is rebuilt on every token via `remember(body)`, so it
allocates an `AnnotatedString` per token.

### 3.8 Empty state

`StreamScreen.kt:1685-1703` → `HeroWelcome`. It is the best-looking thing in the
app, and it has two hard bugs:
- `items/HeroWelcome.kt:47` — `name: String = "кирил"`. **The owner's name is a
  compile-time default.** Every user is greeted as Kyrylo.
- `items/HeroWelcome.kt:56-58` — the hour is `remember`ed once per composition,
  so the greeting never changes while the app is open, including across a
  dawn/day/dusk boundary that the theme itself animates.
- Prompt chips are 38dp tall (`:161`) — below 48dp.

### 3.9 Error states

`AssistantProse.kt:277-282` — the failure copy is a hardcoded lowercase
sentence: `"порожня відповідь · спробуй переформулювати"`, 13sp, 70% alpha,
**no retry affordance**. `WidgetRenderer.kt:289` — unknown card renders
`"невідома картка · $type"`. `NoticeBubble` is the only structured error object
and it carries no action either.

### 3.10 Scroll behaviour

This is the strongest engineering in the file and it is worth saying so:
follow-tail-only-when-at-bottom (`:205-251`), per-thread `LazyListState`
(`:164-165`), unread delta counting that survives batch arrivals (`:216-241`),
a translating (never re-laying-out) header (`:276-277, 659-675`), fading edges
(`:548-551`). All correct.

Two defects remain:
- `StreamScreen.kt:292-348` — the pull-to-exit `NestedScrollConnection` steals
  **all** downward overscroll at the top of the list, so the natural
  "bounce to see the oldest message" gesture instead arms a navigation. At
  180px it commits (`:298`) with no confirmation.
- `:601-608` recession — discussed above.

---

## 4. The card system

### 4.1 Inventory

`WidgetTypes` (`widget/WidgetRenderer.kt:18-97`) declares **46 types**;
`WidgetRenderer` (`:121-281`) dispatches all of them (2 alias to `ComparisonWidget`).

| Type | Renderer |
|---|---|
| `timer` | `widget/TimerWidget.kt` |
| `weather` | `widget/WeatherWidget.kt` |
| `route` | `widget/RouteWidget.kt` |
| `chart` | `widget/ChartWidget.kt` |
| `code-runner` | `widget/CodeRunnerWidget.kt` |
| `map` | `widget/MapWidget.kt` |
| `ar-map` | `widget/ArMapWidget.kt` |
| `task-list` | `widget/TaskListWidget.kt` |
| `question` | `widget/QuestionWidget.kt` |
| `reasoning` | `widget/ReasoningWidget.kt` |
| `vitals-constellation` | `widget/VitalsConstellationWidget.kt` |
| `code-block` | `widget/CodeBlock.kt` |
| `file-card` | `widget/FileCard.kt` |
| `voice-memo` | `widget/VoiceMemo.kt` |
| `tool-trace` | `widget/ToolTrace.kt` |
| `citation` | `widget/CitationPill.kt` |
| `app-action` | `widget/AppActionWidget.kt` |
| `markdown` | `widget/MarkdownWidget.kt` |
| `table` | `widget/TableWidget.kt` |
| `artifact` | `widget/ArtifactWidget.kt` |
| `progress` | `widget/ProgressWidget.kt` |
| `diff` | `widget/DiffWidget.kt` |
| `comparison`, `comparison-table` | `widget/ComparisonWidget.kt` |
| `agent-progress` | `widget/AgentProgressWidget.kt` |
| `image-gen-in-progress` | `widget/ImageGenInProgressWidget.kt` |
| `photo-hero` | `widget/PhotoHeroWidget.kt` |
| `audio-waveform` | `widget/AudioWaveformWidget.kt` |
| `file-transfer` | `widget/FileTransferWidget.kt` |
| `spreadsheet-preview` | `widget/SpreadsheetPreviewWidget.kt` |
| `model-3d` | `widget/Model3DCardWidget.kt` |
| `attachment` | `widget/AttachmentWidget.kt` |
| `briefing`, `stat-hero`, `citation-answer`, `aqi-gauge`, `checklist`, `digest`, `memory-echo`, `alert-card`, `trip-pack` | `facet/InfoFacets.kt`, `facet/SignalFacets.kt` |
| `canvas` | `widget/CanvasWidget.kt` |
| `calendar` | `widget/AgendaWidget.kt` |
| `reminder` | `widget/ReminderWidget.kt` |
| `places` | `widget/PlacesWidget.kt` |
| `report` | `report/ReportWidget.kt` (+ 12-file report subsystem) |
| *(unknown)* | `WidgetRenderer.kt:284-295` fallback |

### 4.2 One card language, or 46?

Neither — **two, plus orphans**:

| Container | Renderers |
|---|---|
| `LivingCard` (header chip + title + freshness + body + act-bar) | 17: Agenda, AgentProgress, AudioWaveform, Canvas, Chart, Comparison, FileCard, FileTransfer, Places, Progress, Question, Reminder, Route, Table, TaskList, VoiceMemo, Weather — plus `FacetScaffold` and 3 in `SignalFacets` |
| bare `GlassCard` (no header, no acts, no freshness) | 16: AppAction, ArMap, Artifact, Attachment, CodeBlock, CodeRunner, Diff, ImageGenInProgress, Map, Markdown, Model3D, PhotoHero, Progress, SpreadsheetPreview, Timer, VitalsConstellation |
| neither | 4: CitationPill, ReasoningWidget, ToolTrace, WorkspaceCanvas |

So slightly more than half the cards have a header, a freshness dot and a
refresh action; the rest have none of those. `ProgressWidget` uses **both** in
the same file. That is the visual inconsistency the owner is reacting to.

### 4.3 `LivingCard` judged

`widget/LivingCard.kt:89-232`. The *anatomy is right* — icon chip, title,
freshness dot + relative time, optional essence line, disclosure, body,
divider, horizontally-scrolling act pills, auto-injected «оновити» when stale.
That is a professional card spine. The execution has five defects:

1. **`:150-155` — the title is `accent`-coloured, uppercase, 11sp Bold,
   `letterSpacing = 1.6.sp`.** On the light palettes `accent` is
   `#F4AF25` on `#FFFDF6` = **1.87:1** (DayClear) and **2.17:1** (DawnBloom).
   The title of every card in the product is illegible in daylight. This alone
   makes the app fail its own "field-grade" premise.
2. **`:112` — `remember(timestampMs) { System.currentTimeMillis() }`.** "Now" is
   frozen at first composition, so a card that said «щойно» says «щойно» for
   the rest of the session, and `stale` never flips, so the «оновити» act never
   appears.
3. `:177` — the disclosure chevron is the CJK glyph pair `"︿"` / `"﹀"`, not an
   icon. Renders differently per device font and has no `contentDescription`.
4. `:242,247` — `RoundedCornerShape(999.dp)` instead of `PhantomRadii.Pill`.
5. `:249-253` — the act pill's tap target is `padding(12, 7)` around a 14dp
   icon + 12sp label ≈ **28dp tall**. Every card action is under-sized.
   `:250` fires `HapticFeedbackType.LongPress` on a *tap*.

### 4.4 Strongest cards

- `report/*` (12 files) — sections, TOC rail, progress header, a real
  `ReportSkeleton` (`report/ReportHydration.kt:160`). The only widget with a
  designed loading state.
- `WeatherWidget`, `RouteWidget`, `PlacesWidget` — clean `LivingCard` usage with
  real acts.
- `map/dossier/PlaceDossierSheet.kt:134,333,360` — proper skeleton blocks.

### 4.5 Embarrassing cards

- `PhotoHeroWidget.kt` — 17 hardcoded hexes, 56 raw tokens, two `999.dp` pills:
  it does not read as part of this app.
- `items/CouncilDebateCard.kt` — 13 hexes, 16 `.sp` literals, its own container.
- `CanvasWidget.kt` — 21 `.sp` literals, 7 `RoundedCornerShape`, 4 of them `999`.
- `WidgetRenderer.kt:284-295` fallback — a naked grey sentence in a card, no
  icon, no action, no report path. This is what the operator sees when the model
  emits a card type the build doesn't know.
- `ToolTrace` / `ReasoningWidget` / `CitationPill` — no container at all, so
  tool plumbing floats in the timeline with no left edge to align to.

---

## 5. Missing states

| State | Reality |
|---|---|
| **Loading — chat** | `StreamScreen.kt:1671-1683` "skeleton" is one centred line of 11sp text. Not a skeleton. |
| **Loading — everything else** | 36 `CircularProgressIndicator` / `LinearProgressIndicator` call sites. The stock Material spinner, in an app whose own brief says «Орб дихає, не спінер», and which ships `PhantomLoader` for exactly this. |
| **Loading skeletons** | Exist in exactly 2 places: `report/ReportHydration.kt:160`, `map/dossier/PlaceDossierSheet.kt:134`. Nowhere else — Vault, Settings/Assets, HistorySearch, LifeGraph, Agenda all pop from blank to full. |
| **Empty — chat** | `HeroWelcome`, good, but hardcoded name + frozen hour. |
| **Empty — everywhere else** | No shared empty-state component exists. 123 `isEmpty()` branches in UI, each hand-writing a sentence or rendering nothing. |
| **Error** | No error component. `NoticeBubble` (65 lines) carries severity but no action. No retry anywhere in the chat. `AssistantProse.kt:278` is a dead-end sentence. |
| **Offline** | The single offline signal in the whole product is `ComposeRow.kt:602` — `BrainLine` printing `"чекаю на мережу…"` at 10.5sp. For an **offline-first** app there is no offline *identity*: nothing tells the operator "this works right now, without a network" — which is the product's entire competitive claim. |
| **First-run / onboarding** | Exists (`feature-onboarding`, 7 screens, 1,742 lines) but has no progress indicator, no skip, and hands off directly to an empty chat. There is no moment that teaches the island's 5 slots, the orb's two gestures (tap = live, hold = PTT — `PhantomIsland.kt:276-280`), the long-press menus, or the pull-down-to-map. **Every navigation gesture in this app is undiscoverable.** |
| **Permissions** | `PermissionsScreen.kt` is fully written and **unreachable** (§2.2). The live path is `rememberRecordAudioPermissionGate()` firing the raw system dialog with no rationale screen. `PermissionExplanationScreen.kt` exists in onboarding but covers only the onboarding-time set. |
| **Stale data** | `LivingCard` has the mechanism and it never fires (§4.3 defect 2). |

---

## 6. Accessibility and polish

### 6.1 contentDescription

103 `contentDescription` occurrences against 128 `Icon(` call sites, and **36 of
the 103 are `contentDescription = null`**. So roughly **half of all icons are
unlabelled**. Notable: `LivingCard.kt:138,258` — the card's family icon *and*
every act pill icon are `null`; `:177` — the disclosure chevron is a bare `Text`
glyph with no semantics; `StreamScreen.kt:1356` — the 📌 pin state is an
unlabelled emoji.

The island is the good exception (`PhantomIsland.kt:184,225,337` all use
`stringResource`).

### 6.2 Touch targets below 48dp

Confirmed sites (not exhaustive):

| Size | Location |
|---|---|
| 20dp | `map/MapToolsCapsule.kt:393,450` |
| 22dp | `map/MapToolsCapsule.kt:565,573` |
| 28dp | `map/MapChromeParts.kt:161`, `map/MapCommandSheet.kt:143,423,431`, `settings/AssetsPane.kt:403,413,422,430` |
| ~28dp | `widget/LivingCard.kt:249-253` — **every card act pill** |
| 32dp | `map/nav/GuidanceChrome.kt:312` |
| 34dp | `map/MapSearchSheet.kt:480` |
| 36dp | `settings/AssetsPane.kt:183,191,199,207` |
| 38dp | `items/HeroWelcome.kt:161` — the welcome prompt chips |
| 40dp | `ComposeRow.kt:629` — the attach button |
| 44dp | `map/MapSearchSheet.kt:503` |
| 18dp | `map/MapSearchSheet.kt:156` — `.size(18.dp).clickable{}` clear-query |

### 6.3 Contrast (measured, WCAG 2.1 relative luminance)

Passing:

| Ratio | Pair |
|---|---|
| 16.25 | AmberNight `ink` on `surfaceBase` |
| 14.64 | DayClear `ink` on `surfaceBase` |
| 10.57 | gold `#F4AF25` on AmberNight ground |
| 7.01 / 7.15 | `inkSecondary` on ground (night / day) |
| 14.57 / 11.83 | operator bubble fg on fill |

Failing:

| Ratio | Pair | Where |
|---|---|---|
| **1.87** | gold `#F4AF25` on `glassPanel #FFFDF6` | **every `LivingCard` title, DayClear** (`LivingCard.kt:151`) |
| **2.17** | `#E89B3C` on `#FEF8F0` | same, DawnBloom |
| **2.74 / 2.71** | `ChatTints.sky` / `.teal` on DayClear ground | message action menu icons (`StreamScreen.kt:1323,1327`) |
| **3.84** | `accentSharp #B5720C` on DayClear card | day accent text |
| **3.87** | `inkSecondary`@70% on night card | **every timestamp** (`BubbleFooter.kt:59-61`, at 9.5sp) |
| **3.63 / 3.98** | SHADOW / GHOST accent on DayClear | state pills |
| **4.34** | `success #3F8654` on DayClear card | freshness dot + "ok" readouts |
| **1.12** | `glassBorder #111622` on AmberNight ground | card borders are invisible at night — the "glass" edge does not exist |

The pattern is unambiguous: **the palette was tuned on the dark theme and the
light themes were derived arithmetically without re-checking a single pair.**
`ChatTints.kt:14-17` even documents the assumption ("picked to stay legible on
both") — it isn't true.

### 6.4 Dark / light

Better than most: 5 palettes, a time-of-day resolver (`ThemeMode.kt:36-44`), a
600ms colour crossfade at phase boundaries (`PhantomTheme.kt:259-287`), and
`isDark` derived from `surfaceBase.luminance()` rather than the id
(`PhantomTheme.kt:218`) — that last one is a genuinely good fix.

Problems: `Living` is the **default** (`ThemeMode.kt:95`), so a user who
installs at 14:00 gets a cream/gold app and at 22:00 gets a black/amber one,
with no warning — and every contrast failure in §6.3 is a *light-theme*
failure, i.e. the default look between 05:00 and 17:00 is the broken one.

### 6.5 RTL

`Icons.AutoMirrored` is used for Send / Reply / VolumeUp / Chat. Everything else
is hand-positioned with `start`/`end`… except `OperatorImprint.kt:185-190`,
whose `OperatorPaneShape` is `RoundedCornerShape(topStart, topEnd, bottomStart,
bottomEnd)` — correct — but `BubbleFooter.kt:41-42` decides padding from
`Alignment.End`, and `StreamScreen.kt:1239-1241` pins the quote-swipe icon to
`CenterStart` with a hardcoded LTR drag direction
(`detectHorizontalDragGestures` coerced to `0f..maxDragPx`,
`StreamScreen.kt:1203`). RTL is not supported; for uk/en that is acceptable
today but should be a stated decision, not an accident.

### 6.6 Dynamic type

`fontScale` exists as an operator dial (`PhantomTheme.kt:342-347`, clamped
0.85–1.30) and multiplies the M3 roles. But since **851 call sites hardcode
`fontSize` in sp**, the dial moves only the ~36 sites that read
`MaterialTheme.typography`. The system font-size setting is not honoured at
all for those 851 either — `.sp` scales with the OS setting, so at 1.3× system
scale the 40+ hand-tuned "must fit one line" strings (`ComposeRow.kt:450-455`,
`PhantomIsland.kt:220-224` explicitly notes clipping at 1.3) will wrap or clip.

---

## 7. The five things that make it look like a student project

1. **Ten font sizes visible in one screenful** of chat, none of them from the
   declared scale.
2. **Card titles that vanish in daylight** (1.87:1) — and the same amber used
   for text, borders, fills and glows without differentiation.
3. **Two card languages** — half the widgets have a header/freshness/act-bar,
   half are naked glass, and both appear in the same scroll.
4. **No states**: a spinner where the brand loader exists, one line of text
   where a skeleton belongs, dead-end errors with no retry, and no offline
   identity in an offline-first product.
5. **Every gesture is invisible**: PTT-hold vs live-tap on the same orb, chamber
   ring on orb long-press, surface tray on header long-press, quote on
   horizontal swipe, map on pull-down, action menu on bubble long-press — none
   of it is taught, and Back navigates *deeper* instead of out
   (`ContinuumHost.kt:318-322`).

The redesign specification is in `docs/night/UX_TARGET.md`.
