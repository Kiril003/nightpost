# PHANTOM — night ledger, 2026-07-26 (02:20 → 10:00)

Owner-run transformation session. Append-only. Every wave ends in a commit so
a context limit never loses work.

## North star

Field-grade offline AI companion. Not a chat app that looks like every other
chat app — an **instrument**: near-black ground, amber signal accent, tabular
numerals, precise type, mechanical motion. It must be readable in daylight and
at 3am, work with the network down, and feel like it knows the operator.

Edge over the giants: fully offline, local-first privacy (SQLCipher), war-zone
awareness (raid/shelter/spoof), proactive but never noisy, and a look that
sells itself.

## Targets by 10:00

1. Chat surface rebuilt to the approved prototype — flagship quality.
2. Design system hardened; token leakage removed from worst offenders.
3. StreamViewModel (4177 lines) and AppContainer (3589) decomposed.
4. Real states everywhere: first-run, empty, loading, error, offline.
5. Dead screens deleted, not hidden.
6. Map: one bar, proper search.
7. Green through scripts/verify.sh at every merge.

## Infrastructure

- Sleep inhibited until ~10:40 (systemd-inhibit, pid 2051980).
- Android emulator + system-image android-34 google_apis x86_64 installed
  tonight — the phone is gone, so the emulator is our eyes for UI work.
- Machine: 12 cores, 15 GB RAM (tight — cap parallel gradle), RTX 5060 8 GB.
- Builds stay `--offline`.

## Log

- 02:21 — session start. Sleep inhibited, emulator download started.
- 02:30 — audit wave launched: architecture, UX/UI, product-gap.
- 02:40 — **session limit hit; all four workers killed mid-edit.** Resets
  07:20. Tree verified still compiling; the data-systems worker had in fact
  finished its whole brief before dying. Solo from here.
- 03:00 — `395fecf3` data systems closed, 79 hand-written pills → token.
- 03:30 — `b427c7e5` one tool protocol, offline routing wired.
- 04:00 — `8a165269` chat first impression: hero greets by real name.
- 04:35 — `431f1835` map search folded into the island; gate flake fixed.

- 04:45 — team available again; three workers out: background air-raid
  service, dead-weight deletion, chat thread rebuild.
- 05:15 — map search chrome: 40 colour emoji → vector glyphs.
- 05:40 — three workers die on a monthly spend limit, tree left red.
- 13:00 — `b92fd781` salvage: their leaves were finished, only the wiring
  was not. Completed it rather than reverting. `SkyState` collision
  resolved (solar keeps the name, alerts become `AlertSky`).
- 13:20 — `38141079` **AmberNight is the default.** The app opened cream
  09:00–17:00 because Living was the default. Also: system-event card had
  a hard 44dp height and cut its own second line; the hero greeting lost
  to the engine's own «ФОКУС → ВАРТА» transition.
- 13:25 — `0b925d43` hero moved off centre to sit above the composer.
- 13:31 — **the phone appeared.** Samsung A56, Android 16, arm64, with the
  owner's REAL profile, vault and chats. `ui.sh reset` now refuses to wipe
  a physical device.
- 13:35 — second wave out, partitioned by DIRECTORY: core-data+core-ai
  (destructive migration, vault plaintext, token ceiling), app/ (startup
  hang, theme pref migration, static analysis), core-design/ (light-theme
  contrast, unwired tokens), feature-stream/ui/map/ (first real look at a
  basemap that actually renders).

- 14:00 — third wave lands. 20 commits, gate green, app verified running.
  Every worker corrected something I or a predecessor had asserted:

  **The vault sealing opened a hole while closing one.** A value can now
  fail to open independently of the database key, and one unreadable row
  ended the Flow — blank vault, no way to delete the offending row.
  Fixed per-row. Also: `fallbackToDestructiveMigration` covered
  *downgrades*, so the comment claiming Room would raise on a downgrade
  was false the whole time it sat there. Sideloading backwards used to
  wipe the vault silently; now it hard-fails.

  **The local model looked stupid because of arithmetic.** The operator's
  turn was clipped to the whole budget, then the system block was
  appended unmeasured — overflow equalled the system block at every
  window, and since `setMaxTokens` is one cache, it ate the output
  reserve. The model had ~20 tokens to answer in. Now 255/256, 1022/1024.

  **AmberNight was not the approved prototype.** I told the owner ground
  and ink were already correct; they were not — four tokens off, and
  `glassBorder` was an opaque near-black where the etalon draws warm ink
  at 10%, so `background(theme.glassBorder)` gave a hairline in four
  palettes and a black bar in the fifth. `PhantomPalette` had zero
  references repo-wide while its own doc claimed to be AmberNight's
  source.

  **Ten cards fabricated content.** Not a crash class — a truth class.
  `WeatherWidget` fell back to «Київ, 22°, Ясно» on any parse failure and
  `MapWidget` to Kyiv's centre, which fires *precisely when there is no
  position*: a navigation surface answering "where?" with a real map of a
  place nobody named. A spreadsheet card printed an invented café ledger
  with a «СУМА ТРАВНЯ 96.30 $» footer under every sheet.

  Ratchets: Cyrillic 2055 → 2030, corners 143 → 141. Both at ground.

## The tool schema was decoration (owner's own commit, 15:00)

