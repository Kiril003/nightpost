# Legacy PWA Snapshot — Design Reference

## Звідки цей snapshot

Зріз PWA-версії Mobile Companion станом на **2026-05-04**, що охоплює фази:
- PWA Phase 1..8 (sensor batcher, Senses, hybrid router, Driver/Mesh/Hub/Telecom)
- Native shell (Capacitor + 6 плагінів)
- Redesign (IA collapse 12→5 tabs, GlobalStatusBar, NowScreen, MeScreen)
- Cross-device (HandoffOverlay, companionControl WS dispatcher, VaultCardAI)
- Bugfixes-r2 (error bus, tab swipe, persistent chatStore)
- Maps + Chat overhaul (InteractiveMap, geocoding/routing/POI, bookmarks/tracks)
- Pair/mDNS/ecosystem (MdnsPlugin Kotlin, multi-host roster, ring broadcast)

Вихідний каталог: `phantom-companion/legacy-pwa/src/` (зовнішній репозиторій, видалений після зрізу).
Гілка на момент зрізу: `companion-v2-phase-0`.

## Чому архів read-only

Vision §1 («dead apps») прибрав PWA IA з канонічного шляху — Native Android (`companion-android/`) є єдиним таргетом. Однак **візуальна мова цього PWA — Glass + Glow + per-state accent — лишається золотим стандартом** і буде використовуватись як reference при Compose-портуванні у `:feature-stream` generative widgets.

Цей каталог — заморожений архів. Жодних змін, жодного запуску.

## Що тут шукати

### `components/` — атомарні UI-примітиви

| Файл | Призначення | Compose-аналог |
|------|-------------|----------------|
| `GlassCard.tsx` | Скляна картка з blur + border-glow | `:core-design/component/GlassCard.kt` (Phase A2) |
| `OrbView.tsx` | Пульсуючий орб стану системи | `:core-design/component/OrbView.kt` (Phase A2) |
| `Waveform.tsx` | Аудіо-хвиля реального часу (Canvas) | `:core-design/component/Waveform.kt` (Phase A2) |
| `Sparkline.tsx` | Мінімалістичний графік тренду | `:core-design/component/Sparkline.kt` (Phase A2) |
| `StatePill.tsx` | Бейдж поточного стану (FOCUS/SENTINEL/…) | `:core-design/component/StatePill.kt` (Phase A2) |
| `VitalsRow.tsx` | Рядок біосигналів (HR/SpO2/temp) | `:core-design/component/VitalsRow.kt` (Phase A2) |
| `PttButton.tsx` | Push-to-talk кнопка з haptic-feedback | `:core-design/component/PttButton.kt` (Phase A2) |
| `MiniMap.tsx` | Мініатюрна карта (MapLibre GL) | референс для `:feature-stream` map widget |
| `WeatherCard.tsx` | Картка погоди з анімацією | референс для `:feature-stream` ambient widget |

### `theme/` — дизайн-токени

- `tokens.ts` — Tailwind-токени: палітра `sunrise-warm` + `amber-night`, spacing, radius, shadows.
- `ThemeProvider.tsx` — React context з підтримкою 6 per-state акцентів.
- `index.css` — CSS custom properties для всіх токенів.

Кольори вже мігрували у:
- `companion-android/core-design/src/main/java/local/phantom/companion/core/design/theme/PhantomTheme.kt`
- `companion-android/core-design/src/main/java/local/phantom/companion/core/design/theme/StateAccent.kt`

**6 per-state accents**: FOCUS (#3B82F6 blue), SENTINEL (#EF4444 red), GHOST (#8B5CF6 violet), DREAM (#10B981 emerald), SHADOW (#6B7280 gray), DIALOGUE (#F59E0B amber).

### `screens/` — 27 PWA-екранів (reference only)

Більшість екранів мертві per vision §1, але деякі містять цінні патерни анімацій та UX-рішення:

| Екран | Статус | Що корисно |
|-------|--------|------------|
| `InteractiveMap.tsx` | reference | Layering, long-press, route polyline, 4 basemap styles |
| `VaultCardAI.tsx` | reference | Inline expander, AI-stream rendering, reveal animation |
| `HandoffOverlay.tsx` | reference | Global slide-up overlay, device selection UX |
| `NowScreen.tsx` | reference | Merged Pulse+Inbox IA, ambient data layout |
| `VoiceScreen.tsx` | reference | Waveform + PTT + transcript flow |
| `ChatScreen.tsx` | reference | Mic integration, retry UX, dual-auth |
| `PulseScreen.tsx` | reference | Biosignal gauges, connection banner |
| Решта 20 | архів | Зберігаються для повноти, не портуються |

## Попередження

**НЕ запускати ці файли.** В архіві немає `package.json`, відсутні `node_modules`, типи Capacitor не встановлені. Файли є виключно read-only design references для Compose-портування.

## Дата зрізу

**2026-05-04** (архівовано 2026-05-05).
