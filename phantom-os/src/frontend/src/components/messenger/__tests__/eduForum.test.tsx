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
});
afterEach(() => {
  cleanup();
  resetEdu();
  useMessengerStore.setState(prev, true);
});

const topic = (id: string, from: string, title: string, at: number, self = false) =>
  evMsg(from, { k: 'topic', id, t: title, at }, [`Тема · ${title}`], 'Чому період не залежить від маси?', self);

describe('Форум класу', () => {
  it('порожній — чесний стан; «Нова тема» шле лист topic із назвою й текстом', () => {
    seed('student', [evMsg(TEACHER, classEv(), ['k'])]);
    const send = fakeSend();
    useEduNav.getState().push({ s: 'forum', chatId: GROUP });
    render(<EduHost />);
    expect(screen.getByTestId('edu-forum-empty').textContent).toContain('Тем ще немає');
    fireEvent.click(screen.getByTestId('edu-topic-new'));
    const publish = screen.getByTestId('edu-topic-publish') as HTMLButtonElement;
    expect(publish.disabled).toBe(true);
    fireEvent.change(screen.getByTestId('edu-topic-title'), { target: { value: 'Маятник' } });
    fireEvent.change(screen.getByTestId('edu-topic-body'), { target: { value: 'Чому період не залежить від маси?' } });
    fireEvent.click(publish);
    const text = send.mock.calls[0][0] as string;
    expect(text.split('\n').slice(0, 2)).toEqual(['Тема · Маятник', 'Чому період не залежить від маси?']);
    expect(parseEdu(text)).toMatchObject({ ev: { k: 'topic', t: 'Маятник' }, body: 'Чому період не залежить від маси?' });
    expect(screen.getByTestId('edu-topics').textContent).toContain('Маятник');
  });

  it('фільтри Усі · Відкриті · Вирішені · Мої рахують і відфільтровують', () => {
    seed('student', [
      evMsg(TEACHER, classEv(), ['k']),
      topic('t1', 'st1', 'Маятник', 2),
      topic('t2', ME, 'Пружина', 3, true),
      evMsg('st2', { k: 'reply', id: 'r1', tp: 't1', at: 4 }, ['В'], 'Бо маса скорочується'),
      evMsg(TEACHER, { k: 'answer', tp: 't1', r: 'r1', at: 5 }),
    ]);
    useEduNav.getState().push({ s: 'forum', chatId: GROUP });
    render(<EduHost />);
    expect(screen.getByTestId('edu-forum-filter-all').textContent).toContain('2');
    fireEvent.click(screen.getByTestId('edu-forum-filter-solved'));
    expect(screen.getByTestId('edu-topic-t1')).toBeTruthy();
    expect(screen.queryByTestId('edu-topic-t2')).toBeNull();
    fireEvent.click(screen.getByTestId('edu-forum-filter-mine'));
    expect(screen.getByTestId('edu-topic-t2')).toBeTruthy();
    expect(screen.queryByTestId('edu-topic-t1')).toBeNull();
    fireEvent.click(screen.getByTestId('edu-forum-filter-open'));
    expect(screen.getByTestId('edu-topic-t2').textContent).toContain('Без відповідей');
  });

  it('гілка: відповідь шле лист reply; вчитель позначає відповідь — вона виноситься нагору, тема «Вирішено»', () => {
    seed('teacher', [
      evMsg(TEACHER, classEv(), ['k'], '', true),
      topic('t1', 'st1', 'Маятник', 2),
      evMsg('st2', { k: 'reply', id: 'r1', tp: 't1', at: 4 }, ['В'], 'Бо маса скорочується'),
    ]);
    const send = fakeSend();
    useEduNav.getState().push({ s: 'thread', chatId: GROUP, id: 't1' });
    render(<EduHost />);
    expect(screen.queryByTestId('edu-answer')).toBeNull();
    fireEvent.change(screen.getByTestId('edu-reply-input'), { target: { value: 'Додайте графік' } });
    fireEvent.click(screen.getByTestId('edu-reply-send'));
    expect(parseEdu(send.mock.calls[0][0] as string)).toMatchObject({ ev: { k: 'reply', tp: 't1' }, body: 'Додайте графік' });
    fireEvent.click(screen.getByTestId('edu-mark-answer-r1'));
    expect(parseEdu(send.mock.calls[1][0] as string)?.ev).toMatchObject({ k: 'answer', tp: 't1', r: 'r1' });
    expect(screen.getByTestId('edu-answer').textContent).toContain('Бо маса скорочується');
    expect(screen.queryByTestId('edu-mark-answer-r1')).toBeNull();
  });

  it('сторонній учень не бачить «Позначити відповіддю», автор теми — бачить', () => {
    const base = [evMsg(TEACHER, classEv(), ['k']), topic('t1', 'st1', 'Маятник', 2), evMsg('st2', { k: 'reply', id: 'r1', tp: 't1', at: 4 }, ['В'], 'Відповідь')];
    seed('student', base);
    useEduNav.getState().push({ s: 'thread', chatId: GROUP, id: 't1' });
    const { unmount } = render(<EduHost />);
    expect(screen.queryByTestId('edu-mark-answer-r1')).toBeNull();
    unmount();
    seed('student', [evMsg(TEACHER, classEv(), ['k']), topic('t1', ME, 'Маятник', 2, true), ...base.slice(2)]);
    render(<EduHost />);
    expect(screen.getByTestId('edu-mark-answer-r1')).toBeTruthy();
  });
});
