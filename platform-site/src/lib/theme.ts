import { useSyncExternalStore } from 'react';

export type Theme = 'day' | 'night';

export const THEME_KEY = 'nightpost-theme';
const COLOR: Record<Theme, string> = { day: '#F6F1E7', night: '#111214' };

export function pickTheme(stored: string | null, systemDark: boolean): Theme {
  if (stored === 'day' || stored === 'night') return stored;
  return systemDark ? 'night' : 'day';
}

function readStored(): string | null {
  try {
    return localStorage.getItem(THEME_KEY);
  } catch {
    return null;
  }
}

function systemQuery(): MediaQueryList | null {
  return typeof window !== 'undefined' && window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
}

export function applyTheme(theme: Theme, root: HTMLElement = document.documentElement) {
  root.dataset.theme = theme;
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', COLOR[theme]);
}

let current: Theme = typeof document === 'undefined' ? 'day' : pickTheme(readStored(), systemQuery()?.matches ?? false);
const listeners = new Set<() => void>();

function set(theme: Theme) {
  if (theme === current) return;
  current = theme;
  applyTheme(theme);
  listeners.forEach((fn) => fn());
}

export function startTheme() {
  applyTheme(current);
  systemQuery()?.addEventListener('change', (e) => {
    if (!readStored()) set(e.matches ? 'night' : 'day');
  });
}

export function setTheme(theme: Theme) {
  try {
    localStorage.setItem(THEME_KEY, theme);
  } catch {
    /* приватне вікно: тема діє до перезавантаження */
  }
  set(theme);
}

function subscribe(fn: () => void) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

export function useTheme(): Theme {
  return useSyncExternalStore(subscribe, () => current, () => 'day');
}
