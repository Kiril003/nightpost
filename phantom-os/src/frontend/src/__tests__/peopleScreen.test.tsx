import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import * as circlesApi from '../services/circlesApi';
import { messengerApi } from '../services/messengerApi';
import { phantomSelfApi } from '../services/phantomSelfApi';
import { deviceApi } from '../services/deviceApi';
import { chatHush } from '../services/chatHush';
import { DEFAULT_CIRCLES, useCirclesStore } from '../stores/circlesStore';
import { useMessengerStore } from '../stores/messengerStore';
import { PhoneSettingsSheet } from '../components/messenger/PhoneSettingsSheet';
import { PeopleScreen } from '../components/messenger/PeopleScreen';
import type { Chat } from '../types/messenger';

const NOW = Date.UTC(2026, 9, 24, 14, 0, 0);

const dm = (id: string, title: string, circles: string[] = []): Chat =>
  ({
    id: `c_${id}`,
    title,
    type: 'dm',
    peerNodeId: id,
    messages: [],
    unreadCount: 0,
    circle: (circles[0] ?? 'all') as Chat['circle'],
    circles,
  }) as unknown as Chat;

let prevState: ReturnType<typeof useMessengerStore.getState>;

beforeEach(() => {
  prevState = useMessengerStore.getState();
  vi.spyOn(Date, 'now').mockReturnValue(NOW);
  chatHush.reset();
  (globalThis as { PhantomBridge?: unknown }).PhantomBridge = { call: () => '{}' };
  vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'listBlocked').mockResolvedValue({ blocked: [] });
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'privacy').mockResolvedValue({ read_receipts: true });
  vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
  vi.spyOn(deviceApi, 'list').mockResolvedValue({ stopped: false, devices: [] });
  vi.spyOn(circlesApi, 'listCirclesWithMeta').mockResolvedValue({ circles: DEFAULT_CIRCLES, maxCustom: 8 });
  useCirclesStore.setState({ circles: DEFAULT_CIRCLES, maxCustom: 8 });
  useMessengerStore.setState({
    currentUser: { ...prevState.currentUser, name: 'Кирило', status: '', bio: '', avatar: '' },
    chats: [
      dm('sofia', 'Софія', ['friends']),
      dm('taras', 'Тарас', ['friends']),
      dm('olena', 'Олена', ['close']),
    ],
  });
});

afterEach(() => {
  cleanup();
  chatHush.reset();
  vi.restoreAllMocks();
  delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
  useMessengerStore.setState(prevState, true);
});

