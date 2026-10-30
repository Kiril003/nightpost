import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { en, uk } from '../i18n/messages/parts/circles';
import { cs } from '../i18n/messages/parts/cs/circles';

const NAMING = /circle|коло|кол|kruh|okruh/i;

describe('circles copy', () => {
  it('no locale names a circle', () => {
    const tables = { uk, en, cs } as Record<string, Record<string, string>>;
    for (const [locale, table] of Object.entries(tables)) {
      for (const [key, value] of Object.entries(table)) {
        expect(NAMING.test(value), `${locale} ${key} «${value}»`).toBe(false);
      }
    }
  });

  it('all three locales carry the same keys', () => {
    expect(Object.keys(en).sort()).toEqual(Object.keys(uk).sort());
    expect(Object.keys(cs).sort()).toEqual(Object.keys(uk).sort());
  });

  it('the sheet does not use the old keys that still name a circle', () => {
    const src = readFileSync(resolve(__dirname, '../components/messenger/CircleSheet.tsx'), 'utf8');
    expect(src).not.toMatch(/circles\.(title|add|none|move|failed)\b/);
  });
});
