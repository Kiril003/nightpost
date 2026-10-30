/**
 * Картка людини: поділитися контактом зі своїх людей з «+», отримати її,
 * відкрити профіль або написати; людина не зі своїх — чесний стан.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MessageComposer } from '../components/messenger/MessageComposer';
import { MessageCard } from '../components/messenger/cards/MessageCard';
import { messengerApi, messageFromNode, type NodeContact } from '../services/messengerApi';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message } from '../types/messenger';

const contact = (id: string, name: string, peer: string): NodeContact =>
  ({ id, peer_node_id: peer, display_name: name, peer_address: null, safety_number: '', safety_number_pretty: '', verified: true, session_ready: true, created_at: '' }) as NodeContact;

const OKSANA = contact('ct_o', 'Оксана', 'node_oksana');
const PETRO = contact('ct_p', 'Петро', 'node_petro');

const CHAT = {
  id: 'chat_g', title: 'Двір', avatar: '', type: 'group', circle: 'all', pinned: false, unreadCount: 0,
  members: [{ id: 'node_oksana', name: 'Оксана', handle: '', avatar: '', role: 'member' }], messages: [],
} as unknown as Chat;

const card = (data: unknown, senderId = 'node_oksana'): Message =>
  ({ id: 'm1', senderId, senderName: 'Оксана', senderAvatar: '', timestamp: '10:00', type: 'widget:person', isSelf: false, personData: data }) as unknown as Message;

const flush = () => act(async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); });

let seq = 0;
beforeEach(() => {
  vi.restoreAllMocks();
  useMessengerStore.setState({
    chats: [CHAT],
    activeChatId: 'chat_g',
    currentUser: { id: 'node_me', name: 'Кирило', avatar: '' },
  } as never);
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([OKSANA, PETRO]);
  vi.spyOn(messengerApi, 'appendMessage').mockImplementation(async () => ({ id: `srv_${++seq}`, delivery_state: 'sent' }) as never);
});

describe('поділитися людиною з «+»', () => {
  const props = {
    onSendMessage: () => {}, onSendVoiceMessage: () => {}, onOpenActions: () => {}, onOpenScheduler: () => {},
    replyingTo: null, onCancelReply: () => {}, editingMessage: null, onCancelEdit: () => {}, onSaveEdit: () => {},
    selectedMessagesForQuote: [], onSynthesizeMultiQuote: () => {}, onClearSelectedQuotes: () => {}, chatId: 'chat_g',
  };

  it('«+ → Ще → Контакт» → людина зі своїх їде карткою widget:person до вузла', async () => {
    render(<MessageComposer {...props} wherePeer={null} />);
    await flush();
    fireEvent.click(screen.getByTestId('composer-attach'));
    fireEvent.click(screen.getByTestId('send-person'));
    await flush();
    const sheet = screen.getByTestId('person-pick');
    fireEvent.click(within(sheet).getByTestId('person-pick-ct_p'));
    await flush();
    const append = vi.mocked(messengerApi.appendMessage);
    expect(append).toHaveBeenCalledTimes(1);
    const body = append.mock.calls[0][1] as { kind: string; body: string };
    expect(body.kind).toBe('widget:person');
    expect(JSON.parse(body.body)).toMatchObject({ v: 1, name: 'Петро', peerId: 'node_petro' });
    await waitFor(() => expect(screen.queryByTestId('person-pick')).toBeNull());
  });

  it('своїх людей немає — чесно сказано', async () => {
    vi.mocked(messengerApi.listContacts).mockResolvedValue([]);
    render(<MessageComposer {...props} wherePeer={null} />);
    await flush();
    fireEvent.click(screen.getByTestId('composer-attach'));
    fireEvent.click(screen.getByTestId('send-person'));
    await flush();
    expect(screen.getByTestId('person-pick-empty').textContent).toBe('У ваших людях поки нікого');
  });
});

describe('отримана картка людини', () => {
  it('з вузла приходить зі своїм вмістом', () => {
    const msg = messageFromNode(
      { id: 'srv_1', kind: 'widget:person', author_id: 'node_oksana', author_name: 'Оксана', sent_at: '2026-10-19T10:00:00', body: JSON.stringify({ v: 1, id: 'p1', name: 'Петро', peerId: 'node_petro' }) } as never,
      'node_me',
    );
    expect(msg.type).toBe('widget:person');
    expect(msg.personData).toMatchObject({ name: 'Петро', peerId: 'node_petro' });
  });

  it('людина зі своїх: «Написати» відкриває розмову з нею', async () => {
    vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([{ id: 'dm_p', contact_id: 'ct_p' }] as never);
    const create = vi.spyOn(messengerApi, 'createConversation');
    const refresh = vi.fn(async () => {});
    useMessengerStore.setState({ refreshConversations: refresh } as never);
    render(<MessageCard msg={card({ v: 1, id: 'p1', name: 'Петро', peerId: 'node_petro' })} meId="node_me" meName="Кирило" />);
    await flush();
    expect(screen.getByTestId('card-title').textContent).toBe('Петро');
    expect(screen.getByTestId('card-status').textContent).toBe('У ваших людях');
    fireEvent.click(screen.getByTestId('person-write'));
    await flush();
    expect(create).not.toHaveBeenCalled();
    expect(refresh).toHaveBeenCalled();
    expect(useMessengerStore.getState().activeChatId).toBe('dm_p');
  });

  it('розмови ще немає — створюється з ключем людини', async () => {
    vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([] as never);
    const create = vi.spyOn(messengerApi, 'createConversation').mockResolvedValue({ id: 'dm_new' } as never);
    useMessengerStore.setState({ refreshConversations: vi.fn(async () => {}) } as never);
    render(<MessageCard msg={card({ v: 1, id: 'p1', name: 'Петро', peerId: 'node_petro' })} meId="node_me" meName="Кирило" />);
    await flush();
    fireEvent.click(screen.getByTestId('person-write'));
    await flush();
    expect(create).toHaveBeenCalledWith(expect.objectContaining({ kind: 'dm', contact_id: 'ct_p' }));
    expect(useMessengerStore.getState().activeChatId).toBe('dm_new');
  });

  it('«Профіль» відкриває профіль людини', async () => {
    render(<MessageCard msg={card({ v: 1, id: 'p1', name: 'Петро', peerId: 'node_petro' })} meId="node_me" meName="Кирило" />);
    await flush();
    fireEvent.click(screen.getByTestId('person-profile'));
    await flush();
    expect(screen.getByTestId('peer-profile-name').textContent).toContain('Петро');
  });

  it('людина не зі своїх — чесний стан, без дій', async () => {
    render(<MessageCard msg={card({ v: 1, id: 'p1', name: 'Ана', peerId: 'node_ana' })} meId="node_me" meName="Кирило" />);
    await flush();
    expect(screen.getByTestId('card-status').textContent).toBe('Ще не у ваших людях');
    expect(screen.queryByTestId('person-actions')).toBeNull();
  });

  it('картка про мене — «Це ви»', async () => {
    render(<MessageCard msg={card({ v: 1, id: 'p1', name: 'Кирило', peerId: 'node_me' })} meId="node_me" meName="Кирило" />);
    await flush();
    expect(screen.getByTestId('card-status').textContent).toBe('Це ви');
    expect(screen.queryByTestId('person-actions')).toBeNull();
  });

  it('криве тіло — чесна рамка, не порожнеча', () => {
    render(<MessageCard msg={card({ v: 1, id: 'p1', name: '', peerId: '' })} meId="node_me" meName="Кирило" />);
    expect(screen.getByTestId('card-unreadable')).toBeTruthy();
  });
});
