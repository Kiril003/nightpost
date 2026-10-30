/**
 * S5: довгий дотик › «Перенести в профіль» — розмова отримує нового власника,
 * міст бачить нову мапу, а простір читає журнал профілю-власника, не активного.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, renderHook, screen, within } from '@testing-library/react';
import { create } from 'zustand';
import { ChatActionsSheet } from '../components/messenger/ChatActionsSheet';
import { loadChatProfiles, startChatProfiles, useChatProfiles } from '../stores/chatProfileStore';
import { templateDefaults, useProfiles, type Profile, type ProfileKind } from '../stores/profilesStore';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat } from '../types/messenger';

vi.mock('../services/spaces/spaceWire', () => ({ pullSpace: vi.fn(async () => undefined) }));
import { pullSpace } from '../services/spaces/spaceWire';
import { useSpace } from '../components/messenger/spaces/useSpace';
import { appendSpaceEvents } from '../services/spaces/spaceVault';

const P = (id: string, name: string, kind: ProfileKind): Profile => ({ ...templateDefaults(kind, 1), id, name });
const personal = P('personal', 'Особисте', 'personal');
const work = P('work', 'Робота', 'work');

const chat = (id: string, extra: Partial<Chat> = {}): Chat =>
  ({ id, title: id, type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false, messages: [], lastAt: '2026-10-27T10:00:00Z', ...extra }) as unknown as Chat;

const sheet = (c: Chat, onClose = vi.fn()) =>
  render(<ChatActionsSheet chat={c} onClose={onClose} onMarkRead={vi.fn()} onRename={vi.fn()} onClear={vi.fn()} onDelete={vi.fn()} />);

const initial = useProfiles.getState();
let prevStore: ReturnType<typeof useMessengerStore.getState>;

beforeEach(() => {
  localStorage.clear();
  useChatProfiles.setState(loadChatProfiles());
  useProfiles.setState({ activeId: 'personal', profiles: [personal, work], previousId: null });
  prevStore = useMessengerStore.getState();
});

afterEach(() => {
  cleanup();
  localStorage.clear();
  useProfiles.setState({ activeId: initial.activeId, profiles: initial.profiles, previousId: null });
  useMessengerStore.setState(prevStore, true);
  delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
});

describe('«Перенести в профіль» у меню розмови', () => {
  it('є лише коли профілів більше одного і розмова має власника', () => {
    sheet(chat('c1'));
    expect(screen.getByText('Перенести в профіль')).toBeTruthy();
    cleanup();
    sheet(chat('saved', { type: 'saved' } as Partial<Chat>));
    expect(screen.queryByText('Перенести в профіль')).toBeNull();
    cleanup();
    useProfiles.setState({ profiles: [personal] });
    sheet(chat('c1'));
    expect(screen.queryByText('Перенести в профіль')).toBeNull();
  });

  it('аркуш показує профілі з реальним ліком, поточний позначено; дотик переносить і закриває', () => {
    useChatProfiles.setState({ map: { c1: 'personal', c2: 'personal', c3: 'work' }, migratedAt: 1 });
    useMessengerStore.setState({ chats: [chat('c1'), chat('c2'), chat('c3')] } as never);
    const onClose = vi.fn();
    sheet(chat('c1'), onClose);
    fireEvent.click(screen.getByText('Перенести в профіль'));
    const list = screen.getByTestId('move-to-profile');
    const mine = within(list).getByTestId('move-to-personal');
    expect(within(mine).getByText('2 розмови')).toBeTruthy();
    expect(within(mine).getByLabelText('обрано')).toBeTruthy();
    expect(within(within(list).getByTestId('move-to-work')).getByText('1 розмова')).toBeTruthy();
    fireEvent.click(within(within(list).getByTestId('move-to-work')).getByRole('button'));
    expect(useChatProfiles.getState().map.c1).toBe('work');
    expect(onClose).toHaveBeenCalled();
  });

  it('після переносу міст отримує нового власника розмови', () => {
    const setProfiles = vi.fn();
    (globalThis as { PhantomBridge?: unknown }).PhantomBridge = { setProfiles };
    const src = create(() => ({ chats: [chat('c1', { peerNodeId: 'node-c1' } as Partial<Chat>)], hydrated: true, currentUser: { id: 'me' } }));
    useChatProfiles.setState({ map: { c1: 'personal' }, migratedAt: 1 });
    const stop = startChatProfiles(src);
    try {
      sheet(src.getState().chats[0]);
      fireEvent.click(screen.getByText('Перенести в профіль'));
      fireEvent.click(within(screen.getByTestId('move-to-work')).getByRole('button'));
      const last = JSON.parse(setProfiles.mock.calls.at(-1)![0] as string);
      expect(last.peerProfile['node-c1']).toBe('work');
    } finally {
      stop();
    }
  });
});

describe('простір читає журнал профілю-власника', () => {
  it('ключ простору — власник розмови, не активний профіль', () => {
    useChatProfiles.setState({ map: { sp1: 'work' }, migratedAt: 1 });
    useMessengerStore.setState({ chats: [chat('sp1', { type: 'group', members: [{ id: 'me', isMe: true }], messages: [{ id: 'm1', sentAt: '2026-10-27T10:00:00Z' }] } as Partial<Chat>)] } as never);
    const { result } = renderHook(() => useSpace('sp1'));
    expect(result.current.profileId).toBe('work');
    expect(pullSpace).toHaveBeenCalledWith('work', 'sp1');
  });

  const seed = (profileId: string, spaceId: string, name: string) =>
    appendSpaceEvents(profileId, [{ id: `${spaceId}-e1`, spaceId, actor: 'me', lamport: 1, at: 1000, body: { t: 'space.create', name, icon: null, template: 'club' } }]);
  const spaceChat = (id: string) =>
    useMessengerStore.setState({ chats: [chat(id, { type: 'group', members: [{ id: 'me', isMe: true }], messages: [{ id: 'm1', sentAt: '2026-10-27T10:00:00Z' }] } as Partial<Chat>)] } as never);

  it('простір, створений у профілі A, показує свої записи, коли власник — A', () => {
    seed('work', 'spA', 'Склад');
    useChatProfiles.setState({ map: { spA: 'work' }, migratedAt: 1 });
    spaceChat('spA');
    const { result } = renderHook(() => useSpace('spA'));
    expect(result.current.profileId).toBe('work');
    expect(result.current.space?.name).toBe('Склад');
  });

  it('старий простір, записаний в іншому профілі, не порожніє: читаємо сховище, де він лежить', () => {
    seed('work', 'spOld', 'Бригада');
    useChatProfiles.setState({ map: {}, migratedAt: 1 });
    spaceChat('spOld');
    const { result } = renderHook(() => useSpace('spOld'));
    expect(result.current.profileId).toBe('work');
    expect(result.current.space?.name).toBe('Бригада');
  });

  it('міграція віддала простір «Особистому», а журнал лежить у «Роботі» — показуємо журнал', () => {
    seed('work', 'spMig', 'Зміна');
    useChatProfiles.setState({ map: { spMig: 'personal' }, migratedAt: 1 });
    spaceChat('spMig');
    const { result } = renderHook(() => useSpace('spMig'));
    expect(result.current.space?.name).toBe('Зміна');
  });
});
