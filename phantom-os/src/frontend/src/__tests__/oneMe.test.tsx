/**
 * Одне «Я» (аудит 28.10 №6): на телефоні веб-екран акаунта не малюється — аркуш
 * передає в рідну вкладку «Я». На ПК — без дублів: плитка
 * «Пристрої» не гортає до розділу під собою, «Мої люди» — один вхід.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { messengerApi } from '../services/messengerApi';
import { phantomSelfApi } from '../services/phantomSelfApi';
import { useMessengerStore } from '../stores/messengerStore';
import { PhoneSettingsSheet } from '../components/messenger/PhoneSettingsSheet';
import { Header } from '../components/messenger/Header';
import type { Chat } from '../types/messenger';

type Bridge = { call: () => string; navigate?: (to: string, args?: string) => boolean };
const setBridge = (b: Bridge | undefined) => {
  if (b) (window as unknown as { PhantomBridge?: Bridge }).PhantomBridge = b;
  else delete (window as unknown as { PhantomBridge?: Bridge }).PhantomBridge;
};

let prev: ReturnType<typeof useMessengerStore.getState>;
beforeEach(() => {
  prev = useMessengerStore.getState();
  vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'listBlocked').mockResolvedValue({ blocked: [] });
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'privacy').mockResolvedValue({ read_receipts: true });
  vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
  useMessengerStore.setState({ currentUser: { ...prev.currentUser, name: 'Кирило', avatar: '' }, chats: [] });
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  setBridge(undefined);
  useMessengerStore.setState(prev, true);
});

const sheet = (onClose = vi.fn()) =>
  render(<PhoneSettingsSheet isOpen onClose={onClose} isSoundEnabled onToggleSound={() => {}} onExportAllData={() => {}} />);

describe('телефон: «Я» одне — рідна вкладка', () => {
  it('⋮ у розмові на телефоні — меню розмови без входу у веб-«Я»', () => {
    const navigate = vi.fn(() => true);
    setBridge({ call: () => '{}', navigate });
    const chat = { id: 'c1', title: 'Оксана', type: 'dm', peerNodeId: 'p1', circle: 'all', avatar: '', unreadCount: 0, pinned: false, messages: [] } as unknown as Chat;
    useMessengerStore.setState({ chats: [chat] } as never);
    const onOpenSettings = vi.fn();
    render(
      <MemoryRouter>
        <Header
          currentChat={chat}
          currentUser={{ id: 'me', name: 'Кирило', avatar: '' } as never}
          onOpenSettings={onOpenSettings} onOpenGroupDetails={() => ''} isSoundEnabled onToggleSound={() => {}}
          onToggleSearch={() => {}} isSearching={false} onBack={() => {}}
        />
      </MemoryRouter>,
    );
    fireEvent.click(screen.getByLabelText('Ще'));
    expect(screen.queryByText('Налаштування')).toBeNull();
    expect(onOpenSettings).not.toHaveBeenCalled();
  });

  it('аркуш акаунта на телефоні не малює веб-«Я», а передає в рідну вкладку', () => {
    const navigate = vi.fn(() => true);
    setBridge({ call: () => '{}', navigate });
    const onClose = vi.fn();
    sheet(onClose);
    expect(screen.queryByTestId('me-tiles')).toBeNull();
    expect(navigate).toHaveBeenCalledWith('profile', '{}');
    expect(onClose).toHaveBeenCalled();
  });
});

describe('ПК: «Я» без дублів', () => {
  it('плитки — Вигляд, Приватність, Мої люди; «Пристроїв»-гортання й рядка «Люди» немає', () => {
    sheet();
    const tiles = [...screen.getByTestId('me-tiles').querySelectorAll('[data-testid^="me-tile-"]')].map((e) => e.getAttribute('data-testid'));
    expect(tiles).toEqual(['me-tile-look', 'me-tile-privacy', 'me-tile-people']);
    expect(screen.queryByTestId('me-people')).toBeNull();
    expect(screen.getAllByText('Налаштування')).toHaveLength(1);
  });

  it('«Мої люди» відкриває людей — один вхід замість двох', () => {
    const onClose = vi.fn();
    sheet(onClose);
    fireEvent.click(screen.getByTestId('me-tile-people'));
    expect(screen.getByTestId('people-screen')).toBeTruthy();
    expect(onClose).not.toHaveBeenCalled();
  });
});
