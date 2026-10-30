import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { EduHost } from '../directions/education/EduHost';
import { useMessengerStore } from '../../../stores/messengerStore';
import { useEduNav } from '../../../services/edu/eduNav';
import { parseEdu } from '../../../services/edu/eduWire';
import { classEv, evMsg, fakeSend, GROUP, ME, resetEdu, seed, TEACHER } from './eduKit';

let prev: ReturnType<typeof useMessengerStore.getState>;
beforeEach(() => {
  prev = useMessengerStore.getState();
  resetEdu();
});
afterEach(() => {
  cleanup();
  resetEdu();
  useMessengerStore.setState(prev, true);
});

const FUTURE = Date.now() + 86_400_000;
const assign = (id = 'a1', due: number | null = FUTURE, teacherSelf = false) =>
  evMsg(TEACHER, { k: 'assign', id, t: 'Лабораторна 4', due, note: 'Таблиця й висновок', at: 3 }, ['Завдання · Лабораторна 4'], '', teacherSelf);

describe('Завдання: вчитель', () => {
  it('порожній список: чесний стан і єдина дія «Нове завдання»', () => {
    seed('teacher', [evMsg(TEACHER, classEv(), ['k'], '', true)]);
    useEduNav.getState().push({ s: 'assign', chatId: GROUP });
    render(<EduHost />);
    expect(screen.getByTestId('edu-assignments-empty').textContent).toContain('Завдань немає');
    expect(screen.getAllByText('Нове завдання')).toHaveLength(1);
  });

  it('видача: назва, строк, опис → лист із читабельним початком і міткою assign', () => {
    seed('teacher', [evMsg(TEACHER, classEv(), ['k'], '', true)]);
    const send = fakeSend();
    useEduNav.getState().push({ s: 'assign', chatId: GROUP });
    render(<EduHost />);
    fireEvent.click(screen.getByText('Нове завдання'));
    const publish = screen.getByTestId('edu-assign-publish') as HTMLButtonElement;
    expect(publish.disabled).toBe(true);
    fireEvent.change(screen.getByTestId('edu-assign-title'), { target: { value: 'Лабораторна 4' } });
    const n = new Date();
    const pick = new Date(n.getFullYear(), n.getMonth() + 1, 25, 9, 0);
    const key = `${pick.getFullYear()}-${String(pick.getMonth() + 1).padStart(2, '0')}-25`;
    fireEvent.click(screen.getByTestId('edu-assign-due'));
    fireEvent.click(screen.getByTestId('when-pick'));
    fireEvent.click(screen.getByRole('button', { name: 'Наступний місяць' }));
    fireEvent.click(document.querySelector(`[data-day="${key}"]`)!);
    fireEvent.click(screen.getByRole('button', { name: /^Обрати · / }));
    fireEvent.change(screen.getByTestId('edu-assign-note'), { target: { value: 'Таблиця й висновок' } });
    fireEvent.click(screen.getByTestId('edu-assign-publish'));
    const text = send.mock.calls[0][0] as string;
    expect(text.split('\n')[0]).toBe('Завдання · Лабораторна 4');
    const p = parseEdu(text)!;
    expect(p.ev).toMatchObject({ k: 'assign', t: 'Лабораторна 4', note: 'Таблиця й висновок', due: pick.getTime() });
    expect(screen.getByTestId(`edu-a-${(p.ev as { id: string }).id}`).textContent).toContain('Лабораторна 4');
  });

  it('деталі: лічильники, фільтр «Не здали» за замовчуванням, нагадати рівно тим, хто не здав (st3 і я-учень)', () => {
    seed('teacher', [
      evMsg(TEACHER, classEv(), ['k'], '', true),
      assign('a1', 100, true),
      evMsg('st1', { k: 'done', a: 'a1', t: 'Лабораторна 4', at: 90 }),
      evMsg('st2', { k: 'done', a: 'a1', t: 'Лабораторна 4', at: 150 }),
    ]);
    const send = fakeSend();
    useEduNav.getState().push({ s: 'assignment', chatId: GROUP, id: 'a1' });
    render(<EduHost />);
    expect(screen.getByTestId('edu-count-done').textContent).toContain('1');
    expect(screen.getByTestId('edu-count-late').textContent).toContain('1');
    expect(screen.getByTestId('edu-count-missing').textContent).toContain('2');
    expect(screen.getByTestId('edu-row-st3')).toBeTruthy();
    expect(screen.queryByTestId('edu-row-st1')).toBeNull();
    fireEvent.click(screen.getByTestId('edu-filter-all'));
    expect(screen.getByTestId('edu-row-st1')).toBeTruthy();
    expect(screen.getByTestId('edu-remind').textContent).toBe('Нагадати 2');
    fireEvent.click(screen.getByTestId('edu-remind'));
    expect(send).toHaveBeenCalledTimes(1);
    expect(send.mock.calls[0][0]).toBe('Нагадування · Лабораторна 4');
    expect((screen.getByTestId('edu-remind') as HTMLButtonElement).disabled).toBe(true);
  });

  it('T58: «Усі» стоїть першим фільтром; смуга тонка і є лише тоді, коли хтось здав', () => {
    seed('teacher', [evMsg(TEACHER, classEv(), ['k'], '', true), assign('a1', FUTURE, true)]);
    useEduNav.getState().push({ s: 'assignment', chatId: GROUP, id: 'a1' });
    const { unmount } = render(<EduHost />);
    const chips = screen.getAllByRole('tab').map((c) => c.getAttribute('data-testid'));
    expect(chips[0]).toBe('edu-filter-all');
    expect(screen.queryByTestId('edu-progress')).toBeNull();
    unmount();

    seed('teacher', [
      evMsg(TEACHER, classEv(), ['k'], '', true),
      assign('a1', FUTURE, true),
      evMsg('st1', { k: 'done', a: 'a1', t: 'Лабораторна 4', at: 5 }),
    ]);
    render(<EduHost />);
    expect(screen.getByTestId('edu-progress').className).toMatch(/(^|\s)h-1(\s|$)/);
  });

  it('усі здали — кнопки нагадування немає; оцінок ніхто не вигадує', () => {
    seed('teacher', [
      evMsg(TEACHER, classEv(), ['k'], '', true),
      assign('a1', null, true),
      ...['st1', 'st2', 'st3'].map((s) => evMsg(s, { k: 'done', a: 'a1', t: 'x', at: 5 })),
      evMsg(ME, { k: 'done', a: 'a1', t: 'x', at: 5 }),
    ]);
    useEduNav.getState().push({ s: 'assignment', chatId: GROUP, id: 'a1' });
    render(<EduHost />);
    expect(screen.queryByTestId('edu-remind')).toBeNull();
    expect(screen.getByTestId('edu-all-done')).toBeTruthy();
    expect(document.body.textContent).not.toContain('Оцінити');
  });

  it('склад невідомий — це сказано, а не нуль учнів із зеленим «усі здали»', () => {
    seed('teacher', [evMsg(TEACHER, classEv(), ['k'], '', true), assign('a1', null, true)], false);
    useEduNav.getState().push({ s: 'assignment', chatId: GROUP, id: 'a1' });
    render(<EduHost />);
    expect(screen.getByTestId('edu-no-members').textContent).toBe('Склад невідомий');
    expect(screen.queryByTestId('edu-all-done')).toBeNull();
  });
});

