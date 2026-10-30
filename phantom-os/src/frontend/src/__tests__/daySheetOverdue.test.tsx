import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { DaySheet } from '../components/messenger/today/DaySheet';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { DayItem } from '../services/dayApi';

const at = (h: number) => {
  const d = new Date();
  d.setHours(h, 0, 0, 0);
  return d.getTime();
};

afterEach(() => {
  cleanup();
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
});

describe('DaySheet: overdue does not truncate in the time column', () => {
  it('the column keeps the due time; «Прострочено» sits whole in the action row', () => {
    useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
    const items = [
      { id: 'r1', kind: 'reminder', title: 'Подзвонити мамі', atMs: at(9), endMs: null, allDay: false, peerId: null, overdue: true },
      { id: 'p1', kind: 'promise', title: 'Повернути книгу', atMs: at(8), endMs: null, allDay: false, peerId: null, overdue: true },
    ] as DayItem[];
    const noop = vi.fn();
    render(
      <DaySheet open items={items} calendar="granted" nowMs={at(12)} onClose={noop} onDone={noop} onDrop={noop}
        onSnooze={noop} onRemoveReminder={noop} onOpenChat={noop} onAskCalendar={noop} />,
    );
    const sheet = screen.getByTestId('day-sheet');
    const times = [...sheet.querySelectorAll('[data-day-row] [data-day-when]')].map((n) => n.textContent);
    expect(times).toEqual(['09:00', '08:00']);
    const tags = [...sheet.querySelectorAll('[data-day-overdue]')];
    expect(tags.map((n) => n.textContent)).toEqual(['Прострочено', 'Прострочено']);
    for (const n of tags) expect(n.className).not.toMatch(/\btruncate\b/);
  });
});
