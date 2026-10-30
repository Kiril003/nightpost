import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { WhereCards, WhereHeaderSign, WhereSheet } from '../components/messenger/WhereCard';
import { mineLine, nearWord, onPeerCheck, theirsLine, wherePeerOf, type WhereState } from '../services/peerWhere';

const NOW = 1_790_000_000_000;
const METERS = /\d\s*(м|км|m|km)\b|метр/i;

const base: WhereState = { now_ms: NOW, eligible: true, why_not: null, mine: null, theirs: null };

let state: WhereState = base;
const calls: Array<{ m: string; p: string; b: string | null }> = [];

function bridge(m: string, p: string, b: string | null): string {
  calls.push({ m, p, b });
  if (!p.startsWith('/messenger/where/')) return JSON.stringify({ ok: false, status: 404 });
  if (m === 'DELETE') state = { ...state, mine: null };
  if (m === 'POST' && p.endsWith('/map')) return JSON.stringify({ ok: true, status: 200, data: { opened: true } });
  return JSON.stringify({ ok: true, status: 200, data: state });
}

const oksana = { id: 'oksana', name: 'Оксана' };
const flush = () => act(async () => { await Promise.resolve(); });
const push = () => act(async () => {
  (window as unknown as { __phantomWhereChanged: () => void }).__phantomWhereChanged();
  await Promise.resolve();
});

beforeEach(() => {
  state = base;
  calls.length = 0;
  (globalThis as { PhantomBridge?: unknown }).PhantomBridge = { call: bridge };
});
afterEach(() => {
  delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
});

describe('рядки картки «Де я»', () => {
  const theirs = (near: 'same' | 'near' | 'far' | null, precision: 'district' | 'quarter' | 'exact' = 'district') =>
    theirsLine('Оксана', { precision, near, at_ms: NOW - 2 * 60_000, expires_at_ms: NOW + 60_000, stand: false }, NOW);

  it('співрозмовник: близькість словом і вік, без метрів', () => {
    expect(theirs('same')).toEqual({ title: 'Оксана показує своє місце', meta: 'у тому ж районі · 2 хв тому' });
    expect(theirs('same', 'exact').meta).toBe('у тому ж кварталі · 2 хв тому');
    expect(theirs('near').meta).toBe('поруч · 2 хв тому');
    expect(theirs('far').meta).toBe('далеко · 2 хв тому');
    expect(theirs(null).meta).toBe('ваше місце невідоме · 2 хв тому');
    for (const n of ['same', 'near', 'far', null] as const) expect(nearWord(n, 'exact')).not.toMatch(METERS);
  });

  it('я: рівень і залишок строку', () => {
    expect(mineLine({ precision: 'district', until_ms: NOW + 42 * 60_000, stand: false }, NOW).meta).toBe('район · ще 42 хв');
    expect(mineLine({ precision: 'quarter', until_ms: NOW + 60 * 60_000, stand: false }, NOW).meta).toBe('квартал · ще 1 год');
    expect(mineLine({ precision: 'exact', until_ms: null, stand: false }, NOW).meta).toBe('точно · до вимкнення');
  });

  it('пункт є лише в розмові з людиною на телефоні', () => {
    expect(wherePeerOf({ peerNodeId: 'oksana', type: 'direct', title: 'Оксана' })).toEqual(oksana);
    expect(wherePeerOf({ peerNodeId: 'g', type: 'group', title: 'Група' })).toBeNull();
    expect(wherePeerOf({ peerNodeId: undefined, type: 'direct', title: 'Nightpost' })).toBeNull();
    delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
    expect(wherePeerOf({ peerNodeId: 'oksana', type: 'direct', title: 'Оксана' })).toBeNull();
  });
});

