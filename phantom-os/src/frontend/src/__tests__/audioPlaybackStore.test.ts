import { beforeEach, describe, expect, it, vi } from 'vitest';
import { VOICE_PLAYED_KEY, type VoiceQueueItem } from '../services/voiceQueue';

let fake: FakeAudio | null = null;

class FakeAudio {
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
    fake = this;
  }
}

vi.stubGlobal('Audio', FakeAudio);

async function getStore() {
  const mod = await import('../stores/audioPlaybackStore');
  return mod.useAudioPlaybackStore;
}

describe('audioPlaybackStore queue playback', () => {
  beforeEach(() => {
    vi.resetModules();
    vi.stubGlobal('Audio', FakeAudio);
    fake = null;
    localStorage.clear();
  });

  it('plays two in a row when the first note ends', async () => {
    const useAudioPlaybackStore = await getStore();
    const q: VoiceQueueItem[] = [
      { id: 'A', url: 'a.ogg', durationSec: 3, ts: 0 },
      { id: 'B', url: 'b.ogg', durationSec: 4, ts: 60_000 },
    ];

    useAudioPlaybackStore.getState().play('A', 'a.ogg', 3, q);
    await Promise.resolve();

    expect(fake).not.toBeNull();
    fake!.onended?.();

    expect(fake!.src.endsWith('b.ogg')).toBe(true);
    expect(useAudioPlaybackStore.getState().currentMsgId).toBe('B');

    await Promise.resolve();
    expect(useAudioPlaybackStore.getState().isPlaying).toBe(true);
  });

  it('skips already played notes in the queue', async () => {
    const useAudioPlaybackStore = await getStore();
    localStorage.setItem(VOICE_PLAYED_KEY, JSON.stringify(['B']));
    const q: VoiceQueueItem[] = [
      { id: 'A', url: 'a.ogg', durationSec: 3, ts: 0 },
      { id: 'B', url: 'b.ogg', durationSec: 4, ts: 60_000 },
      { id: 'C', url: 'c.ogg', durationSec: 5, ts: 120_000 },
    ];

    useAudioPlaybackStore.getState().play('A', 'a.ogg', 3, q);
    await Promise.resolve();

    fake!.onended?.();

    expect(fake!.src.endsWith('c.ogg')).toBe(true);
    expect(useAudioPlaybackStore.getState().currentMsgId).toBe('C');

    await Promise.resolve();
    expect(useAudioPlaybackStore.getState().isPlaying).toBe(true);
  });

  it('stops when the gap to the next note is greater than 10 minutes', async () => {
    const useAudioPlaybackStore = await getStore();
    const q: VoiceQueueItem[] = [
      { id: 'A', url: 'a.ogg', durationSec: 3, ts: 0 },
      { id: 'B', url: 'b.ogg', durationSec: 4, ts: 11 * 60 * 1000 },
    ];

    useAudioPlaybackStore.getState().play('A', 'a.ogg', 3, q);
    await Promise.resolve();

    fake!.onended?.();
    await Promise.resolve();

    expect(useAudioPlaybackStore.getState().isPlaying).toBe(false);
    expect(useAudioPlaybackStore.getState().currentMsgId).toBe('A');
    expect(fake!.play).toHaveBeenCalledTimes(1);
  });

  it('keeps the single-note behaviour when played without a queue', async () => {
    const useAudioPlaybackStore = await getStore();

    useAudioPlaybackStore.getState().play('A', 'a.ogg');
    await Promise.resolve();

    fake!.onended?.();
    await Promise.resolve();

    expect(useAudioPlaybackStore.getState().isPlaying).toBe(false);
    expect(useAudioPlaybackStore.getState().currentMsgId).toBe('A');
    expect(fake!.play).toHaveBeenCalledTimes(1);
  });

  it('clears the queue on stop', async () => {
    const useAudioPlaybackStore = await getStore();
    const q: VoiceQueueItem[] = [
      { id: 'A', url: 'a.ogg', durationSec: 3, ts: 0 },
      { id: 'B', url: 'b.ogg', durationSec: 4, ts: 60_000 },
    ];

    useAudioPlaybackStore.getState().play('A', 'a.ogg', 3, q);
    await Promise.resolve();
    expect(useAudioPlaybackStore.getState().queue).toEqual(q);

    useAudioPlaybackStore.getState().stop();
    expect(useAudioPlaybackStore.getState().queue).toEqual([]);
  });
});
