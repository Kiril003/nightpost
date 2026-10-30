import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react';
import * as dayApi from '../services/dayApi';
import { CalendarSheet, calendarEntries, weekStart } from '../components/messenger/today/CalendarSheet';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const at = (d: number, h: number) => new Date(2026, 9, d, h, 0).getTime();

describe('W4 d-calendar', () => {
  it('тиждень починається з понеділка', () => {
    expect(new Date(weekStart(at(23, 12))).getDate()).toBe(19);
  });

  it('збирає нагадування, плани й сьогоднішні пункти календаря за часом', () => {
    const e = calendarEntries(
      [{ id: 'a', text: 'Квіти', atMs: at(24, 9) }],
      [{ id: 'c1', title: 'Вечеря', at: at(23, 19) }],
      [{ id: 'x', kind: 'event', title: 'Йога', atMs: at(23, 21), endMs: null, allDay: false, peerId: null, overdue: false }],
    );
    expect(e.map((x) => x.kind)).toEqual(['plan', 'phone', 'reminder']);
  });

  it('вибір дня показує лише його пункти; порожній день чесно порожній', async () => {
    vi.spyOn(dayApi, 'listPhoneReminders').mockResolvedValue([{ id: 'a', text: 'Квіти', atMs: at(24, 9) }]);
    const { container } = render(<CalendarSheet nowMs={at(23, 12)} today={[]} calendar="granted" onClose={() => {}} onAskCalendar={() => {}} />);
    expect(container.ownerDocument.querySelector('[data-cal-empty]')).toBeTruthy();
    fireEvent.click(container.ownerDocument.querySelector(`[data-cal-day="${new Date(2026, 9, 24).getTime()}"]`)!);
    await waitFor(() => expect(container.ownerDocument.querySelector('[data-cal-entry="reminder"]')).toBeTruthy());
    expect(container.ownerDocument.querySelector('[data-cal-empty]')).toBeNull();
  });

  it('без доступу до календаря телефону — рядок з кнопкою', () => {
    const ask = vi.fn();
    render(<CalendarSheet nowMs={at(23, 12)} today={[]} calendar="denied" onClose={() => {}} onAskCalendar={ask} />);
    const row = document.querySelector('[data-cal-noperm] button')!;
    fireEvent.click(row);
    expect(ask).toHaveBeenCalled();
  });
});
