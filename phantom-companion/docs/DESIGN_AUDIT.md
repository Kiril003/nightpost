# Design Audit — PhantomTheme vs `tokens.css` (W1.5-1)

> Source of truth: `/tmp/phantom-design-4/phantom-app2/project/tokens.css`
> (Claude Design v4 prototype). Each Phase 1 / 1.5 / 2 surface was ported by
> feel; this audit pins the per-token diff and corrects the drift.
>
> Field shape stays the same — only values change, so existing call sites
> (`theme.inkStrong`, `theme.glassPanel`, …) keep working unchanged. Tests
> in `PhantomThemeAuditTest` pin every corrected value.

## amber-night (PhantomTheme.AmberNight)

| Token              | tokens.css                              | Compose hex (ARGB) | PhantomTheme before | After       | Why                                     |
|--------------------|------------------------------------------|---------------------|---------------------|-------------|-----------------------------------------|
| `--gold`           | `#F4AF25`                                | `0xFFF4AF25`        | `0xFFF4AF25`        | (same)      | already correct                         |
| `--gold-soft`      | `#E0992A`                                | `0xFFE0992A`        | `0xFFFBC66A`        | `0xFFE0992A`| primarySoft drifted to a much lighter gold |
| `--gold-faint`     | `rgba(244,175,37,0.18)`                  | `0x2EF4AF25`        | `0x73F4AF25` (.45)  | `0x2EF4AF25`| glowPrimary alpha was 2.5× too strong   |
| `--ink-strong`     | `#FFF8E7`                                | `0xFFFFF8E7`        | `0xFFFFFFFF`        | `0xFFFFF8E7`| was pure white — washed out vs warm cream |
| `--ink`            | `#F0E6CF`                                | `0xFFF0E6CF`        | `0xFFF6E9CF`        | `0xFFF0E6CF`| ink drift (slight green tint)           |
| `--ink-secondary`  | `#A7967D`                                | `0xFFA7967D`        | `0xFFC9B384`        | `0xFFA7967D`| was too light — dropped contrast        |
| `--coral`          | `#FF8B6E`                                | `0xFFFF8B6E`        | `0xFFE35858`        | `0xFFFF8B6E`| was the day-mode coral, not night       |
| `--bg-base`        | `#0E0A05`                                | `0xFF0E0A05`        | `0xFF0E0A05`        | (same)      | already correct                         |
| `--bg-deep`        | `#060402`                                | `0xFF060402`        | `0xFF080502`        | `0xFF060402`| 2 LSB off — barely visible but pinned   |
| `--glass-tint`     | `rgba(31,22,18,0.55)`                    | `0x8C1F1612`        | `0xA6140F08`        | `0x8C1F1612`| color + alpha both off; cards looked too dark |
| `--glass-edge`     | `rgba(255,230,180,0.10)`                 | `0x1AFFE6B4`        | `0x40F4AF25`        | `0x1AFFE6B4`| was bright gold — every card border glowed |

## sunrise-warm (PhantomTheme.SunriseWarm)

| Token              | tokens.css                              | Compose hex (ARGB) | PhantomTheme before | After       | Why                                     |
|--------------------|------------------------------------------|---------------------|---------------------|-------------|-----------------------------------------|
| `--gold`           | `#F4AF25`                                | `0xFFF4AF25`        | `0xFFF4AF25`        | (same)      | already correct                         |
| `--gold-soft`      | `#E0992A`                                | `0xFFE0992A`        | `0xFFFBC66A`        | `0xFFE0992A`| primarySoft drift, same as night        |
| `--gold-faint`     | `rgba(244,175,37,0.22)`                  | `0x38F4AF25`        | `0x4DF4AF25` (.30)  | `0x38F4AF25`| glow was a touch too saturated          |
| `--ink-strong`     | `#1A140B`                                | `0xFF1A140B`        | `0xFF000000`        | `0xFF1A140B`| was pure black — flat vs warm-paper bg  |
| `--ink`            | `#2A2118`                                | `0xFF2A2118`        | `0xFF1A150D`        | `0xFF2A2118`| was darker than spec; too contrasty     |
| `--ink-secondary`  | `#5D5142`                                | `0xFF5D5142`        | `0xFF8A7344`        | `0xFF5D5142`| was too light — labels lost weight      |
| `--coral`          | `#E76F51` (DESIGN_BRIEF §III)            | `0xFFE76F51`        | `0xFFEF4444`        | `0xFFE76F51`| was Material red, not the warm coral spec |
| `--bg-base`        | `#F8F7F5`                                | `0xFFF8F7F5`        | `0xFFF8F7F5`        | (same)      | already correct                         |
| `--bg-deep`        | `#ECE7DC`                                | `0xFFECE7DC`        | `0xFFF5F1EA`        | `0xFFECE7DC`| was too pale — surfaceDeep merged with surfaceBase |
| `--glass-tint`     | `rgba(255,255,255,0.6)`                  | `0x99FFFFFF`        | `0x99FFFFFF`        | (same)      | already correct                         |
| `--glass-edge`     | `rgba(255,255,255,0.7)`                  | `0xB3FFFFFF`        | `0x33B07A10`        | `0xB3FFFFFF`| was gold — all day-mode borders glowed wrong |

## Tokens NOT yet covered (deferred)

| tokens.css token          | Status                                                  |
|----------------------------|---------------------------------------------------------|
| `--gold-glow`              | partially — `glowPrimary` covers part; semantically distinct |
| `--ink-tertiary` / `--ink-faint` | no PhantomTheme field; add in a follow-up if usage grows |
| `--sage`                   | no field; success-tint usage today goes through `accent.color` (gold) |
| `--vault`                  | no field; vault surface uses Material `secondary`       |
| `--ghost`                  | no field; GHOST surface uses gray inline                |
| `--glass-edge-strong`      | partially — used by `accentBorder` GlassCard variant; future-fold |

These don't show in the prototype's most-visible surfaces (Stream / Onboarding / Header), so addressing them is a Phase 3 polish concern and won't be tackled in W1.5 to keep the wave focused.

## Regression guard

`core-design/src/test/java/.../theme/PhantomThemeAuditTest.kt` pins every
value above. Any future change to a `PhantomThemeSpec` field that
re-introduces drift will fail the corresponding test case.
