# PHANTOM Companion — redesign target

Implementation specification. Every value here is final; nothing needs a design
decision to build. Companion document: `docs/night/UX_AUDIT.md`.

UI copy stays Ukrainian and is written out verbatim below. Move it to
`strings.xml` as you go — no new hardcoded literals.

---

## 0. Visual direction

Derived from what the product is, not from what AI apps look like.

**It is an instrument, not a messenger.** It works with the network down, it
reports GNSS spoofing, it is used in daylight and at 03:00, and it speaks
Ukrainian. Therefore:

1. **Ground is near-black graphite, not "dark grey chat".** Content sits *on*
   the ground; the ground is never decorated.
2. **Amber is signal, never decoration.** Amber marks: PHANTOM himself, the
   active nav slot, a live value, a primary action. If amber is on screen in
   two unrelated places, one of them is wrong.
3. **Two voices, two families.** Prose is Inter. **Every number, time, unit,
   coordinate, percentage and identifier is JetBrains Mono with `tnum`.** This
   single rule is what will make it read as instrumentation.
4. **Structure comes from alignment and hairlines, not from boxes.** PHANTOM's
   speech has no container. Artifacts have containers. That difference *is* the
   grammar.
5. **No blur, no glow, no gradient fills.** They cost battery, they read as
   consumer software, and they were already refused for the island
   (`PhantomIsland.kt:88-95`). Depth = one fill step + one hairline.
6. **Motion is mechanical.** Things arrive in 220ms and leave in 160ms. Nothing
   bounces except under a fingertip.

Anti-goals: rounded pastel bubbles, avatar circles with initials, purple/blue
gradient send buttons, emoji as UI, spinners.

---

## 1. Foundations

### 1.1 Colour

Replace the five-palette `PhantomThemeSpec` set with **two** palettes, `Night`
and `Day`. `Living`/`Dusk`/`Dawn` become a *time-triggered switch between these
two*, not four separate colour worlds (the dawn/dusk palettes are where every
contrast failure lives). Keep `ThemeMode` and `resolveTheme`; change the
resolver so `Living` returns `Day` for 07:00–19:00 and `Night` otherwise, and
make **`Night` the fresh-install default**.

Add three slots to `PhantomThemeSpec`: `surfaceRaised`, `hairlineStrong`,
`inkTertiary`. Retire `glassHighlight`, `glowPrimary`, `primaryDeep`,
`coralDeep` (their consumers are the effects being deleted).

#### Night (default)

| Token | Hex | Measured contrast |
|---|---|---|
| `surfaceBase` (ground) | `#05070B` | — |
| `surfaceCard` (`glassPanel`) | `#0D1117` | — |
| `surfaceRaised` (sheets, menus, composer) | `#141A22` | — |
| `hairline` (`glassBorder`) | `#2A323E` | 1.56 vs ground — an edge, not text |
| `hairlineStrong` (focus, active) | `#3E4A5A` | 2.4 vs card |
| `ink` | `#F0E6CF` | **15.25** on card |
| `inkSecondary` | `#B3A489` | **7.74** on card |
| `inkTertiary` (floor — never below this) | `#8E8271` | **5.03** on card |
| `primary` (signal amber) | `#F4AF25` | **9.92** on card |
| `primarySoft` (amber fill 14%) | `#F4AF25` @ 0.14 | — |
| `accentSharp` (amber on amber-fill) | `#F4AF25` | 9.14 on `#1B1A14` |
| `success` | `#7FBD8F` | **8.63** on card |
| `warn` | `#FFB352` | **10.65** on card |
| `danger` (`coral`) | `#FF8B6E` | **8.26** on card |
| `userBubbleBg` | `#161D27` | — |
| `userBubbleFg` | `#F0E6CF` | **13.66** on fill |

#### Day

| Token | Hex | Measured contrast |
|---|---|---|
| `surfaceBase` | `#FAF6EC` | — |
| `surfaceCard` | `#FFFFFA` | — |
| `surfaceRaised` | `#FFFFFF` | — |
| `hairline` | `#D9CEB6` | 1.45 vs ground |
| `hairlineStrong` | `#B8A98B` | — |
| `ink` | `#241C12` | **15.57** on ground |
| `inkSecondary` | `#5A4E3D` | **8.08** on card |
| `inkTertiary` | `#6E6250` | ≥5.5 on card |
| `primary` **(fills and strokes only — never text)** | `#F4AF25` | — |
| `signalInk` **(amber as text/icon)** | `#8A5A08` | **5.90** on card |
| `success` | `#2F6B42` | **6.34** on card |
| `warn` | `#8A5A08` | 5.90 |
| `danger` | `#A6321C` | **6.77** on card |
| `userBubbleBg` | `#2E2519` | 13.95 vs ground |
| `userBubbleFg` | `#F7F1E3` | **13.37** on fill |

**Hard rule that fixes the worst defect in the product:** amber is never used
as a text or icon colour on a light surface. Add to `PhantomThemeSpec`:

```
/** Amber as INK. On dark this is `primary`; on light it is the dark
 *  amber-brown, because #F4AF25 on cream measures 1.87:1. */
val signalInk: Color
```

