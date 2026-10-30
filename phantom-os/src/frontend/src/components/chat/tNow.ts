/**
 * Non-React translation: reads the current locale from the settings store at
 * call time. For module-level helpers, toasts and text built outside render.
 */
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../../stores/settingsStore';
import { translate, type MessageKey, type TranslateVars } from '../../i18n';
import { resolveLocale } from '../../i18n/useTranslation';
import { dateTag } from '../../i18n/dateTag';

export const tNow = (key: MessageKey, vars?: TranslateVars): string =>
  translate(key, resolveLocale(useSettingsStore.getState().values[LANGUAGE_SETTING_KEY]), vars);

/**
 * Plural word by count. Keys: `<base>.one|few|many` (uk uses all three, the
 * English catalogue fills `.one` and `.many`; `.few` falls back to `.many`;
 * Czech counts 1 · 2-4 · the rest, so `.many` carries its «other»).
 */
const pluralRules = new Map<string, Intl.PluralRules>();

/**
 * Catalogue plural suffix for `n`: CLDR category through Intl, folded onto the
 * three forms the catalogue keeps (one / few / many; "other" and fractions
 * read as many). English-like languages only ever need one and many.
 */
export const pluralKey = (locale: string, n: number, base: string): MessageKey => {
  let rules = pluralRules.get(locale);
  if (!rules) {
    rules = new Intl.PluralRules(dateTag(locale));
    pluralRules.set(locale, rules);
  }
  const cat = rules.select(Number.isInteger(n) ? n : Math.trunc(n));
  const form = cat === 'one' ? 'one' : cat === 'few' ? 'few' : 'many';
  return `${base}.${form}` as MessageKey;
};

export const tPlural = (n: number, base: string): string => {
  const locale = resolveLocale(useSettingsStore.getState().values[LANGUAGE_SETTING_KEY]);
  return translate(pluralKey(locale, n, base), locale);
};

export const currentLocale = () => resolveLocale(useSettingsStore.getState().values[LANGUAGE_SETTING_KEY]);

export const currentDateTag = (): string => dateTag(currentLocale());

/** Picks among three already-chosen forms by the locale's CLDR category. */
export const pickPlural = <T>(locale: string, n: number, one: T, few: T, many: T): T => {
  const form = pluralKey(locale, n, '').slice(1);
  return form === 'one' ? one : form === 'few' ? few : many;
};
