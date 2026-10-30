/**
 * Місце в меню листа і у «Видалити?» — «Місце» або назва точки, а не
 * «Картка» і не сирий тип «geo:point» (обхід A52 28.10).
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { MessageActionSheet, type MessageSheetHandlers } from '../components/messenger/feed/MessageActionSheet';
import { letterPreview } from '../components/messenger/feed/feedParts';
import { tNow } from '../components/chat/tNow';
import type { Chat, Message } from '../types/messenger';

vi.mock('../services/livingPulse', () => ({
  listenLiving: () => () => {},
  useLiving: <T,>(f: (s: { pulse: null }) => T) => f({ pulse: null }),
}));

afterEach(() => cleanup());

const place: Message = {
  id: 'msg_geo',
  senderId: 'me',
  senderName: 'Я',
  senderAvatar: '',
  timestamp: '19:47',
  type: 'geo:point',
  text: '',
  isSelf: true,
  geoPoint: { lat: 50.1, lon: 14.4, atMs: 1 },
} as Message;

const chat: Chat = { id: 'chat_g', title: 'Група', avatar: '', type: 'group', circle: 'friends', unreadCount: 0, messages: [place] } as Chat;

const on = new Proxy({}, { get: () => vi.fn() }) as MessageSheetHandlers;

describe('превʼю місця', () => {
  it('рядок для «Видалити?» — «Місце», не «geo:point»', () => {
    expect(letterPreview(place)).toBe(tNow('chatList.kind.place'));
    expect(letterPreview(place)).not.toContain('geo:point');
  });

  it('назва точки, якщо вона є', () => {
    expect(letterPreview({ ...place, geoPoint: { lat: 1, lon: 2, atMs: 1, label: 'Кавʼярня' } } as Message)).toBe('Кавʼярня');
  });

  it('піднятий лист у меню каже «Місце», а не «Картка»', () => {
    render(<MessageActionSheet msg={place} currentChat={chat} currentUserId="me" speaking={false} onClose={vi.fn()} on={on} />);
    expect(screen.queryByText(tNow('messageActionSheet.card'))).toBeNull();
    expect(screen.getByText(tNow('chatList.kind.place'))).toBeTruthy();
  });

  it('текст листа лишається текстом', () => {
    expect(letterPreview({ ...place, type: 'text', text: 'привіт', geoPoint: undefined } as Message)).toBe('привіт');
  });
});
