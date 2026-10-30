import { lookVars, type Theme } from './looks.ts';

export const D1_FACE = '"Inter Tight Variable", "Inter Tight", Inter, system-ui, sans-serif';

export function builderTheme(param: string | null): Theme {
  return param === 'day' ? 'day' : 'night';
}

export function builderChrome(theme: Theme): Record<string, string> {
  return {
    ...lookVars('shop', theme),
    '--cx-display': D1_FACE,
    '--cx-display-w': '600',
    '--cx-ui': D1_FACE,
    '--cx-track': '-0.035em',
  };
}