Every tool declares an `argsSchema`, it is rendered into the system prompt,
and nothing ever read it again. Each tool parsed its own args, so a missing
required field or a wrong type did not fail — it became a default. The tool
ran on arguments the model never sent and the operator got a confident
answer computed from nothing. Two workers found this independently and
neither could fix it: the parser is in core-symbiote, their dispatch paths
were elsewhere.

`ToolExecutor.run` is the one chokepoint every call passes through, so the
check lives there and returns `bad_args` before execution. It names the
problem, because a model told «missing required 'cmd'» fixes the call next
turn and a model told nothing repeats it. Not counted against the circuit
breaker — the tool never ran.

Deliberately permissive twice: unknown keys pass (models pad calls with
commentary, and refusing that breaks tools that work today), and a number
sent as `"500"` passes (the wire has no types, local models quote
everything, the tool would have parsed it). `SchemaVocabularyTest` pins all
212 declarations across three modules to six type words.

## Process failure, recorded

I told every worker to `git add -A && git commit`. In a tree with five
workers that stages *other people's* files, and one duly swept the owner's
core-symbiote work into its own commit — so the reasoning above lives here
rather than in the commit message where it belonged. Nothing was lost, and
the code carries its own why. Next wave: `git add <your paths>`, never `-A`.
It can also commit a neighbour's half-written file and break the build for
everyone, which is the same class of mistake as the earlier partition-by-
feature error.

## Wave four — the dead-weight rumour was wrong

The ~5000-line estimate does not hold. A worker proved 199 of 209 top-level
declarations in the five feature modules reachable from a real entry point,
and deleted **307 lines it was certain about rather than guessing at more**
— the right call. The orphaned pre-island screens live in `app/` and
`feature-stream/`, not there. Corrections it made to its own first pass:
`feature-car`'s two screens looked unreferenced and are not (`CarHomeScreen`
pushes both), and `feature-godmode` has no screen because it is reached by
*asking in chat* — five registered tools, entirely live.

Three things it left for the owner: `PhantomWallpaperService` is dead from
MainActivity but genuinely reachable from the system wallpaper picker, so
it stays until the product decides; `wear/` is 300 MB of stale build output
with zero sources and no gradle module, untracked, awaiting the owner's
word; and two of its deletions are committed under a neighbour's message.

Both god-object extractions were left **orphaned** when their workers died —
`AppEventBus` and `AiHistoryBuilder` existed, compiled, and nothing called
them, so the tree carried two copies of each and used the old one. Finished
both. AppContainer keeps the channels as delegating properties because ~90
call sites reach through the old names; StreamViewModel 4139 → 4075.

Ratchets: corners 141 → 139. Verified on device: clean install → chat,
greeted by name and hour.

## The ~25 hidden screens are not hidden — premise overturned

I carried this all night and briefed two workers on it: the island reduced
navigation to five slots, so ~25 screens were orphaned and should be deleted.
**It is false.** Every one of them is reachable, by two live gestures:

- **Long-press the chat header** → `SurfaceTray`, which is passed
  `SurfaceId.visible(isDebug)` — that is `values()` minus dev-only, i.e.
  *every* surface. Vault, diary, motion, whisper, present, lens, agenda,
  people, creator, body, hands, lifegraph.
- **Long-press the Orb** → `chamberPetals()` → Vault, Agenda, People, Lens,
  Settings, and «ще…» → the constellation grid for the rest.

Verified myself before accepting it. The island shrank the PRIMARY
navigation; it never orphaned anything. So the product question is not "what
do we delete" but "is a long-press the right door for twelve surfaces" —
a discoverability decision, and mine.

Only 890 lines were genuinely dead: four superseded screens whose own KDoc
admitted they were awaiting wiring that arrived elsewhere, plus a stub.

Two things dead-but-valuable, left for the owner. `ui/report/` is 4,743
lines, unit-tested, and **one line from live** — the `WidgetRenderer` branch
exists, the catalogue entry in the system prompt was never written, and its
own KDoc says exactly that. `WhisperSettingsScreen` is the sole switch for a
whole foreground-service subsystem; deleting it strands all of it.

## The sections are honest — the shell around them is not

Walked five sections on device after making them discoverable, expecting to
confirm the owner's «розділи гівно». Half wrong. The *content* is the most
honest thing in the app:

- ДЕНЬ: «немає жодного фіксу — прогноз буде, щойно з'явиться позиція», and
  live alert data — 7 oblasts by name, from the real feed.
- ЛЮДИ: «зараз СОЛО — жодного спарованого вузла».
- ТІЛО: «немає датчика» per row.
- and each says outright it will not invent: «а не вигадані події».

That is the discipline we spent the night enforcing on the widgets, already
present here. What is bad is everything around the content:

**Every section was a dead end.** `IslandHost` allow-listed three routes, so
on all twelve chambers the app's entire navigation vanished and the only way
back was a system gesture nothing on screen announced. Inverted: the island
hides on the four flows and shows on every place.

**Nine-tenths of each screen is void.** An honest sentence at the top and
black to the bottom. Honest is not the same as finished.

Two things I thought were bugs and were not: `&#10;` and `&amp;` in the
section text are uiautomator escaping its own XML dump, not literals leaking
into the UI. Read the screenshot, not the dump.

