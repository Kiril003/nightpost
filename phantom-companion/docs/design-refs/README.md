# Design references (read-only)

Pixel/spec sources for UI work. **Do not render in a browser** — read the
source directly (HTML/CSS/JSX carry every dimension, colour, layout rule).

- `legacy-pwa-snapshot/` — archived first-gen PWA.
- `phantom-app2/` — Claude-Design handoff bundle exported 2026-05-16 from
  `api.anthropic.com/v1/design/h/ajJbcGylrH2ec1RfdFrKwA`. ~35 React/JSX
  prototype surfaces + `project/tokens.css` + `chats/` transcripts (the
  transcripts are the intent of record). Drives the v2 UI implementation
  plan; `tokens.css` already maps 1:1 to `:core-design` `PhantomThemeSpec`.
