# Familiar Orb Interface

_Started 2026-05-09 18:04 UTC_

---

## User



## Assistant

_[tool: list_files]_

_[tool: questions_v2]_

The design system project is empty, so I'll lean entirely on the brief itself — which is dense and opinionated enough to act as the system. Before I start building, a few quick questions so I aim at the right thing:

## User

Questions answered:
- primary_surface: Stream (the heart) — header + timeline + input + floating Orb, with live state morphing
- format: Phone frame + Tweaks panel (state, theme, motion, voice — all live)
- interactivity: Fully interactive prototype — type into Stream, PTT hold, state FSM, handoff sheet, etc.
- theme_focus: Amber-night primary (the Lhasa-window mood)
- orb_fidelity: WebGL/shader — true ambient occlusion + subsurface scattering vibe
- state_coverage: All six (full coverage)
- tweaks_scope: Sound on/off (TTS demo), Background variant (still / wallpaper / wandering sparks), Motion scale + breath speed, Glass blur intensity, Orb rendering tier (Compose / RenderEffect / Filament-feel), Theme (sunrise / amber), SystemState (FSM switcher)
- stream_content: Familiar welcome (hero glass card), User ↔ Familiar dialogue exchange, Proactive Insight card (with actions), Timer widget (generative UI), Vitals widget, Handoff card (from phantom-os), Map preview widget (route), Notice bar (warning/info)
- extras: Live wallpaper preview, Home-screen Vitals widget, Floating Orb overlay (above another app), Lock-screen / AOD complication
- anything_else: Час дня: ~19:30 evening, Lhasa November mood. Sunrise-warm theme розгляни тільки як toggle.
  ▎
  ▎ Operator: Kirill, ROOT trust, Ukrainian primary. Familiar звертається на «ти», теплий, інтелектуальний, без dyakuyu za 
  ▎ zvernennya тон.
  ▎
  ▎ Real content для seed Stream: напиши українською у голосі Familiar — НЕ machine-translation. Familiar говорить як старий 
  ▎ друг-філософ-engineer. Не "користувач", а "ти". Не "дякую що звернулися", а pause + спостереження.
  ▎
  ▎ Reference аtmosphere:
  ▎ - Blade Runner 2049 (gold over bleak, calm scale)
  ▎ - HER — earpiece intimacy, Samantha's warmth
  ▎ - Westworld S1 — Maeve HUD coldfire
  ▎ - Apple Vision Pro spatial UI
  ▎ - Bang & Olufsen high-end audio interfaces
  ▎ - Sufjan Stevens "Carrie & Lowell" — emotional resonance, low saturation
  ▎
  ▎ Avoid hard:
  ▎ - Будь-який Material 3 cue (rounded corners as default, ripple, FAB)
  ▎ - iOS 17 paradigms (segmented controls, navigation bar)
  ▎ - Discord/Slack chat aesthetic (avatars on left, timestamps top-right, @mentions)
  ▎ - Cyberpunk neon
  ▎ - Spinner / circular progress / linear progress bar — replace with Orb breath
  ▎ - Stark white anywhere — paper-cream #F8F7F5 мінімум
  ▎ - Pure black anywhere — amber-black #0E0A05 мінімум
  ▎ - "Welcome to PHANTOM" splash with logo
  ▎ - Empty states з cartoon ілюстраціями
  ▎ - Toast/snackbar notifications — Familiar має голос
  ▎
  ▎ One sentence anchor for Familiar voice:
  ▎ "Я тут. Я бачу. Я памʼятаю. Скажи що тобі треба — або помовч, я зрозумію коли треба."
  ▎
  ▎ The core test before shipping: "Чи це звучить як присутність, чи як dashboard?" Якщо dashboard — переробити.

## Assistant

_[tool: update_todos]_

_[tool: copy_starter_component]_

_[tool: copy_starter_component]_

_[tool: read_file]_

_[tool: read_file]_

_[tool: snip]_

_[tool: snip]_

_[tool: write_file]_

_[tool: write_file]_

Now I have everything I need. Let me build the system. I'll commit to a custom phone frame (the brief explicitly excludes Material defaults), and write everything in parallel.

_[tool: snip]_

Now the Stream surface — header, all stream item types, and input row.

_[tool: write_file]_

_[tool: snip]_

Now extras (lock screen, floating orb, vitals widget, wallpaper) and the main app + index.html.

_[tool: write_file]_

_[tool: write_file]_

_[tool: write_file]_

_[tool: snip]_

_[tool: show_html]_

_[tool: done]_

_[tool: fork_verifier_agent]_

**PHANTOM Companion** is ready.

The Stream surface lives inside a custom phone frame — no Material defaults. WebGL fragment shader on the Orb (FBM-perturbed sphere SDF with subsurface bleed and SSS-style rim), with Canvas2D and CSS fallback tiers swappable from Tweaks. Audio reacts to PTT hold and TTS speaking. Familiar replies via `window.claude.complete` when available, with a Ukrainian stub fallback in the right tone.