One real find left for its owner: LifeGraphScreen's header is
«PAM'YAT · VISUAL TIMELINE & LOG ARCHIVE» — Ukrainian transliterated into
Latin, in a Ukrainian app.

## Verify before fixing — the tally

Five apparent defects tonight were not defects. Blank map (emulator's
software GL), dead «Далі» button (IME occlusion), English island labels
(emulator locale), assistant bubble clipped by the island (mid-scroll,
clears at the bottom), and raw `[LOCATION:…]` in an operator bubble
(already fixed weeks ago; the message on the phone is dated 18 ЛИП and
predates the fix). Every one would have cost a wrong change. Look first.

## Partitioning mistake, recorded

The alert worker was given "air-raid awareness" and its natural home turned
out to be `feature-stream/ui/map/` — the same package I was editing for the
search chrome. Its half-written `AirAlertWatch.kt` broke my build mid-edit.
Nothing was lost, but the lesson is the architect's: partition by DIRECTORY,
not by feature, and say the directory out loud in the brief.

## Known limits of this rig

- **MapLibre does not render on this emulator** (software GL). Tiles fetch
  fine — HTTP 200 from openfreemap — but the canvas stays blank. Map *chrome*
  is Compose and does render, so chrome work is verifiable and basemap work
  is not. Do not "fix" the blank map from a screenshot.
- **A `Daemon compilation failed: null` can leave a GREEN build that CRASHES.**
  Worst trap of the night. Gradle reported BUILD SUCCESSFUL while that line
  sat in the log, and the APK shipped without `WidgetEventBus` and
  `StreamSurfaceKt` — classes whose sources were right there. On device it
  read as `NoClassDefFoundError`, which looks exactly like someone deleting a
  live class. Nobody had: the incremental cache was lying. If that string
  appears anywhere in the log, the build did not succeed no matter what the
  last line says — `rm -rf */build/tmp/kotlin-classes` and rebuild with
  `--rerun-tasks` before believing anything, and never chase a
  `NoClassDefFoundError` whose source file exists.
- **15 GB is the real constraint.** The emulator (~4 GB) plus a Gradle daemon
  plus workers took the machine to 11/15 and the daemon was killed mid-run:
  "Gradle build daemon disappeared unexpectedly". That is not a code failure
  and retrying it blind wastes twenty minutes — kill the emulator, confirm
  with `free -g`, then re-run. Two other infra failures look like code and
  are not: `duplicate class *Dao_Impl` and a missing `kspCaches` directory
  are both concurrent-build races between workers, and both pass on retry.
  Any gate loop should retry on those three strings and only those.
- The IME covers the bottom third and uiautomator still reports node bounds
  under it. `scripts/ui.sh tap` now sends ESCAPE first.
- Emulator locale must be uk-UA or `values-en` wins and every label lies.
- `scripts/ui.sh` + `$CLAUDE_JOB_DIR/tmp/firstrun.sh` drive a clean install
  to the chat screen in ~60s.

## 2026-07-27 evening — state at compaction

107 commits since 395fecf3 in phantom-companion, 4 in phantom-os. Tree
clean, all gates green.

### The core loop, first end-to-end test today

Typed «17+25» and waited. Three failures, then an answer. What that cost
and taught:

- The thread said «мережа або DNS». It was wrong: DNS resolved and the host
  pinged in 500ms. Real cause `dlopen failed: libllm_inference_engine_jni.so
  not found`. MediaPipe 0.10.14's AAR carries **arm64-v8a only** — verified
  by unzipping it. No x86_64 build exists to ship.
- `httpCode == 0` is the catch-all for every non-HTTP throwable, not just
  socket ones. It now reads the throwable instead of asserting a cause.
- `availableRoutes()` advertised LOCAL_GEMMA whenever a model FILE existed,
  never asking whether the class links. Now checks both, resolved once.
- The morning's log-privacy fix hid the exception message that named the
  failure. Restored for the httpCode==0 arm only, allow-listed with reason.

### Open, in priority order

1. **The answer is wrong.** «17 + 25 + 17 + 25 + 17 + 25 = 126» — it summed
   three separate turns. AiHistoryBuilder correctly excludes Notices, so
   those three operator bubbles are legitimately in history. Whether that is
   the model or the prompt is UNDIAGNOSED. Start here.
2. **The thread does not auto-scroll to a new arrival** and shows a «⌄ 3»
   unread pill instead. StreamScreen.kt:240 gates the scroll on
   `atBottom || lastIsOwn`; both were false. Worth deciding, not assuming.
3. **StreamViewModel is 4075 lines.** `finalizeAssistant` (487) carries a
   documented double-fire hazard: side effects are deferred because the CAS
   lambda can re-run. Needs a dedicated session, not a tired hour.
4. The zараз duplicate actions: «Відкрити розмову» now sits beside the
   island's own chat entry. Product call, deliberately left.

### Rig laws (cost hours today, do not re-learn)

- The emulator is x86_64 + SwiftShader. It renders **white text as red** —
  chased that through three wrong fixes before measuring the layer at
  runtime. Verify colour on the phone (arm64, RFCY61HG0WB) or not at all.
- The phone has TWO packages. `local.phantom.companion.debug` is the owner's
  REAL data — never touch. `store.debug` is disposable.
