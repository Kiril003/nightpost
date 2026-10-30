import { describe, it, expect, beforeEach } from 'vitest';
import { useMessengerStore } from '../stores/messengerStore';
import { installNativeOpenChat } from '../services/nativeOpenChat';

type Win = { __phantomOpenChat?: (id: string) => boolean };

describe('тап по сповіщенню Nightpost відкриває саме його розмову', () => {
  beforeEach(() => {
    useMessengerStore.setState({ chats: [], activeChatId: 'chat_saved_notes' } as never);
  });

  it('відкриває одразу, коли розмова вже в списку', () => {
    useMessengerStore.setState({ chats: [{ id: 'peer_phantom_agent', type: 'phantom', title: 'Nightpost', messages: [] }] } as never);
    const off = installNativeOpenChat();
    expect((window as unknown as Win).__phantomOpenChat?.('peer_phantom_agent')).toBe(true);
    expect(useMessengerStore.getState().activeChatId).toBe('peer_phantom_agent');
    off();
  });

  it('чекає, доки список підвантажиться, і тоді відкриває', () => {
    const off = installNativeOpenChat();
    expect((window as unknown as Win).__phantomOpenChat?.('peer_phantom_agent')).toBe(false);
    expect(useMessengerStore.getState().activeChatId).toBe('chat_saved_notes');
    useMessengerStore.setState({ chats: [{ id: 'peer_phantom_agent', type: 'phantom', title: 'Nightpost', messages: [] }] } as never);
    expect(useMessengerStore.getState().activeChatId).toBe('peer_phantom_agent');
    off();
  });
});
