import { describe, it, expect, beforeEach } from 'vitest';
import {
  LOOKALIKE_OK_KEY,
  isLookalikeOk,
  lookalikeOf,
  markLookalikeOk,
  normName,
} from '../services/money/lookalike';
import type { Chat, Message } from '../types/messenger';

const DAY_MS = 24 * 60 * 60 * 1000;
const NOW = 1_750_000_000_000;

function makeMsg(id: string, createdAt: number): Message {
  return {
    id,
    senderId: 'peer',
    senderName: 'Peer',
    senderAvatar: '',
    timestamp: new Date(createdAt).toISOString(),
    createdAt,
    type: 'text',
    content: 'hi',
  } as unknown as Message;
}

function makeDm(overrides: Record<string, unknown>): Chat {
  return {
    id: 'chat-default',
    title: 'Olek',
    type: 'direct',
    messages: [],
    ...overrides,
  } as unknown as Chat;
}

beforeEach(() => {
  localStorage.clear();
});

describe('lookalike service', () => {
  it('normalises "Олек", "olek" and "0lek" to the same key', () => {
    const kUk = normName('Олек');
    const kLat = normName('olek');
    const kLeet = normName('0lek');
    expect(kUk).toBe('olek');
    expect(kLat).toBe(kUk);
    expect(kLeet).toBe(kUk);
    expect(normName('  ✨ Олек!  ')).toBe(kUk);
  });

  it('1. flags a new dm "Olek" (node B, 2d old, no circle) against close chat "Олек" (node A, 200d old)', () => {
    const closeChat = makeDm({
      id: 'c-trusted',
      title: 'Олек',
      peerNodeId: 'node-a',
      circle: 'close',
      messages: [makeMsg('m1', NOW - 200 * DAY_MS)],
    });
    const newDm = makeDm({
      id: 'c-new',
      title: 'Olek',
      peerNodeId: 'node-b',
      messages: [makeMsg('m2', NOW - 2 * DAY_MS)],
    });

    expect(lookalikeOf(newDm, [closeChat, newDm], NOW)).toBe(closeChat);
  });

  it('2. flags "0lek" too', () => {
    const closeChat = makeDm({
      id: 'c-trusted',
      title: 'Олек',
      peerNodeId: 'node-a',
      circle: 'close',
      messages: [makeMsg('m1', NOW - 200 * DAY_MS)],
    });
    const newDm = makeDm({
      id: 'c-new',
      title: '0lek',
      peerNodeId: 'node-b',
      messages: [makeMsg('m2', NOW - 2 * DAY_MS)],
    });

    expect(lookalikeOf(newDm, [closeChat, newDm], NOW)).toBe(closeChat);
  });

  it('3. returns null when the new chat already has circle "friends"', () => {
    const closeChat = makeDm({
      id: 'c-trusted',
      title: 'Олек',
      peerNodeId: 'node-a',
      circle: 'close',
      messages: [makeMsg('m1', NOW - 200 * DAY_MS)],
    });
    const newDm = makeDm({
      id: 'c-new',
      title: 'Olek',
      peerNodeId: 'node-b',
      circle: 'friends',
      messages: [makeMsg('m2', NOW - 2 * DAY_MS)],
    });

    expect(lookalikeOf(newDm, [closeChat, newDm], NOW)).toBeNull();
  });

  it('4. returns null when the new chat first message is 40 days ago', () => {
    const closeChat = makeDm({
      id: 'c-trusted',
      title: 'Олек',
      peerNodeId: 'node-a',
      circle: 'close',
      messages: [makeMsg('m1', NOW - 200 * DAY_MS)],
    });
    const olderDm = makeDm({
      id: 'c-new',
      title: 'Olek',
      peerNodeId: 'node-b',
      messages: [makeMsg('m2', NOW - 40 * DAY_MS)],
    });

    expect(lookalikeOf(olderDm, [closeChat, olderDm], NOW)).toBeNull();
  });

  it('5. returns null when peerNodeId is the same', () => {
    const closeChat = makeDm({
      id: 'c-trusted',
      title: 'Олек',
      peerNodeId: 'node-a',
      circle: 'close',
      messages: [makeMsg('m1', NOW - 200 * DAY_MS)],
    });
    const samePeerDm = makeDm({
      id: 'c-new',
      title: 'Olek',
      peerNodeId: 'node-a',
      messages: [makeMsg('m2', NOW - 2 * DAY_MS)],
    });

    expect(lookalikeOf(samePeerDm, [closeChat, samePeerDm], NOW)).toBeNull();
  });

  it('6. returns null when there are no personal/close/family chats at all', () => {
    const workChat = makeDm({
      id: 'c-work',
      title: 'Олек',
      peerNodeId: 'node-a',
      circle: 'work',
      messages: [makeMsg('m1', NOW - 200 * DAY_MS)],
    });
    const newDm = makeDm({
      id: 'c-new',
      title: 'Olek',
      peerNodeId: 'node-b',
      messages: [makeMsg('m2', NOW - 2 * DAY_MS)],
    });

    expect(lookalikeOf(newDm, [workChat, newDm], NOW)).toBeNull();
  });

  it('persists dismissed peerNodeIds under LOOKALIKE_OK_KEY', () => {
    expect(isLookalikeOk('node-b')).toBe(false);
    markLookalikeOk('node-b');
    expect(isLookalikeOk('node-b')).toBe(true);
    expect(JSON.parse(localStorage.getItem(LOOKALIKE_OK_KEY) || '[]')).toEqual(['node-b']);
  });
});
