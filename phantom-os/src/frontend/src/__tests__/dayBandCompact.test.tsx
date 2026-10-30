import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { DayBand } from '../components/messenger/today/DayBand';
import { DaySheet } from '../components/messenger/today/DaySheet';
import type { DayItem } from '../services/dayApi';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';

const at = (h: number, m = 0) => {
  const d = new Date();
  d.setHours(h, m, 0, 0);
  return d.getTime();
};
const item = (id: string, kind: DayItem['kind'], title: string, h: number, extra: Partial<DayItem> = {}): DayItem => ({
  id, kind, title, atMs: at(h), endMs: null, allDay: false, peerId: null, overdue: false, ...extra,
});
const keys = item('r1', 'reminder', 'Забрати ключі', 18);
const yoga = item('e1', 'event', 'Йога', 19);
const okSana = item('unread:c1', 'unread', 'Оксана', 10, { allDay: true, chatId: 'c1', count: 2 });
const p1 = item('p1', 'promise', 'Скинути макет', 9, { allDay: true, chatId: 'c9', from: 'Богдан' });
const p2 = item('p2', 'promise', 'Повернути книгу', 9, { allDay: true, chatId: 'c8', from: 'Марта' });
const noop = () => undefined;

beforeEach(() => {
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('День у шапці не витісняє розмови: «Зараз» + один рядок «Далі»', () => {
  it('повний день — у смузі лише картка й рядок «Далі: час назва · ще N», решта в аркуші', () => {
    const onOpenAll = vi.fn();
    const items = [keys, yoga, okSana, p1, p2];
    render(
      <DayBand top={[keys, yoga, okSana]} items={items} total={items.length} status="ready" calendar="granted" nowMs={at(17, 30)}
        onOpenAll={onOpenAll} onOpenItem={noop} onAskCalendar={noop} onDone={noop} onSnooze={noop} onCreateReminder={noop} />,
    );
    const band = document.querySelector('[data-day-band]') as HTMLElement;
    const blocks = band.querySelectorAll('[data-day-focal], [data-day-band-next], [data-day-later], [data-day-promises], [data-day-band-reminders], [data-day-band-calendar]');
    expect([...blocks].map((n) => [...n.attributes].map((a) => a.name).find((a) => a.startsWith('data-day-')))).toEqual(['data-day-top', 'data-day-band-next']);
    const next = band.querySelector('[data-day-band-next]') as HTMLElement;
    expect(next.textContent).toBe('Далі: 19:00 Йога · ще 2');
    fireEvent.click(next);
    expect(onOpenAll).toHaveBeenCalledTimes(1);
  });
});

describe('непрочитані розмови — не пункти дня', () => {
  it('«Далі» в смузі ніколи не веде на непрочитану розмову', () => {
    render(
      <DayBand top={[keys, okSana]} items={[keys, okSana]} total={2} status="ready" calendar="granted" nowMs={at(17, 30)}
        onOpenAll={noop} onOpenItem={noop} onAskCalendar={noop} onDone={noop} onSnooze={noop} />,
    );
    const band = document.querySelector('[data-day-band]') as HTMLElement;
    expect(band.querySelector('[data-day-top="unread"]')).toBeNull();
    expect(band.querySelector('[data-day-band-next]')).toBeNull();
    expect(band.textContent).not.toContain('Оксана');
    expect(band.querySelector('[data-day-caption]')?.textContent).toBe('Сьогодні · ще 1');
  });

  it('аркуш Дня не має рядка непрочитаної розмови', () => {
    render(
      <DaySheet open items={[yoga, okSana]} calendar="granted" nowMs={at(12)} onClose={noop} onDone={noop} onDrop={noop}
        onSnooze={noop} onRemoveReminder={noop} onOpenChat={noop} onOpenScheduled={noop} onAskCalendar={noop} />,
    );
    const sheet = screen.getByTestId('day-sheet');
    expect([...sheet.querySelectorAll('[data-day-row]')].map((n) => n.getAttribute('data-day-row'))).toEqual(['event']);
    expect(sheet.textContent).not.toContain('Оксана');
    expect(sheet.querySelector('[data-day-caption]')?.textContent).toBe('Сьогодні · ще 1');
  });

  it('у аркуші обіцянка без часу не має обрізаної мітки в колонці часу', () => {
    render(
      <DaySheet open items={[yoga, p1]} calendar="granted" nowMs={at(12)} onClose={noop} onDone={noop} onDrop={noop}
        onSnooze={noop} onRemoveReminder={noop} onOpenChat={noop} onOpenScheduled={noop} onAskCalendar={noop} />,
    );
    const rows = [...screen.getByTestId('day-sheet').querySelectorAll('[data-day-row]')];
    const when = (kind: string) => rows.find((r) => r.getAttribute('data-day-row') === kind)?.querySelector('[data-day-when]')?.textContent;
    expect(when('promise') ?? '').toBe('');
    expect(when('event')).toBe('19:00');
  });
});

describe('аркуш Дня: обіцянки окремо, один кружок, тихі рядки з іконками', () => {
  const remind = item('r9', 'reminder', 'Подзвонити', 20);
  const sheetWith = (over: Partial<React.ComponentProps<typeof DaySheet>> = {}) =>
    render(
      <DaySheet open items={[keys, remind, yoga, p1, p2]} calendar="granted" nowMs={at(12)} onClose={noop} onDone={noop} onDrop={noop}
        onSnooze={noop} onRemoveReminder={noop} onOpenChat={noop} onOpenScheduled={noop} onAskCalendar={noop} onCreateReminder={noop} {...over} />,
    );

  it('«Обіцянки · 2» з підписом, з якої розмови', () => {
    sheetWith();
    const sheet = screen.getByTestId('day-sheet');
    const head = sheet.querySelector('[data-day-promises-head]') as HTMLElement;
    expect(head.textContent).toBe('Обіцянки2');
    const rows = [...sheet.querySelectorAll('[data-day-row="promise"]')];
    expect(rows.map((r) => r.textContent)).toEqual([expect.stringContaining('з розмови · Богдан'), expect.stringContaining('з розмови · Марта')]);
  });

  it('один кружок «Зроблено» в кінці рядка; «Прибрати» лише після довгого натискання', () => {
    const onDone = vi.fn();
    const onDrop = vi.fn();
    sheetWith({ onDone, onDrop });
    const row = screen.getByTestId('day-sheet').querySelector('[data-day-row="promise"]') as HTMLElement;
    expect(row.textContent).not.toContain('Прибрати');
    const buttons = [...row.querySelectorAll('button')];
    expect(buttons.map((b) => b.getAttribute('aria-label'))).toEqual(['Зроблено']);
    fireEvent.click(buttons[0]);
    expect(onDone).toHaveBeenCalledWith('p1');
    fireEvent.contextMenu(row);
    fireEvent.click(within(row).getByRole('button', { name: 'Прибрати' }));
    expect(onDrop).toHaveBeenCalledWith('p1');
  });

  it('обіцянка без бурштинової смужки: бурштин на екрані — лише «Зроблено» картки', () => {
    sheetWith();
    const sheet = screen.getByTestId('day-sheet');
    for (const row of sheet.querySelectorAll('[data-day-row="promise"]')) expect(row.querySelector('.bg-accent-line')).toBeNull();
  });

  it('Календар, Нове нагадування, Нагадування — з іконкою на початку, як у налаштуваннях', () => {
    sheetWith();
    const sheet = screen.getByTestId('day-sheet');
    for (const sel of ['[data-day-calendar]', '[data-day-create]', '[data-day-reminders-row]']) {
      const row = sheet.querySelector(sel) as HTMLElement;
      expect(row.firstElementChild?.querySelector('svg') ?? (row.firstElementChild?.tagName === 'svg' ? row.firstElementChild : null)).toBeTruthy();
    }
  });
});