- `adb shell input text` does not URL-decode: `%3D` types literally.
- Tap coordinates from one screen's uiautomator dump are meaningless on
  another. Re-dump every time; I misdiagnosed two "dead buttons" this way.

### Firewalls added today (each because a defect appeared twice)

BottomChromeInsetFirewallTest (ground 0), LogContentFirewallTest,
RussianLeakFirewallTest. All read other modules as raw file I/O — they need
`--rerun-tasks` or they go stale green.

---

## 2026-07-28, 01:40 — second night, commits 108→129

### The verification lesson that cost the most

I ran gates as `./gradlew ... -q 2>&1 | grep -E '^e: '`. A **resource-merge
failure is not an `e:` line**, so a broken `strings.xml` compiled to "no
output" and read as green. No APK was produced for roughly twenty minutes
and every screenshot in that window was of a stale build. I only caught it
because a feature I had just written did not take effect on device.

`scripts/verify.sh` already had this right — it greps `^BUILD SUCCESSFUL`
and carries a comment about exit status being lost through a pipe. The gate
is the build's own verdict. Do not hand-roll it again.

The trigger: normalising `ʼ` (U+02BC) to `'` inside string resources. In
Android XML a bare apostrophe must be escaped `\'`. Kotlin literals need no
escaping — the same sweep across both file types is a trap.

### Shipped

**Chat.** Threads opened on the operator's *oldest* message (a LazyListState
starts at index 0 and nothing moved it). Sending never reliably scrolled:
the follow rule asked whether the last item was the operator's, but the view
model appends the streaming assistant placeholder in the same batch, so the
answer is no on every turn. The thread now holds a tail **anchor** — stuck
to the bottom until you drag away, re-attaching when you return. Position
alone cannot answer this: right after the open-at-tail scroll the layout has
not been re-measured, so an honest `atBottom` says false for a message that
is already on screen.

**«17 + 25 + 17 + 25 + 17 + 25 = 126» is solved.** Not the model and not
history assembly. Two of those three asks had been killed by a cloud error
and re-sent, and history replayed all three as live turns. An ERROR notice
now retires the asks it interrupted; consecutive asks with no failure
between them stay whole, because sending a thought across three bubbles is
a real thing people do.

**First run.** `onCreate` fired a battery-optimisation system screen and a
five-permission barrage before `setContent` drew a pixel. Onboarding already
asks for all of it, one at a time, with reasons. Deferred past onboarding,
and asked **once ever** — declining is an answer.

**Naming.** The vault had five names (сховище / Vault / VAULT · ПРИМАРА /
vault / GHOST). The brand had seven (phantom, Phantom, PHANTOM, phantom-os,
PHANTOM os, Familiar, familiar). One each now.

**Sections.** The tray was fourteen one-word pills naming surfaces without
saying what was behind them. Each carries a sentence taken from that
screen's own title — two of my first drafts were wrong because I wrote them
from enum names without opening the screens.

**StreamViewModel** 4080 → 3847: a ~70-parameter constructor grouped into
seven parameter objects. Body untouched; the groups unpack to the same
private vals.

**ТВОРЕННЯ** persisted its draft — it was a plain `remember`, so leaving the
screen discarded whatever you had typed.

### Deliberate non-decisions

- **AppContainer stays at 2621 lines.** 169 lazy vals is a container doing
  what containers do. Splitting it is cosmetic and risks wiring everywhere.
- The zараз duplicate actions still stand. Still a product call.

### New firewalls

`EnglishInUkrainianFirewallTest` — Ukrainian strings carrying a Latin word
of 4+ letters, across **both** strings.xml and Kotlin literals, skipping
`$foo` templates so it reports words not identifiers. Took 106 findings to
zero. Also pins one apostrophe glyph (it caught ʼ U+02BC in a dozen places).

### Still open

- The **map** is unevaluable: SwiftShader renders it blank and only the
  emulator is attached. Needs the phone.
- `LensScreen` and `LifeGraphScreen` flagged for missing empty/error states,
  not yet opened.
- `finalizeAssistant` double-fire hazard, untouched.

## 2026-07-28, 03:30 — commits 129→141

### New functionality (not polish)

**ДЕНЬ reads the phone's calendar.** The screen had promised «розклад
з'явиться тут, коли буде справжній міст із дозволом» for a bridge nobody
built. It is `CalendarContract` — a local content-resolver read, no account,
no network, no API to route around. Asked in context and once; refused, the
honest line stays and the offer does not return; granted but empty, it says
the day is empty. Verified against rows inserted into the emulator's
provider (`content insert --uri content://com.android.calendar/...`).

**ТВОРЕННЯ persists its draft.** It was a plain `remember` — leaving the
screen discarded everything typed, and there was no save action anywhere.

### The defect class worth remembering

`accent.state.name` printed «GHOST» on a Ukrainian screen. Every i18n gate
reads *literals*, so none could see it: an identifier is not a literal and a
constant name carries no Cyrillic. `EnumNameAsLabelFirewallTest` now forbids
a `text`/`label`/`title` argument being fed a `.name` unless the receiver is
a property that genuinely holds a human name.

