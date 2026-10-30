/**
 * msg-x зріз 5: календар телефона питаємо лише в контексті — «Показати календар»
 * → аркуш із трьома підписами → «Дозволити» → системний запит. Смужка дня під
 * шапкою «Розмов» показує найближче сьогодні одним рядком.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render } from '@testing-library/react';
import { DayBand } from '../components/messenger/today/DayBand';
import { DaySheet } from '../components/messenger/today/DaySheet';
import { DayStrip } from '../components/messenger/today/DayStrip';
import { TopSheet } from '../components/messenger/today/TopSheetView';
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
const noop = () => undefined;
const askSheet = () => document.querySelector('[data-testid="calendar-ask"]') as HTMLElement | null;
const click = (sel: string) => fireEvent.click(document.querySelector(sel) as HTMLElement);

beforeEach(() => {
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('календар — лише після аркуша з підписами', () => {
  it('смуга дня: «Показати календар» відкриває аркуш, системний запит — лише на «Дозволити»', () => {
    const ask = vi.fn();
    render(<DayBand top={[]} total={0} status="ready" calendar="denied" nowMs={at(12)} onOpenAll={noop} onOpenItem={noop} onAskCalendar={ask} />);
    click('[data-day-band-calendar]');
    click('[data-testid="calendar-sheet"] [data-day-show-calendar]');
    expect(ask).not.toHaveBeenCalled();
    const sheet = askSheet()!;
    expect(sheet.textContent).toContain('Календар телефона');
    for (const label of ['Події дня', 'Лише читання', 'Лишається на телефоні']) expect(sheet.textContent).toContain(label);
    click('[data-calendar-ask-allow]');
    expect(ask).toHaveBeenCalledTimes(1);
  });

  it('«Не зараз» закриває аркуш без системного запиту', () => {
    const ask = vi.fn();
    render(<DayBand top={[]} total={0} status="ready" calendar="denied" nowMs={at(12)} onOpenAll={noop} onOpenItem={noop} onAskCalendar={ask} />);
    click('[data-day-band-calendar]');
    click('[data-testid="calendar-sheet"] [data-day-show-calendar]');
    click('[data-calendar-ask-later]');
    expect(ask).not.toHaveBeenCalled();
  });

  it('розгорнутий порожній день: один вхід у календар — кнопка «Календар» з іконкою', () => {
    render(<DayBand top={[]} total={0} status="ready" calendar="denied" nowMs={at(12)} onOpenAll={noop} onOpenItem={noop} onAskCalendar={noop} />);
    const band = document.querySelector('[data-day-band]') as HTMLElement;
    expect(band.querySelectorAll('[data-day-show-calendar], [data-day-band-calendar]')).toHaveLength(1);
    expect(band.querySelector('[data-day-band-calendar] svg')).not.toBeNull();
  });

  it('календар тижня з смуги теж питає через аркуш', () => {
    const ask = vi.fn();
    render(<DayBand top={[]} total={0} status="ready" calendar="denied" nowMs={at(12)} onOpenAll={noop} onOpenItem={noop} onAskCalendar={ask} />);
    click('[data-day-band-calendar]');
    click('[data-testid="calendar-sheet"] [data-day-show-calendar]');
    expect(ask).not.toHaveBeenCalled();
    expect(askSheet()).not.toBeNull();
    click('[data-calendar-ask-allow]');
    expect(ask).toHaveBeenCalledTimes(1);
  });

  it('аркуш дня: те саме', () => {
    const ask = vi.fn();
    render(
      <DaySheet open items={[]} calendar="denied" nowMs={at(12)} onClose={noop} onDone={noop} onDrop={noop}
        onSnooze={noop} onRemoveReminder={noop} onOpenChat={noop} onAskCalendar={ask} />,
    );
    click('[data-day-show-calendar]');
    expect(ask).not.toHaveBeenCalled();
    click('[data-calendar-ask-allow]');
    expect(ask).toHaveBeenCalledTimes(1);
  });
});

describe('смужка дня під шапкою «Розмов»', () => {
  const keys = item('r1', 'reminder', 'Забрати ключі', 18);
  const yoga = item('e1', 'event', 'Йога', 19);
  const unread = item('unread:c1', 'unread', 'Оксана', 10, { allDay: true, chatId: 'c1', count: 2 });

  it('найближче сьогодні одним рядком: час, назва, «ще N»; дотик розкриває день', () => {
    const onOpen = vi.fn();
    render(<DayStrip items={[keys, yoga, unread]} nowMs={at(17, 30)} onOpen={onOpen} />);
    const strip = document.querySelector('[data-day-strip]') as HTMLElement;
    expect(strip.textContent).toContain('18:00');
    expect(strip.textContent).toContain('Забрати ключі');
    expect(strip.textContent).toContain('ще 1');
    expect(strip.textContent).not.toContain('Оксана');
    fireEvent.click(strip);
    expect(onOpen).toHaveBeenCalledTimes(1);
  });

  it('нічого на сьогодні — смужки немає', () => {
    render(<DayStrip items={[unread, item('e0', 'event', 'Ранок', 8, { endMs: at(9) })]} nowMs={at(17, 30)} onOpen={noop} />);
    expect(document.querySelector('[data-day-strip]')).toBeNull();
  });

  const top = (state: 'compact' | 'open') =>
    render(
      <TopSheet state={state} onStateChange={noop} showChips={false} scrolled={false} onOpenSearch={noop} onNewChat={noop} onOpenDay={noop}
        tabs={[]} activeTab="all" onPickTab={noop} day={<div data-test-day />} dayLine={<div data-test-dayline />} />,
    );

  it('шапка: смужка видна, поки день згорнутий; розгорнули — замість неї сам день', () => {
    top('compact');
    expect(document.querySelector('[data-test-dayline]')).not.toBeNull();
    cleanup();
    top('open');
    expect(document.querySelector('[data-test-dayline]')).toBeNull();
    expect(document.querySelector('[data-test-day]')).not.toBeNull();
  });
});