Night: `signalInk = #F4AF25`. Day: `signalInk = #8A5A08`. Every call site that
today writes `color = theme.primary` on text or `tint = accent` on an icon
switches to `theme.signalInk`.

#### Semantic tints (`ChatTints`) — add day variants

| Name | Night | Day | Day ratio |
|---|---|---|---|
| `sky` | `#5B9BD5` | `#2C6FA8` | 5.31 |
| `indigo` | `#7B8BE0` | `#45549E` | 6.94 |
| `teal` | `#3FA796` | `#1F6E62` | 6.04 |
| `green` | `#57A773` | `#2F6B42` | 6.34 |
| `violet` | `#A98BDE` | `#6A4BA8` | 6.57 |
| `rose` | `#C77FB4` | `#96487F` | 5.93 |

Resolve via `if (LocalPhantomTheme.current.isDark) night else day`.

### 1.2 Type

Nine roles. **11sp is the floor — nothing in the product ships below it.**
Add these to `PhantomTextRoles.kt` as composables *and* to `phantomTypography`
so both access paths agree.

| Role | Family | Size / line | Weight | Tracking | Use |
|---|---|---|---|---|---|
| `displayXL` | Inter | 32 / 38 | 800 | −0.6 | greeting, hero numeral |
| `title` | Inter | 20 / 26 | 700 | −0.3 | screen + sheet titles |
| `section` | Inter | 16 / 22 | 600 | −0.1 | card headers (replaces the caps kicker) |
| `body` | Inter | 16 / 24 | 400 | 0 | PHANTOM's prose |
| `bodySm` | Inter | 14 / 20 | 400 | 0 | operator turn, secondary prose |
| `label` | Inter | 13 / 18 | 500 | 0 | buttons, chips, list rows |
| `data` | **JetBrains Mono, `tnum`** | 13 / 18 | 500 | 0 | numbers, metrics, coordinates |
| `dataSm` | **JetBrains Mono, `tnum`** | 11 / 14 | 500 | +0.2 | timestamps, units, counters |
| `kicker` | JetBrains Mono, UPPERCASE | 11 / 14 | 600 | **+1.2** | section labels, state pills |

Deleted: `labelLarge`'s `4.sp` tracking; all 10sp/9.5sp/9sp/8.5sp/8sp/7.5sp
sites (there are ~180 — map them to `dataSm` or `kicker`).

`fontScale` (0.85–1.30) multiplies size and line-height; tracking is never
scaled. Every screen must survive `fontScale = 1.30` **and** system font
scale 1.3 without clipping — labels wrap or ellipsise, they never truncate
mid-glyph.

### 1.3 Space

Replace `PhantomSpacings` with the eleven rungs the codebase actually needs
(`12` and `20` are the 2nd and 12th most used values today and are missing):

```
None 0 · Hair 2 · XS 4 · S 8 · M 12 · Base 16 · L 20 · XL 24 · XXL 32 · Huge 40 · Max 48
```

`Hair = 2.dp` is for hairline offsets only, never for layout gaps.

### 1.4 Radius

```
Tight 4 · Small 8 · Medium 12 · Large 16 · XLarge 20 · Hero 28 · Pill (percent 50)
```

`PhantomRadii.XLarge` moves 24 → 20 and `Hero` 32 → 28: at 28dp+ a phone-width
card stops reading as a panel. `SheetTop = RoundedCornerShape(topStart = 20,
topEnd = 20)`.

`RoundedCornerShape(999.dp)` is banned — 23 sites, all replace with
`PhantomRadii.Pill`.

### 1.5 Elevation (new — `theme/PhantomElevation.kt`)

Real shadows are invisible on `#05070B`. Depth is expressed as
**fill step + hairline**, four levels:

| Level | Fill | Hairline | Shadow | Use |
|---|---|---|---|---|
| `Ground` | `surfaceBase` | none | none | screen background |
| `Card` | `surfaceCard` | 1dp `hairline` | none | timeline cards, list rows |
| `Raised` | `surfaceRaised` | 1dp `hairline` | 8dp @ 24% black, y+2 | composer, island, sheets |
| `Overlay` | `surfaceRaised` | 1dp `hairlineStrong` | 16dp @ 36% black, y+4 | menus, dialogs, tooltips |

`GlassCard` is rewritten to take a `PhantomElevation` and to draw the top
highlight in **dp** (`1.dp` inset, `1.5.dp` stroke) — the current raw-pixel
version (`GlassCard.kt:96-98`) is a density bug. Delete `glow`, delete the
inner bottom shadow gradient, delete `presence` fill-alpha ramping (keep
`presence` but drive only `alpha` on the whole node).

### 1.6 Motion

Reuse `MotionTokens` unchanged; these are the only specs the new surfaces use:

