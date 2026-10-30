import type { Locale } from '../../../i18n/locales';
import { uk, en } from '../../../i18n/messages/parts/profileKit';
import { cs } from '../../../i18n/messages/parts/cs/profileKit';

type Key = keyof typeof uk;
const WORDS: Partial<Record<Locale, Record<Key, string>>> = { uk, en, cs };

export function dirWord(locale: Locale, key: Key, vars: Record<string, string> = {}): string {
  const template = (WORDS[locale] ?? en)[key] ?? uk[key];
  return template.replace(/\{(\w+)\}/g, (m, v: string) => vars[v] ?? m);
}
