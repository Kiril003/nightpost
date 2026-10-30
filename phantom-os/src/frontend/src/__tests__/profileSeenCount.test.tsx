import { beforeEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { ProfilesHost } from '../components/messenger/ProfilesSheet';
import { loadProfiles, useProfiles } from '../stores/profilesStore';
import { useChatProfiles } from '../stores/chatProfileStore';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat } from '../types/messenger';

const click = (id: string) => fireEvent.click(screen.getByTestId(id));
const dm = (id: string, peer: string) =>
  ({ id, title: peer, type: 'dm', circle: 'all', avatar: '', unreadCount: 0, messages: [], peerNodeId: peer }) as unknown as Chat;
const group = (id: string, members: string[]) =>
  ({
    id,
    title: id,
    type: 'group',
    circle: 'all',
    avatar: '',
    unreadCount: 0,
    messages: [],
    members: members.map((m) => ({ id: m, name: m })),
  }) as unknown as Chat;

const seen = (profileId: string) => {
  cleanup();
  render(<ProfilesHost />);
  click('me-profiles-open');
  click(`profile-settings-${profileId}`);
  return screen.getByTestId('profile-seen-count').textContent;
};

let work = '';
let trip = '';

beforeEach(() => {
  cleanup();
  localStorage.clear();
  useProfiles.setState(loadProfiles());
  work = useProfiles.getState().addFromTemplate('work').id;
  trip = useProfiles.getState().addFromTemplate('trip').id;
  useMessengerStore.setState({
    currentUser: { ...useMessengerStore.getState().currentUser, id: 'me' },
    chats: [
      dm('c_oleh', 'oleh'),
      dm('c_iryna', 'iryna'),
      dm('c_mama', 'mama'),
      group('g_team', ['me', 'oleh', 'taras']),
      { ...dm('chat_saved_notes', 'me'), type: 'saved' } as unknown as Chat,
    ],
  } as never);
  useChatProfiles.setState({
    map: { c_oleh: work, c_iryna: work, g_team: work, chat_saved_notes: work },
  });
});

describe('«Бачать N» у кожного профілю своє', () => {
  it('«Робота» рахує людей своїх розмов і груп, без мене й «Збереженого»', () => {
    expect(seen(work)).toBe('Бачать 3 людини');
  });

  it('«Особисте» — одна людина в однині', () => {
    expect(seen(useProfiles.getState().profiles[0].id)).toBe('Бачить 1 людина');
  });

  it('профіль без розмов — чесне «Ще ніхто»', () => {
    expect(seen(trip)).toBe('Ще ніхто');
  });
});
