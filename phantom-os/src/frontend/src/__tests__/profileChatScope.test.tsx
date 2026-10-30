/**
 * W-S1: the list of a profile is its own chats (np.chatprofile.v1), not a
 * circle filter. Work shows what it owns and folds the rest into «Інші
 * розмови»; a work chat is absent from Особисте.
 */
import { afterEach, beforeAll, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render } from '@testing-library/react';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { templateDefaults, useProfiles } from '../stores/profilesStore';
import { loadChatProfiles, useChatProfiles } from '../stores/chatProfileStore';
import type { Chat, UserProfile } from '../types/messenger';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

const initial = useProfiles.getState();
afterEach(() => {
  cleanup();
  localStorage.clear();
  useChatProfiles.setState(loadChatProfiles());
  useProfiles.setState({ activeId: initial.activeId, profiles: initial.profiles });
});

const noop = () => {};
const USER = { id: 'me', name: 'Олег', avatar: '' } as unknown as UserProfile;
const chat = (id: string, title: string, circle: string) =>
  ({ id, title, type: 'dm', circle, avatar: '', unreadCount: 0, pinned: false, messages: [{ id: `m_${id}`, senderId: id, senderName: title, senderAvatar: '', timestamp: '10:00', sentAt: '2026-10-20T10:00:00Z', type: 'text', text: 'Привіт', isSelf: false, status: 'read' }], lastAt: '2026-10-20T10:00:00Z', lastSelfAt: '2026-10-20T10:00:00Z' }) as unknown as Chat;

const use = (active: 'p_personal' | 'p_work') => {
  useProfiles.setState({
    activeId: active,
    profiles: [
      { ...templateDefaults('personal', 1), id: 'p_personal', name: 'Особисте' },
      { ...templateDefaults('work', 2), id: 'p_work', name: 'Робота' },
    ],
  });
  useChatProfiles.getState().assign('dm_taras', 'p_work');
};

const show = () =>
  render(
    <PhoneChatList
      chats={[chat('dm_taras', 'Тарас', 'work'), chat('dm_mama', 'Мама', 'close')]}
      currentUser={USER}
      onSelectChat={noop}
      onNewChat={noop}
      onOpenChatActions={noop}
    />,
  );

const row = (id: string) => document.querySelector(`[data-chat-row="${id}"]`);
const others = () => document.querySelector('[data-folder-row="others"]') as HTMLElement | null;

describe('chat list = the active profile\'s own chats', () => {
  it('Work shows its own chats and folds the rest into Other chats', () => {
    use('p_work');
    show();
    expect(row('dm_taras')).not.toBeNull();
    expect(row('dm_mama')).toBeNull();
    expect(others()).not.toBeNull();
    expect(others()!.textContent).toContain('Мама');
    fireEvent.click(others()!);
    expect(row('dm_mama')).not.toBeNull();
  });

  it('a work chat is absent from Особисте', () => {
    use('p_personal');
    show();
    expect(row('dm_mama')).not.toBeNull();
    expect(row('dm_taras')).toBeNull();
    expect(others()!.textContent).toContain('Тарас');
  });

  it('a work-circle chat nobody moved stays in Особисте, not in Work', () => {
    use('p_work');
    useChatProfiles.setState({ map: {} });
    show();
    expect(row('dm_taras')).toBeNull();
    use('p_personal');
    useChatProfiles.setState({ map: {} });
    cleanup();
    show();
    expect(row('dm_taras')).not.toBeNull();
    expect(others()).toBeNull();
  });
});
