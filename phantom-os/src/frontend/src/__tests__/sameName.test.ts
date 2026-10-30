import { describe, expect, it } from 'vitest';
import {
  foldName,
  handleTag,
  normalizeHandle,
  personKey,
  ringOfPeer,
  sameNameSuffixes,
} from '../services/sameName';
import type { Chat, Message } from '../types/messenger';

const makeDirectChat = ({ name, ...overrides }: Partial<Chat> & { id: string; name: string }): Chat => ({
  title: name,
  avatar: '',
  type: 'dm',
  unreadCount: 0,
  circle: (overrides.circles?.[0] as Chat['circle'] | undefined) ?? 'social',
  messages: [],
  ...overrides,
});

const makeMessage = (overrides: Partial<Message> & { id: string; timestamp: string }): Message => ({
  senderId: 'u1',
  senderName: 'Olena',
  senderAvatar: '',
  text: 'Hello',
  status: 'sent',
  isSelf: false,
  type: 'text',
  ...overrides,
});

describe('sameName', () => {
  it('uses distinct ring names when colliding people belong to different rings', () => {
    const map = sameNameSuffixes([
      { id: 'a', name: 'Olena', ring: 'Work', handle: 'olena_k' },
      { id: 'b', name: 'Olena', ring: 'Friends', handle: 'olena_p' },
    ]);
    expect(map.get('a')).toBe('Work');
    expect(map.get('b')).toBe('Friends');
  });

  it('falls back to @handle when both people share the same ring', () => {
    const map = sameNameSuffixes([
      { id: 'a', name: 'Olena', ring: 'Work', handle: 'olena_k' },
      { id: 'b', name: 'Olena', ring: 'Work', handle: 'olena_p' },
    ]);
    expect(map.get('a')).toBe('@olena_k');
    expect(map.get('b')).toBe('@olena_p');
  });

  it('keeps the unique ring for one and uses @handle for the one without a ring', () => {
    const map = sameNameSuffixes([
      { id: 'a', name: 'Olena', ring: 'Work', handle: 'olena_k' },
      { id: 'b', name: 'Olena', handle: 'olena_p' },
    ]);
    expect(map.get('a')).toBe('Work');
    expect(map.get('b')).toBe('@olena_p');
  });

  it('normalizes handle in one helper and does not double the @ prefix', () => {
    expect(normalizeHandle(' @olena_k ')).toBe('olena_k');
    expect(normalizeHandle('olena_p')).toBe('olena_p');
    expect(normalizeHandle('')).toBe('');
    expect(handleTag('@olena_k')).toBe('@olena_k');
    expect(handleTag('olena_p')).toBe('@olena_p');
    expect(handleTag('   ')).toBe('');

    const map = sameNameSuffixes([
      { id: 'a', name: 'Olena', handle: '@olena_k' },
      { id: 'b', name: 'Olena', handle: 'olena_p' },
    ]);
    expect(map.get('a')).toBe('@olena_k');
    expect(map.get('b')).toBe('@olena_p');
  });

  it('folds case and whitespace so Olena and " olena " collide', () => {
    expect(foldName('Olena')).toBe(foldName(' olena '));
    const map = sameNameSuffixes([
      { id: 'a', name: 'Olena', handle: 'olena_k' },
      { id: 'b', name: ' olena ', handle: 'olena_p' },
    ]);
    expect(map.get('a')).toBe('@olena_k');
    expect(map.get('b')).toBe('@olena_p');
  });

  it('returns an empty map for a single person or the same id listed twice', () => {
    expect(sameNameSuffixes([{ id: 'a', name: 'Olena', handle: 'olena_k' }]).size).toBe(0);
    expect(
      sameNameSuffixes([
        { id: 'a', name: 'Olena', handle: 'olena_k', ring: 'Work' },
        { id: 'a', name: 'Olena', handle: 'olena_k' },
      ]).size,
    ).toBe(0);
  });

  it('omits colliding entries that have neither a unique ring nor a handle', () => {
    const map = sameNameSuffixes([
      { id: 'a', name: 'Olena' },
      { id: 'b', name: 'Olena', ring: '' },
    ]);
    expect(map.size).toBe(0);
  });

  it('ringOfPeer returns circle from non-archived direct chat for that peer and ignores archived or group chats', () => {
    const chats: Chat[] = [
      makeDirectChat({
        id: 'dm-1',
        name: 'Olena',
        circle: 'close',
        members: [
          { id: 'u1', name: 'Olena', handle: 'olena_k', avatar: '', role: 'member' as const },
          { id: 'me', name: 'Me', handle: 'me', avatar: '', isMe: true, role: 'member' as const },
        ],
      }),
      makeDirectChat({
        id: 'dm-2',
        name: 'Olena',
        circles: ['work'],
        members: [{ id: 'u2', name: 'Olena', handle: 'olena_p', avatar: '', role: 'member' as const }],
      }),
      makeDirectChat({
        id: 'dm-3',
        name: 'Olena',
        circle: 'close',
        archived: true,
        members: [{ id: 'u3', name: 'Olena', handle: 'olena_a', avatar: '', role: 'member' as const }],
      }),
      {
        ...makeDirectChat({ id: 'grp-1', name: 'Group', circle: 'friends' }),
        type: 'group',
        members: [{ id: 'u4', name: 'Olena', handle: 'olena_g', avatar: '', role: 'member' as const }],
      },
    ];

    expect(ringOfPeer(chats, 'u1')).toBe('close');
    expect(ringOfPeer(chats, 'u2')).toBe('work');
    expect(ringOfPeer(chats, 'u3')).toBeUndefined();
    expect(ringOfPeer(chats, 'u4')).toBeUndefined();
    expect(ringOfPeer(chats, 'unknown')).toBeUndefined();
  });

  it('personKey returns the same peer id for a DM row and a group member row of the same person', () => {
    const dmChat = makeDirectChat({
      id: 'dm-olena',
      name: 'Olena',
      handle: 'olena_k',
      members: [
        { id: 'u1', name: 'Olena', handle: 'olena_k', avatar: '', role: 'member' as const },
        { id: 'me', name: 'Me', handle: 'me', avatar: '', isMe: true, role: 'member' as const },
      ],
    });
    const dmRow = {
      key: 'chat:dm-olena',
      chat: dmChat,
    };
    const memberRow = {
      key: 'member:grp-1:u1',
      member: { id: 'u1', name: 'Olena', handle: 'olena_k', avatar: '', role: 'member' as const },
    };

    expect(personKey(dmRow)).toBe('u1');
    expect(personKey(memberRow)).toBe('u1');
  });

  it('personKey returns undefined for a DM without a resolvable peer or a group chat and never falls back to chat.id', () => {
    const orphanDm = makeDirectChat({
      id: 'dm-orphan',
      name: 'Olena',
      handle: 'olena_k',
    });
    const selfOnlyDm = makeDirectChat({
      id: 'dm-self',
      name: 'Olena',
      members: [{ id: 'me', name: 'Me', handle: 'me', avatar: '', isMe: true, role: 'member' as const }],
    });
    const groupChat: Chat = {
      ...makeDirectChat({ id: 'grp-olena', name: 'Olena' }),
      type: 'group',
    };

    expect(personKey({ key: 'chat:dm-orphan', chat: orphanDm })).toBeUndefined();
    expect(personKey({ key: 'chat:dm-self', chat: selfOnlyDm })).toBeUndefined();
    expect(personKey({ key: 'chat:grp-olena', chat: groupChat })).toBeUndefined();
  });

  it('ringOfPeer uses the most recently active non-archived DM when a peer has several DMs with different circles', () => {
    const peerMember = { id: 'u1', name: 'Olena', handle: 'olena_k', avatar: '', role: 'member' as const };
    const olderDm = makeDirectChat({
      id: 'dm-older',
      name: 'Olena',
      circle: 'work',
      members: [peerMember],
      messages: [makeMessage({ id: 'm-1', timestamp: '2026-03-20T09:00:00Z' })],
    });
    const newerDm = makeDirectChat({
      id: 'dm-newer',
      name: 'Olena',
      circle: 'close',
      members: [peerMember],
      messages: [
        makeMessage({ id: 'm-2', timestamp: '2026-03-20T08:00:00Z' }),
        makeMessage({ id: 'm-3', timestamp: '2026-03-20T14:30:00Z' }),
      ],
    });
    const archivedNewestDm = makeDirectChat({
      id: 'dm-archived',
      name: 'Olena',
      circle: 'friends',
      archived: true,
      members: [peerMember],
      messages: [makeMessage({ id: 'm-4', timestamp: '2026-03-20T18:00:00Z' })],
    });

    expect(ringOfPeer([olderDm, newerDm, archivedNewestDm], 'u1')).toBe('close');
    expect(ringOfPeer([newerDm, olderDm, archivedNewestDm], 'u1')).toBe('close');
  });
});
