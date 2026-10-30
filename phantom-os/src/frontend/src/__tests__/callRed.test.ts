import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { audioCodecOrder, wantsRed } from '../services/callOpus';
import type { AudioLevel } from '../services/callOpus';
import { OpusLadder } from '../services/callLadder';
import type { LadderView } from '../services/callLadder';

const OPUS: RTCRtpCodec = { mimeType: 'audio/opus', clockRate: 48000, channels: 2 };
const RED: RTCRtpCodec = { mimeType: 'audio/red', clockRate: 48000, channels: 2 };
const DTMF: RTCRtpCodec = { mimeType: 'audio/telephone-event', clockRate: 48000 };
const CAPS = [OPUS, RED, DTMF];

const mimes = (codecs: RTCRtpCodec[]) => codecs.map((c) => c.mimeType);

describe('media-8: RED на сходинках, де канал уже губить пакети', () => {
  it('вузький і економний — RED попереду Opus', () => {
    expect(mimes(audioCodecOrder('narrow', CAPS))).toEqual(['audio/red', 'audio/opus', 'audio/telephone-event']);
    expect(mimes(audioCodecOrder('thrifty', CAPS))[0]).toBe('audio/red');
  });

  it('кришталевий і високий — Opus першим, RED позаду', () => {
    for (const level of ['crystal', 'full'] as AudioLevel[]) {
      expect(mimes(audioCodecOrder(level, CAPS)), level).toEqual(['audio/opus', 'audio/telephone-event', 'audio/red']);
      expect(wantsRed(level)).toBe(false);
    }
  });

  it('RED у браузері немає — порядок не чіпаємо', () => {
    expect(audioCodecOrder('narrow', [OPUS, DTMF])).toEqual([OPUS, DTMF]);
  });
});

describe('media-8: драбина перемикає RED новою пропозицією', () => {
  let setPrefs: ReturnType<typeof vi.fn>;
  let offers: number;

  const fakePc = () => {
    setPrefs = vi.fn();
    offers = 0;
    return {
      signalingState: 'stable',
      localDescription: { sdp: 'v=0' },
      getSenders: () => [],
      getTransceivers: () => [{ receiver: { track: { kind: 'audio' } }, setCodecPreferences: setPrefs }],
      createOffer: async () => {
        offers += 1;
        return { type: 'offer', sdp: 'v=0' };
      },
      setLocalDescription: async () => undefined,
    } as unknown as RTCPeerConnection;
  };

  const ladderAt = (audioLevel: AudioLevel) => {
    const pc = fakePc();
    let v: LadderView = {
      callId: 'c', outgoing: true, media: 'audio', hasCamera: false, audioLevel,
      ladderPinned: false, videoDropped: false, stats: null,
    };
    const ladder = new OpusLadder({
      pc: () => pc,
      localStream: () => null,
      view: () => v,
      patch: (p) => {
        v = { ...v, ...p };
      },
      armNote: () => undefined,
      sendOffer: () => undefined,
    });
    return { ladder, level: () => v.audioLevel };
  };

  const settle = async () => {
    for (let i = 0; i < 6; i++) await Promise.resolve();
  };

  beforeEach(() => {
    vi.stubGlobal('RTCRtpReceiver', { getCapabilities: () => ({ codecs: CAPS, headerExtensions: [] }) });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('спуск із високого на економний — пропозиція з RED першим', async () => {
    const { ladder, level } = ladderAt('full');
    for (let n = 0; n <= 2; n++) ladder.judgeLink(700, n * 20, n * 100);
    await settle();
    expect(level()).toBe('thrifty');
    expect(offers).toBe(1);
    expect(mimes(setPrefs.mock.calls.at(-1)?.[0] ?? [])[0]).toBe('audio/red');
  });

  it('підйом з економного на високий — нова пропозиція знімає RED з голови', async () => {
    const { ladder, level } = ladderAt('thrifty');
    for (let n = 0; n <= 8; n++) ladder.judgeLink(40, 0, n * 100);
    await settle();
    expect(level()).toBe('full');
    expect(offers).toBe(1);
    expect(mimes(setPrefs.mock.calls.at(-1)?.[0] ?? [])[0]).toBe('audio/opus');
  });

  it('підйом без зміни RED (вузький → економний) пропозиції не шле', async () => {
    const { ladder, level } = ladderAt('narrow');
    for (let n = 0; n <= 8; n++) ladder.judgeLink(40, 0, n * 100);
    await settle();
    expect(level()).toBe('thrifty');
    expect(offers).toBe(0);
  });
});
