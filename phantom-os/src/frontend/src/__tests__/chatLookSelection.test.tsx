import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const read = (p: string) => readFileSync(resolve(__dirname, '..', '..', p), 'utf8');

describe('виділення листа нейтральне, не бурштинове', () => {
  it('рамка виділеної бульбашки без accent', () => {
    for (const f of ['src/styles/form.css', 'src/styles/messenger.css']) {
      const rules = read(f).match(/\.msg-bubble-selected\s*\{[^}]*\}/g) ?? [];
      expect(rules.length).toBeGreaterThan(0);
      for (const r of rules) expect(r).not.toMatch(/accent/);
    }
  });
  it('позначка вибору — колір тексту', () => {
    expect(read('src/components/messenger/feed/MessageRow.tsx')).not.toMatch(/isSelected \? 'text-accent/);
  });
});
