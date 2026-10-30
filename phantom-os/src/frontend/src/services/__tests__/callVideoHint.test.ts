import { describe, expect, it, vi } from 'vitest';
import { applyVideoHint, capVideoSender, hintFor } from '../callVideo';

const makeTrack = () =>
  ({
    kind: 'video',
    contentHint: '',
    getSettings: () => ({ width: 1280, height: 720 }),
    applyConstraints: vi.fn(async () => undefined),
  }) as unknown as MediaStreamTrack;

const makeSender = (track: MediaStreamTrack) => {
  const setParameters = vi.fn(async () => undefined);
  return {
    sender: {
      track,
      getParameters: () => ({ encodings: [{}] }),
      setParameters,
    } as unknown as RTCRtpSender,
    setParameters,
  };
};

describe('callVideo camera hints and degradation preference', () => {
  it('maps facing mode to contentHint and degradation preference', () => {
    expect(hintFor('environment')).toEqual({
      contentHint: 'detail',
      degradation: 'maintain-resolution',
    });
    expect(hintFor('user')).toEqual({
      contentHint: 'motion',
      degradation: 'maintain-framerate',
    });
  });

  it('sets contentHint on a video track when applyVideoHint is called', () => {
    const track = makeTrack();
    applyVideoHint(track, 'environment');
    expect(track.contentHint).toBe('detail');
  });

  it('applies per-track degradationPreference in capVideoSender with balanced fallback', async () => {
    const envTrack = makeTrack();
    applyVideoHint(envTrack, 'environment');
    const { sender: envSender, setParameters: setEnvParams } = makeSender(envTrack);
    await capVideoSender(envSender);
    expect(setEnvParams).toHaveBeenCalledWith(
      expect.objectContaining({ degradationPreference: 'maintain-resolution' }),
    );

    const userTrack = makeTrack();
    applyVideoHint(userTrack, 'user');
    const { sender: userSender, setParameters: setUserParams } = makeSender(userTrack);
    await capVideoSender(userSender);
    expect(setUserParams).toHaveBeenCalledWith(
      expect.objectContaining({ degradationPreference: 'maintain-framerate' }),
    );

    const unhintedTrack = makeTrack();
    const { sender: plainSender, setParameters: setPlainParams } = makeSender(unhintedTrack);
    await capVideoSender(plainSender);
    expect(setPlainParams).toHaveBeenCalledWith(
      expect.objectContaining({ degradationPreference: 'balanced' }),
    );
  });
});
