/**
 * PHANTOM i18n runtime.
 *
 * The whole thing is a catalogue lookup plus a `{var}` interpolator — no
 * dependency. uk/en/cs ship in the bundle; the wave locales are JSON chunks
 * loaded on first use (until then their keys read English).
 *
 * Resolution order for every key: requested locale → its `FALLBACK_CHAIN`
 * (English, then the Ukrainian source) → the key itself. So an untranslated
 * Czech string shows readable English instead of `chat.sessions.title`, and a
 * key that exists nowhere is still visible in the UI rather than rendering
 * empty.
 */
import { DEFAULT_LOCALE, FALLBACK_CHAIN, isLazyLocale, type LazyLocale, type Locale } from './locales';
import { cs } from './messages/cs';
import { en } from './messages/en';
import { uk, type MessageKey } from './messages/uk';

export type { MessageKey };
export {
  DEFAULT_LOCALE,
  EAGER_LOCALES,
  FALLBACK_CHAIN,
  LAZY_LOCALES,
  LOCALES,
  LOCALE_LABELS,
  isLocale,
  matchLocale,
  systemLocale,
  type Locale,
} from './locales';

type Catalogue = Partial<Record<MessageKey, string>>;

const CATALOGUES: Partial<Record<Locale, Catalogue>> = { uk, en, cs };

// Wave locales are JSON chunks: one network-free file read on first use
// instead of ~2 MB parsed on every start.
const LAZY = import.meta.glob<Catalogue>('./messages/lazy/*.json', { import: 'default' });
const loading = new Map<LazyLocale, Promise<void>>();
const listeners = new Set<() => void>();
let version = 0;

/** Bumps whenever a catalogue arrives; useTranslation re-renders on it. */
export const catalogueVersion = (): number => version;

export function subscribeCatalogues(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function isLocaleLoaded(locale: Locale): boolean {
  return CATALOGUES[locale] !== undefined;
}

/** Loads a wave locale once; eager locales resolve at once. */
export function loadLocale(locale: Locale): Promise<void> {
  if (!isLazyLocale(locale) || CATALOGUES[locale]) return Promise.resolve();
  const pending = loading.get(locale);
  if (pending) return pending;
  const load = LAZY[`./messages/lazy/${locale}.json`];
  const p = (load ? load() : Promise.resolve({}))
    .then((table) => {
      CATALOGUES[locale] = table;
      version += 1;
      listeners.forEach((l) => l());
    })
    .catch(() => {
      loading.delete(locale);
    });
  loading.set(locale, p);
  return p;
}

/** Values substituted into `{name}` placeholders. */
export type TranslateVars = Record<string, string | number>;

const PLACEHOLDER = /\{(\w+)\}/g;

function interpolate(template: string, vars?: TranslateVars): string {
  if (!vars) return template;
  return template.replace(PLACEHOLDER, (match, name: string) => {
    const value = vars[name];
    // An unknown placeholder stays literal — it reads as an authoring bug in
    // the catalogue instead of silently swallowing part of the sentence.
    return value === undefined ? match : String(value);
  });
}

export function translate(
  key: MessageKey,
  locale: Locale = DEFAULT_LOCALE,
  vars?: TranslateVars,
): string {
  const own = CATALOGUES[locale];
  if (own === undefined) void loadLocale(locale);
  let template = own?.[key];
  if (template === undefined) {
    for (const next of FALLBACK_CHAIN[locale] ?? [DEFAULT_LOCALE]) {
      template = CATALOGUES[next]?.[key];
      if (template !== undefined) break;
    }
  }
  return interpolate(template ?? uk[key] ?? key, vars);
}
