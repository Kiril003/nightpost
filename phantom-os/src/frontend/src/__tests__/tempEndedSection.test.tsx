import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render } from '@testing-library/react';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { loadProfiles, useProfiles } from '../stores/profilesStore';
import { useChatProfiles } from '../stores/chatProfileStore';
import { useMessengerStore } from '../stores/messengerStore';
import { chatArchive } from '../services/chatArchive';
import { installTempProfileEnd } from '../services/tempProfileEnd';
import type { Chat, UserProfile } from '../types/messenger';

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;
const chat = (id: string, title: string): Chat =>
  ({
    id, title, type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false,
    messages: [], lastSnippet: 'привіт', lastAt: new Date().toISOString(),
  }) as unknown as Chat;

beforeEach(() => {
  cleanup();
  localStorage.clear();
  chatArchive.reset();
  useChatProfiles.setState({ map: {}, migratedAt: null });
  useMessengerStore.setState({ chats: [] });
});

describe('«На час»: архів після кінця', () => {
  it('розмови скінченого профілю стоять в архіві під його назвою, решта архіву — окремо', () => {
    useProfiles.setState(loadProfiles(false));
    const party = useProfiles.getState().addFromTemplate('temp', 'Вечірка');
    const expiresAt = Date.now() + 36e5;
    useProfiles.getState().update(party.id, { expiresAt });
    useChatProfiles.getState().assign('c1', party.id);
    useMessengerStore.setState({
      chats: [chat('c1', 'Оксана'), { ...chat('c2', 'Двір'), archived: true }, chat('c3', 'Мама')],
    });

    const stop = installTempProfileEnd();
    act(() => useProfiles.getState().sweepExpired(expiresAt + 1));
    stop();

    render(
      <PhoneChatList
        chats={useMessengerStore.getState().chats}
        currentUser={USER}
        onSelectChat={vi.fn()}
        onNewChat={vi.fn()}
        onOpenChatActions={vi.fn()}
      />,
    );
    fireEvent.click(document.querySelector('[data-folder-row="archive"]') as HTMLElement);

    const ended = document.querySelector(`[data-list-section="ended-${party.id}"]`) as HTMLElement | null;
    expect(ended).not.toBeNull();
    expect(ended!.querySelector('h2')).toHaveTextContent('Вечірка · завершено');
    expect(ended).toHaveTextContent('Оксана');
    expect(ended).not.toHaveTextContent('Двір');
    const rest = document.querySelector('[data-list-section="archive"]') as HTMLElement | null;
    expect(rest).toHaveTextContent('Двір');
    expect(rest).not.toHaveTextContent('Оксана');
  });
});
