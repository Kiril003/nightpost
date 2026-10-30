import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CATCHUP_RATE, RadioLink, catchUpSeconds, squeezeSilence } from '../services/callRadio';

const RATE = 8000;
const tone = (s: number) => Float32Array.from({ length: s * RATE }, (_, i) => 0.3 * Math.sin((2 * Math.PI * 440 * i) / RATE));
const quiet = (s: number) => new Float32Array(s * RATE);
const join = (...parts: Float32Array[]) => {
  const out = new Float32Array(parts.reduce((n, p) => n + p.length, 0));
  parts.reduce((at, p) => (out.set(p, at), at + p.length), 0);
  return out;
};

describe('media-28: догін хвоста рації', () => {
  it('9 с хвоста, з них 3 с тиші, доганяються за ≤ 5 с', () => {
    const backlog = join(tone(3), quiet(3), tone(3));
    expect(catchUpSeconds(backlog, RATE)).toBeLessThanOrEqual(5);
    expect(squeezeSilence(backlog, RATE).length).toBeGreaterThanOrEqual(6 * RATE);
  });

  it('коротку паузу між словами не чіпаємо', () => {
    const speech = join(tone(1), quiet(0.2), tone(1));
    expect(squeezeSilence(speech, RATE).length).toBe(speech.length);
  });
});

describe('media-28: рація віддає хвіст, а не обриває його', () => {
  const sources: Array<{ stop: ReturnType<typeof vi.fn> }> = [];
  const audios: Array<{ playbackRate: number; preservesPitch: boolean; muted: boolean; onended: (() => void) | null; pause: ReturnType<typeof vi.fn> }> = [];
  const chunk = join(tone(1), quiet(1), tone(1));

  class FakeCtx {
    state = 'running';
    currentTime = 0;
    destination = {};
    decodeAudioData = async () => ({
      sampleRate: RATE,
      length: chunk.length,
      duration: chunk.length / RATE,
      getChannelData: () => chunk,
    });
    createBufferSource() {
      const src = { buffer: null, connect: vi.fn(), start: vi.fn(), stop: vi.fn() };
      sources.push(src);
      return src;
    }
    resume = async () => undefined;
    close = async () => undefined;
  }

  class FakeAudio {
    playbackRate = 1;
    preservesPitch = false;
    muted = false;
    onended: (() => void) | null = null;
    onerror: (() => void) | null = null;
    pause = vi.fn();
    constructor(public src: string) {
      audios.push(this);
    }
    play = async () => undefined;
  }

  beforeEach(() => {
    sources.length = 0;
    audios.length = 0;
    vi.stubGlobal('AudioContext', FakeCtx);
    vi.stubGlobal('Audio', FakeAudio);
    URL.createObjectURL = vi.fn(() => 'blob:catch');
    URL.revokeObjectURL = vi.fn();
  });

  afterEach(() => vi.unstubAllGlobals());

  it('доріжка повернулась — заплановане зупинено, хвіст грає 1,25× без зміни висоти, потім живий звук', async () => {
    const link = new RadioLink(() => undefined);
    link.start({ callId: 'c' }, null);
    await link.receive(0, 'AAAA');
    await link.receive(1, 'AAAA');
    expect(sources).toHaveLength(2);

    const done = vi.fn();
    expect(link.catchUp(done)).toBe(true);
    sources.forEach((s) => expect(s.stop).toHaveBeenCalled());
    expect(audios).toHaveLength(1);
    expect(audios[0].playbackRate).toBe(CATCHUP_RATE);
    expect(audios[0].preservesPitch).toBe(true);
    expect(done).not.toHaveBeenCalled();

    audios[0].onended?.();
    expect(done).toHaveBeenCalledTimes(1);
  });

  it('хвоста немає — одразу живий звук', () => {
    const link = new RadioLink(() => undefined);
    link.start({ callId: 'c' }, null);
    expect(link.catchUp(vi.fn())).toBe(false);
    expect(audios).toHaveLength(0);
  });
});

describe('media-28: рушій при поверненні доріжки', () => {
  it('рація з хвостом — catchingUp, поки не дограє', async () => {
    const { callEngine } = await import('../services/callEngine');
    let finish: () => void = () => undefined;
    const link = {
      catchUp: vi.fn((onDone: () => void) => {
        finish = onDone;
        return true;
      }),
      stop: vi.fn(),
    };
    Reflect.set(callEngine, 'radio', link);
    (Reflect.get(callEngine, 'exitRadio') as () => void).call(callEngine);
    expect(link.catchUp).toHaveBeenCalled();
    expect(link.stop).not.toHaveBeenCalled();
    expect(callEngine.getSnapshot().catchingUp).toBe(true);
    finish();
    expect(callEngine.getSnapshot().catchingUp).toBe(false);
  });
});
