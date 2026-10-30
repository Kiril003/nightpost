import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render } from '@testing-library/react';
import type { CallSnapshot } from '../services/callEngine';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat } from '../types/messenger';

let snap: CallSnapshot;
vi.mock('../services/callEngine', async (orig) => {
  const real = await orig<typeof import('../services/callEngine')>();
  return {
    ...real,
    callEngine: {
      subscribe: () => () => undefined,
      getSnapshot: () => snap,
      decline: vi.fn(),
      accept: vi.fn(),
      hangup: vi.fn(),
      dataUsedBytes: vi.fn(async () => null),
    },
  };
});

vi.mock('../services/api', () => ({
  request: vi.fn(async () => {
    const err = new Error('Not found') as Error & { status: number };
    err.status = 404;
    throw err;
  }),
  ApiError: class extends Error {},
}));

const BASE: CallSnapshot = {
  resuming: false,
  state: 'active',
  callId: 'c1',
  peer: { peerNodeId: 'f0e1d2c3b4a59687', displayName: 'Марта', verified: true },
  media: 'audio',
  outgoing: true,
  micOn: true,
  cameraOn: false,
  hasCamera: false,
  localStream: null,
  remoteStream: null,
  startedAt: Date.now() - 65_000,
  stats: null,
  endedReason: null,
  stall: null,
  audioLevel: 'crystal',
  ladderPinned: false,
  videoDropped: false,
  radio: null,
  radioReason: null,
  linkNote: null,
  legs: [],
  roomMax: null,
  roomCrowded: false,
  speakerOn: true,
  served: null,
};

const chat = (id: string, peerNodeId: string): Chat => ({
  id,
  title: id,
  avatar: '',
  type: 'dm',
  peerNodeId,
  unreadCount: 0,
  circle: 'all',
  messages: [],
});

describe('CallOverlay: відповідь замість дзвінка', () => {
  afterEach(() => cleanup());

  it('іде в розмову з тим, хто дзвонить, а не в ту, що відкрита', async () => {
    const sent: Array<[string, string]> = [];
    useMessengerStore.setState({
      chats: [chat('open-chat', 'aaaa'), chat('marta-chat', 'f0e1d2c3b4a59687')],
      activeChatId: 'open-chat',
      sendMessage: (text: string) => {
        sent.push([useMessengerStore.getState().activeChatId, text]);
      },
    } as never);
    snap = { ...BASE, state: 'ringing', outgoing: false };
    const { CallOverlay } = await import('../components/messenger/CallOverlay');
    render(<CallOverlay />);
    fireEvent.click(document.querySelector('[data-decline-more]') as HTMLElement);
    fireEvent.click(document.querySelector('[data-decline-reply]') as HTMLElement);
    await vi.waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0][0]).toBe('marta-chat');
  });
});
