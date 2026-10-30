import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cs } from '../i18n/messages/cs';
import { en } from '../i18n/messages/en';
import { uk, type MessageKey } from '../i18n/messages/uk';
import { messengerApi, messageFromNode, type NodeMessage } from '../services/messengerApi';
import {
  findDirectChat,
  joinFactsFor,
  joinTag,
  joinedOf,
  localJoinFacts,
  type JoinFacts,
} from '../services/joinTag';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, ChatMember, Message } from '../types/messenger';

const makeFacts = (overrides: Partial<Record<keyof JoinFacts, (id: string) => boolean>> = {}): JoinFacts => ({
  inClose: overrides.inClose ?? (() => false),
  verified: overrides.verified ?? (() => false),
  keyChanged: overrides.keyChanged ?? (() => false),
});

const makeDmChat = (peerId: string, overrides: Partial<Chat> = {}): Chat => ({
  id: `dm-${peerId}`,
  peerNodeId: peerId,
  title: peerId,
  avatar: '',
  type: 'direct',
  circle: 'all',
  unreadCount: 0,
  messages: [],
  ...overrides,
});

const makeMember = (id: string, overrides: Partial<ChatMember> = {}): ChatMember => ({
  id,
  name: id,
  handle: id,
  avatar: '',
  role: 'member',
  ...overrides,
});

const makeGroupChat = (members: ChatMember[], overrides: Partial<Chat> = {}): Chat => ({
  id: 'group-1',
  title: 'Група',
  avatar: '',
  type: 'group',
  circle: 'all',
  unreadCount: 0,
  members,
  messages: [],
  ...overrides,
});

const makeMessage = (overrides: Partial<Message> = {}): Message => ({
  id: 'msg-1',
  senderId: 'adder-1',
  senderName: 'Іван',
  senderAvatar: '',
  type: 'system',
  text: '',
  timestamp: '12:00',
  status: 'sent',
  ...overrides,
});