describe('картки в розмові', () => {
  it('порожній стан — нічого', async () => {
    const { container } = render(<WhereCards peer={oksana} onChange={() => {}} />);
    await flush();
    expect(container.innerHTML).toBe('');
  });

  it('обидва боки, живі, потім зупинка сіріє, і жодного метра', async () => {
    state = {
      ...base,
      mine: { precision: 'district', until_ms: Date.now() + 42 * 60_000, stand: false },
      theirs: { precision: 'district', near: 'near', at_ms: Date.now() - 2 * 60_000, expires_at_ms: Date.now() + 60_000, stand: false },
    };
    render(<WhereCards peer={oksana} onChange={() => {}} />);
    await flush();
    const mine = screen.getByTestId('where-card-mine');
    expect(mine.textContent).toContain('Ви показуєте, де ви');
    expect(mine.textContent).toMatch(/район · ще 4[12] хв/);
    const theirs = screen.getByTestId('where-card-theirs');
    expect(theirs.textContent).toContain('Оксана показує своє місце');
    expect(theirs.textContent).toContain('поруч · 2 хв тому');
    expect(document.body.textContent).not.toMatch(METERS);

    fireEvent.click(screen.getByText('На мапі'));
    expect(calls.at(-1)).toMatchObject({ m: 'POST', p: '/messenger/where/oksana/map' });

    fireEvent.click(screen.getByText('Зупинити'));
    await flush();
    expect(calls.some((c) => c.m === 'DELETE' && c.p === '/messenger/where/oksana')).toBe(true);
    expect(screen.queryByTestId('where-card-mine')).toBeNull();
    expect(screen.getByTestId('where-card-mine-ended').textContent).toContain('Ви більше не показуєте');

    state = { ...state, theirs: null };
    await push();
    expect(screen.queryByTestId('where-card-theirs')).toBeNull();
    expect(screen.getByTestId('where-card-theirs-ended').textContent).toContain('Оксана більше не показує місце');
    expect(screen.queryByText('На мапі')).toBeNull();
  });

  it('стенд позначено чесно', async () => {
    state = { ...base, theirs: { precision: 'exact', near: 'same', at_ms: Date.now(), expires_at_ms: Date.now() + 60_000, stand: true } };
    render(<WhereCards peer={oksana} onChange={() => {}} />);
    await flush();
    expect(screen.getByTestId('where-card-theirs').textContent).toContain('стенд');
  });
});