| Event | Spec |
|---|---|
| message row enters | `fadeIn(enterQuart 220ms)` + `slideInVertically { it / 6 }` |
| row leaves | `fadeOut(exitQuart 160ms)` |
| card expands | `expandQuartIntSize 260ms` |
| card folds | `dismissQuartIntSize 180ms` |
| island ↔ composer swap | `creaseQuart 180ms`, crossfade with no overlap |
| tab tint change | `stateQuartColor 200ms` |
| press depth | `gestureSpring` (700 / 0.9) |
| streaming caret | `caretBlink 600ms` linear, alpha 0.35 ↔ 1.0 |
| thinking beads | `pulseSlow 1400ms` reverse, stagger 220ms |
| scroll-to-bottom pill | `enterQuart` in / `exitQuart` out |
| skeleton shimmer | **none.** Skeletons breathe: alpha 0.35 ↔ 0.6, `pulseSlow`, reverse |

Everything honours `LocalMotionScale`; at scale 0 all of the above become
instant state changes with no animation.

---

## 2. Surface 1 — Chat (`StreamScreen`)

The flagship. Target: it should be obvious in the first screenshot that this is
not a wrapper around a chat API.

### 2.1 Layout

```
┌──────────────────────────────────────────────┐
│ status bar inset                             │
│ ┌──────────────────────────────────────────┐ │ header, floats, translates
│ │ ◉ ГОТОВИЙ            ⌕  ⋯               │ │ 44dp tall
│ └──────────────────────────────────────────┘ │
│                                              │
│   timeline — contentPadding 20dp sides       │
│                                              │
│                                       ▾ 3    │ scroll-to-bottom pill
│ ┌──────────────────────────────────────────┐ │
│ │ island OR composer — 64dp                │ │
│ └──────────────────────────────────────────┘ │
│ nav bar inset                                │
└──────────────────────────────────────────────┘
```

- Ground: `surfaceBase`, flat. **Delete `DialogueBackdrop`'s gradient**
  (`StreamScreen.kt:1071-1099`) — with the Continuum's world stratum behind it,
  an opaque ground is correct and cheaper.
- Timeline `contentPadding`: `top = headerHeight + 16.dp`, `start = 20.dp`,
  `end = 20.dp`, `bottom = 12.dp`.
- **Delete the depth recession** (`StreamScreen.kt:601-608`). History is data,
  not atmosphere. Nothing in the timeline is ever below alpha 1.0.
- Keep `fadingEdges` but reduce to `topFade = 20.dp`, `bottomFade = 24.dp`.

### 2.2 Clustering model (replaces the current 8/16dp scheme)

A **cluster** = consecutive messages, same author, within 4 minutes.

| Gap | Value |
|---|---|
| between rows inside a cluster | `4.dp` |
| between clusters | `20.dp` |
| before / after a card | `16.dp` |
| above a day rib | `28.dp`; below it `20.dp` |

Implement in `withTimeRibs` so the row model carries `isClusterStart` /
`isClusterEnd`; delete the per-row `padding(top = if (groupStart…) 8.dp)`
hack at `StreamScreen.kt:609`.

### 2.3 PHANTOM's turn — `AssistantProse` v2

**No container.** This is the grammar rule from §0.4.

```
 ●   Прогноз на завтра: до −4 вночі, вітер
 │   північно-західний 5–7 м/с. Дорогу
 │   на Житомир краще взяти зранку.
 │
 │   ┌────────────────────────────────┐
 │   │  [card]                        │
 │   └────────────────────────────────┘
 │
     14:32
```

| Part | Spec |
|---|---|
| presence dot | 10dp circle, `primary`, at `x = 0`, vertically centred on the first line's cap-height (top offset 7dp). Only on cluster start. |
| spine | 1.5dp wide, `primary` @ 0.22, from `dot.bottom + 6dp` to cluster bottom, at `x = 4.25dp`. Only when the cluster has >1 row **or** contains a card. |
| prose column | starts at `x = 26.dp`, full remaining width |
| text | `body` (16/24), `ink` |
| card in cluster | full column width, `Card` elevation |
| footer | `dataSm`, `inkTertiary`, `HH:mm` only, **cluster end only**, at `x = 26.dp`, `top = 6.dp` |

Delete: the `GlassCard` wrapper (`AssistantProse.kt:134`), the `13.sp`
sub-states, the `8.sp` `"· ПРОАКТИВНЕ"` tag. A proactive turn instead gets its
presence dot drawn as a 10dp **ring** (2dp stroke, hollow centre) — same
footprint, different glyph, zero extra layout.

Long-press keeps opening the action menu; the ripple bounds become the prose
column rect.

### 2.4 Operator's turn — `OperatorImprint` v2

| Part | Spec |
|---|---|
| alignment | end |
| max width | `0.78f` of the column |
| fill | `userBubbleBg` |
| shape | `RoundedCornerShape(topStart = 16, topEnd = 16, bottomStart = 16, bottomEnd = 6)` |
| padding | `horizontal = 14.dp, vertical = 10.dp` |
| text | `bodySm` (14/20), `userBubbleFg`, `textAlign = Start` (not End — right-ragged Ukrainian is hard to read) |
| quote strip | 2dp bar `userBubbleFg` @ 0.30, 10dp gap, quote in `bodySm` @ 0.62, max 3 lines |
| footer | `dataSm`, `inkTertiary`, end-aligned, `top = 6.dp`, cluster end only |
| pending | fill @ 0.55 alpha + `dataSm` `надсилаю…` in footer slot |
| failed | 1dp `danger` border + footer `не надіслано · торкнись, щоб повторити`, whole bubble clickable → `StreamEvent.Retry` |