describe('Профіль › Люди (W1 people management)', () => {
  it('(a) MeScreen «Мої люди» tile opens PeopleScreen (reachability)', async () => {
    render(
      <PhoneSettingsSheet
        isOpen
        onClose={() => {}}
        isSoundEnabled
        onToggleSound={() => {}}
        onExportAllData={() => {}}
      />,
    );

    const row = screen.getByTestId('me-tile-people');
    expect(row.textContent).toContain('Мої люди');
    fireEvent.click(row);

    const people = await screen.findByTestId('people-screen');
    expect(within(people).getByText('Люди')).toBeTruthy();
    expect(within(people).getByText('Кола')).toBeTruthy();
    expect(within(people).getByText('Друзі')).toBeTruthy();
    expect(within(people).getByText('Запити')).toBeTruthy();
    expect(within(people).getByText('Заблоковані')).toBeTruthy();
  });

  it('(a2) circle rows show the faces of their people, an empty circle shows none', async () => {
    render(<PeopleScreen onClose={() => {}} />);
    const home = await screen.findByTestId('people-home');
    const friends = within(home).getByTestId('circle-faces-friends');
    expect(friends.querySelectorAll('[data-face]').length).toBe(2);
    expect(friends.textContent).toContain('С');
    expect(within(home).getByTestId('circle-faces-close').querySelectorAll('[data-face]').length).toBe(1);
    expect(within(home).queryByTestId('circle-faces-work')).toBeNull();
    expect(within(home).getByTestId('circle-count-work').className).toContain('text-ink-3');
  });

  it('(b) circle detail lists members from the store, Прибрати removes calling circles api, and undo restores', async () => {
    const leaveSpy = vi.spyOn(circlesApi, 'leaveCircle').mockResolvedValue([]);
    const joinSpy = vi.spyOn(circlesApi, 'joinCircle').mockResolvedValue(['friends']);

    render(<PeopleScreen onClose={() => {}} />);

    fireEvent.click(screen.getByText('Друзі'));

    const detail = await screen.findByTestId('circle-detail');
    expect(within(detail).getByText('Учасники · 2')).toBeTruthy();
    expect(within(detail).getByText('Софія')).toBeTruthy();
    expect(within(detail).getByText('Тарас')).toBeTruthy();

    fireEvent.click(within(detail).getByText('Софія'));

    const sheet = await screen.findByTestId('person-actions-sheet');
    fireEvent.click(within(sheet).getByText('Прибрати з кола'));

    await waitFor(() => expect(leaveSpy).toHaveBeenCalledWith('friends', 'c_sofia'));
    await waitFor(() => expect(within(detail).queryByText('Софія')).toBeNull());
    expect(within(detail).getByText('Учасники · 1')).toBeTruthy();

    expect(screen.getByText('Прибрано · Софія')).toBeTruthy();
    fireEvent.click(screen.getByText('Скасувати'));

    await waitFor(() => expect(joinSpy).toHaveBeenCalledWith('friends', 'c_sofia'));
    await waitFor(() => expect(within(detail).getByText('Софія')).toBeTruthy());
    expect(within(detail).getByText('Учасники · 2')).toBeTruthy();
  });

  it('(c) mute duration sets and expires (chatHush unit + MuteSheet UI)', async () => {
    const nowSpy = vi.spyOn(Date, 'now').mockReturnValue(NOW);

    chatHush.setFor('c_sofia', NOW + 3_600_000);
    expect(chatHush.has('c_sofia')).toBe(true);

    nowSpy.mockReturnValue(NOW + 3_600_000 - 1);
    expect(chatHush.has('c_sofia')).toBe(true);

    nowSpy.mockReturnValue(NOW + 3_600_000);
    expect(chatHush.has('c_sofia')).toBe(false);

    chatHush.setFor('c_taras', null);
    nowSpy.mockReturnValue(NOW + 365 * 86_400_000);
    expect(chatHush.has('c_taras')).toBe(true);
    chatHush.set('c_taras', false);
    expect(chatHush.has('c_taras')).toBe(false);

    nowSpy.mockReturnValue(NOW);
    render(<PeopleScreen onClose={() => {}} />);
    fireEvent.click(screen.getByText('Друзі'));
    const detail = await screen.findByTestId('circle-detail');
    fireEvent.click(within(detail).getByText('Софія'));

    const sheet = await screen.findByTestId('person-actions-sheet');
    fireEvent.click(within(sheet).getByText('Без звуку'));
    fireEvent.click(within(sheet).getByText('8 годин'));

    expect(chatHush.has('c_sofia')).toBe(true);
    nowSpy.mockReturnValue(NOW + 8 * 3_600_000 + 1);
    expect(chatHush.has('c_sofia')).toBe(false);
  });

  it('(d) block confirm calls blockPeer only after confirm', async () => {
    const blockSpy = vi.spyOn(messengerApi, 'blockPeer').mockResolvedValue({ blocked: true });

    render(<PeopleScreen onClose={() => {}} />);
    fireEvent.click(screen.getByText('Друзі'));
    const detail = await screen.findByTestId('circle-detail');
    fireEvent.click(within(detail).getByText('Софія'));

    const sheet = await screen.findByTestId('person-actions-sheet');
    fireEvent.click(within(sheet).getByText('Заблокувати'));

    expect(blockSpy).not.toHaveBeenCalled();
    expect(within(sheet).getByText('Повідомлення й дзвінки')).toBeTruthy();
    expect(within(sheet).getByText('Не дійдуть')).toBeTruthy();
    expect(within(sheet).getByText('Профіль і Моменти')).toBeTruthy();
    expect(within(sheet).getByText('Не видно')).toBeTruthy();

    fireEvent.click(within(sheet).getByTestId('block-confirm-btn'));
    await waitFor(() => expect(blockSpy).toHaveBeenCalledWith('sofia'));
  });

  it('(e) blocked list unblock + undo', async () => {
    vi.spyOn(messengerApi, 'listBlocked').mockResolvedValue({ blocked: ['bohdan'] });
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([
      { peer_node_id: 'bohdan', display_name: 'Богдан' } as never,
    ]);
    const unblockSpy = vi.spyOn(messengerApi, 'unblockPeer').mockResolvedValue({ blocked: false });
    const blockSpy = vi.spyOn(messengerApi, 'blockPeer').mockResolvedValue({ blocked: true });

    render(<PeopleScreen onClose={() => {}} />);
    const blockedRow = await screen.findByTestId('people-blocked-row');
    await waitFor(() => expect(blockedRow.textContent).toContain('1'));
    fireEvent.click(blockedRow);

    const blockedScreen = await screen.findByTestId('blocked-screen');
    expect(await within(blockedScreen).findByText('Богдан')).toBeTruthy();

    fireEvent.click(within(blockedScreen).getByText('Розблокувати'));
    await waitFor(() => expect(unblockSpy).toHaveBeenCalledWith('bohdan'));
    await waitFor(() => expect(within(blockedScreen).queryByText('Богдан')).toBeNull());

    expect(screen.getByText('Розблоковано · Богдан')).toBeTruthy();
    fireEvent.click(screen.getByText('Скасувати'));

    await waitFor(() => expect(blockSpy).toHaveBeenCalledWith('bohdan'));
    await waitFor(() => expect(within(blockedScreen).getByText('Богдан')).toBeTruthy());
  });

  it('(f) requests screen shows empty state only and count on People row stays hidden', async () => {
    vi.spyOn(messengerApi, 'listRequests').mockResolvedValue({ incoming: [], outgoing: [] });
    render(<PeopleScreen onClose={() => {}} />);

    const reqRow = screen.getByTestId('people-requests-row');
    expect(reqRow.textContent).toBe('Запити');
    fireEvent.click(reqRow);

    const reqScreen = await screen.findByTestId('requests-screen');
    expect(within(reqScreen).getByText('Запитів немає')).toBeTruthy();
    expect(within(reqScreen).queryAllByRole('listitem')).toHaveLength(0);
  });
});
