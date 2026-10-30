/**
 * Nightpost i18n — locale registry.
 *
 * Ukrainian is the key source (`MessageKey` comes from messages/uk.ts) and,
 * with English and Czech, ships in the main bundle and is pinned complete by
 * tests. The wave locales (LAZY_LOCALES) live in messages/lazy/<id>.json,
 * load on first use and fall back to English, then Ukrainian, per key.
 * Dependency-free: a lookup + a `{var}` interpolator + Intl for plurals/dates.
 */
export const EAGER_LOCALES = ['uk', 'en', 'cs'] as const;
export const LAZY_LOCALES = ['es', 'pt-BR', 'de', 'fr', 'pl', 'tr', 'it', 'id'] as const;
export const LOCALES = [...EAGER_LOCALES, ...LAZY_LOCALES] as const;
export type Locale = (typeof LOCALES)[number];
export type LazyLocale = (typeof LAZY_LOCALES)[number];

/** The key source: complete by construction, the last fallback for every locale. */
export const DEFAULT_LOCALE: Locale = 'uk';

/** What a person with no choice and no matching system language sees. */
export const SYSTEM_FALLBACK_LOCALE: Locale = 'en';

/** Where a missing key is looked for, after the requested locale itself. */
export const FALLBACK_CHAIN: Record<Locale, readonly Locale[]> = {
  uk: [],
  en: ['uk'],
  cs: ['en', 'uk'],
  es: ['en', 'uk'],
  'pt-BR': ['en', 'uk'],
  de: ['en', 'uk'],
  fr: ['en', 'uk'],
  pl: ['en', 'uk'],
  tr: ['en', 'uk'],
  it: ['en', 'uk'],
  id: ['en', 'uk'],
};

export function isLocale(value: unknown): value is Locale {
  return typeof value === 'string' && (LOCALES as readonly string[]).includes(value);
}

export function isLazyLocale(value: unknown): value is LazyLocale {
  return typeof value === 'string' && (LAZY_LOCALES as readonly string[]).includes(value);
}

/**
 * A system or bridge tag (`es-MX`, `pt-PT`, `in-ID`, `de_AT`) → one of ours,
 * or null. Region never matters except that every Portuguese reads pt-BR.
 */
export function matchLocale(tag: unknown): Locale | null {
  if (typeof tag !== 'string' || !tag) return null;
  if (isLocale(tag)) return tag;
  const lang = tag.toLowerCase().split(/[-_]/)[0];
  if (lang === 'pt') return 'pt-BR';
  if (lang === 'in') return 'id';
  return isLocale(lang) ? lang : null;
}

/** The first of the device's languages we speak; none → English. */
export function systemLocale(tags?: readonly string[]): Locale {
  const list =
    tags ??
    (typeof navigator === 'undefined'
      ? []
      : navigator.languages?.length
        ? navigator.languages
        : [navigator.language]);
  for (const tag of list) {
    const hit = matchLocale(tag);
    if (hit) return hit;
  }
  return SYSTEM_FALLBACK_LOCALE;
}

/** Human labels for the language picker, in each language's own name. */
export const LOCALE_LABELS: Record<Locale, string> = {
  uk: 'Українська',
  en: 'English',
  cs: 'Čeština',
  es: 'Español',
  'pt-BR': 'Português (Brasil)',
  de: 'Deutsch',
  fr: 'Français',
  pl: 'Polski',
  tr: 'Türkçe',
  it: 'Italiano',
  id: 'Bahasa Indonesia',
};
