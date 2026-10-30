import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

describe('.touch-floor-inline і absolute', () => {
  it('position: relative не перебиває absolute (кнопка налаштувань профілю з’їжджала під картку)', () => {
    const css = readFileSync(resolve(__dirname, '../styles/globals.css'), 'utf8');
    const rules = [...css.matchAll(/([^{}]*\.touch-floor-inline[^{}]*)\{([^}]*)\}/g)];
    const positioning = rules.filter(([, , body]) => /position:\s*relative/.test(body));
    expect(positioning.length).toBeGreaterThan(0);
    for (const [, selector] of positioning) expect(selector).toContain(':not(.absolute)');
  });
});
