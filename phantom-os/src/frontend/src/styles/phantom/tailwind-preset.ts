// GENERATED from docs/design/system/tokens.json — do not edit by hand.
// Usage (phantom-os/src/frontend/tailwind.config.ts):
//   import phantom from './src/styles/phantom/tailwind-preset';
//   export default { presets: [phantom], content: [...] } satisfies Config;
// and import './src/styles/phantom/tokens.css' once in main.tsx.
// Classes: bg-canvas, bg-surface, text-ink-2, bg-accent/15, text-headline, rounded-card, p-4, shadow-e2, duration-standard, ease-standard.
import type { Config } from 'tailwindcss';

// Tailwind приймає функцію-барву (вміє `/15`), але його тип її не описує — звідси приведення.
const v = (name: string): string =>
  ((({ opacityValue }: { opacityValue?: string | number }): string =>
    opacityValue === undefined || String(opacityValue).startsWith('var(--tw-')
      ? `var(${name})`
      : `color-mix(in srgb, var(${name}) calc(${opacityValue} * 100%), transparent)`) as unknown as string);

// Everything sits under `extend`: Tailwind's default numeric spacing (1 = 4px … 16 = 64px)
// already IS the PHANTOM 4-pt scale, so spacing is not redefined — arbitrary values
// (p-[13px], text-[10.5px]) are what the lint rule forbids.
const preset: Partial<Config> = {
  theme: {
    // Look D: no shadows anywhere. '0 0 #0000' (not 'none') keeps ring utilities composable.
    boxShadow: { DEFAULT: '0 0 #0000', sm: '0 0 #0000', md: '0 0 #0000', lg: '0 0 #0000', xl: '0 0 #0000', '2xl': '0 0 #0000', inner: '0 0 #0000', none: '0 0 #0000', e1: '0 0 #0000', e2: '0 0 #0000', e3: '0 0 #0000' },
    extend: {
      colors: {
        'canvas': v('--ph-canvas'),
        'surface': v('--ph-surface'),
        'raised': v('--ph-raised'),
        'overlay': v('--ph-overlay'),
        'line': v('--ph-line'),
        'line-strong': v('--ph-line-strong'),
        'ink': v('--ph-text'),
        'ink-2': v('--ph-text-2'),
        'ink-3': v('--ph-text-3'),
        'accent': v('--ph-accent'),
        'accent-hover': v('--ph-accent-hover'),
        'accent-press': v('--ph-accent-press'),
        'accent-text': v('--ph-accent-text'),
        'accent-line': v('--ph-accent-line'),
        'accent-soft': v('--ph-accent-soft'),
        'on-accent': v('--ph-on-accent'),
        'self': v('--ph-self'),
        'on-self': v('--ph-on-self'),
        'on-self-2': v('--ph-on-self-2'),
        'success': v('--ph-success'),
        'danger': v('--ph-danger'),
        'warning': v('--ph-warning'),
        'act-pin': v('--ph-act-pin'),
        'act-mute': v('--ph-act-mute'),
        'act-archive': v('--ph-act-archive'),
        'on-act': v('--ph-on-act'),
        'wall': v('--ph-wall'),
        'wall-dot': v('--ph-wall-dot'),
        'cover': v('--ph-cover'),
        'cover-line': v('--ph-cover-line'),
        scrim: 'var(--ph-scrim)',
        'person-0': v('--ph-person-0'),
        'person-0-ink': v('--ph-person-0-ink'),
        'person-1': v('--ph-person-1'),
        'person-1-ink': v('--ph-person-1-ink'),
        'person-2': v('--ph-person-2'),
        'person-2-ink': v('--ph-person-2-ink'),
        'person-3': v('--ph-person-3'),
        'person-3-ink': v('--ph-person-3-ink'),
        'person-4': v('--ph-person-4'),
        'person-4-ink': v('--ph-person-4-ink'),
        'person-5': v('--ph-person-5'),
        'person-5-ink': v('--ph-person-5-ink'),
      },
      fontFamily: { sans: ['var(--ph-font-sans)'], mono: ['var(--ph-font-mono)'] },
      fontSize: {
        'display': ['28px', { lineHeight: '34px', fontWeight: '600', letterSpacing: '-0.01em' }],
        'title-1': ['20px', { lineHeight: '26px', fontWeight: '600', letterSpacing: '0em' }],
        'title-2': ['17px', { lineHeight: '24px', fontWeight: '600', letterSpacing: '0em' }],
        'headline': ['16px', { lineHeight: '22px', fontWeight: '600', letterSpacing: '0em' }],
        'body': ['16px', { lineHeight: '22px', fontWeight: '400', letterSpacing: '0em' }],
        'callout': ['15px', { lineHeight: '20px', fontWeight: '400', letterSpacing: '0em' }],
        'subhead': ['14px', { lineHeight: '20px', fontWeight: '500', letterSpacing: '0em' }],
        'footnote': ['13px', { lineHeight: '18px', fontWeight: '400', letterSpacing: '0em' }],
        'caption': ['12px', { lineHeight: '16px', fontWeight: '500', letterSpacing: '0.01em' }],
      },
      borderRadius: {
        'chip': '8px',
        'control': '12px',
        'card': '20px',
        'sheet': '28px',
        'composer': '24px',
        'bubble': '16px',
        'bubble-tail': '8px',
      },
      transitionDuration: {
        instant: '90ms',
        quick: '150ms',
        standard: '240ms',
        emphasized: '360ms',
        exit: '180ms',
      },
      transitionTimingFunction: {
        standard: 'cubic-bezier(0.2, 0, 0, 1)',
        decelerate: 'cubic-bezier(0, 0, 0, 1)',
        accelerate: 'cubic-bezier(0.3, 0, 1, 1)',
      },
    },
  },
};
export default preset;
