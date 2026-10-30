/**
 * Відповідь у гілці — той самий лист, що й у розмові (04.10).
 *
 * Гілка мала власне поле: кнопки 36 замість 44, скріпку замість «+»,
 * плейсхолдер старим hex `#5F6A60` і кільце фокуса довкола картки `surface`,
 * — тож відповідь виглядала як інший застосунок. Тепер і розмова, і гілка
 * складаються з одних деталей MessageComposer: ComposerSheet, ComposerField,
 * SendButton. Поведінка лишається своя: текст у гілці йде відповіддю на корінь.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { ThreadView } from '../components/messenger/ThreadView';
import { useSpaceStore } from '../stores/spaceStore';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message } from '../types/messenger';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
});

const ROOT = {
  id: 'r1',
  senderId: 'p1',
  senderName: 'Оксана',
  senderAvatar: '',
  timestamp: '18:02',
  type: 'text',
  text: 'Беремо квитки на суботу?',
  isSelf: false,
} as unknown as Message;

const CHAT = { id: 'c1', title: 'Оксана', type: 'dm', messages: [ROOT], unreadCount: 0 } as unknown as Chat;

let prev: ReturnType<typeof useMessengerStore.getState>;

beforeEach(() => {
  prev = useMessengerStore.getState();
  useMessengerStore.setState({ chats: [CHAT], activeChatId: CHAT.id, currentUser: { ...prev.currentUser, id: 'me' } });
  useSpaceStore.getState().openThreadView({ chatId: CHAT.id, rootMessageId: ROOT.id });
});

afterEach(() => {
  cleanup();
  useSpaceStore.getState().openThreadView(null);
  useMessengerStore.setState(prev, true);
  vi.restoreAllMocks();
});

describe('гілка: лист відповіді з тих самих деталей, що й розмова', () => {
  it('аркуш, поле й «Надіслати» — спільні деталі; «+» і «Надіслати» колами 44, без старого hex і кільця', () => {
    render(<ThreadView />);
    const sheet = screen.getByTestId('thread-composer');
    expect(sheet.getAttribute('data-ph-composer')).toBe('sheet');
    const field = within(sheet).getByTestId('thread-composer-field');
    expect(field.getAttribute('data-ph-composer')).toBe('field');
    expect(field.className).toContain('rounded-composer');
    const send = within(sheet).getByTestId('thread-send');
    expect(send.getAttribute('data-ph-composer')).toBe('send');
    for (const b of within(sheet).getAllByRole('button')) {
      expect(b.className).toContain('w-11');
      expect(b.className).toContain('h-11');
    }
    expect(within(sheet).getByRole('button', { name: 'Додати фото або файл' })).toBeTruthy();
    const html = sheet.outerHTML;
    expect(html).not.toContain('#5F6A60');
    expect(html).not.toContain('focus-within:ring');
    expect(within(sheet).getByRole('textbox').className).toContain('placeholder:text-ink-3');
  });

  it('текст у гілці йде відповіддю на корінь, а порожній — не йде', () => {
    const startReply = vi.fn();
    const sendMessage = vi.fn();
    useMessengerStore.setState({ startReply, sendMessage } as never);
    render(<ThreadView />);
    const send = screen.getByTestId('thread-send') as HTMLButtonElement;
    expect(send.disabled).toBe(true);
    fireEvent.change(screen.getByRole('textbox', { name: 'Відповісти в гілці' }), { target: { value: 'Так, на 19:40' } });
    fireEvent.click(send);
    expect(startReply).toHaveBeenCalledWith(ROOT);
    expect(sendMessage).toHaveBeenCalledWith('Так, на 19:40');
  });
});
