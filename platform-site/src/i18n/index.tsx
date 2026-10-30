import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { en } from './en';
import { uk } from './uk';
import { STORAGE_KEY, initialLang } from './lang';
import type { Copy, Lang } from './types';

export const dict: Record<Lang, Copy> = { en, uk };

interface LanguageValue {
  lang: Lang;
  setLang: (next: Lang) => void;
  t: Copy;
}

const LanguageContext = createContext<LanguageValue | null>(null);

export function LanguageProvider({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState<Lang>(initialLang);
  const t = dict[lang];

  // Titles/descriptions are page-level concerns (see usePageMeta); the
  // provider only keeps the document language attribute in sync.
  useEffect(() => {
    document.documentElement.lang = lang;
  }, [lang]);

  const setLang = useCallback((next: Lang) => {
    setLangState(next);
    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch {
      /* preference simply does not persist */
    }
  }, []);

  return (
    <LanguageContext.Provider value={{ lang, setLang, t }}>{children}</LanguageContext.Provider>
  );
}

export function useLanguage(): LanguageValue {
  const ctx = useContext(LanguageContext);
  if (!ctx) throw new Error('useLanguage must be used inside <LanguageProvider>');
  return ctx;
}

/** Shorthand for the common case: a component that only reads copy. */
export function useT(): Copy {
  return useLanguage().t;
}
