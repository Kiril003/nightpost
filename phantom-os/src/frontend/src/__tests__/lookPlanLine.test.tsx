/**
 * LOOK-SPEC §2.1 item 7: a group row with an upcoming plan says when and where
 * instead of repeating its last message; unread messages win.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from '@testing-library/react';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { planWhenLabel } from '../components/messenger/todayFormat';
import { planItems } from '../components/messenger/today/agenda';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { Chat, ChatPlan, UserProfile } from '../types/messenger';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

const setLocale = (l: 'uk' | 'en' | 'cs') => useSettingsStore.setState({ values: { [LANGUAGE_SETTING_KEY]: l } });

const NOW = new Date(2026, 9, 15, 10, 0).getTime();
const FRIDAY_19 = new Date(2026, 9, 16, 19, 0).getTime();
const HOUR = 3_600_000;

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  setLocale('uk');
});

const noop = () => {};
const USER = { id: 'me', name: 'Олег', avatar: '' } as unknown as UserProfile;

const show = (chats: Partial<Chat>[]) =>
  render(
    <PhoneChatList
      chats={chats as unknown as Chat[]}
      currentUser={USER}
      onSelectChat={noop}
      onNewChat={noop}
      onOpenChatActions={noop}
    />,
  );

const plan = (over: Partial<ChatPlan> = {}): ChatPlan => ({
  meetupId: 'm1',
  venue: 'Cafe Nova',
  organizer: 'me',
  at: FRIDAY_19,
  expiresAt: FRIDAY_19 + 24 * HOUR,
  keptBy: [],
  cancelled: false,
  ...over,
});

const planGroup = (over: Partial<Chat> = {}): Partial<Chat> => ({
  id: 'grp_plan',
  title: 'Pizza night',
  type: 'group',
  circle: 'all',
  avatar: '',
  unreadCount: 0,
  pinned: false,
  messages: [],
  lastSnippet: 'Okey',
  lastAuthor: 'Daniel',
  lastAt: '2026-10-14T10:00:00Z',
  plan: plan(),
  ...over,
});

const row = (id: string) => document.querySelector(`[data-chat-row="${id}"]`) as HTMLElement;
const tokens = (n: Element) => new Set((n.getAttribute('class') ?? '').split(/\s+/));

describe('W3 plan line on a group row', () => {
  it('shows when and where in place of the last message, with a 14 calendar icon in ink-3', () => {
    setLocale('en');
    show([planGroup()]);
    const line = row('grp_plan').querySelector('[data-row-plan]') as HTMLElement;
    expect(line).not.toBeNull();
    expect(line.textContent).toBe('Fri 19:00 · Cafe Nova');
    const icon = line.querySelector('svg')!;
    const c = tokens(icon);
    expect(c.has('w-3.5') && c.has('h-3.5') && c.has('text-ink-3')).toBe(true);
    expect(row('grp_plan').textContent).not.toContain('Okey');
  });

  it('follows the app language: Czech and Ukrainian weekdays, same 24 h time', () => {
    setLocale('cs');
    show([planGroup()]);
    expect(row('grp_plan').querySelector('[data-row-plan]')!.textContent).toBe('pá 19:00 · Cafe Nova');
    cleanup();
    setLocale('uk');
    show([planGroup()]);
    expect(row('grp_plan').querySelector('[data-row-plan]')!.textContent).toBe('пт 19:00 · Cafe Nova');
  });

  it('a plan with no venue shows only the time', () => {
    setLocale('en');
    show([planGroup({ plan: plan({ venue: null }) })]);
    expect(row('grp_plan').querySelector('[data-row-plan]')!.textContent).toBe('Fri 19:00');
  });

  it('unread messages win: with 2 unread there is no plan line and the last message shows', () => {
    show([planGroup({ unreadCount: 2 })]);
    expect(row('grp_plan').querySelector('[data-row-plan]')).toBeNull();
    expect(row('grp_plan').textContent).toContain('Okey');
  });

  it('a cancelled plan has no plan line', () => {
    show([planGroup({ plan: plan({ cancelled: true }) })]);
    expect(row('grp_plan').querySelector('[data-row-plan]')).toBeNull();
  });

  it('a plan that began 1 h ago still shows, one that began 3 h ago does not', () => {
    show([planGroup({ plan: plan({ at: NOW - HOUR }) })]);
    expect(row('grp_plan').querySelector('[data-row-plan]')).not.toBeNull();
    cleanup();
    show([planGroup({ plan: plan({ at: NOW - 3 * HOUR }) })]);
    expect(row('grp_plan').querySelector('[data-row-plan]')).toBeNull();
  });

  it('a plan without a time, and a direct chat that carries a plan, have no plan line', () => {
    show([planGroup({ plan: plan({ at: null }) }), planGroup({ id: 'dm_x', type: 'dm', title: 'Daniel' })]);
    expect(document.querySelector('[data-row-plan]')).toBeNull();
  });
});

describe('a meetup group named "Venue · HH:mm" by groupTitle', () => {
  it('shows the time once, from plan.at, after the plan moved', () => {
    setLocale('en');
    show([planGroup({ title: 'Cafe Nova · 18:00' })]);
    expect(row('grp_plan').querySelector('[data-row-name]')!.textContent).toBe('Cafe Nova');
    expect(row('grp_plan').querySelector('[data-row-plan]')!.textContent).toBe('Fri 19:00 · Cafe Nova');
  });

  it('keeps a time-like suffix in a chat without a plan', () => {
    show([planGroup({ title: 'Run · 07:30', plan: undefined })]);
    expect(row('grp_plan').querySelector('[data-row-name]')!.textContent).toBe('Run · 07:30');
  });

  it('the agenda row of today\'s plan has no stale time in its title', () => {
    const at = NOW + 2 * HOUR;
    const items = planItems([planGroup({ title: 'Cafe Nova · 18:00', plan: plan({ at, expiresAt: at + 24 * HOUR }) }) as Chat], NOW);
    expect(items.map((i) => i.title)).toEqual(['Cafe Nova']);
  });
});

describe('planWhenLabel', () => {
  it('uses the weekday inside the next 6 days and the date after that', () => {
    expect(planWhenLabel(FRIDAY_19, NOW, 'en')).toBe('Fri 19:00');
    const far = new Date(2026, 9, 29, 8, 30).getTime();
    expect(planWhenLabel(far, NOW, 'en')).toBe('29 Oct 08:30');
  });
});
