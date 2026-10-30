/**
 * Вікно правки 48 год (як `PeerRevise.EDIT_WINDOW_MS`): пізнішої правки
 * отримувач не прийме, тож «Редагувати» не пропонується. «Збережене» — без вікна.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { MessageActionSheet, type MessageSheetHandlers } from '../components/messenger/feed/MessageActionSheet';
import { SAVED_NOTES_ID } from '../services/messengerApi';
import { EDIT_WINDOW_MS, canStillEdit } from '../services/editWindow';
import type { Chat, Message } from '../types/messenger';

vi.mock('../services/livingPulse', () => ({
  listenLiving: () => () => {},
  useLiving: <T,>(f: (s: { pulse: null }) => T) => f({ pulse: null }),
}));

afterEach(cleanup);

const now = Date.parse('2026-10-20T12:00:00Z');
const mine = (sentAt: string): Message => ({
  id: 'm1', senderId: 'me', senderName: 'Я', senderAvatar: '', timestamp: '12:00', sentAt, type: 'text', text: 'о сьомій', isSelf: true,
});
const chat = (id: string): Chat => ({ id, title: 'Оля', avatar: '', type: 'dm', circle: 'friends', unreadCount: 0, messages: [] });
const on = new Proxy({}, { get: () => vi.fn() }) as MessageSheetHandlers;

const sheet = (m: Message, c: Chat) =>
  render(<MessageActionSheet msg={m} currentChat={c} currentUserId="me" speaking={false} onClose={vi.fn()} on={on} />);

describe('вікно правки 48 год', () => {
  it('межа рівно 48 год ще можна, на мить пізніше — ні; без моменту відправки — можна', () => {
    expect(canStillEdit(mine(new Date(now - EDIT_WINDOW_MS).toISOString()), false, now)).toBe(true);
    expect(canStillEdit(mine(new Date(now - EDIT_WINDOW_MS - 1000).toISOString()), false, now)).toBe(false);
    expect(canStillEdit({ ...mine(''), sentAt: undefined }, false, now)).toBe(true);
    expect(canStillEdit(mine('2020-01-01T00:00:00Z'), true, now)).toBe(true);
  });

  it('старий свій лист — без «Редагувати», свіжий і в «Збереженому» — з ним', () => {
    vi.useFakeTimers({ now, toFake: ['Date'] });
    try {
      sheet(mine('2026-10-17T12:00:00Z'), chat('peer_a'));
      expect(screen.queryByRole('button', { name: 'Редагувати' })).toBeNull();
      cleanup();
      sheet(mine('2026-10-20T11:00:00Z'), chat('peer_a'));
      expect(screen.getByRole('button', { name: 'Редагувати' })).toBeTruthy();
      cleanup();
      sheet(mine('2026-10-17T12:00:00Z'), chat(SAVED_NOTES_ID));
      expect(screen.getByRole('button', { name: 'Редагувати' })).toBeTruthy();
    } finally {
      vi.useRealTimers();
    }
  });

  it('своє фото без підпису — «Редагувати» є (додати підпис), голосове — ні', () => {
    const fresh = '2026-10-20T11:00:00Z';
    const media = { url: '', mime: 'image/jpeg' };
    vi.useFakeTimers({ now, toFake: ['Date'] });
    try {
      sheet({ ...mine(fresh), type: 'image', text: undefined, media } as unknown as Message, chat('peer_a'));
      expect(screen.getByRole('button', { name: 'Редагувати' })).toBeTruthy();
      cleanup();
      sheet({ ...mine(fresh), type: 'voice', text: 'розшифровка', media } as unknown as Message, chat('peer_a'));
      expect(screen.queryByRole('button', { name: 'Редагувати' })).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });
});
