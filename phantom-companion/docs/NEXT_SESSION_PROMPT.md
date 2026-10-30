# Next Claude Code Session — Full Prompt

> Copy-paste **everything between the two `===` lines** into a new
> Claude Code session as the very first message. This briefs the
> next agent on the entire context, where to find references, and
> what to build.

```
============================================================
You are joining the PHANTOM Companion project mid-stream. The
previous session closed Phase 0 of the companion-v2 branch and
spent considerable effort creating a comprehensive design prototype
(via Claude Design) and supporting documentation. Your job is to
take all of that and **implement Phase 1 in production Kotlin /
Jetpack Compose** on real Android hardware.

═══ READ FIRST (in this exact order, do not skip) ═══

1. `docs/COMPANION_CONTRACT.md`
   Wire contract between phone and phantom-os backend. 14 documented
   ambiguities. Read all 14, especially §10.1 (/ws path), §10.4
   (multi-endpoint retry), §10.8 (server_proof verification), §10.11
   (profile_sync channel), §10.13 (agent.stream channel rename),
   §10.14 (PIN-resolve missing server-side).

2. `docs/ROADMAP.md`
   Phases that have shipped. Phase-0 closure is the most recent;
   everything above it is done. Below the closed phases are aspirational.

3. `docs/VISION_NEXT.md`
   Engineering roadmap covering Tiers 5 through 13 (Phases 1-9). This
   is the technical decomposition of what to build. Phase 1 is the
   immediate next step — UI surfaces for already-flowing channels.

4. `docs/DESIGN_BRIEF.md`
   705-line atmospheric design specification. Read all 18 sections.
   This is your visual language north star. The "Alien Elegance"
   manifesto + Glass Stack tokens + per-state visual matrix + sound
   catalog + animation catalog + anti-patterns + the core test
   ("presence vs dashboard").

5. `docs/PRESENCE_AND_CHAT.md`
   628-line chat architecture + 13-variant presence channel
   exploration. Defines the multi-tier presence stack (Whisper Line +
   Margin Glow + Floating Orb + Stream), full chat architecture
   (multi-thread, 8 message sources, rich inline content, file
   handlers for 14 formats, 80+ functional capabilities).

6. `~/.claude/projects/-home-radxa-programming-my-own-phantom-ai-PHANTOM-OS-BLUEPRINT-phantom-companion/memory/MEMORY.md`
   Index of 4 memory entries from prior session:
   - `pair_flow_gotchas.md` — 4 real bugs surfaced during live E2E
   - `nsc_cidr_pitfall.md` — Android NSC silently ignores CIDR
   - `phase0_intelligence_gap.md` — pipe alive, surface empty
   - `e2e_playbook.md` — adb commands + logcat filter that works

7. `git log --oneline 8e4016c..HEAD`
   The previous session's 19+ atomic commits. Each commit message has
   the rationale; do not repeat the same fixes.

═══ DESIGN PROTOTYPE REFERENCES ═══

The prior session iterated 4 times with Claude Design and produced
a ~5300-line React/CSS/WebGL prototype covering 30+ surfaces. It is
NOT in the repo (intentionally — it's a design medium, not source
code). It lives in `/tmp/phantom-design{,-2,-3,-4}/phantom-app2/`.
v4 is the latest and most complete. Each iteration added new
files; v4 has all of them:

- `phantom-app2/project/tokens.css`        — design tokens (use as truth for color, type, blur, motion values)
- `phantom-app2/project/orb.jsx`           — 3-tier Orb (CSS / Canvas2D / WebGL shader). Use the WebGL shader formula as the spec for the Filament implementation. Spring physics in `useSmoothedLook` is exactly what production should do.
- `phantom-app2/project/stream.jsx`        — Stream surface: SystemPulseHeader, ChatBubble, NoticeBar, TimerWidget, VitalsWidget, InsightCard, HandoffCard, MapWidget, InputRow
- `phantom-app2/project/extras.jsx`        — LockScreen, HomeScreen, FloatingOrbOverlay, LiveWallpaper, SettingsSheet
- `phantom-app2/project/surfaces.jsx`      — Onboarding 6-step morph, AR Pair scanner, VaultGate, DecisionDiary, MotionCatalog, TimeShiftedHelp, VoiceClonePreview, DialogueThrum, TTS via SpeechSynthesis (uk-UA), audio tones (chimeBell, crystalNotice, spikeAlert)
- `phantom-app2/project/phase2.jsx`        — TIER B chat-rich content: ThreadRail, ChartWidget, FileCard, CodeBlock, VoiceMemo, CitationPill, ToolTrace + Whisper Line scene + Margin Glow scene + Subliminal Pulse scene + Family Hive scene + SurfaceTray (long-press)
- `phantom-app2/project/phase3.jsx`        — TIER C + Phase 9 frontier: PdfChat, CalendarWeek, EmailTriage, SmartHome, SleepDashboard, ImageGen, MemoryPalace (3D WebGL), DreamJournal, NegotiationCourt, AmbientHealth, TypographyScene, ConversationalSettings

Read these as VISUAL REFERENCE only. **Do not literally port the
React component tree** — convert each to the Compose idiom. Match
colors, timings, easings, copy verbatim. The Ukrainian text in the
prototype is in the Familiar's voice (warm, ти-form, intimate);
preserve it word-for-word in the Compose port.

═══ CURRENT STATE (verified) ═══

Branch: `companion-v2-phase-0` (16+ commits ahead of `main` from prior session)

Working tree: clean

Tests: 218 testDebug tests, 0 failures (`./gradlew testDebugUnitTest`)

Build: green (`./gradlew :app:assembleDebug :app:lintDebug`)

Live verification: prior session ran the app on Samsung SM-A566B /
Android 16 against phantom-os v0.19.0 and confirmed:
  - Onboarding 6-step state machine works end-to-end
  - Pair flow with QR JSON paste reaches `Successfully claimed pairing`
  - WS connects: `Connected via http://158.196.114.238:8000`
  - Phantom-os pushes `sensor/snapshot` (500ms cadence) +
    `agent.stream/proactive.cycle` events; phone receives but no
    UI surfaces consume them yet — this is the gap to close

