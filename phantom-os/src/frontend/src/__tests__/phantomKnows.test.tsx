import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';

const facts = [
  { id: 'a', text: 'Тебе звати Кирило', from: 'self', from_label: 'твої слова', skill: false, at: new Date().toISOString(), group: 'about', device: 'phone' },
  { id: 'b', text: 'Ти любиш каву', from: 'heard', from_label: 'почув у розмові', skill: false, at: '2026-09-20T10:00:00Z', group: 'about', device: 'pc' },
  { id: 'd', text: 'Галина їздить на Матизі', from: 'heard', from_label: 'почув у розмові', skill: false, at: '2026-09-20T10:00:00Z', group: 'people', subject: 'Галина' },
  { id: 'e', text: 'Ти, схоже, любиш джаз', from: 'guess', from_label: 'мій здогад · ще не підтверджено', skill: false, at: '2026-09-20T10:00:00Z', group: 'pending', pending: true },
  { id: 'c', text: 'Skoda Octavia 2015, сіра', from: 'guess', from_label: 'мій висновок', skill: false, at: '2026-08-01T10:00:00Z', old: true },
];
const forget = vi.fn(async (_id: string, _why?: string) => ({ forgotten: true }));
const state = vi.fn(async () => ({
  node: 'phone', items: 3, pending: 1, budget_tokens: 1500, persistent: true,
  sync: { peer: 'pc', paired: true, last_ok_ms: Date.now() - 5 * 60000, last_error: null },
}));
const exportAll = vi.fn(async () => ({ format: 'phantom-memory/1', exported_at: 1, count: 4, items: [] }));

vi.mock('../services/phantomSelfApi', () => ({
  phantomSelfApi: {
    facts: vi.fn(async () => facts.map((f) => ({ ...f }))),
    forget: (id: string, why?: string) => forget(id, why),
    memoryState: () => state(),
    exportAll: () => exportAll(),
  },
}));

import { PhantomKnows, memoryStateLine } from '../components/messenger/PhantomKnows';
import { phoneChatMenuDoors } from '../components/messenger/PhoneChatMenu';

describe('Що я про вас знаю', () => {
  beforeEach(() => {
    forget.mockClear();
    exportAll.mockClear();
  });

  it('розкладає памʼять по розділах, кожен рядок — зі своїм джерелом і місцем народження', async () => {
    render(<PhantomKnows />);
    const about = await screen.findByTestId('phantom-knows-group-about');
    expect(about.textContent).toContain('Про вас · 2');
    expect(within(about).getByText(/твої слова · з телефона · сьогодні/)).toBeTruthy();
    expect(within(about).getByText(/почув у розмові · з ПК/)).toBeTruthy();
    expect(screen.getByTestId('phantom-knows-group-people').textContent).toContain('Галина їздить на Матизі');
    expect(screen.getByTestId('phantom-knows-group-pending').textContent).toContain('ще не підтверджено');
    expect(screen.getAllByRole('button', { name: 'неправда' })).toHaveLength(5);
    expect(screen.getAllByRole('button', { name: 'забудь' })).toHaveLength(5);
  });

  it('чесний стан угорі: скільки памʼяті і коли востаннє синхронізувались', async () => {
    render(<PhantomKnows />);
    const line = await screen.findByTestId('phantom-knows-state');
    expect(line.textContent).toContain('3 записів у памʼяті');
    expect(line.textContent).toContain('1 чекають підтвердження');
    expect(line.textContent).toContain('синхронізовано з ПК 5 хв тому');
  });

  it('стан не прикрашає: не спарено, помилка, памʼять лише в RAM', () => {
    const base = { node: 'phone' as const, items: 0, pending: 0, budget_tokens: 1500, sync: { peer: 'pc' as const } };
    expect(memoryStateLine({ ...base, sync: { ...base.sync, paired: false } })).toContain('ПК не спарено');
    expect(memoryStateLine({ ...base, sync: { ...base.sync, paired: true, last_error: 'pull 401 token expired' } }))
      .toContain('синк із ПК не вдався: pull 401 token expired');
    expect(memoryStateLine({ ...base, persistent: false, sync: { ...base.sync, paired: true } }))
      .toContain('не зберігається на диску');
    expect(memoryStateLine({ ...base, node: 'pc', sync: { peer: 'phone', last_ok_ms: null } })).toContain('телефон ще не синхронізувався');
  });

  it('сирі старі записи стоять окремо й тихо, під своїм заголовком', async () => {
    render(<PhantomKnows />);
    const old = await screen.findByTestId('phantom-knows-old');
    expect(old.textContent).toContain('Старі нотатки');
    expect(old.textContent).toContain('Skoda Octavia 2015');
    expect(old.textContent).not.toContain('мій висновок');
  });

  it('«неправда» і «забудь» — різні дії, і рядок зникає лише після підтвердження вузла', async () => {
    render(<PhantomKnows />);
    await screen.findByText('Ти любиш каву');
    fireEvent.click(screen.getAllByRole('button', { name: 'неправда' })[1]);
    await waitFor(() => expect(screen.queryByText('Ти любиш каву')).toBeNull());
    expect(forget).toHaveBeenCalledWith('b', 'false');
    fireEvent.click(screen.getAllByRole('button', { name: 'забудь' })[0]);
    await waitFor(() => expect(screen.queryByText('Тебе звати Кирило')).toBeNull());
    expect(forget).toHaveBeenCalledWith('a', 'forget');
  });

  it('вивантажує всю памʼять файлом', async () => {
    const created = vi.fn(() => 'blob:x');
    Object.assign(URL, { createObjectURL: created, revokeObjectURL: vi.fn() });
    render(<PhantomKnows />);
    fireEvent.click(await screen.findByRole('button', { name: 'Вивантажити памʼять' }));
    expect(await screen.findByText('Вивантажено записів: 4.')).toBeTruthy();
    expect(exportAll).toHaveBeenCalled();
    expect(created).toHaveBeenCalled();
  });

  it('двері є лише в розмові з Nightpost', () => {
    expect(phoneChatMenuDoors({ id: 'peer_phantom_agent', type: 'phantom', title: 'Nightpost' } as never).knows).toBe(true);
    expect(phoneChatMenuDoors({ id: 'x', type: 'direct', title: 'Оля', peerNodeId: 'n' } as never).knows).toBe(false);
  });
});
