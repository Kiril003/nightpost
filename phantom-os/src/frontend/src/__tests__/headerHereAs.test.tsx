import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../services/messengerApi', async (orig) => {
  const real = await orig<typeof import('../services/messengerApi')>();
  return {
    ...real,
    messengerApi: {
      ...real.messengerApi,
      pulse: vi.fn(() => new Promise(() => {})),
      peerProfiles: vi.fn(() => new Promise(() => {})),
    },
  };
});

import { Header } from '../components/messenger/Header';
import { useMessengerStore } from '../stores/messengerStore';
import { loadProfiles, useProfiles } from '../stores/profilesStore';
import { useChatProfiles } from '../stores/chatProfileStore';
import type { Chat, UserProfile } from '../types/messenger';

const ME = { id: 'me', name: 'Max', avatar: '' } as unknown as UserProfile;
const chat = (id: string): Chat =>
  ({ id, title: 'Ірина', type: 'dm', circle: 'all', avatar: '', unreadCount: 0, messages: [], peerNodeId: 'c1' }) as unknown as Chat;

const draw = (c: Chat) =>
  render(
    <MemoryRouter>
      <Header
        currentChat={c}
        currentUser={ME}
        onOpenSettings={() => {}}
        onOpenGroupDetails={() => {}}
        isSoundEnabled
        onToggleSound={() => {}}
        onToggleSearch={() => {}}
        isSearching={false}
        onBack={() => {}}
      />
    </MemoryRouter>,
  );

let work = '';

beforeEach(() => {
  localStorage.clear();
  useProfiles.setState(loadProfiles());
  work = useProfiles.getState().addFromTemplate('work').id;
  useProfiles.getState().setActive(useProfiles.getState().profiles[0].id);
  useChatProfiles.setState({ map: { peer_w: work } });
  useMessengerStore.setState({ typingStatus: {} } as never);
});

afterEach(() => cleanup());

describe('шапка розмови з іншого профілю', () => {
  it('каже «Ви тут як «Робота»», поки активне «Особисте»', () => {
    draw({ ...chat('peer_w'), circle: 'work' } as Chat);
    expect(screen.getByTestId('header-here-as').textContent).toBe('Ви тут як «Робота»');
    expect(screen.queryByTestId('header-circle')).toBeNull();
  });

  it('розмова активного профілю — без цього рядка', () => {
    useProfiles.getState().setActive(work);
    draw(chat('peer_w'));
    expect(screen.queryByTestId('header-here-as')).toBeNull();
  });

  it('«Збережене» не належить жодному профілю — рядка немає', () => {
    useChatProfiles.setState({ map: { chat_saved_notes: work } });
    draw(chat('chat_saved_notes'));
    expect(screen.queryByTestId('header-here-as')).toBeNull();
  });
});
