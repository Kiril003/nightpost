/**
 * Бульбашки стрічки, що ще говорили старою палітрою (03.10, v1.2).
 *
 * Діаграма читала лісовий набір `--chart-*`, силует шляху — нічні числа
 * `#5F6A60`/`#F4AF25` у сталій з малої літери (денний сторож dayTheme ловить
 * лише ВЕЛИКІ, тож удень лишалась ніч), жива геолокація — смарагдовий
 * Tailwind. Тепер лише змінні `--ph-*` і класи токенів.
 * Поверніть будь-що з цього — тест червоніє.
 */
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { LiveLocationBubble } from '../components/messenger/LiveLocationBubble';

const MESSENGER = join(__dirname, '..', 'components', 'messenger');
const src = (file: string) => readFileSync(join(MESSENGER, file), 'utf8');
/** Код без коментарів: історія в коментарях може згадувати старе. */
const code = (file: string) =>
  src(file)
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1');

describe('кольори бульбашок — з токенів v1.2', () => {
  it('діаграма бере --ph-*, а не лісовий --chart-*', () => {
    const chart = code('ChartEmbed.tsx');
    expect(chart).not.toMatch(/--chart-/);
    for (const v of ['--ph-accent', '--ph-text-3', '--ph-line', '--ph-line-strong', '--ph-surface']) expect(chart).toContain(`var(${v})`);
  });

  it('силует шляху без чисел-кольорів (і з малої літери теж)', () => {
    const wire = code('PeerWireBubble.tsx');
    expect(wire).not.toMatch(/['"]#[0-9A-Fa-f]{3,8}['"]/);
    expect(wire).toContain('var(--ph-text-3)');
    expect(wire).toContain('var(--ph-accent)');
  });

  it('жива геолокація — success-токени, заголовок картки title-2 (D), без смарагду', () => {
    const now = Date.now();
    const { container } = render(
      <MemoryRouter>
        <LiveLocationBubble location={{ sessionId: 's', point: { lat: 50.45, lon: 30.52, atMs: now }, startedAtMs: now, expiresAtMs: now + 3_600_000 }} />
      </MemoryRouter>,
    );
    const html = container.innerHTML;
    expect(html).not.toMatch(/emerald-|text-xs|sm:text-sm/);
    expect(container.querySelector('.text-success')).not.toBeNull();
    expect(container.querySelector('h4')?.className).toContain('text-title-2');
  });
});