describe('аркуш і шапка', () => {
  it('новий телефон: людське речення й «Перевірити» веде в профіль людини, а не в міст', async () => {
    state = { ...base, eligible: false, why_not: 'key_alarm' };
    const closed: string[] = [];
    const checked: string[] = [];
    const off = onPeerCheck((id) => checked.push(id));
    render(<WhereSheet peer={oksana} onClose={() => closed.push('x')} />);
    await flush();
    expect(screen.getByTestId('where-why-not').textContent).toBe(
      'У Оксана новий телефон. Переконайтесь, що це справді Оксана, — і тоді покажете місце.',
    );
    expect(screen.queryByText('Район')).toBeNull();
    const before = calls.length;
    fireEvent.click(screen.getByText('Перевірити'));
    off();
    expect(checked).toEqual(['oksana']);
    expect(closed).toHaveLength(1);
    expect(calls.slice(before).filter((c) => c.m === 'POST')).toEqual([]);
  });

  it('ще не на звʼязку: одне речення без кнопки', async () => {
    for (const why of ['no_key', 'not_introduced'] as const) {
      state = { ...base, eligible: false, why_not: why };
      const { unmount } = render(<WhereSheet peer={oksana} onClose={() => {}} />);
      await flush();
      expect(screen.getByTestId('where-why-not').textContent).toBe(
        'Ще не на звʼязку з Оксана — місце покажеться, щойно ви обміняєтесь першими повідомленнями.',
      );
      expect(screen.queryByText('Перевірити')).toBeNull();
      unmount();
    }
  });

  it('на аркуші немає техніки: ні ключа, ні камери, ні шифрування', async () => {
    const TECH = /ключ|камер|зашифр|шифру/i;
    for (const s of [base, ...(['key_alarm', 'no_key', 'not_introduced', 'unknown'] as const).map((w) => ({ ...base, eligible: false, why_not: w }))]) {
      state = s;
      const { unmount } = render(<WhereSheet peer={oksana} onClose={() => {}} />);
      await flush();
      expect(document.body.textContent).not.toMatch(TECH);
      unmount();
    }
    state = base;
    render(<WhereSheet peer={oksana} onClose={() => {}} />);
    await flush();
    expect(document.body.textContent).toContain('Бачить лише Оксана. Без історії.');
  });

  it('за замовчуванням район; вибір іде в міст словами', async () => {
    render(<WhereSheet peer={oksana} onClose={() => {}} />);
    await flush();
    expect(screen.getByText('Район').closest('button')?.getAttribute('aria-pressed')).toBe('true');
    fireEvent.click(screen.getByText('До вимкнення'));
    fireEvent.click(screen.getByText('Показати'));
    const post = calls.find((c) => c.m === 'POST' && c.p === '/messenger/where/oksana');
    expect(JSON.parse(post!.b!)).toEqual({ precision: 'district', minutes: null });
  });

  /**
   * 03.10, v1.2: обрана клітинка — «своя» пара `self`/`on-self`; підказка під
   * словом теж бере чорнило з цієї пари (`on-self-2`). Досі там стояв
   * `text-ink-3` — сірий на бурштині, який не читався ні вночі, ні вдень.
   */
  it('обрана клітинка: on-self і підказка on-self-2; необрана — ink-3', async () => {
    render(<WhereSheet peer={oksana} onClose={() => {}} />);
    await flush();
    const on = screen.getByText('Район').closest('button')!;
    expect(on.getAttribute('aria-pressed')).toBe('true');
    expect(on.className).toMatch(/\bbg-self\b/);
    expect(on.className).toMatch(/\btext-on-self\b/);
    const [, onHint] = on.querySelectorAll('span');
    expect(onHint.className).toMatch(/\btext-on-self-2\b/);
    expect(onHint.className).not.toMatch(/text-ink-3/);
    const off = screen.getByText('Точно').closest('button')!;
    const [, offHint] = off.querySelectorAll('span');
    expect(offHint.className).toMatch(/\btext-ink-3\b/);
  });

  it('знак у шапці лише коли я показую; дотик — зупинити', async () => {
    const { rerender } = render(<WhereHeaderSign peer={oksana} />);
    await flush();
    expect(screen.queryByTestId('where-header-sign')).toBeNull();
    state = { ...base, mine: { precision: 'quarter', until_ms: null, stand: false } };
    await push();
    rerender(<WhereHeaderSign peer={oksana} />);
    fireEvent.click(screen.getByTestId('where-header-sign'));
    fireEvent.click(screen.getByText('Зупинити показ'));
    await flush();
    expect(calls.some((c) => c.m === 'DELETE')).toBe(true);
    expect(screen.queryByTestId('where-header-sign')).toBeNull();
  });
});

describe('джерело без техніки', () => {
  it('ні аркуш, ні peerWhere не кажуть «ключ»/«камерою» і не ходять у міст по звірку', async () => {
    const { readFileSync } = await import('node:fs');
    const { resolve } = await import('node:path');
    const root = resolve(__dirname, '..');
    const where = readFileSync(resolve(root, 'services/peerWhere.ts'), 'utf8');
    const card = readFileSync(resolve(root, 'components/messenger/WhereCard.tsx'), 'utf8');
    const words = (src: string) => (src.match(/(['`"])(?:(?!\1).)*\1|>[^<{}]+</g) ?? []).join(' ');
    for (const src of [where, card]) expect(words(src)).not.toMatch(/ключ|камер|зашифр/i);
    expect(where).not.toMatch(/'verify'/);
  });
});
