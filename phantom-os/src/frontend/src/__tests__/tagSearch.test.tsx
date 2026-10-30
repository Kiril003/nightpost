/**
 * Тег у бульбашці — двері до пошуку; пошук за тегом — цілим словом.
 *
 * Червоніє, якщо: тег знову став простим <span> без дії; подія летить ДО
 * закриття розмови або без `#`; видача за `#світ` приносить `#світло`.
 */
import { afterEach, describe, it, expect, vi } from 'vitest';
import { act, fireEvent, render, renderHook, screen } from '@testing-library/react';
import { FormattedMessageText } from '../components/messenger/FormattedMessageText';
import { useGlobalSearch } from '../components/messenger/GlobalSearch';
import { onlyExactTag, type LetterResult } from '../services/globalSearch';
import { TAG_SEARCH_EVENT, openTagSearch } from '../services/tagSearch';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message } from '../types/messenger';

const caught: string[] = [];
const listen = (e: Event) => caught.push((e as CustomEvent<{ tag: string }>).detail.tag);

afterEach(() => {
  window.removeEventListener(TAG_SEARCH_EVENT, listen);
  caught.length = 0;
  vi.useRealTimers();
});

describe('тег у бульбашці', () => {
  it('дотик закриває розмову і на наступному такті просить список шукати #тег', () => {
    vi.useFakeTimers();
    window.addEventListener(TAG_SEARCH_EVENT, listen);
    useMessengerStore.setState({ activeChatId: 'c1' });
    render(<FormattedMessageText text="Збори #Двір_2 сьогодні" />);
    const tag = screen.getByRole('link', { name: '#Двір_2' });
    fireEvent.click(tag);
    expect(useMessengerStore.getState().activeChatId).toBe('');
    // Список ще не змонтований — подія не мусить летіти синхронно.
    expect(caught).toEqual([]);
    act(() => vi.runAllTimers());
    expect(caught).toEqual(['#двір_2']);
  });

  it('Enter на тезі — те саме, що дотик', () => {
    vi.useFakeTimers();
    window.addEventListener(TAG_SEARCH_EVENT, listen);
    render(<FormattedMessageText text="#світ" />);
    const tag = screen.getByRole('link', { name: '#світ' });
    expect(tag.getAttribute('tabindex')).toBe('0');
    fireEvent.keyDown(tag, { key: 'Enter' });
    act(() => vi.runAllTimers());
    expect(caught).toEqual(['#світ']);
  });

  it('порожній тег нікуди не веде', () => {
    vi.useFakeTimers();
    window.addEventListener(TAG_SEARCH_EVENT, listen);
    useMessengerStore.setState({ activeChatId: 'c1' });
    openTagSearch('#');
    act(() => vi.runAllTimers());
    expect(caught).toEqual([]);
    expect(useMessengerStore.getState().activeChatId).toBe('c1');
  });
});

const letter = (id: string, text: string): LetterResult => ({
  chatId: 'c1', messageId: id, author: 'Петро', where: 'Двір',
  fragment: { text, at: 0, len: 1, field: 'text' },
});

describe('пошук за тегом — цілим словом', () => {
  it('onlyExactTag: #світ лишає #світ і #Світ, відкидає #світло', () => {
    const got = onlyExactTag(
      [letter('a', 'вечір #світло'), letter('b', 'усім #Світ!'), letter('c', '#світло і #світ')],
      '#світ',
    );
    expect(got.map((l) => l.messageId)).toEqual(['b', 'c']);
  });

  it('запит не тег — видача без змін', () => {
    const all = [letter('a', 'світло'), letter('b', 'світ')];
    expect(onlyExactTag(all, 'світ')).toBe(all);
    expect(onlyExactTag(all, '#світ ще')).toBe(all);
  });

  it('useGlobalSearch: за #світ у листах немає #світло', async () => {
    const m = (id: string, text: string): Message =>
      ({ id, senderId: 'p1', senderName: 'Петро', senderAvatar: '', timestamp: '', type: 'text', text, sentAt: '2026-09-20T10:00:00Z' }) as Message;
    const chats = [
      { id: 'c1', title: 'Двір', type: 'group', circle: 'all', avatar: '', unreadCount: 0,
        messages: [m('a', 'вечір #світло'), m('b', 'новини #світ'), m('c', 'просто світ')] },
    ] as unknown as Chat[];
    const { result } = renderHook(() => useGlobalSearch(chats, '#світ', true));
    await act(async () => {
      await new Promise((r) => setTimeout(r, 30));
    });
    expect(result.current.letters.map((l) => l.messageId)).toEqual(['b']);
  });
});