### 2.5 Timestamps

`dataSm` (11sp JetBrains Mono, `tnum`), `inkTertiary` — **not** `inkSecondary`
at 70% alpha (that measures 3.87:1). Rendered **once per cluster**, never per
message. No `·` separators, no place, no state string in the default footer.

### 2.6 Day rib

Replace `TimeRib` (`StreamScreen.kt:1603-1658`). Centre a `kicker` label
(`СЬОГОДНІ` / `ВЧОРА` / `12 ЛИПНЯ`) on `inkTertiary`, with a **single 1dp
hairline** running the full width behind it, interrupted by a 12dp gap on each
side of the label. Height 28dp. No gradients.

### 2.7 Streaming — exactly one indicator at a time

State machine on `TurnPhase`:

| Phase | Indicator | Where |
|---|---|---|
| `THINKING` (no token yet) | three 6dp beads, `primary`, `pulseSlow` reverse, stagger 220ms | at the prose column origin, in place of the first line |
| `ACTING` (tool running) | tool-trace row (see §4.6) with a 1.5dp amber progress hairline under it | in the timeline |
| `TYPING` (tokens arriving) | 2dp × line-height caret, `primary`, `caretBlink` | after the last glyph |
| `IDLE` | nothing | — |

**The header meridian pulse is removed entirely.** One organ, one signal. Delete
the suppression filter at `StreamScreen.kt:173-189` — it exists only because
two indicators could co-exist.

Keep the trailing-glyph alpha ramp but compute it in the draw phase from the
`TextLayoutResult`, not by rebuilding an `AnnotatedString` per token.

### 2.8 Header

44dp tall, floats and translates (keep the existing `headerOffsetPx`
mechanism — it is correct).

```
◉  ГОТОВИЙ                              ⌕    ⋯
```

- `◉` — 20dp presence orb, `primary`, breathing only when phase ≠ IDLE.
- status word — `kicker`, `inkSecondary`. Copy: `ГОТОВИЙ` / `ДУМАЮ` /
  `ПРАЦЮЮ` / `ПИШУ` / `СЛУХАЮ` / `ОФЛАЙН`.
- `⌕` search → `Routes.HistorySearch`, 48dp target.
- `⋯` overflow → threads, spaces, clear, settings. 48dp target.
- **The long-press-to-open-SurfaceTray gesture is deleted.** Move its contents
  into `⋯`.

### 2.9 Scroll-to-bottom

Replace `MeridianPole` with a pill: 32dp tall, `Pill` radius, `Raised`
elevation, contents `▾` 16dp icon + unread count in `dataSm`. Anchored
`BottomEnd`, `end = 20.dp`, `bottom = 12.dp` above the bar. Enter/exit
`enterQuart`/`exitQuart`.

### 2.10 Pull-down-to-map

Keep the gesture, fix the discoverability: while `exitDrag > 0`, show a
`kicker` label `МАПА ↓` under the chevron, fading in with the same progress
value. Raise the commit threshold from 180px to 220px and add
`PhantomHaptics.tick` at 60% progress so the operator feels the arm point.

### 2.11 Empty state

`HeroWelcome` v2 — keep the shape, fix the three bugs.

| Part | Spec |
|---|---|
| container | `Card` elevation, `PhantomRadii.XLarge` (20dp), padding 20dp |
| kicker | `PHANTOM · ОФЛАЙН` or `PHANTOM · ГОТОВИЙ`, `kicker`, `signalInk` |
| greeting | `displayXL`, `ink`. **Name from `profileStore.active.displayName`** — the `"кирил"` default at `HeroWelcome.kt:47` is deleted; if no name, the greeting drops the vocative. |
| hour | read from a `LaunchedEffect` ticking every 60s, not `remember` at `HeroWelcome.kt:56` |
| subline | `bodySm`, `inkSecondary`, max 2 lines |
| chips | 2 chips, `48.dp` tall (not 38), `Pill`, 1dp `hairline`, `label` type, `Raised` fill |

Copy (unchanged from today, it is good):
`доброго ранку, %s.` / `я тут, %s.` / `добрий вечір, %s.` / `не спиться, %s?`

### 2.12 Chat error state

Every failure in the stream becomes a **`NoticeRow`** with an action:

```
 ⚠  не вдалось відповісти — немає моделі
    [ повторити ]   [ налаштування ]
```

| Part | Spec |
|---|---|
| container | none — full width, 1dp `danger` @ 0.5 left rail at `x = 0`, content at `x = 26.dp` |
| icon | 16dp, `danger` |
| text | `bodySm`, `ink` |
| actions | 0–2 pills, 40dp tall, `label`, `hairline` border |
| haptic | `PhantomHaptics.error`, once per id (keep existing guard) |

Copy: `не вдалось відповісти` · `немає звʼязку — відповім, щойно зʼявиться` ·
`порожня відповідь` — each with `повторити`.

