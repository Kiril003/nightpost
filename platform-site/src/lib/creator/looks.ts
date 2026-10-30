export const LOOKS = ['journal', 'studio', 'shop'] as const;
export type Look = (typeof LOOKS)[number];
export type Theme = 'day' | 'night';

export const D_COLOR: Record<Theme, Record<string, string>> = {
  day: {
    canvas: '#F6F1E7', surface: '#FCFAF5', raised: '#EDE6D8', overlay: '#E3DACA',
    line: '#DED9D0', 'line-strong': '#CFCAC1',
    text: '#1E1A15', 'text-2': '#5B5247', 'text-3': '#665F55',
    accent: '#C27A16', 'accent-hover': '#CE8722', 'accent-text': '#84510D', 'accent-line': '#B87415',
    'accent-soft': '#F1E6D2', 'on-accent': '#1E1A15', success: '#18703E', danger: '#B3382C', cover: '#D8C6A2',
  },
  night: {
    canvas: '#111214', surface: '#1C1D21', raised: '#202125', overlay: '#2B2C31',
    line: '#26272A', 'line-strong': '#333438',
    text: '#ECEAE5', 'text-2': '#A3A4AA', 'text-3': '#949599',
    accent: '#E2A048', 'accent-hover': '#E8AE60', 'accent-text': '#E8AE60', 'accent-line': '#E2A048',
    'accent-soft': '#2A2620', 'on-accent': '#1B1408', success: '#7FC79A', danger: '#E8877A', cover: '#17181B',
  },
};

export const D_PERSON: Record<Theme, { fill: string; ink: string }[]> = {
  day: [
    { fill: '#E8D4C3', ink: '#7A3D1C' }, { fill: '#D6DECB', ink: '#3E5A2F' }, { fill: '#D2DBE4', ink: '#2E4966' },
    { fill: '#E7DDC0', ink: '#6C5516' }, { fill: '#E5D3D9', ink: '#6A3147' }, { fill: '#D9D3E2', ink: '#47396A' },
  ],
  night: [
    { fill: '#3A2A22', ink: '#E3B89C' }, { fill: '#26301F', ink: '#B5CCA2' }, { fill: '#212C38', ink: '#A9C0DA' },
    { fill: '#353020', ink: '#DCC98E' }, { fill: '#382630', ink: '#E2B3C6' }, { fill: '#2B2738', ink: '#C3B8E0' },
  ],
};

export const D_RADIUS = { chip: 8, control: 12, card: 20, sheet: 28, full: 9999 } as const;

export type LookSpec = {
  display: string;
  displayWeight: number;
  displayItalicLede: boolean;
  tracking: string;
  h1: [number, number];
  h2: [number, number];
  cardRadius: number;
  imageRadius: number;
  section: [number, number];
  page: 'canvas' | 'surface';
  primary: 'hero' | 'booking';
};

const UI = '"Manrope Variable", Manrope, system-ui, sans-serif';

export const LOOK: Record<Look, LookSpec> = {
  journal: {
    display: '"Source Serif 4 Variable", Georgia, serif',
    displayWeight: 400,
    displayItalicLede: true,
    tracking: '-0.015em',
    h1: [44, 80],
    h2: [30, 44],
    cardRadius: D_RADIUS.control,
    imageRadius: 0,
    section: [64, 120],
    page: 'canvas',
    primary: 'booking',
  },
  studio: {
    display: '"Inter Tight Variable", "Inter Tight", system-ui, sans-serif',
    displayWeight: 700,
    displayItalicLede: false,
    tracking: '-0.05em',
    h1: [48, 96],
    h2: [34, 56],
    cardRadius: D_RADIUS.sheet,
    imageRadius: D_RADIUS.sheet,
    section: [64, 112],
    page: 'surface',
    primary: 'booking',
  },
  shop: {
    display: `"Wix Madefor Display Variable", ${UI}`,
    displayWeight: 700,
    displayItalicLede: false,
    tracking: '-0.03em',
    h1: [42, 76],
    h2: [26, 32],
    cardRadius: D_RADIUS.card,
    imageRadius: D_RADIUS.card,
    section: [56, 96],
    page: 'canvas',
    primary: 'hero',
  },
};

export function isLook(v: unknown): v is Look {
  return typeof v === 'string' && (LOOKS as readonly string[]).includes(v);
}

export function lookVars(look: Look, theme: Theme): Record<string, string> {
  const spec = LOOK[look];
  const c = D_COLOR[theme];
  const vars: Record<string, string> = {};
  for (const [k, v] of Object.entries(c)) vars[`--cx-${k}`] = v;
  D_PERSON[theme].forEach((t, i) => {
    vars[`--cx-t${i + 1}`] = t.fill;
    vars[`--cx-t${i + 1}i`] = t.ink;
  });
  vars['--cx-page'] = c[spec.page];
  vars['--cx-ink'] = c.text;
  vars['--cx-on-ink'] = theme === 'day' ? c.surface : c.canvas;
  vars['--cx-display'] = spec.display;
  vars['--cx-display-w'] = String(spec.displayWeight);
  vars['--cx-ui'] = UI;
  vars['--cx-track'] = spec.tracking;
  vars['--cx-h1-s'] = `${spec.h1[0]}px`;
  vars['--cx-h1-l'] = `${spec.h1[1]}px`;
  vars['--cx-h2-s'] = `${spec.h2[0]}px`;
  vars['--cx-h2-l'] = `${spec.h2[1]}px`;
  vars['--cx-card'] = `${spec.cardRadius}px`;
  vars['--cx-img'] = `${spec.imageRadius}px`;
  vars['--cx-sec-s'] = `${spec.section[0]}px`;
  vars['--cx-sec-l'] = `${spec.section[1]}px`;
  return vars;
}
