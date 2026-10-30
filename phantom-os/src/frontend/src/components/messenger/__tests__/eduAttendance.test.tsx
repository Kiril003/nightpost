import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { EduHost } from '../directions/education/EduHost';
import { useMessengerStore } from '../../../stores/messengerStore';
import { useEduNav } from '../../../services/edu/eduNav';
import { parseEdu } from '../../../services/edu/eduWire';
import { classEv, evMsg, fakeSend, GROUP, ME, resetEdu, seed, TEACHER } from './eduKit';

let prev: ReturnType<typeof useMessengerStore.getState>;
beforeEach(() => {
  prev = useMessengerStore.getState();
  resetEdu();
  useEduNav.getState().push({ s: 'attendance', chatId: GROUP });
});
afterEach(() => {
  cleanup();
  resetEdu();
  useMessengerStore.setState(prev, true);
});

describe('Присутність: вчитель', () => {
  it('без перевірок — чесний порожній стан і одна дія «Нова перевірка»', () => {
    seed('teacher', [evMsg(TEACHER, classEv(), ['k'], '', true)]);
    render(<EduHost />);
    expect(screen.getByTestId('edu-att-empty').textContent).toContain('Перевірок ще не було');
    expect(screen.getAllByText('Нова перевірка')).toHaveLength(1);
  });

  it('відмітка: «Усі присутні», один запізнився, один відсутній → лист із розбивкою; не відмічені не вигадуються', () => {
    seed('teacher', [evMsg(TEACHER, classEv(), ['k'], '', true)]);
    const send = fakeSend();
    render(<EduHost />);
    fireEvent.click(screen.getByTestId('edu-att-new'));
    const save = screen.getByTestId('edu-att-save') as HTMLButtonElement;
    expect(save.disabled).toBe(true);
    fireEvent.change(screen.getByTestId('edu-att-lesson'), { target: { value: 'Урок 10:45' } });
    fireEvent.click(screen.getByTestId('edu-att-all'));
    fireEvent.click(screen.getByTestId('edu-mark-st2-l'));
    fireEvent.click(screen.getByTestId('edu-mark-st3-x'));
    fireEvent.click(save);
    const text = send.mock.calls[0][0] as string;
    expect(text.split('\n')[0]).toBe('Присутність · Урок 10:45 · 3 з 4');
    const ev = parseEdu(text)!.ev;
    expect(ev).toMatchObject({ k: 'attend', lesson: 'Урок 10:45', p: ['st1', ME], l: ['st2'], x: ['st3'] });
    expect(screen.getByTestId('edu-att-history').textContent).toContain('Урок 10:45');
    expect(screen.getByTestId('edu-att-history').textContent).toContain('Присутні 3 з 4');
  });

  it('частково відмічений клас: збереження дозволене, решта лишається «Не відмічено»', () => {
    seed('teacher', [evMsg(TEACHER, classEv(), ['k'], '', true)]);
    fakeSend();
    render(<EduHost />);
    fireEvent.click(screen.getByTestId('edu-att-new'));
    fireEvent.click(screen.getByTestId('edu-mark-st1-p'));
    fireEvent.click(screen.getByTestId('edu-att-save'));
    expect(screen.getByTestId('edu-att-history').textContent).toContain('Не відмічено 3');
  });

  it('склад невідомий — сказано в екрані відмітки, а не порожній список без пояснень', () => {
    seed('teacher', [evMsg(TEACHER, classEv(), ['k'], '', true)], false);
    render(<EduHost />);
    fireEvent.click(screen.getByTestId('edu-att-new'));
    expect(screen.getByTestId('edu-no-members').textContent).toBe('Склад невідомий');
  });
});

describe('Присутність: учень', () => {
  it('бачить лише свій статус за кожен урок і не має кнопки перевірки', () => {
    seed('student', [
      evMsg(TEACHER, classEv(), ['k']),
      evMsg(TEACHER, { k: 'attend', id: 's1', lesson: 'Урок 1', p: [ME], l: [], x: ['st1'], at: 10 }),
      evMsg(TEACHER, { k: 'attend', id: 's2', lesson: 'Урок 2', p: [], l: [], x: [], at: 20 }),
    ]);
    render(<EduHost />);
    expect(screen.queryByTestId('edu-att-new')).toBeNull();
    expect(screen.getByTestId('edu-att-s1').textContent).toContain('Присутні');
    expect(screen.getByTestId('edu-att-s1').textContent).not.toContain('Аліна');
    expect(screen.getByTestId('edu-att-s2').textContent).toContain('Не відмічено');
  });

  it('присутність від не-вчителя не потрапляє в історію', () => {
    seed('student', [evMsg(TEACHER, classEv(), ['k']), evMsg('st1', { k: 'attend', id: 'x', lesson: 'Підроблений', p: [ME], l: [], x: [], at: 5 })]);
    render(<EduHost />);
    expect(screen.queryByText('Підроблений')).toBeNull();
    expect(screen.getByTestId('edu-att-empty')).toBeTruthy();
  });
});