---

## 3. Surface 2 — the bottom bar (island + composer)

One bar. Two contents. Never both.

### 3.1 Shared frame

| Property | Value |
|---|---|
| height | `64.dp` |
| side margin | `20.dp` |
| bottom margin | `12.dp` above `navigationBars` inset |
| shape | `PhantomRadii.Pill` (island) / `PhantomRadii.XLarge` 20dp (composer, so it can grow) |
| elevation | `Raised` |
| swap | `creaseQuart` 180ms, crossfade, no overlap |

`IslandReserve` = `12 + 64 + 12 = 88.dp` (currently 86).

### 3.2 Island — 5 slots

| Slot | Icon | Label (`kicker`) | Action |
|---|---|---|---|
| 1 | `Chat` / `Edit` when active | `ЧАТ` | rack to Dialogue / open composer |
| 2 | `Map` | `МАПА` | rack to World |
| 3 | **orb** | — | tap = live dialogue, hold = PTT |
| 4 | `Lock` | `VAULT` | `Routes.Vault` |
| 5 | `Settings` | — | `Routes.Settings` |

- Slot width: `weight(1f)`, full 64dp height → touch target 64dp. Correct today.
- Icon 22dp, label 4dp below, tint `primary` when selected else `inkSecondary`,
  `stateQuartColor` 200ms.
- Orb: 52dp circle, **flat `primary` fill** — delete the radial gradient
  (`PhantomIsland.kt:305-308`), it is the only gradient left in the product.
  Mic glyph 24dp in `surfaceBase`.
- Listening bloom: keep the halo, **delete the 44-tick waveform ring**
  (`PhantomIsland.kt:379-395`) — it redraws every frame for decoration. Replace
  with a single 2dp ring at r=27dp whose radius breathes ±2dp on `pulseSlow`.
- Both orb gestures must be taught once — see §5.5.

Copy: `ЧАТ` `МАПА` `VAULT`; `contentDescription` for slot 5 `Налаштування`,
for the orb `Голос — торкнись для розмови, утримуй для запису`.

### 3.3 Composer

```
┌──────────────────────────────────────────────┐
│  +   напиши або спитай…                  ▶  │
└──────────────────────────────────────────────┘
     мозок прокидається · 62%
```

| Part | Spec |
|---|---|
| min height | `64.dp`, grows to `max 160.dp` (6 lines) |
| inner padding | `horizontal = 8.dp, vertical = 8.dp` |
| `+` attach | **48dp** (not 40), `inkSecondary`, → attach sheet |
| field | `body` (16/24) — **placeholder is the same 16sp**, `inkTertiary`. Copy: `напиши або спитай…` |
| cursor | `primary`, 2dp |
| send | 48dp circle. Inactive: `hairline` 1dp border, glyph `inkTertiary`. Active: `primary` fill, glyph `surfaceBase`. Sending: morphs to `Stop`, same fill. |
| attachments | chip row above the field, 32dp tall, `Pill`, `hairline` border, name in `label`, remove `×` in a 48dp target |
| quote strip | above the field, 2dp `primary` left bar, quote `bodySm` `inkSecondary`, clear `×` 48dp |
| ghost completion | inline `inkTertiary` tail; accept target is **48×48dp** with a 16dp `→` icon, not a text glyph |
| brain line | `dataSm`, `inkSecondary`, 20dp side padding, 6dp below the bar. `danger` colour only on `Stalled`. |

Copy: `напиши або спитай…` · `надіслати` · `зупинити` · `прокидаюсь · %d%%` ·
`чекаю на мережу — прокинусь сам` · `не зміг завантажити мозок: %s`.

### 3.4 PTT / capture state

While `capturing`: the composer's border becomes 1.5dp `primary`, the field is
replaced by the live transcript in `body` `ink`, and a 24dp-tall waveform sits
above it (existing `VoiceWave`, tint `primary`). No separate full-screen
overlay for a hold-to-talk — `VoiceModeOverlay` is reserved for hands-free live
mode only.

---

## 4. Surface 3 — the card system

### 4.1 The law

- **PHANTOM speaking → no container** (§2.3).
- **PHANTOM producing an artifact → `LivingCard`.**
- There is no third option. The 16 widgets on bare `GlassCard` and the 4 on
  nothing are all migrated to `LivingCard`.

### 4.2 `LivingCard` v2 anatomy

```
┌────────────────────────────────────────────┐
│ ▣  ПОГОДА · КИЇВ                ● 2 хв   ⌄ │  header 40dp
│ ──────────────────────────────────────────  │  hairline (only if body)
│                                            │
│   body                                     │
│                                            │
│ ──────────────────────────────────────────  │  hairline (only if acts)
│  [ оновити ]  [ на мапі ]                  │  act bar 48dp
└────────────────────────────────────────────┘
```