Sweeping it found two more (sensor status, error severity) — and fixing
those exposed what static analysis cannot see: the row *preferred* a `label`
field whose value is the literal token «SENSOR», so the fallback I had just
translated never ran, and a 56dp column measured for «TTS» ran «сенсори»
flush into its value. **The sweep finds the defect; the device finds what
the defect was hiding.**

Then I created the same bug I had just fixed — «попередження» into a 60dp
box built for «warn». Fixed one instance, made its twin in the same file.
Only sweeping the class caught it.

### Process failures, both mine

- Committed twice on a red gate. `cmd && git commit` still reaches the
  commit when `cmd` writes its failure to a log instead of exiting non-zero.
  Read the verdict before committing, every time.
- Whitelisted «Settings» as a proper noun when the firewall flagged it,
  without reading the string. It is not a brand — it is a place in this app,
  and the copy was telling operators to open a menu that does not exist.

### Ground

Cyrillic ratchet 1865 → 1855 across the night, always by extraction, never
by raising a ceiling to make a change pass.

### Walked end to end this night

chat · onboarding · settings · diagnostics · section tray · зараз · ОКО ·
ПАМ'ЯТЬ · ТІЛО · РУКИ · ДЕНЬ · ЩОДЕННИК · ЛЮДИ · ТВОРЕННЯ · СХОВИЩЕ ·
шепіт · пошук в історії · усі простори

### Still open

- The **map**: SwiftShader renders it blank and only the emulator is
  attached. The largest surface in the product, never seen to render.
- **ТІЛО's step counter**: the emulator's 12 Goldfish sensors include no
  step counter, so the code cannot be verified here. Deliberately not
  written blind.
- `finalizeAssistant` double-fire hazard, untouched.

## 2026-07-30 — state at compaction (commit a29d516d)

### Landed since the last entry

**PHANTOM sees the calendar it was given.** ДЕНЬ could show today's schedule
after the bridge landed, but the model answering «сплануй день» never saw it.
`AgendaContextProvider` / `AgendaContextFormatter` now ride the prompt
preamble beside location, mood and geofence, in the same shape those use.
Refused (`[AGENDA:unavailable]`) and free (`[AGENDA:empty]`) stay distinct —
collapsed, the model plans over a meeting nobody showed it.

### ⚠ ANOTHER EDITOR IS IN THIS TREE — read before committing

A **Crystal City** refactor is mid-flight and is NOT mine:

  D  ui/map/crystal/{BuildingTileStore,CityBuilding,CrystalCityFeed,
     CrystalGeo,OverpassBuildingsClient}.kt
  D  ui/map/crystal/mesh/{BuildingPaletteSource,CityMesh,CityMesher,Earcut,
     Polygon2D,RoofGenerators,WindowGenerators}.kt
  D  ui/map/crystal/render/CityVertexBaker.kt
  D  test/.../crystal/{CrystalGeoTest,mesh/CityMesherTest}.kt
  M  MapCanvas.kt, MapTerrainLayers.kt, crystal/render/{CityLayerHost,CityRenderer}.kt
  M  gradle/libs.versions.toml
  ?? strip_renderer.py (repo root)

`CityBuildingParserTest.kt` survives and references deleted sources, so
**`:feature-stream:testDebugUnitTest` cannot compile right now.** That is
theirs, not a regression. Do not "fix" it and do not `git add -A` — commit by
explicit path only until that work lands. `:core-ai` is green and
`:app:assembleStoreDebug` succeeds.

### Gate law (again, it cost real work twice)

- Verdict = the tool's own. Write the log to a file, read `^BUILD SUCCESSFUL`.
  `grep '^e:'` misses resource-merge failures → green log, stale APK.
- `cmd && git commit` still commits when cmd logs its failure instead of
  exiting non-zero. Read before committing.
- core-design ratchets need `--rerun-tasks` or they are stale-green.
- Ratchet rose → extract to strings.xml (+ values-en). Never raise the ceiling.
  Cyrillic ground went 1865 → 1855 this way; it is 1855 now.

### Open, in the order I would take them

1. **Map** — never seen rendered. SwiftShader draws it blank; needs the phone
   (RFCY61HG0WB, arm64). Largest surface in the product, wholly unjudged.
2. **ТІЛО step counter** — emulator has 12 Goldfish sensors, none of them a
   step counter. Deliberately not written blind.
3. `finalizeAssistant` double-fire hazard in StreamViewModel, untouched.
4. AppContainer 2621 lines — deliberately NOT split; 169 lazy vals is a
   container doing its job, and splitting risks wiring across every surface.

### Device rules that cost hours

- Two packages on the phone: `local.phantom.companion.debug` is the owner's
  REAL data — never touch. `store.debug` is disposable.
- uiautomator coordinates are valid for that dump only. Re-dump every time.
- Emulator colour lies (SwiftShader). Verify colour on the phone or not at all.

---

## 30 July · morning — the chat that went mute, and one hypothesis I got wrong

### The foreign refactor: decided as owner

Antigravity was editing the same working tree. It deleted 2626 lines of the
Crystal City CPU mesh pipeline (Earcut, CityMesher, roof/window generators,
the Overpass feed, the tile store, the vertex baker), pinned MapLibre's native
fill-extrusions permanently visible and bumped maplibre 11.5.1 → 11.8.1.
Stated reason: CPU OOM. The reason may well be right; the state it left could
not ship — dead stubs (`fun retain(tiles: Set<Any>) {}`), an empty
LaunchedEffect, a `setBuildingExtrusionsVisible` that ignores its own argument,
five test files referencing deleted classes, and `strip_renderer.py` (regex
surgery on Kotlin) dropped at the repo root.

