import type { Config } from 'tailwindcss';

/*
 * Кольори сайту — це токени дизайн-системи «тепла ніч»
 * (docs/design/system/tokens.json на гілці cloud/design-system), а не
 * власна палітра сайту. Значення живуть у src/styles/tokens.css як RGB-трійки
 * двох тем — «День» і «Ніч».
 */
const v = (name: string) => `rgb(var(--ph-${name}) / <alpha-value>)`;

export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      fontFamily: {
        sans: ['"Inter Tight Variable"', '"Inter Tight"', 'Inter', 'system-ui', 'sans-serif'],
        display: ['"Inter Tight Variable"', '"Inter Tight"', 'Inter', 'system-ui', 'sans-serif'],
        mono: ['ui-monospace', 'SFMono-Regular', 'Menlo', 'Consolas', 'monospace'],
      },
      colors: {
        canvas: v('canvas'),
        surface: v('surface'),
        raised: v('raised'),
        overlay: v('overlay'),
        line: v('line'),
        'line-strong': v('line-strong'),
        ink: v('text'),
        'ink-2': v('text-2'),
        'ink-3': v('text-3'),
        accent: v('accent'),
        'accent-hover': v('accent-hover'),
        'accent-press': v('accent-press'),
        'accent-text': v('accent-text'),
        'accent-line': v('accent-line'),
        'accent-soft': v('accent-soft'),
        'on-accent': v('on-accent'),
        success: v('success'),
        danger: v('danger'),
        warning: v('warning'),
        device: v('device'),
      },
      fontSize: {
        display: ['28px', { lineHeight: '34px', fontWeight: '600' }],
        'title-1': ['20px', { lineHeight: '26px', fontWeight: '600' }],
        'title-2': ['17px', { lineHeight: '24px', fontWeight: '600' }],
        headline: ['16px', { lineHeight: '22px', fontWeight: '500' }],
        body: ['16px', { lineHeight: '22px' }],
        subhead: ['14px', { lineHeight: '20px', fontWeight: '500' }],
        footnote: ['13px', { lineHeight: '18px' }],
        caption: ['12px', { lineHeight: '16px', fontWeight: '500' }],
        hero: ['clamp(40px, 4.4vw, 64px)', { lineHeight: '1.04', letterSpacing: '-0.03em', fontWeight: '700' }],
        'page-h1': ['clamp(28px, 1.8rem + 1vw, 40px)', { lineHeight: '1.1', letterSpacing: '-0.02em', fontWeight: '600' }],
        'section-h2': ['clamp(20px, 1.15rem + 0.8vw, 28px)', { lineHeight: '1.214', letterSpacing: '-0.01em', fontWeight: '600' }],
        // Aliases for existing page classes:
        d1: ['clamp(28px, 1.8rem + 1vw, 40px)', { lineHeight: '1.1', letterSpacing: '-0.02em', fontWeight: '600' }],
        d2: ['clamp(20px, 1.15rem + 0.8vw, 28px)', { lineHeight: '1.214', letterSpacing: '-0.01em', fontWeight: '600' }],
        d3: ['clamp(20px, 1.15rem + 0.8vw, 28px)', { lineHeight: '1.214', letterSpacing: '-0.01em', fontWeight: '600' }],
        h2: ['clamp(20px, 1.15rem + 0.8vw, 28px)', { lineHeight: '1.214', letterSpacing: '-0.01em', fontWeight: '600' }],
        't-title': ['clamp(20px, 1.15rem + 0.8vw, 28px)', { lineHeight: '1.214', letterSpacing: '-0.01em', fontWeight: '600' }],
        h3: ['20px', { lineHeight: '26px', fontWeight: '600' }],
        lead: ['16px', { lineHeight: '22px' }],
        meta: ['16px', { lineHeight: '22px' }],
        callout: ['16px', { lineHeight: '22px' }],
        cap: ['14px', { lineHeight: '20px', fontWeight: '500' }],
      },
      borderRadius: {
        chip: '8px',
        control: '12px',
        card: '20px',
        sheet: '28px',
        phone: '44px',
        tile: '20px',
        stage: '28px',
      },
      boxShadow: {
        e1: 'none',
        e2: 'none',
        e3: 'none',
      },
      transitionTimingFunction: {
        standard: 'cubic-bezier(0.2, 0, 0, 1)',
      },
      transitionDuration: {
        quick: '150ms',
        standard: '240ms',
      },
      maxWidth: {
        shell: '90rem',
        wrap: '72rem',
        prose: '40rem',
      },
    },
  },
  plugins: [],
} satisfies Config;