| Part | Spec |
|---|---|
| container | `Card` elevation, `PhantomRadii.Large` (16dp), full column width |
| header height | `40.dp`, padding `horizontal = 14.dp` |
| family chip | 26dp, `PhantomRadii.Small` (8dp), fill = family tint @ 0.14, icon 15dp in family tint (day variant on light) |
| title | **`section` (16/22 SemiBold), `ink`** — not 11sp uppercase amber. Uppercase is dropped; sentence case in Ukrainian. |
| subject | optional ` · Київ` suffix in `section` weight 400, `inkSecondary` |
| freshness dot | 6dp, `success` fresh / `inkTertiary` stale |
| freshness text | `dataSm`, `inkTertiary` — **recomputed on a 60s ticker**, not `remember`ed |
| disclosure | `Icons.Outlined.ExpandMore`, 20dp icon in a 48dp target, `rotationZ` 0→180 on `stateQuart`; `contentDescription` = `розгорнути` / `згорнути` |
| body padding | `horizontal = 14.dp`, `bottom = 14.dp` |
| divider | 1dp `hairline`, full bleed |
| act pill | **height 40dp**, `Pill`, padding `horizontal = 14.dp`; primary = `primary` @ 0.14 fill + `signalInk` text + 1dp `primary` @ 0.45 border; secondary = transparent + `inkSecondary` + 1dp `hairline`. Icon 16dp, gap 6dp, label `label` (13sp). Row height 48dp so the target is legal. |
| act haptic | `PhantomHaptics.tick` (not `LongPress`) |

**Family tint** replaces the free-form `accent` parameter. One hue per family,
from `ChatTints`:

| Family | Tint | Types |
|---|---|---|
| Sky | `sky` | weather, aqi-gauge |
| Space | `teal` | map, route, places, ar-map, trip-pack |
| Knowledge | `indigo` | citation-answer, briefing, digest, markdown, table, comparison, report |
| Machine | `green` | code-block, code-runner, diff, agent-progress, progress, spreadsheet, file-*, artifact, canvas, model-3d |
| Device | `violet` | app-action, attachment, audio-waveform, voice-memo, photo-hero, image-gen |
| Inner | `rose` | memory-echo, reasoning, checklist, task-list, calendar, reminder, question, timer |
| Alert | `danger` | alert-card |
| Signal | `primary` | stat-hero, vitals-constellation, chart |

### 4.3 Card states — every card gets all four

| State | Render |
|---|---|
| loading | full card frame, header title + chip in place, body replaced by 2–4 `SkeletonBlock`s (see §5.2) |
| ready | as above |
| stale | freshness dot `inkTertiary`, `оновити` act auto-injected as the first pill |
| error | header stays, body replaced by `⚠ не вдалось оновити` in `bodySm` `danger` + a `повторити` act |

### 4.4 Fallback card

`WidgetRenderer`'s `else` branch becomes a real card: Machine family chip,
title `Невідома картка`, body `dataSm` showing the type string, single act
`показати JSON` opening the payload in a monospace sheet. Never a naked
sentence.

### 4.5 Deletions

- `PhotoHeroWidget.kt` — 17 hardcoded hexes; rebuild on `LivingCard` with the
  image as the body slot.
- `items/CouncilDebateCard.kt`, `items/ConvictionReviewCard.kt` — migrate to
  `LivingCard` (Inner family) and delete their bespoke containers.
- `WidgetBubble.kt:174`, `CanvasWidget.kt:190…`, `Model3DCardWidget.kt:280` —
  `999.dp` → `PhantomRadii.Pill`.

### 4.6 Tool trace

Not a card. A single 24dp row inside PHANTOM's cluster:

```
 │   ⟩ weather.forecast          0.4 с
```

`⟩` 12dp in family tint, tool name in `data`, duration in `dataSm`
`inkTertiary`, right-aligned. While running: the name is `inkSecondary` and a
1.5dp family-tint hairline sweeps left→right under the row on `MotionTokens.sweep`.
Consecutive traces group under one row with a count (`⟩ 3 інструменти`,
expandable).

---

## 5. Surface 4 — the state kit (new: `core-design/component/state/`)

Five components. Every screen in the app uses them; no screen hand-writes a
state again.

### 5.1 `PhantomEmpty`

```
     ▢                 32dp icon, inkTertiary
  Заголовок            title, ink
  Один рядок пояснення bodySm, inkSecondary, max 2 lines, centre, max 280dp wide
  [ дія ]              optional 48dp pill, primary
```
Vertically centred, 24dp side padding, 12dp between parts.

### 5.2 `PhantomSkeleton` / `SkeletonBlock`

- `SkeletonLine(widthFraction, height = 12.dp)` — `PhantomRadii.Tight`, fill
  `inkTertiary` @ 0.12, alpha breathing 0.35 ↔ 0.6 on `pulseSlow`. **No shimmer
  sweep** (a moving gradient is a per-frame GPU cost on a weak phone).
- `SkeletonBlock(lines: Int)` — lines at widths 1.0, 0.92, 0.66, 0.8 cycling,
  8dp apart.
- Rule: any list or card that can take >150ms to fill renders a skeleton of the
  **same height** as its loaded form. No layout jump.

### 5.3 `PhantomError`

Same layout as `PhantomEmpty`, icon `danger`, and **a required action**. An
error with no action is not shippable. Default action label `повторити`.

