import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { MediaLightboxModal } from '../components/messenger/MediaLightboxModal';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message } from '../types/messenger';

const item = (i: number): Message =>
  ({
    id: `a${i}`,
    senderId: 'peer',
    senderName: 'Оксана',
    senderAvatar: '',
    timestamp: '2026-10-19T08:00:00Z',
    type: 'image',
    mediaUrl: `blob:album-${i}`,
    text: i === 0 ? 'Озеро' : '',
    mediaBatch: { id: 'b1', index: i, total: 3 },
  }) as Message;

beforeEach(() => {
  const chat = { id: 'c1', title: 'Оксана', type: 'dm', avatar: '', unreadCount: 0, messages: [item(0), item(1), item(2)] } as unknown as Chat;
  useMessengerStore.setState({ chats: [chat], activeChatId: 'c1', replyingTo: null });
});
afterEach(cleanup);

describe('альбом: відповідь на одне фото', () => {
  it('з переглядача — відповідь саме на відкрите фото, не на весь альбом', () => {
    let closed = false;
    render(<MediaLightboxModal isOpen onClose={() => { closed = true; }} mediaUrl="blob:album-1" />);
    fireEvent.click(screen.getByTestId('lightbox-reply'));
    expect(closed).toBe(true);
    expect(useMessengerStore.getState().replyingTo).toMatchObject({ id: 'a1', type: 'image', senderName: 'Оксана' });
  });

  it('третє фото — відповідь на третє', () => {
    render(<MediaLightboxModal isOpen onClose={() => {}} mediaUrl="blob:album-2" />);
    fireEvent.click(screen.getByTestId('lightbox-reply'));
    expect(useMessengerStore.getState().replyingTo?.id).toBe('a2');
  });
});
