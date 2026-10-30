/**
 * LOOK-SPEC §2.1 item 6: the Nightpost and Saved rows on Today sit in quiet grey.
 */
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { PhantomMark } from '../components/messenger/PhantomMark';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { Chat, UserProfile } from '../types/messenger';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

vi.mock('../services/dayApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/dayApi')>();
  return {
    ...actual,
    listPhoneReminders: () => Promise.resolve([]),
    cancelPhoneReminder: () => Promise.resolve(true),
    snoozePhoneReminder: () => Promise.resolve(true),
    dayApi: {
      ...actual.dayApi,
      today: () => Promise.resolve({ items: [], calendar: { connected: false, permission: false, busy: [] } }),
      listPhoneReminders: () => Promise.resolve([]),
      cancelPhoneReminder: () => Promise.resolve(true),
      snoozePhoneReminder: () => Promise.resolve(true),
    },
  };
});

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

const setLocale = (l: 'uk' | 'en' | 'cs') => useSettingsStore.setState({ values: { [LANGUAGE_SETTING_KEY]: l } });

afterEach(() => {
  cleanup();
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

const row = (id: string) => document.querySelector(`[data-chat-row="${id}"]`) as HTMLElement;
const tokens = (n: Element) => new Set((n.getAttribute('class') ?? '').split(/\s+/));

describe('W2 calm system rows', () => {
  it('the tonal mark is bg-raised text-ink, and the default mark stays the inverted disc', () => {
    const tonal = render(<PhantomMark variant="tonal" />);
    const t = tokens(tonal.container.querySelector('[data-phantom-mark]')!);
    expect(t.has('bg-raised')).toBe(true);
    expect(t.has('text-ink')).toBe(true);
    expect(t.has('bg-ink')).toBe(false);
    expect(t.has('text-canvas')).toBe(false);
    tonal.unmount();
    const plain = render(<PhantomMark />);
    const p = tokens(plain.container.querySelector('[data-phantom-mark]')!);
    expect(p.has('bg-ink')).toBe(true);
    expect(p.has('text-canvas')).toBe(true);
  });

  it('the Nightpost row wears the same inverted mark as the chat header (LOOK G3: ≥3:1 to the canvas)', () => {
    show([{ id: 'node', title: 'Nightpost', type: 'phantom', pinned: true, unreadCount: 0, messages: [], lastAt: '2026-10-14T10:00:00Z' }]);
    const mark = row('node').querySelector('[data-phantom-mark]')!;
    expect(mark).not.toBeNull();
    const t = tokens(mark);
    expect(t.has('bg-ink')).toBe(true);
    expect(t.has('text-canvas')).toBe(true);
    expect(t.has('bg-raised')).toBe(false);
  });

  it('global search shows the assistant with the same inverted mark as the row and header', () => {
    const src = readFileSync(join(__dirname, '../components/messenger/GlobalSearch.tsx'), 'utf8');
    expect(src).toContain('<PhantomMark');
    expect(src).not.toMatch(/<PhantomMark[^>]*variant="tonal"/);
  });

  it('the Saved avatar is flat grey: bg-raised text-ink-2, no border, no amber', async () => {
    show([{ id: 'chat_saved_notes', title: 'Saved', type: 'saved', pinned: true, unreadCount: 0, messages: [], lastAt: '2026-10-14T10:00:00Z' }]);
    const avatar = row('chat_saved_notes').querySelector('[data-saved-avatar]')!;
    const t = [...tokens(avatar)];
    expect(t).toContain('bg-raised');
    expect(t).toContain('text-ink-2');
    expect(t.filter((c) => c.startsWith('border') || c.includes('accent'))).toEqual([]);
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });
    expect(document.querySelector('[data-testid="reminders-line"]')).toBeNull();
  });
});