### 5.4 `PhantomOfflineChip`

The product's competitive claim needs a face. A 24dp `Pill`, `success` @ 0.14
fill, `success` text, `kicker` type, copy `ОФЛАЙН · ПРАЦЮЮ`. Shown in the chat
header's status slot when the network is down — **as reassurance, in green,
not as a warning in red.** This single element communicates the differentiator
on the first screenshot.

When something genuinely *needs* the network (a map tile pack, a model
download), that specific card shows `warn` + `чекаю на мережу`.

### 5.5 `PhantomCoachmark` — first-run gesture teaching

Three one-shot overlays, dismissed by tap, persisted in prefs. Shown on the
1st, 2nd and 3rd app open respectively:

| # | Anchor | Copy |
|---|---|---|
| 1 | the orb | `Торкнись — розмова. Утримуй — запис.` |
| 2 | top of the timeline | `Потягни вниз — мапа.` |
| 3 | any message | `Утримай повідомлення — дії.` |

Render: 40% black scrim, a 2dp `primary` ring around the anchor bounds, copy in
`bodySm` `ink` on a `Raised` pill 16dp from the anchor. Enter `enterQuart`,
exit `exitQuart`.

Also: **route `PermissionsScreen` into Settings** (it is written and orphaned)
and add a `PhantomPermissionRationale` sheet — icon, one `bodySm` sentence
naming what breaks without it, `дозволити` / `не зараз` — fired before every
runtime permission request instead of the bare system dialog.

---

## 6. Surface 5 — Settings hub

The current single scrolling `Column` of panes over 9 sub-routes is
unnavigable.

### 6.1 Structure

`LazyColumn`, `Ground`, 20dp side padding, grouped rows:

```
НАЛАШТУВАННЯ                      title, 24dp top

МОЗОК                             kicker, inkTertiary, 24dp above / 8 below
┌────────────────────────────────┐
│ ▣  Моделі              3 · 2.1 ГБ  › │   row
│ ▣  Ключ Gemini              додано › │
└────────────────────────────────┘

ГОЛОС
┌────────────────────────────────┐
│ ▣  Розпізнавання мови         › │
│ ▣  Озвучення            увімк.  ▸│   inline switch
└────────────────────────────────┘
…
```

| Part | Spec |
|---|---|
| group container | `Card` elevation, `PhantomRadii.Medium` (12dp), rows separated by 1dp `hairline` inset 48dp from start |
| row height | **56.dp** min |
| row icon | 20dp, `inkSecondary`, in a 28dp slot |
| row title | `label` (13/18), `ink` |
| row value | `data` (13sp mono) when numeric/stateful, `inkSecondary`, end-aligned |
| chevron | 16dp `inkTertiary` |
| switch | Material3 `Switch`, `primary` checked track |
| destructive group | title `НЕБЕЗПЕЧНА ЗОНА` in `danger`, rows with `danger` titles |
| save ack | keep the reactive strip; `dataSm`, `success`, copy `✓ збережено`, auto-fades after 2s |

Groups, in order: `МОЗОК` · `ГОЛОС` · `КАРТА` · `ПРИВАТНІСТЬ` (mic, permissions,
whisper) · `ВИГЛЯД` (theme mode, font scale) · `ПРИСТРІЙ` (pair, diagnostics,
precision) · `ПРО ДОДАТОК` (licenses, version) · `НЕБЕЗПЕЧНА ЗОНА`.

### 6.2 Fixes carried in

- All `IconButton`s in `AssetsPane.kt` go to 48dp.
- `MapSettingsScreen` (94 raw tokens, 45 `.sp`) is rebuilt on the row component
  above; it should end with zero `.sp` literals.
- Add a `⌕` field at the top filtering rows by title — 9 sub-routes need it.

---

## 7. Implementation order

Each step is independently shippable and independently verifiable.

| # | Step | Files | Check |
|---|---|---|---|
| 1 | Token layer: add `signalInk`, `surfaceRaised`, `hairlineStrong`, `inkTertiary`; collapse to Night/Day; new spacing/radius rungs; `PhantomElevation`; 9 type roles | `core-design/theme/*` | existing `PhantomThemeAuditTest` / `TypographyComplianceTest` updated and green |
| 2 | `GlassCard` → elevation-driven, dp-correct highlight, no glow | `core-design/component/GlassCard.kt` | visual diff |
| 3 | State kit (§5.1–5.4) | new `core-design/component/state/` | renders in isolation |
| 4 | `LivingCard` v2 + family tints + the 4 states + 60s freshness ticker | `widget/LivingCard.kt` | a stale card shows `оновити` after 60m without a recomposition trigger |
| 5 | Migrate the 16 bare-`GlassCard` widgets + the 4 container-less ones | `widget/*`, `items/CouncilDebateCard.kt`, `items/ConvictionReviewCard.kt` | `grep -c "GlassCard(" widget/` == 0 |
| 6 | Chat: clustering, `AssistantProse` v2 (no container, dot+spine), `OperatorImprint` v2, timestamps, day rib, single streaming indicator, recession deleted | `StreamScreen.kt`, `items/*` | see acceptance below |
| 7 | Composer + island | `ComposeRow.kt`, `PhantomIsland.kt` | placeholder and text are the same size; `+` is 48dp |
| 8 | Coachmarks + permission rationale + route `PermissionsScreen` | new + `PhantomNavGraph.kt` | fresh install teaches 3 gestures |
| 9 | Settings hub | `ui/settings/*` | zero `.sp` literals in `MapSettingsScreen.kt` |
| 10 | Back-stack fix | `ContinuumHost.kt:318-322` | Back from chat exits the app; Back from map returns to chat |