describe('Завдання: учень', () => {
  it('«Здав» шле лист done, після нього стан «Здали», кнопка зникає; чужих статусів немає', () => {
    seed('student', [evMsg(TEACHER, classEv(), ['k']), assign('a1')]);
    const send = fakeSend();
    useEduNav.getState().push({ s: 'assignment', chatId: GROUP, id: 'a1' });
    render(<EduHost />);
    expect(screen.queryByTestId('edu-rows')).toBeNull();
    expect(screen.queryByTestId('edu-remind')).toBeNull();
    act(() => {
      fireEvent.click(screen.getByTestId('edu-mark-done'));
    });
    expect(parseEdu(send.mock.calls[0][0] as string)?.ev).toMatchObject({ k: 'done', a: 'a1', t: 'Лабораторна 4' });
    expect(screen.getByTestId('edu-mine').textContent).toContain('Здали');
    expect(screen.queryByTestId('edu-mark-done')).toBeNull();
  });

  it('прострочена здача мітиться «Пізно»', () => {
    seed('student', [evMsg(TEACHER, classEv(), ['k']), assign('a1', 100), evMsg(ME, { k: 'done', a: 'a1', t: 'x', at: 500 }, ['x'], '', true)]);
    useEduNav.getState().push({ s: 'assignment', chatId: GROUP, id: 'a1' });
    render(<EduHost />);
    expect(screen.getByTestId('edu-mine').textContent).toContain('Пізно');
  });
});
