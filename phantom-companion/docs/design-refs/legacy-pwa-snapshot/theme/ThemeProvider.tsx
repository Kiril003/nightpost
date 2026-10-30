import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { PHANTOM_THEMES } from './tokens';
import type { PhantomTheme, ThemeId } from '@/types/theme';

interface ThemeContextValue {
  themeId: ThemeId;
  theme: PhantomTheme;
  setThemeId: (id: ThemeId) => void;
  toggle: () => void;
}

const ThemeContext = createContext<ThemeContextValue | null>(null);
const STORAGE_KEY = 'phantom-companion:themeId';

function readStoredTheme(): ThemeId {
  if (typeof window === 'undefined') return 'sunrise';
  const stored = window.localStorage.getItem(STORAGE_KEY);
  return stored === 'amber' || stored === 'sunrise' ? stored : 'sunrise';
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [themeId, setThemeIdState] = useState<ThemeId>(() => readStoredTheme());
  const [crossFadeFrom, setCrossFadeFrom] = useState<PhantomTheme | null>(null);

  const setThemeId = (id: ThemeId) => {
    if (id === themeId) return;
    setCrossFadeFrom(PHANTOM_THEMES[themeId]);
    setThemeIdState(id);
    try {
      window.localStorage.setItem(STORAGE_KEY, id);
    } catch {
      // ignore
    }
    setTimeout(() => setCrossFadeFrom(null), 320);
  };

  const value = useMemo<ThemeContextValue>(() => {
    const theme = PHANTOM_THEMES[themeId];
    return {
      themeId,
      theme,
      setThemeId,
      toggle: () => setThemeId(themeId === 'sunrise' ? 'amber' : 'sunrise'),
    };
  }, [themeId]);

  useEffect(() => {
    document.documentElement.dataset['phantomTheme'] = value.theme.id;
    document.documentElement.style.background = value.theme.bgGradient;
    const meta = document.querySelector<HTMLMetaElement>('meta[name="theme-color"]:not([media])');
    if (meta) meta.content = value.theme.isDark ? '#0E0A05' : '#F4AF25';
  }, [value.theme]);

  return (
    <ThemeContext.Provider value={value}>
      {children}
      {crossFadeFrom && (
        <div
          aria-hidden
          style={{
            position: 'fixed',
            inset: 0,
            background: crossFadeFrom.bgGradient,
            opacity: 1,
            animation: 'phantom-theme-fade 320ms ease-out forwards',
            pointerEvents: 'none',
            zIndex: 150,
          }}
        />
      )}
    </ThemeContext.Provider>
  );
}

export function useThemeContext(): ThemeContextValue {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error('useThemeContext must be used within <ThemeProvider>');
  return ctx;
}