### 7.1 Acceptance — run these greps, they must all be true

```sh
# no raw pill radii
grep -rn "999.dp" --include=*.kt app feature-* | wc -l          # 0

# no sub-11sp type
grep -rhoE "fontSize = ([0-9]|10)(\.[0-9]+)?\.sp" --include=*.kt app feature-* | wc -l   # 0

# chat surface is on the type roles
grep -c "fontSize" feature-stream/.../ui/items/AssistantProse.kt  # 0
grep -c "fontSize" feature-stream/.../ui/items/OperatorImprint.kt # 0
grep -c "fontSize" feature-stream/.../ui/items/BubbleFooter.kt    # 0

# amber is never ink on light
grep -rn "theme.primary" --include=*.kt app feature-* | grep -E "color =|tint ="   # 0

# no stock spinners
grep -rn "CircularProgressIndicator" --include=*.kt app feature-* | wc -l   # 0

# every icon is labelled or explicitly decorative next to a labelled parent
grep -rn "contentDescription = null" --include=*.kt app feature-* | wc -l   # < 20, each justified
```

### 7.2 Acceptance — on device

1. At 12:00 (Day palette) every card title, timestamp and act label is readable
   at arm's length in direct sunlight.
2. System font size at maximum + app `fontScale` 1.30: no clipped label
   anywhere in chat, island or settings.
3. Airplane mode: the chat header shows `ОФЛАЙН · ПРАЦЮЮ` in green and the app
   answers.
4. Cold start with an empty DB: skeleton → hero, no blank frame, no layout jump.
5. Kill the model mid-answer: an error row with a working `повторити`.
6. Back button from the chat exits the app.
7. Fresh install: after three opens the operator has been shown the orb, the
   pull-down and the long-press.

---

## 8. Світ — the world layer

Added 2026-08-10, when the 3D city stopped being a decoration and started being
lit. This section exists to keep §0.5 from being read as a law it was never
meant to be.

### 8.1 The boundary

**§0.5 — "no blur, no glow, no gradient fills" — governs the chrome. It does not
govern the world.**

- **Chrome is an instrument.** It is austere on purpose: flat fills, hairlines,
  one elevation step, amber only as signal. Everything Compose draws is chrome.
- **World is a window.** It obeys the laws of light, not the laws of the panel:
  a sun with a real azimuth, cast shadows, aerial haze, tone mapping, glass that
  reflects the sky. Everything Filament draws is world.

The boundary is not a matter of taste — it is physical and greppable. If the
pixel came out of a `@Composable`, §0.5 applies. If it came out of
`CityRenderer`, §8 applies.

A ratchet that greps for gradients, glow or blur must therefore exclude
`ui/map/crystal/**` and `ui/map/filament/**`. Distance fog is not a gradient
fill; it is what makes a far quarter sit behind a near one.

### 8.2 What the world may do that the chrome may not

| World | Chrome |
|---|---|
| Sun position from `SolarSky` — real elevation and azimuth for the operator's latitude and clock | fixed tokens |
| Cast shadows, ambient occlusion, image-based ambient light | one fill step + one hairline |
| ACES tone mapping, deterministic exposure driven by sun elevation | sRGB tokens, no post-processing |
| Distance haze in the horizon colour | no gradient fills |
| Emissive night windows, specular glass | no glow |

### 8.3 What the world still owes the chrome

1. **Amber stays signal.** Route, target, the operator's own position are amber.
   A building is never amber, a tree is never amber. The world may be warm; only
   the instrument may be gold.
2. **Numbers stay JetBrains Mono `tnum`,** including labels that live *in* the
   scene rather than on the glass.
3. **No spinner in the world.** An unloaded tile is haze, never emptiness, and a
   loaded tile arrives with `PhantomMotion.enter` (220 ms), never a pop.
4. **Honesty (law 3) is geometry, not decoration.** Where OSM has a footprint but
   no height, the building is drawn differently — muted, no windows — instead of
   being given an invented height. The operator must be able to see the edge of
   what we know.
5. **Zero frames at rest.** The lit path added a moving sun; a moved sun owes the
   screen exactly one frame (`CityLighting.consumeDirty`). Anything that makes
   the parked camera render continuously is a thermal regression, and this
   subsystem has already caused two.

### 8.4 Verification

The lit path ships behind `MapStyleConfig.litCity` (Налаштування → Мапа →
«світло міста» → «Справжнє сонце»). Both render paths stay shippable until the
lit one has won on a real screen, side by side, on SM-A566B.
