/**
 * Аркуш «Спитати Nightpost»: (1) дії ніколи не фарбуються акцентом профілю —
 * фокус на полі, кнопки тональні; (2) відповідь про день читає ті самі пункти,
 * що показує аркуш дня.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

vi.mock('../services/askNightpost', () => ({ askNightpost: vi.fn(() => 'sent'), talkToNightpost: vi.fn(() => true) }));

import { AskSheetHost } from '../components/messenger/ask/AskSheet';
import { DaySheet } from '../components/messenger/today/DaySheet';
import { useAskSheet } from '../stores/askSheetStore';
import { ApiError } from '../services/api';
import { phantomSelfApi } from '../services/phantomSelfApi';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { AskContext } from '../services/askContext';
import type { DayItem } from '../services/dayApi';

const ACCENT = /(^|\s)(?:[a-z-]+:)?(?:bg|text|border|ring|fill|stroke)-(?:accent|on-accent|primary)\b/;

const accentIn = (root: HTMLElement) =>
  [root, ...Array.from(root.querySelectorAll<HTMLElement>('*'))]
    .map((el) => el.getAttribute('class') ?? '')
    .filter((c) => ACCENT.test(c));

const CHAT: AskContext = { surface: 'chat', label: 'Daniel', facts: 'Daniel: Dinner at 8?', suggestions: ['Що вирішили?'] };
const COMPOSE: AskContext = { surface: 'compose', label: 'Чернетка', facts: 'My draft: Купи молоко', suggestions: [], draft: 'Купи молоко' };

beforeEach(() => {
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
  vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
});

afterEach(() => {
  cleanup();
  act(() => useAskSheet.getState().close());
  vi.restoreAllMocks();
});

const sheet = () => screen.getByTestId('ask-sheet');

describe('без акценту профілю', () => {
  it('відповідь: «Продовжити в чаті» тональна, нічого акцентного в аркуші', async () => {
    vi.spyOn(phantomSelfApi, 'ask').mockResolvedValue({ answer: 'О восьмій.' });
    render(<AskSheetHost />);
    act(() => useAskSheet.getState().openAsk(CHAT));
    expect(accentIn(sheet())).toEqual([]);
    fireEvent.click(screen.getByTestId('ask-suggestion'));
    await waitFor(() => expect(screen.getByTestId('ask-answer')).toBeTruthy());
    expect(accentIn(sheet())).toEqual([]);
  });

  it('чекання й чесна відмова — теж без акценту', async () => {
    let fail!: (e: unknown) => void;
    vi.spyOn(phantomSelfApi, 'ask').mockReturnValue(new Promise((_, rej) => (fail = rej)));
    render(<AskSheetHost />);
    act(() => useAskSheet.getState().openAsk(CHAT));
    fireEvent.click(screen.getByTestId('ask-suggestion'));
    expect(screen.getByTestId('ask-asking')).toBeTruthy();
    expect(accentIn(sheet())).toEqual([]);
    await act(async () => fail(new ApiError(429, 'limit', 'slow')));
    expect(screen.getByTestId('ask-retry')).toBeTruthy();
    expect(accentIn(sheet())).toEqual([]);
  });

  it('чернетка: «Вставити» тональна', async () => {
    vi.spyOn(phantomSelfApi, 'ask').mockResolvedValue({ answer: 'Візьми молоко' });
    render(<AskSheetHost />);
    act(() => useAskSheet.getState().openAsk(COMPOSE, { onInsert: () => {} }));
    expect(screen.getByTestId('ask-insert')).toBeTruthy();
    expect(accentIn(sheet())).toEqual([]);
  });
});

describe('відповідь про день читає дані аркуша дня', () => {
  it('два нагадування на аркуші → обидва в контексті й у відповіді', async () => {
    const at = (h: number, m = 0) => {
      const d = new Date();
      d.setHours(h, m, 0, 0);
      return d.getTime();
    };
    const items: DayItem[] = [
      { id: 'r1', kind: 'reminder', title: 'Забрати посилку', atMs: at(18), endMs: null, allDay: false, peerId: null, overdue: false },
      { id: 'r2', kind: 'reminder', title: 'Подзвонити мамі', atMs: at(20, 30), endMs: null, allDay: false, peerId: null, overdue: false },
    ];
    const brain = vi.spyOn(phantomSelfApi, 'ask').mockImplementation(async (_q: string, system?: string) => {
      const titles = items.map((i) => i.title).filter((title) => (system ?? '').includes(title));
      return { answer: titles.length > 0 ? `Сьогодні: ${titles.join(', ')}.` : 'Сьогодні нічого.' };
    });
    const noop = vi.fn();
    render(
      <>
        <DaySheet open items={items} calendar="granted" nowMs={at(9)} onClose={noop} onDone={noop} onDrop={noop}
          onSnooze={noop} onRemoveReminder={noop} onOpenChat={noop} onAskCalendar={noop} />
        <AskSheetHost />
      </>,
    );
    const daySheet = screen.getByTestId('day-sheet');
    expect(daySheet.textContent).toContain('Забрати посилку');
    expect(daySheet.textContent).toContain('Подзвонити мамі');
    fireEvent.click(screen.getByTestId('ask-trigger-day'));
    expect(screen.getByTestId('ask-context').textContent).not.toBe('День');
    fireEvent.change(screen.getByTestId('ask-input'), { target: { value: 'Що в мене сьогодні?' } });
    fireEvent.click(screen.getByTestId('ask-send'));
    await waitFor(() => expect(screen.getByTestId('ask-answer')).toBeTruthy());
    const system = brain.mock.calls[0][1] ?? '';
    expect(system).toContain('Забрати посилку');
    expect(system).toContain('Подзвонити мамі');
    expect(system).toMatch(/language of the question/);
    expect(screen.getByTestId('ask-answer').textContent).toBe('Сьогодні: Забрати посилку, Подзвонити мамі.');
  });
});
