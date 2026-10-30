/**
 * msg-x: смужки зі сталим часом у нагадуваннях → спільні вікна «Коли».
 * Відкласти: «Інший час» — місяць і колеса; вибір без бурштинових кружків.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';

const sent = vi.hoisted(() => ({ shift: [] as unknown[], task: [] as unknown[], assign: [] as unknown[] }));
vi.mock('../services/edu/eduActions', () => ({
  giveAssignment: (...a: unknown[]) => (sent.assign.push(a), 'as1'),
}));
vi.mock('../services/workSend', () => ({
  sendShift: (...a: unknown[]) => sent.shift.push(a),
  sendTask: (...a: unknown[]) => sent.task.push(a),
}));

import { RemindersSheet } from '../components/messenger/today/RemindersSheet';
import { CreateReminderSheet } from '../components/messenger/today/CreateReminderSheet';
import { pickWhen } from './whenPick';
import { AddShiftSheet } from '../components/messenger/work/AddShiftSheet';
import { TaskSheet } from '../components/messenger/work/TaskSheet';
import { EtaPicker } from '../components/messenger/meetup/EtaPicker';
import { AssignSheet } from '../components/messenger/directions/education/AssignSheet';

const NOW = new Date(2026, 9, 9, 12, 0).getTime();

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
});
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(NOW));
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('відкласти нагадування', () => {
  it('«Інший час» → вікна «Коли» → відкласти на обрану хвилину', () => {
    const onSnooze = vi.fn();
    render(<RemindersSheet items={[{ id: 'r1', text: 'Подзвонити', atMs: NOW + 30 * 60_000 }]} onSnooze={onSnooze} onCancel={() => {}} onClose={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: 'Відкласти' }));
    const sheet = screen.getByTestId('snooze-sheet');
    expect(sheet.querySelectorAll('.bg-accent')).toHaveLength(1);
    expect(sheet.querySelector('input[type="date"],input[type="time"],input[type="datetime-local"]')).toBeNull();
    pickWhen(sheet.querySelector('[data-snooze-other]') as HTMLElement, '2026-10-10', '18', '30');
    const other = screen.getByTestId('snooze-sheet').querySelector('[data-snooze-choice="other"]')!;
    expect(other.getAttribute('aria-checked')).toBe('true');
    fireEvent.click(screen.getByTestId('snooze-sheet').querySelector('[data-snooze-go]')!);
    expect(onSnooze).toHaveBeenCalledWith('r1', (new Date(2026, 9, 10, 18, 30).getTime() - NOW) / 60_000);
  });
});

describe('нове нагадування', () => {
  it('«Коли» — рядок, що відкриває вікна; без datetime-local', () => {
    const onSave = vi.fn();
    render(<CreateReminderSheet nowMs={NOW} onClose={() => {}} onSave={onSave} />);
    expect(document.querySelector('input[type="datetime-local"]')).toBeNull();
    expect(document.querySelector('[data-create-when-value]')!.textContent).toBe('Завтра, 09:00');
    fireEvent.change(document.querySelector('[data-create-text]')!, { target: { value: 'Ліки' } });
    pickWhen(document.querySelector('[data-create-when]') as HTMLElement, '2026-10-12', '08', '15');
    expect(document.querySelector('[data-create-when-value]')!.textContent).toMatch(/12 жовтня, 08:15$/);
    fireEvent.click(document.querySelector('[data-create-save]')!);
    expect(onSave).toHaveBeenCalledWith('Ліки', new Date(2026, 9, 12, 8, 15).getTime());
  });
});

const CHAT = { id: 'w1', type: 'group', title: 'Зміни', messages: [], unreadCount: 0, members: [] } as never;
const noRawFields = () => expect(document.querySelector('input[type="date"],input[type="time"],input[type="datetime-local"]')).toBeNull();

describe('робота: строк завдання і години зміни', () => {
  it('строк завдання — рядок «Строк», швидкий вибір від «зараз», прибирається хрестиком', () => {
    render(<TaskSheet chat={CHAT} onClose={() => {}} />);
    noRawFields();
    expect(screen.getByTestId('work-due').textContent).toContain('Без строку');
    fireEvent.click(screen.getByTestId('work-due'));
    fireEvent.click(screen.getByTestId('when-quick-inHour'));
    expect(screen.getByTestId('work-due').textContent).toContain('Сьогодні, 13:00');
    fireEvent.click(screen.getByRole('button', { name: 'Без строку' }));
    expect(screen.getByTestId('work-due').textContent).toContain('Без строку');
  });

  it('зміна: «до» — лише колеса годин; день тижня в минулому лишається доступним', () => {
    sent.shift.length = 0;
    render(<AddShiftSheet chat={CHAT} day="2026-10-09" onClose={() => {}} />);
    noRawFields();
    fireEvent.click(screen.getByTestId('shift-end'));
    fireEvent.click(within(screen.getByRole('listbox', { name: 'Години' })).getByRole('option', { name: '18' }));
    fireEvent.click(within(screen.getByRole('listbox', { name: 'Хвилини' })).getByRole('option', { name: '30' }));
    fireEvent.click(screen.getByRole('button', { name: /^Обрати · / }));
    expect(screen.getByTestId('shift-end').textContent).toContain('18:30');
    fireEvent.click(screen.getByTestId('shift-start'));
    const past = document.querySelector('[data-day="2026-10-05"]') as HTMLButtonElement;
    expect(past.disabled).toBe(false);
    fireEvent.click(past);
    fireEvent.click(within(screen.getByRole('listbox', { name: 'Години' })).getByRole('option', { name: '08' }));
    fireEvent.click(screen.getByRole('button', { name: /^Обрати · / }));
    fireEvent.click(screen.getByRole('button', { name: 'Створити' }));
    expect(sent.shift[0]).toEqual(['w1', { who: '', day: '2026-10-05', from: '08:00', to: '18:30', role: '' }, '']);
  });
});

describe('«Коли будете» на зустріч', () => {
  it('свій час — колеса годин; після півночі — наступна доба; обраний варіант без бурштину', () => {
    const onPick = vi.fn();
    const at = new Date(2026, 9, 9, 23, 30).getTime();
    render(<EtaPicker open at={at} current={at} onPick={onPick} onClose={() => {}} />);
    noRawFields();
    expect(screen.getByTestId('eta-picker').querySelectorAll('.bg-accent')).toHaveLength(0);
    expect(screen.getByTestId('eta-option-0').getAttribute('aria-pressed')).toBe('true');
    fireEvent.click(screen.getByTestId('eta-own'));
    fireEvent.click(within(screen.getByRole('listbox', { name: 'Години' })).getByRole('option', { name: '00' }));
    fireEvent.click(within(screen.getByRole('listbox', { name: 'Хвилини' })).getByRole('option', { name: '15' }));
    fireEvent.click(screen.getByRole('button', { name: /^Обрати · / }));
    expect(onPick).toHaveBeenCalledWith(new Date(2026, 9, 10, 0, 15).getTime());
  });
});

describe('навчання: строк завдання', () => {
  it('рядок «Строк» відкриває вікна «Коли»; без datetime-local; строк іде в завдання', () => {
    sent.assign.length = 0;
    render(<AssignSheet chatId="cls" onClose={() => {}} />);
    noRawFields();
    expect(screen.getByTestId('edu-assign-due').textContent).toContain('Без строку');
    fireEvent.change(screen.getByTestId('edu-assign-title'), { target: { value: 'Есе' } });
    pickWhen(screen.getByTestId('edu-assign-due'), '2026-10-12', '10', '30');
    expect(screen.getByTestId('edu-assign-due').textContent).toMatch(/12 жовтня, 10:30$/);
    fireEvent.click(screen.getByTestId('edu-assign-publish'));
    expect(sent.assign[0]).toEqual(['cls', 'Есе', new Date(2026, 9, 12, 10, 30).getTime(), '']);
  });
});
