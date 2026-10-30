/** Правила копірайту: жодного забороненого слова чи воєнної рамки в рядках en і uk. */
import { describe, it, expect } from 'vitest';
import { en } from '../i18n/messages/en';
import { uk } from '../i18n/messages/uk';

const BANNED = /\blight\b|blackout|darkness|bluetooth|\bmesh\b|jamm|air raid|(?<![А-Яа-яІіЇїЄєҐґ])РЕБ(?![А-Яа-яІіЇїЄєҐґ])|темряв|глушін|глушат|глушил/i;
const CIRCLE_BANNED = /\bcircles?\b|(?<![А-Яа-яІіЇїЄєҐґ])[Кк]ол(?:о|а|і|у|ах|ам|ами|ом)(?![А-Яа-яІіЇїЄєҐґ])/i;

describe('заборонені слова', () => {
  it('en і uk без заборонених слів', () => {
    const hits: string[] = [];
    for (const [lang, table] of [['en', en], ['uk', uk]] as [string, Record<string, string>][]) {
      expect(Object.keys(table).length).toBeGreaterThan(500);
      for (const [key, value] of Object.entries(table)) {
        const clean = value.replace(/\{[^}]*\}/g, "");
        const m = BANNED.exec(clean) ?? (key.startsWith('people.') ? null : CIRCLE_BANNED.exec(clean));
        if (m) hits.push(`${lang} ${key} «${m[0]}»`);
      }
    }
    expect(hits).toEqual([]);
  });
});
