/**
 * Аркуш дій над листом — поверх композера.
 *
 * 25.09 на A52: аркуш рендерився всередині колонки розмови (власний шар), тож
 * `fixed` тримався колонки, а композер лежав зверху — «Видалити повідомлення»
 * опинявся під смугою вводу й був недосяжний пальцем. Портал у body, як у
 * FormSheet, — єдиний спосіб бути над усім.
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const AREA = readFileSync(join(process.cwd(), 'src/components/messenger/ChatArea.tsx'), 'utf8');
const SRC = readFileSync(join(process.cwd(), 'src/components/messenger/feed/MessageActionSheet.tsx'), 'utf8');

describe('аркуш дій над листом', () => {
  it('рендериться порталом у body, а не всередині колонки', () => {
    // ChatArea відкриває аркуш, а сам аркуш малює себе порталом.
    expect(AREA).toMatch(/\{contextMenuMsg && \(\s*<MessageActionSheet/);
    expect(SRC).toMatch(/return createPortal\(/);
    const start = SRC.indexOf('return createPortal(');
    const end = SRC.indexOf('document.body,', start);
    expect(end).toBeGreaterThan(start);
    expect(SRC.slice(start, end)).toContain("aria-label={t('messageActionSheet.actions')}");
  });
});
