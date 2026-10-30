import type { Config } from 'tailwindcss';
import phantom from './src/styles/phantom/tailwind-preset';

/**
 * Дизайн-система PHANTOM v1 «тепла ніч» (docs/design/system): кольори, ролі
 * тексту, радіуси, тіні й рух приходять пресетом `phantom`, згенерованим з
 * `tokens.json` (`src/styles/phantom/tailwind-preset.ts`, не правити руками).
 *
 * Старий словник форми `f-*` лишається ПСЕВДОНІМАМИ нових токенів, щоб
 * екрани поза месенджером не втратили барв, поки їх не переведено:
 * `f-canvas` → canvas, `f-raise` → raised, `f-ink*` → ink*, `f-accent` (текст)
 * → accent-text, заливка `bg-f-accent` → accent. Нова розмітка пише лише
 * новими іменами.
 */
const ph =
  (name: string) =>
  ({ opacityValue }: { opacityValue?: string | number }): string =>
    opacityValue === undefined || String(opacityValue).startsWith('var(--tw-')
      ? `var(${name})`
      : `color-mix(in srgb, var(${name}) calc(${opacityValue} * 100%), transparent)`;

const config: Config = {
  presets: [phantom as Config],
  // На дотику `hover:` прилипає: після дотику кнопка лишалась підсвіченою —
  // «+» після закритого аркуша, шапка розмови після картки, рядок меню під
  // пальцем довгого дотику (A52, 02.10). Наведення — лише там, де воно є.
  future: { hoverOnlyWhenSupported: true },
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        // Старий словник «Sunrise» — ті самі псевдоніми, що в styles/tokens.css,
        // але прямо на --ph-*: так `bg-primary/10` отримує прозорість, а не
        // невалідний `var(--primary) / 0.1`. Видалити, коли зникнуть споживачі.
        primary: ph('--ph-accent'),
        'primary-deep': ph('--ph-accent'),
        'primary-shadow': ph('--ph-accent-text'),
        'ink-primary': ph('--ph-text'),
        'ink-secondary': ph('--ph-text-2'),
        'ink-muted': ph('--ph-text-3'),
        'ink-faint': ph('--ph-text-3'),
        'ink-inverse': ph('--ph-on-accent'),
        'glass-border': ph('--ph-line'),
        'glass-panel': ph('--ph-surface'),
        'glass-card': ph('--ph-surface'),
        'glass-elevated': ph('--ph-raised'),
        'signal-ok': ph('--ph-success'),
        'signal-warn': ph('--ph-warning'),
        'signal-alert': ph('--ph-danger'),
        'signal-info': ph('--ph-text-2'),
        // Псевдоніми старого словника форми → токени PHANTOM v1.
        'f-canvas': ph('--ph-canvas'),
        'f-stage': ph('--ph-canvas'),
        'f-surface': ph('--ph-surface'),
        'f-raise': ph('--ph-raised'),
        'f-self': ph('--ph-self'),
        'f-line': ph('--ph-line'),
        'f-ink': ph('--ph-text'),
        'f-ink-2': ph('--ph-text-2'),
        'f-ink-3': ph('--ph-text-3'),
        'f-accent': ph('--ph-accent-text'),
        'f-on-accent': ph('--ph-on-accent'),
        'f-warn': ph('--ph-warning'),
        'f-live': ph('--ph-success'),
        'f-danger': ph('--ph-danger'),
      },
      // Заливка `bg-f-accent` — бурштин-заливка, а text/border/ring — accent-text.
      backgroundColor: {
        'f-accent': ph('--ph-accent'),
      },
      // Старі ролі форми → шкала PHANTOM v1 (12 — підлога).
      fontSize: {
        'f-display': ['28px', { lineHeight: '34px', fontWeight: '700', letterSpacing: '-0.02em' }],
        'f-title': ['18px', { lineHeight: '24px', fontWeight: '600' }],
        'f-name': ['16px', { lineHeight: '22px', fontWeight: '600' }],
        'f-read': ['16px', { lineHeight: '23px', fontWeight: '400' }],
        'f-body': ['15px', { lineHeight: '20px', fontWeight: '400' }],
        'f-label': ['13px', { lineHeight: '18px', fontWeight: '600' }],
        'f-meta': ['12px', { lineHeight: '16px', fontWeight: '500' }],
        'f-rowname': ['16px', { lineHeight: '22px', fontWeight: '600' }],
        'f-rowbody': ['15px', { lineHeight: '20px', fontWeight: '400' }],
        'f-rowmeta': ['12px', { lineHeight: '16px', fontWeight: '500' }],
      },
      borderRadius: {
        'f-s': '10px',
        'f-m': '10px',
        'f-bubble': '18px',
        'f-tail': '6px',
        'f-sheet': '20px',
      },
      transitionDuration: {
        'f-tap': '90ms',
        'f-layer': '240ms',
        'f-exit': '180ms',
      },
      transitionTimingFunction: {
        'f-std': 'cubic-bezier(0.2, 0, 0, 1)',
        'f-leave': 'cubic-bezier(0.3, 0, 1, 1)',
      },
      // Один шрифт — Manrope 400–700. JetBrains Mono — лише блоки коду.
      // `font-display` і `font-serif` лишаються псевдонімами Manrope, щоб
      // старі класи не повертали ні Onest, ні серифну «корону».
      fontFamily: {
        display: ['var(--ph-font-sans)'],
        sans: ['var(--ph-font-sans)'],
        serif: ['var(--ph-font-sans)'],
        mono: ['var(--ph-font-mono)'],
      },
      screens: {
        phantom: '1024px',
      },
      width: {
        'status-bar': '1024px',
      },
      height: {
        // h-screen мусить означати висоту екрана. Перевизначення на 600px
        // робило застосунок 600-піксельною смугою на будь-якому моніторі —
        // саме звідси мертва чорна зона під згином. Рамка пристрою тепер
        // h-device там, де вона справді потрібна.
        device: '600px',
        'status-bar': '36px',
        main: '564px',
      },
      // Нескінченних декоративних рухів немає (дизайн-система §6): лише поява.
      animation: {
        'fade-in': 'fadeIn 240ms cubic-bezier(0, 0, 0, 1) forwards',
      },
      keyframes: {
        fadeIn: {
          '0%': { opacity: '0', transform: 'translateY(8px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
      },
    },
  },
  plugins: [],
};

export default config;
