import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from '@testing-library/react';
import * as dayApi from '../services/dayApi';
import type { DayItem } from '../services/dayApi';
import { CalendarSheet } from '../components/messenger/today/CalendarSheet';
import { DayBand } from '../components/messenger/today/DayBand';
import { TextCard } from '../components/messenger/MomentViewer';
import { ListMomentsStrip } from '../components/messenger/list/ListMoments';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';

beforeEach(() => {
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const noon = () => {
  const d = new Date();
  d.setHours(12, 0, 0, 0);
  return d.getTime();
};

describe('LOOK-2910 Моменти + День: лише зламане', () => {
  it('свій кружок у смузі — монограма тону людини, а не бежеве на бежевому', () => {
    render(
      <ListMomentsStrip
        data={{ own: [], people: [], nameOf: () => 'Ви', avatarOf: () => undefined, self: 'u-kyrylo' }}
        me={{ id: 'u-kyrylo', name: 'Кирило', avatar: '' }}
      />,
    );
    const face = document.querySelector('[data-moment-own-add] [aria-label="Кирило"]') as HTMLElement;
    expect(face.className).not.toContain('bg-raised');
    expect(face.style.backgroundColor).toMatch(/--ph-person-\d/);
  });

  it('текстовий момент на весь екран — великим кеглем, не 17 px посеред порожнього поля', () => {
    render(<TextCard text="Перший сніг на даху" />);
    expect(document.querySelector('[data-moment-textcard] p')!.className).toContain('text-display');
  });

  it('значок картки дня відділений від самої картки', () => {
    const item: DayItem = { id: 'r1', kind: 'reminder', title: 'Забрати ключі', atMs: noon() + 3_600_000, endMs: null, allDay: false, peerId: null, overdue: false };
    render(<DayBand top={[item]} total={1} status="ready" calendar="granted" nowMs={noon()} onOpenAll={() => {}} onOpenItem={() => {}} onAskCalendar={() => {}} />);
    const icon = document.querySelector('[data-day-focal-icon]') as HTMLElement;
    expect(icon.className).toContain('bg-canvas');
    expect(icon.className).not.toContain('bg-overlay');
  });

  it('календар: місяць і день з великої, без «0» біля порожнього дня', () => {
    vi.spyOn(dayApi, 'listPhoneReminders').mockResolvedValue([]);
    render(<CalendarSheet nowMs={noon()} today={[]} calendar="denied" onClose={() => {}} onAskCalendar={() => {}} />);
    const sheet = document.querySelector('[data-testid="calendar-sheet"]') as HTMLElement;
    const title = sheet.querySelector('h2, h3, [data-sheet-title]')?.textContent ?? '';
    expect(title.charAt(0)).toBe(title.charAt(0).toLocaleUpperCase('uk-UA'));
    expect(title.charAt(0)).not.toBe(title.charAt(0).toLocaleLowerCase('uk-UA'));
    const day = sheet.querySelector('[data-cal-day-title]')!.textContent!;
    expect(day.charAt(0)).not.toBe(day.charAt(0).toLocaleLowerCase('uk-UA'));
    expect(sheet.querySelector('[data-cal-count]')).toBeNull();
    expect(sheet.querySelector('[data-cal-noperm] button')!.className).toContain('bg-canvas');
  });
});
