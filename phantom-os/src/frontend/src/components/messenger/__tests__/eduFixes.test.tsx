import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { EduHost } from '../directions/education/EduHost';
import { EduEventCard } from '../directions/education/EduEventCard';
import { useClass } from '../directions/education/useClass';
import { useMessengerStore } from '../../../stores/messengerStore';
import { useEduNav } from '../../../services/edu/eduNav';
import { eduStrip, parseEdu } from '../../../services/edu/eduWire';
import { searchableFields } from '../../../services/messageSearch';
import { classEv, evMsg, fakeSend, GROUP, ME, resetEdu, seed, TEACHER } from './eduKit';

let prev: ReturnType<typeof useMessengerStore.getState>;
beforeEach(() => {
  prev = useMessengerStore.getState();
  resetEdu();
});
afterEach(() => {
  cleanup();
  resetEdu();
  vi.useRealTimers();
  useMessengerStore.setState(prev, true);
});

const assignEv = (id: string, due: number | null, at = 3) => ({ k: 'assign' as const, id, t: 'Лаб 4', due, note: '', at });

describe('мітка не тече', () => {
  it('eduStrip і пошук по листах не віддають мітку', () => {
    const m = evMsg(TEACHER, assignEv('a1', null), ['Завдання · Лаб 4']);
    expect(eduStrip(m.text!)).toBe('Завдання · Лаб 4');
    expect(eduStrip('привіт')).toBe('привіт');
    expect(searchableFields(m).map(([, t]) => t).join('')).not.toContain('⟦edu1');
  });
});

describe('картка: офіційна лише від вчителя й за наявною ціллю', () => {
  const card = (m: ReturnType<typeof evMsg>, sender: string) => (
    <EduEventCard parsed={parseEdu(m.text)!} chatId={GROUP} senderId={sender} text={eduStrip(m.text!)} />
  );
  it('завдання від учня — звичайний текст; від вчителя без запису в журналі — теж; справжнє — картка', () => {
    const real = evMsg(TEACHER, assignEv('a1', null), ['Завдання · Лаб 4']);
    const forged = evMsg('st1', assignEv('a2', null), ['Завдання · Підробка']);
    seed('student', [evMsg(TEACHER, classEv(), ['k']), real, forged]);
    const { rerender } = render(card(forged, 'st1'));
    expect(screen.getByTestId('edu-card-plain').textContent).toContain('Підробка');
    expect(screen.queryByTestId('edu-card-assign')).toBeNull();
    rerender(card(real, TEACHER));
    expect(screen.getByTestId('edu-card-assign')).toBeTruthy();
    rerender(card(evMsg(TEACHER, assignEv('ghost', null), ['x']), TEACHER));
    expect(screen.getByTestId('edu-card-plain')).toBeTruthy();
  });

  it('маршрут без цілі не лишає шар: знімається сам', () => {
    vi.useFakeTimers();
    seed('student', [evMsg(TEACHER, classEv(), ['k'])]);
    useEduNav.getState().push({ s: 'assignment', chatId: GROUP, id: 'nope' });
    render(<EduHost />);
    expect(document.querySelector('[data-testid="edu-assignment"]')).toBeNull();
    act(() => {
      vi.advanceTimersByTime(1300);
    });
    expect(useEduNav.getState().stack).toHaveLength(0);
  });
});

describe('склад і час', () => {
  it('useClass просить склад у вузла, коли його немає', () => {
    seed('teacher', [evMsg(TEACHER, classEv(), ['k'], '', true)], false);
    const load = vi.fn().mockResolvedValue(true);
    useMessengerStore.setState({ loadGroupMembers: load as never });
    const Probe = () => (useClass(GROUP), null);
    render(<Probe />);
    expect(load).toHaveBeenCalledWith(GROUP);
  });

  it('«Пізно» — за часом листа, а не за словом відправника; без часу листа не вгадуємо', () => {
    const due = 1_000;
    const early = { k: 'done' as const, a: 'a1', t: 'x', at: 10 };
    const m1 = { ...evMsg('st1', early), sentAt: new Date(5_000).toISOString() };
    const m2 = { ...evMsg('st2', early), sentAt: undefined };
    seed('teacher', [evMsg(TEACHER, classEv(), ['k'], '', true), evMsg(TEACHER, assignEv('a1', due), ['a'], '', true), m1, m2]);
    useEduNav.getState().push({ s: 'assignment', chatId: GROUP, id: 'a1' });
    render(<EduHost />);
    expect(screen.getByTestId('edu-count-late').textContent).toContain('1');
    expect(screen.getByTestId('edu-count-done').textContent).toContain('1');
    void ME;
  });
});

describe('поля', () => {
  it('назву уроку можна стерти: поле порожнє, назва за замовчуванням — підказка', () => {
    seed('teacher', [evMsg(TEACHER, classEv(), ['k'], '', true)]);
    fakeSend();
    useEduNav.getState().push({ s: 'attendance', chatId: GROUP });
    render(<EduHost />);
    fireEvent.click(screen.getByTestId('edu-att-new'));
    const input = screen.getByTestId('edu-att-lesson') as HTMLInputElement;
    expect(input.value).toBe('');
    expect(input.placeholder).toMatch(/^Урок \d/);
    fireEvent.change(input, { target: { value: 'Л' } });
    fireEvent.change(input, { target: { value: '' } });
    expect(input.value).toBe('');
  });

  it('рядок присутності: позначки на окремому рядку, імʼя не стискається', () => {
    seed('teacher', [evMsg(TEACHER, classEv(), ['k'], '', true)]);
    useEduNav.getState().push({ s: 'attendance', chatId: GROUP });
    render(<EduHost />);
    fireEvent.click(screen.getByTestId('edu-att-new'));
    const li = screen.getByTestId('edu-mark-st1-p').closest('li')!;
    expect(li.className).toContain('flex-wrap');
    expect(screen.getByTestId('edu-mark-st1-p').parentElement!.className).toContain('w-full');
  });

  it('«Здав» вимикається після дотику: другого листа не буде', () => {
    seed('student', [evMsg(TEACHER, classEv(), ['k']), evMsg(TEACHER, assignEv('a1', null), ['a'])]);
    const send = vi.fn();
    useMessengerStore.setState({ sendMessage: send as never });
    useEduNav.getState().push({ s: 'assignment', chatId: GROUP, id: 'a1' });
    render(<EduHost />);
    const b = screen.getByTestId('edu-mark-done') as HTMLButtonElement;
    fireEvent.click(b);
    fireEvent.click(b);
    expect(send).toHaveBeenCalledTimes(1);
    expect(b.disabled).toBe(true);
  });
});
