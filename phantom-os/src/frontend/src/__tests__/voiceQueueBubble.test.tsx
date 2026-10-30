import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';

class FakeAudio {
  static last: FakeAudio | null = null;
  src = '';
  currentTime = 0;
  playbackRate = 1;
  duration = 0;
  onended: (() => void) | null = null;
  ontimeupdate: (() => void) | null = null;
  onerror: (() => void) | null = null;
  play = vi.fn(() => Promise.resolve());
  pause = vi.fn();
  constructor() {
    FakeAudio.last = this;
  }
}
vi.stubGlobal('Audio', FakeAudio);

import { UnifiedVoiceBubble } from '../components/messenger/UnifiedVoiceBubble';
import { useAudioPlaybackStore } from '../stores/audioPlaybackStore';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message } from '../types/messenger';

const T0 = Date.parse('2026-10-18T09:00:00.000Z');

const voice = (id: string, atMs: number): Message => ({
  id,
  senderId: 'peer-olena',
  senderName: 'Олена',
  senderAvatar: '',
  timestamp: new Date(atMs).toISOString(),
  type: 'voice',
  status: 'sent',
  voiceData: { duration: 4, waveform: [1, 2, 3], audioUrl: `blob:${id}` },
});

const CHAT = (messages: Message[]): Chat =>
  ({ id: 'c1', type: 'direct', title: 'Олена', messages, unreadCount: 0, circle: 'all', avatar: '' }) as unknown as Chat;

let prev: ReturnType<typeof useMessengerStore.getState>;

beforeEach(() => {
  prev = useMessengerStore.getState();
  localStorage.clear();
});

afterEach(() => {
  cleanup();
  useAudioPlaybackStore.getState().stop();
  useMessengerStore.setState(prev, true);
  localStorage.clear();
});

const flush = () =>
  act(async () => {
    for (let i = 0; i < 4; i++) await Promise.resolve();
  });

describe('media-25: голосові підряд у справжній бульбашці', () => {
  it('після першого голосового грає наступне з тієї ж розмови і позначає його «Далі»', async () => {
    const a = voice('va', T0);
    const b = voice('vb', T0 + 60_000);
    useMessengerStore.setState({ chats: [CHAT([a, b])], activeChatId: 'c1' });
    render(
      <>
        <UnifiedVoiceBubble msg={a} />
        <UnifiedVoiceBubble msg={b} />
      </>,
    );

    fireEvent.click(screen.getAllByTestId('voice-play')[0]);
    await flush();

    expect(useAudioPlaybackStore.getState().currentMsgId).toBe('va');
    expect(useAudioPlaybackStore.getState().queue.map((i) => i.id)).toEqual(['va', 'vb']);
    expect(screen.getByTestId('voice-next-up')).toBeTruthy();

    act(() => FakeAudio.last?.onended?.());
    await flush();
    expect(useAudioPlaybackStore.getState().currentMsgId).toBe('vb');
    expect(FakeAudio.last?.src).toBe('blob:vb');
  });

  it('голосове, чий звук ще не на пристрої, не грає саме', async () => {
    const a = voice('va', T0);
    const b: Message = { ...voice('vb', T0 + 60_000), voiceData: { duration: 4, waveform: [1], media: { blobId: 'not-here' } as never } };
    useMessengerStore.setState({ chats: [CHAT([a, b])], activeChatId: 'c1' });
    render(<UnifiedVoiceBubble msg={a} />);
    fireEvent.click(screen.getAllByTestId('voice-play')[0]);
    await flush();
    act(() => FakeAudio.last?.onended?.());
    await flush();
    expect(useAudioPlaybackStore.getState().currentMsgId).toBe('va');
  });
});