Preserved whole on branch `salvage/crystal-city-native-extrusions`; working
tree reverted. **`:feature-stream:testDebugUnitTest` compiles and passes again**
— that gate had been down since the refactor arrived.

Two editors in one tree is not parallel work. Separate by branch or by clone.

### The defect that mattered most

Tapping the hero chip PHANTOM itself offers put the question on screen and then
NOTHING happened — no answer, no error, no sign of life, for as long as the
brain wake took. The turn was never lost: `submit()` holds the prompt when the
brain is not Ready and replays it on Ready. But the only trace of that lived in
`BrainLine` under the composer, invisible while the composer is collapsed to
the island. The screen knew everything and told the operator nothing.

`HeldTurnRow` now shows the wait under the bubble that is waiting. Verified on
device: 8% → 16%, retry replayed the held prompt by itself.

### Operator copy that was really log output

`PhantomError` documents its own contract — source is a subsystem name for
Діагностика, cause is "never shown verbatim to operator". The chat broke both:
every notice rendered as `"${err.source}: ${err.message}"` («TtsEngine:»,
«VoiceManager:», «AIRouter:»), and the download failure put the raw Java
exception in `message`, producing *ModelManager: Qwen 2.5 · 0.5B (int8):
Software caused connection abort* mid-Ukrainian-conversation. Now: «не вдалось
завантажити мозок».

Two more messages handed back chores that would not have fixed anything:
«додай ключ Gemini» fires when the router's *constructor* throws (a key cannot
help; the fallback is null for the process), and «перевір моделі» sits four
lines under `hearingProvisioner.ensureHearing()`.

### A performance hypothesis, measured and abandoned

Logcat showed 1.5 MB/s of garbage and ~25 redraw batches per 25 s on what I
took for an idle chat. Root cause looked certain: `LocalBreathPhase` carried a
raw `Float` changing 60×/s, read in the composition scope of the root shell and
of every consumer.

Refactored it to a `FloatState` holder written by `animate()`, with all reads
moved into `drawBehind`/`graphicsLayer`. **Then measured properly and found no
win.** With the network off, the OLD and the NEW build both render *zero*
frames and run *zero* GCs across 60 s idle. The churn was a model download in
flight the whole time.

Kept the refactor (a per-frame value in a CompositionLocal is a real hazard and
this removes a class of future regressions), threw away the brightness
quantisation added under the false hypothesis, and said so in the commit.
**Rule: measure the thing you are about to claim, under the conditions you will
claim it for.**

### Ratchets added

- `PromptToolContractFirewallTest` — an instrument the system prompt names by
  hand (~20 of them, in Ukrainian prose) must exist among the 67 registered
  tools. Shape-based: a dotted token whose namespace is a real tool family must
  name a real tool, so `it.trim` and `com.spotify` pass untouched.
- Cyrillic ground 1855 → 1852 (mic-privacy extraction, then «СВІТ»/«СХОВОК»).

### Product facts worth keeping

- 67 tools, ~45 card types, all advertised to the model on every turn. No dead
  names. The instruments were never the gap — the *path* and the *feedback*
  were.
- The stratum switcher called the map and the vault «СВІТ»/«СХОВОК»; the island
  one thumb below says «МАПА»/«СХОВИЩЕ». Each of the odd names appeared exactly
  once in the tree.
- Error notices are **persisted into chat history**. A transient download
  failure becomes a permanent row in the operator's conversation. Not fixed.

### Decomposition, started

`StreamViewModel` 3845 → 3827. `AssistantTurnText` extracted with seven tests:
reconciling the streamed text, the trailing Final and the bubble, plus the
hidden `⟦NEXT⟧` trailer. Every rule in it was a bug once and none was reachable
by a test before.

Remaining shape of the god object: `finalizeAssistant` 478, `submit` 455,
`dispatch` 326, and ~800 lines of tool/agent continuation
(`continueAfterTool`, `runToolCall`, `continueAfterParallelAgents`,
`continueAfterAgent`, `dispatchNativeToolCall`) — that last group is one
cohesive subsystem and the obvious next extraction.

---

## Tool calls run strictly one at a time, and the model is re-prompted before the rest have run

Traced 2026-07-30 from an operator report: «не все виконує, інструментами
неправильно користується, завжди послідовно».

The wire is fine. `GeminiCloudRoute` loops over a response's `parts` and emits
one `AIChunk.ToolCall` per `functionCall`, with a per-turn `callCounter` — so
when the model asks for three tools at once, three chunks arrive.

The consumer is where it breaks. In `StreamViewModel`:

    .collect { chunk ->
        is AIChunk.ToolCall -> dispatchNativeToolCall(chunk, …)   // suspend
    }

`dispatchNativeToolCall` → `runToolCall` (runs the tool to completion) →
`continueAfterTool` (**re-calls the model with that one result**). All of it
inside one `collect` emission. Flow collection is sequential by contract, so:

1. call 1 emits → tool runs → model is re-prompted with only call 1's result;
2. that whole nested round-trip must finish before the collector takes call 2;
3. by then the model has already produced a follow-up answer for a turn that
   was supposed to have three results in it.

So three independent tools cost three serial round-trips instead of one
parallel batch, and calls 2..n land in a conversation that has moved on — which
is exactly «doesn't do all of it» and «errors with the tools».

The fix is not a patch: the turn needs to collect the whole batch of tool calls
first, run the independent ones concurrently, and re-prompt **once** with all
results. That is the next architectural block, and it is the highest-value one
left — it is the difference between an assistant that can act and one that
fumbles.

### Surfaces: 14 is too many, and the audit says which

Three read-only auditors covered the eleven surfaces I had not opened. Their
verdicts, on shells rather than defects:

- **ТВОРЕННЯ** — "a near-empty shell": a header, one template pill, a text
  field and an autosave. No run, no output pane, no «макети» despite the
  subtitle promising them. Its own invisible caret (black on a dark theme).
- **ШЕПІТ** — "a prototype demo shipping as a product screen". Now honest, but
  still a settings card whose toggles change nothing outside the composition.
- **РУХ** — a dev-only animation catalogue, entirely English, whose tray blurb
  promises movement data.
- **ЛЮДИ** — one card, honest, but one card.
- **ЗАРАЗ** — the navigation layer its own comments describe was never built:
  an empty `else`, an unused `PresenceAction`, a dead `onOpenHistory`.

A chat-first product with fourteen top-level rooms, five of them empty, is not
a product with fourteen features.

---

## 31 July · state of both products, written against a session limit

A builder agent died mid-task on a session limit tonight (resets 01:20). It had
run npm install and reached the asset gate; no code was written, its worktree
is clean, nothing lost. Two earlier agents died the same way on spend limits.
Assume it happens; commit per working increment; write state down.

### PHANTOM Companion — where it stands

193 commits since 395fecf3. Build, feature-stream, core-design (17 ratchets),
core-ai all green. The field flavour is installed on the owner's phone with his
real data intact; the disposable store package is gone.

Fixed from the owner's own walk and his real data, all traced to cause:
- voice was the Android robot forever — the brain and the ear self-provision,
  the voice had no provisioner at all
- the map never turned while walking — HEADING_FLOOR_MPS stood at 1.4 m/s, and
  the file's own neighbouring comment records pedestrian fixes at 1.2–1.3
- the compass read bearing only on camera IDLE, so it lied through every turn
- the trail drew raw fixes while the puck drew the locked one — two positions
  on one screen
- routes only worked at home: the debug default routes through the laptop's LAN
  address; a public router now stands behind it
- «повторити · налаштування» sat under a bank SMS and the morning digest —
  actions were handed to every notice, not only failures
- tool calls ran one at a time with a re-prompt after each; the turn now runs
  the batch and asks once (red→green acceptance test)

### The one thing still unfound

A widget card renders with roughly 600px of void between two lines of text.
Photographed on the owner's phone; the card is one of the seven expandable
types (map, ar-map, code-runner, markdown, table, artifact, canvas).

Ruled out, each by reading the code: the canvas `gap` node is clamped 2–64dp;
the report hydration skeleton holds its reserved height for one frame only;
an empty chart collapses to 0dp and shows an honest line; WidgetBubble and
TemplateScaffold impose no height.

Do not guess further. It needs the payload of that specific card — either the
owner opens the chat on it, or a temporary render-time log of type and size
(no content, so LogContentFirewallTest stays satisfied).

### PHANTOM OS — what was started

The owner exported a Claude Design bundle («AI Agent Office Workspace») to
~/Завантажене/AI Agent Office Workspace-handoff.zip, extracted under the
session scratchpad. Atrium.dc.html is the chrome — north-star header, view
tabs, meters. Its 3D stage is stations in a circle with packets flying.

The owner does NOT want that stage. He wants a game-like living world: rigged
people or robots that walk an office floor, work, and hand things to each
other. Two hard rules carried into the brief: three.js must be bundled, not
pulled from unpkg at runtime; and every movement must correspond to a real
backend event — an empty office when nothing runs, never idle characters for
atmosphere.

Target: PHANTOM_OS_BLUEPRINT/phantom-os (src/desktop, src/backend/agent).
Worktree branch worktree-agent-ad708d595edbeb501 is clean and reusable.

---

## Night 2 — 31 July, 02:17 onward

### PHANTOM Companion

**Saved map territories** (448096b0). The cloud button on the map rail used to
start a download of whatever was on screen — no inventory, no deletion, and
nothing drawn to say where the saved ground was. It now opens a docked panel
and paints every stored pack on the map: accent for this basemap, muted for a
pack pulled against another style (which renders blank here), dim while
downloading. Row tap flies to it, second bin tap deletes. The primary action
reads `OfflineCoverage.verdict` — already written, never called by anything —
so «already saved» offers nothing and «partial» offers the rest.

**The map overview stops re-deriving the card's own facts** (9e1ebb8d). The
card computes open/closed from the raw OSM hours via `OpeningHours`; the
overview under it was handed the raw string and a clock and left to work the
same answer out in prose. It now receives the computed state, the extra facts
(brand, floor), what else of the kind is nearby with the nearest named, and —
when the identity came from the road mesh rather than a directory — a note
saying so, which is the case where it confidently described the next building.