The "phase0 intelligence gap" memo describes the situation: the
WS pipeline is fully alive, every contract channel routes
correctly into typed Kotlin Flows in PhantomViewModel
(`agentEvents`, `chatEvents`, `mapEvents`, `controlVerbs`,
`handoffEvents`, `contextState`, `familiarState`, `pairEvents`,
`miscEvents`), but **zero Composables subscribe to any of them**.
Server pushes silently buffer. Operator sees a static welcome.
Phase 1 is exactly: subscribers + their UI manifestation.

═══ PHASE 1 IMPLEMENTATION ORDER ═══

Each phase = ONE atomic commit. Green build + tests + reinstall +
verify on device before moving to next. Use the design prototype
as visual reference for each.

**Phase 1-A — Stream rich content layer (the most visible win)**
   Compose surface: convert `phase2.jsx` content to Compose. Concretely:
   - Create `feature-stream/.../ui/widgets/ChartWidget.kt`  — accepts JSON `{kind, data, annotations}`. Supports `line` and `bar` first.
   - Create `.../ui/widgets/FileCard.kt`     — accepts `{kind, title, meta, extracted}`. Shows thumbnail + auto-extracted summary.
   - Create `.../ui/widgets/CodeBlock.kt`    — syntax highlight via `dev.snipme:highlights:0.7.1` library. Add Run button (no-op stub for now).
   - Create `.../ui/widgets/VoiceMemo.kt`    — waveform + scrubber + transcription overlay. Use `androidx.media3:media3-exoplayer` for playback.
   - Create `.../ui/widgets/ToolTrace.kt`    — subtle mono-text trace bar.
   - Create `.../ui/widgets/CitationPill.kt` — tappable inline pill.
   - Extend `WidgetRenderer.kt` to dispatch new types.

   Acceptance: AI returns JSON with one of these new types →
   widget renders inline in Stream. Reference: `phase2.jsx` lines 12-200.

**Phase 1-B — Multi-thread Stream**
   - Add `threadId: String` to `StreamItem` data class
   - `StreamViewModel` holds `threads: Map<ThreadId, List<StreamItem>>` plus `activeThreadId: StateFlow<ThreadId>`
   - Add `ThreadRail` Composable above Stream (pill row, horizontal scroll)
   - Routing: `chatEvents.session_id` lands items in the matching thread; messages without session_id go to "Зараз"
   Reference: `phase2.jsx` ThreadRail (lines 12-50)

**Phase 1-C — Insight subscriber for proactive cards**
   - Subscribe `phantomViewModel.agentEvents` in StreamScreen
   - Render each `agentEvents.proactive.cycle | proactive.pending_action` as an `InsightCard` StreamItem
   - Swipe-right dismiss
   - Action buttons inline ("Так, маршрут" / "Не зараз")
   Reference: `stream.jsx` InsightCard

**Phase 1-D — State morph from emotion.updated**
   - Subscribe `agentEvents` filtered by `kind == "emotion.updated"`
   - Mutate `LocalStateAccent` ProvideScope so colors morph live
   - Orb breathing rate adjusts based on emotion intensity
   Reference: `orb.jsx` `useSmoothedLook` (lines 20-60) — same spring physics in Compose

**Phase 1-E — Chat proactive subscriber**
   - Subscribe `chatEvents` of type `message.proactive`
   - Append as ASSISTANT StreamItem.Text with subtle "from-server" indicator
   - Goes into the active thread (or "Зараз" if no session_id)

**Phase 1-F — Lock Vault verb subscriber**
   - Subscribe `controlVerbs` of verb `lock_vault`
   - Hard clamp `vaultStore` (revoke biometric session)
   - Navigate up out of any sensitive screen
   - Show GHOST-mode briefly via SystemPulseHeader

