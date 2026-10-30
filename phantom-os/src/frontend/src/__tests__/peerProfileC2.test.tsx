import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { PeerProfileSheet } from '../components/messenger/PeerProfileSheet';
import { messengerApi } from '../services/messengerApi';
import { phantomSelfApi } from '../services/phantomSelfApi';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message } from '../types/messenger';

vi.mock('../services/callGate', async (orig) => ({
  ...(await orig<typeof import('../services/callGate')>()),
  callGate: () => ({ can: true, why: '' }),
}));

const PEER = 'a1b2c3d4e5f60718';
const file = { id: 'msg_f', senderId: PEER, senderName: 'Оксана', timestamp: '18:00', type: 'file', fileName: 'plan.pdf' } as unknown as Message;

beforeEach(() => {
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
  vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
  useMessengerStore.setState({
    chats: [{ id: 'conv_ok', title: 'Оксана', type: 'dm', peerNodeId: PEER, handle: 'oksana', messages: [file], muted: false } as unknown as Chat],
  });
});
afterEach(() => vi.restoreAllMocks());

const started = () => {
  const seen: Array<{ video: boolean }> = [];
  const on = (e: Event) => seen.push((e as CustomEvent).detail);
  window.addEventListener('phantom:start-call', on);
  return { seen, stop: () => window.removeEventListener('phantom:start-call', on) };
};

describe('C2: профіль людини', () => {
  it('дзвінок ніколи не набирає сам — спершу питає', () => {
    const s = started();
    render(<PeerProfileSheet peerNodeId={PEER} name="Оксана" chatId="conv_ok" onClose={() => {}} />);
    fireEvent.click(screen.getByTestId('peer-action-call'));
    expect(s.seen).toHaveLength(0);
    expect(screen.getByTestId('peer-call-confirm').textContent).toContain('Подзвонити зараз?');
    fireEvent.click(screen.getByTestId('peer-call-go'));
    expect(s.seen).toEqual([expect.objectContaining({ video: false })]);
    s.stop();
  });

  it('відео — окрема дія, теж через питання', () => {
    const s = started();
    render(<PeerProfileSheet peerNodeId={PEER} name="Оксана" chatId="conv_ok" onClose={() => {}} />);
    fireEvent.click(screen.getByTestId('info-more'));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Відео' }));
    expect(s.seen).toHaveLength(0);
    fireEvent.click(screen.getByTestId('peer-call-go'));
    expect(s.seen).toEqual([expect.objectContaining({ video: true })]);
    s.stop();
  });

  it('відкритий не з розмови: @нік і спільне беруться з розмови з цією людиною', () => {
    render(<PeerProfileSheet peerNodeId={PEER} name="Оксана" onClose={() => {}} />);
    expect(screen.getByTestId('info-kind').textContent).toContain('@oksana');
    expect(screen.getByTestId('info-tabs')).toBeTruthy();
  });
});