**PHANTOM's own voice ships in the APK** (c79b746b). The field complaint
«озвучка не з моделі а вбудованої в андроід» had a cause no operator could
fix: 1057 baked clips existed only on this laptop, installable solely via
`scripts/ship-navvoice.sh` over adb. They are now an asset of :core-voice,
11 MB, in the build. A pushed pack still wins for iteration. `noCompress`
on ogg matters — `openFd` throws on a compressed asset and the throw is
silent, falling straight back to the voice this replaces. A test checks the
manifest against the shipped clips instead of trusting it.

**A calendar that would not open** (2283b8d9). Identical to the call-journal
defect: `runCatching { … }.getOrDefault(emptyList())` meant a failed provider
read printed «на сьогодні в календарі порожньо». Three states now, and
`today()` throws rather than lying.

### PHANTOM OS — the office is built

Seven commits on `rebuild/pc-day1`, ending `9f43ef9`: office floor with five
department zones and a lobby; a pure event→state reducer under 34 tests; one
character per live task at its own desk; walking in, crossing to a department,
walking out; `team.message` handovers; body state for thinking/blocked/
waiting/failed; camera and click-to-inspect. 97 vitest tests, tsc and cargo
clean. Figures are code-built primitives — no glTF was fetched, per the
brief's own fallback.

What that build found in the backend, which the brief had wrong:

- The terminal events (`task.completed/failed/stopped/timeout`) were missing
  from the brief entirely. Without them nobody ever leaves the floor.
- `task.started` carries no role. The only honest source of a specialist's
  department is the `[role=…]` prefix `spawn.py` writes into the goal string.
- Mission tasks never emit `task.started` at all — `mission.started` is their
  only birth event.
- **`team.message` has zero production callers.** The handover animation is
  wired and will play the moment the backend emits one. Nothing does.
- **`_BACKGROUND_ALLOWED_EVENTS` is six items**, and every spawned specialist
  runs on `track="background"` — so on the wire a specialist can only appear
  and disappear. Rich body states are foreground-only.

The last two are being closed now; they are the difference between an office
that is honest and an office that is honest *and* populated.

### Still unverified on a device

The artifact card height, notice-action filtering, the call journal's three
states, the calendar's three states, the territories panel, and the shipped
nav voice. None of these have been seen on the phone — it was not connected
tonight.

---

## Night 2, second half — 31 July

### Both chat god-objects are gone

`StreamViewModel` 3826 → 1961 across nine files; `StreamScreen` 1878 → 982
across four. Every seam its own commit, each independently gate-green.

The `StreamViewModel` work included the only commit of the night that
*removed* logic rather than moving it: `continueAfterTool`,
`continueAfterAgent` and `continueAfterParallelAgents` were the same 130
lines three times, differing only in a fuse-notice string and what got
appended to history. With `widgetAlreadyShown` null on both agent paths the
tool version's two extra conditions collapse to the agent version exactly —
so one body is provably equivalent to all three. A fourth copy of the
`Throwable → AIError` mapping lived in `submit()`.

Two seams were deliberately NOT taken, and both refusals were right:
- the scroll/tail-follow engine. `atBottom` is a `derivedStateOf` read at
  four sites; hoisting it into a helper that returns a plain Boolean moves
  the read into the helper's restart scope and the caller stops recomposing.
  Fixable, but **the gate cannot prove it** — there are no Compose UI tests
  for scroll, so a regression would surface only on device, in the part of
  the surface the operator feels most.
- `TextSelectionSheet`, pinned to `StreamScreen.kt` by
  `BottomChromeInsetFirewallTest`: the rule is per-file, and moving the only
  `systemBarsPadding()` away from the only `LocalBottomChromeInset` created a
  fresh offender. The worker moved it back rather than adding an inset that
  would have changed a full-screen modal's layout.

### Three bugs the decomposition surfaced

Reported, not silently patched — then fixed by the owner in one commit:
`formatReason` fell back to `else -> this`, so an untranslated policy key
reached the operator raw («дзвінок · +380… · spam-heuristic»); the const
documented as its Ukrainian phrasing was permanently `""`; and a second
private `AI_HISTORY_LIMIT_TURNS` carried its own KDoc beside the real one —
the kind of duplicate where changing the number changes nothing.

### The greeting followed the launch, not the clock

`remember` with no key read the hour once. Left running past dusk, PHANTOM
kept saying «доброго ранку». Now wakes at the hour boundary.

### PHANTOM OS — the office is finished

Three contract defects closed (`d9ce54c`, `c55dd5e`). Delegation now
animates: the message is held until its addressee appears rather than
resolved-and-dropped, so the commonest handover in the system stopped being
invisible. Party resolution takes the exact task id and refuses to guess
between two specialists of the same role. And the return trip works — a
finished specialist detours to the desk of whoever awaited it, delivers,
then leaves.

Two finds beyond the brief: `SUBSTATE_POSTURE` had no `reflecting` entry
though the kernel emits it, and the upward report could never have drawn
because the sender was already departing when it fired.

### Machine

Sleep, idle and lid-close inhibited for 15h. `hypridle` only *delays*
suspend; nothing was actually blocking it.