**Phase 1-G — Handoff bottom sheet**
   - Subscribe `handoffEvents` of kind `created`
   - Show bottom-sheet (Material3 ModalBottomSheet) with 3 buttons: Прийняти / Відхилити / Пізніше
   - Backend acknowledgement via `POST /handoff/{id}/{accept|reject|cancel}` (use existing PhantomApi)
   Reference: `stream.jsx` HandoffCard + DESIGN_BRIEF V.9

═══ AFTER PHASE 1 ═══

Once 1-A through 1-G are landed, reassess. Operator may want:
- Phase 2 (multi-modal sensory) — vision pipelines
- Phase 3 (memory layers) — episodic / semantic / procedural
- Whisper Line overlay (separate phase, not in 1-A..G)
- Margin Glow on bezel (phantom-os-side equivalent might exist)

Ask operator before committing to next phase. Write each NEW
Phase as its own roadmap item in `docs/ROADMAP.md`.

═══ NON-NEGOTIABLE CONSTRAINTS ═══

1. **Read before edit.** Every file edit follows a Read of that
   file. Edit hook will reject otherwise.

2. **Atomic commits.** One Phase = one commit. Each must build green
   AND pass `./gradlew test :app:assembleDebug :app:lintDebug` on the
   Radxa host. No "WIP" commits.

3. **Don't break Phase 0.** The pair flow + WS connect work end-to-end.
   Verify after each phase by running `./gradlew test` and reinstalling
   on device if you have one connected.

4. **No dependency bumps without testing.** 155 lint-warned stale
   deps exist; ignore for Phase 1. Bumps are their own phase.

5. **No production NSC changes.** Debug-variant override is enough
   for E2E. Production NSC needs separate review.

6. **Do not regenerate the design prototype.** It's atmospheric
   reference. Match its values verbatim. If something doesn't translate
   1:1 to Compose, ask operator first.

7. **Ukrainian copy is verbatim.** Any string in the Familiar's
   voice in the prototype goes into `strings.xml` exactly as-is. Do
   not paraphrase or "improve" the wording.

8. **No scope creep.** If a feature is not in Phase 1-A through 1-G
   above, it doesn't ship in this phase. Add to ROADMAP for later.

═══ WHEN TO ASK THE OPERATOR ═══

- Before bumping a dep
- Before changing public API of a module
- Before merging anything that touches `:core-net/pair` (security-critical)
- Before deleting any file
- Before pushing to remote (this branch is local-only by default)
- When you get unexpected runtime crashes that aren't covered by
  the contract or memory entries

═══ HOW TO REPORT BACK ═══

After each commit, summarize in 3-5 sentences:
- What landed (1-line scope)
- Verification (green build + tests + (optional) on-device confirmation)
- Anything surprising (new gotcha → propose memory entry)
- Next phase to start

Do NOT narrate every tool call. Operator reads the diff via
`git log -p` if needed. Brief is gold.

═══ REFERENCE PATHS QUICK-CARD ═══

```
docs/COMPANION_CONTRACT.md        ← wire spec
docs/ROADMAP.md                   ← what shipped
docs/VISION_NEXT.md               ← engineering plan
docs/DESIGN_BRIEF.md              ← visual language
docs/PRESENCE_AND_CHAT.md         ← chat + presence channels
~/.claude/projects/.../memory/    ← 4 memory entries
/tmp/phantom-design-4/phantom-app2/project/  ← latest design
git log --oneline 8e4016c..HEAD   ← prior session commits
phantom-os/src/backend/           ← backend ground truth (READ-ONLY)
```

You have 30 hours of focused work. Phase 1-A through 1-G is roughly
1 week of solo Compose work; budget accordingly. Ship Phase 1-A
first as proof of concept, then continue.

Begin by reading the 7 documents listed above. Do not write code
until you have read all of them. Confirm understanding to operator,
then start Phase 1-A.
============================================================
```

---

## Why this prompt structure

- **Read order is enforced** — context-poisoning happens when an
  agent skips foundational docs and tries to "be helpful" early.
  The 7-doc read list ensures the agent has full picture before
  touching code.
- **Phase 1-A through 1-G is broken down to file-level granularity**
  — agent doesn't have to invent the Compose architecture.
- **Constraints are explicit** — atomic commits, read-before-edit,
  no scope creep, ask before destructive actions.
- **Reference prototype is named with file paths** — agent finds
  visual specs without re-asking design AI.
- **Reporting expectations are tight** — 3-5 sentences per commit,
  no narration of tool calls.
- **Memory entries are surfaced** — agent inherits prior session's
  hard-won lessons.

---

## When to use this prompt

- **A new Claude Code session** opens in the same project.
- **First message** is the prompt block above (between the `===`
  lines).
- Agent reads the 7 docs, then asks operator to confirm before
  starting Phase 1-A.

If operator wants to skip Phase 1-A (proactive insights) and
prioritize something else (e.g. Whisper Line overlay), they say
so during the confirmation.

---

## Companion artifacts (commit alongside)

After this commit lands, the next session sees:
- `docs/NEXT_SESSION_PROMPT.md` (this file)
- All other vision/design docs
- Memory entries
- Design prototype in `/tmp/phantom-design-4/`

Operator just opens the new session, pastes the prompt above, and
the new agent has full context.