Tweaks panel exposes everything you asked for: SystemState (all 6), theme (amber-night ↔ sunrise-warm), Orb tier (css / canvas / webgl), motion scale, breath period, glass blur, background variant (still / topo / sparks), sound toggle. Bonus: scene switcher (stream / home / lock / wallpaper) and a draggable floating Orb overlay.

Stream content seeds the brief's Ukrainian voice — Hero welcome, dialogue exchange, proactive Insight, Timer, Handoff from phantom-os, Map widget with a flowing gold route around a coral threat zone, and a notice. Type into the input or hold the mic; replies stream back.

A couple of intentional notes:
- The Orb on **GHOST** state goes very dim and low-opacity — that's by design ("примара").
- States transition with smoothed easing, not instant; you may need a beat after switching to see the full mood shift.
- TTS is simulated — `soundOn` is a placeholder toggle (no Web Audio synthesis wired; that would need real audio assets to honor the brief's "no off-the-shelf SFX" rule).

_[tool: snip]_

Ending turn.

## User



## Assistant

_[tool: snip]_

_[tool: str_replace_edit]_

_[tool: write_file]_

_[tool: list_files]_

_[tool: read_file]_

_[tool: read_file]_

_[tool: str_replace_edit]_

_[tool: update_todos]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: update_todos]_

_[tool: done]_

_[tool: snip]_

_[tool: update_todos]_

_[tool: fork_verifier_agent]_

Pushed in this round: GHOST/SHADOW/DREAM state refinement, Onboarding 6-step morph, AR Pair scanner, Vault Gate (GHOST violet), Decision Diary, Motion Catalog, DialogueThrum, PastSelfNote, VoiceClonePreview, Lhasa-dusk gradient with mountain silhouette, parallax mousemove. All wired into Tweaks → Surface.

## User



## Assistant

_[tool: snip]_

_[tool: grep]_

_[tool: read_file]_

_[tool: read_file]_

_[tool: str_replace_edit]_

_[tool: snip]_

_[tool: write_file]_

_[tool: snip]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: done]_

_[tool: grep]_

_[tool: fork_verifier_agent]_

_[tool: snip]_

Pushed in this round:

**A. Stream rich content (default visible):**
- Thread rail above Stream — 5 threads (Зараз / Olya / Family / Whispers / Vault)
- File card з auto-extracted receipt
- ChartWidget (bar/line, with annotations)
- CodeBlock з Kotlin syntax tokens + Run button
- VoiceMemo з waveform + scrubber + transcription
- Citation pill on assistant message ("з розмови з anna · 12 травня")
- ToolTrace bar — `phantom шукав у Notion · 0.4s · 12 results`, expandable

**B–E. New surfaces** (entry via long-press on "ВАРТА" → SurfaceTray):
- **Whisper Line** — fake Telegram below, 1px gold tickerline above, з "screen recorder sees nothing" indicator
- **Margin Glow** — bezel-only mode, no Orb, color/rate per state, 5 states tappable
- **Subliminal Pulse** — Orb-only, 5 encoded messages cycling (task/wellness/warning/vault/calm)
- **Family Hive** — 5 trusted devices as orbs з proxemic ring, "де Олег?" pulse demo

**F. UX entry** — long-press "ВАРТА" в SystemPulseHeader відкриває slide-up tray зі всіма surfaces.

Tweak surface dropdown все ще працює як shortcut. Next: tier C (PDF chat / RAG, calendar integration), якщо треба — дай знак.

## User



## Assistant

_[tool: read_file]_

_[tool: grep]_

_[tool: read_file]_

_[tool: read_file]_

_[tool: write_file]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: read_file]_

_[tool: str_replace_edit]_

_[tool: snip]_

_[tool: done]_

_[tool: fork_verifier_agent]_

Pushed in this round (TIER C + Phase 9 frontier surfaces, all behind long-press tray):

- **PdfChat** — Sapiens preview + page chips + RAG with page-citation pills
- **Calendar week** — augmented agenda, conflict highlight, AI weekly read
- **Email triage** — important/digest/spam classification, swipe actions
- **Smart home** — climate slider, lights radial, music waveform, 4-cam grid
- **Sleep dashboard** — recovery ring, hypnogram, recurring-theme tags
- **Image gen** — 4 variations strip, painterly Lhasa preview
- **Memory Palace** — 3D canvas, rotating tile grid in space
- **Dream journal** — REM entry + recurring-theme alert + voice capture
- **Negotiation Court** — split parties, transcript, consensus stamp
- **Ambient Health** — 6-week stress curve with future-burnout zone + interventions
- **Typography** — display/body/mono samples + state label
- **Conversational Settings** — replaces toggle grid; text peek per section

