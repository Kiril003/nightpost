/**
 * Склад гілки має ТРИ стани, і кожен виглядає інакше.
 *
 * До 16.09 їх було два на три потрібні: `loadGroupMembers` ковтав відмову
 * мосту мовчки й повертав `void`, тож «ще питаємо» і «міст відмовив»
 * малювались одним написом «Склад ще не питали». Напис завжди читався як
 * «зачекайте», і людина чекала того, що вже не прийде.
 *
 * Червоніти вміє: поверніть `Promise<void>` замість `Promise<boolean>` (або
 * `return` замість `return false` у відмові) — падає тест про червоне
 * «міст не віддав складу», бо шар знову не зможе відрізнити відмову від
 * очікування.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { GroupProfileSheet } from '../components/messenger/GroupProfileSheet';
import { messengerApi } from '../services/messengerApi';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat } from '../types/messenger';

const chat = (over: Partial<Chat> = {}): Chat => ({
  id: 'g1',
  title: 'Двір',
  avatar: '',
  type: 'group',
  circle: 'all',
  unreadCount: 0,
  messages: [],
  ...over,
});

const open = (c: Chat) =>
  render(
    <GroupProfileSheet chat={c} onClose={() => {}} onSelectMember={() => {}} onTogglePinChat={() => {}} />,
  );

afterEach(() => {
  vi.restoreAllMocks();
  useMessengerStore.setState({ chats: [] });
});

describe('три стани складу', () => {
  it('поки міст не відповів — обертач, а не «нікого»', () => {
    // Проміс, який ніколи не осідає: це і є «зараз питаємо».
    vi.spyOn(messengerApi, 'groupMembers').mockReturnValue(new Promise(() => {}) as never);
    open(chat());
    expect(screen.getByText(/Завантажую учасників/)).toBeTruthy();
    expect(screen.queryByText('У складі нікого.')).toBeNull();
  });

  it('міст відмовив — «не вдалося завантажити», а не тихе «нікого»', async () => {
    vi.spyOn(messengerApi, 'groupMembers').mockRejectedValue(new Error('міст мовчить'));
    open(chat());
    await waitFor(() => expect(screen.getByText(/Учасників не завантажено/)).toBeTruthy());
    expect(screen.queryByText('У складі нікого.')).toBeNull();
  });

  it('старий міст віддав `{}` без списку — «не вдалося завантажити», а не падіння екрана', async () => {
    vi.spyOn(messengerApi, 'groupMembers').mockResolvedValue({} as never);
    open(chat());
    await waitFor(() => expect(screen.getByText(/Учасників не завантажено/)).toBeTruthy());
  });

  it('міст відповів порожнім складом — саме «нікого», без червоного', async () => {
    vi.spyOn(messengerApi, 'groupMembers').mockResolvedValue({
      id: 'g1',
      title: 'Двір',
      members: [],
      addable: [],
      max_members: 32,
      authoritative: false,
      standing: 'in',
      my_role: 'owner',
      owner_id: 'me',
      powers: {
        add: true, remove: true, rename: true, card: true,
        grant: true, transfer: true, pin: true,
      },
    } as never);
    useMessengerStore.setState({ chats: [chat({ members: [] })] });
    open(chat({ members: [] }));
    await waitFor(() => expect(screen.getByText('У складі нікого.')).toBeTruthy());
    expect(screen.queryByText(/Учасників не завантажено/)).toBeNull();
    expect(screen.queryByText(/Завантажую учасників/)).toBeNull();
  });
});

describe('учасник групи — і в твоєму колі', () => {
  it('рядок учасника каже, в якому колі ця людина в тебе', () => {
    vi.spyOn(messengerApi, 'groupMembers').mockReturnValue(new Promise(() => {}) as never);
    const member = { id: 'p1', name: 'Марта', handle: '', avatar: '', role: 'member' } as never;
    const stranger = { id: 'p2', name: 'Олег', handle: '', avatar: '', role: 'member' } as never;
    const g = chat({ members: [member, stranger] });
    useMessengerStore.setState({ chats: [g, chat({ id: 'd1', type: 'dm', peerNodeId: 'p1', circle: 'work', title: 'Марта' })] });
    open(g);
    expect(screen.getByText(/також у «Робота»/)).toBeTruthy();
    expect(screen.getAllByText(/також у «/)).toHaveLength(1);
  });
});
