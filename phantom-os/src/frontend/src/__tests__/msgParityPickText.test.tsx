/**
 * MSG-PARITY: частина тексту листа. «Вибрати текст» (меню довгого дотику › Ще)
 * відкриває лист повністю й виділяється; «Копіювати вибране» і «Цитувати
 * вибране» беруть саме виділене, а не весь лист.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MessageActionSheet, type MessageSheetHandlers } from '../components/messenger/feed/MessageActionSheet';
import { tNow } from '../components/chat/tNow';
import type { Chat, Message } from '../types/messenger';

vi.mock('../services/livingPulse', () => ({
  listenLiving: () => () => {},
  useLiving: <T,>(f: (s: { pulse: null }) => T) => f({ pulse: null }),
}));

afterEach(() => {
  cleanup();
  window.getSelection()?.removeAllRanges();
});

const msg: Message = {
  id: 'msg_pick',
  senderId: 'peer_1',
  senderName: 'Олена',
  senderAvatar: '',
  timestamp: '12:00',
  type: 'text',
  text: 'Адреса: **вул. Шевченка 12**, код 4417',
  isSelf: false,
};

const chat: Chat = {
  id: 'chat_dm_1',
  title: 'Олена',
  avatar: '',
  type: 'dm',
  circle: 'friends',
  unreadCount: 0,
  messages: [msg],
};

const handlers = (): MessageSheetHandlers => ({
  reply: vi.fn(),
  copyText: vi.fn(),
  forward: vi.fn(),
  edit: vi.fn(),
  react: vi.fn(),
  openReactionPicker: vi.fn(),
  speak: vi.fn(),
  openCanvas: vi.fn(),
  toast: vi.fn(),
  translate: vi.fn(),
  summarize: vi.fn(),
  actionItems: vi.fn(),
  togglePin: vi.fn(),
  save: vi.fn(),
  select: vi.fn(),
  inspect: vi.fn(),
  remove: vi.fn(),
});

const sheet = (on: MessageSheetHandlers, onClose = vi.fn()) =>
  render(<MessageActionSheet msg={msg} currentChat={chat} currentUserId="me" speaking={false} onClose={onClose} on={on} />);

const openPicker = () => {
  fireEvent.click(screen.getByText(tNow('messageActionSheet.more')));
  fireEvent.click(screen.getByText(tNow('messageActionSheet.selectText')));
};

const pick = (needle: string) => {
  const el = screen.getByTestId('msg-lifted-text');
  const node = el.firstChild as Text;
  const start = node.data.indexOf(needle);
  const range = document.createRange();
  range.setStart(node, start);
  range.setEnd(node, start + needle.length);
  const sel = window.getSelection()!;
  sel.removeAllRanges();
  sel.addRange(range);
  act(() => {
    document.dispatchEvent(new Event('selectionchange'));
  });
};

describe('MSG-PARITY — вибрати частину тексту', () => {
  it('«Вибрати текст» лишає меню відкритим і показує лист без розмітки, весь виділений', () => {
    const onClose = vi.fn();
    sheet(handlers(), onClose);
    openPicker();
    expect(onClose).not.toHaveBeenCalled();
    const lifted = screen.getByTestId('msg-lifted-text');
    expect(lifted.textContent).toBe('Адреса: вул. Шевченка 12, код 4417');
    expect(window.getSelection()?.toString()).toBe('Адреса: вул. Шевченка 12, код 4417');
  });

  it('«Копіювати вибране» копіює лише виділену частину', () => {
    const on = handlers();
    sheet(on);
    openPicker();
    pick('вул. Шевченка 12');
    window.getSelection()?.removeAllRanges();
    fireEvent.click(screen.getByText(tNow('messageActionSheet.copyPicked')));
    expect(on.copyText).toHaveBeenCalledWith('вул. Шевченка 12');
  });

  it('«Цитувати вибране» відповідає цитатою з виділеного', () => {
    const on = handlers();
    sheet(on);
    openPicker();
    pick('код 4417');
    fireEvent.click(screen.getByText(tNow('messageActionSheet.quotePicked')));
    expect(on.reply).toHaveBeenCalledWith(msg, 'код 4417');
  });

  it('виділення поза листом не підміняє вибране', () => {
    const on = handlers();
    sheet(on);
    openPicker();
    pick('код 4417');
    const outside = document.createElement('p');
    outside.textContent = 'чужий рядок';
    document.body.appendChild(outside);
    const range = document.createRange();
    range.selectNodeContents(outside);
    window.getSelection()!.removeAllRanges();
    window.getSelection()!.addRange(range);
    act(() => {
      document.dispatchEvent(new Event('selectionchange'));
    });
    fireEvent.click(screen.getByText(tNow('messageActionSheet.copyPicked')));
    expect(on.copyText).toHaveBeenCalledWith('код 4417');
    outside.remove();
  });
});