describe('joinTag', () => {
  beforeEach(() => {
    localStorage.clear();
    useMessengerStore.setState({ chats: [] });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('returns close for a peer whose direct/dm chat is in Close and never matches by chat.id or non-DM type', () => {
    useMessengerStore.setState({
      chats: [makeDmChat('anna', { circle: 'close' })],
    });
    expect(joinTag('anna', localJoinFacts)).toBe('close');

    useMessengerStore.setState({
      chats: [makeDmChat('bohdan', { type: 'dm', circle: 'all', circles: ['close'] })],
    });
    expect(joinTag('bohdan', localJoinFacts)).toBe('close');

    useMessengerStore.setState({
      chats: [
        {
          id: 'carl',
          peerNodeId: 'other-peer',
          title: 'Карл',
          avatar: '',
          type: 'direct',
          circle: 'close',
          unreadCount: 0,
          messages: [],
        },
        {
          id: 'dm-daria',
          peerNodeId: 'daria',
          title: 'Дарія',
          avatar: '',
          type: 'p2p',
          circle: 'close',
          unreadCount: 0,
          messages: [],
        },
      ],
    });
    expect(findDirectChat('carl')).toBeUndefined();
    expect(findDirectChat('daria')).toBeUndefined();
    expect(joinTag('carl', localJoinFacts)).toBeNull();
    expect(joinTag('daria', localJoinFacts)).toBeNull();
  });

  it('reads ChatMember.verified from this group first and falls back to the direct chat member only', () => {
    useMessengerStore.setState({
      chats: [
        makeGroupChat([
          makeMember('taras', { name: 'Тарас', verified: true }),
          makeMember('ivan', { name: 'Іван', verified: false }),
          makeMember('olena', { name: 'Олена' }),
          makeMember('petro', { name: 'Петро' }),
          makeMember('nazar', { name: 'Назар', verified: false }),
        ]),
        makeGroupChat([makeMember('petro', { name: 'Петро', verified: true })], { id: 'group-2' }),
        makeDmChat('olena', {
          title: 'Олена',
          members: [makeMember('olena', { name: 'Олена', verified: true })],
        }),
        makeDmChat('nazar', {
          title: 'Назар',
          members: [makeMember('nazar', { name: 'Назар', verified: true })],
        }),
      ],
    });

    const facts = joinFactsFor('group-1');
    expect(joinTag('taras', facts)).toBe('verified');
    expect(joinTag('ivan', facts)).toBeNull();
    expect(joinTag('olena', facts)).toBe('verified');
    expect(joinTag('petro', facts)).toBeNull();
    expect(joinTag('nazar', facts)).toBeNull();
  });

  it('returns newKey when direct chat contactKeyChangedAt is within the last 30 days', () => {
    const twoDaysAgo = new Date(Date.now() - 2 * 24 * 60 * 60 * 1000).toISOString();
    const fortyDaysAgo = new Date(Date.now() - 40 * 24 * 60 * 60 * 1000).toISOString();
    useMessengerStore.setState({
      chats: [
        makeDmChat('x9', { contactKeyChangedAt: twoDaysAgo }),
        makeDmChat('old-key', { contactKeyChangedAt: fortyDaysAgo }),
      ],
    });
    expect(joinTag('x9', localJoinFacts)).toBe('newKey');
    expect(joinTag('old-key', localJoinFacts)).toBeNull();
  });

  it('returns newKey even when a Close and verified member has a recent key change', () => {
    const oneHourAgo = new Date(Date.now() - 60 * 60 * 1000).toISOString();
    useMessengerStore.setState({
      chats: [
        makeDmChat('anna', { circle: 'close', contactKeyChangedAt: oneHourAgo }),
        makeGroupChat([makeMember('anna', { verified: true })]),
      ],
    });
    expect(joinTag('anna', localJoinFacts)).toBe('newKey');
  });

  it('does not mark anyone verified, close, or newKey from unrelated localStorage keys', () => {
    localStorage.setItem('some-verified-cache', JSON.stringify(['taras']));
    localStorage.setItem('trusted_peers', JSON.stringify({ taras: true }));
    localStorage.setItem('ph.circles.v1', JSON.stringify({ close: ['anna'] }));
    localStorage.setItem('key_change_log', JSON.stringify(['x9']));

    expect(localJoinFacts.verified('taras')).toBe(false);
    expect(joinTag('taras', localJoinFacts)).toBeNull();
    expect(joinTag('anna', localJoinFacts)).toBeNull();
    expect(joinTag('x9', localJoinFacts)).toBeNull();
  });

  it('returns null when nothing is known or peerId is empty', () => {
    const facts = makeFacts({
      inClose: () => true,
      verified: () => true,
      keyChanged: () => true,
    });
    expect(joinTag('unknown', makeFacts())).toBeNull();
    expect(joinTag('', facts)).toBeNull();
  });

  it('never calls network functions when reading localJoinFacts', () => {
    const convSpy = vi.spyOn(messengerApi, 'listConversations');
    const contactsSpy = vi.spyOn(messengerApi, 'listContacts');
    const membersSpy = vi.spyOn(messengerApi, 'groupMembers');
    const fetchSpy = vi.spyOn(globalThis, 'fetch');

    expect(joinTag('anna', localJoinFacts)).toBeNull();

    useMessengerStore.setState({
      chats: [makeDmChat('anna', { circle: 'close' })],
    });
    expect(joinTag('anna', localJoinFacts)).toBe('close');

    expect(convSpy).not.toHaveBeenCalled();
    expect(contactsSpy).not.toHaveBeenCalled();
    expect(membersSpy).not.toHaveBeenCalled();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('joinedOf only accepts system add with systemActTarget and resolves name from group members or DM title', () => {
    useMessengerStore.setState({
      chats: [
        makeGroupChat([makeMember('taras', { name: 'Тарас' })]),
        makeDmChat('anna', { title: 'Анна' }),
      ],
    });

    expect(
      joinedOf(
        makeMessage({
          id: 'm1',
          type: 'text',
          text: 'Привіт',
          systemAct: 'add',
          systemActTarget: 'anna',
        }),
        'group-1',
      ),
    ).toBeNull();

    expect(
      joinedOf(
        {
          ...makeMessage({ id: 'm-undef-type', systemAct: 'add', systemActTarget: 'anna' }),
          type: undefined as unknown as Message['type'],
        },
        'group-1',
      ),
    ).toBeNull();

    expect(
      joinedOf(
        makeMessage({ id: 'm2', type: 'system', systemAct: 'leave', systemActTarget: 'anna' }),
        'group-1',
      ),
    ).toBeNull();

    expect(
      joinedOf(
        makeMessage({ id: 'm3', type: 'system', systemAct: 'join', systemActTarget: 'anna' }),
        'group-1',
      ),
    ).toBeNull();

    expect(
      joinedOf(
        makeMessage({ id: 'm-no-target', type: 'system', systemAct: 'add', senderId: 'anna', senderName: 'Анна' }),
        'group-1',
      ),
    ).toBeNull();

    expect(
      joinedOf(
        makeMessage({
          id: 'm-unknown-peer',
          type: 'system',
          systemAct: 'add',
          systemActTarget: 'stranger-99',
          senderId: 'adder-1',
          senderName: 'Іван',
        }),
        'group-1',
      ),
    ).toBeNull();

    expect(
      joinedOf(
        makeMessage({
          id: 'm4',
          type: 'system',
          systemAct: 'add',
          systemActTarget: 'taras',
          senderId: 'adder-1',
          senderName: 'Іван',
        }),
        'group-1',
      ),
    ).toEqual({
      id: 'taras',
      name: 'Тарас',
    });

    expect(
      joinedOf(
        makeMessage({
          id: 'm5',
          type: 'system',
          systemAct: 'add',
          systemActTarget: 'anna',
          senderId: 'adder-1',
          senderName: 'Іван',
        }),
        'group-1',
      ),
    ).toEqual({
      id: 'anna',
      name: 'Анна',
    });
  });

  it('messageFromNode maps system_act and system_act_target to systemAct and systemActTarget', () => {
    const nodeRow: NodeMessage = {
      id: 'row-add-1',
      conversation_id: 'group-1',
      client_id: 'cid-1',
      seq: 1,
      author_id: 'adder-1',
      author_name: 'Іван',
      kind: 'system',
      body: null,
      ciphertext: null,
      transport: null,
      sent_at: '2026-03-30T12:00:00Z',
      edited_at: null,
      deleted_at: null,
      system_act: 'add',
      system_act_target: 'anna',
    };

    const msg = messageFromNode(nodeRow, 'me');
    expect(msg.type).toBe('system');
    expect(msg.systemAct).toBe('add');
    expect(msg.systemActTarget).toBe('anna');
    expect(msg.senderId).toBe('adder-1');
  });

  it('keeps uk/en/cs parity for joinTag.* keys and avoids коло/кола/колі in uk', () => {
    const keys: MessageKey[] = ['joinTag.joined', 'joinTag.close', 'joinTag.verified', 'joinTag.newKey'];
    for (const key of keys) {
      expect(typeof uk[key]).toBe('string');
      expect(uk[key].length).toBeGreaterThan(0);
      expect(uk[key]).not.toMatch(/кол[оаі]/i);

      expect(typeof en[key]).toBe('string');
      expect(en[key]!.length).toBeGreaterThan(0);

      expect(typeof cs[key]).toBe('string');
      expect(cs[key].length).toBeGreaterThan(0);
    }
    expect(uk['joinTag.joined']).toContain('{name}');
    expect(en['joinTag.joined']).toContain('{name}');
    expect(cs['joinTag.joined']).toContain('{name}');
  });
});
