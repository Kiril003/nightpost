# OBSIDIAN — дизайн-токени, draft v0 (2026-08-22)

Кросс-поверхневий спек PHANTOM (мобілка / ПК-консоль / агентська поверхня).
Імена стабільні (контракт від 2026-08-22, крапки → дефіси в CSS: `ph.color.ground` → `--ph-color-ground`).
Значення v0 — ЧЕРНЕТКА: фінал на воротах ATLAS-1. Варіанти day/night тут; dusk НЕ третя
колонка, а ніч, зсунута до `accent-warm` на 0.10 — і тільки в земляній родині
(ground/surface/scrim/border), тож сигнальні ролі в обох настроях однакові;
minimal — карто-специфічний.

Семантика честі: `live` — ТІЛЬКИ дані з реального фіда; `simulated` — модель/прогноз.
Це доктрина (masterplan D8), не стилістика.
Правило форми (v0.1): simulated НІКОЛИ не позначається лише кольором — завжди
у парі з формальним маркером (префікс «~», штриховий контур або патерн-фактура);
колір холодний слюдяно-синій, свідомо віддалений від ink-muted і від info.
Трикутник чесності (v0.2): станів три, не два. «Виміряно» (журнал/живий фід) →
live. «Виведено» (пораховано з виміряного: ETA, відстань, залишок) → звичайний
ink БЕЗ спецмаркування — чесна арифметика не є ані телеметрією, ані прогнозом.
«Припущено/прогноз» → simulated + маркер форми. Четвертий вимір — свіжість
(«запам'ятовано»/stale) — ортогональний: колір стану зберігається, додається
маркер віку, ніколи не перефарбовування.
Ваги шрифту: variable 450 — побажання, не вимога; статичні 400/500/600/700 канонічні
(body/caption → 400, micro → 500 у статичному наборі).

## Кольори-ролі

Земляна родина й чорнило (ground, surface, surface-raised, scrim, border, ink, ink-muted,
ink-faint) з 02.10 — значення PHANTOM v1.1 «тепла ніч · глибоке чорнило» (`docs/design/system/tokens.json`:
canvas, surface, raised, scrim, line-strong, text, text-2, text-3). Сигнальні ролі не мінялись.

| Токен | night | day |
|---|---|---|
| ph.color.ground | #0A0C10 | #F8F4EE |
| ph.color.surface | #12151B | #FFFFFF |
| ph.color.surface-raised | #1A1E26 | #F0EAE2 |
| ph.color.glass | rgba(18,22,27,.72) | rgba(255,255,255,.78) |
| ph.color.scrim | rgba(5,6,10,.65) | rgba(30,23,18,.4) |
| ph.color.border | #343B47 | #D2C6B7 |
| ph.color.ink | #F4F1EC | #17110C |
| ph.color.ink-muted | #B6BCC6 | #4E4339 |
| ph.color.ink-faint | #939AA6 | #665A4D |
| ph.color.accent | #F2A93B | #C77B21 |
| ph.color.accent-warm | #D97B29 | #A8541C |
| ph.color.alert | #FF5D45 | #D93B26 |
| ph.color.danger | #E5484D | #C7373D |
| ph.color.success | #46C285 | #1F9D62 |
| ph.color.info | #58A6FF | #2270C4 |
| ph.color.live | #3DDC84 | #1FA45B |
| ph.color.simulated | #8FA3C0 | #6B7FA0 |
| ph.color.route | #4FD8EB | #1D7FD1 |
| ph.color.route-alt | #7A8794 | #8B96A0 |
| ph.color.water | #182733 | #C9D8E0 |
| ph.color.green | #18241D | #DCE6D2 |
| ph.color.road-major | #EDE6DC | #FFFFFF |
| ph.color.road-minor | #4A4D53 | #D9CFC3 |

## Скло / тіні
ph.glass.blur = 18px · ph.glass.opacity = .72 (night) / .78 (day)
ph.shadow.1 = 0 1 2 rgba(0,0,0,.25) · ph.shadow.2 = 0 4 12 rgba(0,0,0,.30) · ph.shadow.3 = 0 12 32 rgba(0,0,0,.38)

## Типографіка
ph.font.ui = "Inter", system-ui · ph.font.mono = "JetBrains Mono", monospace
| Токен | size/weight/tracking |
|---|---|
| ph.type.display | 28 / 700 / −0.01 |
| ph.type.title | 20 / 600 / 0 |
| ph.type.body | 15 / 450 / 0 |
| ph.type.caption | 12.5 / 450 / +0.01 |
| ph.type.micro | 10.5 / 500 / +0.02 |

## Простір / форма / штрих
ph.space.1..8 = 4, 8, 12, 16, 20, 24, 32, 40
ph.radius.s/m/l/pill = 6, 10, 16, 999
ph.stroke.hair/thin/bold = 0.5, 1, 2

## Рух
ph.motion.fast/base/slow = 120, 200, 320 мс
ph.ease.standard = cubic-bezier(.2,0,0,1) · enter = (0,0,0,1) · exit = (.3,0,1,1)

## Картографічні якорі (внутрішні для мапи, НЕ кросс-поверхневі)
Ніч: земля — глибоке чорнило ph.color.ground; дороги — теплі жили road-major по рампі
магістраль→сервіс (#EDE6DC → #4A4D53: від теплого світла до холодного сірого), магістраль
ледь тепліша (10 % accent), вода — спокійна синьо-сіра (#182733, підйом до суміші info з
ink-muted), зелень — шавлія (#18241D),
підписи ink з гало ground 1.2–1.4px. День: тепла бумага, дороги білі з casing border.
Повні рампи z0–z22 — власність компілятора стилю (tools/obsidian).
