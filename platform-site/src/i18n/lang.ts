import type { Lang } from './types';

export const STORAGE_KEY = 'phantom-lang';

export function initialLang(): Lang {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored === 'uk' || stored === 'en') return stored;
  } catch {
    /* private mode or blocked storage */
  }
  return 'en';
}
