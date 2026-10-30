/**
 * Аркуш «Спитати Nightpost»: поле, підказки з даних, питання → живий стан →
 * відповідь → «Продовжити в чаті»; чесний стан без відповіді; чернетка з
 * операціями над справжнім текстом і «Вставити».
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

const talk = vi.hoisted(() => ({ askNightpost: vi.fn(() => 'sent'), talkToNightpost: vi.fn(() => true) }));
vi.mock('../services/askNightpost', () => talk);
const nav = vi.hoisted(() => ({ canNavigate: vi.fn(() => true) }));
vi.mock('../services/nav', async (orig) => ({ ...(await orig<typeof import('../services/nav')>()), canNavigate: nav.canNavigate }));

import { AskSheetHost } from '../components/messenger/ask/AskSheet';
import { useAskSheet } from '../stores/askSheetStore';
import { ApiError } from '../services/api';
import { phantomSelfApi } from '../services/phantomSelfApi';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { AskContext } from '../services/askContext';

const GROUP: AskContext = {
  surface: 'group',
  label: 'Двір · 5 непрочитаних',
  facts: 'Петро: Збори о шостій',
  suggestions: ['Підсумуй непрочитане', 'Що вирішили?'],
};

const deferred = <T,>() => {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
};

beforeEach(() => {
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
  nav.canNavigate.mockReturnValue(true);
});

afterEach(() => {
  cleanup();
  act(() => useAskSheet.getState().close());
  vi.restoreAllMocks();
  talk.askNightpost.mockClear();
  talk.talkToNightpost.mockClear();
});

const openWith = (ctx: AskContext, onInsert?: (t: string) => void) => {
  render(<AskSheetHost />);
  act(() => useAskSheet.getState().openAsk(ctx, onInsert ? { onInsert } : undefined));
};

describe('питання про поверхню', () => {
  it('закритий — нічого; відкритий — шапка, мітка, підказки, поле', () => {
    render(<AskSheetHost />);
    expect(screen.queryByTestId('ask-sheet')).toBeNull();
    act(() => useAskSheet.getState().openAsk(GROUP));
    expect(screen.getByTestId('ask-sheet').textContent).toContain('Nightpost');
    expect(screen.getByTestId('ask-context').textContent).toBe('Двір · 5 непрочитаних');
    expect(screen.getAllByTestId('ask-suggestion').map((b) => b.textContent)).toEqual(['Підсумуй непрочитане', 'Що вирішили?']);
    expect((screen.getByTestId('ask-input') as HTMLInputElement).placeholder).toBe('Спитати про розмову');
  });

  it('без даних — лише поле, без підказок', () => {
    openWith({ ...GROUP, suggestions: [] });
    expect(screen.queryAllByTestId('ask-suggestion')).toHaveLength(0);
    expect(screen.getByTestId('ask-input')).toBeTruthy();
  });

  it('підказка → живий стан → відповідь → «Продовжити в чаті»', async () => {
    const d = deferred<{ answer: string }>();
    const spy = vi.spyOn(phantomSelfApi, 'ask').mockReturnValue(d.promise);
    openWith(GROUP);
    fireEvent.click(screen.getAllByTestId('ask-suggestion')[0]);
    expect(screen.getByTestId('ask-question').textContent).toBe('Підсумуй непрочитане');
    expect(screen.getByTestId('ask-asking')).toBeTruthy();
    expect(spy).toHaveBeenCalledWith('Підсумуй непрочитане', expect.stringContaining('Збори о шостій'));
    await act(async () => d.resolve({ answer: 'Збори в четвер о 18:00.' }));
    expect(screen.getByTestId('ask-answer').textContent).toBe('Збори в четвер о 18:00.');
    const go = screen.getByTestId('ask-continue');
    expect(go.className).toContain('bg-overlay');
    fireEvent.click(go);
    expect(talk.askNightpost).toHaveBeenCalledWith('Підсумуй непрочитане');
    expect(useAskSheet.getState().ctx).toBeNull();
  });

  it('питання з поля — Enter', async () => {
    const spy = vi.spyOn(phantomSelfApi, 'ask').mockResolvedValue({ answer: 'О 18:00' });
    openWith(GROUP);
    const input = screen.getByTestId('ask-input');
    fireEvent.change(input, { target: { value: 'Коли збори?' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    await screen.findByTestId('ask-answer');
    expect(spy).toHaveBeenCalledWith('Коли збори?', expect.any(String));
  });

  it('без розуму — чесний стан з поясненням телефона і «Спробувати ще»', async () => {
    const spy = vi
      .spyOn(phantomSelfApi, 'ask')
      .mockRejectedValueOnce(new ApiError(503, 'no_mind', 'Додайте ключ у налаштуваннях'))
      .mockResolvedValueOnce({ answer: 'Тепер є' });
    openWith(GROUP);
    fireEvent.click(screen.getAllByTestId('ask-suggestion')[1]);
    const none = await screen.findByTestId('ask-none');
    expect(none.getAttribute('data-reason')).toBe('no_mind');
    expect(none.textContent).toContain('Nightpost не підключений');
    expect(none.textContent).toContain('Додайте ключ у налаштуваннях');
    fireEvent.click(screen.getByTestId('ask-retry'));
    expect(await screen.findByTestId('ask-answer')).toBeTruthy();
    expect(spy).toHaveBeenCalledTimes(2);
  });

  it('без звʼязку — «Немає звʼязку»', async () => {
    vi.spyOn(phantomSelfApi, 'ask').mockRejectedValue(new TypeError('Failed to fetch'));
    openWith(GROUP);
    fireEvent.click(screen.getAllByTestId('ask-suggestion')[0]);
    const none = await screen.findByTestId('ask-none');
    expect(none.getAttribute('data-reason')).toBe('offline');
    expect(none.textContent).toContain('Немає звʼязку');
  });

  it('мікрофон — жива розмова; без оболонки мікрофона немає', () => {
    openWith(GROUP);
    fireEvent.click(screen.getByTestId('ask-voice'));
    expect(talk.talkToNightpost).toHaveBeenCalled();
    cleanup();
    act(() => useAskSheet.getState().close());
    nav.canNavigate.mockReturnValue(false);
    openWith(GROUP);
    expect(screen.queryByTestId('ask-voice')).toBeNull();
  });

  it('Escape закриває', () => {
    openWith(GROUP);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(useAskSheet.getState().ctx).toBeNull();
  });

  it('мітки без великих літер на весь рядок і без розмиття', () => {
    openWith(GROUP);
    const html = screen.getByTestId('ask-sheet').innerHTML;
    expect(html).not.toMatch(/\buppercase\b|blur|shadow-(?!none)|gradient/);
  });
});

describe('чернетка', () => {
  const COMPOSE: AskContext = {
    surface: 'compose',
    label: 'Відповідь · Оксана',
    facts: 'Draft: Біжу, буду за 5 хвилин',
    suggestions: [],
    draft: 'Біжу, буду за 5 хвилин',
  };

  it('операція над справжньою чернеткою → «Вставити» кладе текст у композер', async () => {
    const onInsert = vi.fn();
    const spy = vi.spyOn(phantomSelfApi, 'ask').mockResolvedValue({ answer: 'Буду за 5 хв!' });
    openWith(COMPOSE, onInsert);
    expect(screen.getByText('Чернетка')).toBeTruthy();
    expect(screen.getByTestId('ask-draft').textContent).toBe('Біжу, буду за 5 хвилин');
    fireEvent.click(screen.getByTestId('ask-op-shorter'));
    await waitFor(() => expect(screen.getByTestId('ask-draft').textContent).toBe('Буду за 5 хв!'));
    expect(spy.mock.calls[0][0]).toContain('Біжу, буду за 5 хвилин');
    expect(spy.mock.calls[0][1]).toMatch(/only the rewritten message text/i);
    const insert = screen.getByTestId('ask-insert');
    expect(insert.className).toContain('bg-overlay');
    fireEvent.click(insert);
    expect(onInsert).toHaveBeenCalledWith('Буду за 5 хв!');
    expect(useAskSheet.getState().ctx).toBeNull();
  });

  it('порожня чернетка — поле «Що написати?», відповідь стає чернеткою', async () => {
    vi.spyOn(phantomSelfApi, 'ask').mockResolvedValue({ answer: 'Запізнююсь на 10 хвилин' });
    openWith({ ...COMPOSE, draft: '', facts: 'Last incoming: Так, чекаю' }, vi.fn());
    expect(screen.queryByTestId('ask-draft')).toBeNull();
    const input = screen.getByTestId('ask-input') as HTMLInputElement;
    expect(input.placeholder).toBe('Що написати?');
    fireEvent.change(input, { target: { value: 'скажи, що запізнюсь' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    await waitFor(() => expect(screen.getByTestId('ask-draft').textContent).toBe('Запізнююсь на 10 хвилин'));
    expect(screen.getByTestId('ask-again')).toBeTruthy();
  });
});
