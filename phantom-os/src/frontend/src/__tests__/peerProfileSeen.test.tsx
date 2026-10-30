import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { PeerProfileSheet } from '../components/messenger/PeerProfileSheet';
import { messengerApi, type NodeContact } from '../services/messengerApi';
import { phantomSelfApi } from '../services/phantomSelfApi';
import { useMessengerStore } from '../stores/messengerStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import { pinVenue, type CardMarks } from '../types/meetup';
import type { Chat, Message } from '../types/messenger';

const PEER = 'a1b2c3d4e5f60718';

const contact = (over: Partial<NodeContact> = {}): NodeContact => ({
  id: 'ct_1',
  peer_node_id: PEER,
  display_name: 'Оксана Мельник',
  peer_address: null,
  safety_number: '111112222233333444445555566666',
  safety_number_pretty: '11111 22222 33333 44444 55555 66666',
  verified: true,
  session_ready: true,
  created_at: '2026-09-01T10:00:00',
  ...over,
});

const meetupMsg = (id: string, at: number, cardMarks?: CardMarks): Message =>
  ({
    id: `msg_${id}`,
    senderId: 'me',
    senderName: 'Кирило',
    timestamp: '18:00',
    type: 'widget:meetup',
    isSelf: true,
    meetupData: {
      v: 1,
      id,
      venue: pinVenue(50.45, 30.52, 'Каштани'),
      at,
      organizer: 'me',
      invitees: [
        { id: 'me', name: 'Кирило' },
        { id: PEER, name: 'Оксана Мельник' },
      ],
      expiresAt: null,
    },
    cardMarks,
  }) as unknown as Message;

const seedChats = (planMessages: Message[]) => {
  useMessengerStore.setState({
    currentUser: { ...useMessengerStore.getState().currentUser, id: 'me', name: 'Кирило' },
    chats: [
      { id: 'conv_ok', title: 'Оксана Мельник', type: 'dm', peerNodeId: PEER, messages: [], muted: false } as unknown as Chat,
      { id: 'conv_plan', title: 'Вечір', type: 'group', messages: planMessages, muted: false } as unknown as Chat,
    ],
  });
};

const setLocale = (l: 'uk' | 'en') =>
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: l } }));

beforeEach(() => {
  setLocale('uk');
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact()]);
  vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
  vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
});

afterEach(() => {
  setLocale('uk');
  vi.restoreAllMocks();
});

describe('Зустрічі наживо в профілі людини', () => {
  it('показує рядок із кількістю та заголовком, коли є дві спільні зустрічі у групі плану', () => {
    seedChats([
      meetupMsg('m1', Date.UTC(2026, 8, 10, 18, 0), { 'here:1': ['me', PEER] }),
      meetupMsg('m2', Date.UTC(2026, 8, 20, 18, 0), { 'here:1': ['me', PEER] }),
    ]);
    render(<PeerProfileSheet peerNodeId={PEER} name="Оксана Мельник" chatId="conv_ok" onClose={() => {}} />);
    const row = screen.getByTestId('peer-profile-seen');
    expect(row.textContent).toContain('Зустрічі наживо');
    expect(row.textContent).toContain('2 рази');
    expect(row.textContent).toContain('востаннє 20 вересня');
  });

  it('en: одна зустріч — однина і дата англійською', () => {
    setLocale('en');
    seedChats([meetupMsg('m1', Date.UTC(2026, 8, 10, 18, 0), { 'here:1': ['me', PEER] })]);
    render(<PeerProfileSheet peerNodeId={PEER} name="Оксана Мельник" chatId="conv_ok" onClose={() => {}} />);
    const row = screen.getByTestId('peer-profile-seen');
    expect(row.textContent).toContain('Seen in person');
    expect(row.textContent).toContain('1 time · last 10 September');
  });

  it('ховає рядок і не показує 0, коли спільних позначок немає', () => {
    seedChats([meetupMsg('m1', Date.UTC(2026, 8, 10, 18, 0))]);
    render(<PeerProfileSheet peerNodeId={PEER} name="Оксана Мельник" chatId="conv_ok" onClose={() => {}} />);
    expect(screen.queryByTestId('peer-profile-seen')).toBeNull();
    expect(screen.queryByText(/\b0\b/)).toBeNull();
  });
});
