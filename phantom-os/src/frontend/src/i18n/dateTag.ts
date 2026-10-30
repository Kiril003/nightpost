import { DEFAULT_LOCALE, type Locale } from './locales';

const TAGS: Record<Locale, string> = {
  uk: 'uk-UA',
  en: 'en-GB',
  cs: 'cs-CZ',
  es: 'es-ES',
  'pt-BR': 'pt-BR',
  de: 'de-DE',
  fr: 'fr-FR',
  pl: 'pl-PL',
  tr: 'tr-TR',
  it: 'it-IT',
  id: 'id-ID',
};

export const dateTag = (lang: string): string => TAGS[lang as Locale] ?? TAGS[DEFAULT_LOCALE];
