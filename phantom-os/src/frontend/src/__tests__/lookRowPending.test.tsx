/**
 * A last own message that is still on its way (`Message.status` queued or
 * sending, read by `rowSignals().sending`) shows as a Pending chip on the Today
 * row. No such message, no chip: nothing is drawn without a real state.
 */
import { afterEach, beforeAll, describe, expect, it } from 'vitest';
import { cleanup, render } from '@testing-library/react';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { Chat, Message, UserProfile } from '../types/messenger';

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

const own = (status: Message['status']): Message =>
  ({
    id: 'm1',
    senderId: 'me',
    senderName: 'Олег',
    senderAvatar: '',
    timestamp: '10:00',
    sentAt: '2026-10-14T10:00:00Z',
    type: 'text',
    text: 'Okey',
    isSelf: true,
    status,
  }) as Message;

const show = (status: Message['status']) =>
  render(
    <PhoneChatList
      chats={[{ id: 'dm_daniel', title: 'Daniel', type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false, messages: [own(status)], lastAt: '2026-10-14T10:00:00Z' } as unknown as Chat]}
      currentUser={USER}
      onSelectChat={noop}
      onNewChat={noop}
      onOpenChatActions={noop}
    />,
  );

const row = () => document.querySelector('[data-chat-row="dm_daniel"]') as HTMLElement;
const tokens = (n: Element) => (n.getAttribute('class') ?? '').split(/\s+/);

describe('Pending chip on a Today row', () => {
  it.each(['queued', 'sending'] as const)('a %s last message shows a pending kit chip in the app language', (status) => {
    setLocale('en');
    show(status);
    const chip = row().querySelector('[data-row-sending] [data-kit="chip"]') as HTMLElement;
    expect(chip).not.toBeNull();
    expect(chip.getAttribute('data-kind')).toBe('pending');
    expect(chip.textContent).toBe('Pending');
    expect(chip.querySelector('svg')).not.toBeNull();
    expect(tokens(chip).filter((c) => /^bg-(accent|success|warning)/.test(c))).toEqual([]);
    expect(row().textContent).toContain('Okey');
  });

  it('is labelled in Czech and Ukrainian too', () => {
    setLocale('cs');
    show('queued');
    expect(row().querySelector('[data-row-sending]')!.textContent).toBe('Čeká');
    cleanup();
    setLocale('uk');
    show('queued');
    expect(row().querySelector('[data-row-sending]')!.textContent).toBe('Очікує');
  });

  it('a delivered message has no chip', () => {
    show('sent');
    expect(row().querySelector('[data-row-sending]')).toBeNull();
    expect(row().querySelector('[data-kit="chip"]')).toBeNull();
  });

  it('a failed message shows the failure word and no pending chip', () => {
    setLocale('en');
    show('failed');
    expect(row().querySelector('[data-row-sending]')).toBeNull();
    expect(row().querySelector('[data-row-failed]')).not.toBeNull();
  });
});
