import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { loadProfiles, useProfiles } from '../stores/profilesStore';
import { useChatProfiles } from '../stores/chatProfileStore';
import { useMessengerStore } from '../stores/messengerStore';
import { chatArchive } from '../services/chatArchive';
import { installTempProfileEnd } from '../services/tempProfileEnd';
import type { Chat, UserProfile } from '../types/messenger';

const HOUR = 36e5;
const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;
const chat = (id: string, title: string): Chat =>
  ({
    id, title, type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false,
    messages: [], lastSnippet: 'привіт', lastAt: new Date().toISOString(),
  }) as unknown as Chat;

function tempEndingIn(ms: number) {
  useProfiles.setState(loadProfiles(false));
  const t = useProfiles.getState().addFromTemplate('temp', 'Концерт');
  const expiresAt = Date.now() + ms;
  useProfiles.getState().update(t.id, { expiresAt });
  return { id: t.id, expiresAt };
}

const renderList = () =>
  render(
    <PhoneChatList
      chats={[chat('c1', 'Оксана')]}
      currentUser={USER}
      onSelectChat={vi.fn()}
      onNewChat={vi.fn()}
      onOpenChatActions={vi.fn()}
    />,
  );

beforeEach(() => {
  cleanup();
  localStorage.clear();
  chatArchive.reset();
  useChatProfiles.setState({ map: {}, migratedAt: null });
  useMessengerStore.setState({ chats: [] });
});

describe('«На час»: година до кінця', () => {
  it('за годину до кінця список показує одну дію «Продовжити 24 год», і вона продовжує на добу', () => {
    const { id, expiresAt } = tempEndingIn(30 * 60e3);
    renderList();
    const notice = screen.getByTestId('temp-ending');
    expect(notice).toHaveTextContent('Концерт');
    const extend = screen.getByTestId('temp-extend');
    expect(extend).toHaveTextContent('Продовжити 24 год');
    expect(notice.querySelectorAll('button')).toHaveLength(1);
    fireEvent.click(extend);
    expect(useProfiles.getState().profiles.find((p) => p.id === id)?.expiresAt).toBe(expiresAt + 24 * HOUR);
    expect(screen.queryByTestId('temp-ending')).toBeNull();
  });

  it('раніше ніж за годину — тиша', () => {
    tempEndingIn(3 * HOUR);
    renderList();
    expect(screen.queryByTestId('temp-ending')).toBeNull();
  });
});

describe('«На час»: кінець', () => {
  it('розмови профілю йдуть в архів, а не лишаються в «Особистому»', () => {
    const { id, expiresAt } = tempEndingIn(HOUR);
    useChatProfiles.getState().assign('c1', id);
    useMessengerStore.setState({ chats: [chat('c1', 'Оксана'), chat('c2', 'Двір')] });

    const stop = installTempProfileEnd();
    act(() => useProfiles.getState().sweepExpired(expiresAt + 1));
    stop();

    expect(useProfiles.getState().profiles.some((p) => p.id === id)).toBe(false);
    expect(chatArchive.has('c1')).toBe(true);
    expect(chatArchive.has('c2')).toBe(false);
    const byId = Object.fromEntries(useMessengerStore.getState().chats.map((c) => [c.id, c.archived]));
    expect(byId).toEqual({ c1: true, c2: undefined });
  });

  it('кінець, що настав до старту месенджера, архівує розмови, щойно він стартує', () => {
    const { id, expiresAt } = tempEndingIn(HOUR);
    useChatProfiles.getState().assign('c1', id);
    useProfiles.getState().sweepExpired(expiresAt + 1);
    expect(chatArchive.has('c1')).toBe(false);

    const stop = installTempProfileEnd();
    stop();
    expect(chatArchive.has('c1')).toBe(true);
  });
});
